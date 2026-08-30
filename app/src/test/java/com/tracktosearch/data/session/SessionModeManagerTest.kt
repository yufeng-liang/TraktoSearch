package com.tracktosearch.data.session

import com.google.common.truth.Truth.assertThat
import com.tracktosearch.data.auth.AuthManager
import com.tracktosearch.data.auth.AuthState
import com.tracktosearch.data.local.DoubanAuthStorage
import com.tracktosearch.data.remote.trakt.TraktConnectionState
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test

class SessionModeManagerTest {
    private val authManager = mockk<AuthManager>()
    private val doubanAuthStorage = mockk<DoubanAuthStorage>()
    private val authState = MutableStateFlow(AuthState.UNAUTHORIZED)
    private val doubanLoggedIn = MutableStateFlow(false)

    private fun createManager(): SessionModeManager {
        every { authManager.authState } returns authState
        every { doubanAuthStorage.isLoggedIn } returns doubanLoggedIn
        return SessionModeManager(authManager, doubanAuthStorage)
    }

    @Test
    fun `会话模式由网关和平台登录态派生`() = runTest {
        val manager = createManager()

        assertThat(manager.sessionMode.first()).isEqualTo(SessionMode.UNAUTHORIZED)

        authState.value = AuthState.AUTHORIZED
        assertThat(manager.sessionMode.first()).isEqualTo(SessionMode.GUEST)

        doubanLoggedIn.value = true
        assertThat(manager.sessionMode.first()).isEqualTo(SessionMode.DOUBAN)

        manager.setTraktConnectionState(TraktConnectionState.CONNECTED)
        assertThat(manager.sessionMode.first()).isEqualTo(SessionMode.TRAKT)
    }

    @Test
    fun `网关失效优先回到未授权态`() = runTest {
        authState.value = AuthState.AUTHORIZED
        doubanLoggedIn.value = true
        val manager = createManager()
        manager.setTraktConnectionState(TraktConnectionState.CONNECTED)

        authState.value = AuthState.EXPIRED

        assertThat(manager.sessionMode.first()).isEqualTo(SessionMode.UNAUTHORIZED)
    }
}
