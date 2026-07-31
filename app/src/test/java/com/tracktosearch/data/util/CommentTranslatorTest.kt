package com.tracktosearch.data.util

import android.util.LruCache
import com.google.common.truth.Truth.assertThat
import com.tracktosearch.data.remote.trakt.dto.TraktComment
import com.tracktosearch.data.remote.translate.TranslateApiService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.spyk
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * CommentTranslator 单元测试。
 *
 * 验证翻译缓存链路、AI→通用降级、长文本不降级、并发翻译保持顺序、
 * 语言代码映射、MD5 签名等核心逻辑。
 *
 * 测试策略：
 * - 用 spyk(CommentTranslator(), recordPrivateCalls = true) 创建实例
 * - mock private 的 translateWithBaiduAI / translateWithBaidu 方法（避免真实 HTTP 调用）
 * - 通过反射访问 translationCache（验证缓存写入）
 * - 通过反射调用 getTargetLangCode / md5（private 方法）
 * - 用 Locale.setDefault() 控制设备语言
 *
 * 注意：translateOneComment 包含 withTimeoutOrNull(5000)，mock 方法立即返回不触发超时；
 * translateComments 用 withContext(Dispatchers.IO) + async/awaitAll，用 runBlocking 避免虚拟时间问题。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class CommentTranslatorTest {

    private val translateApi = io.mockk.mockk<TranslateApiService>(relaxed = true)
    private lateinit var translator: CommentTranslator
    private var originalLocale: Locale? = null

    @Before
    fun setup() {
        originalLocale = Locale.getDefault()
        translator = spyk(CommentTranslator(translateApi), recordPrivateCalls = true)
    }

    @After
    fun teardown() {
        originalLocale?.let { Locale.setDefault(it) }
    }

    // ==================== 反射辅助 ====================

    /** 获取 translationCache（private LruCache<Int, String>） */
    private fun getTranslationCache(): LruCache<Int, String> {
        val field = CommentTranslator::class.java.getDeclaredField("translationCache")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return field.get(translator) as LruCache<Int, String>
    }

    /** 反射调用 private getTargetLangCode() */
    private fun callGetTargetLangCode(): String {
        val method = CommentTranslator::class.java.getDeclaredMethod("getTargetLangCode")
        method.isAccessible = true
        return method.invoke(translator) as String
    }

    /** 反射调用 private md5(str) */
    private fun callMd5(str: String): String {
        val method = CommentTranslator::class.java.getDeclaredMethod("md5", String::class.java)
        method.isAccessible = true
        return method.invoke(translator, str) as String
    }

    /** mock translateWithBaiduAI 返回指定结果 */
    private fun mockBaiduAI(result: String?) {
        coEvery {
            translator["translateWithBaiduAI"](any<String>(), any<String>())
        } returns result
    }

    /** mock translateWithBaidu 返回指定结果 */
    private fun mockBaiduGeneric(result: String?) {
        coEvery {
            translator["translateWithBaidu"](any<String>(), any<String>())
        } returns result
    }

    // ==================== getTargetLangCode 语言映射 ====================

    @Test
    fun getTargetLangCode_zh映射为zh() {
        Locale.setDefault(Locale.CHINESE)
        assertThat(callGetTargetLangCode()).isEqualTo("zh")
    }

    @Test
    fun getTargetLangCode_ja映射为jp() {
        Locale.setDefault(Locale.JAPANESE)
        assertThat(callGetTargetLangCode()).isEqualTo("jp")
    }

    @Test
    fun getTargetLangCode_ko映射为kor() {
        Locale.setDefault(Locale.KOREAN)
        assertThat(callGetTargetLangCode()).isEqualTo("kor")
    }

    @Test
    fun getTargetLangCode_fr映射为fra() {
        Locale.setDefault(Locale.FRANCE)
        assertThat(callGetTargetLangCode()).isEqualTo("fra")
    }

    @Test
    fun getTargetLangCode_de映射为de() {
        Locale.setDefault(Locale.GERMANY)
        assertThat(callGetTargetLangCode()).isEqualTo("de")
    }

    @Test
    fun getTargetLangCode_es映射为spa() {
        val spanish = Locale("es")
        Locale.setDefault(spanish)
        assertThat(callGetTargetLangCode()).isEqualTo("spa")
    }

    @Test
    fun getTargetLangCode_pt映射为pt() {
        val portuguese = Locale("pt")
        Locale.setDefault(portuguese)
        assertThat(callGetTargetLangCode()).isEqualTo("pt")
    }

    @Test
    fun getTargetLangCode_ru映射为ru() {
        val russian = Locale("ru")
        Locale.setDefault(russian)
        assertThat(callGetTargetLangCode()).isEqualTo("ru")
    }

    @Test
    fun getTargetLangCode_it映射为it() {
        val italian = Locale("it")
        Locale.setDefault(italian)
        assertThat(callGetTargetLangCode()).isEqualTo("it")
    }

    @Test
    fun getTargetLangCode_ar映射为ara() {
        val arabic = Locale("ar")
        Locale.setDefault(arabic)
        assertThat(callGetTargetLangCode()).isEqualTo("ara")
    }

    @Test
    fun getTargetLangCode_th映射为th() {
        val thai = Locale("th")
        Locale.setDefault(thai)
        assertThat(callGetTargetLangCode()).isEqualTo("th")
    }

    @Test
    fun getTargetLangCode_vi映射为vie() {
        val vietnamese = Locale("vi")
        Locale.setDefault(vietnamese)
        assertThat(callGetTargetLangCode()).isEqualTo("vie")
    }

    @Test
    fun getTargetLangCode_en映射为en() {
        Locale.setDefault(Locale.ENGLISH)
        assertThat(callGetTargetLangCode()).isEqualTo("en")
    }

    @Test
    fun getTargetLangCode_未知语言降级为zh() {
        val unknown = Locale("xx") // 不在映射表中的语言
        Locale.setDefault(unknown)
        assertThat(callGetTargetLangCode()).isEqualTo("zh")
    }

    // ==================== md5 签名 ====================

    @Test
    fun md5_正常字符串计算正确() {
        // MD5("hello") = 5d41402abc4b2a76b9719d911017c592
        assertThat(callMd5("hello")).isEqualTo("5d41402abc4b2a76b9719d911017c592")
    }

    @Test
    fun md5_空字符串() {
        // MD5("") = d41d8cd98f00b204e9800998ecf8427e
        assertThat(callMd5("")).isEqualTo("d41d8cd98f00b204e9800998ecf8427e")
    }

    @Test
    fun md5_包含中文字符() {
        // 验证不抛异常且返回 32 位十六进制字符串
        val result = callMd5("测试中文")
        assertThat(result).hasLength(32)
        assertThat(result).matches("[0-9a-f]{32}")
    }

    // ==================== translateSingleComment ====================

    @Test
    fun translateSingleComment_设备语言为en_直接返回不翻译() = runBlocking {
        Locale.setDefault(Locale.ENGLISH)
        val comment = TraktComment(id = 1, comment = "english comment")

        val result = translator.translateSingleComment(comment)

        assertThat(result.comment).isEqualTo("english comment")
        // 不应调用任何翻译 API
        coVerify(exactly = 0) { translator["translateWithBaiduAI"](any<String>(), any<String>()) }
        coVerify(exactly = 0) { translator["translateWithBaidu"](any<String>(), any<String>()) }
    }

    @Test
    fun translateSingleComment_缓存命中_不调API() = runBlocking {
        Locale.setDefault(Locale.CHINESE)
        val comment = TraktComment(id = 20, comment = "original")
        // 预填充缓存
        getTranslationCache().put(20, "缓存翻译")

        val result = translator.translateSingleComment(comment)

        assertThat(result.comment).isEqualTo("缓存翻译")
        coVerify(exactly = 0) { translator["translateWithBaiduAI"](any<String>(), any<String>()) }
        coVerify(exactly = 0) { translator["translateWithBaidu"](any<String>(), any<String>()) }
    }

    @Test
    fun translateSingleComment_缓存未命中_AI翻译成功_写入缓存() = runBlocking {
        Locale.setDefault(Locale.CHINESE)
        mockBaiduAI("中文翻译")
        val comment = TraktComment(id = 21, comment = "english text")

        val result = translator.translateSingleComment(comment)

        assertThat(result.comment).isEqualTo("中文翻译")
        assertThat(getTranslationCache().get(21)).isEqualTo("中文翻译")
        coVerify(exactly = 1) { translator["translateWithBaiduAI"](any<String>(), any<String>()) }
        coVerify(exactly = 0) { translator["translateWithBaidu"](any<String>(), any<String>()) }
    }

    @Test
    fun translateSingleComment_翻译结果与原文相同_不写入缓存() = runBlocking {
        Locale.setDefault(Locale.CHINESE)
        val original = "same text"
        mockBaiduAI(original) // 返回与原文相同
        val comment = TraktComment(id = 22, comment = original)

        val result = translator.translateSingleComment(comment)

        assertThat(result.comment).isEqualTo(original)
        assertThat(getTranslationCache().get(22)).isNull()
    }

    @Test
    fun translateSingleComment_AI返回null_降级通用翻译() = runBlocking {
        Locale.setDefault(Locale.CHINESE)
        mockBaiduAI(null)
        mockBaiduGeneric("通用翻译")
        val comment = TraktComment(id = 23, comment = "english text")

        val result = translator.translateSingleComment(comment)

        assertThat(result.comment).isEqualTo("通用翻译")
        assertThat(getTranslationCache().get(23)).isEqualTo("通用翻译")
        coVerify(exactly = 1) { translator["translateWithBaidu"](any<String>(), any<String>()) }
    }

    @Test
    fun translateSingleComment_AI返回空字符串_降级通用翻译() = runBlocking {
        Locale.setDefault(Locale.CHINESE)
        mockBaiduAI("")
        mockBaiduGeneric("通用翻译")
        val comment = TraktComment(id = 24, comment = "english text")

        val result = translator.translateSingleComment(comment)

        assertThat(result.comment).isEqualTo("通用翻译")
        assertThat(getTranslationCache().get(24)).isEqualTo("通用翻译")
        coVerify(exactly = 1) { translator["translateWithBaidu"](any<String>(), any<String>()) }
    }

    @Test
    fun translateSingleComment_AI和通用都失败_返回原文() = runBlocking {
        Locale.setDefault(Locale.CHINESE)
        mockBaiduAI(null)
        mockBaiduGeneric(null)
        val comment = TraktComment(id = 25, comment = "english text")

        val result = translator.translateSingleComment(comment)

        assertThat(result.comment).isEqualTo("english text")
        assertThat(getTranslationCache().get(25)).isNull()
    }

    @Test
    fun translateSingleComment_AI和通用都返回空_返回原文() = runBlocking {
        Locale.setDefault(Locale.CHINESE)
        mockBaiduAI("")
        mockBaiduGeneric("")
        val comment = TraktComment(id = 26, comment = "english text")

        val result = translator.translateSingleComment(comment)

        assertThat(result.comment).isEqualTo("english text")
        assertThat(getTranslationCache().get(26)).isNull()
    }

    @Test
    fun translateSingleComment_长文本_AI失败不降级通用() = runBlocking {
        Locale.setDefault(Locale.CHINESE)
        val longText = "a".repeat(6001) // > 6000
        mockBaiduAI(null)
        mockBaiduGeneric("通用翻译")
        val comment = TraktComment(id = 27, comment = longText)

        val result = translator.translateSingleComment(comment)

        assertThat(result.comment).isEqualTo(longText) // 返回原文
        assertThat(getTranslationCache().get(27)).isNull()
        coVerify(exactly = 0) { translator["translateWithBaidu"](any<String>(), any<String>()) }
    }

    @Test
    fun translateSingleComment_长文本_AI翻译成功_写入缓存() = runBlocking {
        Locale.setDefault(Locale.CHINESE)
        val longText = "a".repeat(6001)
        mockBaiduAI("长文本翻译")
        val comment = TraktComment(id = 28, comment = longText)

        val result = translator.translateSingleComment(comment)

        assertThat(result.comment).isEqualTo("长文本翻译")
        assertThat(getTranslationCache().get(28)).isEqualTo("长文本翻译")
    }

    @Test
    fun translateSingleComment_AI抛异常_返回原文() = runBlocking {
        Locale.setDefault(Locale.CHINESE)
        coEvery {
            translator["translateWithBaiduAI"](any<String>(), any<String>())
        } throws RuntimeException("API error")
        val comment = TraktComment(id = 29, comment = "english text")

        val result = translator.translateSingleComment(comment)

        assertThat(result.comment).isEqualTo("english text")
        assertThat(getTranslationCache().get(29)).isNull()
    }

    @Test
    fun translateSingleComment_通用翻译抛异常_返回原文() = runBlocking {
        Locale.setDefault(Locale.CHINESE)
        mockBaiduAI(null)
        coEvery {
            translator["translateWithBaidu"](any<String>(), any<String>())
        } throws RuntimeException("Generic API error")
        val comment = TraktComment(id = 30, comment = "english text")

        val result = translator.translateSingleComment(comment)

        assertThat(result.comment).isEqualTo("english text")
        assertThat(getTranslationCache().get(30)).isNull()
    }

    @Test
    fun translateSingleComment_保留评论其他字段() = runBlocking {
        Locale.setDefault(Locale.CHINESE)
        mockBaiduAI("中文翻译")
        val comment = TraktComment(
            id = 31,
            comment = "english text",
            spoiler = true,
            review = false,
            likes = 10,
            replies = 2,
            user_rating = 8.0
        )

        val result = translator.translateSingleComment(comment)

        assertThat(result.comment).isEqualTo("中文翻译")
        assertThat(result.id).isEqualTo(31)
        assertThat(result.spoiler).isTrue()
        assertThat(result.likes).isEqualTo(10)
        assertThat(result.replies).isEqualTo(2)
        assertThat(result.user_rating).isEqualTo(8.0)
    }

    // ==================== translateComments ====================

    @Test
    fun translateComments_空列表返回空() = runBlocking {
        Locale.setDefault(Locale.CHINESE)

        val result = translator.translateComments(emptyList())

        assertThat(result).isEmpty()
    }

    @Test
    fun translateComments_设备语言为en_直接返回() = runBlocking {
        Locale.setDefault(Locale.ENGLISH)
        val comments = listOf(
            TraktComment(id = 1, comment = "c1"),
            TraktComment(id = 2, comment = "c2")
        )

        val result = translator.translateComments(comments)

        assertThat(result).hasSize(2)
        assertThat(result[0].comment).isEqualTo("c1")
        assertThat(result[1].comment).isEqualTo("c2")
        coVerify(exactly = 0) { translator["translateWithBaiduAI"](any<String>(), any<String>()) }
    }

    @Test
    fun translateComments_全部缓存命中_不调API() = runBlocking {
        Locale.setDefault(Locale.CHINESE)
        val cache = getTranslationCache()
        cache.put(1, "翻译1")
        cache.put(2, "翻译2")
        val comments = listOf(
            TraktComment(id = 1, comment = "c1"),
            TraktComment(id = 2, comment = "c2")
        )

        val result = translator.translateComments(comments)

        assertThat(result).hasSize(2)
        assertThat(result[0].comment).isEqualTo("翻译1")
        assertThat(result[1].comment).isEqualTo("翻译2")
        coVerify(exactly = 0) { translator["translateWithBaiduAI"](any<String>(), any<String>()) }
    }

    @Test
    fun translateComments_部分缓存部分新_只翻译未缓存的() = runBlocking {
        Locale.setDefault(Locale.CHINESE)
        val cache = getTranslationCache()
        cache.put(1, "缓存翻译1")
        mockBaiduAI("新翻译")
        val comments = listOf(
            TraktComment(id = 1, comment = "c1"), // 缓存命中
            TraktComment(id = 2, comment = "c2")  // 需翻译
        )

        val result = translator.translateComments(comments)

        assertThat(result).hasSize(2)
        assertThat(result[0].comment).isEqualTo("缓存翻译1")
        assertThat(result[1].comment).isEqualTo("新翻译")
        // 只翻译了 1 条
        coVerify(exactly = 1) { translator["translateWithBaiduAI"](any<String>(), any<String>()) }
        // 缓存写入
        assertThat(cache.get(2)).isEqualTo("新翻译")
    }

    @Test
    fun translateComments_全部新_并发翻译保持顺序() = runBlocking {
        Locale.setDefault(Locale.CHINESE)
        mockBaiduAI("翻译")
        val comments = listOf(
            TraktComment(id = 1, comment = "c1"),
            TraktComment(id = 2, comment = "c2"),
            TraktComment(id = 3, comment = "c3"),
            TraktComment(id = 4, comment = "c4"),
            TraktComment(id = 5, comment = "c5")
        )

        val result = translator.translateComments(comments)

        assertThat(result).hasSize(5)
        // 验证顺序保持
        assertThat(result[0].id).isEqualTo(1)
        assertThat(result[1].id).isEqualTo(2)
        assertThat(result[2].id).isEqualTo(3)
        assertThat(result[3].id).isEqualTo(4)
        assertThat(result[4].id).isEqualTo(5)
        // 全部翻译并写入缓存
        coVerify(exactly = 5) { translator["translateWithBaiduAI"](any<String>(), any<String>()) }
        val cache = getTranslationCache()
        assertThat(cache.get(1)).isEqualTo("翻译")
        assertThat(cache.get(5)).isEqualTo("翻译")
    }

    @Test
    fun translateComments_缓存和新评论交错_保持顺序() = runBlocking {
        Locale.setDefault(Locale.CHINESE)
        val cache = getTranslationCache()
        cache.put(2, "缓存翻译2")
        cache.put(4, "缓存翻译4")
        mockBaiduAI("新翻译")
        val comments = listOf(
            TraktComment(id = 1, comment = "c1"), // 新
            TraktComment(id = 2, comment = "c2"), // 缓存
            TraktComment(id = 3, comment = "c3"), // 新
            TraktComment(id = 4, comment = "c4"), // 缓存
            TraktComment(id = 5, comment = "c5")  // 新
        )

        val result = translator.translateComments(comments)

        assertThat(result).hasSize(5)
        assertThat(result[0].id).isEqualTo(1)
        assertThat(result[0].comment).isEqualTo("新翻译")
        assertThat(result[1].id).isEqualTo(2)
        assertThat(result[1].comment).isEqualTo("缓存翻译2")
        assertThat(result[2].id).isEqualTo(3)
        assertThat(result[2].comment).isEqualTo("新翻译")
        assertThat(result[3].id).isEqualTo(4)
        assertThat(result[3].comment).isEqualTo("缓存翻译4")
        assertThat(result[4].id).isEqualTo(5)
        assertThat(result[4].comment).isEqualTo("新翻译")
        // 只翻译了 3 条（id=1,3,5）
        coVerify(exactly = 3) { translator["translateWithBaiduAI"](any<String>(), any<String>()) }
    }

    @Test
    fun translateComments_AI全部失败降级通用_保持顺序() = runBlocking {
        Locale.setDefault(Locale.CHINESE)
        mockBaiduAI(null)
        mockBaiduGeneric("通用翻译")
        val comments = listOf(
            TraktComment(id = 1, comment = "c1"),
            TraktComment(id = 2, comment = "c2"),
            TraktComment(id = 3, comment = "c3")
        )

        val result = translator.translateComments(comments)

        assertThat(result).hasSize(3)
        assertThat(result[0].comment).isEqualTo("通用翻译")
        assertThat(result[1].comment).isEqualTo("通用翻译")
        assertThat(result[2].comment).isEqualTo("通用翻译")
        coVerify(exactly = 3) { translator["translateWithBaidu"](any<String>(), any<String>()) }
    }

    // ==================== translateCommentsFlow（流式翻译） ====================
    // translateCommentsFlow 返回 Flow<Pair<原索引, 译文>>，每条翻译完成立即 emit。
    // 与 translateComments（suspend 返回完整列表）不同，Flow 版本用于 UI 渐进展示。
    // 缓存命中立即 emit，未命中的并发翻译完成后 emit（顺序不确定）。

    /**
     * 空列表：channelFlow 直接 return，不 emit 任何元素。
     */
    @Test
    fun translateCommentsFlow_空列表不emit任何元素() = runBlocking {
        Locale.setDefault(Locale.CHINESE)

        val result = translator.translateCommentsFlow(emptyList()).toList()

        assertThat(result).isEmpty()
        coVerify(exactly = 0) { translator["translateWithBaiduAI"](any<String>(), any<String>()) }
    }

    /**
     * 设备语言为 en：直接原样 emit 每条评论（index to comment）。
     * 验证 emit 的 Pair.index 与 Pair.second.id 一一对应。
     */
    @Test
    fun translateCommentsFlow_设备语言为en_原样按索引emit() = runBlocking {
        Locale.setDefault(Locale.ENGLISH)
        val comments = listOf(
            TraktComment(id = 101, comment = "hello"),
            TraktComment(id = 102, comment = "world")
        )

        val result = translator.translateCommentsFlow(comments).toList()

        assertThat(result).hasSize(2)
        // 验证索引与评论配对正确
        assertThat(result[0].first).isEqualTo(0)
        assertThat(result[0].second.id).isEqualTo(101)
        assertThat(result[0].second.comment).isEqualTo("hello")
        assertThat(result[1].first).isEqualTo(1)
        assertThat(result[1].second.id).isEqualTo(102)
        assertThat(result[1].second.comment).isEqualTo("world")
        coVerify(exactly = 0) { translator["translateWithBaiduAI"](any<String>(), any<String>()) }
    }

    /**
     * 缓存命中：立即 emit (index, comment.copy(comment = cached))。
     * 验证 emit 的译文是缓存值，且不调用翻译 API。
     */
    @Test
    fun translateCommentsFlow_缓存命中立即emit_译文为缓存值() = runBlocking {
        Locale.setDefault(Locale.CHINESE)
        val cache = getTranslationCache()
        cache.put(201, "缓存译文201")
        cache.put(202, "缓存译文202")
        val comments = listOf(
            TraktComment(id = 201, comment = "original1"),
            TraktComment(id = 202, comment = "original2")
        )

        val result = translator.translateCommentsFlow(comments).toList()

        assertThat(result).hasSize(2)
        // 按 index 排序后验证（缓存命中立即 emit，顺序应与输入一致）
        val byIndex = result.associate { it.first to it.second }
        assertThat(byIndex[0]!!.comment).isEqualTo("缓存译文201")
        assertThat(byIndex[0]!!.id).isEqualTo(201)
        assertThat(byIndex[1]!!.comment).isEqualTo("缓存译文202")
        assertThat(byIndex[1]!!.id).isEqualTo(202)
        coVerify(exactly = 0) { translator["translateWithBaiduAI"](any<String>(), any<String>()) }
    }

    /**
     * 未缓存评论并发翻译 emit：用 coAnswers 区分不同输入，验证索引与译文内容严格配对。
     * 回归场景：若并发翻译结果错位写回（originIndex 与 translated 不配对），
     * 用单一返回值 mock 无法捕获，必须用 coAnswers 区分输入。
     */
    @Test
    fun translateCommentsFlow_未缓存评论并发翻译emit_索引配对正确() = runBlocking {
        Locale.setDefault(Locale.CHINESE)
        // 用 coAnswers 区分不同输入，让每条评论返回不同译文
        coEvery { translator["translateWithBaiduAI"](any<String>(), any<String>()) } coAnswers {
            when (firstArg<String>()) {
                "input1" -> "译文1"
                "input2" -> "译文2"
                "input3" -> "译文3"
                else -> null
            }
        }
        val comments = listOf(
            TraktComment(id = 301, comment = "input1"),
            TraktComment(id = 302, comment = "input2"),
            TraktComment(id = 303, comment = "input3")
        )

        val result = translator.translateCommentsFlow(comments).toList()

        assertThat(result).hasSize(3)
        // 并发 emit 顺序不确定，用 index 查找对应的 Pair，验证内容严格配对
        val byIndex = result.associate { it.first to it.second }
        assertThat(byIndex[0]!!.comment).isEqualTo("译文1")
        assertThat(byIndex[0]!!.id).isEqualTo(301)
        assertThat(byIndex[1]!!.comment).isEqualTo("译文2")
        assertThat(byIndex[1]!!.id).isEqualTo(302)
        assertThat(byIndex[2]!!.comment).isEqualTo("译文3")
        assertThat(byIndex[2]!!.id).isEqualTo(303)
        coVerify(exactly = 3) { translator["translateWithBaiduAI"](any<String>(), any<String>()) }
        // 验证缓存写入
        val cache = getTranslationCache()
        assertThat(cache.get(301)).isEqualTo("译文1")
        assertThat(cache.get(302)).isEqualTo("译文2")
        assertThat(cache.get(303)).isEqualTo("译文3")
    }

    /**
     * 部分翻译失败：AI 和通用都失败时，失败项 emit 原文（translateOneComment 返回 comment 原文）。
     * 验证成功项正常翻译，失败项 emit 原文，两者都 emit。
     */
    @Test
    fun translateCommentsFlow_部分翻译失败_失败项emit原文() = runBlocking {
        Locale.setDefault(Locale.CHINESE)
        // id=401 翻译成功，id=402 AI+通用都失败返回原文
        coEvery { translator["translateWithBaiduAI"](any<String>(), any<String>()) } coAnswers {
            when (firstArg<String>()) {
                "good" -> "好"
                else -> null
            }
        }
        coEvery { translator["translateWithBaidu"](any<String>(), any<String>()) } coAnswers {
            when (firstArg<String>()) {
                "bad" -> null
                else -> null
            }
        }
        val comments = listOf(
            TraktComment(id = 401, comment = "good"),
            TraktComment(id = 402, comment = "bad")
        )

        val result = translator.translateCommentsFlow(comments).toList()

        assertThat(result).hasSize(2)
        val byIndex = result.associate { it.first to it.second }
        // 成功项 emit 译文
        assertThat(byIndex[0]!!.comment).isEqualTo("好")
        assertThat(byIndex[0]!!.id).isEqualTo(401)
        // 失败项 emit 原文（translateOneComment 返回 comment 原文）
        assertThat(byIndex[1]!!.comment).isEqualTo("bad")
        assertThat(byIndex[1]!!.id).isEqualTo(402)
        // 成功项写入缓存，失败项不写入
        val cache = getTranslationCache()
        assertThat(cache.get(401)).isEqualTo("好")
        assertThat(cache.get(402)).isNull()
    }

    /**
     * 缓存命中与新评论混合：缓存命中立即 emit，新评论并发翻译后 emit。
     * 验证混合场景下所有评论都 emit，且译文内容正确。
     */
    @Test
    fun translateCommentsFlow_缓存与新评论混合_全部emit内容正确() = runBlocking {
        Locale.setDefault(Locale.CHINESE)
        val cache = getTranslationCache()
        cache.put(502, "缓存502")
        coEvery { translator["translateWithBaiduAI"](any<String>(), any<String>()) } coAnswers {
            when (firstArg<String>()) {
                "new1" -> "新译文1"
                "new3" -> "新译文3"
                else -> null
            }
        }
        val comments = listOf(
            TraktComment(id = 501, comment = "new1"), // 新
            TraktComment(id = 502, comment = "cached"), // 缓存命中
            TraktComment(id = 503, comment = "new3")  // 新
        )

        val result = translator.translateCommentsFlow(comments).toList()

        assertThat(result).hasSize(3)
        val byIndex = result.associate { it.first to it.second }
        assertThat(byIndex[0]!!.comment).isEqualTo("新译文1")
        assertThat(byIndex[0]!!.id).isEqualTo(501)
        assertThat(byIndex[1]!!.comment).isEqualTo("缓存502")
        assertThat(byIndex[1]!!.id).isEqualTo(502)
        assertThat(byIndex[2]!!.comment).isEqualTo("新译文3")
        assertThat(byIndex[2]!!.id).isEqualTo(503)
        // 只翻译了 2 条新评论（id=501,503），缓存命中的不翻译
        coVerify(exactly = 2) { translator["translateWithBaiduAI"](any<String>(), any<String>()) }
    }

    // ==================== translationCache LruCache ====================

    @Test
    fun translationCache_写入后可读取() {
        val cache = getTranslationCache()
        cache.put(100, "测试翻译")

        assertThat(cache.get(100)).isEqualTo("测试翻译")
    }

    @Test
    fun translationCache_容量限制200条_超出驱逐最旧() {
        val cache = getTranslationCache()
        // 写入 201 条
        repeat(201) { cache.put(it, "translation$it") }

        // 第 0 条被驱逐（最旧）
        assertThat(cache.get(0)).isNull()
        // 第 1 条还在
        assertThat(cache.get(1)).isEqualTo("translation1")
        // 第 200 条还在（最新）
        assertThat(cache.get(200)).isEqualTo("translation200")
        // 总大小为 200
        assertThat(cache.size()).isEqualTo(200)
    }

    @Test
    fun translationCache_重复put更新值不增加大小() {
        val cache = getTranslationCache()
        cache.put(1, "v1")
        cache.put(1, "v2")

        assertThat(cache.get(1)).isEqualTo("v2")
        assertThat(cache.size()).isEqualTo(1)
    }
}
