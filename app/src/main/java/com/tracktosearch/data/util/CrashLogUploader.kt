package com.tracktosearch.data.util

import android.content.Context
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import com.tracktosearch.data.local.CrashLogRecord
import com.tracktosearch.data.local.CrashLogRecordStore
import com.tracktosearch.data.local.CrashLogStorage
import com.tracktosearch.data.remote.crash.CrashLogApiService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 崩溃日志上报器（Hilt 单例，状态机）。
 *
 * 走 auth-worker 代理（${GATEWAY_BASE_URL}/api/crash-logs），客户端不持有上报密钥。
 * 隐私规范：仅在用户授权后上报（[CrashLogStorage.enabled]），未授权时直接跳过。
 *
 * 状态机：
 * - [uploadState] 暴露 Idle / Uploading / Success / Failed(error)
 * - 并发去重：在途上传由 [inflight] 信号复用结果，App 启动 / 授权同意 / 设置页开关并发触发只执行一次真正上传
 * - 上传前扫描目录为无记录文件补 PENDING 记录（含全文快照）；成功删文件，失败保留文件供重试
 */
@Singleton
class CrashLogUploader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val crashLogApi: CrashLogApiService,
    private val crashLogStorage: CrashLogStorage,
    private val recordStore: CrashLogRecordStore,
) {

    companion object {
        private const val CRASH_DIR = "crash_logs"
    }

    private val mutex = Mutex()

    private val _uploadState = MutableStateFlow<UploadState>(UploadState.Idle)
    val uploadState: StateFlow<UploadState> = _uploadState.asStateFlow()

    /** 在途上传的完成信号（非 null 表示正在上传，并发调用 await 复用结果）。锁内只置位/清空，doUpload 在锁外执行 */
    @Volatile
    private var inflight: CompletableDeferred<Boolean>? = null

    /** 是否存在待上传的日志文件（设置页开关打开时判断是否需要触发上传） */
    fun hasPendingLogs(): Boolean {
        val dir = File(context.filesDir, CRASH_DIR)
        return dir.exists() && (dir.listFiles()?.any { it.name.endsWith(".log") } ?: false)
    }

    /**
     * 上传待上传日志。返回是否全部成功。
     * 未授权或无文件视为成功；在途上传复用其结果（不重复执行）。
     */
    suspend fun uploadPendingLogs(): Boolean {
        // 等待授权状态加载完成：避免冷启动竞态下误判未授权而跳过自动上传（与 CrashReportDialogHost 一致）
        crashLogStorage.loaded.first { it }
        // 隐私授权：用户未同意上报时直接跳过（不建记录不打扰）
        if (!crashLogStorage.enabled.first()) return true
        // 锁外快速路径：已有在途上传直接 await 复用结果（inflight 为 volatile，可见性安全）
        inflight?.let { return it.await() }
        // 锁内竞态窗口复查 + 置位：两个调用方同时过快速路径时，后取锁者 join 在途上传
        var ownsUpload = false
        val deferred: CompletableDeferred<Boolean> = mutex.withLock {
            val existing = inflight
            if (existing != null) return@withLock existing
            ownsUpload = true
            CompletableDeferred<Boolean>().also {
                inflight = it
                _uploadState.value = UploadState.Uploading
            }
        }
        // join 了在途上传（非自己创建的信号）：await 复用其结果，不重复执行
        if (!ownsUpload) return deferred.await()

        try {
            val (allOk, lastError) = doUpload()
            _uploadState.value = if (allOk) UploadState.Success else UploadState.Failed(lastError)
            deferred.complete(allOk)
            return allOk
        } catch (e: CancellationException) {
            // 上传协程被取消（如旋转/退出）：复位状态避免宿主持久停在 Uploading 卡死
            _uploadState.value = UploadState.Idle
            deferred.complete(false)
            throw e
        } catch (e: Exception) {
            // DataStore 读写等异常：不 rethrow（避免启动协程崩溃），记失败状态自愈，下次调用重试
            _uploadState.value = UploadState.Failed(e.message ?: "unknown error")
            deferred.complete(false)
            return false
        } finally {
            inflight = null
        }
    }

    /** 逐个上传日志文件；返回是否全部成功与最后一个失败原因 */
    private suspend fun doUpload(): Pair<Boolean, String> {
        val dir = File(context.filesDir, CRASH_DIR)
        if (!dir.exists()) return true to ""
        val files = dir.listFiles()
            ?.filter { it.name.endsWith(".log") }
            ?.sortedBy { it.name }
        if (files.isNullOrEmpty()) return true to ""

        // 为尚无记录的文件补 PENDING 记录（含全文快照），已有记录（含 UPLOADING 残留）不重复建
        recordStore.loadFromDisk()
        val knownIds = recordStore.records.value.map { it.id }.toSet()
        for (file in files) {
            if (file.name in knownIds) continue
            val content = runCatching { file.readText() }.getOrNull() ?: continue
            recordStore.upsert(buildPendingRecord(file.name, content))
        }

        var allOk = true
        var lastError = ""
        withContext(Dispatchers.IO) {
            for (file in files) {
                val record = recordStore.getRecord(file.name) ?: continue
                try {
                    recordStore.markUploading(file.name)
                    val ok = uploadLog(record.logContent)
                    if (ok) {
                        recordStore.markSuccess(file.name, System.currentTimeMillis())
                        file.delete()
                    } else {
                        recordStore.markFailed(file.name, "upload failed")
                        allOk = false
                        lastError = "upload failed"
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val msg = e.message ?: "unknown error"
                    recordStore.markFailed(file.name, msg)
                    allOk = false
                    lastError = msg
                }
            }
        }
        return allOk to lastError
    }

    private fun buildPendingRecord(fileName: String, content: String): CrashLogRecord {
        return CrashLogRecord(
            id = fileName,
            crashTime = parseCrashTime(fileName),
            status = CrashLogRecord.Status.PENDING,
            logContent = content,
            appVersion = content.lines()
                .find { it.startsWith("App Version:") }?.removePrefix("App Version:")?.trim() ?: "",
        )
    }

    /** 从文件名 crash_yyyy-MM-dd_HH-mm-ss.log 解析崩溃时间戳 */
    private fun parseCrashTime(fileName: String): Long {
        return try {
            val ts = fileName.removePrefix("crash_").removeSuffix(".log")
            SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).parse(ts)?.time
                ?: System.currentTimeMillis()
        } catch (_: Exception) {
            System.currentTimeMillis()
        }
    }

    private suspend fun uploadLog(logContent: String): Boolean {
        val appVersion = runCatching {
            val pi = context.packageManager.getPackageInfo(context.packageName, 0)
            "${pi.versionName} (${PackageInfoCompat.getLongVersionCode(pi)})"
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
            "currentPage" to pageLine,
            "recentActions" to actionsLine,
            "stackTrace" to stackTrace
        )

        val requestBody = jsonBody.toRequestBody("application/json".toMediaType())
        return try {
            val response = crashLogApi.upload(requestBody)
            try { response.close() } catch (_: Exception) {}
            true
        } catch (e: CancellationException) {
            throw e
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
