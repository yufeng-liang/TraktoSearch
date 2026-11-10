package com.tracktosearch.data.remote.panxiaozi

/**
 * 盘小子 SSR RSC payload 解析器（纯函数，便于单测）。
 *
 * 列表页返回的 HTML 内嵌 React Server Components 数据，每条资源的字段形如：
 * pinyin:"311810",title:"繁花…",desc:"…",cover:"https://…",
 * diskType:"夸克",url:"https://pan.quark.cn/s/xxx",hotNum:0,isShowHome:1,
 * updatedAt:$R[31]=new Date("2026-05-02T20:25:08.000Z")
 * 字段序稳定，用记录级正则一次抓全；desc / cover / updatedAt 可能为 null。
 */
object PanxiaoziParser {

    /** 解析出的一条盘小子资源 */
    data class Record(
        val title: String,
        val diskType: String,
        val url: String,
        val updatedAt: String
    )

    // 注意：raw string 里 $ 仍会触发模板插值，RSC 的 $R[n] 引用要用 ${'$'} 写字面量
    private val ITEM_REGEX = Regex(
        """pinyin:"([^"]*)",title:"((?:[^"\\]|\\.)*)",desc:(?:"((?:[^"\\]|\\.)*)"|null),""" +
            """cover:(?:"[^"]*"|null),diskType:"([^"]*)",url:"(https?://[^"]+)",""" +
            """hotNum:\d+,isShowHome:\d+,updatedAt:(?:${'$'}R\[\d+\]=)?(?:new Date\("([^"]*)"\)|null)"""
    )

    /** 从整页 HTML 里提取全部资源记录（按出现顺序，已按 url 去重） */
    fun parse(html: String): List<Record> {
        if (html.isBlank()) return emptyList()
        // RSC 的字符串字段里引号写成 \"。不能全局替换：正文里的转义引号会被误当成
        // 字段结束引号导致标题截断；这里只把紧邻字段分隔符的结构引号还原成裸引号。
        val normalized = normalizeRscQuotes(html)
        return ITEM_REGEX.findAll(normalized).map { m ->
            Record(
                title = unescape(m.groupValues[2]),
                diskType = m.groupValues[4],
                url = m.groupValues[5],
                updatedAt = m.groupValues[6]
            )
        }.distinctBy { it.url }.toList()
    }

    /**
     * 只还原 RSC 结构引号（形如 `title:\"`、`\"` 后跟逗号/括号的字段边界），
     * 保留字符串值内部的 `\"` 交给 [unescape] 处理。
     */
    private fun normalizeRscQuotes(html: String): String {
        val sb = StringBuilder(html.length)
        var i = 0
        while (i < html.length) {
            val c = html[i]
            if (c == '\\' && i + 1 < html.length && html[i + 1] == '"') {
                val prev = if (i > 0) html[i - 1] else '\u0000'
                val next = if (i + 2 < html.length) html[i + 2] else '\u0000'
                val structural = prev == ':' || prev == ',' || prev == '[' || prev == '(' ||
                    next == ',' || next == ']' || next == '}' || next == ')'
                if (structural) {
                    sb.append('"')
                    i += 2
                    continue
                }
            }
            sb.append(c)
            i++
        }
        return sb.toString()
    }

    /** RSC 字符串反转义：支持 \\、\n、\" 三种转义。 */
    private fun unescape(raw: String): String {
        val sb = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c == '\\' && i + 1 < raw.length) {
                when (raw[i + 1]) {
                    '\\' -> {
                        sb.append('\\')
                        i += 2
                    }
                    'n' -> {
                        sb.append('\n')
                        i += 2
                    }
                    '"' -> {
                        sb.append('"')
                        i += 2
                    }
                    else -> {
                        sb.append(c)
                        i++
                    }
                }
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }
}
