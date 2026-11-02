// 来源：auth-worker/src/ai/detail-handler.ts（514 行，只移植 prompt 构造段 ~347-369 与其依赖类型 24-79）。
// 详情页两条 AI 链路的 messages 构造：无剧透详情解读 + 候选排序。
// 校验、normalize、applyModelOrder、缓存与路由是服务端 IO/业务层，不移植。
package com.tracktosearch.data.ai.prompt

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.add
import kotlinx.serialization.json.JsonPrimitive

private val detailJson = Json { encodeDefaults = false; ignoreUnknownKeys = true }

/**
 * 详情页媒体条目（对应 TS 的 DetailMedia）。字段顺序与 TS 一致，序列化输出逐字段对齐。
 */
@Serializable
data class DetailMedia(
    val mediaKey: String,
    val mediaType: String, // 'movie' | 'show'
    val title: String,
    val year: Int? = null,
    val genres: List<String> = emptyList(),
    val overview: String? = null,
    val directors: List<String> = emptyList(),
    val cast: List<String> = emptyList(),
    val publicRating: Double? = null,
    val mediaIds: Map<String, String>,
)

/**
 * 粗粒度环境信息（对应 TS 的 DetailEnvironmentInput）。
 * 输入层已拒绝 city/经纬度/rawEvents/spoiler 等字段，App 端只允许构造成这里的形状。
 */
@Serializable
data class DetailEnvironmentInput(
    val localDate: String,
    val weekday: Int,
    val timeOfDay: String,
    val season: String,
    val weatherTag: String? = null,
)

/** 详情请求（对应 TS 的 DetailRequestInput；forceRefresh 只影响服务端缓存，不进 prompt）。 */
data class DetailRequestInput(
    val media: DetailMedia,
    val scene: String,
    val environment: DetailEnvironmentInput? = null,
    val forceRefresh: Boolean = false,
)

/** 排序器候选（对应 TS 的 Candidate）。 */
@Serializable
data class DetailCandidate(
    val mediaKey: String,
    val mediaType: String, // 'movie' | 'show'
    val title: String,
    val year: Int? = null,
    val genres: List<String> = emptyList(),
    val mediaIds: Map<String, String>,
)

/** 画像里的已看条目（对应 profile.media 的元素，字段取排序/推荐用到的那几个）。 */
@Serializable
data class ProfileMediaItem(
    val title: String,
    val mediaType: String = "movie",
    val genres: List<String> = emptyList(),
    /** 画像条目可选年份等扩展字段，进 prompt 时按需带上 */
    val year: Int? = null,
)

/** 画像切片：prompt 里只取 media 前 80 条（对应 profile.media.slice(0, 80)）。 */
data class DetailProfile(
    val media: List<ProfileMediaItem> = emptyList(),
)

/**
 * 无剧透影视助理 messages（对应 TS 的 detailMessages，detail-handler.ts:347-355）。
 */
fun detailMessages(
    input: DetailRequestInput,
    profile: DetailProfile,
): List<AiMessage> {
    val mediaJson = detailJson.encodeToString(DetailMedia.serializer(), input.media)
    val environmentJson = input.environment
        ?.let { detailJson.encodeToString(DetailEnvironmentInput.serializer(), it) } ?: "null"
    val profileJson = detailJson.encodeToString(
        buildJsonArray {
            profile.media.take(80).forEach { item ->
                add(buildJsonObject {
                    put("title", item.title)
                    put("mediaType", item.mediaType)
                    put("year", JsonPrimitive(item.year)) // TS JSON.stringify 对 null 输出 "year":null，字段必须保留
                    putJsonArray("genres") { item.genres.forEach { add(JsonPrimitive(it)) } }
                })
            }
        },
    )
    return listOf(
        AiMessage(
            role = "system",
            content = "你是无剧透影视助理。只返回 JSON，字段必须为 interestLevel、confidence、spoilerFreeSummary、publicRatingInterpretation、watchAdvice、reasons、watchTiming。interestLevel/confidence 只能是 HIGH、MEDIUM、LOW。不要生成个人数字评分，不要输出结局、反转、关键关系、生死、秘密、spoiler 或任何敏感属性。只使用输入的结构化事实。",
        ),
        AiMessage(
            role = "user",
            content = "<MEDIA>" + mediaJson + "</MEDIA><SCENE>" + input.scene + "</SCENE><ENVIRONMENT>" +
                environmentJson + "</ENVIRONMENT><PROFILE>" + profileJson + "</PROFILE>",
        ),
    )
}

/**
 * 影视候选排序器 messages（对应 TS 的 recommendationMessages，detail-handler.ts:358-366）。
 */
fun recommendationMessages(
    candidates: List<DetailCandidate>,
    profile: DetailProfile,
): List<AiMessage> {
    val candidatesJson = detailJson.encodeToString(
        buildJsonArray {
            candidates.forEach { candidate ->
                add(buildJsonObject {
                    put("mediaKey", candidate.mediaKey)
                    put("mediaType", candidate.mediaType)
                    put("title", candidate.title)
                    put("year", JsonPrimitive(candidate.year)) // TS JSON.stringify 对 null 输出 "year":null，字段必须保留
                    putJsonArray("genres") { candidate.genres.forEach { add(JsonPrimitive(it)) } }
                    put("mediaIds", buildJsonObject {
                        candidate.mediaIds.forEach { (key, value) -> put(key, value) }
                    })
                })
            }
        },
    )
    val profileJson = detailJson.encodeToString(
        buildJsonArray {
            profile.media.take(80).forEach { item ->
                add(buildJsonObject {
                    put("title", item.title)
                    put("mediaType", item.mediaType)
                    put("year", JsonPrimitive(item.year)) // TS JSON.stringify 对 null 输出 "year":null，字段必须保留
                    putJsonArray("genres") { item.genres.forEach { add(JsonPrimitive(it)) } }
                })
            }
        },
    )
    return listOf(
        AiMessage(
            role = "system",
            content = "你是影视候选排序器。只返回 JSON，字段为 orderedMediaKeys 和 reasons。只能使用输入中的 mediaKey，不能创建标题、ID或候选。理由必须无剧透且每条不超过120字。",
        ),
        AiMessage(
            role = "user",
            content = "<CANDIDATES>" + candidatesJson + "</CANDIDATES><PROFILE>" + profileJson + "</PROFILE>",
        ),
    )
}

/**
 * 按模型输出顺序重排候选（对应 TS 的 applyModelOrder，detail-handler.ts:368-380）：
 * 只认输入里已有的 mediaKey，去重、保序，未命中的候选按原顺序垫底。
 */
fun applyModelOrder(value: Any?, candidates: List<DetailCandidate>): List<DetailCandidate> {
    val object_ = value as? Map<*, *> ?: return candidates
    val keys = object_["orderedMediaKeys"] as? List<*> ?: return candidates
    val byKey = candidates.associateBy { it.mediaKey }
    val result = ArrayList<DetailCandidate>()
    for (key in keys) {
        if (key !is String) continue
        val candidate = byKey[key]
        if (candidate != null && !result.contains(candidate)) result.add(candidate)
    }
    return result + candidates.filter { !result.contains(it) }
}

/** 无类型匹配时的兜底推荐理由（对应 TS 的 recommendationReason，detail-handler.ts:382-385）。 */
fun recommendationReason(candidate: DetailCandidate, profile: DetailProfile): String {
    val matchingGenre = candidate.genres.firstOrNull { genre ->
        profile.media.any { it.genres.contains(genre) }
    }
    return if (matchingGenre != null) {
        "它与已授权片单中的${matchingGenre}偏好有重合。"
    } else {
        "它来自当前详情页已有的候选池，先按公开类型和媒体信息保留。"
    }
}

/** 观看时机等级（对应 TS 的 timingLevel，detail-handler.ts:387-393）。 */
fun timingLevel(environment: DetailEnvironmentInput?): String {
    if (environment == null) return "UNKNOWN"
    val weather = environment.weatherTag
    if (weather != null && Regex("COLD|SNOW|RAIN", RegexOption.IGNORE_CASE).containsMatchIn(weather)) return "NOW"
    if (Regex("EVENING|NIGHT", RegexOption.IGNORE_CASE).containsMatchIn(environment.timeOfDay)) return "SOON"
    return "LATER"
}

/** 观看时机依据文案（对应 TS 的 timingBasis，detail-handler.ts:395-401）。 */
fun timingBasis(environment: DetailEnvironmentInput?): List<String> {
    if (environment == null) return listOf("暂时没有足够的时间或环境信息")
    val basis = mutableListOf("${environment.season}季", "${environment.timeOfDay}时段")
    environment.weatherTag?.let { basis.add("天气标签为$it") }
    return basis.take(3)
}

/** 环境切片缓存键（对应 TS 的 environmentKey，detail-handler.ts:403-411）。 */
fun environmentKey(input: DetailRequestInput): String {
    if (input.scene != "WATCHLIST_CONTEXT" || input.environment == null) return "none"
    val environment = input.environment
    return listOf(
        environment.localDate,
        environment.weekday.toString(),
        environment.timeOfDay,
        environment.season,
        environment.weatherTag ?: "none",
    ).joinToString(":")
}
