package com.tracktosearch.data.remote.panxiaozi

import okhttp3.ResponseBody
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * 盘小子（pan.xiaozi.cc）搜索取数接口，2026-11 实测可用。
 *
 * 站点是 Next.js 开源项目，所有 api 路由都挂 admin-token JWT 鉴权，没有公开 JSON API；
 * 但搜索列表页是 Server Component 直查库，SSR HTML 的 RSC payload 里带完整结构化字段，
 * 用 PanxiaoziParser 正则提取即可。
 *
 * resource 接口免鉴权，每页固定 10 条，page 从 1 开始。
 * 返回是 HTML 文本，所以用 ResponseBody 接原始字节。
 */
interface PanxiaoziApiService {

    @GET("resource")
    suspend fun search(
        @Query("q") keyword: String,
        @Query("page") page: Int = 1
    ): ResponseBody
}
