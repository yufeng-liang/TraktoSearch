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
 * 转场动画只持续几百毫秒，旧实现里大图这期间才发起请求，转场结束时往往还在下载，
 * 表现为「先糊后突然变清」或进度环空转。这里提前一拍 enqueue（走同一 ImageLoader 的
 * 磁盘缓存），telephoto 子采样直接从磁盘读 tile，打开即可见清晰图。
 * 已在缓存时 enqueue 近乎零开销，无需自行判重。
 */
fun prefetchFullscreenImage(context: android.content.Context, urls: List<String?>) {
    val loader = context.imageLoader
    for (url in urls) {
        if (url == null) continue
        loader.enqueue(
            ImageRequest.Builder(context)
                .data(url)
                .crossfade(false)
                .build()
        )
    }
}
