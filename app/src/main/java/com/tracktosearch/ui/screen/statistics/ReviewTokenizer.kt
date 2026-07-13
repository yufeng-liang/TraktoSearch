package com.tracktosearch.ui.screen.statistics

import androidx.annotation.WorkerThread
import com.huaban.analysis.jieba.JiebaSegmenter
import com.huaban.analysis.jieba.SegMode

/**
 * 基于 jieba-analysis 的影评分词器，用于统计页词云。
 *
 * 注意：分词过程可能较耗时（首次加载词典 + 大量文本切分），
 * 调用方必须在 IO 协程 / 后台线程中调用，禁止在 Composable 或主线程直接调用。
 */
object ReviewTokenizer {

    private val segmenter = JiebaSegmenter()

    // 精简中英文停用词集合，用于降噪
    private val stopWords: Set<String> = buildSet {
        // 中文常见停用词
        addAll(
            listOf(
                "的", "了", "是", "我", "你", "他", "她", "它", "们", "这", "那", "就",
                "都", "而", "及", "与", "或", "在", "有", "和", "也", "很", "么", "吗",
                "吧", "呢", "啊", "呀", "哦", "把", "被", "让", "给", "为", "以", "对",
                "从", "到", "上", "下", "中", "个", "之", "其", "此", "等", "要", "会",
                "能", "可以", "没有", "不是", "还是", "但是", "因为", "所以", "如果", "虽然",
                "这个", "那个", "一个", "一些", "我们", "你们", "他们", "自己", "什么", "怎么",
                "这样", "那样", "已经", "现在", "时候", "觉得", "看"
            )
        )
        // 英文常见停用词
        addAll(
            listOf(
                "the", "a", "an", "and", "or", "but", "if", "then", "else", "of", "to", "in",
                "on", "at", "by", "for", "with", "about", "as", "is", "are", "was", "were", "be",
                "been", "being", "have", "has", "had", "do", "does", "did", "will", "would", "shall",
                "should", "can", "could", "may", "might", "must", "this", "that", "these", "those",
                "it", "its", "i", "you", "he", "she", "we", "they", "my", "your", "his", "her",
                "our", "their", "not", "no", "yes", "so", "than", "too", "very", "just", "also"
            )
        )
    }

    /**
     * 对一组影评文本进行分词并统计词频。
     *
     * @param reviews 影评文本列表（可为空）
     * @return 词 -> 出现次数的映射（已过滤停用词与噪声），调用方自行排序
     */
    @WorkerThread
    fun tokenize(reviews: List<String>): Map<String, Int> {
        val counter = mutableMapOf<String, Int>()
        for (review in reviews) {
            val tokens = segmenter.process(review, SegMode.SEARCH)
            for ((word, _) in tokens) {
                val token = word.lowercase().trim()
                if (token.isEmpty()) continue
                if (stopWords.contains(token)) continue
                if (token.length < 2) continue
                if (!containsMeaningfulChar(token)) continue
                counter[token] = counter.getOrDefault(token, 0) + 1
            }
        }
        return counter
    }

    // 仅含标点/空白/数字的 token 视为无意义噪声
    private fun containsMeaningfulChar(token: String): Boolean {
        return token.any { c ->
            c.isLetter() || (c.code in 0x4E00..0x9FFF)
        }
    }
}
