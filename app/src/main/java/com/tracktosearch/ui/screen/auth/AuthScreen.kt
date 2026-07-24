package com.tracktosearch.ui.screen.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tracktosearch.R

@Composable
fun AuthScreen(
    onActivated: () -> Unit,
    expired: Boolean = false,
    viewModel: AuthViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(state.activated) {
        if (state.activated) onActivated()
    }
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = stringResource(R.string.auth_title),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            text = stringResource(if (expired) R.string.auth_expired_message else R.string.auth_subtitle),
            modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedTextField(
            value = state.inviteCode,
            onValueChange = viewModel::updateInviteCode,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.auth_invite_code)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
            isError = state.error != null
        )
        state.error?.let { error ->
            Text(
                text = stringResource(authErrorString(error)),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }
        Button(
            onClick = viewModel::activate,
            modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
            enabled = !state.isLoading
        ) {
            if (state.isLoading) CircularProgressIndicator() else Text(stringResource(R.string.auth_activate))
        }
    }
}

private fun authErrorString(error: String): Int = when {
    error.contains("MIGRATION_DEVICE_NOT_FOUND") -> R.string.auth_error_migration_device_not_found
    error.contains("MIGRATION_DEVICE_MISMATCH") -> R.string.auth_error_migration_device_mismatch
    error.contains("DEVICE_ALREADY_BOUND") -> R.string.auth_error_device_already_bound
    error.contains("DEVICE_LIMIT_REACHED") -> R.string.auth_error_device_limit
    error.contains("INVITE_EXPIRED") -> R.string.auth_error_invite_expired
    error.contains("FRIEND_DISABLED") -> R.string.auth_error_friend_disabled
    error.contains("INVALID_INVITE") -> R.string.auth_error_invalid_invite
    else -> R.string.auth_error_generic
}
