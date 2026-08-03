package com.tracktosearch.data.auth

import android.util.Base64
import com.google.common.truth.Truth.assertThat
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
        val manager = AuthManager(api, keyManager, continuityManager, storage, Json, traktRepositoryProvider)

        coEvery { storage.ensureCacheLoaded() } returns Unit
        every { storage.getCachedDeviceId() } returns "device-id"
        every { storage.getCachedNextCheckAt() } returns 0L
        every { storage.getCachedLastOnlineAt() } returns 1_000L
        every { storage.getCachedAccessToken() } returns "old-access"
        coEvery { storage.isTokenValid() } returns false
        coEvery { storage.getRefreshToken() } returns "refresh-token"
        every { continuityManager.getAndroidId() } returns "android-id"
        every { keyManager.sign(any()) } returns ByteArray(64)
        coEvery { storage.saveTokens(any(), any(), any()) } returns Unit
        coEvery { storage.saveSessionMetadata(any(), any(), any()) } returns Unit
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
        val manager = AuthManager(api, keyManager, continuityManager, storage, Json, traktRepositoryProvider)

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
        val manager = AuthManager(api, keyManager, continuityManager, storage, Json, traktRepositoryProvider)
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
    fun concurrentInitialize_checksOnlyAfterTheFirstInitializationUpdatesSession() = runTest {
        val api = mockk<AuthApiService>()
        val keyManager = mockk<DeviceKeyManager>()
        val continuityManager = mockk<DeviceContinuityManager>()
        val storage = mockk<TokenStorage>()
        val manager = AuthManager(api, keyManager, continuityManager, storage, Json, traktRepositoryProvider)
        var cachedNextCheckAt = 0L
        val futureNextCheckAt = System.currentTimeMillis() + 60_000L

        coEvery { storage.ensureCacheLoaded() } returns Unit
        every { storage.getCachedDeviceId() } returns "device-id"
        every { storage.getCachedNextCheckAt() } answers { cachedNextCheckAt }
        every { storage.getCachedLastOnlineAt() } returns 1_000L
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
    fun check_forbidden_clearsTraktAccountCaches() = runTest {
        val api = mockk<AuthApiService>()
        val keyManager = mockk<DeviceKeyManager>()
        val continuityManager = mockk<DeviceContinuityManager>()
        val storage = mockk<TokenStorage>()
        val manager = AuthManager(api, keyManager, continuityManager, storage, Json, traktRepositoryProvider)

        every { continuityManager.getAndroidId() } returns "android-id"
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
        val manager = AuthManager(api, keyManager, continuityManager, storage, Json, traktRepositoryProvider)

        coEvery { storage.ensureCacheLoaded() } returns Unit
        every { storage.getCachedDeviceId() } returns "device-id"
        every { storage.getCachedNextCheckAt() } returns 0L
        every { storage.getCachedLastOnlineAt() } returns 1_000L
        every { storage.getCachedAccessToken() } returns "old-access"
        coEvery { storage.isTokenValid() } returns false
        coEvery { storage.getRefreshToken() } returns "refresh-token"
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
