package com.tracktosearch.ui.util

import android.content.Context
import com.tracktosearch.R
import com.tracktosearch.data.remote.douban.DoubanRateLimitedException
import com.tracktosearch.data.remote.feedback.FeedbackApiException
import retrofit2.HttpException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * 将底层异常 / 错误码统一映射为用户友好的本地化文案。
 * 映射顺序：反馈业务错误码 > 豆瓣风控限流 > 网络异常类型 > HTTP 状态码 > 已知消息关键词 > 兜底文案。
 * 避免把英文异常原文（timeout / resolve / HTTP xxx 等）直接展示给用户。
 */
fun Throwable.toUserMessage(context: Context, fallbackRes: Int = R.string.error_operation_failed): String {
    // 1. 反馈服务业务错误码
    if (this is FeedbackApiException) {
        return when {
            errorCode == "RATE_LIMITED" || httpCode == 429 ->
                context.getString(R.string.error_rate_limited)
            httpCode in 500..599 ->
                context.getString(R.string.error_server_error)
            else -> context.getString(fallbackRes)
        }
    }
    // 1.5 豆瓣风控限流（403，有效 Cookie 也可能被限流）
    if (this is DoubanRateLimitedException) {
        return context.getString(R.string.error_rate_limited)
    }
    // 2. 网络异常类型
    when (this) {
        is SocketTimeoutException -> return context.getString(R.string.error_network_timeout)
        is UnknownHostException, is ConnectException -> return context.getString(R.string.error_network_unavailable)
        is IOException -> return context.getString(R.string.error_network_unavailable)
    }
    // 3. HTTP 状态码
    if (this is HttpException) {
        return when (code()) {
            429 -> context.getString(R.string.error_rate_limited)
            in 500..599 -> context.getString(R.string.error_server_error)
            else -> context.getString(fallbackRes)
        }
    }
    // 4. 已知消息关键词（兜底识别常见底层异常文案）
    val msg = message.orEmpty()
    return when {
        msg.contains("timeout", ignoreCase = true) ||
            msg.contains("timed out", ignoreCase = true) ->
            context.getString(R.string.error_network_timeout)
        msg.contains("resolve", ignoreCase = true) ||
            msg.contains("address", ignoreCase = true) ||
            msg.contains("unreachable", ignoreCase = true) ||
            msg.contains("unable to connect", ignoreCase = true) ||
            msg.contains("unavailable", ignoreCase = true) ||
            msg.contains("failed to connect", ignoreCase = true) ||
            msg.contains("refused", ignoreCase = true) ->
            context.getString(R.string.error_network_unavailable)
        msg.contains("http 429", ignoreCase = true) ||
            msg.contains("too many", ignoreCase = true) ->
            context.getString(R.string.error_rate_limited)
        msg.contains("http 5", ignoreCase = true) ->
            context.getString(R.string.error_server_error)
        else -> context.getString(fallbackRes)
    }
}
