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
        // RSC 的字符串字段里引号写成 \"，先把结构引号还原成裸引号再匹配
        // （实测裸引号正则直接匹配 \" 形态会因转义歧义失配，两步法 3/3 全过）
        val normalized = html.replace("\\\"", "\"")
        return ITEM_REGEX.findAll(normalized).map { m ->
            Record(
                title = unescape(m.groupValues[2]),
                diskType = m.groupValues[4],
                url = m.groupValues[5],
                updatedAt = m.groupValues[6]
            )
        }.distinctBy { it.url }.toList()
    }

    /** RSC 字符串反转义：双反斜杠先占位避免被 \n 的处理误吞，再处理 \n 与 \" 单转义 */
    private fun unescape(raw: String): String {
        val pairPlaceholder = "\u0000"
        return raw
            .replace("\\\\", pairPlaceholder)   // \\ -> 占位
            .replace("\\n", "\n")               // \n -> 换行
            .replace("\\\"", "\"")              // \" -> 引号
            .replace(pairPlaceholder, "\\")     // 占位还原为 \
    }
}