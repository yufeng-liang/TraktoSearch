package com.tracktosearch.ui.screen.auth

import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tracktosearch.BuildConfig
import com.tracktosearch.data.auth.AuthCheckScheduler
import com.tracktosearch.data.auth.AuthManager
import com.tracktosearch.data.auth.hasGatewayAccess
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AuthUiState(
    val inviteCode: String = "",
    val isLoading: Boolean = false,
    val error: String? = null,
    val activated: Boolean = false,
    val requiresMigrationInvite: Boolean = false
)

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val authManager: AuthManager,
    private val authCheckScheduler: AuthCheckScheduler
) : ViewModel() {
    companion object {
        // 激活码长度无服务端约定可查（实测样例 13 位，如 TS-1234567890）：
        // 取 32 位保守上限防误粘贴整段文本，空白字符直接过滤
        private const val INVITE_CODE_MAX_LENGTH = 32
    }

    private val _uiState = MutableStateFlow(
        AuthUiState(activated = authManager.authState.value.hasGatewayAccess())
    )
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            // 订阅网关授权态：EXPIRED 等状态在登录页停留期间经静默恢复/重新激活转回
            // 已激活态时解锁平台/访客入口；授权失效时也要立即重新锁定，避免页面保留旧 true，
            // 防止用户从失效会话继续以“访客”绕过激活边界。
            authManager.authState.collectLatest { state ->
                val activated = state.hasGatewayAccess()
                _uiState.update { current ->
                    current.copy(
                        activated = activated,
                        error = if (activated) null else current.error,
                        requiresMigrationInvite = if (activated) false else current.requiresMigrationInvite
                    )
                }
            }
        }
        viewModelScope.launch {
            authManager.recoveryFailure.collectLatest { failure ->
                if (failure != null && !authManager.authState.value.hasGatewayAccess()) {
                    _uiState.value = _uiState.value.copy(requiresMigrationInvite = true)
                }
            }
        }
    }

    fun updateInviteCode(value: String) {
        // 过滤空白字符并截断到保守上限，防误粘贴整段文本或超长输入
        val sanitized = value.filter { !it.isWhitespace() }.take(INVITE_CODE_MAX_LENGTH)
        _uiState.value = _uiState.value.copy(inviteCode = sanitized, error = null)
    }

    fun activate() {
        if (_uiState.value.isLoading || _uiState.value.activated) return
        val inviteCode = _uiState.value.inviteCode.trim()
        if (inviteCode.isEmpty()) {
            _uiState.value = _uiState.value.copy(error = "INVALID_INVITE")
            return
        }
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            val result = authManager.activate(
                inviteCode = inviteCode,
                deviceName = "${Build.MANUFACTURER} ${Build.MODEL}",
                appVersion = BuildConfig.VERSION_NAME,
                packageName = BuildConfig.APPLICATION_ID
            )
            if (!result.isSuccess) {
                val reason = result.exceptionOrNull()?.message ?: "ACTIVATION_FAILED"
                handleActivationFailure(reason)
                return@launch
            }
            val nextCheckAt = authManager.getNextCheckAt()
            viewModelScope.launch(Dispatchers.IO) {
                runCatching { authCheckScheduler.schedulePreflight(nextCheckAt) }
            }
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                activated = true,
                requiresMigrationInvite = false
            )
        }
    }

    private suspend fun handleActivationFailure(reason: String) {
        if (reason.contains("INVITE_ALREADY_USED") || reason.contains("DEVICE_ALREADY_BOUND")) {
            // 邀请码已使用/设备已绑定多发生在「激活中断后重输同码」：本机可能已持有会话，
            // 先按 androidId+公钥静默恢复；恢复成功直接视为已激活，失败再引导用户换码。
            // emitFailure=false：抑制迁移邀请码提示副作用，此路径已有专属错误文案
            val recovered = authManager.recoverSilently(emitFailure = false).isSuccess
            if (recovered) {
                val nextCheckAt = authManager.getNextCheckAt()
                viewModelScope.launch(Dispatchers.IO) {
                    runCatching { authCheckScheduler.schedulePreflight(nextCheckAt) }
                }
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    activated = true,
                    requiresMigrationInvite = false
                )
            } else {
                _uiState.value = _uiState.value.copy(isLoading = false, error = "INVITE_BOUND")
            }
            return
        }
        _uiState.value = _uiState.value.copy(isLoading = false, error = reason)
    }
}
