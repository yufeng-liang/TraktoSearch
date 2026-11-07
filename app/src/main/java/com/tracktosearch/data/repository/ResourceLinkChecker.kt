package com.tracktosearch.data.repository

import com.tracktosearch.data.remote.dto.DiskType
import com.tracktosearch.data.remote.dto.ResourceItem
import com.tracktosearch.data.remote.pansou.PanSouApiService
import com.tracktosearch.data.remote.pansou.dto.PanSouCheckItem
import com.tracktosearch.data.remote.pansou.dto.PanSouCheckRequest
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * 网盘链接有效性检测。
 *
 * 目前只有 PanSou 系站点提供公开检测接口（`POST /api/check/links`）：
 * - so.252035.xyz（PanSou 内置源）
 * - pan.useai.sbs（盘搜内置源）
 *
 * 服务端内部是串行检测，单次最多 256 条，批量请求容易超时/被断开，所以 App 按卡片
 * 单条触发，并用并发闸门限制同时请求数；检测结果按 url 缓存到服务端的 expires_at。
 */
@Singleton
class ResourceLinkChecker @Inject constructor(
    private val panSouApiService: PanSouApiService,
    @Named("useai") private val useAiApiService: PanSouApiService
) {

    enum class State {
        CHECKING,
        VALID,
        INVALID,
        LOCKED,
        UNCERTAIN,
        UNSUPPORTED,
        UNKNOWN
    }

    data class LinkStatus(
        val state: State,
        val summary: String = ""
    )

    private data class CacheEntry(
        val status: LinkStatus,
        val expiresAt: Long
    )

    private val cache = ConcurrentHashMap<String, CacheEntry>()

    // 同时最多两个检测请求，避免滚动时瞬间打爆上游
    private val semaphore = Semaphore(2)

    fun supports(item: ResourceItem): Boolean =
        item.source in SUPPORTED_SOURCES && diskTypeKey(item.diskType) != null

    fun cachedStatus(url: String): LinkStatus? =
        cache[url]?.takeIf { it.expiresAt > System.currentTimeMillis() }?.status

    suspend fun check(item: ResourceItem): LinkStatus {
        cachedStatus(item.url)?.let { return it }
        val diskType = diskTypeKey(item.diskType)
            ?: return LinkStatus(State.UNSUPPORTED)
        if (item.source !in SUPPORTED_SOURCES) return LinkStatus(State.UNSUPPORTED)

        return semaphore.withPermit {
            // 排队期间别的卡片可能已经查过同一 url
            cachedStatus(item.url)?.let { return@withPermit it }
            val service = if (item.source == SOURCE_USEAI) useAiApiService else panSouApiService
            val response = withTimeoutOrNull(CHECK_TIMEOUT_MS) {
                try {
                    service.checkLinks(
                        PanSouCheckRequest(
                            items = listOf(
                                PanSouCheckItem(diskType = diskType, url = item.url)
                            )
                        )
                    )
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
            }
            val result = response?.results?.firstOrNull()
                ?: return@withPermit LinkStatus(State.UNKNOWN)

            val state = when (result.state.lowercase()) {
                "ok" -> State.VALID
                "bad" -> State.INVALID
                "locked" -> State.LOCKED
                "uncertain" -> State.UNCERTAIN
                "unsupported" -> State.UNSUPPORTED
                "pending", "idle" -> State.UNCERTAIN
                else -> State.UNKNOWN
            }
            val status = LinkStatus(state, result.summary)
            if (state != State.UNKNOWN && state != State.UNSUPPORTED) {
                val expiresAt = result.expiresAt.takeIf { it > System.currentTimeMillis() }
                    ?: (System.currentTimeMillis() + DEFAULT_TTL_MS)
                cache[item.url] = CacheEntry(status, expiresAt)
            }
            status
        }
    }

    private fun diskTypeKey(type: DiskType): String? = when (type) {
        DiskType.QUARK -> "quark"
        DiskType.BAIDU -> "baidu"
        DiskType.ALI -> "aliyun"
        DiskType.XUNLEI -> "xunlei"
        DiskType.UC -> "uc"
        DiskType.ONEONEFIVE -> "115"
        DiskType.MAGNET, DiskType.OTHER -> null
    }

    private companion object {
        const val SOURCE_USEAI = "useai"
        val SUPPORTED_SOURCES = setOf("pansou", SOURCE_USEAI)
        const val CHECK_TIMEOUT_MS = 12_000L
        const val DEFAULT_TTL_MS = 30 * 60_000L
    }
}
