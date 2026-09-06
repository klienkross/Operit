package com.ai.assistance.operit.core.tools.system

import android.content.Context
import android.os.SystemClock
import com.ai.assistance.operit.util.AppLogger
import com.ai.assistance.operit.core.tools.system.shell.ShellExecutor
import com.ai.assistance.operit.core.tools.system.shell.ShellExecutorFactory
import com.ai.assistance.operit.core.tools.system.shell.ShellProcess
import com.ai.assistance.operit.data.preferences.androidPermissionPreferences

/** 向后兼容的Shell命令执行工具类 通过权限级别委托到相应的Shell执行器 */
class AndroidShellExecutor {
    companion object {
        private const val TAG = "AndroidShellExecutor"
        private var context: Context? = null
        private val preferredPermissionLevelCacheLock = Any()
        @Volatile private var hasCachedPreferredPermissionLevel = false
        @Volatile private var cachedPreferredPermissionLevel: AndroidPermissionLevel? = null

        /**
         * 设置全局上下文引用
         * @param appContext 应用上下文
         */
        fun setContext(appContext: Context) {
            context = appContext.applicationContext
        }

        fun clearPreferredPermissionLevelCache() {
            synchronized(preferredPermissionLevelCacheLock) {
                cachedPreferredPermissionLevel = null
                hasCachedPreferredPermissionLevel = false
            }
        }

        private fun getPreferredPermissionLevelCached(): AndroidPermissionLevel? {
            if (hasCachedPreferredPermissionLevel) {
                return cachedPreferredPermissionLevel
            }

            synchronized(preferredPermissionLevelCacheLock) {
                if (!hasCachedPreferredPermissionLevel) {
                    cachedPreferredPermissionLevel =
                            androidPermissionPreferences.getPreferredPermissionLevel()
                    hasCachedPreferredPermissionLevel = true
                }
                return cachedPreferredPermissionLevel
            }
        }

        private fun getPermissionLevelLabel(level: AndroidPermissionLevel): String {
            return when (level) {
                AndroidPermissionLevel.STANDARD -> "STANDARD"
                AndroidPermissionLevel.ACCESSIBILITY -> "ACCESSIBILITY"
                AndroidPermissionLevel.DEBUGGER -> "DEBUGGER"
                AndroidPermissionLevel.ADMIN -> "ADMIN"
                AndroidPermissionLevel.ROOT -> "ROOT"
            }
        }

        private fun buildStrictUnavailableReason(
            level: AndroidPermissionLevel,
            executorAvailable: Boolean,
            permStatus: ShellExecutor.PermissionStatus
        ): String {
            val reasons = mutableListOf<String>()

            if (!executorAvailable) {
                reasons += "executor unavailable"
            }
            if (!permStatus.granted) {
                reasons += permStatus.reason.trim().ifEmpty { "permission not granted" }
            }

            val reasonText = reasons.distinct().joinToString("; ").ifBlank { "unknown reason" }
            return "Current ${getPermissionLevelLabel(level)} unavailable: $reasonText"
        }

        /**
         * 封装执行命令的函数
         * @param command 要执行的命令
         * @return 命令执行结果
         */
        suspend fun executeShellCommand(command: String): CommandResult {
            return executeShellCommand(command, null)
        }

        suspend fun executeShellCommand(command: String, identityOverride: ShellIdentity?): CommandResult {
            val ctx = context ?: return CommandResult(false, "", "Context not initialized")

            // 如果调用方显式指定了身份，就直接向下传递；否则使用默认身份
            val identity = identityOverride ?: ShellIdentity.DEFAULT

            val preferredLevel = getPreferredPermissionLevelCached()
            val actualLevel = preferredLevel ?: AndroidPermissionLevel.STANDARD

            val preferredExecutor = ShellExecutorFactory.getExecutor(ctx, actualLevel)
            val pageInfoStep = pageInfoCommandStep(command)
            val permissionStartedAt = SystemClock.elapsedRealtime()
            if (pageInfoStep != null) {
                AppLogger.i(
                    PAGE_INFO_PROBE_TAG,
                    "component=AndroidShellExecutor event=blocking_start boundary=has_permission " +
                        "step=$pageInfoStep permission=$actualLevel executor=${preferredExecutor.javaClass.name} " +
                        "identity=$identity thread=${Thread.currentThread().name} " +
                        "interrupted=${Thread.currentThread().isInterrupted}"
                )
            }
            val permStatus = try {
                preferredExecutor.hasPermission()
            } catch (t: Throwable) {
                if (pageInfoStep != null) {
                    AppLogger.e(
                        PAGE_INFO_PROBE_TAG,
                        "component=AndroidShellExecutor event=blocking_threw boundary=has_permission " +
                            "step=$pageInfoStep elapsed_ms=${SystemClock.elapsedRealtime() - permissionStartedAt} " +
                            "thread=${Thread.currentThread().name} interrupted=${Thread.currentThread().isInterrupted}",
                        t
                    )
                }
                throw t
            }
            if (pageInfoStep != null) {
                AppLogger.i(
                    PAGE_INFO_PROBE_TAG,
                    "component=AndroidShellExecutor event=blocking_end boundary=has_permission " +
                        "step=$pageInfoStep granted=${permStatus.granted} " +
                        "elapsed_ms=${SystemClock.elapsedRealtime() - permissionStartedAt} " +
                        "thread=${Thread.currentThread().name} interrupted=${Thread.currentThread().isInterrupted}"
                )
            }

            val availabilityStartedAt = SystemClock.elapsedRealtime()
            if (pageInfoStep != null) {
                AppLogger.i(
                    PAGE_INFO_PROBE_TAG,
                    "component=AndroidShellExecutor event=blocking_start boundary=is_available " +
                        "step=$pageInfoStep executor=${preferredExecutor.javaClass.name} " +
                        "thread=${Thread.currentThread().name} interrupted=${Thread.currentThread().isInterrupted}"
                )
            }
            val executorAvailable = try {
                preferredExecutor.isAvailable()
            } catch (t: Throwable) {
                if (pageInfoStep != null) {
                    AppLogger.e(
                        PAGE_INFO_PROBE_TAG,
                        "component=AndroidShellExecutor event=blocking_threw boundary=is_available " +
                            "step=$pageInfoStep elapsed_ms=${SystemClock.elapsedRealtime() - availabilityStartedAt} " +
                            "thread=${Thread.currentThread().name} interrupted=${Thread.currentThread().isInterrupted}",
                        t
                    )
                }
                throw t
            }
            if (pageInfoStep != null) {
                AppLogger.i(
                    PAGE_INFO_PROBE_TAG,
                    "component=AndroidShellExecutor event=blocking_end boundary=is_available " +
                        "step=$pageInfoStep available=$executorAvailable " +
                        "elapsed_ms=${SystemClock.elapsedRealtime() - availabilityStartedAt} " +
                        "thread=${Thread.currentThread().name} interrupted=${Thread.currentThread().isInterrupted}"
                )
            }

            if (executorAvailable && permStatus.granted) {
                val executionStartedAt = SystemClock.elapsedRealtime()
                if (pageInfoStep != null) {
                    AppLogger.i(
                        PAGE_INFO_PROBE_TAG,
                        "component=AndroidShellExecutor event=blocking_start boundary=execute_command " +
                            "step=$pageInfoStep permission=$actualLevel executor=${preferredExecutor.javaClass.name} " +
                            "identity=$identity thread=${Thread.currentThread().name} " +
                            "interrupted=${Thread.currentThread().isInterrupted}"
                    )
                }
                val result = try {
                    preferredExecutor.executeCommand(command, identity)
                } catch (t: Throwable) {
                    if (pageInfoStep != null) {
                        AppLogger.e(
                            PAGE_INFO_PROBE_TAG,
                            "component=AndroidShellExecutor event=blocking_threw boundary=execute_command " +
                                "step=$pageInfoStep elapsed_ms=${SystemClock.elapsedRealtime() - executionStartedAt} " +
                                "thread=${Thread.currentThread().name} interrupted=${Thread.currentThread().isInterrupted}",
                            t
                        )
                    }
                    throw t
                }
                if (pageInfoStep != null) {
                    AppLogger.i(
                        PAGE_INFO_PROBE_TAG,
                        "component=AndroidShellExecutor event=blocking_end boundary=execute_command " +
                            "step=$pageInfoStep success=${result.success} exit_code=${result.exitCode} " +
                            "elapsed_ms=${SystemClock.elapsedRealtime() - executionStartedAt} " +
                            "thread=${Thread.currentThread().name} interrupted=${Thread.currentThread().isInterrupted}"
                    )
                }
                return CommandResult(result.success, result.stdout, result.stderr, result.exitCode)
            }

            val reason = buildStrictUnavailableReason(actualLevel, executorAvailable, permStatus)

            AppLogger.d(TAG, "Strict permission mode enabled. $reason")
            return CommandResult(false, "", reason, -1)
        }

        private fun pageInfoCommandStep(command: String): String? = when {
            command.startsWith("uiautomator dump") -> "uiautomator_dump"
            command.startsWith("cat /sdcard/window_dump.xml") -> "read_window_dump"
            command.startsWith("dumpsys window") -> "dumpsys_window"
            command.startsWith("dumpsys activity") -> "dumpsys_activity"
            else -> null
        }

        suspend fun startShellProcess(command: String): ShellProcess {
            val ctx = context ?: throw IllegalStateException("Context not initialized")

            val preferredLevel = getPreferredPermissionLevelCached()
            val actualLevel = preferredLevel ?: AndroidPermissionLevel.STANDARD
            val preferredExecutor = ShellExecutorFactory.getExecutor(ctx, actualLevel)
            val permStatus = preferredExecutor.hasPermission()
            val executorAvailable = preferredExecutor.isAvailable()

            if (executorAvailable && permStatus.granted) {
                return preferredExecutor.startProcess(command)
            }

            val reason = buildStrictUnavailableReason(actualLevel, executorAvailable, permStatus)

            AppLogger.d(TAG, "Strict permission mode enabled. $reason")
            throw SecurityException(reason)
        }

        private const val PAGE_INFO_PROBE_TAG = "PageInfoProbe"
    }

    /** 命令执行结果数据类 */
    data class CommandResult(
            val success: Boolean,
            val stdout: String,
            val stderr: String = "",
            val exitCode: Int = -1
    )
}

enum class ShellIdentity {
    DEFAULT,
    APP,
    ROOT,
    SHELL
}
