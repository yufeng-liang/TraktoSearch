@file:OptIn(dev.chrisbanes.haze.ExperimentalHazeApi::class)

package com.tracktosearch.ui.screen.login

import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.provider.Settings
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.HelpOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tracktosearch.OAuthCallback
import com.tracktosearch.R
import com.tracktosearch.ui.component.CinemaClapperIcon
import com.tracktosearch.ui.component.backdropSource
import com.tracktosearch.ui.component.glassSceneForContent
import com.tracktosearch.ui.component.isAppDarkTheme
import com.tracktosearch.ui.screen.auth.AuthViewModel
import com.tracktosearch.ui.util.HapticType
import com.tracktosearch.ui.util.performHaptic
import com.tracktosearch.ui.util.toUserMessage
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

// 激活页品牌图标固定使用浅色模式配色，避免跟随深色主题改变品牌外观。
private val ActivationClapperAccentColor = Color(0xFF9A6242)
private val ActivationClapperPaperColor = Color.White

@Composable
fun ActivationLoginScreen(
    onLoginSuccess: () -> Unit = {},
    onGuestMode: () -> Unit = {},
    /**
     * 豆瓣登录入口回调：跳转到 DoubanLoginScreen。
     * 登录成功后由 AppNavigation 直接进入 MainScreen（豆瓣独立模式）。
     */
    onDoubanLogin: () -> Unit = {},
    expired: Boolean = false,
    modifier: Modifier = Modifier,
    loginViewModel: LoginViewModel = hiltViewModel(),
    authViewModel: AuthViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val loginState by loginViewModel.loginState.collectAsStateWithLifecycle()
    val errorMessage by loginViewModel.errorMessage.collectAsStateWithLifecycle()
    val authState by authViewModel.uiState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val hazeState = remember { HazeState() }
    val scrollState = rememberScrollState()
    val isDarkTheme = isAppDarkTheme()
    val loginBackground = if (isDarkTheme) Color(0xFFD9CFC2) else Color(0xFFF7EFE2)
    val loginSecondaryTextColor = if (isDarkTheme) Color(0xFF624B3C) else MaterialTheme.colorScheme.onSurfaceVariant
    var showWhatIsTraktDialog by remember { mutableStateOf(false) }

    val isActivated = authState.activated
    val loginGlassScene = glassSceneForContent(
        // 取票机面板上的可数元素远多于原来那张卡片：12 个键 + 6 格 + 像素屏 + 取票键。
        contentCount = 20 + if (authState.requiresMigrationInvite) 2 else 0,
        readabilityDemand = when {
            authState.error != null || errorMessage != null -> 0.96f
            isActivated -> 0.82f
            else -> 0.88f
        },
        ambientColor = loginBackground,
        contentCapacity = 20
    )

    val view = LocalView.current
    val printProgress = remember { Animatable(0f) }
    var isPrinting by remember { mutableStateOf(false) }
    // 粘贴没抽到 6 位数字时的一次性提示。只占像素屏，不进 AuthUiState——
    // 它不是一次激活失败，下一次按键就该消失。
    var pasteMissed by remember { mutableStateOf(false) }
    var observedActivated by remember { mutableStateOf(isActivated) }

    LaunchedEffect(isActivated) {
        when {
            shouldPlayTicketPrint(observedActivated, isActivated) -> {
                if (animatorDurationScale(context) == 0f) {
                    // 系统关闭动画时直接进已取票态。这是无障碍要求，不是可选项。
                    printProgress.snapTo(1f)
                } else {
                    isPrinting = true
                    // finally 不能省：打印途中授权被吊销（isActivated 翻假）会让本
                    // LaunchedEffect 在 animateTo 处被取消，漏掉复位的话 isPrinting
                    // 会永久停在 true——像素屏一直显示「正在打印…」，键盘却是可用的。
                    try {
                        printProgress.snapTo(0f)
                        printProgress.animateTo(
                            targetValue = 1f,
                            animationSpec = tween(TICKET_PRINT_DURATION_MS, easing = LinearEasing)
                        )
                    } finally {
                        isPrinting = false
                    }
                }
            }
            // 冷启动或回访进入已激活态：票静态停在出票口，不重播推出。
            isActivated -> printProgress.snapTo(1f)
            else -> printProgress.snapTo(0f)
        }
        observedActivated = isActivated
    }

    LaunchedEffect(Unit) {
        // 走纸是 6 步阶跃，每跳一档给一次轻触觉，六次连击就是「咔咔咔」。
        // 挂 feedStep 而不是 revealFraction：探头那 120ms 的 revealFraction 是连续插值，
        // distinctUntilChanged 对它无效，60Hz 上会先糊出七八次连续震动。
        snapshotFlow { phaseAt(printProgress.value).feedStep }
            .distinctUntilChanged()
            .filter { it > 0 }
            .collect { if (isPrinting) view.performHaptic(HapticType.TICK) }
    }

    LaunchedEffect(authState.error) {
        if (authState.error == null) return@LaunchedEffect
        // 等六格抖完再清空：抖动过程中清空，用户看不出发生了什么。
        // 用 clearTicketCode 而不是 updateInviteCode("")，后者会顺手把 error 置 null，
        // 刚显示出来的错误文案会立刻消失。
        delay(TICKET_ERROR_CLEAR_DELAY_MS)
        authViewModel.clearTicketCode()
    }

    LaunchedEffect(authState.inviteCode) {
        if (authState.inviteCode.isNotEmpty()) pasteMissed = false
    }

    // authState 是委托属性，authState.error 上没有智能转换，先取一次到局部量
    val authError = authState.error
    // 已取票态六格显示固定的 ****** ：取票码本身不落盘，屏上这串不含信息。
    val machineCode = if (isActivated) COLLECTED_CODE_MASK else authState.inviteCode
    // 屏上那串占位符不能直接念给读屏——「取票码 * * * * * *，还需 0 位」不是实话。
    val machineCodeDescription = if (isActivated) {
        stringResource(R.string.machine_code_collected)
    } else {
        null
    }
    val machineStatus = when {
        authError != null -> stringResource(machineStatusString(authError))
        pasteMissed -> stringResource(R.string.machine_status_clipboard_empty)
        isPrinting -> stringResource(R.string.machine_status_printing)
        isActivated -> stringResource(R.string.machine_status_collected)
        else -> stringResource(R.string.machine_status_ready)
    }
    // 像素屏只放短状态，完整引导句子留在机器下方：屏宽装不下
    // 「请向管理员索取新的取票码」这类话，只靠屏幕报错会丢掉「下一步该干什么」。
    val machineHint = when {
        authError != null -> stringResource(authErrorString(authError))
        authState.requiresMigrationInvite -> stringResource(R.string.auth_migration_invite_hint)
        !isActivated -> stringResource(R.string.login_activation_locked)
        else -> null
    }

    fun launchAuthorization() {
        scope.launch {
            loginViewModel.startAuthorization()
            loginViewModel.getAuthorizationUrl()?.let { authUrl ->
                CustomTabsIntent.Builder().build().launchUrl(context, Uri.parse(authUrl))
            }
        }
    }

    // 浏览器授权取消守卫：CustomTabs 按返回取消无回调，宽限后仍在 AUTHORIZING 则重置 IDLE
    TraktAuthCancelGuard(
        loginState = loginState,
        onCanceled = { loginViewModel.reset() }
    )

    LaunchedEffect(Unit) {
        kotlinx.coroutines.coroutineScope {
            launch {
                OAuthCallback.pendingCodeFlow.filterNotNull().collect { code ->
                    OAuthCallback.setPendingCode(null)
                    loginViewModel.exchangeCodeForToken(code)
                }
            }
            launch {
                OAuthCallback.authDeniedFlow.filter { it }.collect {
                    OAuthCallback.setAuthDenied(false)
                    loginViewModel.onAuthDenied()
                }
            }
        }
    }

    LaunchedEffect(loginState) {
        if (loginState == LoginState.SUCCESS) {
            // 触发后立即 reset，避免 SUCCESS 状态残留导致极端情况下（如导航失败）重复触发
            loginViewModel.reset()
            onLoginSuccess()
        }
    }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = loginBackground
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
        ) {
            // 独立的背景 source 容器，确保自定义绘制内容完整进入 Haze 的采样层。
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .hazeSource(state = hazeState, zIndex = 0f)
                    .backdropSource()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(loginBackground)
                ) {
                    MovieBackdrop(modifier = Modifier.fillMaxSize())
                }
            }

            TextButton(
                onClick = { showWhatIsTraktDialog = true },
                enabled = true,
                colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                    contentColor = if (isDarkTheme) Color(0xFF6B4632) else MaterialTheme.colorScheme.primary
                ),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(end = 12.dp)
                    .zIndex(2f),
            ) {
                Text(stringResource(R.string.login_what_is_trakt))
                Spacer(modifier = Modifier.size(4.dp))
                Icon(Icons.Rounded.HelpOutline, contentDescription = null, modifier = Modifier.size(18.dp))
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(1f)
                    .verticalScroll(scrollState)
                    .imePadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 18.dp, vertical = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(modifier = Modifier.statusBarsPadding().height(53.dp))
                Box(modifier = Modifier.offset(y = 15.dp)) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CinemaClapperIcon(
                            accentColor = ActivationClapperAccentColor,
                            paperColor = ActivationClapperPaperColor
                        )
                        Spacer(modifier = Modifier.height(22.dp))
                        Text(
                            text = stringResource(R.string.login_title),
                            fontSize = 34.sp,
                            lineHeight = 41.sp,
                            letterSpacing = (-2.04f).sp,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Serif
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.login_subtitle),
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontSize = 14.sp,
                                lineHeight = 20.sp
                            ),
                            color = loginSecondaryTextColor,
                            textAlign = TextAlign.Center
                        )
                    }
                }

                Spacer(modifier = Modifier.height(90.dp))
                if (expired) {
                    Text(
                        text = stringResource(R.string.auth_expired_message),
                        fontSize = 16.sp,
                        lineHeight = 24.sp,
                        color = loginSecondaryTextColor,
                        textAlign = TextAlign.Center
                    )
                }
                TicketMachine(
                    code = machineCode,
                    statusText = machineStatus,
                    statusIsError = authState.error != null || pasteMissed,
                    hintText = machineHint,
                    hintIsError = authState.error != null || authState.requiresMigrationInvite,
                    isLoading = authState.isLoading,
                    keypadEnabled = !isActivated && !authState.isLoading,
                    submitEnabled = authState.inviteCode.length == TICKET_CODE_DIGITS &&
                        !authState.isLoading &&
                        !isActivated,
                    hazeState = hazeState,
                    scene = loginGlassScene,
                    onDigit = authViewModel::appendDigit,
                    onBackspace = authViewModel::deleteLastDigit,
                    onPaste = {
                        // Android 12 起每次读剪贴板都会弹系统提示，所以只在用户按下粘贴键时读，
                        // 绝不在进入页面时自动读。
                        pasteMissed = !authViewModel.pasteTicketCode(readClipboardText(context))
                    },
                    onSubmit = { authViewModel.activate() },
                    modifier = Modifier.padding(top = if (expired) 14.dp else 0.dp),
                    codeDescription = machineCodeDescription,
                    ticketSlot = {
                        val stub = authState.ticket
                        if (stub != null) {
                            // printProgress 在这个 lambda 里读，不在屏幕组合体里读：
                            // 出票动画约 84 帧，在外面读会让整屏（含机壳毛玻璃和 12 个键）
                            // 每帧重组一次；读在这里，失效范围收在票内。
                            CinemaTicket(
                                stub = stub,
                                phase = phaseAt(printProgress.value),
                                loginState = loginState,
                                // 打印中三个入口不可点：票还在推出，按下去等于对着半张纸下单
                                traktEnabled = isActivated && !isPrinting,
                                doubanEnabled = isActivated && !isPrinting,
                                guestEnabled = isActivated && !isPrinting,
                                onTraktLogin = { launchAuthorization() },
                                onCancelAuth = { loginViewModel.reset() },
                                onDoubanLogin = onDoubanLogin,
                                onGuestMode = onGuestMode
                            )
                        }
                    }
                )

                if (loginState == LoginState.ERROR) {
                    // LoginViewModel 存原始异常,组合期转本地化文案
                    Text(
                        text = errorMessage?.toUserMessage(context, R.string.login_failed)
                            ?: stringResource(R.string.login_denied),
                        modifier = Modifier.padding(top = 8.dp),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center
                    )
                }
                Spacer(modifier = Modifier.height(20.dp))
            }
        }
    }

    if (showWhatIsTraktDialog) {
        AlertDialog(
            onDismissRequest = { showWhatIsTraktDialog = false },
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            title = { Text(stringResource(R.string.login_what_is_trakt_title)) },
            text = { Text(stringResource(R.string.login_what_is_trakt_desc)) },
            confirmButton = {
                Button(onClick = {
                    showWhatIsTraktDialog = false
                    CustomTabsIntent.Builder().build().launchUrl(context, Uri.parse("https://trakt.tv/auth/join"))
                }) { Text(stringResource(R.string.login_what_is_trakt_register)) }
            },
            dismissButton = {
                TextButton(onClick = { showWhatIsTraktDialog = false }) {
                    Text(stringResource(R.string.login_what_is_trakt_close))
                }
            }
        )
    }
}

/** 取票码位数。六格键盘只收这么多位，满位才点亮取票键。 */
private const val TICKET_CODE_DIGITS = 6

/**
 * 已取票态六格里显示的固定串。
 *
 * 取票码本身不落盘（只存派生出的厅排座），所以这里显示的不是脱敏后的真码，
 * 而是一串不含任何信息的占位——它只负责让机器看起来「装着一张已出的票」。
 */
private const val COLLECTED_CODE_MASK = "******"

/** 报错后清空六格前的等待时长：够六格抖完（5 段 × 45ms），抖动中清空看不出发生了什么。 */
private const val TICKET_ERROR_CLEAR_DELAY_MS = 320L

/**
 * 像素屏上的短状态。分支与 authErrorString 一一对应：屏宽只够放几个字，
 * 完整引导文案仍走 authErrorString 印在机器下方，两处不能有一边漏掉某个错误码。
 */
internal fun machineStatusString(error: String): Int = when {
    error.contains("MIGRATION_DEVICE_NOT_FOUND") -> R.string.machine_status_migration_missing
    error.contains("MIGRATION_DEVICE_MISMATCH") -> R.string.machine_status_migration_mismatch
    error.contains("DEVICE_ALREADY_BOUND") -> R.string.machine_status_device_bound
    error.contains("INVITE_BOUND") -> R.string.machine_status_device_bound
    error.contains("DEVICE_LIMIT_REACHED") -> R.string.machine_status_device_limit
    error.contains("INVITE_ALREADY_USED") -> R.string.machine_status_used
    error.contains("INVITE_REVOKED") -> R.string.machine_status_revoked
    error.contains("INVITE_EXPIRED") -> R.string.machine_status_expired
    error.contains("FRIEND_DISABLED") -> R.string.machine_status_friend_disabled
    error.contains("INVALID_INVITE") -> R.string.machine_status_invalid
    error.contains("RATE_LIMITED") -> R.string.machine_status_rate_limited
    error.contains("timeout", ignoreCase = true) ||
        error.contains("unable to resolve host", ignoreCase = true) ||
        error.contains("failed to connect", ignoreCase = true) ||
        error.contains("network is unreachable", ignoreCase = true) -> R.string.machine_status_network
    else -> R.string.machine_status_failed
}

/**
 * 系统的动画时长倍率。为 0 表示用户在开发者选项或无障碍设置里关掉了动画，
 * 此时必须跳过出票推出直接进已取票态。
 */
private fun animatorDurationScale(context: Context): Float = runCatching {
    Settings.Global.getFloat(
        context.contentResolver,
        Settings.Global.ANIMATOR_DURATION_SCALE,
        1f
    )
}.getOrDefault(1f)

/** 读剪贴板首项文本。取不到返回空串，交给 pasteTicketCode 判定「没抽到码」。 */
private fun readClipboardText(context: Context): String {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    return manager?.primaryClip
        ?.takeIf { it.itemCount > 0 }
        ?.getItemAt(0)
        ?.text
        ?.toString()
        .orEmpty()
}

@Composable
private fun MovieBackdrop(modifier: Modifier = Modifier) {
    val primary = MaterialTheme.colorScheme.primary
    val motifColor = primary.copy(alpha = 0.45f)
    val reelColor = primary.copy(alpha = 0.24f)
    val dotColor = primary.copy(alpha = 0.13f)
    Box(
        modifier
            .fillMaxSize()
            .drawBehind {
                val step = 22.dp.toPx()
                var y = step / 2f
                while (y < size.height) {
                    var x = step / 2f
                    while (x < size.width) {
                        drawCircle(dotColor, radius = 0.7.dp.toPx(), center = Offset(x, y))
                        x += step
                    }
                    y += step
                }
            }
    ) {
        Text(text = "🎟", modifier = Modifier.align(Alignment.TopStart).padding(top = 150.dp, start = 22.dp).rotate(-18f), fontSize = 32.sp, color = motifColor)
        Text(text = "🍿", modifier = Modifier.align(Alignment.TopEnd).padding(top = 194.dp, end = 26.dp).rotate(16f), fontSize = 32.sp, color = motifColor)
        Text(text = "🎞", modifier = Modifier.align(Alignment.BottomStart).padding(bottom = 175.dp, start = 25.dp).rotate(22f), fontSize = 32.sp, color = motifColor)
        Text(text = "🎬", modifier = Modifier.align(Alignment.BottomEnd).padding(bottom = 132.dp, end = 24.dp).rotate(-14f), fontSize = 32.sp, color = motifColor)
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 315.dp)
                .size(105.dp)
                .rotate(15f)
                .drawBehind {
                    drawCircle(
                        color = reelColor,
                        radius = size.minDimension / 2f - 7.dp.toPx(),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 13.dp.toPx(), pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 5.dp.toPx())))
                    )
                }
        )
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(36.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            repeat(10) {
                Box(
                    modifier = Modifier
                        .size(width = 25.dp, height = 36.dp)
                        .background(reelColor)
                )
            }
        }
    }
}

internal fun authErrorString(error: String): Int = when {
    error.contains("MIGRATION_DEVICE_NOT_FOUND") -> R.string.auth_error_migration_device_not_found
    error.contains("MIGRATION_DEVICE_MISMATCH") -> R.string.auth_error_migration_device_mismatch
    error.contains("DEVICE_ALREADY_BOUND") -> R.string.auth_error_device_already_bound
    // AuthViewModel 在「静默恢复也没救回来」时发的是 INVITE_BOUND，原来没有对应分支，
    // 会落到 auth_error_generic，用户看到「取票失败」以为是码的问题继续换码试。
    error.contains("INVITE_BOUND") -> R.string.auth_error_device_already_bound
    error.contains("DEVICE_LIMIT_REACHED") -> R.string.auth_error_device_limit
    error.contains("INVITE_ALREADY_USED") -> R.string.auth_error_invite_used
    error.contains("INVITE_REVOKED") -> R.string.auth_error_invite_revoked
    error.contains("INVITE_EXPIRED") -> R.string.auth_error_invite_expired
    error.contains("FRIEND_DISABLED") -> R.string.auth_error_friend_disabled
    error.contains("INVALID_INVITE") -> R.string.auth_error_invalid_invite
    // 6 位纯数字码可暴破，服务端在激活入口加了单 IP 限流；连续输错会撞到 429，
    // 落到 auth_error_generic 会让用户以为码本身有问题继续换码试。
    error.contains("RATE_LIMITED") -> R.string.auth_error_rate_limited
    error.contains("timeout", ignoreCase = true) ||
        error.contains("unable to resolve host", ignoreCase = true) ||
        error.contains("failed to connect", ignoreCase = true) ||
        error.contains("network is unreachable", ignoreCase = true) -> R.string.auth_error_network
    else -> R.string.auth_error_generic
}
