package com.tracktosearch.data.auth

import com.tracktosearch.data.local.TokenStorage
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

class AuthHeaderInterceptorTest {
    private lateinit var server: MockWebServer
    private val tokenStorage = mockk<TokenStorage>()
    private val authManager = mockk<AuthManager>()
    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        every { tokenStorage.getCachedAccessToken() } returns "access-token"
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun protectedAuthRequest_includesAccessToken() {
        server.enqueue(MockResponse().setResponseCode(200))

        OkHttpClient.Builder()
            .addInterceptor(AuthHeaderInterceptor(tokenStorage))
            .build()
            .newCall(Request.Builder().url(server.url("api/auth/check")).build())
            .execute()
            .close()

        assertThat(server.takeRequest().getHeader("Authorization"))
            .isEqualTo("Bearer access-token")
    }

    @Test
    fun activateRequest_doesNotReuseExistingToken() {
        server.enqueue(MockResponse().setResponseCode(200))

        OkHttpClient.Builder()
            .addInterceptor(AuthHeaderInterceptor(tokenStorage))
            .build()
            .newCall(Request.Builder().url(server.url("api/auth/activate")).build())
            .execute()
            .close()

        assertThat(server.takeRequest().getHeader("Authorization")).isNull()
    }

    @Test
    fun traktNotConnected401_doesNotRefreshAccessToken() {
        server.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"code\":\"UNAUTHORIZED\",\"message\":\"Trakt not connected\"}")
        )
        coEvery { authManager.refreshIfNeeded(any()) } returns false

        OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(tokenStorage, authManager, json))
            .build()
            .newCall(Request.Builder().url(server.url("api/trakt/users/me")).build())
            .execute()
            .close()

        coVerify(exactly = 0) { authManager.refreshIfNeeded(any()) }
    }
}
