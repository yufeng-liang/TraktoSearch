package com.tracktosearch.data.ai.provider

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 单个模型的可用性探测结果（配置页逐个模型显示状态用）。
 * [message] 仅在失败时有值，直接展示服务商原始报错，方便用户判断是 Key、额度还是模型名问题。
 */
data class AiModelProbe(
    val model: String,
    val ok: Boolean,
    val latencyMs: Long = 0,
    val message: String? = null
)

/**
 * BYOK 配置外观（Repository）：组合 [AiProviderSettingsStore]（配置主体）与
 * [AiProviderKeyStore]（Key 密文），向上层提供读写设置、拉取/测试模型、
 * 按功能位推导可执行配置三类能力。
 *
 * 推导规则（BYOK 定案 v2）：
 * - 语音（TTS）/ 图片（DAILY_IMAGE）：各自服务商已配置则用各自的地址/Key/模型，
 *   否则回退文本服务商（回退后模型往往不匹配，由上层按「朗读不可用/无配图」降级）；
 * - 其余功能位一律走文本服务商，模型可按功能位单独指定（默认用主模型）。
 *
 * 不加 Hilt 注解：单例装配由 di/AiProviderModule 负责。
 */
class AiProviderSettingsRepository(
    private val settingsStore: AiProviderSettingsStore,
    private val keyStore: AiProviderKeyStore
) {
    /** 当前完整设置流（不含 Key 明文）。 */
    val settings: Flow<AiProviderSettings> = settingsStore.settings

    /** 读取当前设置快照。 */
    suspend fun currentSettings(): AiProviderSettings = settings.first()

    /**
     * 按功能位推导一次调用该用的完整配置（protocol / baseUrl / Key / model）。
     * 未配置（Key 为空或 baseUrl/model 空）抛 [AiProviderErrorCode.PROVIDER_INVALID]。
     *
     * 半配置回退：语音/图片服务商只填了地址、没填 Key 时回退到全局服务商。
     * 否则用户「配了一半」反而比不配更糟——不配还能跟随全局，配一半直接报错。
     * 回退时模型也改用全局主模型，避免拿媒体服务商的模型名去请求全局地址。
     */
    suspend fun resolve(slot: AiFeatureSlot): AiResolvedConfig {
        val settings = currentSettings()
        val configured = settings.providerFor(slot)
        val fellBackToGlobal = configured.id != AiProviderConfig.GLOBAL_ID &&
            keyStore.getKey(configured.id).isBlank()
        val provider = if (fellBackToGlobal) settings.global else configured
        val apiKey = keyStore.getKey(provider.id)
        val model = if (fellBackToGlobal) settings.global.model else settings.modelFor(slot)
        if (apiKey.isBlank() || provider.baseUrl.isBlank() || model.isBlank()) {
            throw AiProviderException(
                code = AiProviderErrorCode.PROVIDER_INVALID,
                message = "Provider for ${slot.name} is not fully configured"
            )
        }
        // 存量兼容：早期版本允许存「裸域名」，这里拦下并引导用户回配置页重存，
        // 而不是等到真正发请求时抛一条晦涩的 OkHttp 报错。
        if (!AiProviderUrl.hasValidScheme(provider.baseUrl)) {
            throw AiProviderException(
                code = AiProviderErrorCode.PROVIDER_INVALID,
                message = "Provider base URL must start with http:// or https:// — " +
                    "open the AI provider settings and re-save the address"
            )
        }
        return AiResolvedConfig(
            protocol = provider.protocol,
            baseUrl = AiProviderUrl.normalizeBaseUrl(provider.baseUrl),
            apiKey = apiKey,
            model = model
        )
    }

    /** 指定服务商是否已存有 API Key（配置页「已保存」角标用）。 */
    fun hasKey(id: String): Boolean = keyStore.getKey(id).isNotBlank()

    /**
     * 拉取服务商模型目录并缓存到设置里（下次进页面先展示缓存，再手动刷新）。
     * 文本服务商会过滤掉明显不能聊天的模型（embedding/rerank/whisper/图像/审核等），
     * 语音与图片服务商不过滤（模型本来就少，交给用户自己勾）。
     *
     * @param apiKeyOverride 界面刚输入但还没保存的 Key；为空则回退已存 Key。
     */
    suspend fun fetchModels(
        providerId: String,
        protocol: AiProviderProtocol,
        baseUrl: String,
        apiKeyOverride: String?
    ): List<String> {
        val id = AiProviderConfig.normalizeId(providerId)
        val config = buildConfig(id, protocol, baseUrl, model = "-", apiKeyOverride = apiKeyOverride)
        val models = AiProviderClientFactory.create(config.protocol)
            .listModels(config)
            .map { it.id }
            .filter { it.isNotBlank() }
            .let { ids -> if (id == AiProviderConfig.GLOBAL_ID) ids.filterNot(::isNonChatModel) else ids }
            .distinct()
            .sorted()
        if (models.isEmpty()) {
            throw AiProviderException(
                AiProviderErrorCode.INVALID_RESPONSE,
                "The provider returned an empty model list"
            )
        }
        // 落盘缓存：enabledModels 里已不在目录中的条目一并剔除，避免按功能分配时选到失效模型；
        // 一条都没勾选时默认勾上主模型（没有主模型就勾第一个），否则界面会出现「已设主模型却未勾选」的矛盾态
        settingsStore.update { current ->
            val provider = current.provider(id)
            val known = models.toSet()
            val enabled = provider.enabledModels.filter { it in known }.ifEmpty {
                provider.model.takeIf { it in known }?.let { listOf(it) } ?: models.take(1)
            }
            current.withProvider(
                id,
                provider.copy(availableModels = models, enabledModels = enabled)
            )
        }
        return models
    }

    /**
     * 探测单个模型是否真的可用（不是只测连通性）：
     * - 文本服务商：发一句极短的补全请求，看是否返回非空内容；
     * - 语音服务商：合成一句极短文本，看是否返回音频字节；
     * - 图片服务商：真的生成一张图（会消耗一次额度，界面需提前告知用户）。
     *
     * 失败不抛异常，返回 ok=false 与原始 message，让界面逐个展示。
     */
    suspend fun probeModel(
        providerId: String,
        protocol: AiProviderProtocol,
        baseUrl: String,
        model: String,
        apiKeyOverride: String?
    ): AiModelProbe {
        val id = AiProviderConfig.normalizeId(providerId)
        val startedAt = System.currentTimeMillis()
        return try {
            val config = buildConfig(id, protocol, baseUrl, model, apiKeyOverride)
            val client = AiProviderClientFactory.create(config.protocol)
            // 探测加 60s 超时：单模型卡死不该让整页转圈
            val ok = withTimeoutOrNull(PROBE_TIMEOUT_MS) {
                when (id) {
                    AiProviderConfig.TTS_ID -> client.synthesizeSpeech(
                        AiTtsRequest(text = TTS_PROBE_TEXT, voice = TTS_PROBE_VOICE),
                        config = config
                    ).isNotEmpty()
                    AiProviderConfig.IMAGE_ID -> client.generateImage(
                        AiImageRequest(prompt = IMAGE_PROBE_PROMPT, size = IMAGE_PROBE_SIZE),
                        config = config
                    ).isNotEmpty()
                    else -> client.complete(
                        request = AiChatRequest(
                            system = "You are a connectivity test.",
                            user = "Reply with the single word: pong",
                            maxOutputTokens = CHAT_PROBE_MAX_TOKENS
                        ),
                        config = config
                    ).text.isNotBlank()
                }
            } ?: false
            val latency = System.currentTimeMillis() - startedAt
            AiModelProbe(
                model = model,
                ok = ok,
                latencyMs = latency,
                message = if (ok) null else "Timed out after ${PROBE_TIMEOUT_MS / 1000}s"
            )
        } catch (e: AiProviderException) {
            AiModelProbe(model, false, 0, e.message ?: e.code.name)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            AiModelProbe(model, false, 0, e.message ?: "unknown")
        }
    }

    /** 保存某服务商勾选启用的模型（界面上的勾选状态）。 */
    suspend fun setEnabledModels(providerId: String, models: List<String>) {
        val id = AiProviderConfig.normalizeId(providerId)
        settingsStore.update { current ->
            current.withProvider(id, current.provider(id).copy(enabledModels = models.distinct()))
        }
    }

    /** 设置某服务商的主模型（文本=默认聊天模型；语音=朗读模型；图片=生成模型）。 */
    suspend fun setPrimaryModel(providerId: String, model: String) {
        val id = AiProviderConfig.normalizeId(providerId)
        settingsStore.update { current ->
            current.withProvider(id, current.provider(id).copy(model = model.trim()))
        }
    }

    /**
     * 保存一条服务商配置。地址与协议立即生效；模型留空表示「还没选」，
     * 用户可以只填地址和 Key 就保存，再去拉目录选模型。
     *
     * @param apiKey 非空 = 覆盖 Key 明文；null/空 = 保持原 Key 不变（界面不回显明文）。
     */
    suspend fun saveProviderConfig(
        id: String,
        protocol: AiProviderProtocol,
        baseUrl: String,
        model: String,
        apiKey: String?
    ) {
        val normalizedId = AiProviderConfig.normalizeId(id)
        // scheme 校验前置：坏地址在保存当场就报错，而不是落盘后 resolve 才炸。
        if (!AiProviderUrl.hasValidScheme(baseUrl)) {
            throw AiProviderException(
                code = AiProviderErrorCode.PROVIDER_INVALID,
                message = "API base URL must start with http:// or https://"
            )
        }
        // 语音/图片服务商只支持 OpenAI 兼容协议，其余协议自动收敛
        val effectiveProtocol =
            if (normalizedId == AiProviderConfig.GLOBAL_ID) protocol
            else if (protocol in AiProviderProtocol.MEDIA_CAPABLE) protocol
            else AiProviderProtocol.OPENAI_COMPATIBLE
        settingsStore.update { current ->
            val base = current.provider(normalizedId)
            current.withProvider(
                normalizedId,
                base.copy(
                    protocol = effectiveProtocol,
                    // 落盘时就剥掉用户多贴的端点路径，下次进页面显示的就是干净地址
                    baseUrl = AiProviderUrl.normalizeBaseUrl(baseUrl),
                    model = model.trim()
                )
            )
        }
        if (!apiKey.isNullOrBlank()) {
            keyStore.setKey(normalizedId, apiKey.trim())
        }
    }

    /** 按功能位指定模型（空串 = 恢复用文本服务商主模型）。 */
    suspend fun saveModelOverride(slot: AiFeatureSlot, model: String) {
        if (!slot.isChatModel) return
        settingsStore.update { current ->
            val overrides = current.modelOverrides.toMutableMap()
            if (model.isBlank()) overrides.remove(slot.name) else overrides[slot.name] = model.trim()
            current.copy(modelOverrides = overrides)
        }
    }

    /**
     * 清除某个服务商的 API Key。
     *
     * Key 只能写不能读回，所以「换一个 Key」可以直接覆盖，但「删掉 Key」必须有显式入口：
     * 删 Key 同时清掉该服务商的模型目录/勾选/主模型，避免留下「有模型却没有凭据」的半配置态。
     */
    suspend fun clearProviderKey(id: String) {
        val normalizedId = AiProviderConfig.normalizeId(id)
        keyStore.setKey(normalizedId, "")
        settingsStore.update { current ->
            val base = current.provider(normalizedId)
            val cleared = base.copy(
                model = "",
                availableModels = emptyList(),
                enabledModels = emptyList()
            )
            val overrides = if (normalizedId == AiProviderConfig.GLOBAL_ID) {
                emptyMap()
            } else {
                current.modelOverrides
            }
            current.withProvider(normalizedId, cleared).copy(modelOverrides = overrides)
        }
    }

    /** 音色跟随角色开关。 */
    suspend fun setVoiceFollowCharacter(enabled: Boolean) {
        settingsStore.update { it.copy(voiceFollowCharacter = enabled) }
    }

    /** 出题预生成开关。 */
    suspend fun setQuizPregenerateEnabled(enabled: Boolean) {
        settingsStore.update { it.copy(quizPregenerateEnabled = enabled) }
    }

    /**
     * 用界面当前输入拼一条临时配置（不落盘）；Key 覆盖为空时回退已存 Key。
     * 任一要素缺失抛 [AiProviderErrorCode.PROVIDER_INVALID]。
     */
    private suspend fun buildTestConfig(
        providerId: String,
        protocol: AiProviderProtocol,
        baseUrl: String,
        model: String,
        apiKeyOverride: String?
    ): AiResolvedConfig =
        buildConfig(AiProviderConfig.normalizeId(providerId), protocol, baseUrl, model, apiKeyOverride)

    /** 组装内存配置：协议按服务商能力收敛，Key 覆盖优先，最后校验完整性。 */
    private suspend fun buildConfig(
        normalizedId: String,
        protocol: AiProviderProtocol,
        baseUrl: String,
        model: String,
        apiKeyOverride: String?
    ): AiResolvedConfig {
        val effectiveProtocol =
            if (normalizedId == AiProviderConfig.GLOBAL_ID) protocol
            else if (protocol in AiProviderProtocol.MEDIA_CAPABLE) protocol
            else AiProviderProtocol.OPENAI_COMPATIBLE
        val apiKey = apiKeyOverride?.takeIf { it.isNotBlank() } ?: keyStore.getKey(normalizedId)
        if (apiKey.isBlank()) {
            throw AiProviderException(
                code = AiProviderErrorCode.PROVIDER_INVALID,
                message = "API key is missing"
            )
        }
        if (baseUrl.isBlank()) {
            throw AiProviderException(
                code = AiProviderErrorCode.PROVIDER_INVALID,
                message = "API base URL is missing"
            )
        }
        if (!AiProviderUrl.hasValidScheme(baseUrl)) {
            throw AiProviderException(
                code = AiProviderErrorCode.PROVIDER_INVALID,
                message = "API base URL must start with http:// or https://"
            )
        }
        return AiResolvedConfig(
            protocol = effectiveProtocol,
            baseUrl = AiProviderUrl.normalizeBaseUrl(baseUrl),
            apiKey = apiKey,
            model = model.trim()
        )
    }

    private companion object {
        /** 单模型探测超时：卡死的模型不该让整页转圈。 */
        const val PROBE_TIMEOUT_MS = 60_000L

        /**
         * 聊天探测的 max_tokens 上限。取 256 而不是更小的值：
         * reasoning 模型（o 系 / 思考型 Gemini / Claude extended thinking）会把
         * 生成预算整段花在思考上，给 8 个 token 时 content 必然为空，
         * 会把「完全可用的模型」误报成探测失败。
         */
        const val CHAT_PROBE_MAX_TOKENS = 256
        const val TTS_PROBE_TEXT = "到！"
        const val TTS_PROBE_VOICE = "alloy"
        const val IMAGE_PROBE_PROMPT = "a single small red circle on white background"
        const val IMAGE_PROBE_SIZE = "1024x1024"

        /**
         * 明显不能用于聊天/生成文本的模型关键词：模型目录里常混着这类条目，
         * 让用户在配置页勾选时不至于一屏都是 embedding。
         */
        val NON_CHAT_MODEL_KEYWORDS = listOf(
            "embedding", "embed", "rerank", "whisper", "moderation", "tts",
            "dall-e", "dalle", "gpt-image", "text-to-speech", "speech-to-text",
            "audio-preview", "transcribe", "realtime", "image", "vision-preview"
        )

        fun isNonChatModel(modelId: String): Boolean {
            val lower = modelId.lowercase()
            return NON_CHAT_MODEL_KEYWORDS.any { lower.contains(it) }
        }
    }
}
