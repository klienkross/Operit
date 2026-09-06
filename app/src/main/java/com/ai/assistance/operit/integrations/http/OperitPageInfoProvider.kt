package com.ai.assistance.operit.integrations.http

import android.content.Context
import com.ai.assistance.operit.core.tools.UIPageResultData
import com.ai.assistance.operit.core.tools.defaultTool.ToolGetter
import com.ai.assistance.operit.data.model.AITool
import com.ai.assistance.operit.data.model.ToolResult
import java.net.ConnectException
import java.util.Locale
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking

internal class OperitPageInfoProvider(
    private val timeoutMillis: Long = PAGE_INFO_TIMEOUT_MS,
    private val readPageInfo: suspend () -> ToolResult
) : PageInfoProvider, AutoCloseable {
    private val workerBusy = AtomicBoolean(false)
    private val worker: ExecutorService = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        SynchronousQueue(),
        { runnable -> Thread(runnable, "operit-page-info-probe").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy()
    )

    constructor(context: Context) : this(
        readPageInfo = {
            ToolGetter.getUITools(context.applicationContext)
                .getPageInfo(AITool(name = PAGE_INFO_TOOL_NAME))
        }
    )

    override suspend fun getCurrentPageInfo(): PageInfoProviderResult {
        if (!workerBusy.compareAndSet(false, true)) {
            return PageInfoProviderResult.Unavailable
        }

        val future = try {
            worker.submit<ToolResult> {
                try {
                    runBlocking { readPageInfo() }
                } finally {
                    // Cancellation marks a Future done before an interrupt-ignoring worker exits.
                    workerBusy.set(false)
                }
            }
        } catch (_: RejectedExecutionException) {
            workerBusy.set(false)
            return PageInfoProviderResult.Unavailable
        }

        val result = try {
            future.get(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            // Do not cancel: the suspend implementation may have dispatched blocking work to
            // another thread. Keeping this wrapper alive keeps workerBusy true until that real
            // coroutine completes, preventing accumulation of orphaned reads.
            return PageInfoProviderResult.Unavailable
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return PageInfoProviderResult.ExecutionError
        } catch (e: ExecutionException) {
            return e.cause.toProviderFailure()
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

    override fun close() {
        worker.shutdownNow()
    }

    private fun Throwable?.toProviderFailure(): PageInfoProviderResult = when {
        this is SecurityException -> PageInfoProviderResult.PermissionRequired
        this is IllegalStateException && message.isPermissionFailure() ->
            PageInfoProviderResult.PermissionRequired
        this?.hasConnectionRefusedCause() == true -> PageInfoProviderResult.Unavailable
        else -> PageInfoProviderResult.ExecutionError
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
        const val PAGE_INFO_TIMEOUT_MS = 2_500L

        const val PAGE_INFO_TOOL_NAME = "get_page_info"
        private val LOCAL_CONNECT_FAILURE =
            Regex("failed to connect to /(?:127\\.0\\.0\\.1|localhost)(?::\\d+)?")
    }
}
