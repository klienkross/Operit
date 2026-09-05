package com.ai.assistance.operit.integrations.http

import android.content.Context
import com.ai.assistance.operit.core.tools.UIPageResultData
import com.ai.assistance.operit.core.tools.defaultTool.ToolGetter
import com.ai.assistance.operit.data.model.AITool
import com.ai.assistance.operit.data.model.ToolResult
import java.util.Locale

internal class OperitPageInfoProvider(
    private val readPageInfo: suspend () -> ToolResult
) : PageInfoProvider {
    constructor(context: Context) : this(
        readPageInfo = {
            ToolGetter.getUITools(context.applicationContext)
                .getPageInfo(AITool(name = PAGE_INFO_TOOL_NAME))
        }
    )

    override suspend fun getCurrentPageInfo(): PageInfoProviderResult {
        val result = try {
            readPageInfo()
        } catch (e: IllegalStateException) {
            return if (e.message.isPermissionFailure()) {
                PageInfoProviderResult.PermissionRequired
            } else {
                PageInfoProviderResult.ExecutionError
            }
        } catch (_: SecurityException) {
            return PageInfoProviderResult.PermissionRequired
        } catch (_: Exception) {
            return PageInfoProviderResult.ExecutionError
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
            normalized.contains("unable to get ui hierarchy")
    }

    private companion object {
        const val PAGE_INFO_TOOL_NAME = "get_page_info"
    }
}
