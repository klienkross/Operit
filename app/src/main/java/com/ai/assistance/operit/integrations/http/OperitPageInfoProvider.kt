package com.ai.assistance.operit.integrations.http

import android.content.Context
import com.ai.assistance.operit.core.tools.UIPageResultData
import com.ai.assistance.operit.core.tools.defaultTool.ToolGetter
import com.ai.assistance.operit.data.model.AITool
import com.ai.assistance.operit.data.model.ToolResult
import java.net.ConnectException
import java.util.Locale
import kotlinx.coroutines.withTimeoutOrNull

internal class OperitPageInfoProvider(
    private val readPageInfo: suspend () -> ToolResult,
    private val timeoutMillis: Long = PAGE_INFO_TIMEOUT_MS
) : PageInfoProvider {
    constructor(context: Context) : this(
        readPageInfo = {
            ToolGetter.getUITools(context.applicationContext)
                .getPageInfo(AITool(name = PAGE_INFO_TOOL_NAME))
        }
    )

    override suspend fun getCurrentPageInfo(): PageInfoProviderResult {
        val result = try {
            withTimeoutOrNull(timeoutMillis) { readPageInfo() }
                ?: return PageInfoProviderResult.Unavailable
        } catch (e: IllegalStateException) {
            return if (e.message.isPermissionFailure()) {
                PageInfoProviderResult.PermissionRequired
            } else {
                PageInfoProviderResult.ExecutionError
            }
        } catch (_: SecurityException) {
            return PageInfoProviderResult.PermissionRequired
        } catch (e: Exception) {
            return if (e.hasConnectionRefusedCause()) {
                PageInfoProviderResult.Unavailable
            } else {
                PageInfoProviderResult.ExecutionError
            }
        }

        if (result.success) {
            val pageInfo = result.result as? UIPageResultData
                ?: return PageInfoProviderResult.ExecutionError
            return PageInfoProviderResult.Available(pageInfo.toString())
        }

        val error = result.error
        return when {
            error.isPermissionFailure() -> PageInfoProviderResult.PermissionRequired
            error.isUnavailableFailure() -> PageInfoProviderResult.Unavailable
            else -> PageInfoProviderResult.ExecutionError
        }
    }

    private fun String?.isPermissionFailure(): Boolean {
        val normalized = this?.lowercase(Locale.ROOT) ?: return false
        return normalized.contains("permission") ||
            normalized.contains("accessibility service is not enabled")
    }

    private fun String?.isUnavailableFailure(): Boolean {
        val normalized = this?.lowercase(Locale.ROOT) ?: return false
        return normalized.contains("not supported") ||
            normalized.contains("failed to retrieve ui data") ||
            normalized.contains("unable to get ui hierarchy") ||
            normalized.contains("connection refused") ||
            LOCAL_CONNECT_FAILURE.containsMatchIn(normalized)
    }

    private fun Throwable.hasConnectionRefusedCause(): Boolean {
        var current: Throwable? = this
        while (current != null) {
            if (current is ConnectException) return true
            current = current.cause
        }
        return false
    }

    companion object {
        const val PAGE_INFO_TIMEOUT_MS = 3_000L

        const val PAGE_INFO_TOOL_NAME = "get_page_info"
        private val LOCAL_CONNECT_FAILURE =
            Regex("failed to connect to /(?:127\\.0\\.0\\.1|localhost)(?::\\d+)?")
    }
}
