package com.tracktosearch.data.local

import android.content.Context
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 直接对 assets/quotes.json 本体做体检。
 *
 * 台词库是手写的 365 条数据，只靠人眼过不了：一条缺了某种语言、或者中文关键词写成 5 个字，
 * 到线上就是当天开屏空一块或者印章挤出边框。这里把「文件本身必须成立」的那些约束钉死，
 * 以后往库里加片子，跑一次测试就知道有没有写漏。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SplashQuoteCatalogTest {

    private val appContext: Context = RuntimeEnvironment.getApplication()

    private val langs = listOf("en", "zh", "ja", "ko")

    private suspend fun quotes() = SplashQuoteCatalog(appContext).quotes()

    @Test
    fun `台词库能解析出条目且 id 不重复`() = runTest {
        val quotes = quotes()

        assertThat(quotes).isNotEmpty()
        assertThat(quotes.map { it.id }).containsNoDuplicates()
    }

    @Test
    fun `每条台词四种语言的行和片名都不缺`() = runTest {
        quotes().forEach { quote ->
            langs.forEach { lang ->
                assertThat(quote.lines[lang]).isNotNull()
                assertThat(quote.lines.getValue(lang)).isNotEmpty()
                assertThat(quote.lines.getValue(lang).none { it.isBlank() }).isTrue()
                assertThat(quote.title[lang]).isNotEmpty()
            }
        }
    }

    @Test
    fun `每条台词四种语言的关键词都不缺`() = runTest {
        quotes().forEach { quote ->
            langs.forEach { lang ->
                assertThat(quote.keyword[lang]).isNotEmpty()
            }
        }
    }

    /** 印章是方的，中文超过 4 个字就得缩到看不清；少于 2 个字又撑不住版面 */
    @Test
    fun `中文关键词限 2 到 4 个字`() = runTest {
        quotes().forEach { quote ->
            assertThat(quote.keywordFor("zh").length).isIn(2..4)
        }
    }

    /** 日签日历一个月里可能撞上重复的词，翻起来会以为自己看错了 */
    @Test
    fun `中文关键词互不重复`() = runTest {
        assertThat(quotes().map { it.keywordFor("zh") }).containsNoDuplicates()
    }

    @Test
    fun `海报路径都是 TMDB 的相对路径`() = runTest {
        quotes().forEach { quote ->
            assertThat(quote.posterPath).startsWith("/")
            assertThat(quote.tmdbId).isGreaterThan(0)
        }
    }

    @Test
    fun `preferOriginal 的条目走英文原文其余跟界面语言`() = runTest {
        val quotes = quotes()
        val original = quotes.filter { it.preferOriginal }
        val translated = quotes.filterNot { it.preferOriginal }

        assertThat(original).isNotEmpty()
        assertThat(translated).isNotEmpty()
        original.forEach { assertThat(it.lineLang("zh")).isEqualTo(SplashQuote.FALLBACK_LANG) }
        translated.forEach { assertThat(it.lineLang("zh")).isEqualTo("zh") }
    }

    @Test
    fun `缺失语言时行片名关键词都回落英文`() = runTest {
        val quote = quotes().first()

        assertThat(quote.linesFor("de")).isEqualTo(quote.linesFor("en"))
        assertThat(quote.titleFor("de")).isEqualTo(quote.titleFor("en"))
        assertThat(quote.keywordFor("de")).isEqualTo(quote.keywordFor("en"))
    }
}
