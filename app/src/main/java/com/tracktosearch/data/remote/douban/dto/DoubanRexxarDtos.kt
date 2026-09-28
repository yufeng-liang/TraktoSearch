package com.tracktosearch.data.remote.douban.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** Rexxar 详情响应。字段保持可空，豆瓣内部接口可能随版本增删字段。 */
@Serializable
data class DoubanRexxarDetailDto(
    val id: String = "",
    val title: String? = null,
    @SerialName("original_title") val originalTitle: String? = null,
    val type: String? = null,
    val subtype: String? = null,
    val year: String? = null,
    @SerialName("card_subtitle") val cardSubtitle: String? = null,
    val pubdate: List<String> = emptyList(),
    val genres: List<String> = emptyList(),
    /** 演职员字段在不同 Rexxar 返回中可能是字符串、字符串数组或对象数组。 */
    val directors: JsonElement? = null,
    val director: JsonElement? = null,
    val writers: JsonElement? = null,
    val writer: JsonElement? = null,
    val casts: JsonElement? = null,
    val cast: JsonElement? = null,
    val actors: JsonElement? = null,
    val actor: JsonElement? = null,
    val summary: String? = null,
    val intro: String? = null,
    val countries: JsonElement? = null,
    val region: JsonElement? = null,
    val languages: JsonElement? = null,
    val language: JsonElement? = null,
    val durations: JsonElement? = null,
    val duration: String? = null,
    val aka: JsonElement? = null,
    val alias: JsonElement? = null,
    val imdb: String? = null,
    @SerialName("imdb_id") val imdbId: String? = null,
    @SerialName("release_date") val releaseDate: JsonElement? = null,
    val rating: DoubanRexxarRatingDto? = null,
    val cover: DoubanRexxarCoverDto? = null,
    /** 当前样本中的 pic 是字符串 URL 对象，和 cover.image 的三档对象结构不同。 */
    val pic: DoubanRexxarPicDto? = null
)

@Serializable
data class DoubanRexxarRatingDto(
    val value: Double? = null,
    val average: Double? = null,
    val count: Int? = null,
    val max: Int? = null,
    @SerialName("star_count") val starCount: Double? = null
)

@Serializable
data class DoubanRexxarCoverDto(
    val image: DoubanRexxarImageDto? = null
)

@Serializable
data class DoubanRexxarPicDto(
    val large: String? = null,
    val normal: String? = null,
    val small: String? = null
)

@Serializable
data class DoubanRexxarImageDto(
    val large: DoubanRexxarImageVariantDto? = null,
    val normal: DoubanRexxarImageVariantDto? = null,
    val small: DoubanRexxarImageVariantDto? = null
)

@Serializable
data class DoubanRexxarImageVariantDto(
    val url: String? = null,
    val width: Int? = null,
    val height: Int? = null
)

/** Rexxar 剧照分页响应。 */
@Serializable
data class DoubanRexxarPhotoPageDto(
    val total: Int = 0,
    val start: Int = 0,
    val count: Int = 0,
    val photos: List<DoubanRexxarPhotoDto> = emptyList()
)

@Serializable
data class DoubanRexxarPhotoDto(
    val id: String = "",
    val image: DoubanRexxarImageDto? = null
)

/** Rexxar 短评分页响应。 */
@Serializable
data class DoubanRexxarInterestPageDto(
    val total: Int = 0,
    val start: Int = 0,
    val count: Int = 0,
    val interests: List<DoubanRexxarInterestDto> = emptyList()
)

@Serializable
data class DoubanRexxarInterestDto(
    val id: String = "",
    val comment: String? = null,
    @SerialName("create_time") val createTime: String? = null,
    /** 点赞数（豆瓣短评的 vote_count）。 */
    @SerialName("vote_count") val voteCount: Int? = null,
    val rating: DoubanRexxarRatingDto? = null,
    val user: DoubanRexxarUserDto? = null
)

@Serializable
data class DoubanRexxarUserDto(
    val name: String? = null
)
