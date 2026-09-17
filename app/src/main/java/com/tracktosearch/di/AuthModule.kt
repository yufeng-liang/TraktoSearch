package com.tracktosearch.di

import com.tracktosearch.BuildConfig
import com.tracktosearch.data.auth.AuthHeaderInterceptor
import com.tracktosearch.data.auth.AuthApiService
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Singleton
import javax.inject.Named

@Module
@InstallIn(SingletonComponent::class)
object AuthModule {

    @Provides
    @Singleton
    @Named("auth")
    fun provideAuthOkHttpClient(
        baseClient: OkHttpClient,
        authHeaderInterceptor: AuthHeaderInterceptor
    ): OkHttpClient {
        return baseClient.newBuilder()
            .addInterceptor(authHeaderInterceptor)
            // 授权接口仅在启动/校验/激活场景调用，收紧超时防止单个请求长时间占用启动预算
            // （协程侧 2s 上限先行截断，这里兜底网络栈自身超时）
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .writeTimeout(6, TimeUnit.SECONDS)
            .callTimeout(7, TimeUnit.SECONDS)
            .build()
    }

    @Provides
    @Singleton
    fun provideAuthApiService(
        @Named("auth") client: OkHttpClient,
        json: Json
    ): AuthApiService {
        val retrofit = Retrofit.Builder()
            .baseUrl(BuildConfig.GATEWAY_BASE_URL.trimEnd('/') + "/")
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
        return retrofit.create(AuthApiService::class.java)
    }
}
