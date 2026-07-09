package com.tracktosearch.ui.screen.login

import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.tracktosearch.OAuthCallback
import com.tracktosearch.R
import com.tracktosearch.data.local.TokenStorage
import com.tracktosearch.data.remote.trakt.TraktAuthManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
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
    private val tokenStorage: TokenStorage
) : ViewModel() {

    private val _loginState = MutableStateFlow(LoginState.IDLE)
    val loginState: StateFlow<LoginState> = _loginState.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    /**
     * 检查 Trakt 是否已登录且 token 有效。
     * 用于「从豆瓣导入」按钮前置校验:未登录 Trakt 时引导用户先登录。
     */
    suspend fun isTraktLoggedIn(): Boolean {
        val token = tokenStorage.getCachedAccessToken() ?: return false
        return tokenStorage.isTokenValid()
    }

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
                _errorMessage.value = result.exceptionOrNull()?.message ?: ""
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
    onDoubanImport: () -> Unit = {},
    redirectToBrowser: Boolean = false,
    modifier: Modifier = Modifier,
    viewModel: LoginViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val loginState by viewModel.loginState.collectAsStateWithLifecycle()
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    // 「从豆瓣导入」前置预检:未登录 Trakt 时弹引导对话框
    var showDoubanImportRequireLoginDialog by remember { mutableStateOf(false) }
    // 标记 OAuth 成功后是否自动跳转豆瓣登录页(用户在引导对话框中确认走 OAuth 流程)
    var pendingDoubanImportAfterLogin by remember { mutableStateOf(false) }

    // 如果 redirectToBrowser 为 true，直接进入浏览器授权
    LaunchedEffect(redirectToBrowser) {
        if (redirectToBrowser && loginState == LoginState.IDLE) {
            viewModel.startAuthorization()
            val authUrl = viewModel.authManager.buildAuthorizationUrl()
            val customTabsIntent = CustomTabsIntent.Builder().build()
            customTabsIntent.launchUrl(context, Uri.parse(authUrl))
        }
    }

    // 监听 OAuth 回调 code 或拒绝授权（用 StateFlow 替代轮询，延迟接近 0）
    LaunchedEffect(Unit) {
        kotlinx.coroutines.coroutineScope {
            launch {
                OAuthCallback.pendingCodeFlow
                    .filterNotNull()
                    .collect { code ->
                        OAuthCallback.setPendingCode(null)
                        viewModel.exchangeCodeForToken(code)
                    }
            }
            launch {
                OAuthCallback.authDeniedFlow
                    .filter { it }
                    .collect {
                        OAuthCallback.setAuthDenied(false)
                        viewModel.onAuthDenied()
                    }
            }
        }
    }

    // 登录成功时通知外部
    // 如果是「从豆瓣导入」引导的 OAuth 流程,成功后自动跳豆瓣登录页
    if (loginState == LoginState.SUCCESS) {
        if (pendingDoubanImportAfterLogin) {
            pendingDoubanImportAfterLogin = false
            onDoubanImport()
        } else {
            onLoginSuccess()
        }
    }

    Surface(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding(),
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
                imageVector = Icons.Rounded.Movie,
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
                        val loginFailedText = stringResource(R.string.login_failed)
                        val loginDeniedText = stringResource(R.string.login_denied)
                        Text(
                            text = errorMessage?.ifEmpty { loginFailedText } ?: loginDeniedText,
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
                    onClick = {
                        // 前置预检:已登录 Trakt → 直接跳豆瓣登录页
                        // 未登录 → 弹引导对话框,确认后启动 Trakt OAuth 流程
                        scope.launch {
                            if (viewModel.isTraktLoggedIn()) {
                                onDoubanImport()
                            } else {
                                showDoubanImportRequireLoginDialog = true
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = stringResource(R.string.login_douban_import),
                        style = MaterialTheme.typography.bodyLarge
                    )
                }

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

    // 「从豆瓣导入」需要先登录 Trakt 的引导对话框
    if (showDoubanImportRequireLoginDialog) {
        AlertDialog(
            onDismissRequest = { showDoubanImportRequireLoginDialog = false },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text(stringResource(R.string.douban_import_require_trakt_title)) },
            text = { Text(stringResource(R.string.douban_import_require_trakt_desc)) },
            confirmButton = {
                TextButton(onClick = {
                    showDoubanImportRequireLoginDialog = false
                    // 标记 OAuth 成功后自动跳豆瓣登录页
                    pendingDoubanImportAfterLogin = true
                    // 启动 Trakt OAuth 流程
                    viewModel.startAuthorization()
                    val authUrl = viewModel.authManager.buildAuthorizationUrl()
                    val customTabsIntent = CustomTabsIntent.Builder().build()
                    customTabsIntent.launchUrl(context, Uri.parse(authUrl))
                }) {
                    Text(stringResource(R.string.douban_import_require_trakt_login))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDoubanImportRequireLoginDialog = false }) {
                    Text(stringResource(R.string.douban_import_require_trakt_cancel))
                }
            }
        )
    }
}
