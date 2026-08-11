@file:OptIn(dev.chrisbanes.haze.ExperimentalHazeApi::class)

package com.tracktosearch.ui.screen.login

import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.HelpOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tracktosearch.OAuthCallback
import com.tracktosearch.R
import com.tracktosearch.ui.component.AppGlassStyles
import com.tracktosearch.ui.component.DoubanLogo
import com.tracktosearch.ui.component.appVisualEffect
import com.tracktosearch.ui.component.isAppDarkTheme
import com.tracktosearch.ui.theme.monetDoubanGreen
import com.tracktosearch.ui.theme.onMonetDoubanGreen
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.materials.HazeMaterials
import dev.chrisbanes.haze.hazeSource
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
    redirectToBrowser: Boolean = false,
    expired: Boolean = false,
    modifier: Modifier = Modifier,
    loginViewModel: LoginViewModel = hiltViewModel(),
    authViewModel: com.tracktosearch.ui.screen.auth.AuthViewModel = hiltViewModel()
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val loginState by loginViewModel.loginState.collectAsStateWithLifecycle()
    val errorMessage by loginViewModel.errorMessage.collectAsStateWithLifecycle()
    val authState by authViewModel.uiState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val hazeState = remember { HazeState() }
    val scrollState = rememberScrollState()
    val isDarkTheme = isAppDarkTheme()
    val loginBackground = if (isDarkTheme) Color(0xFFD9CFC2) else Color(0xFFF7EFE2)
    val loginTextColor = if (isDarkTheme) Color(0xFF4A3529) else MaterialTheme.colorScheme.onSurface
    val loginSecondaryTextColor = if (isDarkTheme) Color(0xFF624B3C) else MaterialTheme.colorScheme.onSurfaceVariant
    var showWhatIsTraktDialog by remember { mutableStateOf(false) }

    val isActivated = authState.activated

    fun launchAuthorization() {
        scope.launch {
            loginViewModel.startAuthorization()
            loginViewModel.getAuthorizationUrl()?.let { authUrl ->
                CustomTabsIntent.Builder().build().launchUrl(context, Uri.parse(authUrl))
            }
        }
    }

    LaunchedEffect(redirectToBrowser, isActivated) {
        if (redirectToBrowser && isActivated && loginState == LoginState.IDLE) {
            launchAuthorization()
        }
    }

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
                    .verticalScroll(scrollState)
                    .imePadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 18.dp, vertical = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(modifier = Modifier.statusBarsPadding().height(53.dp))
                Box(modifier = Modifier.offset(y = 15.dp)) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CinemaClapperIcon()
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
                ActivationCard(
                    authState = authState,
                    authViewModel = authViewModel,
                    hazeState = hazeState,
                    loginState = loginState,
                    onLoginClick = { launchAuthorization() },
                    canUseActions = isActivated,
                    modifier = Modifier.padding(top = if (expired) 14.dp else 0.dp)
                )

                val canUseActions = isActivated
                if (loginState == LoginState.ERROR) {
                    Text(
                        text = errorMessage?.ifEmpty { stringResource(R.string.login_failed) }
                            ?: stringResource(R.string.login_denied),
                        modifier = Modifier.padding(top = 8.dp),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center
                    )
                }

                ActivationSecondaryActions(
                    guestEnabled = canUseActions,
                    doubanEnabled = canUseActions &&
                        loginState != LoginState.AUTHORIZING &&
                        loginState != LoginState.CONNECTING,
                    hazeState = hazeState,
                    guestContentColor = loginTextColor,
                    onDoubanLogin = onDoubanLogin,
                    onGuestMode = onGuestMode,
                    modifier = Modifier.fillMaxWidth()
                )
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

@Composable
internal fun ActivationSecondaryActions(
    guestEnabled: Boolean,
    doubanEnabled: Boolean = guestEnabled,
    hazeState: HazeState,
    guestContentColor: Color,
    onDoubanLogin: () -> Unit,
    onGuestMode: () -> Unit,
    modifier: Modifier = Modifier
) {
    val doubanGreen = MaterialTheme.colorScheme.monetDoubanGreen()
    val doubanButtonShape = RoundedCornerShape(14.dp)
    val doubanInteractionSource = remember { MutableInteractionSource() }
    val doubanHazeStyle = HazeMaterials.thin(
        doubanGreen.copy(alpha = if (doubanEnabled) 0.72f else 0.24f)
    )
    Column(
        modifier = modifier.padding(start = 18.dp, top = 8.dp, end = 18.dp)
    ) {
        Button(
            onClick = onDoubanLogin,
            enabled = doubanEnabled,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .clip(doubanButtonShape)
                .appVisualEffect(
                    input = HazeInput.Sources(hazeState),
                    hazeStyle = doubanHazeStyle,
                    glassStyle = AppGlassStyles.control(
                        tint = doubanGreen.copy(alpha = if (doubanEnabled) 0.72f else 0.24f),
                        shape = doubanButtonShape,
                        interactive = doubanEnabled
                    ),
                    interactionSource = doubanInteractionSource
                ),
            shape = doubanButtonShape,
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.Transparent,
                contentColor = MaterialTheme.colorScheme.onMonetDoubanGreen(),
                disabledContainerColor = Color.Transparent,
                disabledContentColor = MaterialTheme.colorScheme.onMonetDoubanGreen().copy(alpha = 0.38f)
            ),
            interactionSource = doubanInteractionSource
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 品牌图标的 Image 不读 LocalContentColor，需按禁用态内容色 alpha 手动变淡
                val contentAlpha = LocalContentColor.current.alpha
                DoubanLogo(
                    contentDescription = stringResource(R.string.settings_account_douban),
                    modifier = Modifier.size(18.dp).alpha(contentAlpha)
                )
                Text(stringResource(R.string.login_douban))
            }
        }
        TextButton(
            onClick = onGuestMode,
            enabled = guestEnabled,
            colors = ButtonDefaults.textButtonColors(
                contentColor = guestContentColor,
                disabledContentColor = guestContentColor.copy(alpha = 0.38f)
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.login_guest))
        }
    }
}

/** v7 原型中的场记板图标：三条斜切片、圆角棕色底和双层镜头圆环。 */
@Composable
private fun CinemaClapperIcon() {
    val accent = MaterialTheme.colorScheme.primary
    val paper = MaterialTheme.colorScheme.surface
    Box(
        modifier = Modifier.size(width = 84.dp, height = 66.dp),
        contentAlignment = Alignment.TopCenter
    ) {
        Box(
            modifier = Modifier
                .size(width = 84.dp, height = 66.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(accent)
                .drawBehind {
                    val bandWidth = 9.dp.toPx()
                    val bandStep = 19.dp.toPx()
                    val firstBand = 16.dp.toPx()
                    drawLine(
                        color = accent,
                        start = Offset(0f, 0f),
                        end = Offset(size.width, 0f),
                        strokeWidth = 1.dp.toPx()
                    )
                    repeat(3) { index ->
                        val left = firstBand + index * bandStep
                        val path = Path().apply {
                            moveTo(left + 6.dp.toPx(), 0f)
                            lineTo(left + bandWidth + 6.dp.toPx(), 0f)
                            lineTo(left + bandWidth, 12.dp.toPx())
                            lineTo(left, 12.dp.toPx())
                            close()
                        }
                        drawPath(path = path, color = paper)
                    }
                }
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 12.dp)
                .width(60.dp)
                .height(2.dp)
                .background(paper.copy(alpha = 0.28f))
        )
    }
}

@Composable
private fun ActivationCard(
    authState: com.tracktosearch.ui.screen.auth.AuthUiState,
    authViewModel: com.tracktosearch.ui.screen.auth.AuthViewModel,
    hazeState: HazeState,
    loginState: LoginState,
    onLoginClick: () -> Unit,
    canUseActions: Boolean,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(20.dp)
    val accent = MaterialTheme.colorScheme.primary
    val isDarkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val inputFillColor = if (isDarkTheme) {
        Color.Black.copy(alpha = 0.72f)
    } else {
        Color.White.copy(alpha = 0.68f)
    }
    val inputTextColor = if (isDarkTheme) Color.White else MaterialTheme.colorScheme.onSurface
    val inputPlaceholderColor = if (isDarkTheme) {
        Color(0xFFBDBDBD)
    } else {
        accent.copy(alpha = 0.72f)
    }
    val hazeStyle = HazeMaterials.thin(
        MaterialTheme.colorScheme.surface.copy(alpha = 0.48f)
    ).then {
        blurRadius(36.dp)
        noiseFactor(0f)
        blurEnabled(true)
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .zIndex(1f)
            .appVisualEffect(
                input = HazeInput.Sources(hazeState),
                hazeStyle = hazeStyle,
                glassStyle = AppGlassStyles.surface(
                    tint = MaterialTheme.colorScheme.surface.copy(alpha = 0.48f),
                    shape = shape,
                    edgeSoftness = 8.dp,
                    specularIntensity = 0.46f,
                    ambientResponse = 0.50f
                )
            )
            .background(Color.Transparent, shape)
            .border(BorderStroke(1.dp, accent.copy(alpha = 0.30f)), shape)
            .padding(horizontal = 18.dp, vertical = 16.dp)
    ) {
        Text(
            text = stringResource(R.string.login_personal_cinema_access),
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                letterSpacing = 1.43.sp
            ),
            color = accent,
            fontWeight = FontWeight.Bold
        )
        if (authState.requiresMigrationInvite) {
            Text(
                text = stringResource(R.string.auth_migration_invite_hint),
                modifier = Modifier.padding(top = 8.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val inputShape = RoundedCornerShape(13.dp)
            BasicTextField(
                value = authState.inviteCode,
                onValueChange = authViewModel::updateInviteCode,
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .clip(inputShape)
                    .background(inputFillColor, inputShape)
                    .border(
                        width = 1.dp,
                        color = if (authState.error != null) {
                            MaterialTheme.colorScheme.error
                        } else {
                            accent.copy(alpha = 0.30f)
                        },
                        shape = inputShape
                    )
                    .padding(horizontal = 14.dp),
                singleLine = true,
                textStyle = TextStyle(
                    color = inputTextColor,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp
                ),
                cursorBrush = SolidColor(accent),
                decorationBox = { innerTextField ->
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        if (authState.inviteCode.isEmpty()) {
                            Text(
                                text = stringResource(R.string.auth_invite_code),
                                color = inputPlaceholderColor,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 13.sp
                            )
                        }
                        innerTextField()
                    }
                }
            )
            Button(
                onClick = authViewModel::activate,
                enabled = !authState.isLoading && !authState.activated,
                modifier = Modifier
                    .padding(start = 8.dp)
                    .height(48.dp),
                shape = RoundedCornerShape(13.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = accent,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                if (authState.isLoading) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                else Text(stringResource(R.string.auth_activate))
            }
        }
        authState.error?.let { error ->
            Text(
                text = stringResource(authErrorString(error)),
                modifier = Modifier.padding(top = 7.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        } ?: Text(
            text = stringResource(if (authState.activated) R.string.login_activation_choose_path else R.string.login_activation_locked),
            modifier = Modifier.padding(top = 8.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        val loginButtonColors = ButtonDefaults.buttonColors(
            containerColor = accent,
            disabledContainerColor = Color(0xFFDED6CE),
            disabledContentColor = Color(0xFF9A9189)
        )
        Button(
            onClick = onLoginClick,
            enabled = canUseActions && loginState != LoginState.AUTHORIZING && loginState != LoginState.CONNECTING,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp)
                .height(48.dp),
            shape = RoundedCornerShape(14.dp),
            colors = loginButtonColors
        ) {
            when (loginState) {
                LoginState.CONNECTING -> CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                LoginState.ERROR -> Text(stringResource(R.string.login_retry))
                else -> Text(stringResource(R.string.login_button))
            }
        }
    }
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

private fun authErrorString(error: String): Int = when {
    error.contains("MIGRATION_DEVICE_NOT_FOUND") -> R.string.auth_error_migration_device_not_found
    error.contains("MIGRATION_DEVICE_MISMATCH") -> R.string.auth_error_migration_device_mismatch
    error.contains("DEVICE_ALREADY_BOUND") -> R.string.auth_error_device_already_bound
    error.contains("DEVICE_LIMIT_REACHED") -> R.string.auth_error_device_limit
    error.contains("INVITE_ALREADY_USED") -> R.string.auth_error_invite_used
    error.contains("INVITE_REVOKED") -> R.string.auth_error_invite_revoked
    error.contains("INVITE_EXPIRED") -> R.string.auth_error_invite_expired
    error.contains("FRIEND_DISABLED") -> R.string.auth_error_friend_disabled
    error.contains("INVALID_INVITE") -> R.string.auth_error_invalid_invite
    error.contains("timeout", ignoreCase = true) ||
        error.contains("unable to resolve host", ignoreCase = true) ||
        error.contains("failed to connect", ignoreCase = true) ||
        error.contains("network is unreachable", ignoreCase = true) -> R.string.auth_error_network
    else -> R.string.auth_error_generic
}
