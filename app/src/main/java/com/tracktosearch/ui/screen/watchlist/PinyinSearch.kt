package com.tracktosearch.ui.screen.watchlist

import net.sourceforge.pinyin4j.PinyinHelper
import net.sourceforge.pinyin4j.format.HanyuPinyinOutputFormat
import net.sourceforge.pinyin4j.format.HanyuPinyinToneType
import java.util.concurrent.ConcurrentHashMap

/**
 * 看单本地搜索的拼音匹配工具。
 *
 * 输入拼音（如 "huas"）时，用影视中文名的拼音索引匹配，命中看单里
 * 中文名不含查询字串、但拼音含的条目（如 "花束般的恋爱"）。
 * 匹配规则：
 * - 全拼：中文名 → "huashu bande lianai"，输入 "huashu" 或 "huashubandelianai" 命中
 * - 首字母缩写：同上 → "hsbdla"，输入 "hsbd" 命中
 * 仅当查询为纯 ASCII（拼音输入）时启用，避免干扰中文直接匹配。
 */
internal object PinyinSearch {

    /** 拼音索引缓存，键为中文名，避免每次过滤重复转换 */
    private val cache = ConcurrentHashMap<String, String>()

    /** 无音调拼音输出格式（pinyin4j 转换用，可复用） */
    private val outputFormat = HanyuPinyinOutputFormat().apply {
        toneType = HanyuPinyinToneType.WITHOUT_TONE
    }

    /**
     * 判断中文名是否匹配拼音查询。查询应已去除首尾空格、保留原大小写。
     */
    fun matches(title: String, query: String): Boolean {
        if (title.isBlank() || query.isBlank()) return false
        // 查询含非 ASCII 字母（如汉字）时不做拼音匹配
        if (query.any { it.isLetter() && !it.isAsciiLetter() }) return false
        val index = buildIndex(title) ?: return false
        val q = query.lowercase()
        // 全拼匹配：含空格与去空格两种形式
        if (index.contains(q) || index.replace(" ", "").contains(q)) return true
        // 首字母缩写：连续缩写段前缀命中
        return abbreviationOf(index).startsWith(q)
    }

    /**
     * 构建拼音搜索索引："花束般的恋爱" → "huashu bande lianai"（无音调、小写、含空格）。
     * 标题为空或无可转换内容时返回 null，供上层为列表预计算搜索索引复用。
     */
    fun buildIndex(title: String): String? {
        if (title.isBlank()) return null
        return cache.getOrPut(title) { toPinyinIndex(title) }.ifEmpty { null }
    }

    /** 由拼音索引计算首字母缩写：例如 "huashu bande lianai" → "hsbdla" */
    fun abbreviationOf(index: String): String =
        index.split(" ").mapNotNull { it.firstOrNull() }.joinToString("")

    /** 中文名转拼音索引：非汉字原样保留，整体小写，多音字取第一个读音 */
    private fun toPinyinIndex(text: String): String {
        return buildString {
            for (ch in text) {
                // 非汉字返回 null，原样保留；多音字取第一个读音
                val pinyin = PinyinHelper.toHanyuPinyinStringArray(ch, outputFormat)?.firstOrNull()
                if (pinyin == null) append(ch) else append(pinyin).append(' ')
            }
        }.trim().lowercase()
    }

    private fun Char.isAsciiLetter(): Boolean = this in 'a'..'z' || this in 'A'..'Z'
}
