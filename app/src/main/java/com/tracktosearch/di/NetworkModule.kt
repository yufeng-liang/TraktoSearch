package com.tracktosearch.di

import com.tracktosearch.BuildConfig
import com.tracktosearch.data.local.TokenStorage
import com.tracktosearch.data.remote.douban.DoubanHotApiService
import com.tracktosearch.data.remote.omdb.OmdbApiService
import com.tracktosearch.data.remote.pansou.PanSouApiService
import com.tracktosearch.data.remote.tmdb.TmdbApiService
import com.tracktosearch.data.remote.trakt.TraktApiService
import com.tracktosearch.data.remote.update.GitHubUpdateApiService
import com.tracktosearch.data.remote.update.GiteeUpdateApiService
import com.tracktosearch.data.remote.zreso.ZresoApiService
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        encodeDefaults = true
    }

    @Provides
    @Singleton
    fun provideJson(): Json = json

    @Provides
    @Singleton
    fun provideLoggingInterceptor(): HttpLoggingInterceptor {
        return HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.BASIC
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
        }
    }

    @Provides
    @Singleton
    @Named("trakt")
    fun provideTraktOkHttpClient(
        loggingInterceptor: HttpLoggingInterceptor,
        tokenStorage: TokenStorage
    ): OkHttpClient {
        return OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                // 直接用 TokenStorage 的内存缓存，避免 runBlocking 阻塞主线程
                val token = tokenStorage.getCachedAccessToken()
                val request = chain.request().newBuilder()
                    .addHeader("Content-Type", "application/json")
                    .addHeader("trakt-api-key", BuildConfig.TRAKT_CLIENT_ID)
                    .addHeader("trakt-api-version", "2")
                    .apply {
                        if (!token.isNullOrEmpty()) {
                            addHeader("Authorization", "Bearer $token")
                        }
                    }
                    .build()
                chain.proceed(request)
            })
            .addInterceptor(loggingInterceptor)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            // 失败自动重试
            .retryOnConnectionFailure(true)
            .build()
    }

    @Provides
    @Singleton
    fun provideTraktApiService(
        @Named("trakt") okHttpClient: OkHttpClient
    ): TraktApiService {
        return Retrofit.Builder()
            .baseUrl("https://api.trakt.tv/")
            .client(okHttpClient)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(TraktApiService::class.java)
    }

    @Provides
    @Singleton
    @Named("tmdb")
    fun provideTmdbOkHttpClient(
        loggingInterceptor: HttpLoggingInterceptor
    ): OkHttpClient {
        return OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val request = chain.request().newBuilder()
                    .addHeader("Authorization", "Bearer ${BuildConfig.TMDB_API_KEY}")
                    .build()
                chain.proceed(request)
            })
            .addInterceptor(loggingInterceptor)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .dns(object : okhttp3.Dns {
                override fun lookup(hostname: String): List<java.net.InetAddress> {
                    val systemAddresses = okhttp3.Dns.SYSTEM.lookup(hostname)
                    return systemAddresses.filter { it is java.net.Inet4Address }
                        .ifEmpty { systemAddresses }
                }
            })
            .build()
    }

    @Provides
    @Singleton
    fun provideTmdbApiService(
        @Named("tmdb") okHttpClient: OkHttpClient
    ): TmdbApiService {
        return Retrofit.Builder()
            .baseUrl("https://api.tmdb.org/3/")
            .client(okHttpClient)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(TmdbApiService::class.java)
    }

    @Provides
    @Singleton
    @Named("pansou")
    fun providePanSouOkHttpClient(
        loggingInterceptor: HttpLoggingInterceptor
    ): OkHttpClient {
        return OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val request = chain.request().newBuilder()
                    .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Mobile Safari/537.36")
                    .addHeader("Referer", "https://so.252035.xyz/")
                    .build()
                chain.proceed(request)
            })
            .addInterceptor(loggingInterceptor)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    @Provides
    @Singleton
    fun providePanSouApiService(
        @Named("pansou") okHttpClient: OkHttpClient
    ): PanSouApiService {
        return Retrofit.Builder()
            .baseUrl("https://so.252035.xyz/")
            .client(okHttpClient)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(PanSouApiService::class.java)
    }

    @Provides
    @Singleton
    @Named("panhub")
    fun providePanHubOkHttpClient(
        loggingInterceptor: HttpLoggingInterceptor
    ): OkHttpClient {
        return OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val request = chain.request().newBuilder()
                    .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Mobile Safari/537.36")
                    .addHeader("Referer", "https://panhub.shenzjd.com/")
                    .build()
                chain.proceed(request)
            })
            .addInterceptor(loggingInterceptor)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    @Provides
    @Singleton
    @Named("panhub")
    fun providePanHubApiService(
        @Named("panhub") okHttpClient: OkHttpClient
    ): PanSouApiService {
        return Retrofit.Builder()
            .baseUrl("https://panhub.shenzjd.com/")
            .client(okHttpClient)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(PanSouApiService::class.java)
    }

    @Provides
    @Singleton
    fun provideDoubanHotApiService(
        @Named("panhub") okHttpClient: OkHttpClient
    ): DoubanHotApiService {
        return Retrofit.Builder()
            .baseUrl("https://panhub.shenzjd.com/")
            .client(okHttpClient)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(DoubanHotApiService::class.java)
    }

    @Provides
    @Singleton
    @Named("zreso")
    fun provideZresoOkHttpClient(
        loggingInterceptor: HttpLoggingInterceptor
    ): OkHttpClient {
        return OkHttpClient.Builder()
            .addInterceptor(loggingInterceptor)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    @Provides
    @Singleton
    fun provideZresoApiService(
        @Named("zreso") okHttpClient: OkHttpClient
    ): ZresoApiService {
        return Retrofit.Builder()
            .baseUrl("https://zreso.cn/")
            .client(okHttpClient)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(ZresoApiService::class.java)
    }

    @Provides
    @Singleton
    @Named("omdb")
    fun provideOmdbOkHttpClient(
        loggingInterceptor: HttpLoggingInterceptor
    ): OkHttpClient {
        return OkHttpClient.Builder()
            .addInterceptor(loggingInterceptor)
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    @Provides
    @Singleton
    fun provideOmdbApiService(
        @Named("omdb") okHttpClient: OkHttpClient
    ): OmdbApiService {
        return Retrofit.Builder()
            .baseUrl("https://www.omdbapi.com/")
            .client(okHttpClient)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(OmdbApiService::class.java)
    }

    @Provides
    @Singleton
    fun provideGitHubUpdateApiService(
        loggingInterceptor: HttpLoggingInterceptor
    ): GitHubUpdateApiService {
        val token = BuildConfig.GITHUB_UPDATE_TOKEN
        val client = OkHttpClient.Builder()
            .apply {
                if (token.isNotEmpty()) {
                    addInterceptor(Interceptor { chain ->
                        val request = chain.request().newBuilder()
                            .addHeader("Authorization", "Bearer $token")
                            .build()
                        chain.proceed(request)
                    })
                }
            }
            .addInterceptor(loggingInterceptor)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
        return Retrofit.Builder()
            .baseUrl("https://api.github.com/")
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(GitHubUpdateApiService::class.java)
    }

    @Provides
    @Singleton
    fun provideGiteeUpdateApiService(
        loggingInterceptor: HttpLoggingInterceptor
    ): GiteeUpdateApiService {
        val token = BuildConfig.GITEE_ACCESS_TOKEN
        val client = OkHttpClient.Builder()
            .apply {
                if (token.isNotEmpty()) {
                    addInterceptor(Interceptor { chain ->
                        val originalUrl = chain.request().url
                        val newUrl = originalUrl.newBuilder()
                            .addQueryParameter("access_token", token)
                            .build()
                        val request = chain.request().newBuilder()
                            .url(newUrl)
                            .build()
                        chain.proceed(request)
                    })
                }
            }
            .addInterceptor(loggingInterceptor)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
        return Retrofit.Builder()
            .baseUrl("https://gitee.com/api/v5/")
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(GiteeUpdateApiService::class.java)
    }

    @Provides
    @Singleton
    @Named("custom_search")
    fun provideCustomSearchOkHttpClient(
        loggingInterceptor: HttpLoggingInterceptor
    ): OkHttpClient {
        return OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val request = chain.request().newBuilder()
                    .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Mobile Safari/537.36")
                    .build()
                chain.proceed(request)
            })
            .addInterceptor(loggingInterceptor)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}
