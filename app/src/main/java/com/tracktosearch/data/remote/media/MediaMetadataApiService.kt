package com.tracktosearch.data.remote.media

import com.tracktosearch.data.remote.media.dto.MediaDetailEnvelope
import com.tracktosearch.data.remote.media.dto.MediaSummariesResponse
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * 归一化影视元数据接口。
 *
 * 摘要接口用于看单/历史列表批量补全，详情接口用于播放页按 section 增量加载；
 * 两者都只返回服务端归一化后的公开 TMDB 数据。
 */
interface MediaMetadataApiService {

    @GET("summaries")
    suspend fun getSummaries(
        @Query("locale") locale: String,
        @Query("ids") ids: String
    ): Response<MediaSummariesResponse>

    @GET("detail")
    suspend fun getDetail(
        @Query("type") type: String,
        @Query("id") id: Int,
        @Query("locale") locale: String,
        @Query("sections") sections: String
    ): Response<MediaDetailEnvelope>
}
