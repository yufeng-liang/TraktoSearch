package com.tracktosearch.data.remote.cloud

import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Path

/**
 * Gitee 公共数据仓库的 Raw 直读接口。
 *
 * 该接口刻意不使用网关或鉴权拦截器，仓库内容必须保持匿名可读，避免公共数据读取消耗网关额度。
 */
interface GiteePublicRawApi {

    /** 读取 `meta-data-public` master 分支下的一个 JSON 文件。 */
    @GET("{path}")
    suspend fun getRawFile(
        @Path(value = "path", encoded = true) path: String
    ): Response<ResponseBody>
}
