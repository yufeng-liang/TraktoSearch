package com.tracktosearch.data.remote.media.dto

import com.tracktosearch.data.remote.tmdb.dto.TmdbVideo
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * `/api/media/summaries` 的完整响应。
 *
 * 字段名使用服务端当前 wire shape（camelCase）；入口字段显式标注，避免后续服务端
 * 增加包装字段时 Retrofit 反序列化出现歧义。
 */
@Serializable
data class MediaSummariesResponse(
    @SerialName("code") val code: String = "SUCCESS",
    @SerialName("message") val message: String = "",
    @SerialName("requestId") val requestId: String = "",
    @SerialName("data") val data: MediaSummariesData = MediaSummariesData()
)

@Serializable
data class MediaSummariesData(
    @SerialName("items") val items: List<MediaSummaryDto> = emptyList(),
    @SerialName("missing") val missing: List<String> = emptyList(),
    @SerialName("partial") val partial: Boolean = false
)

@Serializable
data class MediaSummaryDto(
    @SerialName("mediaType") val mediaType: String = "",
    @SerialName("tmdbId") val tmdbId: Int = 0,
    @SerialName("locale") val locale: String = "",
    @SerialName("title") val title: String = "",
    @SerialName("originalTitle") val originalTitle: String = "",
    @SerialName("overview") val overview: String = "",
    @SerialName("posterPath") val posterPath: String? = null,
    @SerialName("year") val year: Int? = null,
    @SerialName("genres") val genres: List<String> = emptyList(),
    @SerialName("voteAverage") val voteAverage: Double? = null,
    @SerialName("runtime") val runtime: Int? = null,
    @SerialName("countries") val countries: List<String> = emptyList(),
    @SerialName("status") val status: String = "",
    @SerialName("imdbId") val imdbId: String? = null,
    @SerialName("collectionId") val collectionId: Int? = null,
    @SerialName("titleSource") val titleSource: String = "NONE"
)

@Serializable
data class MediaDetailEnvelope(
    @SerialName("code") val code: String = "SUCCESS",
    @SerialName("message") val message: String = "",
    @SerialName("requestId") val requestId: String = "",
    @SerialName("data") val data: MediaDetailBundleDto? = null
)

@Serializable
data class MediaDetailBundleDto(
    @SerialName("summary") val summary: MediaSummaryDto = MediaSummaryDto(),
    @SerialName("credits") val credits: MediaCreditsDto? = null,
    @SerialName("videos") val videos: List<TmdbVideo>? = null,
    @SerialName("images") val images: List<MediaImageDto>? = null,
    @SerialName("similar") val similar: List<MediaSimilarDto>? = null,
    @SerialName("collection") val collection: MediaCollectionDto? = null
)

@Serializable
data class MediaCreditsDto(
    @SerialName("cast") val cast: List<MediaCastDto> = emptyList(),
    @SerialName("crew") val crew: List<MediaCrewDto> = emptyList()
)

@Serializable
data class MediaCastDto(
    @SerialName("id") val id: Int = 0,
    @SerialName("name") val name: String = "",
    @SerialName("character") val character: String = "",
    @SerialName("profilePath") val profilePath: String? = null,
    @SerialName("order") val order: Int = 0
)

@Serializable
data class MediaCrewDto(
    @SerialName("id") val id: Int = 0,
    @SerialName("name") val name: String = "",
    @SerialName("job") val job: String = "",
    @SerialName("department") val department: String = "",
    @SerialName("profilePath") val profilePath: String? = null
)

@Serializable
data class MediaImageDto(
    @SerialName("filePath") val filePath: String = "",
    @SerialName("width") val width: Int? = null,
    @SerialName("height") val height: Int? = null,
    @SerialName("iso6391") val iso6391: String? = null,
    @SerialName("source") val source: String = "tmdb"
)

@Serializable
data class MediaSimilarDto(
    @SerialName("tmdbId") val tmdbId: Int = 0,
    @SerialName("title") val title: String = "",
    @SerialName("originalTitle") val originalTitle: String = "",
    @SerialName("posterPath") val posterPath: String? = null,
    @SerialName("releaseDate") val releaseDate: String? = null,
    @SerialName("voteAverage") val voteAverage: Double? = null
)

@Serializable
data class MediaCollectionDto(
    @SerialName("id") val id: Int = 0,
    @SerialName("name") val name: String = "",
    @SerialName("overview") val overview: String = "",
    @SerialName("posterPath") val posterPath: String? = null,
    @SerialName("backdropPath") val backdropPath: String? = null,
    @SerialName("parts") val parts: List<MediaSimilarDto> = emptyList()
)
