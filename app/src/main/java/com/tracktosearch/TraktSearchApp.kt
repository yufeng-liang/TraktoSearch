package com.tracktosearch

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.CachePolicy
import com.tracktosearch.push.JPushHelper
import dagger.hilt.android.HiltAndroidApp
import okhttp3.Dns
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.net.Inet4Address
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@HiltAndroidApp
class TraktSearchApp : Application(), ImageLoaderFactory, Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory

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
        // 复用 NetworkModule 里 TMDB client 相同的强制 IPv4 DNS 策略，
        // 避免 image.tmdb.org 在某些网络环境下因 IPv6 解析失败导致图片加载失败
        val ipv4OnlyDns = object : Dns {
            override fun lookup(hostname: String): List<java.net.InetAddress> {
                val systemAddresses = Dns.SYSTEM.lookup(hostname)
                return systemAddresses.filter { it is Inet4Address }.ifEmpty { systemAddresses }
            }
        }

        // 豆瓣图片防盗链：对 doubanio.com 请求添加 Referer 和 User-Agent 头
        val doubanRefererInterceptor = Interceptor { chain ->
            val request = chain.request()
            val newRequest = if (request.url.host.contains("doubanio.com")) {
                request.newBuilder()
                    .header("Referer", "https://movie.douban.com/")
                    .header("User-Agent", com.tracktosearch.di.NetworkModule.USER_AGENT)
                    .build()
            } else {
                request
            }
            chain.proceed(newRequest)
        }

        val okHttpClient = OkHttpClient.Builder()
            .dns(ipv4OnlyDns)
            .addInterceptor(doubanRefererInterceptor)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()

        return ImageLoader.Builder(this)
            .okHttpClient(okHttpClient)
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
            .crossfade(true)
            .respectCacheHeaders(false)
            .build()
    }
}
