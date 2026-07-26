package com.tracktosearch.ui.screen.login

import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.ConfirmationNumber
import androidx.compose.material.icons.rounded.LocalMovies
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tracktosearch.OAuthCallback
import com.tracktosearch.R
import com.tracktosearch.ui.component.hazeModalSurface
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.blurEffect
import dev.chrisbanes.haze.blur.materials.HazeMaterials
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

@Composable
fun ActivationLoginScreen(
    onLoginSuccess: () -> Unit = {},
    onActivated: (() -> Unit)? = null,
    onGuestMode: () -> Unit = {},
    onDoubanImport: () -> Unit = {},
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
    var showWhatIsTraktDialog by remember { mutableStateOf(false) }
    var showDoubanImportRequireLoginDialog by remember { mutableStateOf(false) }
    var pendingDoubanImportAfterLogin by remember { mutableStateOf(false) }

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
            if (pendingDoubanImportAfterLogin) {
                pendingDoubanImportAfterLogin = false
                onDoubanImport()
            } else {
                onLoginSuccess()
            }
        }
    }

    LaunchedEffect(authState.activated) {
        if (authState.activated) onActivated?.invoke()
    }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .hazeSource(state = hazeState)
        ) {
            MovieBackdrop()

            TextButton(
                onClick = { showWhatIsTraktDialog = true },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(end = 12.dp),
            ) {
                Text(stringResource(R.string.login_what_is_trakt))
                Spacer(modifier = Modifier.size(4.dp))
                Icon(Icons.Rounded.Movie, contentDescription = null, modifier = Modifier.size(18.dp))
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .imePadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 28.dp, vertical = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(modifier = Modifier.statusBarsPadding().height(38.dp))
                Icon(
                    imageVector = Icons.Rounded.Movie,
                    contentDescription = null,
                    modifier = Modifier.size(76.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(20.dp))
                Text(
                    text = stringResource(R.string.login_title),
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.login_subtitle),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(64.dp))
                Text(
                    text = stringResource(
                        if (expired) R.string.auth_expired_message else R.string.login_activation_hint
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                ActivationCard(
                    authState = authState,
                    authViewModel = authViewModel,
                    hazeState = hazeState,
                    modifier = Modifier
                        .padding(top = 14.dp)
                )

                val canUseActions = isActivated
                val loginButtonColors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    disabledContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                    disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                )
                Button(
                    onClick = { launchAuthorization() },
                    enabled = canUseActions && loginState != LoginState.AUTHORIZING && loginState != LoginState.CONNECTING,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .padding(top = 10.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = loginButtonColors
                ) {
                    when (loginState) {
                        LoginState.CONNECTING -> CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                        LoginState.ERROR -> Text(stringResource(R.string.login_retry))
                        else -> Text(stringResource(R.string.login_button))
                    }
                }

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

                Text(
                    text = stringResource(R.string.login_sync_hint),
                    modifier = Modifier.padding(top = 12.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                TextButton(
                    onClick = {
                        scope.launch {
                            if (loginViewModel.isTraktLoggedIn()) onDoubanImport()
                            else showDoubanImportRequireLoginDialog = true
                        }
                    },
                    enabled = canUseActions,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.login_douban_import)) }
                TextButton(
                    onClick = onGuestMode,
                    enabled = canUseActions,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.login_guest)) }
                Spacer(modifier = Modifier.height(20.dp))
            }
        }
    }

    if (showDoubanImportRequireLoginDialog) {
        AlertDialog(
            onDismissRequest = { showDoubanImportRequireLoginDialog = false },
            modifier = Modifier.hazeModalSurface(ultraThick = true),
            title = { Text(stringResource(R.string.douban_import_require_trakt_title)) },
            text = { Text(stringResource(R.string.douban_import_require_trakt_desc)) },
            confirmButton = {
                TextButton(onClick = {
                    showDoubanImportRequireLoginDialog = false
                    pendingDoubanImportAfterLogin = true
                    launchAuthorization()
                }) { Text(stringResource(R.string.douban_import_require_trakt_login)) }
            },
            dismissButton = {
                TextButton(onClick = { showDoubanImportRequireLoginDialog = false }) {
                    Text(stringResource(R.string.douban_import_require_trakt_cancel))
                }
            }
        )
    }

    if (showWhatIsTraktDialog) {
        AlertDialog(
            onDismissRequest = { showWhatIsTraktDialog = false },
            modifier = Modifier.hazeModalSurface(ultraThick = true),
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
private fun ActivationCard(
    authState: com.tracktosearch.ui.screen.auth.AuthUiState,
    authViewModel: com.tracktosearch.ui.screen.auth.AuthViewModel,
    hazeState: HazeState,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(24.dp)
    val hazeStyle = HazeMaterials.thick(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f))
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .hazeEffect(state = hazeState) {
                blurEffect { style = hazeStyle }
            }
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.50f), shape)
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)), shape)
            .padding(16.dp)
    ) {
        Text(
            text = stringResource(R.string.login_personal_cinema_access),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp),
            verticalAlignment = Alignment.Top
        ) {
            OutlinedTextField(
                value = authState.inviteCode,
                onValueChange = authViewModel::updateInviteCode,
                modifier = Modifier.weight(1f),
                label = { Text(stringResource(R.string.auth_invite_code)) },
                singleLine = true,
                isError = authState.error != null
            )
            Button(
                onClick = authViewModel::activate,
                enabled = !authState.isLoading,
                modifier = Modifier
                    .padding(start = 8.dp)
                    .height(56.dp),
                shape = RoundedCornerShape(14.dp)
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
            text = stringResource(if (authState.activated) R.string.login_activation_success else R.string.login_activation_locked),
            modifier = Modifier.padding(top = 8.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun MovieBackdrop() {
    val iconColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
    Box(Modifier.fillMaxSize()) {
        Icon(Icons.Rounded.ConfirmationNumber, null, Modifier.align(Alignment.TopStart).padding(top = 110.dp, start = 18.dp).size(34.dp), tint = iconColor)
        Icon(Icons.Rounded.Videocam, null, Modifier.align(Alignment.TopEnd).padding(top = 180.dp, end = 18.dp).size(40.dp), tint = iconColor)
        Icon(Icons.Rounded.LocalMovies, null, Modifier.align(Alignment.CenterStart).padding(start = 20.dp).size(38.dp), tint = iconColor)
        Icon(Icons.Rounded.CameraAlt, null, Modifier.align(Alignment.CenterEnd).padding(end = 20.dp).size(34.dp), tint = iconColor)
        Icon(Icons.Rounded.Star, null, Modifier.align(Alignment.BottomStart).padding(bottom = 150.dp, start = 26.dp).size(30.dp), tint = iconColor)
        Icon(Icons.Rounded.Movie, null, Modifier.align(Alignment.BottomEnd).padding(bottom = 120.dp, end = 24.dp).size(42.dp), tint = iconColor)
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
