package com.ai.assistance.operit.integrations.http

import com.ai.assistance.operit.integrations.externalchat.ExternalChatResult
import fi.iki.elonen.NanoHTTPD
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal class ExternalHttpBearerAuthenticator(
    private val bearerToken: () -> String
) {
    fun requireBearerToken(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response? {
        val expectedToken = bearerToken().trim()
        if (expectedToken.isBlank()) {
            return unauthorized("Bearer token not configured")
        }

        val authorization = session.headers.entries.firstOrNull {
            it.key.equals("authorization", ignoreCase = true)
        }?.value?.trim().orEmpty()
        val actualToken = if (authorization.startsWith("Bearer ", ignoreCase = true)) {
            authorization.substringAfter(' ').trim()
        } else {
            ""
        }

        return if (actualToken == expectedToken) null else unauthorized("Unauthorized")
    }

    private fun unauthorized(error: String): NanoHTTPD.Response =
        NanoHTTPD.newFixedLengthResponse(
            NanoHTTPD.Response.Status.UNAUTHORIZED,
            JSON_MIME_TYPE,
            json.encodeToString(ExternalChatResult(success = false, error = error))
        ).apply {
            addHeader("Access-Control-Allow-Origin", "*")
            addHeader("Access-Control-Allow-Methods", "GET, POST, PATCH, DELETE, OPTIONS")
            addHeader("Access-Control-Allow-Headers", "Authorization, Content-Type, Accept")
            addHeader("Access-Control-Max-Age", "3600")
        }

    private companion object {
        const val JSON_MIME_TYPE = "application/json; charset=utf-8"
        val json = Json { ignoreUnknownKeys = true }
    }
}
