package com.tracktosearch.ui.screen.statistics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewTokenizerTest {

    @Test
    fun tokenize_returnsNonEmptyMap_forChineseReviews() {
        val reviews = listOf(
            "这部电影的剧情非常精彩，演员的演技也很出色。",
            "精彩的剧情和出色的演技让我很感动。"
        )
        val result = ReviewTokenizer.tokenize(reviews)
        assertTrue("词频 Map 不应为空", result.isNotEmpty())
    }

    @Test
    fun tokenize_excludesStopWords() {
        val reviews = listOf("我的电影是很好看的，但是我觉得的的")
        val result = ReviewTokenizer.tokenize(reviews)
        assertFalse("不应包含停用词 我的", result.containsKey("我的"))
        assertFalse("不应包含停用词 是", result.containsKey("是"))
        assertFalse("不应包含停用词 的", result.containsKey("的"))
        assertFalse("不应包含停用词 但是", result.containsKey("但是"))
    }

    @Test
    fun tokenize_countsRealWordCorrectly() {
        val reviews = listOf(
            "演技非常出色，演技让我惊喜。",
            "出色的演技是这部电影的优点。"
        )
        val result = ReviewTokenizer.tokenize(reviews)
        assertEquals("实词 演技 应出现 3 次", 3, result["演技"])
        assertEquals("实词 出色 应出现 2 次", 2, result["出色"])
    }

    @Test
    fun tokenize_lowercasesEnglishAndFiltersStopWords() {
        val reviews = listOf("The movie is great and the story is good")
        val result = ReviewTokenizer.tokenize(reviews)
        assertFalse("应过滤英文停用词 the", result.containsKey("the"))
        assertFalse("应过滤英文停用词 is", result.containsKey("is"))
        assertFalse("应过滤英文停用词 and", result.containsKey("and"))
        assertTrue("应保留实词 movie", result.containsKey("movie"))
        assertTrue("应保留实词 story", result.containsKey("story"))
        assertEquals("movie 出现 1 次", 1, result["movie"])
    }

    @Test
    fun tokenize_emptyList_returnsEmptyMap() {
        val result = ReviewTokenizer.tokenize(emptyList())
        assertTrue("空列表应返回空 Map", result.isEmpty())
    }

    @Test
    fun tokenize_singleReview_returnsNonEmptyMap() {
        val result = ReviewTokenizer.tokenize(listOf("剧情精彩"))
        assertTrue("单条评论应返回非空 Map", result.isNotEmpty())
    }

    @Test
    fun tokenize_longText_doesNotCrash() {
        val longReview = "精彩".repeat(500)
        val result = ReviewTokenizer.tokenize(listOf(longReview))
        assertTrue("超长文本不应崩溃且应返回非空 Map", result.isNotEmpty())
    }

    @Test
    fun tokenize_mixedChineseEnglish_handlesBoth() {
        val result = ReviewTokenizer.tokenize(
            listOf("剧情精彩 story is great")
        )
        assertTrue("应包含中文词", result.containsKey("剧情") || result.containsKey("精彩"))
        assertTrue("应包含英文词 story", result.containsKey("story"))
        assertTrue("应包含英文词 great", result.containsKey("great"))
    }

    @Test
    fun tokenize_onlyStopWords_returnsEmptyOrSmallMap() {
        val result = ReviewTokenizer.tokenize(listOf("我们 你们 他们 但是 因为 所以"))
        assertTrue("全停用词应返回空或极小 Map", result.size <= 2)
    }

    @Test
    fun tokenize_numbersAndPunctuation_notReturnedAsValidWords() {
        val result = ReviewTokenizer.tokenize(listOf("123 456 789 !@# $%^"))
        assertTrue("纯数字与标点应返回空 Map", result.isEmpty())
    }
}
