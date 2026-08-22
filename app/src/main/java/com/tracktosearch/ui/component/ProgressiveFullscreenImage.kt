package com.tracktosearch.ui.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.request.ImageRequest
import com.tracktosearch.data.remote.ImageDownloadProgress
import com.tracktosearch.data.remote.tmdb.TmdbImageUrls
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 全屏大图的渐进底图 URL：把 TMDB 尺寸段换成 w780。
 *
 * 缩略图/详情页头部本来就用 w780，点开时它已在 Coil 内存或磁盘缓存里，可以瞬时铺满，
 * 避免「先空白、几秒后整张大图突然出现」。非 TMDB `/t/p/` 结构（豆瓣图、fanart 人物图）
 * 或本身就是 w780 时返回 null，不额外发请求。
 */
private fun progressiveUnderlay(model: Any?): String? {
    val url = model as? String ?: return null
    val swapped = TmdbImageUrls.swapSize(url, "w780")
    return swapped.takeIf { it != url }
}

/**
 * 全屏大图渐进显示 + 加载进度。
 *
 * 三层叠加：
 * 1. 底层小尺寸缓存图（若有），立刻可见；
 * 2. 上层大图，加载完成即覆盖（同一位置、同一构图，不用 crossfade 以免与共享元素转场叠加成双重动画）；
 * 3. 加载期间的进度环——有 Content-Length 时为确定性进度（original 剧照常 2-5MB，
 *    纯 spinner 无法判断还要等多久），拿不到长度时退化为不确定 spinner。
 *
 * 共享元素与手势修饰符由调用方挂在 [modifier] 上（作用于整个 Box，与原来挂在单张图上等价）。
 */
@Composable
internal fun ProgressiveFullscreenImage(
    model: Any,
    contentScale: ContentScale,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    imageSizePx: Int = 1080,
) {
    val context = LocalContext.current
    val underlayUrl = remember(model) { progressiveUnderlay(model) }
    var settled by remember(model) { mutableStateOf(false) }

    // 只有被观察的 URL 才会被进度拦截器包装，离开时必须 unwatch，否则 map 会一直持有状态
    val progressKey = model as? String ?: ""
    val progressFlow: StateFlow<Float> = remember(progressKey) {
        if (progressKey.isEmpty()) MutableStateFlow(-1f) else ImageDownloadProgress.watch(progressKey)
    }
    DisposableEffect(progressKey) {
        onDispose { if (progressKey.isNotEmpty()) ImageDownloadProgress.unwatch(progressKey) }
    }
    val progress by progressFlow.collectAsStateWithLifecycle()

    Box(modifier = modifier) {
        if (underlayUrl != null && !settled) {
            AsyncImage(
                model = remember(underlayUrl) {
                    ImageRequest.Builder(context)
                        .data(underlayUrl)
                        .crossfade(false)
                        .build()
                },
                contentDescription = null,
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize()
            )
        }
        AsyncImage(
            model = remember(model, imageSizePx) {
                ImageRequest.Builder(context)
                    .data(model)
                    .crossfade(false)
                    .size(imageSizePx)
                    .build()
            },
            contentDescription = contentDescription,
            contentScale = contentScale,
            modifier = Modifier.fillMaxSize(),
            onState = { state ->
                settled = state is AsyncImagePainter.State.Success ||
                    state is AsyncImagePainter.State.Error
            }
        )
        if (!settled) {
            // 有底图时进度环缩小到底部，不挡住已经能看的画面；无底图时居中，替代整片空白
            val ringModifier = if (underlayUrl != null) {
                Modifier.align(Alignment.BottomCenter).padding(bottom = 40.dp).size(26.dp)
            } else {
                Modifier.align(Alignment.Center).size(44.dp)
            }
            if (progress > 0f) {
                CircularProgressIndicator(
                    progress = { progress },
                    modifier = ringModifier,
                    color = Color.White,
                    trackColor = Color.White.copy(alpha = 0.25f),
                    strokeWidth = 3.dp
                )
            } else {
                CircularProgressIndicator(
                    modifier = ringModifier,
                    color = Color.White.copy(alpha = 0.85f),
                    strokeWidth = 3.dp
                )
            }
        }
    }
}
