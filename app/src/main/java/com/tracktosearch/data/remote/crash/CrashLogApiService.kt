package com.tracktosearch.data.remote.crash

import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.http.Body
import retrofit2.http.POST

/**
 * 崩溃日志上报接口（走 auth-worker 代理）。
 *
 * baseUrl = ${GATEWAY_BASE_URL}/api/crash-logs/
 * Worker 接收后注入 CRASH_LOG_TOKEN 转发到 app-config Pages Functions，
 * 客户端不再持有上报密钥，避免反编译泄露。
 */
interface CrashLogApiService {
    /** 上报一条崩溃日志（JSON 字符串） */
    @POST(".")
    suspend fun upload(@Body body: RequestBody): ResponseBody
}
