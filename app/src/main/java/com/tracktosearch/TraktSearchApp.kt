package com.tracktosearch

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.CachePolicy
import dagger.hilt.android.HiltAndroidApp
import okhttp3.Dns
import okhttp3.OkHttpClient
import java.net.Inet4Address
import java.util.concurrent.TimeUnit

@HiltAndroidApp
class TraktSearchApp : Application(), ImageLoaderFactory {

    override fun newImageLoader(): ImageLoader {
        // 复用 NetworkModule 里 TMDB client 相同的强制 IPv4 DNS 策略，
        // 避免 image.tmdb.org 在某些网络环境下因 IPv6 解析失败导致图片加载失败
        val ipv4OnlyDns = object : Dns {
            override fun lookup(hostname: String): List<java.net.InetAddress> {
                val systemAddresses = Dns.SYSTEM.lookup(hostname)
                return systemAddresses.filter { it is Inet4Address }.ifEmpty { systemAddresses }
            }
        }

        val okHttpClient = OkHttpClient.Builder()
            .dns(ipv4OnlyDns)
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
