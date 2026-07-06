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
import com.tracktosearch.data.notification.NotificationScheduler
import com.tracktosearch.data.remote.douban.dto.DoubanHotData
import com.tracktosearch.data.repository.TmdbRepository
import com.tracktosearch.data.repository.TraktRepository
import com.tracktosearch.data.util.PersistentTtlCache
import com.tracktosearch.push.JPushHelper
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
    @Inject lateinit var tmdbRepository: TmdbRepository
    @Inject lateinit var traktRepository: TraktRepository
    @Inject lateinit var doubanHotCache: PersistentTtlCache<DoubanHotData>
    @Inject lateinit var doubanDetailCache: PersistentTtlCache<com.tracktosearch.data.remote.douban.DoubanDetailCacheEntry>

    // 缓存 Configuration，避免每次 get() 都新建实例
    override val workManagerConfiguration: Configuration by lazy {
        Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
    }

    override fun onCreate() {
        super.onCreate()
        // 只在主进程初始化，避免 :pushcore 子进程重复初始化 Hilt 注入依赖、CrashHandler、JPush
        if (isMainProcess()) {
            CrashHandler.init(this)
            Thread { JPushHelper.init(this) }.start()
            // WorkManager 调度移到后台线程，避免 getInstance + enqueueUniquePeriodicWork 阻塞主线程
            Thread { notificationScheduler.schedulePeriodicCheck() }.start()
            // 后台加载持久化缓存（海报路径、演职员头像、ID 映射、6h 榜单数据等），不阻塞 UI
            // 四组缓存并行加载：Tmdb 详情/列表 + Trakt ID 映射/趋势 + 豆瓣热榜 + 豆瓣详情页缓存
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                tmdbRepository.persistentCaches.forEach { it.loadFromDisk() }
                traktRepository.persistentCaches.forEach { it.loadFromDisk() }
                doubanHotCache.loadFromDisk()
                doubanDetailCache.loadFromDisk()
            }
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
                    // 500MB：海报图、演职员头像持久化缓存（跨 App 重启复用，省去重复下载）
                    .maxSizeBytes(500L * 1024 * 1024)
                    .build()
            }
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .crossfade(false)
            .respectCacheHeaders(false)
            .build()
    }
}
