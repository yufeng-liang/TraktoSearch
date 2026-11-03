package com.tracktosearch.data.ai.provider

import kotlinx.serialization.Serializable

/**
 * BYOK 改造的共享契约（第一部分）：Provider 配置模型。
 *
 * 配置结构（v2）：
 * - 文本服务商（global）：聊天类功能的默认来源，可拉取模型目录并勾选多个模型，
 *   再按功能位分配不同模型；
 * - 语音服务商（tts）：朗读专用，独立地址/Key/模型，未配置时朗读回退系统 TTS；
 * - 图片生成服务商（image）：每日一图插图专用，独立地址/Key/模型，未配置则不生成插图。
 *
 * 三家都只存 baseUrl / model / 模型目录缓存，Key 明文单独走 AiProviderKeyStore。
 */

/** 支持的协议。语音合成与图片生成只支持 OpenAI 兼容接口。 */
enum class AiProviderProtocol {
    /** OpenAI 兼容：POST {baseUrl}/chat/completions，SSE 流式；/models 拉目录。 */
    OPENAI_COMPATIBLE,

    /**
     * OpenAI 原生 Responses API：POST {baseUrl}/responses。
     * 官方推荐的新一代接口，reasoning 模型（o 系列 / gpt-5 起）在上面能力更完整；
     * 三方中转站目前多只实现 chat/completions，所以两者并存、由用户自行选择。
     */
    OPENAI_RESPONSES,

    /** Gemini 原生：POST {baseUrl}/v1beta/models/{model}:generateContent / :streamGenerateContent。 */
    GEMINI,

    /** Anthropic 原生：POST {baseUrl}/v1/messages，SSE 流式。 */
    ANTHROPIC;

    companion object {
        /** 语音/图片服务商可选的协议集合（BYOK 定案：仅 OpenAI 兼容）。 */
        val MEDIA_CAPABLE: Set<AiProviderProtocol> = setOf(OPENAI_COMPATIBLE)
    }
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
    DAILY_IMAGE;

    /** 文本生成类功能位：模型从文本服务商的模型池里按功能分配。 */
    val isChatModel: Boolean
        get() = this != TTS && this != DAILY_IMAGE

    companion object {
        /** 可按功能分配模型的槽位（顺序即界面展示顺序）。 */
        val CHAT_SLOTS: List<AiFeatureSlot> = entries.filter { it.isChatModel }
    }
}

/** 一个用户配置的服务商（文本 / 语音 / 图片生成）。 */
@Serializable
data class AiProviderConfig(
    /** 稳定标识：[GLOBAL_ID] / [TTS_ID] / [IMAGE_ID]。 */
    val id: String = GLOBAL_ID,
    val name: String = "",
    val protocol: AiProviderProtocol = AiProviderProtocol.OPENAI_COMPATIBLE,
    /** 形如 https://api.openai.com/v1 或 https://generativelanguage.googleapis.com 的根地址。 */
    val baseUrl: String = "",
    /** 主模型：文本服务商=默认聊天模型；语音=朗读模型；图片=生成模型。 */
    val model: String = "",
    /** 上次从服务商拉到的模型目录（落盘缓存，进页面先展示，可手动刷新）。 */
    val availableModels: List<String> = emptyList(),
    /** 用户勾选启用的模型；文本服务商可勾多个再按功能分配，语音/图片通常只勾一个。 */
    val enabledModels: List<String> = emptyList()
) {
    // 配置完整与否只看 baseUrl/model；Key 单独存 AiProviderKeyStore，永不回填本类
    val isConfigured: Boolean
        get() = baseUrl.isNotBlank() && model.isNotBlank()

    /** 有地址就能拉模型目录（Key 由调用方从 KeyStore 补）。 */
    val canFetchModels: Boolean
        get() = baseUrl.isNotBlank()

    /**
     * 可供分配/选择的模型池：用户勾选过就用勾选的，没勾过就用目录全量
     *（首次拉取后直接可分配，不必先逐个勾选）。
     */
    val modelPool: List<String>
        get() = enabledModels.ifEmpty { availableModels }

    companion object {
        const val GLOBAL_ID = "global"
        const val TTS_ID = "tts"
        const val IMAGE_ID = "image"

        /** 全部服务商 id（顺序即界面展示顺序：文本 → 语音 → 图片）。 */
        val ALL_IDS: List<String> = listOf(GLOBAL_ID, TTS_ID, IMAGE_ID)

        /** 未知 id 一律归到文本服务商，避免脏数据把配置写丢。 */
        fun normalizeId(id: String): String = if (id in ALL_IDS) id else GLOBAL_ID
    }
}

/**
 * 用户提供但不能随配置落盘明文的字段单独存放：
 * Key 走 EncryptedSharedPreferences，其余配置走 DataStore。
 * 本类仅在内存中组装完整配置时使用，不参与序列化落盘。
 */
data class AiProviderSecret(val apiKey: String = "")

/** 三个服务商配置 + 按功能分配模型 + 偏好开关（不含 Key 明文）。 */
@Serializable
data class AiProviderSettings(
    /** 文本服务商：聊天类功能的默认来源。 */
    val global: AiProviderConfig = AiProviderConfig(id = AiProviderConfig.GLOBAL_ID),
    /** 语音服务商（可选）：未配置时朗读回退系统 TTS。 */
    val tts: AiProviderConfig = AiProviderConfig(id = AiProviderConfig.TTS_ID),
    /** 图片生成服务商（可选）：未配置时不生成每日一图插图。 */
    val image: AiProviderConfig = AiProviderConfig(id = AiProviderConfig.IMAGE_ID),
    /** 文本功能位 → 指定模型；空表示用文本服务商的主模型。 */
    val modelOverrides: Map<String, String> = emptyMap(),
    /** 音色跟随角色开关（TTS 用）。 */
    val voiceFollowCharacter: Boolean = true,
    /** quiz 后台预生成开关，默认开。 */
    val quizPregenerateEnabled: Boolean = true
) {
    /** 按 id 取某个服务商配置。 */
    fun provider(id: String): AiProviderConfig = when (AiProviderConfig.normalizeId(id)) {
        AiProviderConfig.TTS_ID -> tts
        AiProviderConfig.IMAGE_ID -> image
        else -> global
    }

    /** 按 id 替换某个服务商配置。 */
    fun withProvider(id: String, config: AiProviderConfig): AiProviderSettings =
        when (AiProviderConfig.normalizeId(id)) {
            AiProviderConfig.TTS_ID -> copy(tts = config)
            AiProviderConfig.IMAGE_ID -> copy(image = config)
            else -> copy(global = config)
        }

    /**
     * 某功能位实际使用的服务商：语音/图片未配置时回退文本服务商
     *（回退后大概率因模型不匹配而失败，由上层按「朗读不可用/无配图」降级）。
     */
    fun providerFor(slot: AiFeatureSlot): AiProviderConfig = when (slot) {
        AiFeatureSlot.TTS -> tts.takeIf { it.isConfigured } ?: global
        AiFeatureSlot.DAILY_IMAGE -> image.takeIf { it.isConfigured } ?: global
        else -> global
    }

    /** 某功能位应使用的模型：文本类按功能分配优先，语音/图片用各自服务商模型。 */
    fun modelFor(slot: AiFeatureSlot): String = when (slot) {
        AiFeatureSlot.TTS -> tts.model.takeIf { it.isNotBlank() } ?: global.model
        AiFeatureSlot.DAILY_IMAGE -> image.model.takeIf { it.isNotBlank() } ?: global.model
        else -> modelOverrides[slot.name]?.takeIf { it.isNotBlank() } ?: global.model
    }

    /** 某功能位是否已具备可调用条件（语音/图片为可选增强，不影响整体可用）。 */
    fun isSlotConfigured(slot: AiFeatureSlot): Boolean = providerFor(slot).isConfigured
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
