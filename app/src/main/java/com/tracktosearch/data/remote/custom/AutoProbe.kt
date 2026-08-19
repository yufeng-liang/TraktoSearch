package com.tracktosearch.data.remote.custom

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 自动探测：给定接口响应 JSON，识别结果列表与字段路径。
 */
object AutoProbe {

    /** 自动探测结果：可直接构造 CustomSearchSource 的解析配置 */
    data class ProbeResult(
        val parseMode: String,
        val listPath: String,
        val namePath: String,
        val urlPath: String,
        val diskTypePath: String?,
        val datePath: String?
    )

    data class Variant(val apiPath: String, val keywordParam: String)

    val KEYWORD_PARAM_VARIANTS = listOf("kw", "q", "keyword", "query")
    val API_PATH_VARIANTS = listOf("api/search", "search", "api/query", "query")

    /** 路径 × 关键词参数的组合变体 */
    fun variants(): List<Variant> = API_PATH_VARIANTS.flatMap { p ->
        KEYWORD_PARAM_VARIANTS.map { Variant(p, it) }
    }

    /**
     * 分析 JSON 结构，识别列表与字段路径。
     * 返回 null 表示未找到可用列表结构。
     */
    fun analyze(root: JsonElement): ProbeResult? {
        val candidate = findListCandidate(root) ?: return null
        val (listPath, modeHint, firstItem) = candidate

        val namePath = findStringField(firstItem, NAME_PRIORITY)
        val urlPath = findStringField(firstItem, URL_PRIORITY)
        if (namePath == null || urlPath == null) return null

        val diskTypePath = findStringField(firstItem, DISK_PRIORITY)
        val datePath = findStringField(firstItem, DATE_PRIORITY)

        return ProbeResult(
            parseMode = modeHint ?: "custom",
            listPath = listPath,
            namePath = namePath,
            urlPath = urlPath,
            diskTypePath = diskTypePath,
            datePath = datePath
        )
    }

    private val NAME_PRIORITY = listOf("title", "name", "note", "filename", "file_name")
    private val URL_PRIORITY = listOf("url", "link", "download_url", "downloadUrl", "magnet")
    private val DISK_PRIORITY = listOf("type", "disk_type", "diskType", "source")
    private val DATE_PRIORITY = listOf("datetime", "date", "time", "upload_time", "update_time", "create_time")

    private data class ListCandidate(
        val path: String,
        val modeHint: String?,
        val firstItem: JsonElement
    )

    /**
     * 深度优先搜索根 JSON，找候选列表：
     * 1. 名字匹配的数组字段（results/data/items/list/rows/records）
     * 2. merged_by_type 这类"object 值全为数组"的字段（PanSou）
     * 3. 任意数组字段（兜底）
     */
    private fun findListCandidate(root: JsonElement): ListCandidate? {
        // 深度优先遍历，记录路径
        fun walk(element: JsonElement, path: String): ListCandidate? {
            if (element is JsonObject) {
                // 1. 命名单数组字段（results/data/items/list/rows/records）
                for ((key, value) in element) {
                    val nextPath = childPath(path, key)
                    if (value is JsonArray && value.isNotEmpty() && value.first() is JsonObject) {
                        val hint = if (key == "results" && containsLinksField(value.first() as JsonObject)) {
                            "zreso_template"
                        } else {
                            null
                        }
                        return ListCandidate(nextPath, hint, value.first())
                    }
                }
                // 2. 检查"object 值全为 array"的字段（merged_by_type 聚合模式，PanSou）
                for ((key, value) in element) {
                    if (value is JsonObject && isAggregation(value)) {
                        val arrays = value.values.filterIsInstance<JsonArray>().filter { it.isNotEmpty() }
                        if (arrays.isNotEmpty()) {
                            val item = arrays.first().first()
                            return ListCandidate("${childPath(path, key)}[*]", "pansou_template", item)
                        }
                    }
                }
                // 3. 递归深入其余字段
                for ((key, value) in element) {
                    walk(value, childPath(path, key))?.let { return it }
                }
            } else if (element is JsonArray) {
                for ((index, item) in element.withIndex()) {
                    if (item is JsonObject) {
                        return ListCandidate("$path[$index]", null, item)
                    }
                    walk(item, "$path[$index]")?.let { return it }
                }
            }
            return null
        }
        return walk(root, "")
    }

    /** 常见列表容器键名：单键且名为这些时视为普通列表而非聚合 */
    private val COMMON_LIST_KEYS = setOf("results", "items", "list", "rows", "records", "data", "entries", "docs", "children")

    /** 聚合模式判定：所有值都是数组，且键名不是常见列表容器名（PanSou merged_by_type 的盘符分组） */
    private fun isAggregation(obj: JsonObject): Boolean {
        if (obj.values.isEmpty() || !obj.values.all { it is JsonArray }) return false
        return obj.keys.none { it.lowercase() in COMMON_LIST_KEYS }
    }

    /** 计算子路径：空路径或已带 [*] 的路径直接挂键名 */
    private fun childPath(path: String, key: String): String =
        if (path.endsWith("[*]") || path.isBlank()) "$.$key" else "$path.$key"

    private fun containsLinksField(obj: JsonObject): Boolean =
        obj.keys.any { it == "links" } || obj.keys.any { it.contains("link") }

    /**
     * 在列表元素中按优先级找字符串字段，返回相对路径（如 "title"、"links[0].url"）。
     */
    private fun findStringField(element: JsonElement, priorities: List<String>): String? {
        if (element !is JsonObject) return null
        // 优先精确名字匹配
        for (key in priorities) {
            val v = element[key]
            if (v is JsonPrimitive && v.isString) {
                return key
            }
        }
        // 其次模糊匹配（字段名与优先级关键词互相包含，如 "disk" 命中 disk_type）
        for ((key, value) in element) {
            if (value is JsonPrimitive && value.isString) {
                val lower = key.lowercase()
                if (priorities.any { p -> lower.contains(p.lowercase()) || p.lowercase().contains(lower) }) {
                    return key
                }
            }
        }
        // 再查 links 数组首元素（zreso 场景）
        for ((key, value) in element) {
            if (value is JsonArray && value.isNotEmpty() && value.first() is JsonObject) {
                val first = value.first() as JsonObject
                for (p in priorities) {
                    val v = first[p]
                    if (v is JsonPrimitive && v.isString) {
                        return "$key[0].$p"
                    }
                }
            }
        }
        return null
    }
}
