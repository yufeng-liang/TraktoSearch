package com.tracktosearch.data.ai.provider

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * BYOK 错误映射中枢：三个协议适配器共用的 HTTP 状态 → [AiProviderException] 转换。
 *
 * 三家 Provider 的错误体形状不同但都带 error.message：
 * - OpenAI 兼容：{"error": {"message": "..."}}
 * - Gemini：     {"error": {"message": "...", "status": "..."}}
 * - Anthropic：  {"type": "error", "error": {"type": "...", "message": "..."}}
 * 都落在 "error" → "message" 这条路径上，所以提取逻辑可以共用。
 */
object AiProviderErrorMapper {

    /** 宽松解析：错误体只要能挖出 message 就用，解析失败不炸，留原始 body 兜底。 */
    private val lenientJson = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * 把 HTTP 状态码 + 响应体映射为统一异常。
     *
     * 状态 → 错误码：
     * - 401/403 → [AiProviderErrorCode.PROVIDER_AUTH]（Key 无效/无权限）
     * - 429 → [AiProviderErrorCode.RATE_LIMITED]
     * - 400/404/422 → [AiProviderErrorCode.INVALID_REQUEST]
     * - 5xx → [AiProviderErrorCode.SERVER]
     * - 其余 → [AiProviderErrorCode.UNKNOWN]
     *
     * message 优先取 body 里的 error.message，取不到回退原始 body 片段。
     */
    fun fromHttpStatus(status: Int, body: String?): AiProviderException {
        val code = when {
            status == 401 || status == 403 -> AiProviderErrorCode.PROVIDER_AUTH
            status == 429 -> AiProviderErrorCode.RATE_LIMITED
            status == 400 || status == 404 || status == 422 -> AiProviderErrorCode.INVALID_REQUEST
            status in 500..599 -> AiProviderErrorCode.SERVER
            else -> AiProviderErrorCode.UNKNOWN
        }
        return AiProviderException(
            code = code,
            message = extractErrorMessage(body) ?: "HTTP $status",
            httpStatus = status
        )
    }

    /** 网络层失败（IOException 等）的统一包装：不区分协议，一律 NETWORK。 */
    fun fromNetworkError(cause: Throwable): AiProviderException =
        AiProviderException(
            code = AiProviderErrorCode.NETWORK,
            message = cause.message ?: cause.javaClass.simpleName,
            cause = cause
        )

    /**
     * 判断错误体是否指向「该模型不接受 temperature 参数」。
     *
     * OpenAI 的 o 系列与 gpt-5 起的 reasoning 模型会直接拒绝 temperature，
     * 返回 400 + `Unsupported parameter: 'temperature' is not supported with this model.`；
     * 部分中转站措辞略有差异，所以用关键字 + 参数名双重匹配：
     * 必须同时出现 temperature 与 (unsupported / not supported / 不支持)。
     */
    fun mentionsTemperature(body: String?): Boolean {
        if (body.isNullOrBlank()) return false
        val lower = body.lowercase()
        if (!lower.contains("temperature")) return false
        return lower.contains("unsupported") ||
            lower.contains("not supported") ||
            lower.contains("不支持") ||
            lower.contains("unknown parameter") ||
            lower.contains("unrecognized")
    }

    /**
     * 判断错误体是否指向「该模型不接受当前输出长度参数」。
     *
     * OpenAI 的 o 系列与 gpt-5 起改用 `max_completion_tokens`，仍带 `max_tokens` 会返回 400 +
     * `Unsupported parameter: 'max_tokens' is not supported with this model.`；
     * 反向也有中转站只认 `max_tokens`。两种参数名都要能命中，交给调用方去掉该字段重发一次。
     */
    fun mentionsMaxTokensRejection(body: String?): Boolean {
        if (body.isNullOrBlank()) return false
        val lower = body.lowercase()
        if (!lower.contains("max_tokens") && !lower.contains("max_completion_tokens")) return false
        return lower.contains("unsupported") ||
            lower.contains("not supported") ||
            lower.contains("不支持") ||
            lower.contains("unknown parameter") ||
            lower.contains("unrecognized")
    }

    /**
     * 从错误体提取可读 message。
     * 依次尝试 OpenAI/Gemini/Anthropic 三种风格（其实共用 "error"."message" 一条路径），
     * 失败则截取原始 body 前 200 字符兜底。
     */
    fun extractErrorMessage(body: String?): String? {
        if (body.isNullOrBlank()) return null
        val root = runCatching { lenientJson.parseToJsonElement(body).jsonObject }.getOrNull()
        val providerMessage = root?.extractNestedMessage()
        return providerMessage ?: body.take(200)
    }

    /**
     * 递归找 "error" 对象里的 "message" 字段。
     * OpenAI / Gemini 都是 error.message 一层；Anthropic 也一样，只是 error 里多个 type。
     */
    private fun JsonObject.extractNestedMessage(): String? {
        val error = this["error"] as? JsonObject ?: return null
        val message = error["message"] as? JsonPrimitive
        return message?.takeIf { it.isString && it.content.isNotBlank() }?.content
    }

    /** 从响应 JSON 里取一个字符串字段，取不到给 null（不抛）。 */
    internal fun stringField(element: JsonElement?, key: String): String? {
        val primitive = (element as? JsonObject)?.get(key) as? JsonPrimitive ?: return null
        return primitive.takeIf { it.isString }?.content ?: primitive.content
    }

    /** 从响应 JSON 里取整数（choices[0].finish_reason 之外的通用小工具）。 */
    internal fun stringArray(element: JsonElement?, key: String): List<String> {
        val array = (element as? JsonObject)?.get(key) as? JsonArray ?: return emptyList()
        return array.mapNotNull { item ->
            (item as? JsonPrimitive)?.takeIf { it.isString }?.content
        }
    }
}
