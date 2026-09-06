package com.ai.assistance.operit.integrations.http

import android.content.Context
import android.os.Looper
import com.ai.assistance.operit.core.tools.UIPageResultData
import com.ai.assistance.operit.core.tools.defaultTool.ToolGetter
import com.ai.assistance.operit.data.model.AITool
import com.ai.assistance.operit.data.model.ToolParameter
import com.ai.assistance.operit.data.model.ToolResult
import com.ai.assistance.operit.util.AppLogger
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
    private val readPageInfo: suspend (Long) -> ToolResult
) : PageInfoProvider, AutoCloseable {
    private val workerBusy = AtomicBoolean(false)
    private val worker: ExecutorService = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        SynchronousQueue(),
        { runnable -> Thread(runnable, WORKER_THREAD_NAME).apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy()
    )

    constructor(context: Context) : this(
        readPageInfo = { requestId ->
            val uiTools = ToolGetter.getUITools(context.applicationContext, requestId)
            val startedAt = monotonicMillis()
            AppLogger.i(
                PAGE_INFO_PROBE_TAG,
                "component=OperitPageInfoProvider event=ui_call_start request_id=$requestId " +
                    "selected_class=${uiTools.javaClass.name} method=getPageInfo " +
                    "thread=${Thread.currentThread().name} interrupted=${Thread.currentThread().isInterrupted}"
            )
            try {
                uiTools.getPageInfo(
                    AITool(
                        name = PAGE_INFO_TOOL_NAME,
                        parameters = listOf(
                            ToolParameter(PAGE_INFO_PROBE_REQUEST_ID_PARAMETER, requestId.toString())
                        )
                    )
                ).also { result ->
                    AppLogger.i(
                        PAGE_INFO_PROBE_TAG,
                        "component=OperitPageInfoProvider event=ui_call_end request_id=$requestId " +
                            "selected_class=${uiTools.javaClass.name} success=${result.success} " +
                            "result_type=${result.result.javaClass.name} " +
                            "elapsed_ms=${monotonicMillis() - startedAt} " +
                            "thread=${Thread.currentThread().name} interrupted=${Thread.currentThread().isInterrupted}"
                    )
                }
            } catch (t: Throwable) {
                AppLogger.e(
                    PAGE_INFO_PROBE_TAG,
                    "component=OperitPageInfoProvider event=ui_call_threw request_id=$requestId " +
                        "selected_class=${uiTools.javaClass.name} " +
                        "elapsed_ms=${monotonicMillis() - startedAt} " +
                        "thread=${Thread.currentThread().name} interrupted=${Thread.currentThread().isInterrupted}",
                    t
                )
                throw t
            }
        }
    )

    override suspend fun getCurrentPageInfo(requestId: Long): PageInfoProviderResult {
        val providerStartedAt = monotonicMillis()
        AppLogger.i(
            PAGE_INFO_PROBE_TAG,
            "component=OperitPageInfoProvider event=provider_enter request_id=$requestId " +
                "thread=${Thread.currentThread().name} interrupted=${Thread.currentThread().isInterrupted}"
        )
        if (!workerBusy.compareAndSet(false, true)) {
            return logProviderResult(
                requestId,
                providerStartedAt,
                PageInfoProviderResult.Unavailable,
                "worker_busy"
            )
        }

        val timedOut = AtomicBoolean(false)
        val future = try {
            worker.submit<ToolResult> {
                val readStartedAt = monotonicMillis()
                AppLogger.i(
                    PAGE_INFO_PROBE_TAG,
                    "component=OperitPageInfoProvider event=read_start request_id=$requestId " +
                        "thread=${Thread.currentThread().name} interrupted=${Thread.currentThread().isInterrupted}"
                )
                try {
                    runBlocking { readPageInfo(requestId) }.also { result ->
                        AppLogger.i(
                            PAGE_INFO_PROBE_TAG,
                            "component=OperitPageInfoProvider event=read_return request_id=$requestId " +
                                "success=${result.success} result_type=${result.result.javaClass.name} " +
                                "elapsed_ms=${monotonicMillis() - readStartedAt} " +
                                "thread=${Thread.currentThread().name} interrupted=${Thread.currentThread().isInterrupted}"
                        )
                    }
                } catch (t: Throwable) {
                    AppLogger.e(
                        PAGE_INFO_PROBE_TAG,
                        "component=OperitPageInfoProvider event=read_threw request_id=$requestId " +
                            "elapsed_ms=${monotonicMillis() - readStartedAt} " +
                            "thread=${Thread.currentThread().name} interrupted=${Thread.currentThread().isInterrupted}",
                        t
                    )
                    throw t
                } finally {
                    // Cancellation marks a Future done before an interrupt-ignoring worker exits.
                    workerBusy.set(false)
                    AppLogger.i(
                        PAGE_INFO_PROBE_TAG,
                        "component=OperitPageInfoProvider event=read_finished request_id=$requestId " +
                            "after_timeout=${timedOut.get()} elapsed_ms=${monotonicMillis() - readStartedAt} " +
                            "thread=${Thread.currentThread().name} interrupted=${Thread.currentThread().isInterrupted}"
                    )
                }
            }
        } catch (e: RejectedExecutionException) {
            workerBusy.set(false)
            AppLogger.e(
                PAGE_INFO_PROBE_TAG,
                "component=OperitPageInfoProvider event=worker_rejected request_id=$requestId",
                e
            )
            return logProviderResult(
                requestId,
                providerStartedAt,
                PageInfoProviderResult.Unavailable,
                "worker_rejected"
            )
        }

        AppLogger.i(
            PAGE_INFO_PROBE_TAG,
            "component=OperitPageInfoProvider event=future_wait_start request_id=$requestId " +
                "timeout_ms=$timeoutMillis thread=${Thread.currentThread().name}"
        )
        val result = try {
            future.get(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            // Do not cancel: the suspend implementation may have dispatched blocking work to
            // another thread. Keeping this wrapper alive keeps workerBusy true until that real
            // coroutine completes, preventing accumulation of orphaned reads.
            timedOut.set(true)
            AppLogger.w(
                PAGE_INFO_PROBE_TAG,
                "component=OperitPageInfoProvider event=future_wait_timeout request_id=$requestId " +
                    "elapsed_ms=${monotonicMillis() - providerStartedAt} future_done=${future.isDone} " +
                    "worker_busy=${workerBusy.get()} thread=${Thread.currentThread().name} " +
                    "interrupted=${Thread.currentThread().isInterrupted}"
            )
            // Debug intent: capture the blocking frame in the same APK run; adb signal access is
            // device-policy dependent and a missing stack would force another installation.
            dumpRelevantThreadStacks(requestId)
            return logProviderResult(
                requestId,
                providerStartedAt,
                PageInfoProviderResult.Unavailable,
                "timeout"
            )
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            dumpRelevantThreadStacks(requestId)
            return logProviderResult(
                requestId,
                providerStartedAt,
                PageInfoProviderResult.ExecutionError,
                "future_wait_interrupted"
            )
        } catch (e: ExecutionException) {
            AppLogger.e(
                PAGE_INFO_PROBE_TAG,
                "component=OperitPageInfoProvider event=future_wait_threw request_id=$requestId " +
                    "elapsed_ms=${monotonicMillis() - providerStartedAt}",
                e.cause ?: e
            )
            return logProviderResult(
                requestId,
                providerStartedAt,
                e.cause.toProviderFailure(),
                "execution_exception"
            )
        }
        AppLogger.i(
            PAGE_INFO_PROBE_TAG,
            "component=OperitPageInfoProvider event=future_wait_end request_id=$requestId " +
                "elapsed_ms=${monotonicMillis() - providerStartedAt} " +
                "thread=${Thread.currentThread().name} interrupted=${Thread.currentThread().isInterrupted}"
        )

        if (result.success) {
            val pageInfo = result.result as? UIPageResultData
                ?: return logProviderResult(
                    requestId,
                    providerStartedAt,
                    PageInfoProviderResult.ExecutionError,
                    "unexpected_result_type"
                )
            return logProviderResult(
                requestId,
                providerStartedAt,
                PageInfoProviderResult.Available(pageInfo.toString()),
                "available"
            )
        }

        val error = result.error
        val providerResult = when {
            error.isPermissionFailure() -> PageInfoProviderResult.PermissionRequired
            error.isUnavailableFailure() -> PageInfoProviderResult.Unavailable
            else -> PageInfoProviderResult.ExecutionError
        }
        return logProviderResult(requestId, providerStartedAt, providerResult, "tool_failure")
    }

    override fun close() {
        AppLogger.i(PAGE_INFO_PROBE_TAG, "component=OperitPageInfoProvider event=close worker_busy=${workerBusy.get()}")
        worker.shutdownNow()
    }

    private fun logProviderResult(
        requestId: Long,
        startedAt: Long,
        result: PageInfoProviderResult,
        reason: String
    ): PageInfoProviderResult {
        AppLogger.i(
            PAGE_INFO_PROBE_TAG,
            "component=OperitPageInfoProvider event=provider_return request_id=$requestId " +
                "result_type=${result.javaClass.name} reason=$reason " +
                "elapsed_ms=${monotonicMillis() - startedAt} " +
                "thread=${Thread.currentThread().name} interrupted=${Thread.currentThread().isInterrupted}"
        )
        return result
    }

    private fun dumpRelevantThreadStacks(requestId: Long) {
        try {
            val mainThread = Looper.getMainLooper().thread
            Thread.getAllStackTraces()
                .filter { (thread, stack) ->
                    thread === mainThread ||
                        thread.name == WORKER_THREAD_NAME ||
                        stack.any { frame ->
                            val className = frame.className
                            className.contains("PageInfo") ||
                                className.contains("UITools") ||
                                className.contains("UIHierarchyManager") ||
                                className.contains("AndroidShellExecutor") ||
                                className.contains("ShellExecutor") ||
                                className.startsWith("com.topjohnwu.superuser") ||
                                className.startsWith("moe.shizuku") ||
                                className.startsWith("java.lang.Process")
                        }
                }
                .toList()
                .sortedBy { (thread, _) -> thread.name }
                .forEach { (thread, stack) ->
                    AppLogger.w(
                        PAGE_INFO_PROBE_TAG,
                        "component=OperitPageInfoProvider event=timeout_thread_stack request_id=$requestId " +
                            "thread=${thread.name} state=${thread.state} interrupted=${thread.isInterrupted} " +
                            "is_main=${thread === mainThread} frames=${stack.take(64).joinToString(" <- ")}"
                    )
                }
        } catch (t: Throwable) {
            AppLogger.e(
                PAGE_INFO_PROBE_TAG,
                "component=OperitPageInfoProvider event=timeout_thread_stack_failed request_id=$requestId",
                t
            )
        }
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
        const val PAGE_INFO_PROBE_REQUEST_ID_PARAMETER = "page_info_probe_request_id"
        private const val PAGE_INFO_PROBE_TAG = "PageInfoProbe"
        private const val WORKER_THREAD_NAME = "operit-page-info-probe"
        private val LOCAL_CONNECT_FAILURE =
            Regex("failed to connect to /(?:127\\.0\\.0\\.1|localhost)(?::\\d+)?")

        private fun monotonicMillis(): Long = System.nanoTime() / 1_000_000L
    }
}
