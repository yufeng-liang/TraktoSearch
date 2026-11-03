// 来源：auth-worker/src/ai/detail-handler.ts（514 行，只移植 prompt 构造段 ~347-369 与其依赖类型 24-79）。
// 详情页两条 AI 链路的 messages 构造：无剧透详情解读 + 候选排序。
// 校验、normalize、applyModelOrder、缓存与路由是服务端 IO/业务层，不移植。
//
// 序列化口径：TS 侧全部用 JSON.stringify，键集恒定（null / 空数组也照样输出）。
// 因此这里不用 kotlinx 的 encodeToString（encodeDefaults=false 会把 null 字段与空列表整键省略，
// 模型看到的 MEDIA/PROFILE 结构与线上不一致），改为 buildJsonObject 显式写全每个键。
package com.tracktosearch.data.ai.prompt

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.add

/**
 * 详情页媒体条目（对应 TS 的 DetailMedia，detail-handler.ts:34-43）。
 * 字段顺序与 TS 一致，序列化输出逐字段对齐（见 detailMediaJson）。
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
 * 粗粒度环境信息（对应 TS 的 DetailEnvironmentInput，detail-handler.ts:45-51）。
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

/** 排序器候选（对应 TS 的 Candidate，detail-handler.ts:65-71）。 */
@Serializable
data class DetailCandidate(
    val mediaKey: String,
    val mediaType: String, // 'movie' | 'show'
    val title: String,
    val year: Int? = null,
    val genres: List<String> = emptyList(),
    val mediaIds: Map<String, String>,
)

/**
 * 画像里的已看/想看条目（对应 TS 的 ProfileMediaView，profile-store.ts:65-78，共 11 字段）。
 *
 * 可空字段表示「本地当前拿不到该值」：序列化时写成 null，而不是编一个 false——
 * 编出来的 watchlist=false / watched=false 会让排序器拿到与事实相反的信号，
 * 比缺字段更糟。等编排层把 AiProfileMediaEntity 的
 * mediaKey/isWatched/isWatchlist/userRating/userComment/mediaIds 一并传进来即可自动补齐，
 * 本文件无需再改。
 */
@Serializable
data class ProfileMediaItem(
    /** 画像条目的稳定主键（tmdb:xxx / trakt:xxx）；本地实体有 mediaKey，暂未透传时为 null。 */
    val mediaKey: String? = null,
    val title: String,
    val mediaType: String = "movie",
    val year: Int? = null,
    val posterUrl: String? = null,
    val genres: List<String> = emptyList(),
    val mediaIds: Map<String, String> = emptyMap(),
    /** 是否在待看清单。 */
    val watchlist: Boolean? = null,
    /** 是否已看。 */
    val watched: Boolean? = null,
    /** 用户自己的评分（对应 TS rating；本地来自 AiProfileMediaEntity.userRating）。 */
    val rating: Double? = null,
    /** 是否写过短评（对应 TS commentPresent；本地来自 userComment 非空判断）。 */
    val commentPresent: Boolean? = null,
)

/** 画像切片：prompt 里只取 media 前 80 条（对应 profile.media.slice(0, 80)）。 */
data class DetailProfile(
    val media: List<ProfileMediaItem> = emptyList(),
)

/** mediaIds 对象序列化（TS 里是可选的四个 id 键，无值时是空对象）。 */
private fun idMapJson(ids: Map<String, String>) = buildJsonObject {
    ids.forEach { (key, value) -> put(key, value) }
}

/**
 * DetailMedia 的 JSON：字段集与顺序逐字对拍 TS 的 JSON.stringify(input.media)。
 * year/overview/publicRating 为 null、directors/cast/genres 为空数组时也照样写出键，
 * 与服务端下发到模型的结构完全一致。
 */
private fun detailMediaJson(media: DetailMedia) = buildJsonObject {
    put("mediaKey", media.mediaKey)
    put("mediaType", media.mediaType)
    put("title", media.title)
    put("year", media.year?.let { JsonPrimitive(it) } ?: JsonNull)
    put("genres", buildJsonArray { media.genres.forEach { add(JsonPrimitive(it)) } })
    put("overview", media.overview?.let { JsonPrimitive(it) } ?: JsonNull)
    put("directors", buildJsonArray { media.directors.forEach { add(JsonPrimitive(it)) } })
    put("cast", buildJsonArray { media.cast.forEach { add(JsonPrimitive(it)) } })
    put("publicRating", media.publicRating?.let { JsonPrimitive(it) } ?: JsonNull)
    put("mediaIds", idMapJson(media.mediaIds))
}

/** DetailEnvironmentInput 的 JSON（weatherTag 为 null 时也写 "weatherTag":null）。 */
private fun detailEnvironmentJson(environment: DetailEnvironmentInput) = buildJsonObject {
    put("localDate", environment.localDate)
    put("weekday", environment.weekday)
    put("timeOfDay", environment.timeOfDay)
    put("season", environment.season)
    put("weatherTag", environment.weatherTag?.let { JsonPrimitive(it) } ?: JsonNull)
}

/**
 * 画像条目的 JSON：11 个键恒定输出，顺序与 TS ProfileMediaView 一致。
 * 本地拿不到的值为 null（表示未知），不要在这里补默认 false。
 */
private fun profileMediaJson(item: ProfileMediaItem) = buildJsonObject {
    put("mediaKey", item.mediaKey?.let { JsonPrimitive(it) } ?: JsonNull)
    put("mediaType", item.mediaType)
    put("title", item.title)
    put("year", item.year?.let { JsonPrimitive(it) } ?: JsonNull)
    put("posterUrl", item.posterUrl?.let { JsonPrimitive(it) } ?: JsonNull)
    put("genres", buildJsonArray { item.genres.forEach { add(JsonPrimitive(it)) } })
    put("mediaIds", idMapJson(item.mediaIds))
    put("watchlist", item.watchlist?.let { JsonPrimitive(it) } ?: JsonNull)
    put("watched", item.watched?.let { JsonPrimitive(it) } ?: JsonNull)
    put("rating", item.rating?.let { JsonPrimitive(it) } ?: JsonNull)
    put("commentPresent", item.commentPresent?.let { JsonPrimitive(it) } ?: JsonNull)
}

/** 画像数组 JSON（前 80 条，对应 profile.media.slice(0, 80)）。 */
private fun profileMediaArrayJson(profile: DetailProfile) = buildJsonArray {
    profile.media.take(80).forEach { add(profileMediaJson(it)) }
}

/**
 * 无剧透影视助理 messages（对应 TS 的 detailMessages，detail-handler.ts:347-355）。
 */
fun detailMessages(
    input: DetailRequestInput,
    profile: DetailProfile,
): List<AiMessage> {
    val mediaJson = detailMediaJson(input.media).toString()
    // TS 对 null 的 environment 输出字面量 null
    val environmentJson = input.environment?.let { detailEnvironmentJson(it).toString() } ?: "null"
    val profileJson = profileMediaArrayJson(profile).toString()
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
    val candidatesJson = buildJsonArray {
        candidates.forEach { candidate ->
            add(
                buildJsonObject {
                    put("mediaKey", candidate.mediaKey)
                    put("mediaType", candidate.mediaType)
                    put("title", candidate.title)
                    // TS JSON.stringify 对 null 输出 "year":null，字段必须保留
                    put("year", candidate.year?.let { JsonPrimitive(it) } ?: JsonNull)
                    put("genres", buildJsonArray { candidate.genres.forEach { add(JsonPrimitive(it)) } })
                    put("mediaIds", idMapJson(candidate.mediaIds))
                },
            )
        }
    }.toString()
    val profileJson = profileMediaArrayJson(profile).toString()
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
