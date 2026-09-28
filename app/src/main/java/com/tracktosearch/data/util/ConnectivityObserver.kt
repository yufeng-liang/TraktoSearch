package com.tracktosearch.data.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 全局网络状态观察者：供网络层快速失败与重试策略感知离线/省流量状态。
 *
 * 背景：此前网络层无任何网络感知，弱网/无网时请求照发并按 10s connect timeout 空等，
 * 重试拦截器还会额外 sleep 重试，白白消耗电量与线程。
 *
 * 判定口径只有一条：默认网络是否具备 NET_CAPABILITY_INTERNET。
 * 刻意不要求 NET_CAPABILITY_VALIDATED —— 它表示系统连通性探测（captive portal 检测）
 * 最近一次成功过，探测端点被拦截、探测超时或刚切网时都会缺失，而此时应用自己的请求
 * 完全可以通。拿它当「离线」判据，就会出现网络明明连通、App 却提示离线的误报。
 * 真正连不通时请求会按普通网络错误失败，UI 走既有失败分支，不需要在这里提前下结论。
 */
@Singleton
class ConnectivityObserver @Inject constructor(
    @ApplicationContext context: Context
) {
    enum class NetworkStatus { ONLINE, OFFLINE }

    private val connectivityManager =
        context.getSystemService(ConnectivityManager::class.java)

    /**
     * 默认网络与其能力一律由回调维护，不在回调里做同步查询：
     * 官方文档明确警告同步查询存在竞态（切网瞬间 activeNetwork 可能为空或仍是旧网络），
     * 会把一次瞬时抖动放大成「离线」。
     */
    private var currentNetwork: Network? = connectivityManager?.activeNetwork
    private var currentCapabilities: NetworkCapabilities? =
        currentNetwork?.let { connectivityManager?.getNetworkCapabilities(it) }

    private val _status = MutableStateFlow(statusOf(currentCapabilities))
    val status: StateFlow<NetworkStatus> = _status.asStateFlow()

    private val _dataSaverEnabled = MutableStateFlow(isDataSaverEnabled())
    val dataSaverEnabled: StateFlow<Boolean> = _dataSaverEnabled.asStateFlow()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        // 只记住网络，不在这里刷新状态：能力回调紧随其后按序到达（Android 8.0+ 有保证），
        // 提前刷新只会让中间态短暂落到 OFFLINE。
        override fun onAvailable(network: Network) {
            currentNetwork = network
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            if (network != currentNetwork) return
            currentCapabilities = caps
            refresh()
        }

        override fun onLost(network: Network) {
            // 旧默认网络的丢失回调可能晚于新网络的 onAvailable 到达；只处理当前网络，
            // 否则切网会把已经恢复的在线状态又按回离线。
            if (network != currentNetwork) return
            currentNetwork = null
            currentCapabilities = null
            refresh()
        }

        override fun onUnavailable() {
            currentNetwork = null
            currentCapabilities = null
            refresh()
        }
    }

    init {
        try {
            connectivityManager?.registerDefaultNetworkCallback(callback)
        } catch (_: Exception) {
            // 注册失败时保持初始状态（按 activeNetwork 实测），不阻断应用启动
        }
    }

    private fun statusOf(capabilities: NetworkCapabilities?): NetworkStatus =
        if (capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true) {
            NetworkStatus.ONLINE
        } else {
            NetworkStatus.OFFLINE
        }

    private fun refresh() {
        _status.value = statusOf(currentCapabilities)
        _dataSaverEnabled.value = isDataSaverEnabled()
    }

    private fun isDataSaverEnabled(): Boolean {
        return connectivityManager?.restrictBackgroundStatus ==
            ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED
    }
}
