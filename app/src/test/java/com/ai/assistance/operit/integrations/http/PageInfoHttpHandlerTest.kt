package com.ai.assistance.operit.integrations.http

import com.ai.assistance.operit.core.tools.SimplifiedUINode
import com.ai.assistance.operit.core.tools.UIPageResultData
import com.ai.assistance.operit.data.model.ToolResult
import com.ai.assistance.operit.util.AppLogger
import fi.iki.elonen.NanoHTTPD
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import kotlin.system.measureTimeMillis

class PageInfoHttpHandlerTest {
    private var previousSystemLogEnabled = true

    @Before
    fun disableAndroidSystemLog() {
        previousSystemLogEnabled = AppLogger.enableSystemLog
        AppLogger.enableSystemLog = false
    }

    @After
    fun restoreAndroidSystemLog() {
        AppLogger.enableSystemLog = previousSystemLogEnabled
    }

    @Test
    fun `authenticated GET returns current page info`() {
        val authenticator = ExternalHttpBearerAuthenticator { "secret-token" }
        val handler = PageInfoHttpHandler(
            pageInfoProvider = PageInfoProvider {
                PageInfoProviderResult.Available("Current Application: com.example")
            },
            requireBearerToken = authenticator::requireBearerToken
        )

        val response = handler.handle(
            getSession(headers = mapOf("Authorization" to "Bearer secret-token"))
        )
        val body = response.jsonBody()

        assertEquals(NanoHTTPD.Response.Status.OK, response.status)
        assertTrue(body.getValue("ok").jsonPrimitive.boolean)
        assertEquals(
            "Current Application: com.example",
            body.getValue("pageInfo").jsonPrimitive.content
        )
        assertFalse(body.getValue("truncated").jsonPrimitive.boolean)
        assertEquals(setOf("ok", "pageInfo", "truncated"), body.keys)
    }

    @Test
    fun `missing bearer token uses existing unauthorized semantics`() {
        var providerCalled = false
        val authenticator = ExternalHttpBearerAuthenticator { "secret-token" }
        val handler = PageInfoHttpHandler(
            pageInfoProvider = PageInfoProvider {
                providerCalled = true
                PageInfoProviderResult.Available("must not be read")
            },
            requireBearerToken = authenticator::requireBearerToken
        )

        val response = handler.handle(getSession())
        val body = response.jsonBody()

        assertEquals(NanoHTTPD.Response.Status.UNAUTHORIZED, response.status)
        assertFalse(body.getValue("success").jsonPrimitive.boolean)
        assertEquals("Unauthorized", body.getValue("error").jsonPrimitive.content)
        assertFalse(providerCalled)
    }

    @Test
    fun `oversized page info is bounded and marked truncated`() {
        val handler = authenticatedHandler("a".repeat(PageInfoHttpHandler.PAGE_INFO_MAX_UTF8_BYTES + 1))

        val body = handler.handle(getSession()).jsonBody()
        val pageInfo = body.getValue("pageInfo").jsonPrimitive.content

        assertEquals(PageInfoHttpHandler.PAGE_INFO_MAX_UTF8_BYTES, pageInfo.toByteArray().size)
        assertTrue(body.getValue("truncated").jsonPrimitive.boolean)
    }

    @Test
    fun `multibyte page info never crosses UTF-8 byte bound`() {
        val handler = authenticatedHandler("界".repeat(PageInfoHttpHandler.PAGE_INFO_MAX_UTF8_BYTES))

        val body = handler.handle(getSession()).jsonBody()
        val pageInfo = body.getValue("pageInfo").jsonPrimitive.content
        val pageInfoBytes = pageInfo.toByteArray(StandardCharsets.UTF_8)

        assertTrue(pageInfoBytes.size <= PageInfoHttpHandler.PAGE_INFO_MAX_UTF8_BYTES)
        assertEquals(0, pageInfoBytes.size % "界".toByteArray(StandardCharsets.UTF_8).size)
        assertTrue(body.getValue("truncated").jsonPrimitive.boolean)
    }

    @Test
    fun `provider failures use stable errors and status codes`() {
        val cases = listOf(
            PageInfoProviderResult.Unavailable to
                (NanoHTTPD.Response.Status.SERVICE_UNAVAILABLE to "unavailable"),
            PageInfoProviderResult.PermissionRequired to
                (NanoHTTPD.Response.Status.FORBIDDEN to "permission_required"),
            PageInfoProviderResult.ExecutionError to
                (NanoHTTPD.Response.Status.INTERNAL_ERROR to "execution_error")
        )

        cases.forEach { (providerResult, expected) ->
            val handler = PageInfoHttpHandler(
                pageInfoProvider = PageInfoProvider { providerResult },
                requireBearerToken = { null }
            )

            val response = handler.handle(getSession())
            val body = response.jsonBody()

            assertEquals(expected.first, response.status)
            assertFalse(body.getValue("ok").jsonPrimitive.boolean)
            assertEquals(expected.second, body.getValue("code").jsonPrimitive.content)
            assertNull(body["pageInfo"])
            assertEquals(setOf("ok", "code"), body.keys)
        }
    }

    @Test
    fun `provider timeout returns unavailable instead of waiting for the client`() {
        val provider = OperitPageInfoProvider(
            readPageInfo = { CompletableDeferred<ToolResult>().await() },
            timeoutMillis = 25L
        )
        val handler = PageInfoHttpHandler(
            pageInfoProvider = provider,
            requireBearerToken = { null }
        )

        val response = handler.handle(getSession())
        val body = response.jsonBody()

        assertEquals(NanoHTTPD.Response.Status.SERVICE_UNAVAILABLE, response.status)
        assertEquals(false, body.getValue("ok").jsonPrimitive.boolean)
        assertEquals("unavailable", body.getValue("code").jsonPrimitive.content)
        assertEquals(setOf("ok", "code"), body.keys)
        provider.close()
    }

    @Test
    fun `interrupt ignoring read leaves worker occupied without queueing more work`() {
        val releaseFirstRead = CountDownLatch(1)
        val readCalls = AtomicInteger(0)
        val hostResult = UIPageResultData(
            packageName = "com.example",
            activityName = ".MainActivity",
            uiElements = SimplifiedUINode(
                className = "FrameLayout",
                text = null,
                contentDesc = null,
                resourceId = null,
                bounds = "[0,0][10,10]",
                isClickable = false,
                children = emptyList()
            )
        )
        val provider = OperitPageInfoProvider(timeoutMillis = 250L) {
            withContext(Dispatchers.IO) {
                if (readCalls.incrementAndGet() == 1) {
                    while (releaseFirstRead.count > 0) {
                        try {
                            releaseFirstRead.await()
                        } catch (_: InterruptedException) {
                            // Models a dispatched backend that ignores cancellation/interrupt.
                        }
                    }
                }
                ToolResult("get_page_info", true, hostResult)
            }
        }
        val handler = PageInfoHttpHandler(provider, requireBearerToken = { null })
        val requestExecutor = Executors.newSingleThreadExecutor()

        try {
            val firstElapsed = measureTimeMillis {
                val response = requestExecutor
                    .submit<NanoHTTPD.Response> { handler.handle(getSession()) }
                    .get(1, TimeUnit.SECONDS)
                assertUnavailable(response)
            }
            assertTrue("first request was not bounded: ${firstElapsed}ms", firstElapsed < 1_000L)

            val secondElapsed = measureTimeMillis {
                assertUnavailable(handler.handle(getSession()))
            }
            assertTrue("occupied worker did not reject immediately: ${secondElapsed}ms", secondElapsed < 100L)
            assertEquals(1, readCalls.get())

            releaseFirstRead.countDown()
            val deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            var response: NanoHTTPD.Response
            do {
                response = handler.handle(getSession())
                if (response.status == NanoHTTPD.Response.Status.OK) break
                Thread.sleep(10L)
            } while (System.nanoTime() < deadlineNanos)

            assertEquals(NanoHTTPD.Response.Status.OK, response.status)
            assertEquals(2, readCalls.get())
        } finally {
            releaseFirstRead.countDown()
            requestExecutor.shutdownNow()
            provider.close()
        }
    }

    @Test
    fun `only fixed parameterless GET route can reach provider`() {
        var providerCalls = 0
        val handler = PageInfoHttpHandler(
            pageInfoProvider = PageInfoProvider {
                providerCalls += 1
                PageInfoProviderResult.Available("page")
            },
            requireBearerToken = { null }
        )
        val post = session(NanoHTTPD.Method.POST, PageInfoHttpHandler.PATH)
        val arbitraryTool = session(NanoHTTPD.Method.GET, "/api/tools/call")
        val scriptedGet = getSession(parameters = mapOf("script" to listOf("tap(1, 2)")))

        assertFalse(handler.matches(post))
        assertFalse(handler.matches(arbitraryTool))
        assertTrue(handler.matches(scriptedGet))
        val rejected = handler.handle(scriptedGet)

        assertEquals(NanoHTTPD.Response.Status.BAD_REQUEST, rejected.status)
        assertEquals("invalid_request", rejected.jsonBody().getValue("code").jsonPrimitive.content)
        assertEquals(0, providerCalls)
    }

    private fun authenticatedHandler(pageInfo: String): PageInfoHttpHandler =
        PageInfoHttpHandler(
            pageInfoProvider = PageInfoProvider { PageInfoProviderResult.Available(pageInfo) },
            requireBearerToken = { null }
        )

    private fun assertUnavailable(response: NanoHTTPD.Response) {
        val body = response.jsonBody()
        assertEquals(NanoHTTPD.Response.Status.SERVICE_UNAVAILABLE, response.status)
        assertFalse(body.getValue("ok").jsonPrimitive.boolean)
        assertEquals("unavailable", body.getValue("code").jsonPrimitive.content)
        assertEquals(setOf("ok", "code"), body.keys)
    }

    private fun getSession(
        parameters: Map<String, List<String>> = emptyMap(),
        headers: Map<String, String> = emptyMap()
    ): NanoHTTPD.IHTTPSession =
        session(NanoHTTPD.Method.GET, PageInfoHttpHandler.PATH, parameters, headers)

    private fun session(
        method: NanoHTTPD.Method,
        uri: String,
        parameters: Map<String, List<String>> = emptyMap(),
        headers: Map<String, String> = emptyMap()
    ): NanoHTTPD.IHTTPSession {
        val session = mock<NanoHTTPD.IHTTPSession>()
        whenever(session.method).thenReturn(method)
        whenever(session.uri).thenReturn(uri)
        whenever(session.parameters).thenReturn(parameters)
        whenever(session.headers).thenReturn(headers)
        return session
    }

    private fun NanoHTTPD.Response.jsonBody() =
        Json.parseToJsonElement(data.reader(StandardCharsets.UTF_8).readText()).jsonObject
}
