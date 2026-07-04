package com.tracktosearch.data.remote.douban

import com.tracktosearch.data.remote.douban.dto.DoubanChartResponse
import com.tracktosearch.data.remote.douban.dto.DoubanNowPlayingResponse
import com.tracktosearch.data.remote.douban.dto.DoubanTop250Response
import retrofit2.http.GET
import retrofit2.http.Query

interface DoubanHotApiService {

    @GET("api/chart")
    suspend fun getChart(): DoubanChartResponse

    @GET("api/weekly")
    suspend fun getWeekly(): DoubanChartResponse

    @GET("api/nowplaying")
    suspend fun getNowPlaying(): DoubanNowPlayingResponse

    @GET("api/top250")
    suspend fun getTop250(
        @Query("page") page: Int = 1
    ): DoubanTop250Response
}
