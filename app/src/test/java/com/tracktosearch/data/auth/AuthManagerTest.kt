package com.tracktosearch.data.auth

import android.util.Base64
import com.google.common.truth.Truth.assertThat
import com.tracktosearch.data.local.TokenStorage
import io.mockk.every
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Before
import org.junit.Test
import retrofit2.Response

class AuthManagerTest {
    @Before
    fun setUp() {
        mockkStatic(Base64::class)
        every { Base64.encodeToString(any(), any()) } returns "signature"
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
        val manager = AuthManager(api, keyManager, continuityManager, storage, Json)

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
}
