package com.tracktosearch.ui.screen.login

import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.tracktosearch.OAuthCallback
import com.tracktosearch.R
import com.tracktosearch.data.remote.trakt.TraktAuthManager
import com.tracktosearch.data.repository.TraktRepository
import com.tracktosearch.ui.haptic.rememberAppHaptics
import com.tracktosearch.ui.util.toUserMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class LoginState {
    IDLE,           // 初始状态，显示登录按钮
    AUTHORIZING,    // 正在跳转授权页
    CONNECTING,     // 授权回调中，正在连接 Trakt
    SUCCESS,        // 登录成功
    ERROR           // 登录失败
}

/** 授权取消守卫的宽限时长：覆盖「deep link 先于 ON_RESUME 到达、回调收集器推进状态」的正常时序 */
private const val TRAKT_AUTH_CANCEL_GRACE_MS = 1_500L

/**
 * Trakt 浏览器授权取消守卫。
 *
 * 用户在 CustomTabs 授权页按返回取消时不会产生任何回调，loginState 会永久停留在
 * AUTHORIZING 锁死全部按钮。守卫监听生命周期：打开浏览器会使宿主 Activity ON_STOP，
 * 返回前台（ON_RESUME）后经过短暂宽限仍未收到授权回调则重置为 IDLE。
 *
 * 宽限期用于规避 ON_RESUME 与成功回调的竞态：deep link 在 onNewIntent（先于 ON_RESUME）
 * 写入 OAuthCallback，回到前台后回调收集器会在宽限窗口内把状态推进到 CONNECTING，
 * 此时守卫不再重置，正常授权流程不受影响。
 */
@Composable
internal fun TraktAuthCancelGuard(
    loginState: LoginState,
    onCanceled: () -> Unit
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentLoginState by rememberUpdatedState(loginState)
    val currentOnCanceled by rememberUpdatedState(onCanceled)
    // 曾在 AUTHORIZING 期间离开前台：区分「从浏览器返回」与通知栏下拉等不离开前台的 ON_RESUME 抖动
    var leftForegroundWhileAuthorizing by remember { mutableStateOf(false) }
    var resumeEpoch by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP ->
                    if (currentLoginState == LoginState.AUTHORIZING) leftForegroundWhileAuthorizing = true
                Lifecycle.Event.ON_RESUME ->
                    if (leftForegroundWhileAuthorizing) resumeEpoch++
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(resumeEpoch) {
        if (resumeEpoch == 0) return@LaunchedEffect
        delay(TRAKT_AUTH_CANCEL_GRACE_MS)
        // 宽限结束后仍是 AUTHORIZING 才算取消：期间收到回调会先推进到 CONNECTING/SUCCESS
        if (currentLoginState == LoginState.AUTHORIZING) {
            leftForegroundWhileAuthorizing = false
            currentOnCanceled()
        }
    }
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
    val haptics = rememberAppHaptics()

    // 浏览器授权取消守卫：CustomTabs 按返回取消无回调，宽限后仍在 AUTHORIZING 则重置 IDLE
    TraktAuthCancelGuard(
        loginState = loginState,
        onCanceled = { viewModel.reset() }
    )

    // 「从豆瓣导入」前置预检:未登录 Trakt 时弹引导对话框
    var showDoubanImportRequireLoginDialog by remember { mutableStateOf(false) }
    // 标记 OAuth 成功后是否自动跳转豆瓣登录页(用户在引导对话框中确认走 OAuth 流程)
    var pendingDoubanImportAfterLogin by remember { mutableStateOf(false) }
    // 「什么是 Trakt」说明弹窗
    var showWhatIsTraktDialog by remember { mutableStateOf(false) }

    // 如果 redirectToBrowser 为 true，直接进入浏览器授权
    LaunchedEffect(redirectToBrowser) {
        if (redirectToBrowser && loginState == LoginState.IDLE) {
            viewModel.startAuthorization()
            viewModel.getAuthorizationUrl()?.let { authUrl ->
                val customTabsIntent = CustomTabsIntent.Builder().build()
                customTabsIntent.launchUrl(context, Uri.parse(authUrl))
            }
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

    // 登录成功时通知外部（用 LaunchedEffect 包裹，避免在组合体内直接调副作用导致重复触发）
    // 如果是「从豆瓣导入」引导的 OAuth 流程,成功后自动跳豆瓣登录页
    LaunchedEffect(loginState) {
        if (loginState == LoginState.SUCCESS) {
            // 触发后立即 reset，避免 SUCCESS 状态残留导致重复触发导航
            viewModel.reset()
            if (pendingDoubanImportAfterLogin) {
                pendingDoubanImportAfterLogin = false
                onDoubanImport()
            } else {
                onLoginSuccess()
            }
        }
    }

    Surface(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding(),
        color = MaterialTheme.colorScheme.background
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // 右上角「什么是 Trakt」入口
            TextButton(
                onClick = {
                    haptics.lightTap()
                    showWhatIsTraktDialog = true
                },
                modifier = Modifier.align(Alignment.TopEnd)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.login_what_is_trakt),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.size(4.dp))
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.HelpOutline,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
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
                text = "TraktoSearch",
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
                        // OAuth 授权走 CustomTabs 但拿着 code 通过 deep link 回到本应用，不算外跳；
                        // 这是本屏的主 CTA，给 tap。触感在 onClick 首行发，不等协程
                        onClick = {
                            haptics.tap()
                            scope.launch {
                                viewModel.startAuthorization()
                                viewModel.getAuthorizationUrl()?.let { authUrl ->
                                    val customTabsIntent = CustomTabsIntent.Builder().build()
                                    customTabsIntent.launchUrl(context, Uri.parse(authUrl))
                                }
                            }
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
                    if (loginState == LoginState.AUTHORIZING) {
                        // CustomTabs 取消授权不产生回调：显示等待提示与取消逃生，避免永久锁死
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = stringResource(R.string.douban_login_waiting_auth),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        TextButton(onClick = {
                            haptics.lightTap()
                            viewModel.reset()
                        }) {
                            Text(stringResource(R.string.common_cancel))
                        }
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
                        // VM 存原始异常,组合期转本地化文案
                        val loginDeniedText = stringResource(R.string.login_denied)
                        Text(
                            text = errorMessage?.toUserMessage(context, R.string.login_failed) ?: loginDeniedText,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            // 只把状态复位回 IDLE，不外跳，按「重试」给 tap
                            onClick = {
                                haptics.tap()
                                viewModel.reset()
                            },
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
                        // 换一条登录路径的次级入口，不外跳（跳的是应用内豆瓣登录页）
                        haptics.lightTap()
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
                    onClick = {
                        haptics.lightTap()
                        onGuestMode()
                    },
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

    // 「从豆瓣导入」需要先登录 Trakt 的引导对话框
    if (showDoubanImportRequireLoginDialog) {
        AlertDialog(
            onDismissRequest = { showDoubanImportRequireLoginDialog = false },
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            title = { Text(stringResource(R.string.douban_import_require_trakt_title)) },
            text = { Text(stringResource(R.string.douban_import_require_trakt_desc)) },
            confirmButton = {
                // OAuth 走 CustomTabs 但回得来（拿 code 经 deep link 返回），不算外跳；
                // 槽是独立 subcomposition（自己的宿主 View），单独取一份
                val confirmHaptics = rememberAppHaptics()
                TextButton(onClick = {
                    confirmHaptics.tap()
                    showDoubanImportRequireLoginDialog = false
                    // 标记 OAuth 成功后自动跳豆瓣登录页
                    pendingDoubanImportAfterLogin = true
                    // 启动 Trakt OAuth 流程
                    scope.launch {
                        viewModel.startAuthorization()
                        viewModel.getAuthorizationUrl()?.let { authUrl ->
                            val customTabsIntent = CustomTabsIntent.Builder().build()
                            customTabsIntent.launchUrl(context, Uri.parse(authUrl))
                        }
                    }
                }) {
                    Text(stringResource(R.string.douban_import_require_trakt_login))
                }
            },
            dismissButton = {
                // 槽是独立 subcomposition（自己的宿主 View），单独取一份
                val dismissHaptics = rememberAppHaptics()
                TextButton(onClick = {
                    dismissHaptics.lightTap()
                    showDoubanImportRequireLoginDialog = false
                }) {
                    Text(stringResource(R.string.douban_import_require_trakt_cancel))
                }
            }
        )
    }

    // 「什么是 Trakt」说明弹窗
    if (showWhatIsTraktDialog) {
        AlertDialog(
            onDismissRequest = { showWhatIsTraktDialog = false },
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.HelpOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(stringResource(R.string.login_what_is_trakt_title))
                }
            },
            text = {
                Text(
                    text = stringResource(R.string.login_what_is_trakt_desc),
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                // 不发触感：CustomTabs 只是打开 trakt.tv 注册页去看，回不回来不确定，属真的离开本应用。
                // 与上面登录按钮的区别在「回不回来」，不是「有没有用 CustomTabs」
                Button(onClick = {
                    showWhatIsTraktDialog = false
                    val registerUrl = "https://trakt.tv/auth/join"
                    val customTabsIntent = CustomTabsIntent.Builder().build()
                    customTabsIntent.launchUrl(context, Uri.parse(registerUrl))
                }) {
                    Text(stringResource(R.string.login_what_is_trakt_register))
                }
            },
            dismissButton = {
                val dismissHaptics = rememberAppHaptics()
                TextButton(onClick = {
                    dismissHaptics.lightTap()
                    showWhatIsTraktDialog = false
                }) {
                    Text(stringResource(R.string.login_what_is_trakt_close))
                }
            }
        )
    }
}
