package com.tracktosearch.data.auth

import com.tracktosearch.data.ai.AiStorage
import com.tracktosearch.data.local.TokenStorage
import com.tracktosearch.data.repository.TraktRepository
import com.tracktosearch.data.util.StartupTrace
import com.tracktosearch.BuildConfig
import android.os.Build
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.decodeFromString
import java.util.UUID
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * 授权状态机
 *
 * 状态：
 * - UNAUTHORIZED：未授权（无有效令牌）
 * - AUTHORIZED：已授权（令牌有效）
 * - OFFLINE：离线宽限中（网络异常，使用本地缓存能力）
 * - EXPIRED：宽限期已过，必须重新联网
 */
enum class AuthState {
    UNAUTHORIZED,
    AUTHORIZED,
    OFFLINE,
    EXPIRED
}

/**
 * 授权管理器
 * - 管理激活、令牌刷新、每日校验、离线宽限
 * - 持久化授权状态（最后在线时间、宽限期）
 */
@Singleton
class AuthManager @Inject constructor(
    private val authApiService: AuthApiService,
    private val deviceKeyManager: DeviceKeyManager,
    private val deviceContinuityManager: DeviceContinuityManager,
    private val tokenStorage: TokenStorage,
    private val json: Json,
    private val aiStorage: AiStorage,
    private val traktRepositoryProvider: Provider<TraktRepository>
) {
    companion object {
        // 冷启动授权等待硬上限：网络正常时 challenge+refresh 刷新链约 0.3-1s，2s 足够覆盖；
        // 超时走既有 OFFLINE 宽限路径（3 天）仍进主界面，不改变授权失败/离线场景行为。
        const val STARTUP_AUTH_TIMEOUT_MS = 2_000L

        // OFFLINE 恢复退避序列（毫秒）。瞬时抖动（切网/DNS/代理切换）通常数秒内结束，
        // 5s 首跳多数能赶在离线横幅（OFFLINE 稳定 3s 才弹）被察觉前恢复；
        // 序列走完仍失败则停手，交给既有 15 分钟周期 worker 与回前台校验兜底。
        internal val OFFLINE_RECOVERY_DELAYS_MS = longArrayOf(
            5_000L, 15_000L, 30_000L, 60_000L, 120_000L, 300_000L, 600_000L
        )
    }

    private val refreshCoordinator = AuthRefreshCoordinator()
    private val initializationMutex = Mutex()
    private val startupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val startupJobMutex = Mutex()
    private var startupInitializationJob: Job? = null

    private val _authState = MutableStateFlow(AuthState.UNAUTHORIZED)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    // OFFLINE 宽限的快速恢复：状态一进 OFFLINE 就按退避序列重试授权校验，状态离开
    // OFFLINE（恢复 AUTHORIZED 或转入 UNAUTHORIZED/EXPIRED）即取消。背景：运行中校验只挂在
    // 15 分钟周期 worker 上且 worker 失败也不 retry，一次瞬时抖动就会让网通着的用户
    // 顶着「离线模式」横幅最长半个周期（onResume 校验只覆盖切后台再回来的场景）。
    @Volatile
    internal var offlineRecoveryDelaysMs: LongArray = OFFLINE_RECOVERY_DELAYS_MS
    @Volatile
    private var offlineRecoveryJob: Job? = null

    init {
        startupScope.launch {
            _authState.collect { state ->
                if (state == AuthState.OFFLINE) startOfflineRecovery() else stopOfflineRecovery()
            }
        }
    }

    private val _recoveryFailure = MutableStateFlow<String?>(null)
    val recoveryFailure: StateFlow<String?> = _recoveryFailure.asStateFlow()

    // 朋友昵称（激活/校验时由网关返回，未授权或离线时为 null）
    private val _nickname = MutableStateFlow<String?>(null)
    val nickname: StateFlow<String?> = _nickname.asStateFlow()

    // AI 网关按 friendId 隔离缓存与配额；只由授权校验响应写入。
    private val _friendId = MutableStateFlow<String?>(null)
    val friendId: StateFlow<String?> = _friendId.asStateFlow()

    // 设备 ID（激活后缓存）。@Volatile：WorkManager 线程写入对主线程可见
    @Volatile
    private var deviceId: String? = null

    // 下次校验时间（Unix 秒）。@Volatile：跨线程可见，避免误判 EXPIRED
    @Volatile
    private var nextCheckAt: Long = 0L

    // 最后在线时间（Unix 秒）。@Volatile：跨线程可见，避免误判 UNAUTHORIZED
    @Volatile
    private var lastOnlineAt: Long = 0L

    // 离线宽限期（3 天 = 259200 秒）
    private val offlineGracePeriod = 3 * 24 * 60 * 60L

    /**
     * 激活（首次使用或重新绑定）
     */
    suspend fun activate(inviteCode: String, deviceName: String, appVersion: String, packageName: String): Result<ActivateResponse> {
        return try {
            val publicKey = deviceKeyManager.getPublicKeyBase64()
            val request = ActivateRequest(
                inviteCode = inviteCode,
                publicKey = publicKey,
                deviceName = deviceName,
                appVersion = appVersion,
                packageName = packageName,
                androidId = deviceContinuityManager.getAndroidId(),
            )
            val response = authApiService.activate(request)
            if (response.isSuccessful) {
                val body = response.body()?.data ?: return Result.failure(Exception(response.errorMessage("Empty response")))
                val now = System.currentTimeMillis() / 1000
                tokenStorage.saveSession(
                    accessToken = body.accessToken,
                    refreshToken = body.refreshToken,
                    expiresIn = body.accessExpiresAt - now,
                    deviceId = body.deviceId,
                    lastOnlineAt = now,
                    nextCheckAt = body.nextCheckAt,
                )
                clearAiIdentity()
                deviceId = body.deviceId
                nextCheckAt = body.nextCheckAt
                lastOnlineAt = now
                _authState.value = AuthState.AUTHORIZED
                // 取票成功那一刻票面就要印昵称，此时每日 check 还没跑；
                // 空串不写入，避免覆盖掉上一次校验拿到的旧昵称。
                body.nickname.takeIf { it.isNotBlank() }?.let { _nickname.value = it }
                Result.success(body)
            } else {
                response.failureWith("Activate failed: ${response.code()}")
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 每日校验（启动时 + 每 24 小时）
     */
    suspend fun check(): Result<CheckResponse> = check(allowRefresh = true)

    private suspend fun check(allowRefresh: Boolean): Result<CheckResponse> {
        return try {
            val failedAccessToken = tokenStorage.getCachedAccessToken().orEmpty()
            val response = authApiService.check(CheckRequest(deviceContinuityManager.getAndroidId()))
            if (response.isSuccessful) {
                val body = response.body()?.data ?: return Result.failure(Exception(response.errorMessage("Empty response")))
                deviceId = body.deviceId.ifBlank { deviceId }
                lastOnlineAt = System.currentTimeMillis() / 1000
                nextCheckAt = body.nextCheckAt
                val did = deviceId
                if (did != null) tokenStorage.saveSessionMetadata(did, lastOnlineAt, nextCheckAt)
                updateFriendIdentity(body.friendId)
                _authState.value = AuthState.AUTHORIZED
                _nickname.value = body.nickname.takeIf { it.isNotBlank() }
                Result.success(body)
            } else if (response.code() == 401) {
                response.closeQuietly()
                if (allowRefresh) {
                    // 令牌失效时仅允许刷新一次；刷新后的重试仍返回 401 时直接失败，避免递归刷新。
                    refreshAfterCheck(failedAccessToken)
                } else {
                    Result.failure(Exception("Check failed after refresh: 401"))
                }
            } else if (response.code() == 403) {
                invalidateSession()
                response.failureWith("Check failed: ${response.code()}")
            } else {
                response.failureWith("Check failed: ${response.code()}")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 网络异常 → 进入离线宽限
            handleOffline()
        }
    }

    /**
     * 刷新令牌
     */
    suspend fun refresh(): Result<RefreshResponse> = refreshCoordinator.withLock {
        refreshLocked()
    }

    /**
     * 处理因旧 access token 失败的刷新请求。
     * 如果其他并发请求已经完成刷新，直接复用缓存中的新 token。
     */
    suspend fun refreshIfNeeded(
        failedAccessToken: String,
        allowSilentRecovery: Boolean = true,
    ): Boolean =
        refreshCoordinator.refreshIfNeeded(
            failedAccessToken = failedAccessToken,
            currentAccessToken = tokenStorage::getCachedAccessToken
        ) {
            refreshLocked(allowSilentRecovery).isSuccess
        }

    private suspend fun refreshLocked(allowSilentRecovery: Boolean = true): Result<RefreshResponse> {
        return try {
            val currentDeviceId = deviceId ?: return Result.failure(Exception("No device ID"))
            val refreshToken = tokenStorage.getRefreshToken() ?: return Result.failure(Exception("No refresh token"))
            val attemptId = tokenStorage.getRefreshAttemptId()
                ?.takeIf { it.isNotBlank() }
                ?: UUID.randomUUID().toString().also { tokenStorage.saveRefreshAttemptId(it) }

            // 获取挑战码
            val challengeResponse = authApiService.challenge(ChallengeRequest(currentDeviceId))
            if (!challengeResponse.isSuccessful) {
                if (challengeResponse.code() == 401 || challengeResponse.code() == 403) {
                    invalidateSession()
                }
                return challengeResponse.failureWith("Challenge failed: ${challengeResponse.code()}")
            }
                val challenge = challengeResponse.body()?.data?.nonce ?: return Result.failure(Exception("No nonce"))

            // 用 Keystore 私钥签名
            val signature = deviceKeyManager.sign(challenge.toByteArray())
            val signatureBase64 = android.util.Base64.encodeToString(signature, android.util.Base64.NO_WRAP)

            // 刷新
            val refreshResponse = authApiService.refresh(
                RefreshRequest(
                    deviceId = currentDeviceId,
                    refreshToken = refreshToken,
                    nonce = challenge,
                    signature = signatureBase64,
                    attemptId = attemptId,
                )
            )
            if (refreshResponse.isSuccessful) {
                val body = refreshResponse.body()?.data ?: return Result.failure(Exception(refreshResponse.errorMessage("Empty response")))
                val now = System.currentTimeMillis() / 1000
                tokenStorage.saveSession(
                    accessToken = body.accessToken,
                    refreshToken = body.refreshToken,
                    expiresIn = body.accessExpiresAt - now,
                    deviceId = currentDeviceId,
                    lastOnlineAt = now,
                    nextCheckAt = nextCheckAt,
                )
                lastOnlineAt = now
                _authState.value = AuthState.AUTHORIZED
                Result.success(body)
            } else if (refreshResponse.code() == 401 || refreshResponse.code() == 403) {
                // 服务端已撤销当前刷新会话时，release 先尝试用设备连续性自动恢复。
                invalidateSession()
                refreshResponse.closeQuietly()
                if (allowSilentRecovery) {
                    recoverAfterRefreshFailure()
                } else {
                    Result.failure(Exception("Refresh failed, re-authorization required"))
                }
            } else {
                refreshResponse.failureWith("Refresh failed: ${refreshResponse.code()}")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleOffline()
        }
    }

    /**
     * 处理网络异常（离线宽限）
     */
    private suspend fun refreshAfterCheck(failedAccessToken: String): Result<CheckResponse> {
        val refreshed = refreshCoordinator.refreshIfNeeded(
            failedAccessToken = failedAccessToken,
            currentAccessToken = tokenStorage::getCachedAccessToken,
        ) {
            refreshLocked().isSuccess
        }
        return if (refreshed) {
            // 刷新成功后带新 token 重发一次 check，走正常身份/排期更新路径。
            // 不能手工构造 CheckResponse：friendId 空串、nextCheckAt 旧值会跳过
            // updateFriendIdentity/saveSessionMetadata，服务端账号切换时旧 friendId
            // 的 AI 缓存清理被无限期延迟。新 token 仍 401 时 refreshIfNeeded 判定
            // 重试仍返回 401 时直接失败，不再进入第二轮刷新。
            check(allowRefresh = false)
        } else {
            Result.failure(Exception("Refresh failed"))
        }
    }

    private fun <T> handleOffline(): Result<T> {
        val now = System.currentTimeMillis() / 1000
        val offlineDuration = now - lastOnlineAt
        _authState.value = if (offlineDuration > offlineGracePeriod) {
            AuthState.EXPIRED
        } else {
            AuthState.OFFLINE
        }
        return Result.failure(Exception("Network error, offline grace: ${offlineGracePeriod - offlineDuration}s remaining"))
    }

    /** OFFLINE 期间的指数退避快速重试：抖动结束即恢复授权，不必等周期 worker。 */
    private fun startOfflineRecovery() {
        if (offlineRecoveryJob?.isActive == true) return
        offlineRecoveryJob = startupScope.launch {
            for (delayMs in offlineRecoveryDelaysMs) {
                delay(delayMs)
                if (_authState.value != AuthState.OFFLINE) return@launch
                try {
                    // forceNetworkCheck=true 绕过 nextCheckAt 缓存：token 有效走 check，
                    // 已过期走 refresh 轮换，两条路成功都会回到 AUTHORIZED
                    initialize(forceNetworkCheck = true)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // initialize 内部已按结果收敛状态；吞掉保证退避循环继续推进
                }
                if (_authState.value != AuthState.OFFLINE) return@launch
            }
        }
    }

    private fun stopOfflineRecovery() {
        offlineRecoveryJob?.cancel()
        offlineRecoveryJob = null
    }

    /** 启动时恢复本地会话并按需向网关校验。 */
    suspend fun initialize(forceNetworkCheck: Boolean = false) {
        initializationMutex.withLock { initializeLocked(forceNetworkCheck) }
    }

    /**
     * 冷启动本地快速初始化：只读本地会话并按「是否曾激活」做乐观授权判定，
     * 不发起任何网络请求。供 MainActivity 组合门使用，让首屏不被网络校验拖住。
     *
     * - 有本地 refresh 会话 → 乐观置 AUTHORIZED（真实状态由完整初始化/校验在后台收敛；
     *   沿用 initializeForStartup 超时路径的乐观语义，离线宽限仍可进主界面）
     * - 从未激活 → UNAUTHORIZED，走登录页
     *
     * @return 是否已激活（可作为 hasGatewayAccess 的本地近似判据）
     */
    suspend fun initializeLocalOnly(): Boolean {
        loadCachedSession()
        val hasLocalSession = !deviceId.isNullOrBlank() &&
            !tokenStorage.getRefreshToken().isNullOrBlank()
        _authState.value = if (hasLocalSession) AuthState.AUTHORIZED else AuthState.UNAUTHORIZED
        return hasLocalSession
    }

    /**
     * 启动授权校验的硬上限，避免网关连接异常时系统 Splash 无限等待。
     * 超时仍沿用已有离线宽限判定，不会把已激活用户直接送回登录页。
     */
    suspend fun initializeForStartup(timeoutMillis: Long = STARTUP_AUTH_TIMEOUT_MS) {
        val deadlineNanos = System.nanoTime() + timeoutMillis.coerceAtLeast(0L) * 1_000_000L
        val cacheLoaded = withTimeoutOrNull(timeoutMillis) {
            loadCachedSession()
            true
        } == true
        if (!cacheLoaded) {
            StartupTrace.mark("auth.initialize.timeout", "timeoutMs=$timeoutMillis;phase=cache")
            handleOffline<Unit>()
            return
        }
        val initializationJob = startupJobMutex.withLock {
            startupInitializationJob?.takeIf { it.isActive }
                ?: startupScope.launch { initialize() }.also { startupInitializationJob = it }
        }
        val remainingMillis = ((deadlineNanos - System.nanoTime()) / 1_000_000L).coerceAtLeast(0L)
        val completed = withTimeoutOrNull(remainingMillis) {
            initializationJob.join()
            true
        } == true
        if (!completed) {
            StartupTrace.mark("auth.initialize.timeout", "timeoutMs=$timeoutMillis")
            // 不取消后台刷新：服务端可能已经完成一次性 refresh token 轮换。
            // 本地会话有效时，网络慢≠离线：乐观置为已授权进入主界面，由后台 job 收敛真实状态
            // （成功→AUTHORIZED，失败→OFFLINE/EXPIRED），避免启动期网络响应慢被误报离线提示。
            val hasLocalSession = !deviceId.isNullOrBlank() &&
                !tokenStorage.getRefreshToken().isNullOrBlank()
            if (!hasLocalSession) {
                // 从未激活过的设备（本地无已保存会话）保持 UNAUTHORIZED 走登录页：
                // 超时离线宽限放行主界面仅限曾激活过的设备，避免新用户被启动超时误判直进主界面
                _authState.value = AuthState.UNAUTHORIZED
            } else {
                _authState.value = AuthState.AUTHORIZED
            }
        }
    }

    private suspend fun loadCachedSession() {
        tokenStorage.ensureCacheLoaded()
        deviceId = tokenStorage.getCachedDeviceId()
        nextCheckAt = tokenStorage.getCachedNextCheckAt()
        lastOnlineAt = tokenStorage.getCachedLastOnlineAt()
        _friendId.value = readPersistedFriendId()
    }

    private suspend fun initializeLocked(forceNetworkCheck: Boolean) {
        loadCachedSession()
        if (!tokenStorage.isTokenValid()) {
            // access token 仅有 15 分钟寿命；只要 refresh session 仍在，就先轮换令牌。
            // 不能因为短期 access token 过期就丢弃已激活的设备会话，否则应用重启会错误回到邀请码页。
            val hasRefreshSession = !deviceId.isNullOrBlank() &&
                !tokenStorage.getRefreshToken().isNullOrBlank()
            if (hasRefreshSession) {
                // 多个初始化入口可能同时看到过期 token；刷新锁内复用已轮换的新 token。
                val failedAccessToken = tokenStorage.getCachedAccessToken().orEmpty()
                refreshCoordinator.refreshIfNeeded(
                    failedAccessToken = failedAccessToken,
                    currentAccessToken = tokenStorage::getCachedAccessToken
                ) {
                    refreshLocked(allowSilentRecovery = false).isSuccess
                }
            } else {
                _authState.value = AuthState.UNAUTHORIZED
                clearAiIdentity()
            }
            if (_authState.value == AuthState.UNAUTHORIZED) {
                recoverSilently()
            }
            if (_authState.value != AuthState.AUTHORIZED || !forceNetworkCheck) return
        }
        // 每日网关校验尚未到期时，沿用本地授权状态，避免冷启动被网络请求阻塞。
        if (!forceNetworkCheck && nextCheckAt > System.currentTimeMillis() / 1000) {
            _authState.value = AuthState.AUTHORIZED
            StartupTrace.mark("auth.initialize.cached_authorization", "nextCheckAt=$nextCheckAt")
            return
        }
        _authState.value = AuthState.AUTHORIZED
        check()
    }

    /**
     * 退出授权态（设备撤销后）
     */
    suspend fun deauthorize() {
        invalidateSession()
        deviceId = null
        nextCheckAt = 0L
    }

    /** 授权会话失效时同步清理 Trakt 私有数据，避免重新激活后沿用旧账号列表。 */
    private suspend fun invalidateSession() {
        tokenStorage.clearTokens()
        traktRepositoryProvider.get().clearTraktAccountCaches()
        clearAiIdentity()
        deviceId = null
        _authState.value = AuthState.UNAUTHORIZED
        _nickname.value = null
    }

    /** 从加密缓存恢复当前账号，并在服务端账号发生切换时清理旧账号数据。 */
    private suspend fun updateFriendIdentity(rawFriendId: String) {
        val nextFriendId = rawFriendId.trim().takeIf { it.isNotEmpty() }
        val previousFriendId = _friendId.value ?: readPersistedFriendId()
        if (previousFriendId != null && previousFriendId != nextFriendId) {
            clearFriendAiCache(previousFriendId)
        }
        if (nextFriendId != null) {
            persistFriendId(nextFriendId)
        } else {
            clearPersistedFriendId()
        }
        _friendId.value = nextFriendId
    }

    private suspend fun clearAiIdentity() {
        val currentFriendId = _friendId.value ?: readPersistedFriendId()
        if (currentFriendId != null) clearFriendAiCache(currentFriendId)
        clearPersistedFriendId()
        _friendId.value = null
    }

    private suspend fun clearFriendAiCache(friendId: String) {
        try {
            aiStorage.clearFriend(friendId)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // 缓存清理失败不应阻断令牌失效和重新授权流程。
        }
    }

    private suspend fun persistFriendId(friendId: String) {
        try {
            aiStorage.saveCurrentFriendId(friendId)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // 内存状态仍以本次在线校验为准，下一次启动会再次尝试恢复。
        }
    }

    private suspend fun readPersistedFriendId(): String? = try {
        aiStorage.readCurrentFriendId()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    private suspend fun clearPersistedFriendId() {
        try {
            aiStorage.clearCurrentFriendId()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // 允许授权状态机继续完成登出/撤销。
        }
    }

    /**
     * 静默恢复：按 androidId+公钥向网关申请重发会话（无需邀请码）。
     * 成功时写入会话并置 AUTHORIZED（状态置位在本方法内完成）；失败默认写入
     * [recoveryFailure] 供 UI 引导迁移邀请码，[emitFailure] 为 false 时
     * （激活撞码后的重试等已有专属错误提示的场景）只返回失败结果，不产生该副作用。
     * 须在挂起上下文调用；网络与签名在调用方协程执行，状态均经 StateFlow 写回，跨线程安全。
     */
    suspend fun recoverSilently(emitFailure: Boolean = true): Result<ActivateResponse> {
        _recoveryFailure.value = null
        if (BuildConfig.DEBUG) return Result.failure(Exception("RECOVERY_RELEASE_ONLY"))
        val androidId = deviceContinuityManager.getAndroidId()
            ?: return recoveryFailure("RECOVERY_ID_UNAVAILABLE", emitFailure)
        return try {
            val publicKey = deviceKeyManager.getPublicKeyBase64()
            val challengeResponse = authApiService.recoveryChallenge(
                RecoveryChallengeRequest(androidId, publicKey, BuildConfig.APPLICATION_ID)
            )
            if (!challengeResponse.isSuccessful) {
                return challengeResponse.failureWith("Recovery challenge failed: ${challengeResponse.code()}")
            }
            val nonce = challengeResponse.body()?.data?.nonce
                ?: return recoveryFailure("Recovery challenge is empty", emitFailure)
            val signature = Base64.encodeToString(
                deviceKeyManager.sign(nonce.toByteArray()),
                Base64.NO_WRAP,
            )
            val response = authApiService.recover(
                RecoveryRequest(
                    androidId = androidId,
                    publicKey = publicKey,
                    nonce = nonce,
                    signature = signature,
                    deviceName = "${Build.MANUFACTURER} ${Build.MODEL}",
                    appVersion = BuildConfig.VERSION_NAME,
                    packageName = BuildConfig.APPLICATION_ID,
                )
            )
            if (!response.isSuccessful) {
                return response.failureWith("Recovery failed: ${response.code()}")
            }
            val body = response.body()?.data
                ?: return recoveryFailure("Recovery response is empty")
            val now = System.currentTimeMillis() / 1000
            tokenStorage.saveSession(
                accessToken = body.accessToken,
                refreshToken = body.refreshToken,
                expiresIn = body.accessExpiresAt - now,
                deviceId = body.deviceId,
                lastOnlineAt = now,
                nextCheckAt = body.nextCheckAt,
            )
            deviceId = body.deviceId
            nextCheckAt = body.nextCheckAt
            lastOnlineAt = now
            _authState.value = AuthState.AUTHORIZED
            Result.success(body)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (emitFailure) _recoveryFailure.value = e.message ?: "RECOVERY_FAILED"
            Result.failure(e)
        }
    }

    private fun recoveryFailure(message: String, emitFailure: Boolean = true): Result<ActivateResponse> {
        if (emitFailure) _recoveryFailure.value = message
        return Result.failure(Exception(message))
    }

    private suspend fun recoverAfterRefreshFailure(): Result<RefreshResponse> {
        val recovery = recoverSilently()
        val body = recovery.getOrNull()
            ?: return Result.failure(
                recovery.exceptionOrNull() ?: Exception("Refresh failed, re-authorization required")
            )
        return Result.success(
            RefreshResponse(
                accessToken = body.accessToken,
                refreshToken = body.refreshToken,
                accessExpiresAt = body.accessExpiresAt,
                refreshExpiresAt = body.refreshExpiresAt,
            )
        )
    }

    // === Getters ===

    fun getDeviceId(): String? = deviceId

    fun getNextCheckAt(): Long = nextCheckAt

    fun getLastOnlineAt(): Long = lastOnlineAt

    private fun <T> retrofit2.Response<GatewayResponse<T>>.errorMessage(fallback: String): String {
        val envelope = body()
        if (envelope != null && envelope.code != "SUCCESS") return "${envelope.code}: ${envelope.message}"
        val raw = errorBody()?.string().orEmpty()
        if (raw.isNotBlank()) {
            runCatching { json.decodeFromString<GatewayResponse<JsonElement>>(raw) }
                .getOrNull()?.let { return "${it.code}: ${it.message}" }
        }
        return fallback
    }

    /**
     * 关闭 Retrofit 响应体（非 2xx 分支避免连接泄漏）。
     * retrofit2.Response 本身没有 close()，须经 raw() 取底层 okhttp3.Response
     * （实现 Closeable）关闭；close 幂等，errorBody 已消费也不受影响。
     */
    private fun retrofit2.Response<*>.closeQuietly() {
        runCatching { raw().close() }
    }

    /**
     * 失败分支统一出口：先取错误信息再关闭响应体，避免非 2xx 分支泄漏连接
     * （网关/网络频繁失败场景连接池被掏空）。先 [errorMessage] 后关闭，
     * 顺序不可颠倒——errorMessage 要读 errorBody，关闭后读取会失败。
     */
    private fun <T> retrofit2.Response<GatewayResponse<T>>.failureWith(fallback: String): Result<Nothing> {
        val failure = Result.failure<Nothing>(Exception(errorMessage(fallback)))
        closeQuietly()
        return failure
    }
}
