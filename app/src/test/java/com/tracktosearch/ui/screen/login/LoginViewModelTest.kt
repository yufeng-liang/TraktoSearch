package com.tracktosearch.ui.screen.login

import com.tracktosearch.data.remote.trakt.TraktAuthManager
import com.tracktosearch.data.repository.TraktRepository
import io.mockk.coEvery
import io.mockk.coVerify
import android.content.Context
import io.mockk.mockk
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class LoginViewModelTest {

    @Test
    fun `Trakt OAuth 成功后清理上一账号缓存再进入成功状态`() = runTest {
        val authManager = mockk<TraktAuthManager>()
        val traktRepository = mockk<TraktRepository>(relaxed = true)
        coEvery { authManager.exchangeCodeForToken("oauth-code") } returns Result.success(Unit)

        val viewModel = LoginViewModel(authManager, traktRepository, mockk<Context>(relaxed = true))
        viewModel.exchangeCodeForToken("oauth-code")
        advanceUntilIdle()

        coVerify(exactly = 1) { traktRepository.clearTraktAccountCaches() }
        assertEquals(LoginState.SUCCESS, viewModel.loginState.value)
    }
}
