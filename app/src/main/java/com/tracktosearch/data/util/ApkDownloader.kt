package com.tracktosearch.data.util

import android.content.Context
import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

object ApkDownloader {
    suspend fun downloadApk(
        context: Context,
        url: String,
        fileName: String,
        onProgress: (Float) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()

        val request = Request.Builder().url(url).build()
        val response = client.newCall(request).execute()

        if (!response.isSuccessful) {
            throw Exception("下载失败: HTTP ${response.code}")
        }

        val body = response.body ?: throw Exception("下载失败: 响应为空")
        val contentLength = body.contentLength()

        // 保存到 app 专属外部存储目录，不需要额外权限
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: throw Exception("无法访问下载目录")
        val file = File(dir, fileName)

        // 删除旧文件
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
                    if (contentLength > 0) {
                        onProgress(bytesRead.toFloat() / contentLength.toFloat())
                    }
                }
            }
        }

        file
    }
}
