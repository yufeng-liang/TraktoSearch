package com.tracktosearch.data.remote.douban

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

    @GET("{type}/{id}")
    suspend fun getDetail(
        @Path("type") type: String,
        @Path("id") id: String
    ): retrofit2.Response<DoubanRexxarDetailDto>

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
}
