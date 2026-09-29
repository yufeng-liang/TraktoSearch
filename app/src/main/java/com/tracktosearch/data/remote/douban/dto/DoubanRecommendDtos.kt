package com.tracktosearch.data.remote.douban.dto

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 豆瓣「为你推荐」rexxar 端点响应。
 *
 * 带登录 cookie 时返回个性化单剧推荐（type="movie"/"tv"），混少量片单（type="playlist"）和广告（type="ad"）。
 * 免登录时返回通用热门片单。
 */
@Serializable
data class DoubanRecommendResponse(
    val items: List<DoubanRecommendItem> = emptyList(),
    val total: Int = 0
)

@Serializable
data class DoubanRecommendItem(
    val type: String = "",           // "movie"/"tv"=单剧 | "playlist"=片单 | "ad"=广告
    val id: String = "",             // doubanId
    val title: String = "",
    val cover: String? = null,       // 片单海报 URL（单剧无此字段）
    val pic: DoubanRecommendPic? = null,  // 单剧海报（嵌套对象）
    val rating: DoubanRecommendRating? = null,
    val url: String? = null,
    val year: String? = null,        // 单剧才有，如 "2025"
    /**
     * 豆瓣 API 返回的 alg_json 是一个**转义的 JSON 字符串**（不是嵌套对象）。
     * 单剧推荐结构：{"id":"...","reason_data":[["tag_descr",null,{"tags":["日本","悬疑"]}],["director","12345",{}]],"alg_strategy":"user_movie",...}
     * 片单推荐结构：{"card_type":"doulist","model":"hot",...}（无 reason_data）
     */
    val alg_json: String? = null
) {
    /**
     * 推荐理由标签（如 ["日本", "悬疑", "犯罪"]），仅个性化单剧推荐才有。
     * alg_json.reason_data 结构：[["tag_descr",null,{"tags":["日本","悬疑","犯罪"]}],[\"director\",...],...]
     * 提取所有 tag_descr 类型项的 tags 数组元素。
     * 解析失败返回 null。
     */
    val reasonTags: List<String>? get() = alg_json?.let { str ->
        runCatching {
            val root = Json.parseToJsonElement(str).jsonObject
            val reasonData = root["reason_data"] ?: return@runCatching null
            val tags = mutableListOf<String>()
            reasonData.jsonArray.forEach { item ->
                // 每项是数组：[类型, id/参数, {tags: [...]}]
                val arr: JsonArray = item.jsonArray
                if (arr.size >= 3) {
                    val meta = arr.getOrNull(2)
                    if (meta is JsonObject) {
                        val tagsArr = meta["tags"]?.jsonArray
                        if (tagsArr != null) {
                            tagsArr.forEach { tag ->
                                tags.add(tag.jsonPrimitive.content)
                            }
                        }
                    }
                }
            }
            tags.takeIf { it.isNotEmpty() }
        }.getOrNull()
    }
}

/** 单剧海报（嵌套对象） */
@Serializable
data class DoubanRecommendPic(
    val large: String? = null,    // 大图 URL
    val normal: String? = null    // 中图 URL（用于卡片）
)

@Serializable
data class DoubanRecommendRating(
    val value: Double? = null,       // 9.7（豆瓣返回数字）
    val count: Int? = null,
    val max: Int? = null,
    val star_count: Double? = null
)

@Serializable
data class AlgJson(
    val alg_strategy: String? = null,  // "user_movie" / "user_tv" / "hot"
    val reason_data: List<String>? = null
)
