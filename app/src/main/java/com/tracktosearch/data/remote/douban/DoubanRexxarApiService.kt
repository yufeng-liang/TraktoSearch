package com.tracktosearch.data.remote.douban

import com.tracktosearch.data.remote.douban.dto.DoubanRexxarCollectionPage
import com.tracktosearch.data.remote.douban.dto.DoubanRexxarDetailDto
import com.tracktosearch.data.remote.douban.dto.DoubanRexxarInterestPageDto
import com.tracktosearch.data.remote.douban.dto.DoubanRexxarPhotoPageDto
import okhttp3.Interceptor
import okhttp3.Response
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

/** Rexxar 直连接口请求头。该接口不走网关，也不需要 Cookie。 */
class DoubanRexxarRequestInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request().newBuilder()
            .header("User-Agent", MOBILE_USER_AGENT)
            .header("Referer", DOUBAN_MOBILE_REFERER)
            .build()
        return chain.proceed(request)
    }

    private companion object {
        const val MOBILE_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Mobile Safari/537.36"
        const val DOUBAN_MOBILE_REFERER = "https://m.douban.com/"
    }
}

/** 豆瓣移动端 Rexxar 内部 API。 */
interface DoubanRexxarApiService {

    /** 详情响应同时携带海报（cover/pic）；不需要单独的海报请求。 */
    @GET("{type}/{id}")
    suspend fun getDetail(
        @Path("type") type: String,
        @Path("id") id: String
    ): retrofit2.Response<DoubanRexxarDetailDto>

    /** 独立接口只用于剧照分页，不能用详情里的海报替代。 */
    @GET("{type}/{id}/photos")
    suspend fun getPhotos(
        @Path("type") type: String,
        @Path("id") id: String,
        @Query("start") start: Int = 0,
        @Query("count") count: Int = 20
    ): retrofit2.Response<DoubanRexxarPhotoPageDto>

    @GET("{type}/{id}/interests")
    suspend fun getInterests(
        @Path("type") type: String,
        @Path("id") id: String,
        @Query("start") start: Int = 0,
        @Query("count") count: Int = 20
    ): retrofit2.Response<DoubanRexxarInterestPageDto>

    /** 豆瓣移动端榜单集合（新片榜 movie_hot / 口碑榜 movie_weekly_best / Top250 / 热门 movie_hot_gaia）。 */
    @GET("subject_collection/{collectionId}/items")
    suspend fun getCollectionItems(
        @Path("collectionId") collectionId: String,
        @Query("start") start: Int = 0,
        @Query("count") count: Int = 25
    ): retrofit2.Response<DoubanRexxarCollectionPage>
}
