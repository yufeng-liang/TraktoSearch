package com.tracktosearch.data.util

import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import okhttp3.Dns

/**
 * 图片专用 DNS 缓存：带 TTL 的内存缓存 + IPv4 过滤。
 *
 * 背景：图片 OkHttpClient 每次建立新连接都会调用 Dns.lookup，慢速网络/被污染环境下
 * 重复的系统 DNS 解析会显著拖慢海报图首次下载。image.tmdb.org、doubanio.com 这类 CDN
 * 主机 IP 在数小时内稳定，缓存 10 分钟足够覆盖绝大多数重复解析；IP 漂移由
 * OkHttp retryOnConnectionFailure 兜底，缓存过期后自动刷新。
 *
 * 线程安全：ConcurrentHashMap + 幂等写入，支持 OkHttp 多线程并发 lookup。
 */
class DnsCache(
    private val ttlMillis: Long = TimeUnit.MINUTES.toMillis(10)
) : Dns {

    private class Entry(
        val expiresAt: Long,
        val addresses: List<InetAddress>,
    )

    private val cache = ConcurrentHashMap<String, Entry>()

    override fun lookup(hostname: String): List<InetAddress> {
        val now = System.currentTimeMillis()
        // 命中且未过期：直接返回，避免重复解析
        cache[hostname]?.let { entry ->
            if (entry.expiresAt > now) return entry.addresses
        }

        // 未命中或过期：走系统 DNS 解析，沿用全局 IPv4 过滤策略（过滤 AAAA 避免 IPv6 解析超时）
        // 单次解析 + 过滤：解析失败返回空列表时不缓存，下次再查
        val resolved = Dns.SYSTEM.lookup(hostname)
        val addrs = if (resolved.any { it is Inet4Address }) resolved.filter { it is Inet4Address } else resolved
        // 仅缓存非空结果，解析失败不缓存避免缓存污染
        if (addrs.isNotEmpty()) {
            cache[hostname] = Entry(now + ttlMillis, addrs)
        }
        return addrs
    }
}