package com.tracktosearch.ui.component

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalContext
import coil.imageLoader
import coil.request.CachePolicy
import coil.request.ImageRequest
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * 列表滚动预取：监听 LazyRow / LazyVerticalGrid 的可见窗口，为即将滚入视口的
 * 屏幕外海报预热 Coil 内存/磁盘缓存，减少快速滚动时"边滚边下"的加载延迟。
 *
 * 用法：在列表所在组合中调用，传入与列表条目一一对应的海报 URL 列表。
 * 只对可见窗口末尾之后 [prefetchAhead] 个条目发起一次预取，已预取过的索引不会重复。
 * 预取请求走 App 的 ImageLoader（同样的磁盘/内存缓存与连接池），命中即被后续 AsyncImage 复用。
 *
 * [decodeSizePx] 必须与该列表卡片实际使用的解码尺寸一致，否则内存缓存会被错误尺寸的位图占用：
 * Coil 2 在无 transformation 时不把 size 并入内存缓存 key，两端尺寸不一致时，
 * 先写入的那份位图会被另一端复用（偏大时白占内存，偏小时判定无效并重新解码）。
 * 传 null 表示不限制，解码源图原始尺寸 —— 仅当 URL 本身已是目标尺寸
 * （如 TMDB w342 配 `MovieCard` 的 `.size(342)`），或卡片故意按布局尺寸解码时才这样用。
 *
 * @param listState 目标 LazyRow / LazyColumn 的 LazyListState
 * @param urls 与列表条目顺序一致的海报 URL 列表，null 条目跳过
 * @param prefetchAhead 在可见窗口末尾之后额外预取的条目数
 * @param decodeSizePx 解码尺寸（像素），与卡片侧 `ImageRequest.size()` 取值保持一致；null 为不限制
 */
@Composable
fun rememberPosterPrefetch(
    listState: LazyListState,
    urls: List<String?>,
    prefetchAhead: Int = 6,
    decodeSizePx: Int? = null
) {
    rememberPosterPrefetchCore(
        stateKey = listState,
        lastVisibleIndex = { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 },
        urls = urls,
        prefetchAhead = prefetchAhead,
        decodeSizePx = decodeSizePx
    )
}

/**
 * LazyVerticalGrid 重载（LazyGridState 与 LazyListState 是兄弟类型，无公共基类可统一签名）。
 */
@Composable
fun rememberPosterPrefetch(
    gridState: LazyGridState,
    urls: List<String?>,
    prefetchAhead: Int = 6,
    decodeSizePx: Int? = null
) {
    rememberPosterPrefetchCore(
        stateKey = gridState,
        lastVisibleIndex = { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 },
        urls = urls,
        prefetchAhead = prefetchAhead,
        decodeSizePx = decodeSizePx
    )
}

/**
 * 预取核心逻辑：以 `stateKey` 作为 LaunchedEffect 的 key（列表状态切换即重启），
 * 通过 [lastVisibleIndex] 读取当前可见窗口末尾索引。
 */
@Composable
private fun rememberPosterPrefetchCore(
    stateKey: Any,
    lastVisibleIndex: () -> Int,
    urls: List<String?>,
    prefetchAhead: Int,
    decodeSizePx: Int?
) {
    val context = LocalContext.current
    val imageLoader = remember(context) { context.imageLoader }
    val urlsRef by rememberUpdatedState(urls)
    LaunchedEffect(stateKey, decodeSizePx) {
        // 已预取到的最大索引：只向前推进，避免滚动时对同一批 URL 重复 enqueue
        var prefetchedUpTo = -1
        snapshotFlow { lastVisibleIndex() }
            .distinctUntilChanged()
            .collect { lastVisible ->
                if (lastVisible < 0) return@collect
                val start = (prefetchedUpTo + 1).coerceAtLeast(lastVisible + 1)
                val end = minOf(start + prefetchAhead, urlsRef.size)
                if (start >= end) return@collect
                for (i in start until end) {
                    val url = urlsRef.getOrNull(i) ?: continue
                    imageLoader.enqueue(
                        ImageRequest.Builder(context)
                            .data(url)
                            .apply { if (decodeSizePx != null) size(decodeSizePx) }
                            .crossfade(false)
                            .build()
                    )
                }
                prefetchedUpTo = end - 1
            }
    }
}

/**
 * 点击缩略图后、启动共享元素转场前，预热全屏查看用的大图 URL。
 *
 * telephoto 子采样需要原图落盘后从磁盘读 tile，预热提前完成这一步，
 * 打开即可见清晰图，避免「先糊后突然变清」。
 *
 * 已落盘的直接跳过：省掉一次 pipeline 与一次整图解码（预热请求的唯一目的就是把文件写进磁盘缓存）。
 * 未落盘时才 enqueue，此时本预热请求与全屏组件约 1 帧后发出的正式请求是两次独立 pipeline
 * 执行（Coil 2 无飞行中合并），同一 URL 会并发下载两份流量；换来的是磁盘写入提前一拍。
 *
 * 预热请求刻意压到 1px 解码 + 完全绕开内存缓存：磁盘缓存存的是原始响应字节，与解码尺寸无关，
 * 子采样照样能读到完整原图；而不限尺寸解码 original 剧照会产生数十 MB 位图并挤掉海报缓存。
 * 上面已按磁盘缓存判过 continue，内存缓存这一层读也没有意义（读命中反而可能跳过落盘）。
 */
@OptIn(coil.annotation.ExperimentalCoilApi::class) // DiskCache.openSnapshot 仍是实验 API
fun prefetchFullscreenImage(context: android.content.Context, urls: List<String?>) {
    val loader = context.imageLoader
    val diskCache = loader.diskCache
    for (url in urls) {
        if (url == null) continue
        // diskCacheKey 未自定义时 Coil 2 直接用 URL 作 key
        if (diskCache?.openSnapshot(url)?.use { true } == true) continue
        loader.enqueue(
            ImageRequest.Builder(context)
                .data(url)
                .crossfade(false)
                .size(1)
                .memoryCachePolicy(CachePolicy.DISABLED)
                .build()
        )
    }
}
