package com.ai.assistance.operit.integrations.http

import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

fun interface PageInfoProvider {
    suspend fun getCurrentPageInfo(): PageInfoProviderResult
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
    fun matches(session: NanoHTTPD.IHTTPSession): Boolean =
        session.method == NanoHTTPD.Method.GET && session.uri == PATH

    fun handle(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val unauthorized = requireBearerToken(session)
        if (unauthorized != null) {
            return unauthorized
        }

        if (session.parameters.isNotEmpty()) {
            return errorResponse(NanoHTTPD.Response.Status.BAD_REQUEST, INVALID_REQUEST)
        }

        val providerResult = try {
            runBlocking(Dispatchers.IO) { pageInfoProvider.getCurrentPageInfo() }
        } catch (_: Exception) {
            PageInfoProviderResult.ExecutionError
        }

        return when (providerResult) {
            is PageInfoProviderResult.Available -> {
                val bounded = boundUtf8(providerResult.pageInfo, PAGE_INFO_MAX_UTF8_BYTES)
                jsonResponse(
                    NanoHTTPD.Response.Status.OK,
                    PageInfoSuccessResponse(
                        pageInfo = bounded.value,
                        truncated = bounded.truncated
                    )
                )
            }

            PageInfoProviderResult.Unavailable ->
                errorResponse(NanoHTTPD.Response.Status.SERVICE_UNAVAILABLE, UNAVAILABLE)

            PageInfoProviderResult.PermissionRequired ->
                errorResponse(NanoHTTPD.Response.Status.FORBIDDEN, PERMISSION_REQUIRED)

            PageInfoProviderResult.ExecutionError ->
                errorResponse(NanoHTTPD.Response.Status.INTERNAL_ERROR, EXECUTION_ERROR)
        }
    }

    private fun errorResponse(
        status: NanoHTTPD.Response.Status,
        error: String
    ): NanoHTTPD.Response = jsonResponse(status, PageInfoErrorResponse(error = error))

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
        private val json = Json
    }
}

@Serializable
private data class PageInfoSuccessResponse(
    val ok: Boolean = true,
    val pageInfo: String,
    val truncated: Boolean
)

@Serializable
private data class PageInfoErrorResponse(
    val ok: Boolean = false,
    val error: String
)
