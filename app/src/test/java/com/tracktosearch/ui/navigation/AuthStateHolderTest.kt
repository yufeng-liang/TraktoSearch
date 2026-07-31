package com.tracktosearch.ui.navigation

import com.google.common.truth.Truth.assertThat
import com.tracktosearch.data.auth.AuthManager
import com.tracktosearch.data.auth.AuthState
import com.tracktosearch.data.remote.trakt.TraktAuthManager
import com.tracktosearch.data.repository.TraktRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test

class AuthStateHolderTest {

    private val authManager = mockk<AuthManager> {
        every { authState } returns MutableStateFlow(AuthState.AUTHORIZED)
    }
    private val traktAuthManager = mockk<TraktAuthManager>()
    private val traktRepository = mockk<TraktRepository>(relaxed = true)

    @Test
    fun `直接登录入口返回Trakt授权地址`() = runTest {
        coEvery { traktAuthManager.buildAuthorizationUrl() } returns
            Result.success("https://trakt.example/oauth")
        val holder = AuthStateHolder(authManager, traktAuthManager, traktRepository)

        val result = holder.buildTraktAuthorizationUrl()

        assertThat(result.getOrNull()).isEqualTo("https://trakt.example/oauth")
    }

    @Test
    fun `直接登录回调成功后清理Trakt缓存`() = runTest {
        coEvery { traktAuthManager.exchangeCodeForToken("oauth-code") } returns Result.success(Unit)
        val holder = AuthStateHolder(authManager, traktAuthManager, traktRepository)

        val result = holder.exchangeTraktCode("oauth-code")

        assertThat(result.getOrNull()).isEqualTo(Unit)
        coVerify(exactly = 1) { traktRepository.clearTraktAccountCaches() }
    }
}
