package com.tracktosearch

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.CachePolicy
import com.tracktosearch.di.NetworkModule
import com.tracktosearch.push.JPushHelper
import dagger.hilt.android.HiltAndroidApp
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import javax.inject.Inject

@HiltAndroidApp
class TraktSearchApp : Application(), ImageLoaderFactory, Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var baseOkHttpClient: OkHttpClient

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        CrashHandler.init(this)
        Thread { JPushHelper.init(this) }.start()
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
                    .maxSizeBytes(50L * 1024 * 1024)
                    .build()
            }
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .crossfade(false)
            .respectCacheHeaders(false)
            .build()
    }
}
