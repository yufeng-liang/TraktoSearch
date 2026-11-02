package com.tracktosearch.data.ai.provider

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * BYOK 改造的共享契约（第一部分）：Provider 配置模型。
 *
 * 这是所有 BYOK 子任务的共同地基，字段名一经定案不得擅改；
 * 协议适配器、配置存储、Repository 切换、配置页 UI 都以本文件为准。
 */

/** 支持的聊天补全协议。语音/配图 Provider 固定走 [OPENAI_COMPATIBLE]。 */
enum class AiProviderProtocol {
    /** OpenAI 兼容：POST {baseUrl}/chat/completions，SSE 流式。 */
    OPENAI_COMPATIBLE,

    /** Gemini 原生：POST {baseUrl}/v1beta/models/{model}:generateContent / :streamGenerateContent。 */
    GEMINI,

    /** Anthropic 原生：POST {baseUrl}/v1/messages，SSE 流式。 */
    ANTHROPIC
}

/** BYOK 中可单独覆盖模型的功能位。 */
enum class AiFeatureSlot {
    GREETING,
    TASTE,
    QUIZ,
    DAILY,
    DETAIL,
    RANK,
    TTS,
    DAILY_IMAGE
}

/** 一个用户配置的 Provider（全局默认或语音/配图专用）。 */
@Serializable
data class AiProviderConfig(
    /** 稳定标识：全局默认固定 [GLOBAL_ID]；语音/配图 Provider 固定 [MEDIA_ID]。 */
    val id: String = GLOBAL_ID,
    val name: String = "",
    val protocol: AiProviderProtocol = AiProviderProtocol.OPENAI_COMPATIBLE,
    /** 形如 https://api.openai.com/v1 或 https://generativelanguage.googleapis.com 的根地址。 */
    val baseUrl: String = "",
    val model: String = "",
    /** 是否语音/配图专用 Provider。 */
    val isMediaProvider: Boolean = false
) {
    // 配置完整与否只看 baseUrl/model（id 为 GLOBAL_ID 时允许 model 留空走默认）；Key 单独存 AiProviderKeyStore，永不回填本类
    val isConfigured: Boolean
        get() = baseUrl.isNotBlank() && model.isNotBlank()

    companion object {
        const val GLOBAL_ID = "global"
        const val MEDIA_ID = "media"
    }
}

/**
 * 用户提供但不能随配置落盘明文的字段单独存放：
 * Key 走 EncryptedSharedPreferences，其余配置走 DataStore。
 * 本类仅在内存中组装完整配置时使用，不参与序列化落盘。
 */
data class AiProviderSecret(val apiKey: String = "")

/** 全局默认 + 按功能覆盖模型 的完整设置（不含 Key 明文）。 */
@Serializable
data class AiProviderSettings(
    /** 语音/配图 Provider 不配置时跟随全局（isMediaProvider=false 表示未启用独立配置）。 */
    val global: AiProviderConfig = AiProviderConfig(id = AiProviderConfig.GLOBAL_ID),
    val media: AiProviderConfig = AiProviderConfig(id = AiProviderConfig.MEDIA_ID, isMediaProvider = true),
    /** 功能位 → 覆盖模型名；空串表示用全局默认模型。 */
    val modelOverrides: Map<String, String> = emptyMap(),
    /** 音色跟随角色开关（TTS 用）。 */
    val voiceFollowCharacter: Boolean = true,
    /** quiz 后台预生成开关，默认开。 */
    val quizPregenerateEnabled: Boolean = true
) {
    /** 解析某功能位应使用的模型：覆盖优先，回退全局模型。 */
    fun modelFor(slot: AiFeatureSlot): String =
        modelOverrides[slot.name]?.takeIf { it.isNotBlank() } ?: global.model

    /** 某功能位是否走独立语音/配图 Provider（仅 TTS/DAILY_IMAGE 有意义）。 */
    fun usesMediaProvider(slot: AiFeatureSlot): Boolean =
        (slot == AiFeatureSlot.TTS || slot == AiFeatureSlot.DAILY_IMAGE) &&
            media.isConfigured
}

/** BYOK 专用错误码；与 AiErrorCode 并列使用，HTTP 状态映射见 AiProviderErrorMapper。 */
enum class AiProviderErrorCode {
    /** 配置缺失/无效：未配 Key、baseUrl 非法、模型为空。 */
    PROVIDER_INVALID,

    /** 当前 Provider 不支持语音合成。 */
    PROVIDER_NO_TTS,

    /** 当前 Provider 不支持配图。 */
    PROVIDER_NO_IMAGE,

    /** Key 无效或无权限（401/403）。 */
    PROVIDER_AUTH,

    /** 429 限流。 */
    RATE_LIMITED,

    /** 请求格式被 Provider 拒绝（400/404/422 等）。 */
    INVALID_REQUEST,

    /** 响应无法解析/被截断。 */
    INVALID_RESPONSE,

    /** 网络层失败。 */
    NETWORK,

    /** 5xx 服务端错误。 */
    SERVER,

    UNKNOWN
}

/** BYOK 异常：携带错误码与用户可读的原始 message（Provider 的 error 文本直接透传）。 */
class AiProviderException(
    val code: AiProviderErrorCode,
    override val message: String,
    val httpStatus: Int? = null,
    cause: Throwable? = null
) : Exception(message, cause)
