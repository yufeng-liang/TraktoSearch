package com.tracktosearch.di

import com.google.common.truth.Truth.assertThat
import com.tracktosearch.data.auth.AuthInterceptor
import io.mockk.mockk
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import org.junit.Test

class NetworkModuleTest {

    @Test
    fun giteeClient_usesDetailsPoolTimeouts() {
        val client = NetworkModule.provideGiteeOkHttpClient(
            baseClient = OkHttpClient.Builder().build(),
            loggingInterceptor = HttpLoggingInterceptor(),
            authInterceptor = mockk<AuthInterceptor>(relaxed = true)
        )

        assertThat(client.connectTimeoutMillis).isEqualTo(30_000)
        assertThat(client.readTimeoutMillis).isEqualTo(60_000)
        assertThat(client.writeTimeoutMillis).isEqualTo(60_000)
        assertThat(client.callTimeoutMillis).isEqualTo(75_000)
    }
}
