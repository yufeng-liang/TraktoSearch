package com.tracktosearch.data.auth

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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.decodeFromString
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
    private val traktRepositoryProvider: Provider<TraktRepository>
) {
    companion object {
        const val STARTUP_AUTH_TIMEOUT_MS = 5_000L
    }

    private val refreshCoordinator = AuthRefreshCoordinator()
    private val initializationMutex = Mutex()
    private val startupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val startupJobMutex = Mutex()
    private var startupInitializationJob: Job? = null

    private val _authState = MutableStateFlow(AuthState.UNAUTHORIZED)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    private val _recoveryFailure = MutableStateFlow<String?>(null)
    val recoveryFailure: StateFlow<String?> = _recoveryFailure.asStateFlow()

    // 朋友昵称（激活/校验时由网关返回，未授权或离线时为 null）
    private val _nickname = MutableStateFlow<String?>(null)
    val nickname: StateFlow<String?> = _nickname.asStateFlow()

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
                deviceId = body.deviceId
                nextCheckAt = body.nextCheckAt
                lastOnlineAt = now
                _authState.value = AuthState.AUTHORIZED
                Result.success(body)
            } else {
                Result.failure(Exception(response.errorMessage("Activate failed: ${response.code()}")))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 每日校验（启动时 + 每 24 小时）
     */
    suspend fun check(): Result<CheckResponse> {
        return try {
            val response = authApiService.check(CheckRequest(deviceContinuityManager.getAndroidId()))
            if (response.isSuccessful) {
                val body = response.body()?.data ?: return Result.failure(Exception(response.errorMessage("Empty response")))
                deviceId = body.deviceId.ifBlank { deviceId }
                lastOnlineAt = System.currentTimeMillis() / 1000
                nextCheckAt = body.nextCheckAt
                if (deviceId != null) tokenStorage.saveSessionMetadata(deviceId!!, lastOnlineAt, nextCheckAt)
                _authState.value = AuthState.AUTHORIZED
                _nickname.value = body.nickname.takeIf { it.isNotBlank() }
                Result.success(body)
            } else if (response.code() == 401) {
                // 令牌失效，尝试刷新
                refreshAfterCheck()
            } else if (response.code() == 403) {
                invalidateSession()
                Result.failure(Exception(response.errorMessage("Check failed: ${response.code()}")))
            } else {
                Result.failure(Exception(response.errorMessage("Check failed: ${response.code()}")))
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

            // 获取挑战码
            val challengeResponse = authApiService.challenge(ChallengeRequest(currentDeviceId))
            if (!challengeResponse.isSuccessful) {
                if (challengeResponse.code() == 401 || challengeResponse.code() == 403) {
                    invalidateSession()
                }
                return Result.failure(Exception(challengeResponse.errorMessage("Challenge failed: ${challengeResponse.code()}")))
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
                    signature = signatureBase64
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
                if (allowSilentRecovery) {
                    recoverAfterRefreshFailure()
                } else {
                    Result.failure(Exception("Refresh failed, re-authorization required"))
                }
            } else {
                Result.failure(Exception(refreshResponse.errorMessage("Refresh failed: ${refreshResponse.code()}")))
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
    private suspend fun refreshAfterCheck(): Result<CheckResponse> {
        val result = refresh()
        return if (result.isSuccess) {
            Result.success(CheckResponse(
                authorized = true,
                friendId = "",
                deviceId = deviceId ?: "",
                nickname = "",
                deviceStatus = "ACTIVE",
                nextCheckAt = nextCheckAt,
                configVersion = 1
            ))
        } else {
            Result.failure(result.exceptionOrNull() ?: Exception("Refresh failed"))
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

    /** 启动时恢复本地会话并按需向网关校验。 */
    suspend fun initialize(forceNetworkCheck: Boolean = false) {
        initializationMutex.withLock { initializeLocked(forceNetworkCheck) }
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
            handleOffline<Unit>()
        }
    }

    private suspend fun loadCachedSession() {
        tokenStorage.ensureCacheLoaded()
        deviceId = tokenStorage.getCachedDeviceId()
        nextCheckAt = tokenStorage.getCachedNextCheckAt()
        lastOnlineAt = tokenStorage.getCachedLastOnlineAt()
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
        deviceId = null
        _authState.value = AuthState.UNAUTHORIZED
        _nickname.value = null
    }

    private suspend fun recoverSilently(): Result<ActivateResponse> {
        _recoveryFailure.value = null
        if (BuildConfig.DEBUG) return Result.failure(Exception("RECOVERY_RELEASE_ONLY"))
        val androidId = deviceContinuityManager.getAndroidId()
            ?: return recoveryFailure("RECOVERY_ID_UNAVAILABLE")
        return try {
            val publicKey = deviceKeyManager.getPublicKeyBase64()
            val challengeResponse = authApiService.recoveryChallenge(
                RecoveryChallengeRequest(androidId, publicKey, BuildConfig.APPLICATION_ID)
            )
            if (!challengeResponse.isSuccessful) {
                return recoveryFailure(challengeResponse.errorMessage("Recovery challenge failed: ${challengeResponse.code()}"))
            }
            val nonce = challengeResponse.body()?.data?.nonce
                ?: return recoveryFailure("Recovery challenge is empty")
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
                return recoveryFailure(response.errorMessage("Recovery failed: ${response.code()}"))
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
            _recoveryFailure.value = e.message ?: "RECOVERY_FAILED"
            Result.failure(e)
        }
    }

    private fun recoveryFailure(message: String): Result<ActivateResponse> {
        _recoveryFailure.value = message
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
}
