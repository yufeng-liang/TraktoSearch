package com.tracktosearch.ui.component

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.tracktosearch.R
import com.tracktosearch.ui.haptic.hapticClickable
import com.tracktosearch.ui.haptic.rememberAppHaptics
import kotlinx.coroutines.launch
import me.saket.telephoto.zoomable.DoubleClickToZoomListener
import me.saket.telephoto.zoomable.DynamicZoomSpec
import me.saket.telephoto.zoomable.EnabledZoomGestures
import me.saket.telephoto.zoomable.ZoomLimit
import me.saket.telephoto.zoomable.ZoomSpec
import me.saket.telephoto.zoomable.ZoomableImageState
import me.saket.telephoto.zoomable.coil.ZoomableAsyncImage
import me.saket.telephoto.zoomable.rememberZoomableImageState
import me.saket.telephoto.zoomable.rememberZoomableState

/**
 * 通用全屏图片查看 overlay：黑底 + 横滑翻页 + 双击/双指缩放 + 可选保存。
 * 用 AnimatedVisibility 包裹，`sharedKeyPrefix` 非空且共享转场开启时，
 * 每页图片以 `$sharedKeyPrefix-$page` 作为 sharedElement key，
 * 与缩略图端 [zoomSharedSource] 的相同 key 配对，实现 Telegram 风格的小图→大图缩放过渡。
 *
 * 手势、子采样与加载进度由 [ProgressiveFullscreenImage] 承担：双击在 1x 与动态上限之间循环，
 * 放大后 telephoto 的 nested scroll 自动消费横向拖动、缩小时交还给 pager 翻页。
 *
 * 注意：本组件必须一直处于组合中（用 `visible` 控制显隐），不能包在 `if` 里，
 * 否则共享元素两侧无法在同一帧共存，转场不会发生。
 * 例外：跨窗口场景（Dialog 盖 ModalBottomSheet）本就无法共享元素，
 * 此时传 `sharedKeyPrefix = null` 并用 [enter]/[exit] 指定 scale+fade 近似动画。
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun ZoomableImageOverlay(
    visible: Boolean,
    images: List<Any>,
    initialIndex: Int,
    sharedKeyPrefix: String?,
    onDismiss: () -> Unit,
    onSave: ((Int) -> Unit)? = null,
    isSavedAt: (Int) -> Boolean = { false },
    // 缩略图侧的裁剪方式，决定本侧的 resizeMode，见 ZoomThumbnailCrop。默认 Crop：
    // 除反馈截图（Fit 缩略图）之外，各入口的缩略图都是 ContentScale.Crop。
    thumbnailCrop: ZoomThumbnailCrop = ZoomThumbnailCrop.Crop,
    // 进入用 snap：共享元素位置/尺寸由 spring 负责，alpha 再叠 200ms fade 会双重动画；
    // 退出保留短 fade：缩回缩略图后遮罩平滑消失，避免黑幕瞬间闪断
    enter: EnterTransition = fadeIn(animationSpec = snap()),
    exit: ExitTransition = fadeOut(animationSpec = tween(120)),
) {
    val haptics = rememberAppHaptics()
    val safeInitial = initialIndex.coerceIn(0, (images.size - 1).coerceAtLeast(0))
    val closeDesc = stringResource(R.string.detail_close)

    AnimatedVisibility(
        visible = visible,
        enter = enter,
        exit = exit
    ) {
        val animatedVisibilityScope = this

        // pagerState 建在 AnimatedVisibility 内容里,每次打开都是新的一份,
        // initialPage 在首次组合就生效,打开动画的第一帧即落在目标页。
        //
        // 原先建在外层常驻组合,复位靠 LaunchedEffect 里的 scrollToPage —— 那要等到
        // 下一帧才跑,于是打开动画的第一帧渲染的还是上次看的那页,而且那页的 sharedElement
        // 会跟自己那张仍可见的缩略图配成一对被抬进转场 overlay。表现为在两张图之间反复
        // 开关时「打开 a 时 b 闪一下」。也顺带修掉「同一张图横滑几页后重开仍停在旧页」。
        //
        // 内容要等退出动画跑完才被丢弃,所以缩回缩略图的动画不受影响。
        val pagerState = rememberPagerState(initialPage = safeInitial, pageCount = { images.size })

        // 转场动画期间同时锁缩放手势和翻页：telephoto 只在放大后才用 nested scroll 接管横向拖动，
        // 手势被禁时它不消费，pager 仍能被滑走，把共享元素配对的目标页换掉。
        // 判定见 zoomTransitionPhase —— 本组件的 enter 是 snap，只看 AnimatedVisibility 的
        // transition.isRunning 会在第二帧就解锁。
        val phase = zoomTransitionPhase(animatedVisibilityScope)

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.92f))
                .statusBarsPadding()
                // 基准用这个 tag 断言查看器开了、又真的关掉了：本节点随 AnimatedVisibility 进出，
                // 退出动画播完才被移除，正好是「一次往返」的两个端点。
                // 本组件是通用件，剧照、人物图、反馈截图几处入口共用这一个 tag；
                // 详情页上只有剧照查看器走这里，不会歧义。
                .testTag("zoomable_image_overlay")
        ) {
            // 图片区：单击退出（放大时先复位）与返回键都由 ProgressiveFullscreenImage 统一处理。
            // 这里不再套一层全屏 clickable —— 图片节点本身就 fillMaxSize 且始终消费单击，
            // 那层永远收不到事件，只会给 TalkBack 多挂一个全屏可点节点。
            HorizontalPager(
                state = pagerState,
                userScrollEnabled = !phase.running,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                val url = images.getOrNull(page) ?: return@HorizontalPager
                ProgressiveFullscreenImage(
                    model = url,
                    contentScale = ContentScale.Fit,
                    contentDescription = stringResource(R.string.cd_image_page, page + 1, images.size),
                    gesturesEnabled = !phase.running,
                    deferZoomable = phase.opening,
                    transitionRunning = phase.running,
                    onRequestDismiss = onDismiss,
                    // 每页各自接管返回键：仅当前页组合，不会重复注册
                    backHandlerEnabled = true,
                    modifier = Modifier
                        .fillMaxSize()
                        .zoomSharedTarget(
                            key = sharedKeyPrefix?.let { "$it-$page" },
                            animatedVisibilityScope = animatedVisibilityScope,
                            thumbnailCrop = thumbnailCrop
                        )
                )
            }
            // 顶部操作栏（在图片之上，必须自己吃掉单击，否则空白处的点击会穿到下面的图片节点触发退出）
            // 不参与配对：缩略图那一侧没有对应物。改为等图片基本落位再淡入，并抬进转场 overlay ——
            // 转场期图片是画在 overlay 里的，普通兄弟节点会被它整块盖住。
            // if(visible)：chrome 抬进转场 overlay 后吃不到 AnimatedVisibility 的 fadeOut，
            // 退场动画形同虚设（关图时与页面按钮整段重叠）；关闭瞬间直接离开组合。
            if (visible) Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .appSharedOverlayChrome(animatedVisibilityScope)
                    .padding(horizontal = 8.dp, vertical = 8.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {}
                    ),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = {
                    haptics.lightTap()
                    onDismiss()
                }) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .background(Color.Black.copy(alpha = 0.4f), RoundedCornerShape(20.dp))
                            .border(1.dp, Color.White.copy(alpha = 0.35f), RoundedCornerShape(20.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Rounded.Close,
                            contentDescription = closeDesc,
                            tint = Color.White,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }

                // 页码（多图时显示）
                if (images.size > 1) {
                    Text(
                        text = "${pagerState.currentPage + 1} / ${images.size}",
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .background(Color.Black.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }

                // 保存按钮（可选）
                if (onSave != null) {
                    val idx = pagerState.currentPage
                    val saved = isSavedAt(idx)
                    val saveDescription = stringResource(
                        if (saved) R.string.cd_saved else R.string.cd_save
                    )
                    IconButton(
                        onClick = {
                            haptics.tap()
                            onSave(idx)
                        },
                        modifier = Modifier.semantics { contentDescription = saveDescription }
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .background(Color.Black.copy(alpha = 0.4f), RoundedCornerShape(20.dp))
                                .border(1.dp, Color.White.copy(alpha = 0.35f), RoundedCornerShape(20.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                if (saved) Icons.Rounded.Check else Icons.Rounded.Download,
                                contentDescription = null,
                                tint = if (saved) Color(0xFF4CAF50) else Color.White,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
/**
 * 全屏大图的缩放规格：上限交给 telephoto 的 [DynamicZoomSpec.recommend] 按实际图片尺寸算。
 *
 * 固定 2.5x 是「相对 fit 之后」的倍率：4K 剧照 fit 到 1080p 屏时 base 缩放约 0.28，
 * 2.5x 只到原图的 0.7 倍，永远看不到原生像素——白下 original，也白换子采样。
 * recommend 会把上限抬到至少能 1:1 看到原生像素，小图仍保底这里给的 2.5x。
 * 同时换掉已废弃的 `ZoomSpec(maxZoomFactor = ...)` 构造函数。
 */
private val FULLSCREEN_ZOOM_SPEC: DynamicZoomSpec =
    DynamicZoomSpec.recommend(ZoomSpec(maximum = ZoomLimit(factor = 2.5f)))

/** 全屏大图统一的 telephoto 状态，保证各入口手感与缩放上限一致。 */
@Composable
internal fun rememberFullscreenZoomableImageState(): ZoomableImageState =
    rememberZoomableImageState(rememberZoomableState(FULLSCREEN_ZOOM_SPEC))

/**
 * 「放大时先复位、未放大才关闭」的防误触协议收口。
 *
 * 图片本体的单击、返回键，以及 [ContentScale.Fit] 留出的黑边区域的单击都要走这里；
 * 黑边直接接 onDismiss 会让放大状态下点到边上就整个关掉。
 *
 * **这里一记触感都不发**：三条入口点到的都是图片/非按钮区域，用户规则「点击图片
 * 不应该有触感」—— 关闭走显式的 × 按钮（那里有自己的 lightTap）。调用方也不要
 * 再各自挂触感，同一次点击两记是双震。
 */
@Composable
internal fun rememberResetOrDismiss(
    state: ZoomableImageState,
    onDismiss: (() -> Unit)?,
): () -> Unit {
    val scope = rememberCoroutineScope()
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    return remember(state, scope) {
        {
            val zoomed = state.zoomableState.zoomFraction?.let { it > 0f } == true
            if (zoomed) scope.launch { state.zoomableState.resetZoom() } else currentOnDismiss?.invoke()
        }
    }
}

/**
 * 全屏大图统一入口：telephoto 缩放手势 + 子采样 + 内存缓存小图渐进底图。
 * 海报单图（PosterFullscreenOverlay）与多图 pager 共用，保证手势手感一致。
 *
 * 关闭协议见 [rememberResetOrDismiss]。[backHandlerEnabled] 为 true 时由本组件接管返回键
 * （多图 pager 下每页一个，非当前页不组合故不冲突）。
 */
@Composable
internal fun ZoomableFullscreenImage(
    model: Any,
    contentScale: ContentScale,
    modifier: Modifier = Modifier,
    state: ZoomableImageState = rememberFullscreenZoomableImageState(),
    gesturesEnabled: Boolean = true,
    onRequestDismiss: (() -> Unit)? = null,
    backHandlerEnabled: Boolean = false,
    contentDescription: String? = null,
) {
    val context = LocalContext.current
    val resetOrDismiss = rememberResetOrDismiss(state, onRequestDismiss)
    // ImageRequest 有 equals，重建不会触发重新加载，但每次重组都新建一份纯属浪费
    val request = remember(context, model) {
        fullscreenImageRequest(context, model, progressiveUnderlay(context, model))
    }

    if (backHandlerEnabled && onRequestDismiss != null) {
        BackHandler { resetOrDismiss() }
    }

    val hasDismiss = onRequestDismiss != null
    val tapHandler: ((Offset) -> Unit)? = remember(resetOrDismiss, hasDismiss) {
        if (hasDismiss) { { _: Offset -> resetOrDismiss() } } else null
    }
    // 转场期间双击也要锁：gestures 只管缩放/平移，单击与双击走的是另一个手势节点，
    // 不受它影响。把双击上限压到 1x 等于让双击什么都不做。
    val doubleClick = remember(gesturesEnabled) {
        if (gesturesEnabled) DoubleClickToZoomListener.cycle() else DoubleClickToZoomListener.cycle(1f)
    }

    ZoomableAsyncImage(
        model = request,
        contentDescription = contentDescription,
        contentScale = contentScale,
        state = state,
        gestures = if (gesturesEnabled) EnabledZoomGestures.ZoomAndPan else EnabledZoomGestures.None,
        onClick = tapHandler,
        onDoubleClick = doubleClick,
        modifier = modifier
    )
}
