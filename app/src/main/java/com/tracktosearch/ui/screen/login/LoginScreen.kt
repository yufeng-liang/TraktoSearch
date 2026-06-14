package com.tracktosearch.ui.screen.login

import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.tracktosearch.R
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tracktosearch.OAuthCallback
import com.tracktosearch.data.remote.trakt.TraktAuthManager
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
    val authManager: TraktAuthManager
) : ViewModel() {

    private val _loginState = MutableStateFlow(LoginState.IDLE)
    val loginState: StateFlow<LoginState> = _loginState.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    fun startAuthorization() {
        _loginState.value = LoginState.AUTHORIZING
    }

    fun exchangeCodeForToken(code: String) {
        _loginState.value = LoginState.CONNECTING
        viewModelScope.launch {
            val result = authManager.exchangeCodeForToken(code)
            if (result.isSuccess) {
                _loginState.value = LoginState.SUCCESS
            } else {
                _loginState.value = LoginState.ERROR
                _errorMessage.value = result.exceptionOrNull()?.message ?: "登录失败"
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
}

@Composable
fun LoginScreen(
    onLoginSuccess: () -> Unit = {},
    onGuestMode: () -> Unit = {},
    redirectToBrowser: Boolean = false,
    modifier: Modifier = Modifier,
    viewModel: LoginViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val loginState by viewModel.loginState.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()

    // 如果 redirectToBrowser 为 true，直接进入浏览器授权
    LaunchedEffect(redirectToBrowser) {
        if (redirectToBrowser && loginState == LoginState.IDLE) {
            viewModel.startAuthorization()
            val authUrl = viewModel.authManager.buildAuthorizationUrl()
            val customTabsIntent = CustomTabsIntent.Builder().build()
            customTabsIntent.launchUrl(context, Uri.parse(authUrl))
        }
    }

    // 监听 OAuth 回调 code 或拒绝授权
    LaunchedEffect(Unit) {
        while (true) {
            val code = OAuthCallback.pendingCode
            if (code != null) {
                OAuthCallback.pendingCode = null
                viewModel.exchangeCodeForToken(code)
            }
            if (OAuthCallback.authDenied) {
                OAuthCallback.authDenied = false
                viewModel.onAuthDenied()
            }
            kotlinx.coroutines.delay(300)
        }
    }

    // 登录成功时通知外部
    if (loginState == LoginState.SUCCESS) {
        onLoginSuccess()
    }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Default.Movie,
                contentDescription = null,
                modifier = Modifier.size(80.dp),
                tint = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "TraktToSearch",
                style = MaterialTheme.typography.displayLarge,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = stringResource(R.string.login_subtitle),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(48.dp))

            when (loginState) {
                LoginState.IDLE, LoginState.AUTHORIZING -> {
                    Button(
                        onClick = {
                            viewModel.startAuthorization()
                            val authUrl = viewModel.authManager.buildAuthorizationUrl()
                            val customTabsIntent = CustomTabsIntent.Builder().build()
                            customTabsIntent.launchUrl(context, Uri.parse(authUrl))
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        ),
                        enabled = loginState != LoginState.AUTHORIZING
                    ) {
                        Text(
                            text = stringResource(R.string.login_button),
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                }

                LoginState.CONNECTING -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(48.dp),
                            strokeWidth = 4.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = stringResource(R.string.login_connecting),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.login_connecting_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }

                LoginState.ERROR -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = errorMessage ?: stringResource(R.string.login_denied),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = { viewModel.reset() },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp),
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary
                            )
                        ) {
                            Text(
                                text = stringResource(R.string.login_retry),
                                style = MaterialTheme.typography.titleMedium
                            )
                        }
                    }
                }

                LoginState.SUCCESS -> {
                    // 会被 onLoginSuccess 回调处理，不需要额外 UI
                    CircularProgressIndicator(
                        modifier = Modifier.size(48.dp),
                        strokeWidth = 4.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.login_success),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (loginState == LoginState.IDLE || loginState == LoginState.AUTHORIZING) {
                Text(
                    text = stringResource(R.string.login_sync_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(16.dp))

                TextButton(
                    onClick = onGuestMode,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = stringResource(R.string.login_guest),
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }
        }
    }
}
