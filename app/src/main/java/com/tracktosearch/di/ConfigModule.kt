package com.tracktosearch.di

import android.content.Context
import com.tracktosearch.BuildConfig
import com.tracktosearch.data.auth.AuthInterceptor
import com.tracktosearch.data.remote.config.ConfigApiService
import com.tracktosearch.data.remote.config.RemoteConfigManager
import com.tracktosearch.data.remote.config.RemoteConfigProvider
import com.tracktosearch.data.remote.config.RemoteConfigStorage
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Named
import javax.inject.Singleton

/** 云端配置走网关 /api/config（服务端解密），密钥不再编译进 APK。 */
@Module
@InstallIn(SingletonComponent::class)
object ConfigModule {

    @Provides
    @Singleton
    @Named("config")
    fun provideConfigOkHttpClient(
        baseClient: OkHttpClient,
        loggingInterceptor: HttpLoggingInterceptor,
        authInterceptor: AuthInterceptor
    ): OkHttpClient = baseClient.newBuilder()
        .addInterceptor(authInterceptor)
        .addInterceptor(loggingInterceptor)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    @Provides
    @Singleton
    fun provideConfigApiService(
        @Named("config") okHttpClient: OkHttpClient,
        json: Json
    ): ConfigApiService = Retrofit.Builder()
        .baseUrl(BuildConfig.GATEWAY_BASE_URL.trimEnd('/') + "/")
        .client(okHttpClient)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(ConfigApiService::class.java)

    @Provides
    @Singleton
    fun provideRemoteConfigStorage(
        @ApplicationContext context: Context
    ): RemoteConfigStorage = RemoteConfigStorage(context)

    @Provides
    @Singleton
    fun provideRemoteConfigManager(
        configApiService: ConfigApiService,
        storage: RemoteConfigStorage,
        json: Json
    ): RemoteConfigManager = RemoteConfigManager(configApiService, storage, json)

    @Provides
    @Singleton
    fun provideRemoteConfigProvider(manager: RemoteConfigManager): RemoteConfigProvider = manager
}
