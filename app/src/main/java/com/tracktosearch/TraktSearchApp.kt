package com.tracktosearch

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.CachePolicy
import com.tracktosearch.di.NetworkModule
import com.tracktosearch.data.auth.AuthCheckScheduler
import com.tracktosearch.data.local.DoubanAuthStorage
import com.tracktosearch.data.local.ImageTrafficStorage
import com.tracktosearch.data.local.ThemeStorage
import com.tracktosearch.data.local.db.AppDatabase
import com.tracktosearch.data.notification.NotificationScheduler
import com.tracktosearch.data.remote.ImageDownloadProgress
import com.tracktosearch.data.util.DnsCache
import com.tracktosearch.data.util.StartupTrace
import com.tracktosearch.data.worker.SplashPosterScheduler
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.Dispatcher
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Provider

@HiltAndroidApp
class TraktSearchApp : Application(), ImageLoaderFactory, Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var baseOkHttpClient: OkHttpClient
    @Inject lateinit var dnsCache: DnsCache
    @Inject lateinit var notificationScheduler: NotificationScheduler
    @Inject lateinit var authCheckScheduler: AuthCheckScheduler
    @Inject lateinit var splashPosterScheduler: SplashPosterScheduler
    @Inject lateinit var crashLogUploader: com.tracktosearch.data.util.CrashLogUploader
    @Inject lateinit var imageTrafficStorage: ImageTrafficStorage
    @Inject lateinit var themeStorage: ThemeStorage
    // 惰性 Provider：注入本身不触发数据库创建，仅在使用时才解析 @Singleton 实例
    @Inject lateinit var appDatabaseProvider: Provider<AppDatabase>
    // 惰性 Provider：注入本身不触发 EncryptedSharedPreferences 初始化，仅在使用时才解析
    @Inject lateinit var doubanAuthStorageProvider: Provider<DoubanAuthStorage>

    // CrashLogUploader 已改为 Hilt 单例：走网关 /api/crash-logs 代理，客户端不持有上报密钥。

    // 缓存 Configuration，避免每次 get() 都新建实例
    override val workManagerConfiguration: Configuration by lazy {
        Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
    }

    override fun onCreate() {
        super.onCreate()
        // CrashHandler 在所有进程初始化，确保子进程崩溃也能记录日志
        CrashHandler.init(this)
        // 只在主进程初始化，避免子进程重复初始化 Hilt 注入依赖
        if (isMainProcess()) {
            StartupTrace.markProcessStart()
            StartupTrace.mark("application.onCreate.enter")
            // 系统 Splash 在 Application.onCreate 完成前就渲染，必须在这里同步读取 app 主题设置
            // 并联动系统 uiMode（AppCompatDelegate.setDefaultNightMode，API 31+ 内部走
            // UiModeManager.setApplicationNightMode），否则冷启动的系统 Splash 只会跟随
            // 系统夜间模式，忽略 app 内的深色设置。DataStore 首值一般几十毫秒内读完。
            StartupTrace.mark("application.theme_mode.apply.start")
            // 主题模式应用在 ThemeStorage 的 companion 中，初始化阶段直接调用静态入口
            // 即可，避免把 DataStore 实例误当成扩展方法接收者。
            ThemeStorage.applyThemeModeToSystem(
                runBlocking { themeStorage.readThemeModeSnapshot() }
            )
            StartupTrace.mark("application.theme_mode.apply.done")
            // 数据库与豆瓣凭据存储预热：SQLCipher 的 loadLibs 与 Keystore 密钥解密在 MainActivity 主线程 Hilt 注入
            // （TraktRepository → MarkActionRecordDao → AppDatabase）时固定消耗 200ms-1s；
            // DoubanAuthStorage 首次初始化同样同步读 EncryptedSharedPreferences（MasterKey 走 Keystore），
            // 首次注入点在 AppNavigation 组合期主线程。
            // 这里在后台单线程提前触发单例创建，主线程后续注入直接复用现成实例。
            // Hilt 对 @Singleton 生成双检锁代理，跨线程并发访问安全；预热失败不阻断启动。
            StartupTrace.mark("application.db_warmup.start")
            val dbWarmupExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "startup-warmup") }
            dbWarmupExecutor.execute {
                try {
                    // 豆瓣凭据存储预热（与数据库无关，单独捕获避免相互影响）
                    try {
                        doubanAuthStorageProvider.get()
                        StartupTrace.mark("application.douban_auth_warmup.done")
                    } catch (e: Exception) {
                        // 预热失败不影响启动：主线程首次访问时仍会按需创建
                        StartupTrace.mark("application.douban_auth_warmup.failed", "err=${e.javaClass.simpleName}")
                    }
                    appDatabaseProvider.get()
                    StartupTrace.mark("application.db_warmup.done")
                } catch (e: Exception) {
                    // 预热失败不影响启动：后续主线程首次访问数据库时仍会按需创建
                    StartupTrace.mark("application.db_warmup.failed", "err=${e.javaClass.simpleName}")
                } finally {
                    dbWarmupExecutor.shutdown()
                }
            }
            // WorkManager 调度移到后台线程，避免 getInstance + enqueueUniquePeriodicWork 阻塞主线程
            Thread { notificationScheduler.schedulePeriodicCheck() }.start()
            // 授权撤销最多 15 分钟内生效；网络不可用时由 AuthManager 保留离线宽限策略。
            Thread { authCheckScheduler.schedulePeriodicCheck() }.start()
            // 开屏台词海报整池补齐：不计费网络下跑，池子齐了之后每次执行都是空转。
            Thread { splashPosterScheduler.schedulePeriodicPrefetch() }.start()
            // 上传未发送的崩溃日志到云端（走网关代理，worker 注入上报密钥）
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                crashLogUploader.uploadPendingLogs()
            }

            // 暂时停用远程配置拉取：当前暂无业务读取这些配置，避免启动阶段产生额外网络请求。
            // 后续启用时恢复下一行调用。
            // remoteConfigManager.initialize()
            StartupTrace.mark("application.remote_config.disabled")
            // 持久化缓存按当前页面首次使用时加载；不在 Application 阶段全量读盘或预热。
            StartupTrace.mark("application.cache_warmup.deferred", "reason=load_on_page")
        }
    }

    private fun isMainProcess(): Boolean {
        val pid = android.os.Process.myPid()
        val am = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return true
        val processName = am.runningAppProcesses?.firstOrNull { it.pid == pid }?.processName
        return processName == null || processName == packageName
    }

    override fun newImageLoader(): ImageLoader {
        // 豆瓣图片防盗链：对 doubanio.com 请求添加 Referer 和 User-Agent 头
        val doubanRefererInterceptor = Interceptor { chain ->
            val request = chain.request()
            val newRequest = if (request.url.host.contains("doubanio.com")) {
                request.newBuilder()
                    .header("Referer", "https://movie.douban.com/")
                    .header("User-Agent", NetworkModule.USER_AGENT)
                    .build()
            } else {
                request
            }
            chain.proceed(newRequest)
        }

        // 复用 DI 中的 base OkHttpClient（共享连接池），仅添加图片特有配置：
        // 1. 豆瓣防盗链拦截器
        // 2. 图片专用 DNS 缓存（TTL 10min）：海报图首次下载常发生在慢速/被污染网络，
        //    避免每个新连接重复系统 DNS 解析
        // 3. 独立 Dispatcher 提升并发：默认 maxRequestsPerHost=5 是 3 列网格快速滚动的瓶颈，
        //    提升到 per-host 8 / 总 20（线程池 8 与 per-host 对齐）
        // 4. 显式声明 HTTP/2 优先：同一连接多路复用，批量海报下载更高效
        // 5. 图片流量统计拦截器：仅统计图片 client 的实际下载字节（含压缩后 body 大小），
        //    供设置页展示，判断是否值得接入国内 CDN
        val imageHttpClient = baseOkHttpClient.newBuilder()
            .addInterceptor(doubanRefererInterceptor)
            // 全屏大图查看器的下载进度：仅对被观察的 URL 生效，其余零开销透传
            .addInterceptor(ImageDownloadProgress.interceptor)
            .addInterceptor { chain ->
                val response = chain.proceed(chain.request())
                val body = response.body
                // body.contentLength() 对分块/压缩响应可能为 -1，此时无法精确计数，跳过
                if (body != null && body.contentLength() >= 0) {
                    imageTrafficStorage.record(body.contentLength())
                }
                response
            }
            .dns(dnsCache)
            .dispatcher(
                Dispatcher(Executors.newFixedThreadPool(8)).apply {
                    maxRequests = 20
                    maxRequestsPerHost = 8
                }
            )
            .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
            .build()

        return ImageLoader.Builder(this)
            .okHttpClient(imageHttpClient)
            .memoryCache {
                // 0.30：Coil 2 默认 0.25（低内存机 0.15），原先设的 0.20 比默认还低。
                // 一张 264px 海报解码后约 418KB、342px 约 702KB，20% 堆只装得下几屏内容，
                // 滚出视口再回来就要走磁盘 + 重新解码 JPEG。低内存机仍按默认 0.15 保护。
                val lowRam = (getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)
                    ?.isLowRamDevice ?: false
                MemoryCache.Builder(this)
                    .maxSizePercent(if (lowRam) 0.15 else 0.30)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    // 888MB：海报图、演职员头像持久化缓存（跨 App 重启复用，省去重复下载）
                    .maxSizeBytes(888L * 1024 * 1024)
                    .build()
            }
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .crossfade(false)
            .respectCacheHeaders(false)
            .build()
    }
}
