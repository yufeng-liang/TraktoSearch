package com.tracktosearch.ui.screen.privacy

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tracktosearch.R
import com.tracktosearch.data.ai.AiProfileRepository
import com.tracktosearch.data.ai.AiProfileSettings
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
 * [isAvailable] 是「有没有可归属的 friendId」：画像与行为计数都按 friendId
 * 落在本地数据库，未登录时整组开关只读。BYOK 后不再上传服务端，无同步开关。
 */
@Immutable
data class AiProfileSettingsState(
    val friendId: String? = null,
    val profileConsent: Boolean = false,
    val behaviorConsent: Boolean = false,
    val personalizationEnabled: Boolean = false,
    val isLoaded: Boolean = false,
    val isUpdating: Boolean = false
) {
    val isAvailable: Boolean get() = !friendId.isNullOrBlank()
}

/**
 * 「数据与隐私」页 ViewModel。
 *
 * 独立于 SettingsViewModel 自建（设置页原「AI 与隐私」分组与崩溃日志开关行
 * 迁入本页）：
 * - AI taste / 崩溃日志开关：存储层是单例内存镜像，进入页面直接读当前值
 * - AI 画像开关组：按 friendId 隔离；BYOK 后画像只存本地，写入失败时回滚界面状态
 */
@HiltViewModel
class PrivacyViewModel @Inject constructor(
    private val aiTasteStorage: AiTasteStorage,
    private val crashLogStorage: CrashLogStorage,
    private val crashLogUploader: CrashLogUploader,
    private val aiProfileRepository: AiProfileRepository,
    private val authManager: AuthManager
) : ViewModel() {

    /** 「AI 锐评看单」数据上传开关（单例内存镜像，进入页面即有上次保存值） */
    val aiTasteEnabled: StateFlow<Boolean> = aiTasteStorage.tasteUploadEnabled

    /** 崩溃日志上报开关（单例内存镜像，进入页面即有上次保存值） */
    val crashLogEnabled: StateFlow<Boolean> = crashLogStorage.enabled

    private val _message = MutableStateFlow<PrivacyMessage?>(null)
    val message: StateFlow<PrivacyMessage?> = _message.asStateFlow()

    private val _aiProfileSettings = MutableStateFlow(AiProfileSettingsState())
    val aiProfileSettings: StateFlow<AiProfileSettingsState> = _aiProfileSettings.asStateFlow()

    init {
        // 画像按 friendId 隔离：换账号就整组重载，登出则清回默认（开关全不可用）
        viewModelScope.launch {
            authManager.friendId.collectLatest { rawFriendId ->
                val friendId = rawFriendId?.trim()?.takeIf { it.isNotEmpty() }
                if (friendId == null) {
                    _aiProfileSettings.value = AiProfileSettingsState(isLoaded = true)
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
        val friendId = requireAiFriendId() ?: return
        updateAiProfileSettings(
            friendId = friendId,
            successMessageRes = R.string.settings_ai_profile_saved,
            commit = {
                if (enabled) {
                    aiProfileRepository.grantProfileConsent(
                        friendId = friendId,
                        shouldAutoImport = false
                    )
                } else {
                    // 撤回授权等同清画像：本地镜像与行为计数一起作废
                    aiProfileRepository.clear(friendId)
                }
            }
        )
    }

    fun setAiPersonalizationEnabled(enabled: Boolean) {
        val current = _aiProfileSettings.value
        val friendId = requireAiFriendId() ?: return
        if (!current.profileConsent) return
        updateAiProfileSettings(
            friendId = friendId,
            successMessageRes = R.string.settings_ai_profile_saved,
            commit = {
                aiProfileRepository.grantProfileConsent(
                    friendId = friendId,
                    behaviorConsent = current.behaviorConsent,
                    personalizationEnabled = enabled,
                    shouldAutoImport = false
                )
            }
        )
    }

    fun setAiBehaviorConsent(enabled: Boolean) {
        val friendId = requireAiFriendId() ?: return
        updateAiProfileSettings(
            friendId = friendId,
            successMessageRes = R.string.settings_ai_profile_saved,
            commit = { aiProfileRepository.setBehaviorConsent(friendId, enabled) }
        )
    }

    fun clearAiProfile() {
        val friendId = requireAiFriendId() ?: return
        updateAiProfileSettings(
            friendId = friendId,
            successMessageRes = R.string.settings_ai_profile_clear_success,
            commit = { aiProfileRepository.clear(friendId) }
        )
    }

    /** snackbar 已展示，清掉这条消息，避免返回本页时重放 */
    fun clearMessage() {
        _message.value = null
    }

    private suspend fun loadAiProfileSettings(friendId: String) {
        // BYOK：画像设置只存本地，直接读本地镜像，不再回源服务端
        val local = runCatching { aiProfileRepository.settings(friendId) }.getOrNull()
        _aiProfileSettings.value = local?.toUiState()?.copy(
            friendId = friendId
        ) ?: AiProfileSettingsState(
            friendId = friendId,
            isLoaded = true
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

    /**
     * 画像开关统一落盘管线（BYOK 后只有本地）：
     * 先置 isUpdating 防抖，commit 成功→重新读本地设置刷新 UI+成功提示；
     * commit 失败→保持切换前的值+失败提示。
     */
    private fun updateAiProfileSettings(
        friendId: String,
        successMessageRes: Int,
        commit: suspend () -> Unit
    ) {
        val previous = _aiProfileSettings.value
        if (previous.isUpdating) return
        _aiProfileSettings.value = previous.copy(isUpdating = true)
        viewModelScope.launch {
            try {
                commit()
                val settings = runCatching { aiProfileRepository.settings(friendId) }.getOrNull()
                _aiProfileSettings.value = settings?.toUiState()?.copy(friendId = friendId)
                    ?: previous.copy(isUpdating = false)
                _message.value = PrivacyMessage(
                    resId = successMessageRes,
                    outcome = HapticOutcome.SUCCESS
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _aiProfileSettings.value = previous.copy(isUpdating = false)
                _message.value = PrivacyMessage(
                    resId = R.string.settings_ai_profile_update_failed,
                    outcome = HapticOutcome.FAILURE
                )
            }
        }
    }

    private fun AiProfileSettings.toUiState(): AiProfileSettingsState = AiProfileSettingsState(
        friendId = friendId,
        profileConsent = profileConsent,
        behaviorConsent = behaviorConsent,
        personalizationEnabled = personalizationEnabled,
        isLoaded = true
    )
}
