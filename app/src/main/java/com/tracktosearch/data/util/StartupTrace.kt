package com.tracktosearch.data.util

import android.os.SystemClock
import android.util.Log
import com.tracktosearch.BuildConfig

/**
 * 启动阶段 Debug 埋点。
 * 使用 elapsedRealtime 记录单调时间，避免系统时间调整影响耗时计算。
 */
object StartupTrace {
    private const val TAG = "StartupTrace"

    @Volatile
    private var processStartElapsedMs = 0L

    // 按线程记录相邻埋点，避免 Application 的 IO 线程与 Activity 主线程互相覆盖。
    private val previousMarkElapsedMs = ThreadLocal<Long>()

    fun markProcessStart() {
        if (!BuildConfig.DEBUG) return
        if (processStartElapsedMs == 0L) {
            synchronized(this) {
                if (processStartElapsedMs == 0L) {
                    processStartElapsedMs = runCatching {
                        SystemClock.elapsedRealtime()
                    }.getOrDefault(0L)
                }
            }
        }
    }

    fun mark(event: String, details: String = ""): Long {
        if (!BuildConfig.DEBUG) return 0L
        markProcessStart()
        val now = runCatching { SystemClock.elapsedRealtime() }.getOrNull() ?: return 0L
        if (processStartElapsedMs == 0L) return 0L
        val processElapsed = now - processStartElapsedMs
        val sincePrevious = now - (previousMarkElapsedMs.get() ?: processStartElapsedMs)
        previousMarkElapsedMs.set(now)
        val suffix = if (details.isBlank()) "" else " $details"
        Log.i(
            TAG,
            "event=$event processElapsedMs=$processElapsed sincePreviousMs=$sincePrevious$suffix"
        )
        return now
    }

    suspend fun <T> measure(name: String, block: suspend () -> T): T {
        val start = mark("$name.begin")
        return try {
            block()
        } finally {
            val end = if (BuildConfig.DEBUG) SystemClock.elapsedRealtime() else start
            mark("$name.end", "durationMs=${end - start}")
        }
    }
}
