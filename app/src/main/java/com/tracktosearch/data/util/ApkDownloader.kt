package com.tracktosearch.data.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Environment
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.tracktosearch.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

object ApkDownloader {
    private const val CHANNEL_ID = "apk_download"
    private const val NOTIFICATION_ID = 1001

    suspend fun downloadApk(
        context: Context,
        url: String,
        fileName: String,
        /** 每读一个块回调一次：(已读字节数, 总字节数)；总字节数未知时 totalBytes 传 0（UI 转 indeterminate） */
        onProgress: (bytesRead: Long, totalBytes: Long) -> Unit,
        fallbackUrl: String = "",
        /** APK 期望 SHA-256（小写 hex），非空时下载完成后校验，不匹配抛异常并删除文件 */
        expectedSha256: String = ""
    ): File = withContext(Dispatchers.IO) {
        // APK 下载走匿名请求（GitHub releases 查询已走网关代理）。
        // 移除直连 GitHub token 注入，避免密钥编译进 APK 被反编译泄露。
        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()

        createDownloadChannel(context)
        showDownloadNotification(context, 0L, 0L)

        try {
            // 尝试主 URL
            val result = tryDownload(client, url, context, fileName) { bytesRead, totalBytes ->
                onProgress(bytesRead, totalBytes)
                showDownloadNotification(context, bytesRead, totalBytes)
            }

            // 主 URL 失败且有备用 URL，自动降级尝试
            val finalResult = if (result == null && fallbackUrl.isNotEmpty()) {
                tryDownload(client, fallbackUrl, context, fileName) { bytesRead, totalBytes ->
                    onProgress(bytesRead, totalBytes)
                    showDownloadNotification(context, bytesRead, totalBytes)
                } ?: throw Exception(context.getString(R.string.download_failed_both))
            } else if (result == null) {
                throw Exception(context.getString(R.string.download_failed))
            } else {
                result
            }

            // SHA-256 完整性校验：防止下载过程中被篡改或损坏
            if (expectedSha256.isNotEmpty()) {
                val actualHash = computeSha256(finalResult)
                if (!actualHash.equals(expectedSha256, ignoreCase = true)) {
                    finalResult.delete()
                    throw Exception(context.getString(R.string.download_sha256_mismatch))
                }
            }

            NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
            finalResult
        } catch (e: Exception) {
            NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
            throw e
        } finally {
            // 每次下载新建的 client 用完必须关闭：释放 dispatcher 线程池与连接池，
            // 否则多次更新检查后线程/连接泄漏（OkHttpClient 无 close()，须 shutdown dispatcher）。
            runCatching { client.dispatcher.executorService.shutdown() }
        }
    }

    /** 计算文件 SHA-256（小写 hex） */
    private fun computeSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun tryDownload(
        client: OkHttpClient,
        url: String,
        context: Context,
        fileName: String,
        onProgress: (bytesRead: Long, totalBytes: Long) -> Unit
    ): File? {
        var response: okhttp3.Response? = null
        return try {
            val request = Request.Builder().url(url).build()
            response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                // 非 2xx：errorBody 未消费会泄漏连接，必须显式关闭
                response.close()
                return null
            }

            val body = response.body
            if (body == null) {
                response.close()
                return null
            }
            val contentLength = body.contentLength()

            val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            if (dir == null) {
                body.close()
                response.close()
                return null
            }
            val file = File(dir, fileName)

            if (file.exists()) file.delete()

            var bytesRead = 0L
            val buffer = ByteArray(8192)
            body.byteStream().use { input ->
                file.outputStream().use { output ->
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        bytesRead += read
                        // 每读一个块回调原始字节数；contentLength 未知（<=0）时 totalBytes 传 0，由 UI 层转 indeterminate
                        onProgress(bytesRead, if (contentLength > 0) contentLength else 0L)
                    }
                }
            }

            file
        } catch (e: Exception) {
            null
        } finally {
            // response 在成功时由 body.use 关闭，失败时在此关闭
        }
    }

    private fun createDownloadChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_channel_apk_download),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = context.getString(R.string.notification_channel_apk_download_desc)
            setShowBadge(false)
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    /** 下载进度通知：内部按 bytesRead/totalBytes 换算百分比，totalBytes 未知（<=0）时转 indeterminate */
    private fun showDownloadNotification(context: Context, bytesRead: Long, totalBytes: Long) {
        val indeterminate = totalBytes <= 0
        val percent = if (indeterminate) 0 else ((bytesRead * 100) / totalBytes).toInt().coerceIn(0, 100)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(context.getString(R.string.update_download_builtin))
            .setContentText(if (indeterminate) context.getString(R.string.download_preparing) else "$percent%")
            .setProgress(100, percent, indeterminate)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // Android 13+ 未授予 POST_NOTIFICATIONS 权限时静默失败
        }
    }
}
