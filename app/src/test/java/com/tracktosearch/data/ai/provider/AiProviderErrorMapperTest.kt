package com.tracktosearch.data.ai.provider

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * 错误映射中枢单测：状态码 → 错误码、三家 Provider 错误体 message 提取、
 * 非 JSON body 兜底、网络异常包装。
 */
class AiProviderErrorMapperTest {

    // ---------- 状态码映射 ----------

    @Test
    fun `401 maps to PROVIDER_AUTH`() {
        val error = AiProviderErrorMapper.fromHttpStatus(401, null)
        assertThat(error.code).isEqualTo(AiProviderErrorCode.PROVIDER_AUTH)
        assertThat(error.httpStatus).isEqualTo(401)
    }

    @Test
    fun `403 maps to PROVIDER_AUTH`() {
        val error = AiProviderErrorMapper.fromHttpStatus(403, null)
        assertThat(error.code).isEqualTo(AiProviderErrorCode.PROVIDER_AUTH)
    }

    @Test
    fun `429 maps to RATE_LIMITED`() {
        val error = AiProviderErrorMapper.fromHttpStatus(429, null)
        assertThat(error.code).isEqualTo(AiProviderErrorCode.RATE_LIMITED)
    }

    @Test
    fun `400 404 422 map to INVALID_REQUEST`() {
        for (status in listOf(400, 404, 422)) {
            val error = AiProviderErrorMapper.fromHttpStatus(status, null)
            assertThat(error.code).isEqualTo(AiProviderErrorCode.INVALID_REQUEST)
            assertThat(error.httpStatus).isEqualTo(status)
        }
    }

    @Test
    fun `5xx maps to SERVER`() {
        for (status in listOf(500, 502, 503, 599)) {
            val error = AiProviderErrorMapper.fromHttpStatus(status, null)
            assertThat(error.code).isEqualTo(AiProviderErrorCode.SERVER)
        }
    }

    @Test
    fun `unmapped status maps to UNKNOWN with HTTP fallback message`() {
        val error = AiProviderErrorMapper.fromHttpStatus(418, null)
        assertThat(error.code).isEqualTo(AiProviderErrorCode.UNKNOWN)
        assertThat(error.message).isEqualTo("HTTP 418")
    }

    // ---------- message 提取：三家 Provider 错误体风格 ----------

    @Test
    fun `extracts OpenAI style error message`() {
        val body = """{"error":{"message":"Incorrect API key provided","type":"invalid_request_error"}}"""
        val error = AiProviderErrorMapper.fromHttpStatus(401, body)
        assertThat(error.message).isEqualTo("Incorrect API key provided")
    }

    @Test
    fun `extracts Gemini style error message`() {
        val body = """{"error":{"code":400,"message":"API key not valid. Please pass a valid API key.","status":"INVALID_ARGUMENT"}}"""
        val error = AiProviderErrorMapper.fromHttpStatus(400, body)
        assertThat(error.message).isEqualTo("API key not valid. Please pass a valid API key.")
    }

    @Test
    fun `extracts Anthropic style error message`() {
        val body = """{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}"""
        val error = AiProviderErrorMapper.fromHttpStatus(401, body)
        assertThat(error.message).isEqualTo("invalid x-api-key")
    }

    @Test
    fun `non JSON body falls back to first 200 chars`() {
        val longBody = "<html>" + "x".repeat(300) + "</html>"
        val error = AiProviderErrorMapper.fromHttpStatus(502, longBody)
        assertThat(error.message).isEqualTo(longBody.take(200))
        assertThat(error.message!!.length).isAtMost(200)
    }

    @Test
    fun `null or blank body falls back to HTTP status message`() {
        assertThat(AiProviderErrorMapper.fromHttpStatus(500, null).message).isEqualTo("HTTP 500")
        assertThat(AiProviderErrorMapper.fromHttpStatus(500, "  ").message).isEqualTo("HTTP 500")
    }

    @Test
    fun `error message empty string falls back to body snippet`() {
        // error.message 为空串时不算有效 message，回退 body 片段
        val body = """{"error":{"message":""}}"""
        val error = AiProviderErrorMapper.fromHttpStatus(400, body)
        assertThat(error.message).isEqualTo(body)
    }

    // ---------- 网络异常包装 ----------

    @Test
    fun `fromNetworkError wraps IOException as NETWORK with cause`() {
        val cause = SocketTimeoutException("timeout after 90s")
        val error = AiProviderErrorMapper.fromNetworkError(cause)
        assertThat(error.code).isEqualTo(AiProviderErrorCode.NETWORK)
        assertThat(error.message).isEqualTo("timeout after 90s")
        assertThat(error.cause).isSameInstanceAs(cause)
    }

    @Test
    fun `fromNetworkError with null message uses class simple name`() {
        val error = AiProviderErrorMapper.fromNetworkError(IOException())
        assertThat(error.code).isEqualTo(AiProviderErrorCode.NETWORK)
        assertThat(error.message).isEqualTo("IOException")
    }
}
