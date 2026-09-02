package com.tracktosearch.ui.component

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.tracktosearch.R
import com.tracktosearch.ui.haptic.HapticSemantic
import com.tracktosearch.ui.haptic.hapticClickable
import com.tracktosearch.ui.haptic.rememberAppHaptics
import kotlinx.coroutines.launch
import net.engawapg.lib.zoomable.rememberZoomState
import net.engawapg.lib.zoomable.zoomable
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

/**
 * 通用全屏图片查看 overlay：黑底 + 横滑翻页 + 双击/双指缩放 + 可选保存。
 * 用 AnimatedVisibility 包裹，`sharedKeyPrefix` 非空且共享转场开启时，
 * 每页图片以 `$sharedKeyPrefix-$page` 作为 sharedElement key，
 * 与缩略图端 [zoomSharedSource] 的相同 key 配对，实现 Telegram 风格的小图→大图缩放过渡。
 *
 * 手势：单击退出；若已放大则单击先复位到初始大小，再次单击才退出；双击切换 1x/2.5x。
 *
 * 注意：本组件必须一直处于组合中（用 `visible` 控制显隐），不能包在 `if` 里，
 * 否则共享元素两侧无法在同一帧共存，转场不会发生。
 * 例外：跨窗口场景（盖在 ModalBottomSheet 之上的 Dialog）本就无法共享元素，
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
    enter: EnterTransition = fadeIn(animationSpec = tween(200)),
    exit: ExitTransition = fadeOut(animationSpec = tween(200)),
) {
    val scope = rememberCoroutineScope()
    val haptics = rememberAppHaptics()
    val safeInitial = initialIndex.coerceIn(0, (images.size - 1).coerceAtLeast(0))
    val pagerState = rememberPagerState(initialPage = safeInitial, pageCount = { images.size })
    val zoomState = rememberZoomState()
    val closeDesc = stringResource(R.string.detail_close)

    // 切页时重置缩放
    LaunchedEffect(pagerState.currentPage) { zoomState.reset() }

    // 重开/目标页变化时复位到对应页：pagerState 创建于 AnimatedVisibility 之外并常驻组合,
    // initialPage 仅在首次组合生效;Dialog 改内联后若不主动复位,重开会停留在上次滑动到的页。
    // 同时键住 visible,保证"关闭→重开"即使目标页未变化也会复位;复位时同步重置缩放。
    LaunchedEffect(visible, safeInitial) {
        if (visible) {
            pagerState.scrollToPage(safeInitial)
            zoomState.reset()
        }
    }

    AnimatedVisibility(
        visible = visible,
        enter = enter,
        exit = exit
    ) {
        val animatedVisibilityScope = this

        BackHandler(enabled = true) {
            if (zoomState.scale > 1f) scope.launch { zoomState.changeScale(1f, Offset.Zero) }
            else onDismiss()
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.92f))
                .statusBarsPadding()
        ) {
            // 图片区：点背景退出
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .hapticClickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        semantic = HapticSemantic.LIGHT_TAP,
                        onClick = {
                            if (zoomState.scale > 1f) scope.launch { zoomState.changeScale(1f, Offset.Zero) }
                            else onDismiss()
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                HorizontalPager(
                    state = pagerState,
                    userScrollEnabled = zoomState.scale <= 1f,
                    modifier = Modifier.fillMaxSize()
                ) { page ->
                    val url = images.getOrNull(page) ?: return@HorizontalPager
                    ProgressiveFullscreenImage(
                        model = url,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .zoomSharedTarget(
                                key = sharedKeyPrefix?.let { "$it-$page" },
                                animatedVisibilityScope = animatedVisibilityScope
                            )
                            // 单击退出（已放大时先复位）、双击缩放都交给 zoomable 自带回调，
                            // 避免再叠一层 detectTapGestures 与它争抢手势
                            .zoomable(
                                zoomState,
                                onTap = {
                                    // 与外层「点背景退出」那个面同一记 LIGHT_TAP：两处行为完全一样
                                    // （已放大先复位，否则退出），落点不同却手感不同才是怪的
                                    haptics.lightTap()
                                    if (zoomState.scale > 1f) {
                                        scope.launch { zoomState.changeScale(1f, Offset.Zero) }
                                    } else {
                                        onDismiss()
                                    }
                                },
                                onDoubleTap = { tapOffset ->
                                    if (zoomState.scale > 1f) {
                                        zoomState.changeScale(1f, Offset.Zero)
                                    } else {
                                        zoomState.changeScale(2.5f, tapOffset)
                                    }
                                }
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
