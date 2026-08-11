package com.tracktosearch.ui.component

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.tracktosearch.R
import kotlinx.coroutines.launch
import net.engawapg.lib.zoomable.rememberZoomState
import net.engawapg.lib.zoomable.zoomable

/**
 * 通用全屏图片查看 overlay：黑底 + 横滑翻页 + 双击/双指缩放 + 可选保存。
 * 用 AnimatedVisibility 包裹，`sharedKeyPrefix` 非空且共享转场开启时，
 * 每页图片以 `$sharedKeyPrefix-$page` 作为 sharedElement key，
 * 与缩略图端相同 key 配对，实现 Telegram 风格的小图→大图缩放过渡。
 * 注意：本组件必须一直处于组合中（用 `visible` 控制显隐），不能包在 `if` 里。
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
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
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
        enter = fadeIn(animationSpec = tween(200)),
        exit = fadeOut(animationSpec = tween(200))
    ) {
        val sharedTransitionScope = LocalSharedTransitionScope.current
        val sharedEnabled = LocalSharedTransitionEnabled.current
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
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
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
                    val sharedModifier = if (sharedTransitionScope != null && sharedEnabled && sharedKeyPrefix != null) {
                        with(sharedTransitionScope) {
                            Modifier.sharedElement(
                                rememberSharedContentState(key = "$sharedKeyPrefix-$page"),
                                animatedVisibilityScope = animatedVisibilityScope
                            )
                        }
                    } else Modifier
                    AsyncImage(
                        model = remember(url) {
                            ImageRequest.Builder(context)
                                .data(url)
                                .crossfade(false)
                                .size(1080)
                                .build()
                        },
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .then(sharedModifier)
                            .pointerInput(zoomState) {
                                detectTapGestures(
                                    onDoubleTap = { tapOffset ->
                                        if (zoomState.scale > 1f) {
                                            scope.launch { zoomState.changeScale(1f, Offset.Zero) }
                                        } else {
                                            scope.launch { zoomState.changeScale(2.5f, tapOffset) }
                                        }
                                    }
                                )
                            }
                            .zoomable(zoomState)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = {} // 拦截点击，不触发外层 dismiss
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
                    IconButton(onClick = { onSave(idx) }) {
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
