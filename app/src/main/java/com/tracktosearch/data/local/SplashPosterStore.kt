package com.tracktosearch.data.local

import android.content.Context
import com.tracktosearch.data.remote.tmdb.TmdbImageUrls
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 开屏海报的专用磁盘存储。
 *
 * 不复用 Coil 的图片缓存，原因有两个：
 * 1. 那是 888MB 的 LRU，浏览几百张海报就可能把开屏用的那张挤掉；
 * 2. 它在 cacheDir 下，系统清理缓存或存储紧张时会被抹掉。
 * 开屏要求「永远不出现占位图」，所以放 filesDir 下自己管，整池 365 条约 15MB。
 *
 * 内置的 6 条海报随 APK 走 assets，不占这里的空间，也不需要下载。
 */
@Singleton
class SplashPosterStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient,
) {
    private val dir: File by lazy {
        File(context.filesDir, DIR_NAME).apply { if (!exists()) mkdirs() }
    }

    /** 已下载海报的落盘位置 */
    private fun file(id: String): File = File(dir, "$id.jpg")

    /** assets 内置海报的相对路径 */
    private fun assetPath(id: String): String = "$ASSET_DIR/$id.jpg"

    /**
     * 海报是否已就绪：内置的直接算就绪，其余看文件是否存在且非空。
     *
     * 长度为 0 的文件当作没下载——下载中途被杀会留下空文件，
     * 当成就绪会让开屏拿到一张解不出来的图，正是要避免的占位图场景。
     */
    fun isReady(quote: SplashQuote): Boolean =
        quote.bundled || file(quote.id).let { it.exists() && it.length() > 0 }

    /**
     * 取可直接解码的海报字节来源。内置走 assets，其余走 filesDir。
     * 返回 null 表示还没就绪，调用方不应该退化成占位图，而应换一条已就绪的台词。
     */
    suspend fun readBytes(quote: SplashQuote): ByteArray? = withContext(Dispatchers.IO) {
        try {
            if (quote.bundled) {
                context.assets.open(assetPath(quote.id)).use { it.readBytes() }
            } else {
                val f = file(quote.id)
                if (f.exists() && f.length() > 0) f.readBytes() else null
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Coil 能直接吃的海报来源：内置的给 assets URI，其余给 [File]。
     *
     * 给日签日历用。那一屏最多 31 张缩略图，走 [readBytes] 就是 31 次全量读盘 + 手动解码；
     * 交给 Coil 才能按控件尺寸降采样并复用它的内存缓存。
     * 返回 null 表示还没就绪，调用方按「没有这张图」处理，不要画占位框。
     */
    fun posterModel(quote: SplashQuote): Any? = if (quote.bundled) {
        "file:///android_asset/${assetPath(quote.id)}"
    } else {
        file(quote.id).takeIf { it.exists() && it.length() > 0 }
    }

    /**
     * 下载单条海报，已就绪则跳过。返回是否处于就绪状态。
     *
     * 先写临时文件再改名：直接写目标文件的话，下载被中断就留下一个半张图的文件，
     * 而 [isReady] 只看长度非零，会把它当成完整海报。
     */
    suspend fun download(quote: SplashQuote): Boolean = withContext(Dispatchers.IO) {
        if (isReady(quote)) return@withContext true
        val url = TmdbImageUrls.build(quote.posterPath, TmdbImageUrls.W342)
        val target = file(quote.id)
        val temp = File(dir, "${quote.id}.part")
        try {
            val request = Request.Builder().url(url).build()
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext false
                temp.outputStream().use { out -> response.body.byteStream().copyTo(out) }
            }
            if (temp.length() <= 0) {
                temp.delete()
                return@withContext false
            }
            temp.renameTo(target)
        } catch (e: Exception) {
            temp.delete()
            return@withContext false
        }
        isReady(quote)
    }

    /** 清掉不在当前台词库里的遗留海报（台词下线后不该继续占空间） */
    suspend fun pruneOrphans(validIds: Set<String>) = withContext(Dispatchers.IO) {
        try {
            dir.listFiles()?.forEach { f ->
                val id = f.name.removeSuffix(".jpg").removeSuffix(".part")
                if (id !in validIds) f.delete()
            }
        } catch (e: Exception) {
            // 清理失败只是多占几十 KB，不值得让调用方处理
        }
    }

    companion object {
        private const val DIR_NAME = "splash_posters"
        private const val ASSET_DIR = "splash_posters"
    }
}
