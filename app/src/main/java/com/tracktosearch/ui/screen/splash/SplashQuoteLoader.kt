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
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ?: return@withContext null
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
}
