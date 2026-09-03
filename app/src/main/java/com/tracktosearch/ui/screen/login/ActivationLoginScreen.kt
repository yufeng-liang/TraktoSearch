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
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tracktosearch.OAuthCallback
import com.tracktosearch.R
import com.tracktosearch.ui.component.isAppDarkTheme
import com.tracktosearch.ui.theme.LoginActionInk
import com.tracktosearch.ui.theme.LoginErrorInk
import com.tracktosearch.ui.theme.LoginPaperDark
import com.tracktosearch.ui.theme.LoginPaperLight
import com.tracktosearch.ui.theme.LoginSeatSilhouette
import com.tracktosearch.ui.theme.LoginSecondaryInk
import com.tracktosearch.ui.theme.LoginTitleInk
import com.tracktosearch.ui.screen.auth.AuthViewModel
import com.tracktosearch.ui.util.HapticType
import com.tracktosearch.ui.util.performHaptic
import com.tracktosearch.ui.util.toUserMessage
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

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
    val scrollState = rememberScrollState()
    val isDarkTheme = isAppDarkTheme()
    // 整屏不映射主题，见 Color.kt 的「激活登录页固定色」段
    val loginBackground = if (isDarkTheme) LoginPaperDark else LoginPaperLight
    var showWhatIsTraktDialog by remember { mutableStateOf(false) }

    val isActivated = authState.activated

    val view = LocalView.current
    val printProgress = remember { Animatable(0f) }
    var isPrinting by remember { mutableStateOf(false) }
    // 粘贴没抽到 6 位数字时的一次性提示。只占像素屏，不进 AuthUiState——
    // 它不是一次激活失败，下一次按键就该消失。
    var pasteMissed by remember { mutableStateOf(false) }
    // 豆瓣那行的进行态。豆瓣登录是导航走开，没有 loginState 可用，只能本地记一笔：
    // 正常路径下返回本页时 composition 重建，这个标记自己就没了；异常路径（点了但导航
    // 没发生）靠下面那个超时兜底，不然票上那行会一直停在「授权中…」
    var doubanBusy by remember { mutableStateOf(false) }
    var observedActivated by remember { mutableStateOf(isActivated) }

    LaunchedEffect(doubanBusy) {
        if (!doubanBusy) return@LaunchedEffect
        delay(DOUBAN_BUSY_TIMEOUT_MS)
        doubanBusy = false
    }

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
    // 屏上两行、屏上墨色、六格抖动、顶边那排灯全从这一个档位派生，见 machinePhaseOf：
    // 原先各判一遍，加一档状态很容易只改到其中一处，屏上说正在核对、灯却还红着
    val machinePhase = machinePhaseOf(
        isLoading = authState.isLoading,
        hasError = authError != null || pasteMissed,
        isPrinting = isPrinting,
        isCollected = isActivated,
    )
    val machineStatus = when (machinePhase) {
        MachinePhase.Verifying -> stringResource(R.string.machine_status_verifying)
        // 报错档有两个来源：服务端返回的错误码，和粘贴时剪贴板里抽不出六位数字
        MachinePhase.Failed -> if (authError != null) {
            stringResource(machineStatusString(authError))
        } else {
            stringResource(R.string.machine_status_clipboard_empty)
        }
        MachinePhase.Printing -> stringResource(R.string.machine_status_printing)
        MachinePhase.Collected -> stringResource(R.string.machine_status_collected)
        MachinePhase.Ready -> stringResource(R.string.machine_status_ready)
    }
    // 像素屏第二行放完整引导句。它以前印在机器外面，是因为当时误以为屏宽装不下 ——
    // 实测一行约 25 字（412dp 屏）到 17 字（360dp 屏），而最长的引导是 22 字，装得下。
    //
    // 这条链不能照抄第一行的 when(phase)：粘贴失败只有短状态没有长文案（第二行仍留着引导句），
    // 迁移提示也不占一个档位，它是叠在待输入态上的一句话
    val machineDetail = when {
        machinePhase == MachinePhase.Verifying -> stringResource(R.string.machine_detail_verifying)
        authError != null -> stringResource(authErrorString(authError))
        authState.requiresMigrationInvite -> stringResource(R.string.auth_migration_invite_hint)
        isActivated -> stringResource(R.string.machine_detail_collected)
        else -> stringResource(R.string.login_activation_locked)
    }

    fun launchAuthorization() {
        scope.launch {
            loginViewModel.startAuthorization()
            loginViewModel.getAuthorizationUrl()?.let { authUrl ->
                // 浏览器起不来（设备上没有浏览器、被安全软件拦下）时不会有 ON_STOP，
                // TraktAuthCancelGuard 整条兜底路径都走不到，而票上那行已经没有取消入口，
                // 于是会永久停在「授权中…」。这里自己复位，是那个入口留下的唯一一个洞
                runCatching {
                    CustomTabsIntent.Builder().build().launchUrl(context, Uri.parse(authUrl))
                }.onFailure { loginViewModel.onAuthLaunchFailed(it) }
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
            // 背景直接铺，不再套 hazeSource / backdropSource 那两层：
            // 这一屏唯一用毛玻璃的组件是取票机机壳，现在机壳自绘了，就没有采样层的消费者
            MovieBackdrop(modifier = Modifier.fillMaxSize())

            ActivationLoginContent(
                machineCode = machineCode,
                machineStatus = machineStatus,
                machineDetail = machineDetail,
                machinePhase = machinePhase,
                keypadEnabled = !isActivated && !authState.isLoading,
                submitEnabled = authState.inviteCode.length == TICKET_CODE_DIGITS &&
                    !authState.isLoading &&
                    !isActivated,
                expiredMessage = if (expired) stringResource(R.string.auth_expired_message) else null,
                loginErrorText = if (loginState == LoginState.ERROR) {
                    // LoginViewModel 存原始异常，组合期转本地化文案
                    errorMessage?.toUserMessage(context, R.string.login_failed)
                        ?: stringResource(R.string.login_denied)
                } else {
                    null
                },
                codeDescription = machineCodeDescription,
                scrollState = scrollState,
                onDigit = authViewModel::appendDigit,
                onBackspace = authViewModel::deleteLastDigit,
                onPaste = {
                    // Android 12 起每次读剪贴板都会弹系统提示，所以只在用户按下粘贴键时读，
                    // 绝不在进入页面时自动读。
                    pasteMissed = !authViewModel.pasteTicketCode(readClipboardText(context))
                },
                onSubmit = { authViewModel.activate() },
                onWhatIsTrakt = { showWhatIsTraktDialog = true },
                modifier = Modifier.zIndex(1f),
                ticketSlot = {
                    val stub = authState.ticket
                    if (stub != null) {
                        // printProgress 在这个 lambda 里读，不在屏幕组合体里读：
                        // 出票动画约 84 帧，在外面读会让整屏（含机壳和 12 个键）
                        // 每帧重组一次；读在这里，失效范围收在票内。
                        CinemaTicket(
                            stub = stub,
                            phase = phaseAt(printProgress.value),
                            loginState = loginState,
                            doubanBusy = doubanBusy,
                            // 打印中三个入口不可点：票还在推出，按下去等于对着半张纸下单
                            traktEnabled = isActivated && !isPrinting,
                            doubanEnabled = isActivated && !isPrinting,
                            guestEnabled = isActivated && !isPrinting,
                            onTraktLogin = { launchAuthorization() },
                            onDoubanLogin = {
                                doubanBusy = true
                                onDoubanLogin()
                            },
                            onGuestMode = onGuestMode
                        )
                    }
                }
            )
        }
    }

    if (showWhatIsTraktDialog) {
        AlertDialog(
            onDismissRequest = { showWhatIsTraktDialog = false },
            containerColor = LoginPaperLight,
            titleContentColor = LoginTitleInk,
            textContentColor = LoginSecondaryInk,
            title = { Text(stringResource(R.string.login_what_is_trakt_title)) },
            text = { Text(stringResource(R.string.login_what_is_trakt_desc)) },
            confirmButton = {
                Button(
                    onClick = {
                        showWhatIsTraktDialog = false
                        CustomTabsIntent.Builder().build()
                            .launchUrl(context, Uri.parse("https://trakt.tv/auth/join"))
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = LoginTitleInk,
                        contentColor = LoginPaperLight
                    )
                ) { Text(stringResource(R.string.login_what_is_trakt_register)) }
            },
            dismissButton = {
                TextButton(
                    onClick = { showWhatIsTraktDialog = false },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = LoginActionInk
                    )
                ) {
                    Text(stringResource(R.string.login_what_is_trakt_close))
                }
            }
        )
    }
}

/**
 * 激活页的整个竖向版式。不含背景层，也不认识 ViewModel。
 *
 * 抽出来是为了能在单元测试里量高度：这一屏的设计要求是「一屏放得下」，
 * 而带 `hiltViewModel()` 默认值的 [ActivationLoginScreen] 在 Robolectric 里立不起来。
 * 护栏见 ActivationLoginLayoutTest。
 *
 * 比基准机矮的屏整屏按比例缩，见 [loginContentScale]。
 *
 * @param scrollState 由调用方持有 —— 测试靠 `maxValue == 0` 判断有没有超出一屏
 * @param expiredMessage 授权过期提示，为 null 时不占位
 * @param loginErrorText Trakt 授权失败提示，为 null 时不占位
 * @param machinePhase 机器此刻处于哪一档，原样转给 [TicketMachine]
 */
@Composable
internal fun ActivationLoginContent(
    machineCode: String,
    machineStatus: String,
    machineDetail: String?,
    machinePhase: MachinePhase,
    keypadEnabled: Boolean,
    submitEnabled: Boolean,
    expiredMessage: String?,
    loginErrorText: String?,
    codeDescription: String?,
    scrollState: ScrollState,
    onDigit: (Char) -> Unit,
    onBackspace: () -> Unit,
    onPaste: () -> Unit,
    onSubmit: () -> Unit,
    onWhatIsTrakt: () -> Unit,
    modifier: Modifier = Modifier,
    ticketSlot: @Composable () -> Unit = {},
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val density = LocalDensity.current
        // 系统栏那部分高度不参与缩放：它是物理的一段，缩内容也缩不掉它，
        // 所以先扣掉再算比例。IME 不算在内 —— 弹键盘时整台机器不该跟着缩一次
        val systemBarsHeight = with(density) {
            val insets = WindowInsets.statusBars.getTop(this) +
                WindowInsets.navigationBars.getBottom(this)
            insets.toDp()
        }
        val scale = loginContentScale(available = maxHeight - systemBarsHeight)
        // 缩放走 density 而不是 graphicsLayer：后者只改绘制，版面仍按原尺寸测量，
        // 滚动条照旧存在，缩完还得自己算平移。改 density 则整棵子树按小一号的 dp
        // 重新测量 —— dp、sp、触达区、圆角、笔画宽度一起等比缩小，一处都不会漏。
        // fontScale 原样传下去：用户调大的系统字号不该被这里悄悄抵消掉
        CompositionLocalProvider(
            LocalDensity provides Density(density.density * scale, density.fontScale)
        ) {
            ActivationLoginLayout(
                machineCode = machineCode,
                machineStatus = machineStatus,
                machineDetail = machineDetail,
                machinePhase = machinePhase,
                keypadEnabled = keypadEnabled,
                submitEnabled = submitEnabled,
                expiredMessage = expiredMessage,
                loginErrorText = loginErrorText,
                codeDescription = codeDescription,
                scrollState = scrollState,
                onDigit = onDigit,
                onBackspace = onBackspace,
                onPaste = onPaste,
                onSubmit = onSubmit,
                onWhatIsTrakt = onWhatIsTrakt,
                ticketSlot = ticketSlot,
            )
        }
    }
}

/**
 * 整屏缩放比例：矮屏按比例缩，高屏不放大。
 *
 * 上界钉在 1f 是这条规则的一半 —— 屏更高时机器不该跟着长大。那点余量给不了新信息，
 * 只会让一台取票机变成一件巨物，票面 11sp 的小字也不会因为屏高就该变成 13sp。
 *
 * 下界 [LOGIN_MIN_CONTENT_SCALE] 兜的是异常小的窗口（分屏、折叠屏内屏的一半）：
 * 缩到那以下键帽会小到按不准，那种情况下宁可让它滚动，也不交一屏按不动的界面。
 *
 * @param available 扣掉系统栏之后这一屏真正能用的高度
 */
internal fun loginContentScale(available: Dp): Float =
    (available / LoginContentBaselineHeight).coerceIn(LOGIN_MIN_CONTENT_SCALE, 1f)

/**
 * 版式在基准机上占掉的高度（不含系统栏）。
 *
 * 792dp 是实测值 779dp 加约 13dp 余量：标题 41 + 间隙 20 + 机器 451 + 票 178 +
 * 「什么是 Trakt」44 + 页面上下内边距 40。余量留给别的语言 —— 日韩文案更长，
 * 像素屏第二行和票面三行都可能比中文高一档。基准机 390×866dp 上算出来的比例是
 * 1.02，取 1f，也就是这台机器上的版面与实测那一版逐 dp 相同。
 */
private val LoginContentBaselineHeight = 792.dp

/** 缩放下界。再小键帽就按不准了，那种窗口宁可滚动。 */
private const val LOGIN_MIN_CONTENT_SCALE = 0.7f

@Composable
private fun ActivationLoginLayout(
    machineCode: String,
    machineStatus: String,
    machineDetail: String?,
    machinePhase: MachinePhase,
    keypadEnabled: Boolean,
    submitEnabled: Boolean,
    expiredMessage: String?,
    loginErrorText: String?,
    codeDescription: String?,
    scrollState: ScrollState,
    onDigit: (Char) -> Unit,
    onBackspace: () -> Unit,
    onPaste: () -> Unit,
    onSubmit: () -> Unit,
    onWhatIsTrakt: () -> Unit,
    ticketSlot: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .imePadding()
            .navigationBarsPadding()
            .padding(horizontal = 18.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.statusBarsPadding().height(TopInset))
        Text(
            text = stringResource(R.string.login_title),
            fontSize = 34.sp,
            lineHeight = 41.sp,
            letterSpacing = (-2.04f).sp,
            color = LoginTitleInk,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Serif
        )
        // 副标题撤掉：出票之后这一屏要装下标题、整台机器和一张 178dp 的票，
        // 而这句 slogan 连行距占 27dp，是首屏里唯一一处纯装饰的高度。
        // 它仍留在 LoginScreen 上，那一屏没有取票机要养

        Spacer(modifier = Modifier.height(TitleToMachineGap))
        if (expiredMessage != null) {
            Text(
                text = expiredMessage,
                fontSize = 16.sp,
                lineHeight = 24.sp,
                color = LoginSecondaryInk,
                textAlign = TextAlign.Center
            )
        }
        TicketMachine(
            code = machineCode,
            statusText = machineStatus,
            detailText = machineDetail,
            phase = machinePhase,
            keypadEnabled = keypadEnabled,
            submitEnabled = submitEnabled,
            onDigit = onDigit,
            onBackspace = onBackspace,
            onPaste = onPaste,
            onSubmit = onSubmit,
            modifier = Modifier.padding(top = if (expiredMessage != null) 14.dp else 0.dp),
            codeDescription = codeDescription,
            ticketSlot = ticketSlot
        )

        if (loginErrorText != null) {
            Text(
                text = loginErrorText,
                modifier = Modifier.padding(top = 8.dp),
                color = LoginErrorInk,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center
            )
        }

        // 「什么是 Trakt」从右上角挪到这里。标题上提 45dp 后，右上角那个按钮会压在标题上，
        // 而它不是主动作，不值得为它在顶部留出一整行
        TextButton(
            onClick = onWhatIsTrakt,
            colors = ButtonDefaults.textButtonColors(contentColor = LoginActionInk),
            modifier = Modifier.padding(top = 4.dp)
        ) {
            Text(stringResource(R.string.login_what_is_trakt))
            Spacer(modifier = Modifier.size(4.dp))
            Icon(
                Icons.AutoMirrored.Rounded.HelpOutline,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

/** 顶部余量。原先是 53dp 加一块场记板图标，两者加起来吃掉近 1/4 屏。 */
private val TopInset = 4.dp

/** 标题到机器的间隙。原先 90dp，是这一屏最大的一块可回收空间。 */
private val TitleToMachineGap = 20.dp

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
 * 豆瓣那行「授权中…」的超时。
 *
 * 正常路径根本用不到它：点下去就导航走了，回到本页时状态已经重置。它兜的是导航没发生
 * 那一档——5 秒是「慢设备上导航确实还没完成」和「用户开始觉得这行卡住了」之间的那一档。
 */
private const val DOUBAN_BUSY_TIMEOUT_MS = 5_000L

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
 *
 * internal 而不是 private：机壳顶边的跑马灯也要问这一句，见 TicketMachine.MarqueeBulbs。
 */
internal fun animatorDurationScale(context: Context): Float = runCatching {
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
    // 底纹也走场景固定墨色：跟着 primary 走的话，冷色主题下牛皮纸上会浮一层蓝点
    val motifColor = LoginActionInk.copy(alpha = 0.45f)
    val reelColor = LoginActionInk.copy(alpha = 0.24f)
    val dotColor = LoginActionInk.copy(alpha = 0.13f)
    val seatColor = LoginSeatSilhouette.copy(alpha = 0.30f)
    // 四个影院符号按屏高比例摆，不写死 padding：原先那套死值（top 150/194、bottom 175/132）
    // 是照旧版式调的，版式一压缩爆米花就压在机壳右上角上了。
    // 比例的落点原则是「避开机器」—— 机器在这一版里大约占屏高的 18% 到 73%，
    // 符号只能待在它上下两条窄带里，横向再靠到左右边缘，才不会跟居中的标题打架。
    BoxWithConstraints(
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
        val screenHeight = maxHeight
        Text(
            text = "🎟",
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(top = screenHeight * 0.060f, start = 16.dp)
                .rotate(-18f),
            fontSize = 32.sp,
            color = motifColor
        )
        Text(
            text = "🍿",
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = screenHeight * 0.100f, end = 18.dp)
                .rotate(16f),
            fontSize = 32.sp,
            color = motifColor
        )
        Text(
            text = "🎞",
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(bottom = screenHeight * 0.100f, start = 20.dp)
                .rotate(22f),
            fontSize = 32.sp,
            color = motifColor
        )
        Text(
            text = "🎬",
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(bottom = screenHeight * 0.055f, end = 20.dp)
                .rotate(-14f),
            fontSize = 32.sp,
            color = motifColor
        )
        // 胶片轮挪到下半屏：它原来在机壳右上角后面，而机壳这一版起不再半透明，
        // 压在机器底下的东西一点都看不见
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(bottom = screenHeight * 0.135f, end = 6.dp)
                .size(88.dp)
                .rotate(15f)
                .drawBehind {
                    drawCircle(
                        color = reelColor,
                        radius = size.minDimension / 2f - 7.dp.toPx(),
                        style = Stroke(
                            width = 13.dp.toPx(),
                            pathEffect = PathEffect.dashPathEffect(
                                floatArrayOf(2.dp.toPx(), 5.dp.toPx())
                            )
                        )
                    )
                }
        )
        // 屏底一排影院座椅剪影。原先是 10 个等宽方块，那读出来是条纹不是座位；
        // 现在每个座位有靠背、扶手和两侧的间隙，看一眼就知道是从后排望向银幕
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(SeatRowHeight),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.Bottom
        ) {
            repeat(SEAT_COUNT) {
                CinemaSeat(color = seatColor)
            }
        }
    }
}

/** 座椅剪影一排的高度。只占屏底一条窄带，机器和按钮都在它上面。 */
private val SeatRowHeight = 40.dp

/** 座位数。7 个在 412dp 宽上每个约 50dp，靠背和扶手分得开。 */
private const val SEAT_COUNT = 7

/**
 * 单个座椅剪影：一块圆角靠背，两侧各一条矮扶手。
 *
 * 画成剪影而不是描边：它在最底层，描边会跟机器的边线抢，实心色块只当影子。
 */
@Composable
private fun CinemaSeat(color: Color) {
    Box(
        modifier = Modifier
            .size(width = 46.dp, height = SeatRowHeight)
            .drawBehind {
                val armWidth = 6.dp.toPx()
                val backTop = 8.dp.toPx()
                val corner = 7.dp.toPx()
                // 靠背：上圆角、下贴底
                drawRoundRect(
                    color = color,
                    topLeft = Offset(armWidth, backTop),
                    size = Size(
                        width = size.width - armWidth * 2,
                        height = size.height - backTop
                    ),
                    cornerRadius = CornerRadius(corner, corner)
                )
                // 两侧扶手：比靠背矮一截，顶端也倒个小角
                val armTop = size.height * 0.45f
                listOf(0f, size.width - armWidth).forEach { x ->
                    drawRoundRect(
                        color = color,
                        topLeft = Offset(x, armTop),
                        size = Size(armWidth, size.height - armTop),
                        cornerRadius = CornerRadius(armWidth / 2f, armWidth / 2f)
                    )
                }
            }
    )
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
