package com.tracktosearch.data.ai.provider

import kotlinx.coroutines.flow.Flow

/**
 * BYOK 共享契约（第二部分）：Provider 客户端接口与数据载体。
 *
 * 三套协议适配器（OpenAI 兼容 / Gemini 原生 / Anthropic 原生）都实现本接口，
 * 上层（Repository / quiz 引擎 / TTS / 配图）只面向本接口编程。
 *
 * 统一约定：
 * - 输入输出都是纯文本（本项目所有 prompt 均为文本，无多模态输入）；
 * - 流式返回增量文本块（delta），由调用方自行拼接；
 * - 所有失败抛 [AiProviderException]，不得返回 null 或空串混过。
 */
interface AiProviderClient {
    /** 单轮补全（非流式）。用于 quiz 修题、测试连接、小请求。 */
    suspend fun complete(request: AiChatRequest, config: AiResolvedConfig): AiChatResponse

    /** 流式补全，逐块产出增量文本。用于 greeting/taste/daily/quiz 的阶段生成。 */
    fun stream(request: AiChatRequest, config: AiResolvedConfig): Flow<AiStreamChunk>

    /** 语音合成（OpenAI 兼容 /audio/speech）；不支持时抛 PROVIDER_NO_TTS。 */
    suspend fun synthesizeSpeech(request: AiTtsRequest, config: AiResolvedConfig): ByteArray

    /** 文生图（OpenAI 兼容 images/generations，b64_json）；不支持时抛 PROVIDER_NO_IMAGE。 */
    suspend fun generateImage(request: AiImageRequest, config: AiResolvedConfig): ByteArray

    /**
     * 拉取服务商模型目录（OpenAI 兼容 /models、Gemini /v1beta/models、Anthropic /v1/models）。
     * 配置页用它替代手输模型名；失败抛 [AiProviderException]（401/网络等原样上抛）。
     */
    suspend fun listModels(config: AiResolvedConfig): List<AiModelInfo>
}

/** 模型目录条目：id 用于请求，displayName 仅用于界面展示更友好的名字。 */
data class AiModelInfo(
    val id: String,
    val displayName: String? = null
)

/** 一次聊天补全请求（文本对话，messages 按序拼接）。 */
data class AiChatRequest(
    val system: String,
    val user: String,
    /** 生成温度；缺省用适配器内部默认（0.7）。 */
    val temperature: Double? = null,
    /** 最大输出 token；缺省由适配器给安全上限。 */
    val maxOutputTokens: Int? = null,
    /** 期望返回 JSON 时置 true：适配器负责加各自协议的 JSON 约束。 */
    val jsonMode: Boolean = false
)

/** 非流式补全结果。 */
data class AiChatResponse(
    val text: String,
    /** 实际使用模型（响应回带，测试连接展示用）。 */
    val model: String? = null
)

/** 流式增量块。文本块外还有一次性的终态事件，用于测试连接校验。 */
sealed interface AiStreamChunk {
    data class Delta(val text: String) : AiStreamChunk

    /** 流正常结束，携带累计文本（适配器汇总），触发点：[DONE] / message_stop / finishReason。 */
    data class Done(val fullText: String) : AiStreamChunk
}

/**
 * 解析后的单次调用配置：从 AiProviderSettings + AiProviderSecret 推导，
 * 携带本次该用的 baseUrl / Key / model。适配器不接触存储层。
 */
data class AiResolvedConfig(
    val protocol: AiProviderProtocol,
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    /** 请求经 gateway-pages 之外直连，超时由适配器常量控制。 */
    val connectTimeoutMs: Int = 15_000,
    /** 首包超时：两段式生成首包可达数十秒。 */
    val readTimeoutMs: Int = 90_000
)

/** TTS 请求。voice 语义：标准音色名或自定义音色名，由适配器透传。 */
data class AiTtsRequest(
    val text: String,
    val voice: String,
    /** 语速 0.25~4.0，缺省 1.0。 */
    val speed: Double = 1.0
)

/** 配图请求。 */
data class AiImageRequest(
    val prompt: String,
    /** 尺寸，缺省 1024x1024。 */
    val size: String = "1024x1024",
    /** 返回格式固定 b64_json。 */
    val n: Int = 1
)
