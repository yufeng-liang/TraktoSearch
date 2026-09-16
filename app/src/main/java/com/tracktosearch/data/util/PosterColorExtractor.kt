package com.tracktosearch.data.util

import android.graphics.Bitmap
import com.tracktosearch.data.util.mcu.quantize.QuantizerCelebi
import com.tracktosearch.data.util.mcu.score.Score
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 海报主色调提取器。
 * 先查 PosterColorCache,命中直接返回;未命中用 Material Color Utilities(QuantizerCelebi + Score)
 * 异步提取主色,写缓存后返回。
 *
 * 算法说明:QuantizerCelebi 做色彩量化(Wu 预量化 + WSMeans 细化),Score 按 population、
 * 彩色度与黑白对比度打分排序,选出视觉上最适合做主题背景的颜色,避免纯按像素数量取色
 * 导致大面积暗部/背景"脏色"胜出的问题。
 */
@Singleton
class PosterColorExtractor @Inject constructor(
    private val cache: PosterColorCache
) {
    /** 仅查当前进程内存缓存，不挂起、不触发磁盘读取。 */
    fun peekCachedColor(posterUrl: String): Long? = cache.peekColor(posterUrl)

    /**
     * 按 TMDB 各尺寸候选 key 查内存缓存。
     *
     * 同一张海报在列表与详情页可能用不同尺寸的 URL（w342 / w780），
     * 写入与读取的 key 因此可能对不上；这里统一把候选尺寸都试一遍。
     */
    fun peekCachedColorCandidates(posterUrl: String): Long? {
        val candidates = posterCacheKeyCandidates(posterUrl)
        for (url in candidates) {
            val argb = cache.peekColor(url)
            if (argb != null && argb != 0L) return argb
        }
        return null
    }

    suspend fun extractDominantColor(posterUrl: String, bitmap: Bitmap): Long = withContext(Dispatchers.Default) {
        cache.getColor(posterUrl)?.let { return@withContext it }

        // HARDWARE bitmap 不支持 getPixels,需先 copy 成 ARGB_8888
        val safeBitmap = if (bitmap.config == Bitmap.Config.HARDWARE) {
            bitmap.copy(Bitmap.Config.ARGB_8888, false)
        } else {
            bitmap
        }
        // 降采样到最大边 48px 再量化,QuantizerCelebi 只统计颜色分布,缩小后大幅减少像素计算量
        val sample = Bitmap.createScaledBitmap(
            safeBitmap,
            minOf(safeBitmap.width, MAX_SAMPLE_SIZE),
            minOf(safeBitmap.height, MAX_SAMPLE_SIZE),
            true
        )
        val pixels = IntArray(sample.width * sample.height)
        sample.getPixels(pixels, 0, sample.width, 0, 0, sample.width, sample.height)

        val colorToPopulation = QuantizerCelebi.quantize(pixels, MAX_QUANTIZE_COLORS)
        val argb = Score.score(colorToPopulation).firstOrNull()?.toLong() ?: 0L

        if (argb != 0L) {
            cache.putColor(posterUrl, argb)
        }
        argb
    }

    /**
     * 仅查缓存的主色调查询,不需要 bitmap。
     * 用于进入详情页时尽早拿到主色(命中则瞬间显示沉浸背景,未命中仍需等海报加载后用 [extractDominantColor])。
     */
    suspend fun getCachedColor(posterUrl: String): Long? = withContext(Dispatchers.Default) {
        cache.getColor(posterUrl)
    }

    private companion object {
        /** 降采样最大边长,保持速度与质量平衡 */
        const val MAX_SAMPLE_SIZE = 48

        /** 量化目标颜色数,Score 将从这些代表色中打分排名 */
        const val MAX_QUANTIZE_COLORS = 128
    }
}
