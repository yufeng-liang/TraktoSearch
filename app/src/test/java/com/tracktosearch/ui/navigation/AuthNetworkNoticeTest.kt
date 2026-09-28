package com.tracktosearch.ui.navigation

import android.app.Application
import android.content.Context
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.tracktosearch.R
import com.tracktosearch.data.auth.AuthState
import com.tracktosearch.data.util.ConnectivityObserver.NetworkStatus
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 授权提示回归：网络正常时不能收到「离线」文案。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class AuthNetworkNoticeTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val authState = MutableStateFlow(AuthState.OFFLINE)
    private val networkStatus = MutableStateFlow(NetworkStatus.ONLINE)
    private val snackbarHostState = SnackbarHostState()

    @Test
    fun onlineDeviceWithAuthorizationFailureDoesNotClaimToBeOffline() {
        showNotice()
        advanceTimeBy(3_100)

        val context = ApplicationProvider.getApplicationContext<Context>()
        val message = snackbarHostState.currentSnackbarData?.visuals?.message
        assertThat(message).isEqualTo(context.getString(R.string.auth_verify_unavailable))
        assertThat(message).isNotEqualTo(context.getString(R.string.auth_offline_mode))
    }

    @Test
    fun disconnectedDeviceDoesNotDuplicateTheOfflineBanner() {
        networkStatus.value = NetworkStatus.OFFLINE
        showNotice()
        advanceTimeBy(3_100)

        assertThat(snackbarHostState.currentSnackbarData).isNull()
    }

    @Test
    fun transientAuthorizationFailureDoesNotShowNotice() {
        showNotice()
        advanceTimeBy(1_500)
        composeRule.runOnIdle { authState.value = AuthState.AUTHORIZED }
        advanceTimeBy(3_100)

        assertThat(snackbarHostState.currentSnackbarData).isNull()
    }

    @Test
    fun networkLossDismissesAnExistingAuthorizationNotice() {
        showNotice()
        advanceTimeBy(3_100)
        assertThat(snackbarHostState.currentSnackbarData).isNotNull()

        composeRule.runOnIdle { networkStatus.value = NetworkStatus.OFFLINE }
        advanceTimeBy(100)

        assertThat(snackbarHostState.currentSnackbarData).isNull()
    }

    @Test
    fun networkRecoveryRechecksStillFailingAuthorization() {
        networkStatus.value = NetworkStatus.OFFLINE
        showNotice()
        advanceTimeBy(3_100)
        assertThat(snackbarHostState.currentSnackbarData).isNull()

        composeRule.runOnIdle { networkStatus.value = NetworkStatus.ONLINE }
        advanceTimeBy(3_100)

        assertThat(snackbarHostState.currentSnackbarData).isNotNull()
    }

    @Test
    fun authorizationRecoveryDismissesAnExistingNotice() {
        showNotice()
        advanceTimeBy(3_100)
        assertThat(snackbarHostState.currentSnackbarData).isNotNull()

        composeRule.runOnIdle { authState.value = AuthState.AUTHORIZED }
        advanceTimeBy(100)

        assertThat(snackbarHostState.currentSnackbarData).isNull()
    }

    private fun showNotice() {
        composeRule.setContent {
            AuthNetworkNotice(
                authState = authState,
                networkStatus = networkStatus,
                snackbarHostState = snackbarHostState,
            )
        }
        composeRule.mainClock.autoAdvance = false
    }

    private fun advanceTimeBy(milliseconds: Long) {
        composeRule.mainClock.advanceTimeBy(milliseconds)
        composeRule.waitForIdle()
    }
}
