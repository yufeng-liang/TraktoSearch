package com.tracktosearch.ui.component

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.imageLoader
import coil.memory.MemoryCache
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.tracktosearch.data.remote.ImageDownloadProgress
import com.tracktosearch.data.remote.tmdb.TmdbImageUrls
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import me.saket.telephoto.zoomable.ZoomableImageState

/**
 * 渐进底图的候选尺寸段，按「越大越清晰」排序。
 * 详情页头部海报与剧照缩略图用 w780，人物图缩略图用 h632，列表卡片与详情页兜底海报用 w342。
 * w300 只有剧照列表的 loading 垫图在用——「刚滚到那张剧照、w780 还没解码完就点开」的
 * 窗口期里缓存中只有 w300，少了这个候选那一瞬会退成白屏转圈。
 */
private val UNDERLAY_SIZES = listOf("w780", "h632", "w500", "w342", "w300")

/**
 * 全屏大图的渐进底图 key：在 Coil 内存缓存里挑一张同图的更小尺寸位图，交给 telephoto 的原生
 * 占位图机制（[ImageRequest.Builder.placeholderMemoryCacheKey]）瞬时显示，
 * 避免「先空白、几秒后整张大图突然出现」。
 *
 * 候选逐个查内存缓存，命中即用；全不命中返回 null。placeholderMemoryCacheKey 只读内存缓存、
 * 不会额外发请求，所以未命中的唯一代价是没有底图（退化成转圈）。
 * 候选末尾带上 model 自身：豆瓣海报没有尺寸段可换、人物图全屏与缩略图是同一张图，
 * 这两处现成的底图就在自己这个 key 下——缩略图按 200/300/360px 解码，而 Coil 2 无 transformation
 * 时内存缓存 key 不含尺寸，同 URL 就能取到那份小位图。
 *
 * 原实现只猜 w780：人物图缩略图是 h632、豆瓣图没有尺寸段，两处底图永远 miss。
 */
internal fun progressiveUnderlay(context: Context, model: Any?): String? {
    val url = model as? String ?: return null
    val memoryCache = context.imageLoader.memoryCache ?: return null
    val candidates = UNDERLAY_SIZES.mapNotNull { size ->
        TmdbImageUrls.swapSize(url, size).takeIf { it != url }
    } + url
    return candidates.firstOrNull { memoryCache[MemoryCache.Key(it)] != null }
}

/**
 * 全屏大图请求：original 大图 + 内存缓存里的小尺寸底图作占位。
 *
 * telephoto 的 Coil 集成会把占位 drawable 画在同一位置（同一 ContentScale/构图），
 * 大图到位后以 crossfade 淡入覆盖在占位之上（占位保持可见、大图 alpha 0→1），
 * 全程只有一个绘制节点——替代旧实现的两层 AsyncImage 叠加
 * （旧方案在共享元素转场期间双层绘制、尺寸不一致，是打开卡顿的根因之一）。
 *
 * 请求尺寸不指定 .size(1080)：telephoto 需要原图落盘后做子采样分块解码，
 * 缩到 1080 会让磁盘缓存里只有缩放后的小图，放大就糊。
 *
 * 内存缓存设 READ_ONLY：不限尺寸解码的 original 剧照可达数十 MB（4K 图按 ARGB_8888 约 33MB），
 * 写进内存缓存会把占堆 30% 的海报缓存整片挤掉；而 telephoto 显示走的是磁盘子采样分块，
 * 并不依赖这份整图位图。代价是重复打开同一张要重新解码，收益是列表海报缓存不被冲掉。
 * READ_ONLY 仍允许读，占位底图查缓存不受影响。
 */
internal fun fullscreenImageRequest(context: Context, model: Any, underlayUrl: String?): ImageRequest {
    val builder = ImageRequest.Builder(context)
        .data(model)
        // 250ms 的「逐渐清晰」淡入：telephoto 读出 Coil CrossfadeTransition 的时长，
        // 用 animateFloatAsState 在占位小图之上淡入高清大图（占位保持可见）。
        .crossfade(250)
        .memoryCachePolicy(CachePolicy.READ_ONLY)
    if (underlayUrl != null) {
        builder.placeholderMemoryCacheKey(underlayUrl)
    }
    return builder.build()
}

/**
 * 转场期顶替 telephoto 的静态底图：直接把内存缓存里那张已解码位图画出来。
 *
 * 不能用 AsyncImage —— 它至少要等一帧才拿到图，而那一帧正是共享元素落在缩略图位置的第一帧，
 * 缩略图此时已被置为不可见，于是会闪一下空白。从 [MemoryCache] 直接取位图是同步的，
 * 首帧就有内容。取不到（缓存里没有同图的小尺寸段）时返回 null，调用方据此退回直接挂 telephoto。
 */
@Composable
private fun rememberUnderlayPainter(model: Any): BitmapPainter? {
    val context = LocalContext.current
    return remember(context, model) {
        val key = progressiveUnderlay(context, model)
        val bitmap = key?.let { context.imageLoader.memoryCache?.get(MemoryCache.Key(it))?.bitmap }
        bitmap?.let { BitmapPainter(it.asImageBitmap()) }
    }
}

/** 无底图时的出场延迟：本地解码、缓存命中的快加载在这段时间内就完成，环来不及闪。 */
private const val RingAppearDelayMs = 200L

/** 有底图时的兜底超时：画面已经能看，正常网络下环不该出现；慢网超时才浮出小环，免得像卡死。 */
private const val RingUnderlayTimeoutMs = 2_000L

/**
 * 加载进度环。
 *
 * 单独拆成一个 composable 是为了把三个高频 State 读取（下载进度、telephoto 的两个显示标志）
 * 关在这个叶子作用域里。原先它们直接读在 [ProgressiveFullscreenImage] 的函数体内，
 * 于是每来一个进度包就要重组整个 Box —— 里面装着 telephoto，而进度包在大图下载期间每几十毫秒一个。
 *
 * 出场时机分两档：无底图 200ms 后出现（顶替整片空白），有底图 2s 兜底超时后才出现——
 * 有底图时画面已经能看（小图渐清晰淡入中），环只会是打扰。
 *
 * @param visible 为 false 时整体不画。转场期延迟挂载 telephoto 那一程要传 false：
 *   那时 telephoto 还没挂上，[ZoomableImageState.isImageDisplayed] 恒为 false，
 *   不关掉就会在飞行中的图片上叠一个转圈。倒计时也从本组合（telephoto 实际挂载）起算。
 */
@Composable
private fun BoxScope.FullscreenImageProgressRing(
    state: ZoomableImageState,
    progressFlow: StateFlow<Float>,
    visible: Boolean,
) {
    if (!visible || state.isImageDisplayed) return
    val hasUnderlay = state.isPlaceholderDisplayed
    var showRing by remember { mutableStateOf(false) }
    LaunchedEffect(hasUnderlay) {
        if (!showRing) {
            delay(if (hasUnderlay) RingUnderlayTimeoutMs else RingAppearDelayMs)
            showRing = true
        }
    }
    if (!showRing) return
    val progress by progressFlow.collectAsStateWithLifecycle()
    // 有底图时进度环缩小到底部，不挡住已经能看的画面；无底图时居中，替代整片空白
    val ringModifier = if (hasUnderlay) {
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

/**
 * 全屏大图渐进显示 + 加载进度。
 *
 * 1. 内存缓存里的小尺寸底图作为占位立刻可见（若有）；
 * 2. original 大图加载完成原地覆盖；
 * 3. 加载期间的进度环——有 Content-Length 时为确定性进度（original 剧照常 2-5MB），
 *    拿不到长度时退化为不确定 spinner。出场按底图分档：无底图 200ms 后居中出现，
 *    有底图则平时完全不出现（画面已能看、淡入自会揭示进度），慢网超 2s 才缩在底部兜底。
 *
 * 出场分档依据 telephoto 的 [ZoomableImageState.isPlaceholderDisplayed]：底图真的画出来了
 * 才算「有底图」。原来靠「底图 URL 是否非空」猜，猜中与否和实际有没有画出来无关。
 *
 * 共享元素与手势修饰符由调用方挂在 [modifier] 上；
 * 关闭协议参数（[onRequestDismiss]/[backHandlerEnabled]）原样透传 [ZoomableFullscreenImage]。
 *
 * @param deferZoomable 转场进行中时先不挂 telephoto，改画一张内存缓存里的静态底图。
 *   telephoto 的子采样分块是按视口尺寸算的，共享元素转场期间视口每帧都在变，
 *   一次打开动画能触发几十轮重新分块解码，正是「点开大图卡一下」的主因。
 *   只在打开这一程传 true，且缓存里真有底图时才生效；没有底图时按原路挂 telephoto，
 *   否则转场期会是一片空白，比卡顿更糟。
 *   底图不随转场结束撤走：telephoto 挂载后它自己的占位图要到 Coil 异步解析 + 下一轮
 *   重组才真正画出来（实测有 200ms+ 空窗），期间底图垫在 telephoto 下面兜住画面，
 *   直到 [ZoomableImageState.isImageDisplayed] 才撤。
 */
@Composable
internal fun ProgressiveFullscreenImage(
    model: Any,
    contentScale: ContentScale,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    state: ZoomableImageState = rememberFullscreenZoomableImageState(),
    gesturesEnabled: Boolean = true,
    deferZoomable: Boolean = false,
    onRequestDismiss: (() -> Unit)? = null,
    backHandlerEnabled: Boolean = false,
) {
    // 只有被观察的 URL 才会被进度拦截器包装，离开时必须 unwatch，否则 map 会一直持有状态
    val progressKey = model as? String ?: ""
    val progressFlow: StateFlow<Float> = remember(progressKey) {
        if (progressKey.isEmpty()) MutableStateFlow(-1f) else ImageDownloadProgress.watch(progressKey)
    }
    DisposableEffect(progressKey) {
        onDispose { if (progressKey.isNotEmpty()) ImageDownloadProgress.unwatch(progressKey) }
    }

    val underlayPainter = rememberUnderlayPainter(model)
    val deferred = deferZoomable && underlayPainter != null

    Box(modifier = modifier) {
        if (deferred) {
            // telephoto 未挂载的这一程它自己的返回键也没挂，这里补一个：
            // 转场期缩放必然在原位，reset 与 dismiss 等价，直接 dismiss。
            if (backHandlerEnabled && onRequestDismiss != null) {
                BackHandler { onRequestDismiss() }
            }
        }
        // 静态底图不能只在转场期存在：telephoto 挂载后，它的占位图从 Coil 异步解析
        // 到真正画出来之间有实测 200ms+ 的空窗（此期间 resolved.placeholder 已写入
        // 但重组迟迟不来），若底图在交接点撤走，这一段就露出遮罩下的页面——「闪一下」。
        // 垫在 telephoto 下面，直到大图真正显示（isImageDisplayed）才撤。
        if (underlayPainter != null && (deferred || !state.isImageDisplayed)) {
            Image(
                painter = underlayPainter,
                contentDescription = null,
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize()
            )
        }
        if (!deferred) {
            ZoomableFullscreenImage(
                model = model,
                contentScale = contentScale,
                contentDescription = contentDescription,
                state = state,
                gesturesEnabled = gesturesEnabled,
                onRequestDismiss = onRequestDismiss,
                backHandlerEnabled = backHandlerEnabled,
                modifier = Modifier.fillMaxSize()
            )
        }
        FullscreenImageProgressRing(
            state = state,
            progressFlow = progressFlow,
            visible = !deferred
        )
    }
}
