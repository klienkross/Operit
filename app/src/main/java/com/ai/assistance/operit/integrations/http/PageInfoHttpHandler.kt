package com.ai.assistance.operit.integrations.http

import com.ai.assistance.operit.util.AppLogger
import fi.iki.elonen.NanoHTTPD
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

fun interface PageInfoProvider {
    suspend fun getCurrentPageInfo(requestId: Long): PageInfoProviderResult
}

sealed interface PageInfoProviderResult {
    data class Available(val pageInfo: String) : PageInfoProviderResult

    data object Unavailable : PageInfoProviderResult

    data object PermissionRequired : PageInfoProviderResult

    data object ExecutionError : PageInfoProviderResult
}

internal class PageInfoHttpHandler(
    private val pageInfoProvider: PageInfoProvider,
    private val requireBearerToken: (NanoHTTPD.IHTTPSession) -> NanoHTTPD.Response?
) {
    private val requestIds = AtomicLong(0L)

    fun matches(session: NanoHTTPD.IHTTPSession): Boolean =
        session.method == NanoHTTPD.Method.GET && session.uri == PATH

    fun handle(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val requestId = requestIds.incrementAndGet()
        val requestStartedAt = monotonicMillis()
        AppLogger.i(
            PAGE_INFO_PROBE_TAG,
            "component=PageInfoHttpHandler event=request_start request_id=$requestId " +
                "thread=${Thread.currentThread().name} interrupted=${Thread.currentThread().isInterrupted}"
        )
        AppLogger.i(PAGE_INFO_PROBE_TAG, "component=PageInfoHttpHandler event=handler_entered request_id=$requestId")

        val unauthorized = requireBearerToken(session)
        if (unauthorized != null) {
            logResponse(requestId, requestStartedAt, 401, "auth_failed")
            return unauthorized
        }
        AppLogger.i(PAGE_INFO_PROBE_TAG, "component=PageInfoHttpHandler event=auth_passed request_id=$requestId")

        if (session.parameters.isNotEmpty()) {
            logResponse(requestId, requestStartedAt, 400, "invalid_request")
            return errorResponse(NanoHTTPD.Response.Status.BAD_REQUEST, INVALID_REQUEST)
        }

        val providerStartedAt = monotonicMillis()
        AppLogger.i(
            PAGE_INFO_PROBE_TAG,
            "component=PageInfoHttpHandler event=provider_call_start request_id=$requestId " +
                "thread=${Thread.currentThread().name}"
        )
        val providerResult = try {
            runBlocking(Dispatchers.IO) { pageInfoProvider.getCurrentPageInfo(requestId) }
        } catch (e: Exception) {
            AppLogger.e(
                PAGE_INFO_PROBE_TAG,
                "component=PageInfoHttpHandler event=provider_call_failure request_id=$requestId " +
                    "elapsed_ms=${monotonicMillis() - providerStartedAt} " +
                    "thread=${Thread.currentThread().name} interrupted=${Thread.currentThread().isInterrupted}",
                e
            )
            PageInfoProviderResult.ExecutionError
        }
        AppLogger.i(
            PAGE_INFO_PROBE_TAG,
            "component=PageInfoHttpHandler event=provider_call_end request_id=$requestId " +
                "result_type=${providerResult.javaClass.name} " +
                "elapsed_ms=${monotonicMillis() - providerStartedAt} " +
                "thread=${Thread.currentThread().name} interrupted=${Thread.currentThread().isInterrupted}"
        )

        val (response, status) = when (providerResult) {
            is PageInfoProviderResult.Available -> {
                val bounded = boundUtf8(providerResult.pageInfo, PAGE_INFO_MAX_UTF8_BYTES)
                jsonResponse(
                    NanoHTTPD.Response.Status.OK,
                    PageInfoSuccessResponse(
                        ok = true,
                        pageInfo = bounded.value,
                        truncated = bounded.truncated
                    )
                ) to 200
            }

            PageInfoProviderResult.Unavailable ->
                errorResponse(NanoHTTPD.Response.Status.SERVICE_UNAVAILABLE, UNAVAILABLE) to 503

            PageInfoProviderResult.PermissionRequired ->
                errorResponse(NanoHTTPD.Response.Status.FORBIDDEN, PERMISSION_REQUIRED) to 403

            PageInfoProviderResult.ExecutionError ->
                errorResponse(NanoHTTPD.Response.Status.INTERNAL_ERROR, EXECUTION_ERROR) to 500
        }
        logResponse(requestId, requestStartedAt, status, "response_emitted")
        return response
    }

    private fun logResponse(requestId: Long, startedAt: Long, status: Int, event: String) {
        AppLogger.i(
            PAGE_INFO_PROBE_TAG,
            "component=PageInfoHttpHandler event=$event request_id=$requestId http_status=$status " +
                "total_elapsed_ms=${monotonicMillis() - startedAt} " +
                "thread=${Thread.currentThread().name} interrupted=${Thread.currentThread().isInterrupted}"
        )
    }

    private fun errorResponse(
        status: NanoHTTPD.Response.Status,
        error: String
    ): NanoHTTPD.Response = jsonResponse(
        status,
        PageInfoErrorResponse(ok = false, code = error)
    )

    private inline fun <reified T> jsonResponse(
        status: NanoHTTPD.Response.Status,
        body: T
    ): NanoHTTPD.Response = NanoHTTPD.newFixedLengthResponse(
        status,
        JSON_MIME_TYPE,
        json.encodeToString(body)
    )

    private fun boundUtf8(value: String, maxBytes: Int): BoundedText {
        val encoded = value.toByteArray(Charsets.UTF_8)
        if (encoded.size <= maxBytes) {
            return BoundedText(value, truncated = false)
        }

        val bounded = StringBuilder()
        var byteCount = 0
        var index = 0
        while (index < value.length) {
            val codePoint = value.codePointAt(index)
            val codePointText = String(Character.toChars(codePoint))
            val codePointBytes = codePointText.toByteArray(Charsets.UTF_8).size
            if (byteCount + codePointBytes > maxBytes) {
                break
            }
            bounded.append(codePointText)
            byteCount += codePointBytes
            index += Character.charCount(codePoint)
        }
        return BoundedText(bounded.toString(), truncated = true)
    }

    private data class BoundedText(val value: String, val truncated: Boolean)

    companion object {
        const val PATH = "/api/ui/page-info"
        const val PAGE_INFO_MAX_UTF8_BYTES = 32 * 1024

        private const val JSON_MIME_TYPE = "application/json; charset=utf-8"
        private const val INVALID_REQUEST = "invalid_request"
        private const val UNAVAILABLE = "unavailable"
        private const val PERMISSION_REQUIRED = "permission_required"
        private const val EXECUTION_ERROR = "execution_error"
        private const val PAGE_INFO_PROBE_TAG = "PageInfoProbe"
        private val json = Json

        private fun monotonicMillis(): Long = System.nanoTime() / 1_000_000L
    }
}

@Serializable
private data class PageInfoSuccessResponse(
    val ok: Boolean,
    val pageInfo: String,
    val truncated: Boolean
)

@Serializable
private data class PageInfoErrorResponse(
    val ok: Boolean,
    val code: String
)
