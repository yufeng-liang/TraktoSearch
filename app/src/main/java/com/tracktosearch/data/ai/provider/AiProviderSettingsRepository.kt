package com.tracktosearch.data.ai.provider

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/**
 * BYOK 配置外观（Repository）：组合 [AiProviderSettingsStore]（配置主体）与
 * [AiProviderKeyStore]（Key 密文），向上层提供读写设置、按功能位推导可执行配置、
 * 测试连接三类能力。
 *
 * 推导规则（BYOK 定案）：
 * - TTS / DAILY_IMAGE：media Provider 已配置（isConfigured）则用 media，否则回退 global；
 * - 其余功能位一律走 global；
 * - 模型覆盖经 [AiProviderSettings.modelFor]（覆盖优先，回退全局模型）。
 *
 * 不加 Hilt 注解：单例装配由后续集成的 Hilt 模块负责。
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
     * 按功能位推导一次调用该用的完整配置（baseUrl / Key / model）。
     * 未配置（Key 为空或 baseUrl/model 空）抛 [AiProviderErrorCode.PROVIDER_INVALID]。
     */
    suspend fun resolve(slot: AiFeatureSlot): AiResolvedConfig {
        val settings = currentSettings()
        val useMedia = settings.usesMediaProvider(slot)
        val provider = if (useMedia) settings.media else settings.global
        val apiKey = keyStore.getKey(provider.id)
        // 模型取法：按功能覆盖优先；未覆盖时 media 槽位用 media 自带 model
        //（media.isConfigured 已保证非空），其余槽位回退全局模型（即 settings.modelFor(slot)）。
        val model = settings.modelOverrides[slot.name]?.takeIf { it.isNotBlank() }
            ?: if (useMedia) provider.model else settings.global.model

        // 未配置的判断口径：Key 缺失，或 baseUrl/model 任一为空（isConfigured 只查了后两者）
        if (apiKey.isBlank() || provider.baseUrl.isBlank() || model.isBlank()) {
            throw AiProviderException(
                code = AiProviderErrorCode.PROVIDER_INVALID,
                message = if (useMedia) {
                    "Media provider for ${slot.name} is not fully configured"
                } else {
                    "Global provider for ${slot.name} is not fully configured"
                }
            )
        }
        return AiResolvedConfig(
            protocol = provider.protocol,
            baseUrl = provider.baseUrl.trim(),
            apiKey = apiKey,
            model = model
        )
    }

    /**
     * 测试连接：发一句 "ping"，要求对端回 "pong"。
     * 适配器抛出的 [AiProviderException] 原样透传，UI 直接展示 message。
     */
    suspend fun testConnection(config: AiResolvedConfig): AiChatResponse {
        val client = AiProviderClientFactory.create(config.protocol)
        return client.complete(
            request = AiChatRequest(
                system = "You are a connectivity test.",
                user = "Reply with the single word: pong"
            ),
            config = config
        )
    }

    /** 指定 Provider 是否已存有 API Key（配置页「Key 已保存」角标用）。 */
    fun hasKey(id: String): Boolean = keyStore.getKey(id).isNotBlank()

    /**
     * 用界面当前输入拼一条测试配置（不落盘）：Key 覆盖为空时回退已存 Key。
     * 任一要素缺失抛 [AiProviderErrorCode.PROVIDER_INVALID]，UI 提示补全后再测。
     */
    suspend fun buildTestConfig(
        providerId: String,
        protocol: AiProviderProtocol,
        baseUrl: String,
        model: String,
        apiKeyOverride: String?
    ): AiResolvedConfig {
        val normalizedId = if (providerId == AiProviderConfig.MEDIA_ID) AiProviderConfig.MEDIA_ID else AiProviderConfig.GLOBAL_ID
        val effectiveProtocol =
            if (normalizedId == AiProviderConfig.MEDIA_ID) AiProviderProtocol.OPENAI_COMPATIBLE else protocol
        val apiKey = apiKeyOverride?.takeIf { it.isNotBlank() } ?: keyStore.getKey(normalizedId)
        if (apiKey.isBlank() || baseUrl.isBlank() || model.isBlank()) {
            throw AiProviderException(
                code = AiProviderErrorCode.PROVIDER_INVALID,
                message = "Test config is incomplete"
            )
        }
        return AiResolvedConfig(protocol = effectiveProtocol, baseUrl = baseUrl.trim(), apiKey = apiKey, model = model.trim())
    }

    /**
     * 保存一条 Provider 配置。
     * @param apiKey 非空 = 覆盖 Key 明文；null/空 = 保持原 Key 不变（编辑页不回显明文的约定）。
     * media 槽协议固定 OPENAI_COMPATIBLE（语音/配图仅支持该协议，见 BYOK 定案）。
     */
    suspend fun saveProviderConfig(
        id: String,
        protocol: AiProviderProtocol,
        baseUrl: String,
        model: String,
        apiKey: String?
    ) {
        val normalizedId = if (id == AiProviderConfig.MEDIA_ID) AiProviderConfig.MEDIA_ID else AiProviderConfig.GLOBAL_ID
        val effectiveProtocol =
            if (normalizedId == AiProviderConfig.MEDIA_ID) AiProviderProtocol.OPENAI_COMPATIBLE else protocol
        settingsStore.update { current ->
            val base = if (normalizedId == AiProviderConfig.MEDIA_ID) current.media else current.global
            val updated = base.copy(protocol = effectiveProtocol, baseUrl = baseUrl.trim(), model = model.trim())
            if (normalizedId == AiProviderConfig.MEDIA_ID) current.copy(media = updated) else current.copy(global = updated)
        }
        if (!apiKey.isNullOrBlank()) {
            keyStore.setKey(normalizedId, apiKey.trim())
        }
    }

    /** 按功能位覆盖模型（空串 = 恢复用全局模型）。 */
    suspend fun saveModelOverride(slot: AiFeatureSlot, model: String) {
        settingsStore.update { current ->
            val overrides = current.modelOverrides.toMutableMap()
            if (model.isBlank()) overrides.remove(slot.name) else overrides[slot.name] = model.trim()
            current.copy(modelOverrides = overrides)
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
}
