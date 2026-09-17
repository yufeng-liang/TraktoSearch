package com.tracktosearch.data.remote.tmdb.dto

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

@Serializable
data class TmdbMovieDetail(
    val id: Int = 0,
    val title: String = "",
    val original_title: String = "",
    val poster_path: String? = null,
    val overview: String = "",
    val genres: List<TmdbGenre> = emptyList(),
    val release_date: String = "",
    val vote_average: Double = 0.0,
    val runtime: Int? = null,
    val production_countries: List<TmdbProductionCountry> = emptyList(),
    val belongs_to_collection: TmdbBelongsToCollection? = null,
    val status: String = "",
    val imdb_id: String? = null
)

@Serializable
data class TmdbTvDetail(
    val id: Int = 0,
    val name: String = "",
    val original_name: String = "",
    val poster_path: String? = null,
    val overview: String = "",
    val genres: List<TmdbGenre> = emptyList(),
    val first_air_date: String = "",
    val vote_average: Double = 0.0,
    val episode_run_time: List<Int>? = null,
    val origin_country: List<String> = emptyList(),
    val status: String = "",
    val imdb_id: String? = null
)

@Serializable
data class TmdbProductionCountry(
    val iso_3166_1: String = "",
    val name: String = ""
)

@Serializable
data class TmdbBelongsToCollection(
    val id: Int = 0,
    val name: String = "",
    val poster_path: String? = null,
    val backdrop_path: String? = null
)

@Serializable
data class TmdbCollectionResponse(
    val id: Int = 0,
    val name: String = "",
    val overview: String = "",
    val poster_path: String? = null,
    val backdrop_path: String? = null,
    val parts: List<TmdbCollectionPart> = emptyList()
)

@Serializable
data class TmdbCollectionPart(
    val id: Int = 0,
    val title: String = "",
    val original_title: String = "",
    val poster_path: String? = null,
    val release_date: String = "",
    val vote_average: Double = 0.0
)

@Serializable
data class TmdbGenre(
    val id: Int = 0,
    val name: String = ""
)

@Serializable
data class TmdbAlternativeTitlesResponse(
    val titles: List<TmdbAlternativeTitle> = emptyList()
)

@Serializable
data class TmdbAlternativeTitle(
    val iso_3166_1: String = "",
    val title: String = "",
    val type: String = ""
)

@Serializable
data class TmdbCreditsResponse(
    val cast: List<TmdbCast> = emptyList(),
    val crew: List<TmdbCrew> = emptyList()
)

@Immutable
@Serializable
data class TmdbCast(
    val id: Int = 0,
    val name: String = "",
    val original_name: String = "",
    val profile_path: String? = null,
    val character: String = "",
    val order: Int = 0
)

@Immutable
@Serializable
data class TmdbCrew(
    val id: Int = 0,
    val name: String = "",
    val original_name: String = "",
    val profile_path: String? = null,
    val job: String = "",
    val department: String = ""
)

@Serializable
data class TmdbReviewsResponse(
    val results: List<TmdbReview> = emptyList(),
    val total_pages: Int = 1,
    val total_results: Int = 0
)

@Serializable
data class TmdbReview(
    val id: String = "",
    val author: String = "",
    val author_details: TmdbReviewAuthor = TmdbReviewAuthor(),
    val content: String = "",
    val created_at: String = "",
    val url: String = ""
)

@Serializable
data class TmdbReviewAuthor(
    val name: String = "",
    val username: String = "",
    val avatar_path: String? = null,
    val rating: Double? = null
)

@Serializable
data class TmdbPerson(
    val id: Int = 0,
    val name: String = "",
    val also_known_as: List<String> = emptyList(),
    val birthday: String? = null,
    val deathday: String? = null,
    val biography: String = "",
    val place_of_birth: String? = null,
    val profile_path: String? = null,
    val known_for_department: String = "",
    val gender: Int? = null
)

@Serializable
data class TmdbPersonMovieCredits(
    val cast: List<TmdbPersonMovieCredit> = emptyList()
)

@Serializable
data class TmdbPersonMovieCredit(
    val id: Int = 0,
    val title: String = "",
    /** 原始片名，人物作品列表进详情页时作为首帧原名种子。 */
    val original_title: String = "",
    val poster_path: String? = null,
    val character: String = "",
    val release_date: String = "",
    val vote_average: Double = 0.0
)

@Serializable
data class TmdbPersonTvCredits(
    val cast: List<TmdbPersonTvCredit> = emptyList()
)

@Serializable
data class TmdbPersonTvCredit(
    val id: Int = 0,
    val name: String = "",
    /** 原始剧名，语义同 [TmdbPersonMovieCredit.original_title]。 */
    val original_name: String = "",
    val poster_path: String? = null,
    val character: String = "",
    val first_air_date: String = "",
    val vote_average: Double = 0.0
)

@Serializable
data class TmdbSearchResponse(
    val page: Int = 1,
    val results: List<TmdbSearchResult> = emptyList(),
    val total_pages: Int = 0,
    val total_results: Int = 0
)

@Serializable
data class TmdbMultiSearchResponse(
    val page: Int = 1,
    val results: List<TmdbMultiSearchResult> = emptyList(),
    val total_pages: Int = 0,
    val total_results: Int = 0
)

@Serializable
data class TmdbSearchResult(
    val id: Int = 0,
    val title: String = "",
    /**
     * 原始标题：`language=zh-CN` 时 [title] 是中文译名，这个字段仍是原片名。
     *
     * 详情页首帧要用它填「原名」那一行——列表接口本来就返回该字段，不去接的话
     * 从发现页/筛选页进详情时只能等详情富化回来，原名行插入会把评分卡和下方内容整体下推。
     */
    val original_title: String = "",
    val overview: String = "",
    val poster_path: String? = null,
    val release_date: String = "",
    // Discover API 额外返回的字段（其他接口不返回时取默认值，向后兼容）
    val vote_average: Double = 0.0,
    val genre_ids: List<Int> = emptyList(),
    val origin_country: List<String> = emptyList(),
    val original_language: String = "",  // 原始语言（discover/movie 不返回 origin_country 时用此推断）
    val name: String? = null,          // TV 节目标题
    /** 剧集原始名称，语义同 [original_title]。 */
    val original_name: String = "",
    val first_air_date: String? = null  // TV 首播日期
)

@Serializable
data class TmdbMultiSearchResult(
    val id: Int = 0,
    val title: String? = null,
    val name: String? = null,
    // 原始标题：language=zh-CN 时 title/name 是中文译名，跨语言精确匹配须比对原始标题
    val original_title: String? = null,
    val original_name: String? = null,
    val overview: String = "",
    val poster_path: String? = null,
    val release_date: String? = null,
    val first_air_date: String? = null,
    val media_type: String = "",
    val vote_average: Double = 0.0
)

@Serializable
data class TmdbPersonSearchResponse(
    val page: Int = 1,
    val results: List<TmdbPersonSearchResult> = emptyList(),
    val total_pages: Int = 0,
    val total_results: Int = 0
)

@Serializable
data class TmdbPersonSearchResult(
    val id: Int = 0,
    val name: String = "",
    val original_name: String = "",
    val profile_path: String? = null,
    val known_for_department: String = "",
    val gender: Int = 0,
    val popularity: Double = 0.0,
    val known_for: List<TmdbSearchResult> = emptyList()
)

@Serializable
data class TmdbTvSeasonDetail(
    val id: Int = 0,
    val name: String = "",
    val episodes: List<TmdbSeasonEpisode> = emptyList()
)

@Serializable
data class TmdbSeasonEpisode(
    val id: Int = 0,
    val name: String = "",
    val episode_number: Int = 0
)

@Serializable
data class TmdbVideosResponse(
    val results: List<TmdbVideo> = emptyList()
)

@Serializable
data class TmdbVideo(
    val id: String = "",
    val key: String = "",
    val name: String = "",
    val site: String = "",
    val type: String = "",
    val official: Boolean = false,
    val size: Int = 0
)

@Serializable
data class TmdbImagesResponse(
    val backdrops: List<TmdbImage> = emptyList()
)

@Serializable
data class TmdbPersonImagesResponse(
    val id: Int = 0,
    val profiles: List<TmdbImage> = emptyList()
)

@Serializable
data class TmdbPersonTaggedImagesResponse(
    val id: Int = 0,
    val page: Int = 0,
    val results: List<TmdbTaggedImage> = emptyList(),
    val total_pages: Int = 0,
    val total_results: Int = 0
)

@Serializable
data class TmdbTaggedImage(
    val file_path: String = "",
    val width: Int = 0,
    val height: Int = 0,
    val iso_639_1: String? = null,
    val media_type: String = ""
)

@Serializable
data class TmdbImage(
    val file_path: String = "",
    val width: Int = 0,
    val height: Int = 0,
    val iso_639_1: String? = null
)
