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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.request.ImageRequest
import com.tracktosearch.data.remote.ImageDownloadProgress
import com.tracktosearch.data.remote.tmdb.TmdbImageUrls
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 全屏大图的渐进底图 URL：把 TMDB 尺寸段换成 w780。
 *
 * 缩略图/详情页头部本来就用 w780，点开时它已在 Coil 内存缓存里，通过
 * [ImageRequest.Builder.placeholderMemoryCacheKey] 作为 telephoto 的原生占位图瞬时显示，
 * 避免「先空白、几秒后整张大图突然出现」。非 TMDB `/t/p/` 结构（豆瓣图、fanart 人物图）
 * 或本身就是 w780 时返回 null，不额外发请求。
 */
internal fun progressiveUnderlay(model: Any?): String? {
    val url = model as? String ?: return null
    val swapped = TmdbImageUrls.swapSize(url, "w780")
    return swapped.takeIf { it != url }
}

/**
 * 全屏大图请求：original 大图 + w780 内存缓存底图作占位。
 *
 * telephoto 的 Coil 集成会把占位 drawable 画在同一位置（同一 ContentScale/构图），
 * 大图到位后原地替换，全程只有一个绘制节点——替代旧实现的两层 AsyncImage 叠加
 * （旧方案在共享元素转场期间双层绘制、尺寸不一致，是打开卡顿的根因之一）。
 *
 * 请求尺寸不指定 .size(1080)：telephoto 需要原图落盘后做子采样分块解码，
 * 缩到 1080 会让磁盘缓存里只有缩放后的小图，放大就糊。
 */
internal fun fullscreenImageRequest(context: android.content.Context, model: Any, underlayUrl: String?): ImageRequest {
    val builder = ImageRequest.Builder(context)
    builder.data(model).crossfade(false)
    if (underlayUrl != null) {
        builder.placeholderMemoryCacheKey(underlayUrl)
    }
    return builder.build()
}

/**
 * 全屏大图渐进显示 + 加载进度（保留旧对外签名，内部已换成 telephoto 单节点）。
 *
 * 1. w780 缓存底图作为占位立刻可见（若有）；
 * 2. original 大图加载完成原地覆盖；
 * 3. 加载期间的进度环——有 Content-Length 时为确定性进度（original 剧照常 2-5MB），
 *    拿不到长度时退化为不确定 spinner。
 *
 * 共享元素与手势修饰符由调用方挂在 [modifier] 上。
 */
@Composable
internal fun ProgressiveFullscreenImage(
    model: Any,
    contentScale: ContentScale,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    gesturesEnabled: Boolean = true,
    onClick: ((androidx.compose.ui.geometry.Offset) -> Unit)? = null,
) {
    val underlayUrl = remember(model) { progressiveUnderlay(model) }

    // 只有被观察的 URL 才会被进度拦截器包装，离开时必须 unwatch，否则 map 会一直持有状态
    val progressKey = model as? String ?: ""
    val progressFlow: StateFlow<Float> = remember(progressKey) {
        if (progressKey.isEmpty()) MutableStateFlow(-1f) else ImageDownloadProgress.watch(progressKey)
    }
    DisposableEffect(progressKey) {
        onDispose { if (progressKey.isNotEmpty()) ImageDownloadProgress.unwatch(progressKey) }
    }
    val progress by progressFlow.collectAsStateWithLifecycle()

    var settled by remember(model) { mutableStateOf(false) }

    Box(modifier = modifier) {
        ZoomableFullscreenImage(
            model = model,
            contentScale = contentScale,
            contentDescription = contentDescription,
            gesturesEnabled = gesturesEnabled,
            onClick = onClick,
            modifier = Modifier.fillMaxSize(),
            onDisplayedChanged = { settled = it }
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
