package com.tracktosearch.ui.screen.ai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tracktosearch.data.ai.provider.AiFeatureSlot
import com.tracktosearch.data.ai.provider.AiModelProbe
import com.tracktosearch.data.ai.provider.AiProviderConfig
import com.tracktosearch.data.ai.provider.AiProviderErrorCode
import com.tracktosearch.data.ai.provider.AiProviderException
import com.tracktosearch.data.ai.provider.AiProviderProtocol
import com.tracktosearch.data.ai.provider.AiProviderSettings
import com.tracktosearch.data.ai.provider.AiProviderSettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 单个服务商的界面态：拉目录、逐个模型探测、保存反馈。
 * ViewModel 层的 message 一律英文，界面负责映射成用户文案。
 */
data class ProviderUiStatus(
    val fetchingModels: Boolean = false,
    val fetchError: String? = null,
    val probingModels: Set<String> = emptySet(),
    val probes: Map<String, AiModelProbe> = emptyMap(),
    val saved: Boolean = false,
    val saveError: String? = null
)

/**
 * BYOK 服务商配置页 ViewModel。
 *
 * 交互模型（v2）：
 * - 用户只填地址与 Key，模型不再手输：点「拉取模型」从服务商目录取回，
 *   勾选要用的模型，再把它们按功能分配；
 * - 每个勾选的模型都能单独探测可用性（真的发一次请求），避免选到不可用模型；
 * - 语音、图片生成各自独立配置，互不影响。
 *
 * Key 明文只在编辑框里存在一次，保存即进 Keystore，界面永不回显。
 */
@HiltViewModel
class AiProviderSettingsViewModel @Inject constructor(
    private val repository: AiProviderSettingsRepository
) : ViewModel() {

    val settings: StateFlow<AiProviderSettings> = repository.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AiProviderSettings())

    private val _status = MutableStateFlow<Map<String, ProviderUiStatus>>(emptyMap())
    val status: StateFlow<Map<String, ProviderUiStatus>> = _status.asStateFlow()

    /** 已保存过 Key 的服务商 id 集合（界面据此显示「已保存」占位，Key 明文永不回显）。 */
    private val _withKey = MutableStateFlow<Set<String>>(emptySet())
    val withKey: StateFlow<Set<String>> = _withKey.asStateFlow()

    init {
        viewModelScope.launch { refreshKeyState() }
    }

    private suspend fun refreshKeyState() {
        _withKey.value = AiProviderConfig.ALL_IDS.filterTo(mutableSetOf()) { repository.hasKey(it) }
    }

    fun statusOf(providerId: String): ProviderUiStatus =
        _status.value[AiProviderConfig.normalizeId(providerId)] ?: ProviderUiStatus()

    /** 指定服务商是否已存有 Key（保存过一次后界面显示「已保存」）。 */
    fun hasKey(providerId: String): Boolean =
        AiProviderConfig.normalizeId(providerId) in _withKey.value

    /** 从服务商拉取模型目录（写回设置缓存）。 */
    fun fetchModels(providerId: String, protocol: AiProviderProtocol, baseUrl: String, apiKey: String) {
        val id = AiProviderConfig.normalizeId(providerId)
        if (statusOf(id).fetchingModels) return
        updateStatus(id) { it.copy(fetchingModels = true, fetchError = null) }
        viewModelScope.launch {
            runCatching {
                repository.fetchModels(id, protocol, baseUrl, apiKey.ifBlank { null })
            }.onSuccess { models ->
                updateStatus(id) { it.copy(fetchingModels = false, fetchError = null) }
                // 能拉到目录说明地址与 Key 都对：顺手落盘，省掉一次「保存」
                val current = repository.currentSettings().provider(id)
                runCatching {
                    repository.saveProviderConfig(id, protocol, baseUrl, current.model, apiKey.ifBlank { null })
                }
                refreshKeyState()
                // 还没有主模型、或原主模型已不在目录里时，落到第一个模型
                if (models.isNotEmpty() && (current.model.isBlank() || current.model !in models)) {
                    repository.setPrimaryModel(id, models.first())
                }
            }.onFailure { error ->
                if (error is kotlinx.coroutines.CancellationException) throw error
                updateStatus(id) { it.copy(fetchingModels = false, fetchError = describe(error)) }
            }
        }
    }

    /** 逐个探测模型是否真的可用（文本=补全、语音=合成、图片=真生成一张）。 */
    fun probeModels(
        providerId: String,
        protocol: AiProviderProtocol,
        baseUrl: String,
        models: List<String>,
        apiKey: String
    ) {
        val id = AiProviderConfig.normalizeId(providerId)
        if (models.isEmpty() || statusOf(id).probingModels.isNotEmpty()) return
        updateStatus(id) { it.copy(probingModels = models.toSet()) }
        viewModelScope.launch {
            models.forEach { model ->
                val probe = repository.probeModel(id, protocol, baseUrl, model, apiKey.ifBlank { null })
                updateStatus(id) { current ->
                    current.copy(
                        probingModels = current.probingModels - model,
                        probes = current.probes + (model to probe)
                    )
                }
            }
        }
    }

    /** 勾选/取消勾选一个模型。勾选的第一个模型自动成为主模型。 */
    fun toggleModel(providerId: String, model: String, enabled: Boolean) {
        val id = AiProviderConfig.normalizeId(providerId)
        viewModelScope.launch {
            val current = repository.currentSettings().provider(id)
            val next = if (enabled) {
                (current.enabledModels + model).distinct()
            } else {
                current.enabledModels - model
            }
            repository.setEnabledModels(id, next)
            // 主模型必须落在已勾选集合内：空池或主模型被取消勾选时，落到池里第一个
            if (next.isEmpty() || current.model !in next) {
                repository.setPrimaryModel(id, next.firstOrNull().orEmpty())
            }
        }
    }

    /** 指定主模型（文本=默认聊天模型；语音=朗读模型；图片=生成模型）。 */
    fun setPrimaryModel(providerId: String, model: String) {
        viewModelScope.launch {
            repository.setPrimaryModel(AiProviderConfig.normalizeId(providerId), model)
        }
    }

    /** 保存服务商：地址、协议、主模型、Key（Key 留空表示保持原 Key）。 */
    fun saveProvider(
        providerId: String,
        protocol: AiProviderProtocol,
        baseUrl: String,
        model: String,
        apiKey: String
    ) {
        val id = AiProviderConfig.normalizeId(providerId)
        updateStatus(id) { it.copy(saved = false, saveError = null) }
        viewModelScope.launch {
            runCatching {
                repository.saveProviderConfig(id, protocol, baseUrl, model, apiKey.ifBlank { null })
            }.onSuccess {
                updateStatus(id) { it.copy(saved = true) }
                refreshKeyState()
            }.onFailure { error ->
                if (error is kotlinx.coroutines.CancellationException) throw error
                updateStatus(id) { it.copy(saveError = describe(error)) }
            }
        }
    }

    /** 按功能位指定模型；空串 = 恢复用文本服务商主模型。 */
    fun saveModelOverride(slot: AiFeatureSlot, model: String) {
        viewModelScope.launch { repository.saveModelOverride(slot, model) }
    }

    fun setVoiceFollowCharacter(enabled: Boolean) {
        viewModelScope.launch { repository.setVoiceFollowCharacter(enabled) }
    }

    fun setQuizPregenerateEnabled(enabled: Boolean) {
        viewModelScope.launch { repository.setQuizPregenerateEnabled(enabled) }
    }

    /** 清掉某服务商的探测结果（重新拉目录后旧结果就过期了）。 */
    fun clearProbes(providerId: String) {
        updateStatus(AiProviderConfig.normalizeId(providerId)) { it.copy(probes = emptyMap()) }
    }

    private fun updateStatus(providerId: String, transform: (ProviderUiStatus) -> ProviderUiStatus) {
        _status.update { current ->
            current + (providerId to transform(current[providerId] ?: ProviderUiStatus()))
        }
    }

    private fun describe(error: Throwable): String = when (error) {
        is AiProviderException -> when (error.code) {
            AiProviderErrorCode.PROVIDER_INVALID -> "API key or base URL is missing"
            else -> error.message ?: error.code.name
        }
        else -> error.message ?: "unknown"
    }
}
