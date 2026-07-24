package com.tracktosearch.data.auth

import com.tracktosearch.data.local.TokenStorage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.decodeFromString
import javax.inject.Inject
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
    private val tokenStorage: TokenStorage,
    private val json: Json
) {
    private val refreshCoordinator = AuthRefreshCoordinator()

    private val _authState = MutableStateFlow(AuthState.UNAUTHORIZED)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    // 设备 ID（激活后缓存）
    private var deviceId: String? = null

    // 下次校验时间（Unix 秒）
    private var nextCheckAt: Long = 0L

    // 最后在线时间（Unix 秒）
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
                packageName = packageName
            )
            val response = authApiService.activate(request)
            if (response.isSuccessful) {
                val body = response.body()?.data ?: return Result.failure(Exception(response.errorMessage("Empty response")))
                // 保存令牌
                tokenStorage.saveTokens(body.accessToken, body.refreshToken, body.accessExpiresAt - System.currentTimeMillis() / 1000)
                deviceId = body.deviceId
                nextCheckAt = body.nextCheckAt
                lastOnlineAt = System.currentTimeMillis() / 1000
                tokenStorage.saveSessionMetadata(deviceId!!, lastOnlineAt, nextCheckAt)
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
            val response = authApiService.check()
            if (response.isSuccessful) {
                val body = response.body()?.data ?: return Result.failure(Exception(response.errorMessage("Empty response")))
                deviceId = body.deviceId.ifBlank { deviceId }
                lastOnlineAt = System.currentTimeMillis() / 1000
                nextCheckAt = body.nextCheckAt
                if (deviceId != null) tokenStorage.saveSessionMetadata(deviceId!!, lastOnlineAt, nextCheckAt)
                _authState.value = AuthState.AUTHORIZED
                Result.success(body)
            } else if (response.code() == 401) {
                // 令牌失效，尝试刷新
                refreshAfterCheck()
            } else if (response.code() == 403) {
                tokenStorage.clearTokens()
                deviceId = null
                _authState.value = AuthState.UNAUTHORIZED
                Result.failure(Exception(response.errorMessage("Check failed: ${response.code()}")))
            } else {
                Result.failure(Exception(response.errorMessage("Check failed: ${response.code()}")))
            }
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
    suspend fun refreshIfNeeded(failedAccessToken: String): Boolean =
        refreshCoordinator.refreshIfNeeded(
            failedAccessToken = failedAccessToken,
            currentAccessToken = tokenStorage::getCachedAccessToken
        ) {
            refreshLocked().isSuccess
        }

    private suspend fun refreshLocked(): Result<RefreshResponse> {
        return try {
            val currentDeviceId = deviceId ?: return Result.failure(Exception("No device ID"))
            val refreshToken = tokenStorage.getRefreshToken() ?: return Result.failure(Exception("No refresh token"))

            // 获取挑战码
            val challengeResponse = authApiService.challenge(ChallengeRequest(currentDeviceId))
            if (!challengeResponse.isSuccessful) {
                if (challengeResponse.code() == 401 || challengeResponse.code() == 403) {
                    tokenStorage.clearTokens()
                    deviceId = null
                    _authState.value = AuthState.UNAUTHORIZED
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
                tokenStorage.saveTokens(body.accessToken, body.refreshToken, body.accessExpiresAt - System.currentTimeMillis() / 1000)
                lastOnlineAt = System.currentTimeMillis() / 1000
                tokenStorage.saveSessionMetadata(currentDeviceId, lastOnlineAt, nextCheckAt)
                _authState.value = AuthState.AUTHORIZED
                Result.success(body)
            } else if (refreshResponse.code() == 401 || refreshResponse.code() == 403) {
                // 刷新失败，需要重新激活
                tokenStorage.clearTokens()
                deviceId = null
                _authState.value = AuthState.UNAUTHORIZED
                Result.failure(Exception("Refresh failed, re-authorization required"))
            } else {
                Result.failure(Exception(refreshResponse.errorMessage("Refresh failed: ${refreshResponse.code()}")))
            }
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
    suspend fun initialize() {
        tokenStorage.ensureCacheLoaded()
        deviceId = tokenStorage.getCachedDeviceId()
        nextCheckAt = tokenStorage.getCachedNextCheckAt()
        lastOnlineAt = tokenStorage.getCachedLastOnlineAt()
        if (!tokenStorage.isTokenValid()) {
            // access token 仅有 15 分钟寿命；只要 refresh session 仍在，就先轮换令牌。
            // 不能因为短期 access token 过期就丢弃已激活的设备会话，否则应用重启会错误回到邀请码页。
            val hasRefreshSession = !deviceId.isNullOrBlank() &&
                !tokenStorage.getRefreshToken().isNullOrBlank()
            if (hasRefreshSession) {
                refresh()
            } else {
                _authState.value = AuthState.UNAUTHORIZED
            }
            return
        }
        check()
    }

    /**
     * 退出授权态（设备撤销后）
     */
    suspend fun deauthorize() {
        tokenStorage.clearTokens()
        deviceId = null
        nextCheckAt = 0L
        _authState.value = AuthState.UNAUTHORIZED
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
