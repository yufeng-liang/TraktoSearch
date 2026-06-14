package com.tracktosearch

import android.content.Context
import android.os.Build
import android.os.Process
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CrashHandler private constructor(
    private val context: Context
) : Thread.UncaughtExceptionHandler {

    private val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

    companion object {
        private const val CRASH_DIR = "crash_logs"
        private const val CRASH_COUNT_PREFS = "crash_prefs"
        private const val KEY_CRASH_COUNT = "crash_count"
        private const val MAX_LOG_FILES = 5

        @Volatile
        private var instance: CrashHandler? = null

        fun init(context: Context) {
            if (instance == null) {
                synchronized(this) {
                    if (instance == null) {
                        instance = CrashHandler(context.applicationContext).also {
                            Thread.setDefaultUncaughtExceptionHandler(it)
                        }
                    }
                }
            }
        }

        /**
         * 获取累计崩溃次数，每次调用后重置计数
         */
        fun getAndResetCrashCount(context: Context): Int {
            val prefs = context.applicationContext.getSharedPreferences(CRASH_COUNT_PREFS, Context.MODE_PRIVATE)
            val count = prefs.getInt(KEY_CRASH_COUNT, 0)
            prefs.edit().putInt(KEY_CRASH_COUNT, 0).apply()
            return count
        }

        /**
         * 获取所有崩溃日志内容
         */
        fun getCrashLogs(context: Context): String {
            val dir = File(context.applicationContext.filesDir, CRASH_DIR)
            if (!dir.exists()) return ""
            return dir.listFiles()
                ?.filter { it.name.endsWith(".log") }
                ?.sortedByDescending { it.name }
                ?.take(MAX_LOG_FILES)
                ?.mapNotNull {
                    try { it.readText() } catch (_: Exception) { null }
                }
                ?.joinToString("\n\n---\n\n")
                ?: ""
        }

        /**
         * 删除所有崩溃日志
         */
        fun clearCrashLogs(context: Context) {
            val dir = File(context.applicationContext.filesDir, CRASH_DIR)
            dir.listFiles()?.forEach { it.delete() }
        }
    }

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        // 递增崩溃计数
        val prefs = context.getSharedPreferences(CRASH_COUNT_PREFS, Context.MODE_PRIVATE)
        val count = prefs.getInt(KEY_CRASH_COUNT, 0)
        prefs.edit().putInt(KEY_CRASH_COUNT, count + 1).apply()

        // 写入崩溃日志文件
        try {
            saveCrashLog(throwable)
        } catch (_: Exception) { }

        // 交给系统默认处理器（会终止进程）
        defaultHandler?.uncaughtException(thread, throwable)
            ?: Process.killProcess(Process.myPid())
    }

    private fun saveCrashLog(throwable: Throwable) {
        val dir = File(context.filesDir, CRASH_DIR)
        if (!dir.exists()) dir.mkdirs()

        // 清理旧日志，保留最近 MAX_LOG_FILES 个
        dir.listFiles()
            ?.filter { it.name.endsWith(".log") }
            ?.sortedBy { it.lastModified() }
            ?.dropLast(MAX_LOG_FILES - 1)
            ?.forEach { it.delete() }

        val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault()).format(Date())
        val logFile = File(dir, "crash_$timestamp.log")

        PrintWriter(FileWriter(logFile)).use { pw ->
            pw.println("=== Crash Log ===")
            pw.println("Time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}")
            pw.println("App Version: ${getAppVersion()}")
            pw.println("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            pw.println("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            pw.println()
            pw.println("=== Stack Trace ===")
            throwable.printStackTrace(pw)
            pw.println()

            // 打印 cause chain
            var cause = throwable.cause
            while (cause != null) {
                pw.println("=== Caused by: ${cause.javaClass.name} ===")
                pw.println("Message: ${cause.message}")
                cause.printStackTrace(pw)
                cause = cause.cause
            }
        }
    }

    private fun getAppVersion(): String {
        return try {
            val pi = context.packageManager.getPackageInfo(context.packageName, 0)
            "${pi.versionName} (${if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode})"
        } catch (_: Exception) {
            "unknown"
        }
    }
}
