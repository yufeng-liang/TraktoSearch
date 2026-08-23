package com.tracktosearch.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import com.tracktosearch.BuildConfig
import com.tracktosearch.data.ai.AiApiService
import com.tracktosearch.data.auth.AuthInterceptor
import com.tracktosearch.data.remote.douban.DoubanHotApiService
import com.tracktosearch.data.remote.douban.DoubanRexxarApiService
import com.tracktosearch.data.remote.douban.DoubanRexxarRequestInterceptor
import com.tracktosearch.data.remote.cloud.GiteePublicRawApi
import com.tracktosearch.data.remote.omdb.OmdbApiService
import com.tracktosearch.data.remote.panhub.PanHubApiService
import com.tracktosearch.data.remote.pansou.PanSouApiService
import com.tracktosearch.data.remote.tmdb.TmdbApiService
import com.tracktosearch.data.remote.trakt.TraktApiService
import com.tracktosearch.data.remote.update.GitHubUpdateApiService
import com.tracktosearch.data.remote.update.GiteeUpdateApiService
import com.tracktosearch.data.remote.weather.XiaomiWeatherApi
import com.tracktosearch.data.remote.zreso.ZresoApiService
import com.tracktosearch.data.util.ConnectivityObserver
import com.tracktosearch.data.util.DnsCache
import com.tracktosearch.data.util.PersistentTtlCache
import com.tracktosearch.data.util.persistentTtlCache
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import okhttp3.Cache
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Named
import javax.inject.Singleton
import kotlin.math.pow

/** 豆瓣持久化缓存 DataStore */
private val Context.doubanPersistentCacheStore: DataStore<Preferences> by preferencesDataStore(name = "douban_persistent_cache")

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    private const val CACHE_SIZE = 10L * 1024 * 1024 // 10 MB
    private const val DOUBAN_REXXAR_BASE_URL = "https://m.douban.com/rexxar/api/v2/"
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

    /** 共享基础 OkHttpClient：连接池 + 线程池 + IPv4 DNS 缓存，子客户端通过 newBuilder() 复用 */
    @Provides
    @Singleton
    fun provideBaseOkHttpClient(
        dnsCache: DnsCache,
        connectivityObserver: ConnectivityObserver
    ): OkHttpClient {
        return OkHttpClient.Builder()
            .connectionPool(ConnectionPool(10, 5, TimeUnit.MINUTES))
            .dns(dnsCache)
            // 无网快速失败：避免离线时按 connectTimeout 空等浪费电量，网络恢复后 OkHttp 自动重连
            .addInterceptor(NetworkStatusInterceptor(connectivityObserver))
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    @Provides
    @Singleton
    fun provideDnsCache(): DnsCache = DnsCache()

    /**
     * 网关专用请求调度器（Trakt + TMDB 共用）。
     *
     * OkHttp 默认 Dispatcher 的 maxRequestsPerHost 是 5，而 Trakt / TMDB 走同一个网关域名，
     * 于是全部业务 API 并发被压到 5：一个 200 条的 watchlist 首次富化要排 40 轮。
     * 网关走 HTTP/2，同一连接多路复用，提到 16 并发只是多开 stream，成本很低。
     * 豆瓣等其它客户端仍用共享的默认 Dispatcher，避免抬高对豆瓣的并发触发反爬。
     */
    @Provides
    @Singleton
    @Named("gateway")
    fun provideGatewayDispatcher(): Dispatcher = Dispatcher().apply {
        maxRequests = 32
        maxRequestsPerHost = 16
    }

    @Provides
    @Singleton
    @Named("trakt")
    fun provideTraktOkHttpClient(
        baseClient: OkHttpClient,
        loggingInterceptor: HttpLoggingInterceptor,
        cache: Cache,
        authInterceptor: AuthInterceptor,
        connectivityObserver: ConnectivityObserver,
        @Named("gateway") gatewayDispatcher: Dispatcher
    ): OkHttpClient {
        return baseClient.newBuilder()
            .cache(cache)
            .dispatcher(gatewayDispatcher)
            .addInterceptor(authInterceptor)
            .addInterceptor(Interceptor { chain ->
                val request = chain.request().newBuilder()
                    .addHeader("Content-Type", "application/json")
                    .addHeader("User-Agent", USER_AGENT)
                    .build()
                chain.proceed(request)
            })
            .addInterceptor(RetryInterceptor(connectivityObserver = connectivityObserver, maxRetries = 2))
            .addInterceptor(loggingInterceptor)
            .build()
    }

    @Provides
    @Singleton
    fun provideTraktApiService(
        @Named("trakt") okHttpClient: OkHttpClient
    ): TraktApiService {
        return Retrofit.Builder()
            .baseUrl("${BuildConfig.GATEWAY_BASE_URL.trimEnd('/')}/api/trakt/")
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
        cache: Cache,
        authInterceptor: AuthInterceptor,
        connectivityObserver: ConnectivityObserver,
        @Named("gateway") gatewayDispatcher: Dispatcher
    ): OkHttpClient {
        return baseClient.newBuilder()
            .cache(cache)
            .dispatcher(gatewayDispatcher)
            .addInterceptor(authInterceptor)
            .addInterceptor(Interceptor { chain ->
                val request = chain.request().newBuilder()
                    .addHeader("User-Agent", USER_AGENT)
                    .build()
                chain.proceed(request)
            })
            .addInterceptor(RetryInterceptor(connectivityObserver = connectivityObserver, maxRetries = 2))
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
            .baseUrl("${BuildConfig.GATEWAY_BASE_URL.trimEnd('/')}/api/tmdb/")
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

    /** 豆瓣热榜共享缓存（搜索页与发现页复用，持久化跨 App 重启保留） */
    @Provides
    @Singleton
    fun provideDoubanHotCache(
        @ApplicationContext context: android.content.Context,
        json: Json
    ): PersistentTtlCache<com.tracktosearch.data.remote.douban.dto.DoubanHotData> {
        val dataStore = context.doubanPersistentCacheStore
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        return persistentTtlCache(
            ttlMillis = 6 * 60 * 60 * 1000L, // 6 小时
            maxSize = 0,
            dataStore = dataStore,
            json = json,
            keyPrefix = "douban_hot_v2",
            scope = scope
        )
    }

    @Provides
    @Singleton
    fun provideDoubanHotApiService(
        @Named("douban") okHttpClient: OkHttpClient
    ): DoubanHotApiService {
        return Retrofit.Builder()
            .baseUrl("${BuildConfig.GATEWAY_BASE_URL.trimEnd('/')}/api/douban/")
            .client(okHttpClient)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(DoubanHotApiService::class.java)
    }

    /** Rexxar 直连客户端：不带网关鉴权，只添加移动端 UA 和 Referer。 */
    @Provides
    @Singleton
    @Named("doubanRexxar")
    fun provideDoubanRexxarOkHttpClient(
        baseClient: OkHttpClient,
        loggingInterceptor: HttpLoggingInterceptor
    ): OkHttpClient {
        return baseClient.newBuilder()
            .addInterceptor(DoubanRexxarRequestInterceptor())
            .addInterceptor(loggingInterceptor)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    @Provides
    @Singleton
    fun provideDoubanRexxarApiService(
        @Named("doubanRexxar") okHttpClient: OkHttpClient,
        json: Json
    ): DoubanRexxarApiService {
        return Retrofit.Builder()
            .baseUrl(DOUBAN_REXXAR_BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(DoubanRexxarApiService::class.java)
    }

    /** 豆瓣热榜专用 OkHttpClient（带 API Key） */
    @Provides
    @Singleton
    @Named("douban")
    fun provideDoubanOkHttpClient(
        baseClient: OkHttpClient,
        loggingInterceptor: HttpLoggingInterceptor,
        cache: Cache,
        authInterceptor: AuthInterceptor
    ): OkHttpClient {
        return baseClient.newBuilder()
            .cache(cache)
            .addInterceptor(authInterceptor)
            .addInterceptor(Interceptor { chain ->
                val request = chain.request().newBuilder()
                    .addHeader("User-Agent", USER_AGENT)
                    .build()
                chain.proceed(request)
            })
            .addInterceptor(loggingInterceptor)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
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
        loggingInterceptor: HttpLoggingInterceptor,
        authInterceptor: AuthInterceptor
    ): OkHttpClient {
        return baseClient.newBuilder()
            .addInterceptor(authInterceptor)
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
            .baseUrl("${BuildConfig.GATEWAY_BASE_URL.trimEnd('/')}/api/omdb/")
            .client(okHttpClient)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(OmdbApiService::class.java)
    }

    @Provides
    @Singleton
    @Named("github")
    fun provideGithubOkHttpClient(
        baseClient: OkHttpClient,
        loggingInterceptor: HttpLoggingInterceptor,
        authInterceptor: AuthInterceptor
    ): OkHttpClient {
        // GitHub API 走网关代理（auth-worker 注入 GITHUB_UPDATE_TOKEN），
        // 客户端只需带网关 JWT，不再直连 api.github.com。
        return baseClient.newBuilder()
            .addInterceptor(authInterceptor)
            .addInterceptor(loggingInterceptor)
            .build()
    }

    @Provides
    @Singleton
    fun provideGitHubUpdateApiService(
        @Named("github") okHttpClient: OkHttpClient
    ): GitHubUpdateApiService {
        return Retrofit.Builder()
            .baseUrl("${BuildConfig.GATEWAY_BASE_URL.trimEnd('/')}/api/github/")
            .client(okHttpClient)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(GitHubUpdateApiService::class.java)
    }

    @Provides
    @Singleton
    @Named("gitee")
    fun provideGiteeOkHttpClient(
        baseClient: OkHttpClient,
        loggingInterceptor: HttpLoggingInterceptor,
        authInterceptor: AuthInterceptor
    ): OkHttpClient {
        // Gitee API 走网关代理（auth-worker 注入 GITEE_ACCESS_TOKEN），
        // 客户端只需带网关 JWT，不再直连 gitee.com。
        return baseClient.newBuilder()
            .addInterceptor(authInterceptor)
            .addInterceptor(loggingInterceptor)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .callTimeout(75, TimeUnit.SECONDS)
            .build()
    }

    @Provides
    @Singleton
    fun provideGiteeUpdateApiService(
        @Named("gitee") okHttpClient: OkHttpClient
    ): GiteeUpdateApiService {
        return Retrofit.Builder()
            .baseUrl("${BuildConfig.GATEWAY_BASE_URL.trimEnd('/')}/api/gitee/")
            .client(okHttpClient)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(GiteeUpdateApiService::class.java)
    }

    /** 豆瓣失败项云端同步专用 Gitee Contents API（走网关代理，worker 注入 token） */
    @Provides
    @Singleton
    fun provideGiteeContentsApi(
        @Named("gitee") okHttpClient: OkHttpClient
    ): com.tracktosearch.data.remote.cloud.GiteeContentsApi {
        // 专用 Json:encodeDefaults = false,使 GiteeContentRequest.sha = null 时不出现在 JSON 体中
        // (Gitee API 收到 "sha": null 会报 "sha is empty")
        val giteeJson = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
            encodeDefaults = false
        }
        return Retrofit.Builder()
            .baseUrl("${BuildConfig.GATEWAY_BASE_URL.trimEnd('/')}/api/gitee/")
            .client(okHttpClient)
            .addConverterFactory(giteeJson.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(com.tracktosearch.data.remote.cloud.GiteeContentsApi::class.java)
    }

    /**
     * 公共数据池 Raw 直读客户端：匿名访问 Gitee 静态内容，不带网关鉴权，避免消耗网关额度。
     * 上传仍使用上面的 Gitee Contents API。
     */
    @Provides
    @Singleton
    @Named("giteePublic")
    fun provideGiteePublicOkHttpClient(
        baseClient: OkHttpClient,
        loggingInterceptor: HttpLoggingInterceptor,
        cache: Cache
    ): OkHttpClient {
        return baseClient.newBuilder()
            .cache(cache)
            .addInterceptor(Interceptor { chain ->
                val request = chain.request().newBuilder()
                    .header("User-Agent", USER_AGENT)
                    .build()
                chain.proceed(request)
            })
            .addInterceptor(loggingInterceptor)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(25, TimeUnit.SECONDS)
            .build()
    }

    @Provides
    @Singleton
    fun provideGiteePublicRawApi(
        @Named("giteePublic") okHttpClient: OkHttpClient
    ): GiteePublicRawApi {
        return Retrofit.Builder()
            .baseUrl("https://gitee.com/yufeng-liang/meta-data-public/raw/master/")
            .client(okHttpClient)
            .build()
            .create(GiteePublicRawApi::class.java)
    }

    @Provides
    @Singleton
    @Named("translate")
    fun provideTranslateOkHttpClient(
        baseClient: OkHttpClient,
        loggingInterceptor: HttpLoggingInterceptor,
        authInterceptor: AuthInterceptor
    ): OkHttpClient {
        // 百度翻译走网关代理（auth-worker 注入 BAIDU_* 密钥并代签名），
        // 客户端只需带网关 JWT，不再直连 fanyi-api.baidu.com。
        return baseClient.newBuilder()
            .addInterceptor(authInterceptor)
            .addInterceptor(loggingInterceptor)
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    @Provides
    @Singleton
    fun provideTranslateApiService(
        @Named("translate") okHttpClient: OkHttpClient
    ): com.tracktosearch.data.remote.translate.TranslateApiService {
        return Retrofit.Builder()
            .baseUrl("${BuildConfig.GATEWAY_BASE_URL.trimEnd('/')}/api/translate/")
            .client(okHttpClient)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(com.tracktosearch.data.remote.translate.TranslateApiService::class.java)
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
    fun provideXiaomiWeatherApiService(
        baseClient: OkHttpClient
    ): XiaomiWeatherApi {
        val client = baseClient.newBuilder()
            .addInterceptor(Interceptor { chain ->
                val request = chain.request().newBuilder()
                    .addHeader("User-Agent", USER_AGENT)
                    .build()
                chain.proceed(request)
            })
            .build()
        return Retrofit.Builder()
            .baseUrl("https://weatherapi.market.xiaomi.com/wtr-v3/")
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(XiaomiWeatherApi::class.java)
    }

    @Provides
    @Singleton
    @Named("crashLogs")
    fun provideCrashLogsOkHttpClient(
        baseClient: OkHttpClient,
        loggingInterceptor: HttpLoggingInterceptor,
        authInterceptor: AuthInterceptor
    ): OkHttpClient {
        // 崩溃日志走网关代理，由 auth-worker 直接写入共享 CRASH_LOGS KV，
        // 客户端只需带网关 JWT，不再持有上报密钥。
        return baseClient.newBuilder()
            .addInterceptor(authInterceptor)
            .addInterceptor(loggingInterceptor)
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    @Provides
    @Singleton
    fun provideCrashLogApiService(
        @Named("crashLogs") okHttpClient: OkHttpClient
    ): com.tracktosearch.data.remote.crash.CrashLogApiService {
        return Retrofit.Builder()
            .baseUrl("${BuildConfig.GATEWAY_BASE_URL.trimEnd('/')}/api/crash-logs/")
            .client(okHttpClient)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(com.tracktosearch.data.remote.crash.CrashLogApiService::class.java)
    }

    @Provides
    @Singleton
    @Named("feedback")
    fun provideFeedbackOkHttpClient(
        baseClient: OkHttpClient,
        loggingInterceptor: HttpLoggingInterceptor,
        authInterceptor: AuthInterceptor
    ): OkHttpClient {
        return baseClient.newBuilder()
            .addInterceptor(authInterceptor)
            .addInterceptor(Interceptor { chain ->
                val request = chain.request().newBuilder()
                    .addHeader("Content-Type", "application/json")
                    .addHeader("User-Agent", USER_AGENT)
                    .build()
                chain.proceed(request)
            })
            .addInterceptor(loggingInterceptor)
            .build()
    }

    @Provides
    @Singleton
    fun provideFeedbackApiService(
        @Named("feedback") client: OkHttpClient,
        json: Json
    ): com.tracktosearch.data.remote.feedback.FeedbackApiService {
        val retrofit = Retrofit.Builder()
            .baseUrl(BuildConfig.FEEDBACK_BASE_URL.trimEnd('/') + "/")
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
        return retrofit.create(com.tracktosearch.data.remote.feedback.FeedbackApiService::class.java)
    }

    @Provides
    @Singleton
    fun provideAiApiService(
        baseClient: OkHttpClient,
        loggingInterceptor: HttpLoggingInterceptor,
        authInterceptor: AuthInterceptor,
        json: Json
    ): AiApiService {
        val client = baseClient.newBuilder()
            .addInterceptor(authInterceptor)
            .addInterceptor(Interceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder()
                        .addHeader("Content-Type", "application/json")
                        .addHeader("User-Agent", USER_AGENT)
                        .build()
                )
            })
            .addInterceptor(loggingInterceptor)
            .build()
        return Retrofit.Builder()
            .baseUrl("${BuildConfig.GATEWAY_BASE_URL.trimEnd('/')}/api/ai/")
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(AiApiService::class.java)
    }
}

/**
 * 网络状态感知拦截器：离线时立即失败，跳过 connectTimeout 空等。
 * 挂在 base client，所有子客户端（含图片 client）继承。
 */
class NetworkStatusInterceptor(
    private val connectivityObserver: ConnectivityObserver
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
        if (connectivityObserver.status.value != ConnectivityObserver.NetworkStatus.ONLINE) {
            throw java.io.IOException("no network")
        }
        return chain.proceed(chain.request())
    }
}

/**
 * 带指数退避的重试拦截器
 * 对 429 (Too Many Requests) 和 5xx 错误自动重试
 */
class RetryInterceptor(
    private val connectivityObserver: ConnectivityObserver,
    private val maxRetries: Int = 2,
    private val baseDelayMs: Long = 500L,
    private val tokenProvider: () -> String? = { null }
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
        val original = chain.request()
        var response = chain.proceed(original)
        var retries = 0

        while (shouldRetry(response) && retries < maxRetries) {
            // 离线时跳过重试：网络已不可用，重试只会浪费电量与 dispatcher 线程
            if (connectivityObserver.status.value != ConnectivityObserver.NetworkStatus.ONLINE) {
                break
            }
            // 优先读 Retry-After header（429 响应通常携带，单位秒），否则用指数退避
            val retryAfterSec = response.header("Retry-After")?.toLongOrNull()
            response.close()
            // 限制最大延迟 3 秒：既保留 429 退避语义，又避免长时间阻塞 OkHttp dispatcher 线程
            val delayMs = (retryAfterSec?.let { it * 1000 }
                ?: baseDelayMs * 2.0.pow(retries.toDouble()).toLong())
                .coerceAtMost(3_000L)
            try {
                Thread.sleep(delayMs)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return response
            }
            retries++
            // 重试时重建请求并刷新 Authorization：auth 注入拦截器位于 RetryInterceptor 之前，
            // 重试走 chain.proceed 不会重新执行它，故此处手动补上最新 token，
            // 避免用过期 Authorization 重试（#19）。original 的其余 header（Content-Type 等）被 newBuilder 保留。
            val retryBuilder = original.newBuilder()
            tokenProvider()?.takeIf { it.isNotEmpty() }?.let {
                retryBuilder.header("Authorization", "Bearer $it")
            }
            response = chain.proceed(retryBuilder.build())
        }
        return response
    }

    private fun shouldRetry(response: okhttp3.Response): Boolean {
        return response.code == 429 || response.code >= 500
    }
}
