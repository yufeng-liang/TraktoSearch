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
import com.tracktosearch.data.notification.NotificationScheduler
import com.tracktosearch.data.util.StartupTrace
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import javax.inject.Inject

@HiltAndroidApp
class TraktSearchApp : Application(), ImageLoaderFactory, Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var baseOkHttpClient: OkHttpClient
    @Inject lateinit var notificationScheduler: NotificationScheduler
    @Inject lateinit var authCheckScheduler: AuthCheckScheduler
    @Inject lateinit var crashLogUploader: com.tracktosearch.data.util.CrashLogUploader

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
            // WorkManager 调度移到后台线程，避免 getInstance + enqueueUniquePeriodicWork 阻塞主线程
            Thread { notificationScheduler.schedulePeriodicCheck() }.start()
            // 授权撤销最多 15 分钟内生效；网络不可用时由 AuthManager 保留离线宽限策略。
            Thread { authCheckScheduler.schedulePeriodicCheck() }.start()
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

        // 复用 DI 中的 base OkHttpClient（共享连接池和 IPv4 DNS 策略），仅添加图片特有拦截器
        val imageHttpClient = baseOkHttpClient.newBuilder()
            .addInterceptor(doubanRefererInterceptor)
            .build()

        return ImageLoader.Builder(this)
            .okHttpClient(imageHttpClient)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.20)
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
