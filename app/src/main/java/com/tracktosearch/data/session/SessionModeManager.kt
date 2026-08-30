package com.tracktosearch.data.session

import com.tracktosearch.data.auth.AuthManager
import com.tracktosearch.data.auth.hasGatewayAccess
import com.tracktosearch.data.local.DoubanAuthStorage
import com.tracktosearch.data.remote.trakt.TraktConnectionState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 会话模式。
 *
 * - [UNAUTHORIZED]: 未激活网关（卡在登录页）
 * - [GUEST]: 激活网关，未连 trakt，未登录豆瓣（访客模式）
 * - [DOUBAN]: 激活网关，未连 trakt，已登录豆瓣（豆瓣独立模式，watchlist 走本地表）
 * - [TRAKT]: 已连 trakt（trakt 优先，豆瓣数据作为同步源）
 */
enum class SessionMode {
    UNAUTHORIZED,
    GUEST,
    DOUBAN,
    TRAKT
}

/**
 * 统一会话模式判断入口。
 *
 * 组合三个状态源:
 * - 网关激活态: [AuthManager.authState]（持久化）
 * - 豆瓣登录态: [DoubanAuthStorage.isLoggedIn]（持久化 StateFlow）
 * - Trakt 连接态: 由 UI 层网络校验后写入（非持久化）
 *
 * Trakt 连接态用 Boolean 内部状态参与模式判定, CHECKING 期间保留上次结果避免抖动;
 * 三态 [traktConnectionState] 仍暴露给 UI 做 loading 展示。
 */
@Singleton
class SessionModeManager @Inject constructor(
    private val authManager: AuthManager,
    private val doubanAuthStorage: DoubanAuthStorage
) {
    // Trakt 连接三态（含 CHECKING），供 UI 展示 loading
    private val _traktConnectionState = MutableStateFlow(TraktConnectionState.DISCONNECTED)
    val traktConnectionState: StateFlow<TraktConnectionState> = _traktConnectionState.asStateFlow()

    // Trakt 是否已连接的最终结果（CHECKING 期间保留上次，避免模式抖动）
    private val _traktConnected = MutableStateFlow(false)
    val traktConnected: StateFlow<Boolean> = _traktConnected.asStateFlow()

    /**
     * 当前会话模式。
     *
     * 判定优先级:
     * 1. 未激活网关 → UNAUTHORIZED
     * 2. 已连 trakt → TRAKT（trakt 优先，即使已登录豆瓣也用 trakt 数据源）
     * 3. 已登录豆瓣 → DOUBAN（豆瓣独立模式）
     * 4. 否则 → GUEST
     */
    val sessionMode: Flow<SessionMode> = combine(
        authManager.authState,
        doubanAuthStorage.isLoggedIn,
        _traktConnected
    ) { authState, doubanLoggedIn, traktConnected ->
        val isAuthorized = authState.hasGatewayAccess()
        when {
            !isAuthorized -> SessionMode.UNAUTHORIZED
            traktConnected -> SessionMode.TRAKT
            doubanLoggedIn -> SessionMode.DOUBAN
            else -> SessionMode.GUEST
        }
    }.distinctUntilChanged()

    /** 便捷: 是否处于豆瓣独立模式（激活网关 + 已登录豆瓣 + 未连 trakt） */
    val isDoubanMode: Flow<Boolean> = sessionMode.map { it == SessionMode.DOUBAN }.distinctUntilChanged()

    /** 由 UI 层写入 Trakt 连接态（网络校验结果） */
    fun setTraktConnectionState(state: TraktConnectionState) {
        _traktConnectionState.value = state
        when (state) {
            TraktConnectionState.CONNECTED -> _traktConnected.value = true
            TraktConnectionState.DISCONNECTED -> _traktConnected.value = false
            TraktConnectionState.CHECKING -> Unit // 保留上次结果，避免 CHECKING 期间模式抖动
        }
    }
}
