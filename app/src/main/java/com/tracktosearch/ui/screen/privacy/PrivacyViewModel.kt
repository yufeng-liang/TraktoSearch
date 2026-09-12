package com.tracktosearch.ui.screen.privacy

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tracktosearch.R
import com.tracktosearch.data.ai.AiProfileRepository
import com.tracktosearch.data.ai.AiProfileSettings
import com.tracktosearch.data.ai.AiProfileSettingsDto
import com.tracktosearch.data.ai.AiProfileSettingsRequest
import com.tracktosearch.data.ai.AiRepository
import com.tracktosearch.data.auth.AuthManager
import com.tracktosearch.data.local.AiTasteStorage
import com.tracktosearch.data.local.CrashLogStorage
import com.tracktosearch.data.util.CrashLogUploader
import com.tracktosearch.ui.haptic.HapticOutcome
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * AI 画像开关组的一次性反馈。
 *
 * 只带资源 ID 与触感方向：文案按当前语言在组合期渲染，VM 不碰 Context。
 * 本页所有画像写入（含清除）的结果都从这一条流出去，界面只挂一处 snackbar。
 */
@Immutable
data class PrivacyMessage(
    @StringRes val resId: Int,
    /** null 表示这条消息不该发触感 */
    val outcome: HapticOutcome?
)

/**
 * AI 画像开关组的界面状态。
 *
 * [isAvailable] 是「有没有可归属的 friendId」：画像、行为计数与同步批次都按
 * friendId 落在服务端，未登录时整组开关只读。
 */
@Immutable
data class AiProfileSettingsState(
    val friendId: String? = null,
    val profileConsent: Boolean = false,
    val behaviorConsent: Boolean = false,
    val personalizationEnabled: Boolean = false,
    val syncEnabled: Boolean = true,
    val isLoading: Boolean = false,
    val isUpdating: Boolean = false
) {
    val isAvailable: Boolean get() = !friendId.isNullOrBlank()
}

/**
 * 「数据与隐私」页 ViewModel。
 *
 * 独立于 SettingsViewModel 自建（设置页原「AI 与隐私」分组与崩溃日志开关行
 * 迁入本页），逻辑与原 SettingsViewModel 中的实现保持一致：
 * - AI taste 开关：存储层是冷 Flow，stateIn 起来给开关绑定；首值落地前用默认 true 占位
 * - 崩溃日志开关：存储层已是 StateFlow（默认 false），直接透传
 * - AI 画像开关组：按 friendId 隔离，云端写入失败时整体回滚
 */
@HiltViewModel
class PrivacyViewModel @Inject constructor(
    private val aiTasteStorage: AiTasteStorage,
    private val crashLogStorage: CrashLogStorage,
    private val crashLogUploader: CrashLogUploader,
    private val aiProfileRepository: AiProfileRepository,
    private val aiRepository: AiRepository,
    private val authManager: AuthManager
) : ViewModel() {

    /** 「AI 锐评看单」数据上传开关（默认开启） */
    val aiTasteEnabled: StateFlow<Boolean> = aiTasteStorage.tasteUploadEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    /** 崩溃日志上报开关（默认关闭，需用户授权） */
    val crashLogEnabled: StateFlow<Boolean> = crashLogStorage.enabled

    private val _message = MutableStateFlow<PrivacyMessage?>(null)
    val message: StateFlow<PrivacyMessage?> = _message.asStateFlow()

    private val _aiProfileSettings = MutableStateFlow(AiProfileSettingsState(isLoading = true))
    val aiProfileSettings: StateFlow<AiProfileSettingsState> = _aiProfileSettings.asStateFlow()

    init {
        // 画像按 friendId 隔离：换账号就整组重载，登出则清回默认（开关全不可用）
        viewModelScope.launch {
            authManager.friendId.collectLatest { rawFriendId ->
                val friendId = rawFriendId?.trim()?.takeIf { it.isNotEmpty() }
                if (friendId == null) {
                    _aiProfileSettings.value = AiProfileSettingsState()
                } else {
                    loadAiProfileSettings(friendId)
                }
            }
        }
    }

    /** 「AI 锐评看单」数据上传开关（关闭后功能项保留，点击时引导回本页开启） */
    fun setAiTasteEnabled(enabled: Boolean) {
        viewModelScope.launch { aiTasteStorage.setTasteUploadEnabled(enabled) }
    }

    fun setCrashLogEnabled(enabled: Boolean) {
        viewModelScope.launch {
            crashLogStorage.setEnabled(enabled)
            // 开启且有待传日志：立即触发上传（成功/失败反馈由主界面 CrashReportDialogHost 统一处理，避免双提示）
            if (enabled && crashLogUploader.hasPendingLogs()) {
                crashLogUploader.uploadPendingLogs()
            }
        }
    }

    fun setAiProfileConsent(enabled: Boolean) {
        val current = _aiProfileSettings.value
        val friendId = requireAiFriendId() ?: return
        val request = AiProfileSettingsRequest(
            profileConsent = enabled,
            behaviorConsent = if (enabled) current.behaviorConsent else false,
            personalizationEnabled = enabled,
            syncEnabled = current.syncEnabled
        )
        updateAiProfileSettings(
            friendId = friendId,
            previous = current,
            request = request,
            successMessageRes = R.string.settings_ai_profile_saved,
            localCommit = { id, settings ->
                if (settings.profileConsent) {
                    aiProfileRepository.grantProfileConsent(
                        friendId = id,
                        behaviorConsent = settings.behaviorConsent,
                        personalizationEnabled = settings.personalizationEnabled,
                        syncEnabled = settings.syncEnabled,
                        shouldAutoImport = false
                    )
                } else {
                    // 撤回授权等同清画像：本地镜像与行为计数一起作废
                    aiProfileRepository.clear(id)
                }
            }
        )
    }

    fun setAiPersonalizationEnabled(enabled: Boolean) {
        val current = _aiProfileSettings.value
        val friendId = requireAiFriendId() ?: return
        if (!current.profileConsent) return
        val request = AiProfileSettingsRequest(personalizationEnabled = enabled)
        updateAiProfileSettings(
            friendId = friendId,
            previous = current,
            request = request,
            successMessageRes = R.string.settings_ai_profile_saved,
            localCommit = { id, settings ->
                aiProfileRepository.grantProfileConsent(
                    friendId = id,
                    behaviorConsent = settings.behaviorConsent,
                    personalizationEnabled = settings.personalizationEnabled,
                    syncEnabled = settings.syncEnabled,
                    shouldAutoImport = false
                )
            }
        )
    }

    fun setAiBehaviorConsent(enabled: Boolean) {
        val current = _aiProfileSettings.value
        val friendId = requireAiFriendId() ?: return
        val request = AiProfileSettingsRequest(behaviorConsent = enabled)
        updateAiProfileSettings(
            friendId = friendId,
            previous = current,
            request = request,
            successMessageRes = R.string.settings_ai_profile_saved,
            localCommit = { id, settings ->
                aiProfileRepository.setBehaviorConsent(id, settings.behaviorConsent)
            }
        )
    }

    fun setAiSyncEnabled(enabled: Boolean) {
        val current = _aiProfileSettings.value
        val friendId = requireAiFriendId() ?: return
        val request = AiProfileSettingsRequest(syncEnabled = enabled)
        updateAiProfileSettings(
            friendId = friendId,
            previous = current,
            request = request,
            successMessageRes = R.string.settings_ai_profile_saved,
            localCommit = { id, settings ->
                if (settings.profileConsent) {
                    aiProfileRepository.grantProfileConsent(
                        friendId = id,
                        behaviorConsent = settings.behaviorConsent,
                        personalizationEnabled = settings.personalizationEnabled,
                        syncEnabled = settings.syncEnabled,
                        shouldAutoImport = false
                    )
                }
            }
        )
    }

    fun clearAiProfile() {
        val current = _aiProfileSettings.value
        val friendId = requireAiFriendId() ?: return
        updateAiProfileSettings(
            friendId = friendId,
            previous = current,
            request = AiProfileSettingsRequest(
                profileConsent = false,
                behaviorConsent = false,
                personalizationEnabled = false,
                syncEnabled = current.syncEnabled
            ),
            successMessageRes = R.string.settings_ai_profile_clear_success,
            localCommit = { id, _ -> aiProfileRepository.clear(id) }
        )
    }

    /** snackbar 已展示，清掉这条消息，避免返回本页时重放 */
    fun clearMessage() {
        _message.value = null
    }

    private suspend fun loadAiProfileSettings(friendId: String) {
        // 先用本地镜像撑住界面（避免开关闪一下默认值），再用云端结果覆盖
        val local = runCatching { aiProfileRepository.settings(friendId) }.getOrNull()
        _aiProfileSettings.value = local?.toUiState()?.copy(
            friendId = friendId,
            isLoading = true,
            isUpdating = false
        ) ?: AiProfileSettingsState(friendId = friendId, isLoading = true)

        aiRepository.getProfileSettings(friendId).fold(
            onSuccess = { settings ->
                _aiProfileSettings.value = settings.toUiState(friendId)
            },
            onFailure = {
                // 拉不到就停在本地镜像上：至少反映最后一次已知授权状态
                _aiProfileSettings.value = local?.toUiState()?.copy(
                    friendId = friendId,
                    isLoading = false,
                    isUpdating = false
                ) ?: AiProfileSettingsState(friendId = friendId)
            }
        )
    }

    private fun requireAiFriendId(): String? {
        val friendId = authManager.friendId.value?.trim()?.takeIf { it.isNotEmpty() }
        if (friendId == null) {
            _message.value = PrivacyMessage(
                resId = R.string.settings_ai_profile_unavailable,
                outcome = HapticOutcome.FAILURE
            )
        }
        return friendId
    }

    private fun updateAiProfileSettings(
        friendId: String,
        previous: AiProfileSettingsState,
        request: AiProfileSettingsRequest,
        successMessageRes: Int,
        localCommit: suspend (String, AiProfileSettingsDto) -> Unit
    ) {
        if (previous.isUpdating) return
        _aiProfileSettings.value = previous.copy(isUpdating = true)
        viewModelScope.launch {
            aiRepository.updateProfileSettings(friendId, request).fold(
                onSuccess = { settings ->
                    try {
                        localCommit(friendId, settings)
                        _aiProfileSettings.value = settings.toUiState(friendId)
                        _message.value = PrivacyMessage(
                            resId = successMessageRes,
                            outcome = HapticOutcome.SUCCESS
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // 云端已保存时保留云端结果，避免本地失败造成 UI 显示与授权状态相反。
                        _aiProfileSettings.value = settings.toUiState(friendId)
                        _message.value = PrivacyMessage(
                            resId = R.string.settings_ai_profile_update_failed,
                            outcome = HapticOutcome.FAILURE
                        )
                    }
                },
                onFailure = {
                    // 网络失败时不写本地状态，StateFlow 仍保持切换前的值，形成失败回滚。
                    _aiProfileSettings.value = previous.copy(isUpdating = false)
                    _message.value = PrivacyMessage(
                        resId = R.string.settings_ai_profile_update_failed,
                        outcome = HapticOutcome.FAILURE
                    )
                }
            )
        }
    }

    private fun AiProfileSettings.toUiState(): AiProfileSettingsState = AiProfileSettingsState(
        friendId = friendId,
        profileConsent = profileConsent,
        behaviorConsent = behaviorConsent,
        personalizationEnabled = personalizationEnabled,
        syncEnabled = syncEnabled
    )

    private fun AiProfileSettingsDto.toUiState(friendId: String): AiProfileSettingsState = AiProfileSettingsState(
        friendId = friendId,
        profileConsent = profileConsent,
        behaviorConsent = behaviorConsent,
        personalizationEnabled = personalizationEnabled,
        syncEnabled = syncEnabled
    )
}
