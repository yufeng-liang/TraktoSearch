package com.tracktosearch.di

import android.content.Context
import com.tracktosearch.BuildConfig
import com.tracktosearch.data.remote.config.ApiKeyInterceptor
import com.tracktosearch.data.remote.config.ApiKeyProvider
import com.tracktosearch.data.remote.config.BaseUrlInterceptor
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
import javax.inject.Qualifier
import javax.inject.Singleton

// ==================== Qualifier 注解(区分 4 个服务的拦截器和 Provider) ====================

@Qualifier annotation class TmdbApiKeyProvider
@Qualifier annotation class TraktApiKeyProvider
@Qualifier annotation class DoubanApiKeyProvider

@Qualifier annotation class TmdbBaseUrlInterceptor
@Qualifier annotation class TraktBaseUrlInterceptor
@Qualifier annotation class DoubanBaseUrlInterceptor

@Qualifier annotation class TmdbApiKeyInterceptor
@Qualifier annotation class TraktApiKeyInterceptor
@Qualifier annotation class DoubanApiKeyInterceptor

/**
 * 云端配置 Hilt 模块。
 *
 * 提供:
 * - [ConfigApiService](拉取加密配置)
 * - [RemoteConfigStorage](DataStore 持久化)
 * - [RemoteConfigManager](实现 RemoteConfigProvider,拉取+解密+查询)
 * - 3 个 [ApiKeyProvider] 实例(TMDB/Trakt/豆瓣,OMDB 走 query 参数不走 Provider)
 * - 3 个 [BaseUrlInterceptor] 实例(TMDB/Trakt/豆瓣,OMDB 在 RatingsRepository 直接用 RemoteConfig)
 * - 3 个 [ApiKeyInterceptor] 实例(TMDB/Trakt/豆瓣)
 *
 * OMDB 特殊:用 ?apikey= query 参数,不用 header。
 * 改造方式:RatingsRepository 注入 RemoteConfigProvider,从 "omdb.apiKey" 取 key 传给 OmdbApiService。
 */
@Module
@InstallIn(SingletonComponent::class)
object ConfigModule {

    /** 远程配置专用 OkHttpClient(轻量,无 cache,无重试)。 */
    @Provides
    @Singleton
    @Named("config")
    fun provideConfigOkHttpClient(
        baseClient: OkHttpClient,
        loggingInterceptor: HttpLoggingInterceptor
    ): OkHttpClient {
        return baseClient.newBuilder()
            .addInterceptor(loggingInterceptor)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    @Provides
    @Singleton
    fun provideConfigApiService(
        @Named("config") okHttpClient: OkHttpClient,
        json: Json
    ): ConfigApiService {
        return Retrofit.Builder()
            .baseUrl(BuildConfig.CONFIG_BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(ConfigApiService::class.java)
    }

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

    // ==================== ApiKeyProvider 实例(每个服务一个) ====================

    @Provides
    @Singleton
    @TmdbApiKeyProvider
    fun provideTmdbApiKeyProvider(remoteConfig: RemoteConfigProvider): ApiKeyProvider {
        return ApiKeyProvider(
            remoteConfig = remoteConfig,
            configKey = "tmdb.apiKeys",
            fallbackKey = ""
        )
    }

    @Provides
    @Singleton
    @TraktApiKeyProvider
    fun provideTraktApiKeyProvider(remoteConfig: RemoteConfigProvider): ApiKeyProvider {
        return ApiKeyProvider(
            remoteConfig = remoteConfig,
            configKey = "trakt.clientId",  // Trakt 单 client_id,无池
            fallbackKey = BuildConfig.TRAKT_CLIENT_ID
        )
    }

    @Provides
    @Singleton
    @DoubanApiKeyProvider
    fun provideDoubanApiKeyProvider(remoteConfig: RemoteConfigProvider): ApiKeyProvider {
        return ApiKeyProvider(
            remoteConfig = remoteConfig,
            configKey = "douban.apiKey",
            fallbackKey = ""
        )
    }

    // ==================== BaseUrlInterceptor 实例 ====================

    @Provides
    @Singleton
    @TmdbBaseUrlInterceptor
    fun provideTmdbBaseUrlInterceptor(remoteConfig: RemoteConfigProvider): BaseUrlInterceptor {
        return BaseUrlInterceptor(
            remoteConfig = remoteConfig,
            configKey = "tmdb.baseUrl",
            fallbackUrl = "https://api.tmdb.org/3/"
        )
    }

    @Provides
    @Singleton
    @TraktBaseUrlInterceptor
    fun provideTraktBaseUrlInterceptor(remoteConfig: RemoteConfigProvider): BaseUrlInterceptor {
        return BaseUrlInterceptor(
            remoteConfig = remoteConfig,
            configKey = "trakt.baseUrl",
            fallbackUrl = "https://api.trakt.tv/"
        )
    }

    @Provides
    @Singleton
    @DoubanBaseUrlInterceptor
    fun provideDoubanBaseUrlInterceptor(remoteConfig: RemoteConfigProvider): BaseUrlInterceptor {
        return BaseUrlInterceptor(
            remoteConfig = remoteConfig,
            configKey = "douban.baseUrl",
            fallbackUrl = "https://douban-movie-api.pages.dev/"
        )
    }

    // ==================== ApiKeyInterceptor 实例 ====================

    @Provides
    @Singleton
    @TmdbApiKeyInterceptor
    fun provideTmdbApiKeyInterceptor(@TmdbApiKeyProvider provider: ApiKeyProvider): ApiKeyInterceptor {
        return ApiKeyInterceptor(
            apiKeyProvider = provider,
            headerName = "Authorization",
            headerValueTemplate = { key -> "Bearer $key" }
        )
    }

    @Provides
    @Singleton
    @TraktApiKeyInterceptor
    fun provideTraktApiKeyInterceptor(@TraktApiKeyProvider provider: ApiKeyProvider): ApiKeyInterceptor {
        return ApiKeyInterceptor(
            apiKeyProvider = provider,
            headerName = "trakt-api-key",
            headerValueTemplate = { key -> key }
        )
    }

    @Provides
    @Singleton
    @DoubanApiKeyInterceptor
    fun provideDoubanApiKeyInterceptor(@DoubanApiKeyProvider provider: ApiKeyProvider): ApiKeyInterceptor {
        return ApiKeyInterceptor(
            apiKeyProvider = provider,
            headerName = "X-API-Key",
            headerValueTemplate = { key -> key }
        )
    }

    // 注意:OMDB 用 query 参数 ?apikey=,不用 header。
    // OMDB 的 apiKey 改造在 RatingsRepository 中直接注入 RemoteConfigProvider 取 "omdb.apiKey"。
}
