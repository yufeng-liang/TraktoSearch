package com.tracktosearch.data.remote

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.Source
import okio.buffer
import java.util.concurrent.ConcurrentHashMap

/**
 * 图片下载进度总线。
 *
 * Coil 不暴露下载进度，这里在图片 OkHttp client 上包一层 ResponseBody 统计已读字节，
 * 供全屏大图查看器显示确定性进度环（original 剧照常有 2-5MB，纯 spinner 无法判断还要等多久）。
 *
 * 只有被 UI [watch] 的 URL 才会建立状态并被包装，因此 map 不会无界增长；
 * UI 离开时必须 [unwatch]。
 */
object ImageDownloadProgress {

    private val watched = ConcurrentHashMap<String, MutableStateFlow<Float>>()

    /** 开始观察某 URL 的下载进度，返回 0f..1f 的进度流（-1f 表示长度未知无法计算） */
    fun watch(url: String): StateFlow<Float> = watched.getOrPut(url) { MutableStateFlow(0f) }

    /** 停止观察并释放状态，UI 销毁时必须调用 */
    fun unwatch(url: String) {
        watched.remove(url)
    }

    private fun report(url: String, bytesRead: Long, contentLength: Long) {
        val flow = watched[url] ?: return
        if (contentLength <= 0L) {
            flow.value = -1f // 分块/压缩响应拿不到总长度，UI 退化为不确定进度
            return
        }
        val fraction = (bytesRead.toFloat() / contentLength).coerceIn(0f, 1f)
        // 每 8KB 一次读取，3MB 图片会触发近 400 次更新；只在变化超过 1% 时上报，避免无谓重组
        if (fraction >= 1f || fraction - flow.value >= 0.01f) {
            flow.value = fraction
        }
    }

    /**
     * 图片 client 拦截器：仅对正在被观察的 URL 包装 body 统计进度，
     * 其余请求零开销原样透传。
     */
    val interceptor: Interceptor = Interceptor { chain ->
        val response = chain.proceed(chain.request())
        val url = chain.request().url.toString()
        val body = response.body
        if (body == null || !watched.containsKey(url)) {
            response
        } else {
            response.newBuilder().body(ProgressResponseBody(body, url)).build()
        }
    }

    private class ProgressResponseBody(
        private val body: ResponseBody,
        private val url: String,
    ) : ResponseBody() {

        private val bufferedSource: BufferedSource by lazy {
            countingSource(body.source(), body.contentLength()).buffer()
        }

        override fun contentType(): MediaType? = body.contentType()

        override fun contentLength(): Long = body.contentLength()

        override fun source(): BufferedSource = bufferedSource

        /**
         * 注意不要在 object 体里引用外层的 body 字段：
         * okio.ForwardingSource 自身有 `delegate` 属性，同名遮蔽很容易踩坑，
         * 这里把总长度作为参数提前捕获。
         */
        private fun countingSource(source: Source, totalBytes: Long): Source =
            object : ForwardingSource(source) {
                private var read = 0L

                override fun read(sink: Buffer, byteCount: Long): Long {
                    val count = super.read(sink, byteCount)
                    if (count == -1L) {
                        report(url, totalBytes.coerceAtLeast(0L), totalBytes)
                    } else {
                        read += count
                        report(url, read, totalBytes)
                    }
                    return count
                }
            }
    }
}
