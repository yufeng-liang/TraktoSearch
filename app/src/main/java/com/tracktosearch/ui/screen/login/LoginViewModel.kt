package com.tracktosearch.ui.screen.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tracktosearch.data.remote.trakt.TraktAuthManager
import com.tracktosearch.data.repository.TraktRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class LoginState {
    IDLE,           // 初始状态，显示登录按钮
    AUTHORIZING,    // 正在跳转授权页
    CONNECTING,     // 授权回调中，正在连接 Trakt
    SUCCESS,        // 登录成功
    ERROR           // 登录失败
}

@HiltViewModel
class LoginViewModel @Inject constructor(
    val authManager: TraktAuthManager,
    private val traktRepository: TraktRepository
) : ViewModel() {

    private val _loginState = MutableStateFlow(LoginState.IDLE)
    val loginState: StateFlow<LoginState> = _loginState.asStateFlow()

    private val _errorMessage = MutableStateFlow<Throwable?>(null)
    /** 登录失败原始异常(VM 不做本地化,UI 组合期用 toUserMessage 转文案;null 表示未产生异常) */
    val errorMessage: StateFlow<Throwable?> = _errorMessage.asStateFlow()

    /**
     * 检查 Trakt 是否已登录且 token 有效。
     * 用于「从豆瓣导入」按钮前置校验:未登录 Trakt 时引导用户先登录。
     */
    suspend fun isTraktLoggedIn(): Boolean {
        return traktRepository.checkTraktConnection()
    }

    fun startAuthorization() {
        _loginState.value = LoginState.AUTHORIZING
    }

    suspend fun getAuthorizationUrl(): String? {
        val result = authManager.buildAuthorizationUrl()
        return result.getOrElse {
            _loginState.value = LoginState.ERROR
            _errorMessage.value = it
            null
        }
    }

    fun exchangeCodeForToken(code: String) {
        _loginState.value = LoginState.CONNECTING
        viewModelScope.launch {
            val result = authManager.exchangeCodeForToken(code)
            if (result.isSuccess) {
                // OAuth 可能切换到另一个 Trakt 账号，避免 Watchlist 和资料沿用旧账号缓存。
                traktRepository.clearTraktAccountCaches()
                _loginState.value = LoginState.SUCCESS
            } else {
                _loginState.value = LoginState.ERROR
                _errorMessage.value = result.exceptionOrNull()
            }
        }
    }

    fun reset() {
        _loginState.value = LoginState.IDLE
        _errorMessage.value = null
    }

    fun onAuthDenied() {
        _loginState.value = LoginState.ERROR
        _errorMessage.value = null  // 使用默认的拒绝授权提示
    }

    /**
     * 浏览器压根没起来（设备上没有可用浏览器、被安全软件拦下）。
     *
     * 与 [onAuthDenied] 分开：那一条是用户拒绝，这一条是根本没走到授权页，
     * 得带上原始异常，界面上才能给出「登录失败」而不是「授权被拒绝」。
     */
    fun onAuthLaunchFailed(error: Throwable) {
        _loginState.value = LoginState.ERROR
        _errorMessage.value = error
    }
}