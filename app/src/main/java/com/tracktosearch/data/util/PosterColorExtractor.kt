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

        val palette = Palette.from(bitmap).generate()
        val argb = palette.getDominantColor(0).toLong()

        if (argb != 0L) {
            cache.putColor(posterUrl, argb)
        }
        argb
    }
}
