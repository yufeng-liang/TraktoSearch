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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.tracktosearch.R
import kotlinx.coroutines.launch
import me.saket.telephoto.zoomable.EnabledZoomGestures
import me.saket.telephoto.zoomable.ZoomSpec
import me.saket.telephoto.zoomable.ZoomableImageState
import me.saket.telephoto.zoomable.coil.ZoomableAsyncImage
import me.saket.telephoto.zoomable.rememberZoomableImageState
import me.saket.telephoto.zoomable.rememberZoomableState
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

/**
 * 通用全屏图片查看 overlay：黑底 + 横滑翻页 + 双击/双指缩放 + 可选保存。
 * 用 AnimatedVisibility 包裹，`sharedKeyPrefix` 非空且共享转场开启时，
 * 每页图片以 `$sharedKeyPrefix-$page` 作为 sharedElement key，
 * 与缩略图端 [zoomSharedSource] 的相同 key 配对，实现 Telegram 风格的小图→大图缩放过渡。
 *
 * 手势与子采样由 telephoto [ZoomableFullscreenImage] 承担：双击在 1x/2.5x 间切换，
 * 放大后 telephoto 的 nested scroll 自动消费横向拖动、缩小时交还给 pager 翻页，
 * 无需再手工锁 userScrollEnabled。
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
    // 进入用 snap：共享元素位置/尺寸由 spring 负责，alpha 再叠 200ms fade 会双重动画；
    // 退出保留短 fade：缩回缩略图后遮罩平滑消失，避免黑幕瞬间闪断
    enter: EnterTransition = fadeIn(animationSpec = snap()),
    exit: ExitTransition = fadeOut(animationSpec = tween(120)),
) {
    val scope = rememberCoroutineScope()
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

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.92f))
                .statusBarsPadding()
        ) {
            // 图片区：点背景退出（放大时先复位，由 ZoomableFullscreenImage 统一处理）
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss
                    ),
                contentAlignment = Alignment.Center
            ) {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize()
                ) { page ->
                    val url = images.getOrNull(page) ?: return@HorizontalPager
                    ZoomableFullscreenImage(
                        model = url,
                        contentScale = ContentScale.Fit,
                        // 转场动画期间锁手势，避免手势 transform 与共享元素转场互相打架
                        gesturesEnabled = animatedVisibilityScope.transition.isRunning.not(),
                        onRequestDismiss = onDismiss,
                        // 每页各自接管返回键：仅当前页组合，不会重复注册
                        backHandlerEnabled = true,
                        modifier = Modifier
                            .fillMaxSize()
                            .zoomSharedTarget(
                                key = sharedKeyPrefix?.let { "$it-$page" },
                                animatedVisibilityScope = animatedVisibilityScope
                            )
                    )
                }
            }

            // 顶部操作栏
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .padding(horizontal = 8.dp, vertical = 8.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {}
                    ),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onDismiss) {
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
                        onClick = { onSave(idx) },
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
 * 全屏大图统一入口：telephoto 缩放手势 + 子采样 + w780 渐进底图占位。
 * 海报单图（PosterFullscreenOverlay）与多图 pager 共用，保证手势手感一致。
 *
 * 关闭协议：[onRequestDismiss] 触发时若已放大（zoomFraction > 0）先复位到初始大小，
 * 未放大才真正回调关闭，恢复「放大时单击/返回先复位」的防误触语义。
 * [backHandlerEnabled] 为 true 时由本组件接管返回键（多图 pager 下每页一个，
 * 非当前页不组合故不冲突）。双击缩放交给 telephoto 默认 cycle()（1x↔最大倍率）。
 */
@Composable
internal fun ZoomableFullscreenImage(
    model: Any,
    contentScale: ContentScale,
    modifier: Modifier = Modifier,
    state: ZoomableImageState = rememberZoomableImageState(
        rememberZoomableState(zoomSpec = ZoomSpec(maxZoomFactor = 2.5f))
    ),
    gesturesEnabled: Boolean = true,
    onRequestDismiss: (() -> Unit)? = null,
    backHandlerEnabled: Boolean = false,
    contentDescription: String? = null,
    onDisplayedChanged: ((Boolean) -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val underlayUrl = remember(model) { progressiveUnderlay(model) }

    fun resetOrDismiss() {
        val zoomed = state.zoomableState.zoomFraction.let { it != null && it > 0f }
        if (zoomed) scope.launch { state.zoomableState.resetZoom() }
        else onRequestDismiss?.invoke()
    }

    if (backHandlerEnabled && onRequestDismiss != null) {
        BackHandler { resetOrDismiss() }
    }

    if (onDisplayedChanged != null) {
        DisposableEffect(state) {
            onDispose { onDisplayedChanged(false) }
        }
        LaunchedEffect(state) {
            snapshotFlow { state.isImageDisplayed }.collect { onDisplayedChanged(it) }
        }
    }

    val tapHandler: ((Offset) -> Unit)? = if (onRequestDismiss != null) {
        { _ -> resetOrDismiss() }
    } else {
        null
    }

    ZoomableAsyncImage(
        model = fullscreenImageRequest(context, model, underlayUrl),
        contentDescription = contentDescription,
        contentScale = contentScale,
        state = state,
        gestures = if (gesturesEnabled) EnabledZoomGestures.ZoomAndPan else EnabledZoomGestures.None,
        onClick = tapHandler,
        modifier = modifier
    )
}
