package com.tracktosearch.di

import com.tracktosearch.BuildConfig
import com.tracktosearch.data.local.TokenStorage
import com.tracktosearch.data.remote.douban.DoubanHotApiService
import com.tracktosearch.data.remote.omdb.OmdbApiService
import com.tracktosearch.data.remote.panhub.PanHubApiService
import com.tracktosearch.data.remote.pansou.PanSouApiService
import com.tracktosearch.data.remote.tmdb.TmdbApiService
import com.tracktosearch.data.remote.trakt.TraktApiService
import com.tracktosearch.data.remote.update.GitHubUpdateApiService
import com.tracktosearch.data.remote.update.GiteeUpdateApiService
import com.tracktosearch.data.remote.weather.OpenMeteoApi
import com.tracktosearch.data.remote.zreso.ZresoApiService
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.ConnectionPool
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import okhttp3.Cache
import java.net.Inet4Address
import java.util.concurrent.TimeUnit
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    private const val CACHE_SIZE = 10L * 1024 * 1024 // 10 MB
    const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Mobile Safari/537.36"

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
    fun provideCache(@ApplicationContext context: android.content.Context): Cache {
        return Cache(context.cacheDir.resolve("http_cache"), CACHE_SIZE)
    }

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

    /** 共享基础 OkHttpClient：连接池 + 线程池 + IPv4 DNS，子客户端通过 newBuilder() 复用 */
    @Provides
    @Singleton
    fun provideBaseOkHttpClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .connectionPool(ConnectionPool(5, 5, TimeUnit.MINUTES))
            .dns(object : okhttp3.Dns {
                override fun lookup(hostname: String): List<java.net.InetAddress> {
                    val addrs = okhttp3.Dns.SYSTEM.lookup(hostname)
                    return addrs.filter { it is Inet4Address }.ifEmpty { addrs }
                }
            })
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    @Provides
    @Singleton
    @Named("trakt")
    fun provideTraktOkHttpClient(
        baseClient: OkHttpClient,
        loggingInterceptor: HttpLoggingInterceptor,
        tokenStorage: TokenStorage,
        cache: Cache
    ): OkHttpClient {
        return baseClient.newBuilder()
            .cache(cache)
            .addInterceptor(Interceptor { chain ->
                val token = tokenStorage.getCachedAccessToken()
                val request = chain.request().newBuilder()
                    .addHeader("Content-Type", "application/json")
                    .addHeader("trakt-api-key", BuildConfig.TRAKT_CLIENT_ID)
                    .addHeader("trakt-api-version", "2")
                    .addHeader("User-Agent", USER_AGENT)
                    .apply {
                        if (!token.isNullOrEmpty()) {
                            addHeader("Authorization", "Bearer $token")
                        }
                    }
                    .build()
                chain.proceed(request)
            })
            .addInterceptor(RetryInterceptor(maxRetries = 2))
            .addInterceptor(loggingInterceptor)
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
        baseClient: OkHttpClient,
        loggingInterceptor: HttpLoggingInterceptor,
        cache: Cache
    ): OkHttpClient {
        return baseClient.newBuilder()
            .cache(cache)
            .addInterceptor(Interceptor { chain ->
                val request = chain.request().newBuilder()
                    .addHeader("Authorization", "Bearer ${BuildConfig.TMDB_API_KEY}")
                    .addHeader("User-Agent", USER_AGENT)
                    .build()
                chain.proceed(request)
            })
            .addInterceptor(RetryInterceptor(maxRetries = 2))
            .addInterceptor(loggingInterceptor)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
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
        baseClient: OkHttpClient,
        loggingInterceptor: HttpLoggingInterceptor,
        cache: Cache
    ): OkHttpClient {
        return baseClient.newBuilder()
            .cache(cache)
            .addInterceptor(Interceptor { chain ->
                val request = chain.request().newBuilder()
                    .addHeader("User-Agent", USER_AGENT)
                    .addHeader("Referer", "https://so.252035.xyz/")
                    .build()
                chain.proceed(request)
            })
            .addInterceptor(loggingInterceptor)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
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
        baseClient: OkHttpClient,
        loggingInterceptor: HttpLoggingInterceptor,
        cache: Cache
    ): OkHttpClient {
        return baseClient.newBuilder()
            .cache(cache)
            .addInterceptor(Interceptor { chain ->
                val request = chain.request().newBuilder()
                    .addHeader("User-Agent", USER_AGENT)
                    .addHeader("Referer", "https://panhub.shenzjd.com/")
                    .build()
                chain.proceed(request)
            })
            .addInterceptor(loggingInterceptor)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
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
    fun providePanHubGranularApiService(
        @Named("panhub") okHttpClient: OkHttpClient
    ): PanHubApiService {
        return Retrofit.Builder()
            .baseUrl("https://panhub.shenzjd.com/")
            .client(okHttpClient)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(PanHubApiService::class.java)
    }

    @Provides
    @Singleton
    fun provideDoubanHotApiService(
        @Named("panhub") okHttpClient: OkHttpClient
    ): DoubanHotApiService {
        return Retrofit.Builder()
            .baseUrl("https://panhubshenzjdcom-beta-indol.vercel.app/")
            .client(okHttpClient)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(DoubanHotApiService::class.java)
    }

    @Provides
    @Singleton
    @Named("zreso")
    fun provideZresoOkHttpClient(
        baseClient: OkHttpClient,
        loggingInterceptor: HttpLoggingInterceptor,
        cache: Cache
    ): OkHttpClient {
        return baseClient.newBuilder()
            .cache(cache)
            .addInterceptor(loggingInterceptor)
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
        baseClient: OkHttpClient,
        loggingInterceptor: HttpLoggingInterceptor
    ): OkHttpClient {
        return baseClient.newBuilder()
            .addInterceptor(loggingInterceptor)
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS)
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
        baseClient: OkHttpClient,
        loggingInterceptor: HttpLoggingInterceptor
    ): GitHubUpdateApiService {
        val token = BuildConfig.GITHUB_UPDATE_TOKEN
        val client = baseClient.newBuilder().apply {
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
        baseClient: OkHttpClient,
        loggingInterceptor: HttpLoggingInterceptor
    ): GiteeUpdateApiService {
        val token = BuildConfig.GITEE_ACCESS_TOKEN
        val client = baseClient.newBuilder().apply {
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
        baseClient: OkHttpClient,
        loggingInterceptor: HttpLoggingInterceptor
    ): OkHttpClient {
        return baseClient.newBuilder()
            .addInterceptor(Interceptor { chain ->
                val request = chain.request().newBuilder()
                    .addHeader("User-Agent", USER_AGENT)
                    .build()
                chain.proceed(request)
            })
            .addInterceptor(loggingInterceptor)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    @Provides
    @Singleton
    fun provideOpenMeteoApiService(
        baseClient: OkHttpClient,
        loggingInterceptor: HttpLoggingInterceptor
    ): OpenMeteoApi {
        val client = baseClient.newBuilder()
            .addInterceptor(loggingInterceptor)
            .build()
        return Retrofit.Builder()
            .baseUrl("https://api.open-meteo.com/v1/")
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(OpenMeteoApi::class.java)
    }
}

/**
 * 带指数退避的重试拦截器
 * 对 429 (Too Many Requests) 和 5xx 错误自动重试
 */
class RetryInterceptor(
    private val maxRetries: Int = 2,
    private val baseDelayMs: Long = 500L
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
        val request = chain.request()
        var response = chain.proceed(request)
        var retries = 0

        while (shouldRetry(response) && retries < maxRetries) {
            response.close()
            val delayMs = baseDelayMs * (1L shl retries)
            try {
                Thread.sleep(delayMs)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return response
            }
            retries++
            response = chain.proceed(request)
        }
        return response
    }

    private fun shouldRetry(response: okhttp3.Response): Boolean {
        return response.code == 429 || response.code >= 500
    }
}


