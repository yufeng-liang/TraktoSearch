package com.tracktosearch.ui.screen.auth

import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tracktosearch.BuildConfig
import com.tracktosearch.data.auth.AuthCheckScheduler
import com.tracktosearch.data.auth.AuthManager
import com.tracktosearch.data.auth.hasGatewayAccess
import com.tracktosearch.data.local.TicketStub
import com.tracktosearch.data.local.TicketStubStorage
import com.tracktosearch.data.local.deriveTicketSeat
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

data class AuthUiState(
    val inviteCode: String = "",
    val isLoading: Boolean = false,
    val error: String? = null,
    val activated: Boolean = false,
    val requiresMigrationInvite: Boolean = false,
    val ticket: TicketStub? = null
)

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val authManager: AuthManager,
    private val authCheckScheduler: AuthCheckScheduler,
    private val ticketStubStorage: TicketStubStorage
) : ViewModel() {
    companion object {
        // 取票码固定 6 位纯数字（服务端自 77074cad 起只签发这种格式）。
        // 取票机是自绘数字键盘，只会喂进数字；粘贴路径的内容不可信，
        // 所以过滤和截断都放在这里，不依赖调用方。
        private const val TICKET_CODE_LENGTH = 6

        // 提到常量：粘贴是高频交互，没必要每次调用都重新编译一遍正则
        private val TICKET_CODE_PATTERN = Regex("""\d{6}""")
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
        viewModelScope.launch {
            // 回访时票直接停在出票口，不等 check 回来、也不重播打印动画。
            // 只在非空时写：DataStore 首次发射可能早于 issueTicketStub 落盘完成，
            // 无条件覆盖会把刚印好的票根冲成 null。
            ticketStubStorage.stub.collectLatest { stub ->
                if (stub != null) _uiState.update { it.copy(ticket = stub) }
            }
        }
    }

    fun updateInviteCode(value: String) {
        val sanitized = value.filter { it.isDigit() }.take(TICKET_CODE_LENGTH)
        _uiState.value = _uiState.value.copy(inviteCode = sanitized, error = null)
    }

    /**
     * 从剪贴板文本里抽 6 位连续数字（剪贴板里通常是「你的取票码是 492013」这类整句）。
     * 抽到返回 true 并填入，抽不到返回 false 且不动已输入的内容——
     * 用户可能已经手输了几位，一次失败的粘贴不该把它清掉。
     */
    fun pasteTicketCode(text: String): Boolean {
        val match = TICKET_CODE_PATTERN.find(text) ?: return false
        _uiState.value = _uiState.value.copy(inviteCode = match.value, error = null)
        return true
    }

    /**
     * 只清空已输入的取票码，保留 error。
     *
     * 取票失败时六格要抖两下再清空，但错误文案得留在屏上——
     * 用 updateInviteCode("") 清会连带把 error 置 null，用户还没看清就没了。
     */
    fun clearTicketCode() {
        _uiState.value = _uiState.value.copy(inviteCode = "")
    }

    fun activate() {
        if (_uiState.value.isLoading || _uiState.value.activated) return
        val inviteCode = _uiState.value.inviteCode.trim()
        // UI 会禁用不足 6 位时的取票键，这道校验兜住数字键盘之外的调用路径。
        if (inviteCode.length != TICKET_CODE_LENGTH) {
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
                handleActivationFailure(reason, inviteCode)
                return@launch
            }
            val nextCheckAt = authManager.getNextCheckAt()
            viewModelScope.launch(Dispatchers.IO) {
                runCatching { authCheckScheduler.schedulePreflight(nextCheckAt) }
            }
            // 昵称优先取激活响应：check 还没跑，AuthManager 里可能还是空的。
            val nickname = result.getOrNull()?.nickname?.takeIf { it.isNotBlank() }
                ?: authManager.nickname.value.orEmpty()
            issueTicketStub(inviteCode, nickname)
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                activated = true,
                requiresMigrationInvite = false
            )
        }
    }

    private suspend fun handleActivationFailure(reason: String, code: String) {
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
                // 恢复走的是 recover 端点，响应不带昵称，只能取 AuthManager 里已有的。
                issueTicketStub(code, authManager.nickname.value.orEmpty())
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

    /** 出票：票面内容在取票成功这一刻定格，之后不再随昵称/日期等外部状态变化。 */
    private suspend fun issueTicketStub(code: String, nickname: String) {
        val seat = deriveTicketSeat(code)
        val stub = TicketStub(
            nickname = nickname,
            // 存取票当天而不是每次读系统当天：票印出来之后日期就不该再变。
            issuedEpochDay = LocalDate.now().toEpochDay(),
            hall = seat.hall,
            row = seat.row,
            seat = seat.seat,
        )
        // 落盘失败不该把一次成功的取票变成失败：内存票根照样发给 UI。
        runCatching { ticketStubStorage.save(stub) }
        _uiState.update { it.copy(ticket = stub) }
    }
}
