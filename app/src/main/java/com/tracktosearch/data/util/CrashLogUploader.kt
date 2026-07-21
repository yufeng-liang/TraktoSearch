package com.tracktosearch.data.util

import android.content.Context
import android.os.Build
import com.tracktosearch.BuildConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object CrashLogUploader {

    private const val CRASH_DIR = "crash_logs"
    private val _uploadResult = CompletableDeferred<Boolean>()
    val uploadResult: Deferred<Boolean> = _uploadResult

    /** Called at app start — waits for current upload, returns true if all files uploaded OK. */
    suspend fun uploadPendingLogs(context: Context): Boolean {
        val apiUrl = BuildConfig.CRASH_LOG_API_URL
        val apiToken = BuildConfig.CRASH_LOG_API_TOKEN
        if (apiUrl.isBlank() || apiToken.isBlank()) {
            _uploadResult.complete(false)
            return false
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
                    val result = uploadLog(apiUrl, apiToken, content, context)
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

    private suspend fun uploadLog(
        apiUrl: String,
        apiToken: String,
        logContent: String,
        context: Context
    ): Boolean {
        val appVersion = runCatching {
            val pi = context.packageManager.getPackageInfo(context.packageName, 0)
            "${pi.versionName} (${if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode})"
        }.getOrNull() ?: "unknown"

        // 解析本地日志的字段
        val lines = logContent.lines()
        val timeLine = lines.find { it.startsWith("Time:") }?.removePrefix("Time:")?.trim() ?: ""
        val pageLine = lines.find { it.startsWith("Current Page:") }?.removePrefix("Current Page:")?.trim() ?: ""
        val actionsLine = lines.joinToString("\n").let { full ->
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

        val url = java.net.URL(apiUrl)
        val conn = url.openConnection() as java.net.HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $apiToken")
            conn.doOutput = true
            conn.connectTimeout = 15000
            conn.readTimeout = 15000

            conn.outputStream.use { os ->
                os.write(jsonBody.toByteArray(Charsets.UTF_8))
            }

            return conn.responseCode in 200..299
        } finally {
            conn.disconnect()
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
