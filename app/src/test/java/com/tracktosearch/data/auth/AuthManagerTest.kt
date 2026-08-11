package com.tracktosearch.data.auth

import android.util.Base64
import com.google.common.truth.Truth.assertThat
import com.tracktosearch.data.ai.AiStorage
import com.tracktosearch.data.local.TokenStorage
import com.tracktosearch.data.repository.TraktRepository
import io.mockk.every
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Before
import org.junit.Test
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.Response
import javax.inject.Provider

class AuthManagerTest {
    private val traktRepository = mockk<TraktRepository>(relaxed = true)
    private val traktRepositoryProvider = mockk<Provider<TraktRepository>>()

    @Before
    fun setUp() {
        mockkStatic(Base64::class)
        every { Base64.encodeToString(any(), any()) } returns "signature"
        every { traktRepositoryProvider.get() } returns traktRepository
    }

    @After
    fun tearDown() {
        unmockkStatic(Base64::class)
    }

    @Test
    fun initialize_refreshesExpiredAccessTokenBeforeDeclaringUnauthorized() = runTest {
        val api = mockk<AuthApiService>()
        val keyManager = mockk<DeviceKeyManager>()
        val continuityManager = mockk<DeviceContinuityManager>()
        val storage = mockk<TokenStorage>()
        val manager = AuthManager(api, keyManager, continuityManager, storage, Json, mockk<AiStorage>(relaxed = true), traktRepositoryProvider)

        coEvery { storage.ensureCacheLoaded() } returns Unit
        every { storage.getCachedDeviceId() } returns "device-id"
        every { storage.getCachedNextCheckAt() } returns 0L
        every { storage.getCachedLastOnlineAt() } returns 1_000L
        every { storage.getCachedAccessToken() } returns "old-access"
        coEvery { storage.isTokenValid() } returns false
        coEvery { storage.getRefreshToken() } returns "refresh-token"
        coEvery { storage.getRefreshAttemptId() } returns null
        coEvery { storage.saveRefreshAttemptId(any()) } returns Unit
        every { continuityManager.getAndroidId() } returns "android-id"
        every { keyManager.sign(any()) } returns ByteArray(64)
        coEvery { storage.saveSession(any(), any(), any(), any(), any(), any()) } returns Unit
        coEvery { api.challenge(ChallengeRequest("device-id")) } returns Response.success(
            GatewayResponse("SUCCESS", "OK", data = ChallengeResponse("nonce", 2_000L))
        )
        coEvery { api.refresh(any()) } returns Response.success(
            GatewayResponse(
                "SUCCESS",
                "OK",
                data = RefreshResponse("new-access", "new-refresh", 2_000L, 3_000L)
            )
        )

        manager.initialize()

        assertThat(manager.authState.value).isEqualTo(AuthState.AUTHORIZED)
        coVerify(exactly = 1) { api.challenge(ChallengeRequest("device-id")) }
        coVerify(exactly = 1) { api.refresh(any()) }
    }

    @Test
    fun initialize_usesCachedAuthorizationBeforeNextCheckAt() = runTest {
        val api = mockk<AuthApiService>()
        val keyManager = mockk<DeviceKeyManager>()
        val continuityManager = mockk<DeviceContinuityManager>()
        val storage = mockk<TokenStorage>()
        val manager = AuthManager(api, keyManager, continuityManager, storage, Json, mockk<AiStorage>(relaxed = true), traktRepositoryProvider)

        coEvery { storage.ensureCacheLoaded() } returns Unit
        every { storage.getCachedDeviceId() } returns "device-id"
        every { storage.getCachedNextCheckAt() } returns System.currentTimeMillis() / 1000 + 3_600
        every { storage.getCachedLastOnlineAt() } returns System.currentTimeMillis() / 1000
        coEvery { storage.isTokenValid() } returns true

        manager.initialize()

        assertThat(manager.authState.value).isEqualTo(AuthState.AUTHORIZED)
        coVerify(exactly = 0) { api.check(any()) }
    }

    @Test
    fun initializeForStartup_timeoutKeepsSessionInOfflineGrace() = runTest {
        val api = mockk<AuthApiService>()
        val keyManager = mockk<DeviceKeyManager>()
        val continuityManager = mockk<DeviceContinuityManager>()
        val storage = mockk<TokenStorage>()
        val manager = AuthManager(api, keyManager, continuityManager, storage, Json, mockk<AiStorage>(relaxed = true), traktRepositoryProvider)
        val now = System.currentTimeMillis() / 1000

        coEvery { storage.ensureCacheLoaded() } returns Unit
        every { storage.getCachedDeviceId() } returns "device-id"
        every { storage.getCachedNextCheckAt() } returns 0L
        every { storage.getCachedLastOnlineAt() } returns now
        coEvery { storage.isTokenValid() } returns true
        every { continuityManager.getAndroidId() } returns "android-id"
        coEvery { api.check(CheckRequest("android-id")) } coAnswers {
            delay(1_000)
            Response.success(
                GatewayResponse(
                    "SUCCESS",
                    "OK",
                    data = CheckResponse(
                        authorized = true,
                        friendId = "friend-id",
                        deviceId = "device-id",
                        nickname = "friend",
                        deviceStatus = "ACTIVE",
                        nextCheckAt = now + 86_400,
                        configVersion = 1,
                    ),
                ),
            )
        }

        manager.initializeForStartup(timeoutMillis = 100)

        assertThat(manager.authState.value).isEqualTo(AuthState.OFFLINE)
    }

    @Test
    fun initializeForStartup_timeoutDoesNotCancelInFlightRefresh() = runTest {
        val api = mockk<AuthApiService>()
        val keyManager = mockk<DeviceKeyManager>()
        val continuityManager = mockk<DeviceContinuityManager>()
        val storage = mockk<TokenStorage>()
        val manager = AuthManager(api, keyManager, continuityManager, storage, Json, mockk<AiStorage>(relaxed = true), traktRepositoryProvider)
        val refreshStarted = CompletableDeferred<Unit>()
        val releaseRefresh = CompletableDeferred<Unit>()

        coEvery { storage.ensureCacheLoaded() } returns Unit
        every { storage.getCachedDeviceId() } returns "device-id"
        every { storage.getCachedNextCheckAt() } returns 0L
        every { storage.getCachedLastOnlineAt() } returns System.currentTimeMillis() / 1000
        every { storage.getCachedAccessToken() } returns "old-access"
        coEvery { storage.isTokenValid() } returns false
        coEvery { storage.getRefreshToken() } returns "refresh-token"
        coEvery { storage.getRefreshAttemptId() } returns null
        coEvery { storage.saveRefreshAttemptId(any()) } returns Unit
        every { continuityManager.getAndroidId() } returns "android-id"
        every { keyManager.sign(any()) } returns ByteArray(64)
        coEvery { storage.saveSession(any(), any(), any(), any(), any(), any()) } returns Unit
        coEvery { api.challenge(ChallengeRequest("device-id")) } returns Response.success(
            GatewayResponse("SUCCESS", "OK", data = ChallengeResponse("nonce", 2_000L))
        )
        coEvery { api.refresh(any()) } coAnswers {
            refreshStarted.complete(Unit)
            releaseRefresh.await()
            Response.success(
                GatewayResponse(
                    "SUCCESS",
                    "OK",
                    data = RefreshResponse("new-access", "new-refresh", 2_000L, 3_000L)
                )
            )
        }

        val startup = async { manager.initializeForStartup(timeoutMillis = 100) }
        refreshStarted.await()
        startup.await()

        assertThat(manager.authState.value).isEqualTo(AuthState.OFFLINE)
        releaseRefresh.complete(Unit)

        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default.limitedParallelism(1)) {
            kotlinx.coroutines.withTimeout(2_000) {
                while (manager.authState.value != AuthState.AUTHORIZED) {
                    delay(10)
                }
            }
        }

        coVerify(exactly = 1) {
            storage.saveSession("new-access", "new-refresh", any(), "device-id", any(), any())
        }
    }

    @Test
    fun concurrentInitialize_checksOnlyAfterTheFirstInitializationUpdatesSession() = runTest {
        val api = mockk<AuthApiService>()
        val keyManager = mockk<DeviceKeyManager>()
        val continuityManager = mockk<DeviceContinuityManager>()
        val storage = mockk<TokenStorage>()
        val manager = AuthManager(api, keyManager, continuityManager, storage, Json, mockk<AiStorage>(relaxed = true), traktRepositoryProvider)
        var cachedNextCheckAt = 0L
        val futureNextCheckAt = System.currentTimeMillis() + 60_000L

        coEvery { storage.ensureCacheLoaded() } returns Unit
        every { storage.getCachedDeviceId() } returns "device-id"
        every { storage.getCachedNextCheckAt() } answers { cachedNextCheckAt }
        every { storage.getCachedLastOnlineAt() } returns 1_000L
        every { storage.getCachedAccessToken() } returns "access-token"
        coEvery { storage.isTokenValid() } returns true
        coEvery { storage.saveSessionMetadata(any(), any(), any()) } answers {
            cachedNextCheckAt = arg(2)
        }
        every { continuityManager.getAndroidId() } returns "android-id"
        coEvery { api.check(CheckRequest("android-id")) } coAnswers {
            delay(10)
            Response.success(
                GatewayResponse(
                    "SUCCESS",
                    "OK",
                    data = CheckResponse(
                        authorized = true,
                        friendId = "friend-id",
                        deviceId = "device-id",
                        nickname = "friend",
                        deviceStatus = "ACTIVE",
                        nextCheckAt = futureNextCheckAt,
                        configVersion = 1,
                    ),
                ),
            )
        }

        val first = async { manager.initialize() }
        val second = async { manager.initialize() }
        first.await()
        second.await()

        coVerify(exactly = 1) { api.check(CheckRequest("android-id")) }
    }

    @Test
    fun concurrentExpiredChecksOnlyRotateRefreshSessionOnce() = runTest {
        val api = mockk<AuthApiService>()
        val keyManager = mockk<DeviceKeyManager>()
        val continuityManager = mockk<DeviceContinuityManager>()
        val storage = mockk<TokenStorage>()
        val manager = AuthManager(api, keyManager, continuityManager, storage, Json, mockk<AiStorage>(relaxed = true), traktRepositoryProvider)
        val bothChecksStarted = CompletableDeferred<Unit>()
        var checkCalls = 0
        var cachedAccessToken = "old-access"

        coEvery { storage.ensureCacheLoaded() } returns Unit
        every { storage.getCachedDeviceId() } returns "device-id"
        every { storage.getCachedNextCheckAt() } returns System.currentTimeMillis() / 1000 + 3_600
        every { storage.getCachedLastOnlineAt() } returns System.currentTimeMillis() / 1000
        coEvery { storage.isTokenValid() } returns true
        every { storage.getCachedAccessToken() } answers { cachedAccessToken }
        coEvery { storage.getRefreshToken() } returns "refresh-token"
        coEvery { storage.getRefreshAttemptId() } returns null
        coEvery { storage.saveRefreshAttemptId(any()) } returns Unit
        coEvery { storage.saveSessionMetadata(any(), any(), any()) } returns Unit
        coEvery { storage.saveSession(any(), any(), any(), any(), any(), any()) } coAnswers {
            cachedAccessToken = firstArg()
        }
        every { continuityManager.getAndroidId() } returns "android-id"
        every { keyManager.sign(any()) } returns ByteArray(64)
        coEvery { api.check(CheckRequest("android-id")) } coAnswers {
            checkCalls++
            if (checkCalls == 2) bothChecksStarted.complete(Unit)
            bothChecksStarted.await()
            Response.error(401, "expired".toResponseBody())
        }
        coEvery { api.challenge(ChallengeRequest("device-id")) } returns Response.success(
            GatewayResponse("SUCCESS", "OK", data = ChallengeResponse("nonce", 2_000L))
        )
        coEvery { api.refresh(any()) } returns Response.success(
            GatewayResponse(
                "SUCCESS",
                "OK",
                data = RefreshResponse("new-access", "new-refresh", 2_000L, 3_000L)
            )
        )

        manager.initialize()
        val first = async { manager.check() }
        val second = async { manager.check() }

        first.await()
        second.await()

        coVerify(exactly = 1) { api.refresh(any()) }
        assertThat(cachedAccessToken).isEqualTo("new-access")
    }

    @Test
    fun check_forbidden_clearsTraktAccountCaches() = runTest {
        val api = mockk<AuthApiService>()
        val keyManager = mockk<DeviceKeyManager>()
        val continuityManager = mockk<DeviceContinuityManager>()
        val storage = mockk<TokenStorage>()
        val manager = AuthManager(api, keyManager, continuityManager, storage, Json, mockk<AiStorage>(relaxed = true), traktRepositoryProvider)

        every { continuityManager.getAndroidId() } returns "android-id"
        every { storage.getCachedAccessToken() } returns "access-token"
        coEvery { api.check(CheckRequest("android-id")) } returns
            Response.error(403, "forbidden".toResponseBody())
        coEvery { storage.clearTokens() } returns Unit

        val result = manager.check()

        assertThat(result.isFailure).isTrue()
        assertThat(manager.authState.value).isEqualTo(AuthState.UNAUTHORIZED)
        coVerify(exactly = 1) { traktRepository.clearTraktAccountCaches() }
    }

    @Test
    fun refresh_replay_clearsTraktAccountCaches() = runTest {
        val api = mockk<AuthApiService>()
        val keyManager = mockk<DeviceKeyManager>()
        val continuityManager = mockk<DeviceContinuityManager>()
        val storage = mockk<TokenStorage>()
        val manager = AuthManager(api, keyManager, continuityManager, storage, Json, mockk<AiStorage>(relaxed = true), traktRepositoryProvider)

        coEvery { storage.ensureCacheLoaded() } returns Unit
        every { storage.getCachedDeviceId() } returns "device-id"
        every { storage.getCachedNextCheckAt() } returns 0L
        every { storage.getCachedLastOnlineAt() } returns 1_000L
        every { storage.getCachedAccessToken() } returns "old-access"
        coEvery { storage.isTokenValid() } returns false
        coEvery { storage.getRefreshToken() } returns "refresh-token"
        coEvery { storage.getRefreshAttemptId() } returns null
        coEvery { storage.saveRefreshAttemptId(any()) } returns Unit
        every { keyManager.sign(any()) } returns ByteArray(64)
        coEvery { storage.clearTokens() } returns Unit
        coEvery { api.challenge(ChallengeRequest("device-id")) } returns Response.success(
            GatewayResponse("SUCCESS", "OK", data = ChallengeResponse("nonce", 2_000L))
        )
        coEvery { api.refresh(any()) } returns Response.error(401, "replay".toResponseBody())

        manager.initialize()

        assertThat(manager.authState.value).isEqualTo(AuthState.UNAUTHORIZED)
        coVerify(exactly = 1) { traktRepository.clearTraktAccountCaches() }
    }
}
