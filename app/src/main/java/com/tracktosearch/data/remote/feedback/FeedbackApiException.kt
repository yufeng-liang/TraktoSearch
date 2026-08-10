package com.tracktosearch.data.remote.feedback

/**
 * 反馈服务返回的业务错误（携带服务端错误码与 HTTP 状态码）。
 * ViewModel 内部保留英文 code；UI 层通过 ErrorMessages 映射为本地化文案。
 */
class FeedbackApiException(
    val errorCode: String?,
    val httpCode: Int,
    override val message: String
) : Exception(message)
