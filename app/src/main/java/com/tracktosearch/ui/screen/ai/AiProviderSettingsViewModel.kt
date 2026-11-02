package com.tracktosearch.ui.screen.ai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tracktosearch.data.ai.provider.AiFeatureSlot
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
import kotlinx.coroutines.launch

/** 配置页单条测试连接的可见状态（ViewModel error 走英文，UI 文案由界面层映射）。 */
data class ProviderTestState(
    val runningFor: String? = null,
    val successFor: String? = null,
    val failureFor: String? = null,
    val failureMessage: String? = null
)

/**
 * BYOK Provider 配置页 ViewModel：读写设置、按功能覆盖模型、开关偏好、测试连接。
 * Key 明文只在编辑框里存在一次，保存即进 Keystore，界面永不回显。
 */
@HiltViewModel
class AiProviderSettingsViewModel @Inject constructor(
    private val repository: AiProviderSettingsRepository
) : ViewModel() {

    val settings: StateFlow<AiProviderSettings> = repository.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AiProviderSettings())

    private val _testState = MutableStateFlow(ProviderTestState())
    val testState: StateFlow<ProviderTestState> = _testState.asStateFlow()

    /** 指定 Provider 是否已存有 Key（保存过一次后界面显示「已保存」角标）。 */
    fun hasKey(providerId: String): Boolean = repository.hasKey(providerId)

    /** 保存全局或语音/配图 Provider；apiKey 为空串表示保持原 Key。 */
    fun saveProvider(
        providerId: String,
        protocol: AiProviderProtocol,
        baseUrl: String,
        model: String,
        apiKey: String,
        onSaved: () -> Unit
    ) {
        viewModelScope.launch {
            repository.saveProviderConfig(
                id = providerId,
                protocol = protocol,
                baseUrl = baseUrl,
                model = model,
                apiKey = apiKey.ifBlank { null }
            )
            onSaved()
        }
    }

    /** 按功能位覆盖模型；空串 = 恢复全局。 */
    fun saveModelOverride(slot: AiFeatureSlot, model: String) {
        viewModelScope.launch { repository.saveModelOverride(slot, model) }
    }

    fun setVoiceFollowCharacter(enabled: Boolean) {
        viewModelScope.launch { repository.setVoiceFollowCharacter(enabled) }
    }

    fun setQuizPregenerateEnabled(enabled: Boolean) {
        viewModelScope.launch { repository.setQuizPregenerateEnabled(enabled) }
    }

    /**
     * 测试连接：直接用界面当前输入（含未保存的 Key）拼临时配置发一句 ping，
     * 让用户在保存前就能验证填法是否正确。Key 留空时退回已保存的 Key。
     */
    fun testConnection(providerId: String, protocol: AiProviderProtocol, baseUrl: String, model: String, apiKey: String) {
        if (_testState.value.runningFor != null) return
        viewModelScope.launch {
            _testState.value = ProviderTestState(runningFor = providerId)
            _testState.value = try {
                val config = repository.buildTestConfig(
                    providerId = providerId,
                    protocol = protocol,
                    baseUrl = baseUrl,
                    model = model,
                    apiKeyOverride = apiKey.ifBlank { null }
                )
                repository.testConnection(config)
                ProviderTestState(successFor = providerId)
            } catch (e: AiProviderException) {
                ProviderTestState(failureFor = providerId, failureMessage = describe(e))
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                ProviderTestState(failureFor = providerId, failureMessage = e.message ?: "unknown")
            }
        }
    }

    private fun describe(e: AiProviderException): String = when (e.code) {
        AiProviderErrorCode.PROVIDER_INVALID -> "provider not fully configured"
        else -> e.message ?: e.code.name
    }
}
