package com.tracktosearch.data.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Environment
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.tracktosearch.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * 下载完成但 SHA-256 不匹配。单独建类型而不是抛裸 Exception：
 * toUserMessage 会把未知异常一律压成「下载失败，请尝试浏览器下载」，
 * 用户照提示点浏览器下载就等于绕开了完整性校验。
 */
class ApkIntegrityException(message: String) : Exception(message)

/**
 * 用户主动取消下载（弹窗按钮或通知栏动作）。单独建类型：
 * 混进通用失败分支会给用户弹一记「下载失败」，取消不是失败。
 */
class ApkDownloadCancelledException : Exception("APK download cancelled by user")

object ApkDownloader {
    private const val CHANNEL_ID = "apk_download"
    private const val NOTIFICATION_ID = 1001

    /**
     * 当前正在进行的下载调用。读流是阻塞的，取消协程并不会打断 read()，
     * 必须 call.cancel() 才能真正停下来 —— 取消入口（弹窗按钮 / 通知栏动作）都走这里。
     */
    private val activeCall = AtomicReference<Call?>(null)
    private val cancelRequested = AtomicBoolean(false)

    /** 取消当前下载；返回是否确实有一个在途请求被取消 */
    fun cancelActiveDownload(): Boolean {
        val call = activeCall.getAndSet(null) ?: return false
        cancelRequested.set(true)
        call.cancel()
        return true
    }

    suspend fun downloadApk(
        context: Context,
        url: String,
        fileName: String,
        /** 每读一个块回调一次：(已读字节数, 总字节数)；总字节数未知时 totalBytes 传 0（UI 转 indeterminate） */
        onProgress: (bytesRead: Long, totalBytes: Long) -> Unit,
        fallbackUrl: String = "",
        /** APK 期望 SHA-256（小写 hex），非空时下载完成后校验，不匹配抛异常并删除文件 */
        expectedSha256: String = "",
        /** 通知栏标题里的版本名；留空则退回通道名 */
        version: String = ""
    ): File = withContext(Dispatchers.IO) {
        // APK 下载走匿名请求（GitHub releases 查询已走网关代理）。
        // 移除直连 GitHub token 注入，避免密钥编译进 APK 被反编译泄露。
        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()

        cancelRequested.set(false)
        createDownloadChannel(context)

        // 每读一个 8KB 块回调一次，一次下载要回调上千次；
        // 通知按整数百分比去重，否则系统通知通道会被无害的重复刷新打满。
        var lastNotifiedPercent = -2
        val reportProgress: (Long, Long) -> Unit = { bytesRead, totalBytes ->
            onProgress(bytesRead, totalBytes)
            val percent = if (totalBytes <= 0) -1 else ((bytesRead * 100) / totalBytes).toInt().coerceIn(0, 100)
            if (percent != lastNotifiedPercent) {
                lastNotifiedPercent = percent
                showDownloadNotification(context, bytesRead, totalBytes, version)
            }
        }
        showDownloadNotification(context, 0L, 0L, version)

        try {
            // 尝试主 URL
            val result = tryDownload(client, url, context, fileName) { bytesRead, totalBytes ->
                reportProgress(bytesRead, totalBytes)
            }

            // 主 URL 失败且有备用 URL，自动降级尝试
            val finalResult = if (result == null && fallbackUrl.isNotEmpty()) {
                tryDownload(client, fallbackUrl, context, fileName) { bytesRead, totalBytes ->
                    reportProgress(bytesRead, totalBytes)
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
                    throw ApkIntegrityException(context.getString(R.string.download_sha256_mismatch))
                }
            }

            NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
            finalResult
        } catch (e: Exception) {
            NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
            throw e
        } finally {
            activeCall.set(null)
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
        var file: File? = null
        return try {
            val request = Request.Builder().url(url).build()
            val call = client.newCall(request)
            activeCall.set(call)
            response = call.execute()

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
            file = File(dir, fileName)

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
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 协程取消不能吞：吞掉会把「用户取消」伪装成「下载失败」，外层还会继续走降级重试
            file?.delete() // 清掉半截 APK，避免下次校验前误用
            throw e
        } catch (e: Exception) {
            file?.delete()
            // call.cancel() 会让读流抛 IOException：这不是失败，是用户取消，
            // 走降级重试或弹「下载失败」都是在替用户重新发起他已经取消的动作
            if (cancelRequested.get()) throw ApkDownloadCancelledException()
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

    /**
     * 下载进度通知：内部按 bytesRead/totalBytes 换算百分比，totalBytes 未知（<=0）时转 indeterminate。
     * 标题带版本号（「正在下载 v3.7.0」），复用按钮文案「内置下载」看不出在下载什么；
     * 进度条保留系统配色，通知栏不属于应用内主题。
     */
    private fun showDownloadNotification(context: Context, bytesRead: Long, totalBytes: Long, version: String) {
        val indeterminate = totalBytes <= 0
        val percent = if (indeterminate) 0 else ((bytesRead * 100) / totalBytes).toInt().coerceIn(0, 100)
        val cancelIntent = Intent(context, ApkDownloadActionReceiver::class.java)
            .setAction(ApkDownloadActionReceiver.ACTION_CANCEL)
        val cancelPending = PendingIntent.getBroadcast(
            context,
            NOTIFICATION_ID,
            cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(
                if (version.isNotBlank()) context.getString(R.string.update_notification_title, version)
                else context.getString(R.string.notification_channel_apk_download)
            )
            .setContentText(if (indeterminate) context.getString(R.string.download_preparing) else "$percent%")
            .setProgress(100, percent, indeterminate)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .addAction(0, context.getString(R.string.update_cancel_download), cancelPending)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // Android 13+ 未授予 POST_NOTIFICATIONS 权限时静默失败
        }
    }
}
