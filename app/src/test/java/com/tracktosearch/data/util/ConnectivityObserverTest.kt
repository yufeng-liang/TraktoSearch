package com.tracktosearch.data.util

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.google.common.truth.Truth.assertThat
import com.tracktosearch.data.util.ConnectivityObserver.NetworkStatus
import com.tracktosearch.di.NetworkStatusInterceptor
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

/**
 * 误报回归：手机网络连通却提示离线。
 *
 * 两个来源都在这里锁死——
 * 1. 判定要求 NET_CAPABILITY_VALIDATED（系统探测结果），探测缺失就误判离线；
 * 2. 在回调里做同步查询，切网竞态把瞬时抖动放大成离线。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class ConnectivityObserverTest {
    private val context = mockk<Context>()
    private val manager = mockk<ConnectivityManager>()
    private val network = mockk<Network>()
    private val callback = slot<ConnectivityManager.NetworkCallback>()

    @Before
    fun setUp() {
        every { context.getSystemService(ConnectivityManager::class.java) } returns manager
        every { manager.activeNetwork } returns network
        every { manager.getNetworkCapabilities(network) } returns capabilities(validated = true)
        every { manager.restrictBackgroundStatus } returns
            ConnectivityManager.RESTRICT_BACKGROUND_STATUS_DISABLED
        every { manager.registerDefaultNetworkCallback(capture(callback)) } returns Unit
    }

    @Test
    fun validatedNetworkIsOnline() {
        assertThat(ConnectivityObserver(context).status.value).isEqualTo(NetworkStatus.ONLINE)
    }

    @Test
    fun internetNetworkWithoutSystemValidationIsOnline() {
        every { manager.getNetworkCapabilities(network) } returns capabilities(validated = false)

        assertThat(ConnectivityObserver(context).status.value).isEqualTo(NetworkStatus.ONLINE)
    }

    @Test
    fun noDefaultNetworkIsOffline() {
        every { manager.activeNetwork } returns null

        assertThat(ConnectivityObserver(context).status.value).isEqualTo(NetworkStatus.OFFLINE)
    }

    @Test
    fun networkWithoutInternetCapabilityIsOffline() {
        every { manager.getNetworkCapabilities(network) } returns capabilities(internet = false)

        assertThat(ConnectivityObserver(context).status.value).isEqualTo(NetworkStatus.OFFLINE)
    }

    @Test
    fun capabilitiesCallbackRestoresOnlineEvenWhenSynchronousSnapshotIsStillEmpty() {
        every { manager.activeNetwork } returns null
        val observer = ConnectivityObserver(context)

        callback.captured.onAvailable(network)
        callback.captured.onCapabilitiesChanged(network, capabilities(validated = true))

        assertThat(observer.status.value).isEqualTo(NetworkStatus.ONLINE)
    }

    @Test
    fun losingSystemValidationDoesNotMakeInternetNetworkOffline() {
        val observer = ConnectivityObserver(context)
        val unvalidated = capabilities(validated = false)
        every { manager.getNetworkCapabilities(network) } returns unvalidated

        callback.captured.onAvailable(network)
        callback.captured.onCapabilitiesChanged(network, unvalidated)

        assertThat(observer.status.value).isEqualTo(NetworkStatus.ONLINE)
    }

    @Test
    fun losingCurrentNetworkIsOfflineEvenWhenSynchronousSnapshotIsStale() {
        val observer = ConnectivityObserver(context)
        callback.captured.onAvailable(network)
        callback.captured.onCapabilitiesChanged(network, capabilities(validated = true))

        // 同步查询仍返回旧网络（典型的陈旧快照），状态必须跟着 onLost 走。
        callback.captured.onLost(network)

        assertThat(observer.status.value).isEqualTo(NetworkStatus.OFFLINE)
    }

    @Test
    fun lateLostCallbackForPreviousNetworkDoesNotDisconnectReplacement() {
        val observer = ConnectivityObserver(context)
        val replacement = mockk<Network>()
        callback.captured.onAvailable(network)
        every { manager.activeNetwork } returns replacement
        every { manager.getNetworkCapabilities(replacement) } returns capabilities(validated = true)
        callback.captured.onAvailable(replacement)
        callback.captured.onCapabilitiesChanged(replacement, capabilities(validated = true))
        // 切网期间同步查询可以短暂为空，旧网络的丢失事件不能覆盖新网络。
        every { manager.activeNetwork } returns null

        callback.captured.onLost(network)

        assertThat(observer.status.value).isEqualTo(NetworkStatus.ONLINE)
    }

    @Test
    fun losingInternetCapabilityUsesCallbackInsteadOfStaleCapabilities() {
        val observer = ConnectivityObserver(context)
        callback.captured.onAvailable(network)

        callback.captured.onCapabilitiesChanged(network, capabilities(internet = false))

        assertThat(observer.status.value).isEqualTo(NetworkStatus.OFFLINE)
    }

    @Test
    fun unvalidatedInternetNetworkDoesNotBlockHttpRequest() {
        every { manager.getNetworkCapabilities(network) } returns capabilities(validated = false)
        val observer = ConnectivityObserver(context)
        val request = Request.Builder().url("https://example.org/").build()
        val response = mockk<Response>()
        val chain = mockk<Interceptor.Chain>()
        every { chain.request() } returns request
        every { chain.proceed(request) } returns response

        assertThat(NetworkStatusInterceptor(observer).intercept(chain)).isSameInstanceAs(response)
        verify(exactly = 1) { chain.proceed(request) }
    }

    @Test(expected = IOException::class)
    fun disconnectedNetworkStillFailsHttpRequestImmediately() {
        every { manager.activeNetwork } returns null
        val observer = ConnectivityObserver(context)
        val chain = mockk<Interceptor.Chain>()

        try {
            NetworkStatusInterceptor(observer).intercept(chain)
        } finally {
            verify(exactly = 0) { chain.proceed(any()) }
        }
    }

    @Test
    fun networkCallbacksStillRefreshDataSaverState() {
        val observer = ConnectivityObserver(context)
        every { manager.restrictBackgroundStatus } returns
            ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED

        callback.captured.onAvailable(network)
        callback.captured.onCapabilitiesChanged(network, capabilities(validated = true))

        assertThat(observer.dataSaverEnabled.value).isTrue()
    }

    private fun capabilities(
        internet: Boolean = true,
        validated: Boolean = false,
    ): NetworkCapabilities = mockk {
        every { hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) } returns internet
        every { hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) } returns validated
    }
}
