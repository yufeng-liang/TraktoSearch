package com.tracktosearch.data.remote.trakt

/** Trakt 独立于网关授权的连接状态。 */
enum class TraktConnectionState {
    CHECKING,
    CONNECTED,
    DISCONNECTED
}
