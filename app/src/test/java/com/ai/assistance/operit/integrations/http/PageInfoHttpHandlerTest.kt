package com.ai.assistance.operit.integrations.http

import fi.iki.elonen.NanoHTTPD
import java.nio.charset.StandardCharsets
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class PageInfoHttpHandlerTest {
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
            assertEquals(expected.second, body.getValue("error").jsonPrimitive.content)
            assertNull(body["pageInfo"])
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
        assertEquals("invalid_request", rejected.jsonBody().getValue("error").jsonPrimitive.content)
        assertEquals(0, providerCalls)
    }

    private fun authenticatedHandler(pageInfo: String): PageInfoHttpHandler =
        PageInfoHttpHandler(
            pageInfoProvider = PageInfoProvider { PageInfoProviderResult.Available(pageInfo) },
            requireBearerToken = { null }
        )

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
