package com.tracktosearch.data.util

import android.graphics.Bitmap
import androidx.palette.graphics.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 海报主色调提取器。
 * 先查 PosterColorCache,命中直接返回;未命中用 Palette 异步提取 dominantColor,写缓存后返回。
 */
@Singleton
class PosterColorExtractor @Inject constructor(
    private val cache: PosterColorCache
) {
    suspend fun extractDominantColor(posterUrl: String, bitmap: Bitmap): Long = withContext(Dispatchers.Default) {
        cache.getColor(posterUrl)?.let { return@withContext it }

        // HARDWARE bitmap 不支持 getPixels,需先 copy 成 ARGB_8888
        val safeBitmap = if (bitmap.config == Bitmap.Config.HARDWARE) {
            bitmap.copy(Bitmap.Config.ARGB_8888, false)
        } else {
            bitmap
        }
        val palette = Palette.from(safeBitmap).generate()
        val argb = palette.getDominantColor(0).toLong()

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
}
