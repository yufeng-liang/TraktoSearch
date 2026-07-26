package com.tracktosearch.ui.screen.auth

import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tracktosearch.BuildConfig
import com.tracktosearch.data.auth.AuthManager
import com.tracktosearch.data.auth.AuthState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AuthUiState(
    val inviteCode: String = "",
    val isLoading: Boolean = false,
    val error: String? = null,
    val activated: Boolean = false
)

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val authManager: AuthManager
) : ViewModel() {
    private val _uiState = MutableStateFlow(
        AuthUiState(activated = authManager.authState.value.isActivated())
    )
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    private fun AuthState.isActivated(): Boolean = this == AuthState.AUTHORIZED || this == AuthState.OFFLINE

    fun updateInviteCode(value: String) {
        _uiState.value = _uiState.value.copy(inviteCode = value, error = null)
    }

    fun activate() {
        val inviteCode = _uiState.value.inviteCode.trim()
        if (inviteCode.isEmpty()) {
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
            _uiState.value = if (result.isSuccess) {
                _uiState.value.copy(isLoading = false, activated = true)
            } else {
                _uiState.value.copy(
                    isLoading = false,
                    error = result.exceptionOrNull()?.message ?: "ACTIVATION_FAILED"
                )
            }
        }
    }
}
