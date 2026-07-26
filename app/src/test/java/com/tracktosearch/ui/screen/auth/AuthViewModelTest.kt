package com.tracktosearch.ui.screen.auth

import com.google.common.truth.Truth.assertThat
import com.tracktosearch.test.MainDispatcherRule
import com.tracktosearch.data.auth.AuthManager
import com.tracktosearch.data.auth.AuthState
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Test
import org.junit.Rule

@OptIn(ExperimentalCoroutinesApi::class)
class AuthViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val authManager = mockk<AuthManager>()

    @Test
    fun `authorized gateway state starts as activated`() = runTest {
        every { authManager.authState } returns MutableStateFlow(AuthState.AUTHORIZED)

        val viewModel = AuthViewModel(authManager)

        assertThat(viewModel.uiState.value.activated).isTrue()
    }

    @Test
    fun `updating invite code clears previous error`() = runTest {
        every { authManager.authState } returns MutableStateFlow(AuthState.UNAUTHORIZED)
        val viewModel = AuthViewModel(authManager)

        viewModel.updateInviteCode("")
        viewModel.activate()
        viewModel.updateInviteCode("TS-1234567890")

        assertThat(viewModel.uiState.value.error).isNull()
    }

    @Test
    fun `successful activation unlocks the login page`() = runTest {
        every { authManager.authState } returns MutableStateFlow(AuthState.UNAUTHORIZED)
        coEvery { authManager.activate(any(), any(), any(), any()) } returns Result.success(mockk())
        val viewModel = AuthViewModel(authManager)

        viewModel.updateInviteCode(" TS-1234567890 ")
        viewModel.activate()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.activated).isTrue()
        assertThat(viewModel.uiState.value.isLoading).isFalse()
        assertThat(viewModel.uiState.value.error).isNull()
    }

    @Test
    fun `failed activation keeps login page locked`() = runTest {
        every { authManager.authState } returns MutableStateFlow(AuthState.UNAUTHORIZED)
        coEvery { authManager.activate(any(), any(), any(), any()) } returns Result.failure(Exception("INVALID_INVITE"))
        val viewModel = AuthViewModel(authManager)

        viewModel.updateInviteCode("TS-1234567890")
        viewModel.activate()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.activated).isFalse()
        assertThat(viewModel.uiState.value.error).isEqualTo("INVALID_INVITE")
    }
}
