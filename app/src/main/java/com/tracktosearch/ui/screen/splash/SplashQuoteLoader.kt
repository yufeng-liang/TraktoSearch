package com.tracktosearch.ui.screen.splash

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.asImageBitmap
import com.tracktosearch.data.local.LanguageStorage
import com.tracktosearch.data.local.SplashPosterStore
import com.tracktosearch.data.local.SplashQuote
import com.tracktosearch.data.repository.SplashQuoteRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 把当天的台词和海报组装成可直接渲染的 [SplashQuoteUi]。
 *
 * 全程在 IO 线程完成，返回给 UI 时已经没有任何待加载的东西。
 * 任何一步拿不到东西（台词库空、语言缺行、海报解不出来）都返回 null，
 * 由调用方跳过整个台词层——宁可不显示，也不显示半成品。
 */
@Singleton
class SplashQuoteLoader @Inject constructor(
    private val repository: SplashQuoteRepository,
    private val posterStore: SplashPosterStore,
) {

    suspend fun load(language: String): SplashQuoteUi? = withContext(Dispatchers.IO) {
        try {
            val quote = repository.todayQuote() ?: return@withContext null
            val lang = resolveLang(language)
            // 台词可能走英文原文，片名始终跟界面语言：混排时片名才认得出是哪部片
            val lineLang = quote.lineLang(lang)
            val lines = quote.linesFor(lineLang)
            if (lines.isEmpty()) return@withContext null
            val bytes = posterStore.readBytes(quote) ?: return@withContext null
            val bitmap = decodePoster(bytes) ?: return@withContext null
            // 这里只判断展示档位，不提前落「已展示」标记：用户此时可能仍停在激活页，
            // 真正开演后由 MainActivity 的 onSplashQuoteShown 统一记录。
            val isFirstToday = repository.isFirstShowToday()
            SplashQuoteUi(
                quoteId = quote.id,
                lines = lines,
                title = quote.titleFor(lang),
                year = quote.year,
                poster = bitmap.asImageBitmap(),
                isEnglish = lineLang == SplashQuote.FALLBACK_LANG,
                titleWrap = SplashQuote.titleWrap(lang),
                keyword = quote.keywordFor(lang),
                sealKeyword = quote.sealKeywordFor(lang),
                sealLang = lang,
                keywordLatin = quote.keywordFor(SplashQuote.FALLBACK_LANG)
                    .takeIf { lang != SplashQuote.FALLBACK_LANG && it.isNotBlank() },
                isFirstToday = isFirstToday,
            )
        } catch (e: Exception) {
            // 解码 OOM 或文件损坏：开屏不值得为此崩溃，跳过台词层即可
            null
        }
    }

    /** 跟随系统时按系统语言取，落不到支持的四种就回英文 */
    private fun resolveLang(language: String): String {
        val tag = if (language == LanguageStorage.LANGUAGE_SYSTEM) {
            Locale.getDefault().language
        } else {
            language
        }
        return SplashQuote.resolveLang(tag)
    }

    /**
     * 按显示尺寸解码海报，不按原图尺寸解。
     *
     * 海报在纸上的宽度是 [POSTER_TARGET_WIDTH_PX]，当前源图（TMDB w342）本来就没超过它，
     * 所以今天这一步只把尺寸读出来、采样比留在 1，画质与耗时都不变。
     * 它的意义在以后：源图档位一旦调大（w500/w780），或者台词库换成更大的图，
     * 原样全解会让「日签上屏」这条关键路径凭空多出几倍解码时间与内存——而多出来的像素
     * 没有一个能落到屏幕上。采样比只在会缩小时才生效，不会放大，
     * 所以这条规则只可能在源图变大时省钱，不会在小图上损失画质。
     */
    private fun decodePoster(bytes: ByteArray): android.graphics.Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth)
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    /** 取 2 的幂里最小的那个，使缩小后的宽度仍不小于目标宽度 */
    private fun sampleSizeFor(sourceWidth: Int): Int {
        var sample = 1
        while (sourceWidth / (sample * 2) >= POSTER_TARGET_WIDTH_PX) {
            sample *= 2
        }
        return sample
    }

    private companion object {
        /**
         * 海报在日签页上的宽度（238dp）折算到 3.5 倍的 xxhdpi 屏，再留两成余量。
         * 比这个小，图上屏就开始发虚；比这个大只是白解像素。
         */
        const val POSTER_TARGET_WIDTH_PX = 1000
    }
}
