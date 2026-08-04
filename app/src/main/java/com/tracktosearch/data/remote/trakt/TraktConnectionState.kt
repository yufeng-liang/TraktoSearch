package com.tracktosearch.data.remote.trakt

/** Trakt 独立于网关授权的连接状态。 */
enum class TraktConnectionState {
    CHECKING,
    CONNECTED,
    DISCONNECTED
}

/** 启动时 Trakt 网络检查的结果，UNKNOWN 表示不能确认登录已失效。 */
enum class TraktConnectionCheckResult {
    CONNECTED,
    DISCONNECTED,
    UNKNOWN
}
