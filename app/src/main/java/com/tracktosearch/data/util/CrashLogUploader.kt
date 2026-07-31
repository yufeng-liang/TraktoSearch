package com.tracktosearch.data.util

import android.content.Context
import android.os.Build
import com.tracktosearch.data.local.CrashLogStorage
import com.tracktosearch.data.remote.crash.CrashLogApiService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 崩溃日志上报器（Hilt 单例）。
 *
 * 走 auth-worker 代理（${GATEWAY_BASE_URL}/api/crash-logs），
 * 客户端不持有崩溃日志写入密钥，避免敏感凭据编译进 APK 被反编译泄露。
 *
 * 隐私规范：仅在用户授权后上报（[CrashLogStorage.enabled]）。
 * 未授权时本地日志保留，等待用户开启后下次启动重试。
 */
@Singleton
class CrashLogUploader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val crashLogApi: CrashLogApiService,
    private val crashLogStorage: CrashLogStorage,
) {

    companion object {
        private const val CRASH_DIR = "crash_logs"
    }

    private val _uploadResult = CompletableDeferred<Boolean>()
    val uploadResult: Deferred<Boolean> = _uploadResult

    /** Called at app start — waits for current upload, returns true if all files uploaded OK. */
    suspend fun uploadPendingLogs(): Boolean {
        // 隐私授权：用户未同意上报时直接返回（视为成功，不触发邮件兜底弹窗）
        if (!crashLogStorage.enabled.first()) {
            _uploadResult.complete(true)
            return true
        }

        val dir = File(context.filesDir, CRASH_DIR)
        if (!dir.exists()) {
            _uploadResult.complete(true)
            return true
        }

        val files = dir.listFiles()
            ?.filter { it.name.endsWith(".log") }
            ?.sortedBy { it.name }
        if (files.isNullOrEmpty()) {
            _uploadResult.complete(true)
            return true
        }

        var allOk = true
        withContext(Dispatchers.IO) {
            for (file in files) {
                try {
                    val content = file.readText()
                    val result = uploadLog(content)
                    if (result) {
                        file.delete()
                    } else {
                        allOk = false
                    }
                } catch (_: Exception) {
                    allOk = false
                }
            }
        }
        _uploadResult.complete(allOk)
        return allOk
    }

    private suspend fun uploadLog(logContent: String): Boolean {
        val appVersion = runCatching {
            val pi = context.packageManager.getPackageInfo(context.packageName, 0)
            "${pi.versionName} (${if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode})"
        }.getOrNull() ?: "unknown"

        // 解析本地日志的字段
        val lines = logContent.lines()
        val timeLine = lines.find { it.startsWith("Time:") }?.removePrefix("Time:")?.trim() ?: ""
        val pageLine = lines.find { it.startsWith("Current Page:") }?.removePrefix("Current Page:")?.trim() ?: ""
        val actionsLine = logContent.let { full ->
            val idx = full.indexOf("=== Recent User Actions ===")
            if (idx >= 0) {
                val endIdx = full.indexOf("\n\n", idx)
                if (endIdx >= 0) full.substring(idx, endIdx) else full.substring(idx)
            } else ""
        }

        // 提取栈信息
        val stackStart = logContent.indexOf("=== Stack Trace ===")
        val stackTrace = if (stackStart >= 0) {
            val afterStack = logContent.substring(stackStart)
            afterStack.substringBefore("=== Crash Context ===")
                .substringBefore("=== Caused by:")
                .trim()
        } else {
            logContent
        }

        val jsonBody = buildJsonString(
            "timestamp" to timeLine,
            "appVersion" to appVersion,
            "androidVersion" to "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            "device" to "${Build.MANUFACTURER} ${Build.MODEL}",
            "currentPage" to pageLine,
            "recentActions" to actionsLine,
            "stackTrace" to stackTrace
        )

        val requestBody = jsonBody.toRequestBody("application/json".toMediaType())
        return try {
            val response = crashLogApi.upload(requestBody)
            // ResponseBody 读取后即视为成功（worker 返回 2xx）
            try { response.close() } catch (_: Exception) {}
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun buildJsonString(vararg pairs: Pair<String, String>): String {
        return pairs.joinToString(
            separator = ",",
            prefix = "{",
            postfix = "}"
        ) { (key, value) ->
            "\"${escapeJson(key)}\":\"${escapeJson(value)}\""
        }
    }

    private fun escapeJson(s: String): String {
        return s.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }
}
