package com.tracktosearch.data.util

import android.content.Context
import android.os.Environment
import com.tracktosearch.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

object ApkDownloader {
    suspend fun downloadApk(
        context: Context,
        url: String,
        fileName: String,
        onProgress: (Float) -> Unit,
        fallbackUrl: String = ""
    ): File = withContext(Dispatchers.IO) {
        val githubToken = BuildConfig.GITHUB_UPDATE_TOKEN

        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .followRedirects(true)
            .apply {
                if (githubToken.isNotEmpty()) {
                    addNetworkInterceptor { chain ->
                        val request = chain.request()
                        val host = request.url.host
                        if (host == "github.com" || host.endsWith("github.com") ||
                            host == "objects.githubusercontent.com" || host.endsWith("githubusercontent.com")) {
                            val newRequest = request.newBuilder()
                                .header("Authorization", "token $githubToken")
                                .build()
                            chain.proceed(newRequest)
                        } else {
                            chain.proceed(request)
                        }
                    }
                }
            }
            .build()

        // 尝试主 URL
        val result = tryDownload(client, url, context, fileName, onProgress)

        // 主 URL 失败且有备用 URL，自动降级尝试
        if (result == null && fallbackUrl.isNotEmpty()) {
            tryDownload(client, fallbackUrl, context, fileName, onProgress)
                ?: throw Exception("主URL和备用URL均下载失败")
        } else if (result == null) {
            throw Exception("下载失败")
        } else {
            result
        }
    }

    private suspend fun tryDownload(
        client: OkHttpClient,
        url: String,
        context: Context,
        fileName: String,
        onProgress: (Float) -> Unit
    ): File? {
        var response: okhttp3.Response? = null
        return try {
            val request = Request.Builder().url(url).build()
            response = client.newCall(request).execute()

            if (!response.isSuccessful) {
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
                        if (contentLength > 0) {
                            onProgress(bytesRead.toFloat() / contentLength.toFloat())
                        }
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
}