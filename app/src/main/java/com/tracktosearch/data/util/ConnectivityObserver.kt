package com.tracktosearch.data.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
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
 * 重试拦截器还会额外 sleep 重试，白白消耗电量与线程。本观察者用
 * registerDefaultNetworkCallback 持续跟踪在线状态与 DataSaver 开关，
 * 拦截器读取 StateFlow 即可零阻塞快速决策。
 */
@Singleton
class ConnectivityObserver @Inject constructor(
    @ApplicationContext context: Context
) {
    enum class NetworkStatus { ONLINE, OFFLINE }

    private val connectivityManager =
        context.getSystemService(ConnectivityManager::class.java)

    private val _status = MutableStateFlow(currentStatus())
    val status: StateFlow<NetworkStatus> = _status.asStateFlow()

    private val _dataSaverEnabled = MutableStateFlow(isDataSaverEnabled())
    val dataSaverEnabled: StateFlow<Boolean> = _dataSaverEnabled.asStateFlow()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = refresh()
        override fun onLost(network: Network) = refresh()
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = refresh()
        override fun onUnavailable() = refresh()
    }

    init {
        try {
            connectivityManager?.registerDefaultNetworkCallback(callback)
        } catch (_: Exception) {
            // 注册失败时保持初始状态（按 activeNetwork 实测），不阻断应用启动
        }
    }

    private fun currentStatus(): NetworkStatus =
        if (isOnline()) NetworkStatus.ONLINE else NetworkStatus.OFFLINE

    private fun refresh() {
        _status.value = currentStatus()
        _dataSaverEnabled.value = isDataSaverEnabled()
    }

    private fun isOnline(): Boolean {
        val network = connectivityManager?.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun isDataSaverEnabled(): Boolean {
        return connectivityManager?.restrictBackgroundStatus ==
            ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED
    }
}
