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
 * @param listState 目标 LazyRow / LazyColumn 的 LazyListState
 * @param urls 与列表条目顺序一致的海报 URL 列表，null 条目跳过
 * @param prefetchAhead 在可见窗口末尾之后额外预取的条目数
 */
@Composable
fun rememberPosterPrefetch(
    listState: LazyListState,
    urls: List<String?>,
    prefetchAhead: Int = 6
) {
    rememberPosterPrefetchCore(
        stateKey = listState,
        lastVisibleIndex = { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 },
        urls = urls,
        prefetchAhead = prefetchAhead
    )
}

/**
 * LazyVerticalGrid 重载（LazyGridState 与 LazyListState 是兄弟类型，无公共基类可统一签名）。
 */
@Composable
fun rememberPosterPrefetch(
    gridState: LazyGridState,
    urls: List<String?>,
    prefetchAhead: Int = 6
) {
    rememberPosterPrefetchCore(
        stateKey = gridState,
        lastVisibleIndex = { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 },
        urls = urls,
        prefetchAhead = prefetchAhead
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
    prefetchAhead: Int
) {
    val context = LocalContext.current
    val imageLoader = remember(context) { context.imageLoader }
    val urlsRef by rememberUpdatedState(urls)
    LaunchedEffect(stateKey) {
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
                            .crossfade(false)
                            .build()
                    )
                }
                prefetchedUpTo = end - 1
            }
    }
}
