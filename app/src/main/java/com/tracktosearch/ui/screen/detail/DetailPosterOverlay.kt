package com.tracktosearch.ui.screen.detail

import android.os.Environment
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.tracktosearch.R
import com.tracktosearch.data.remote.tmdb.TmdbImageUrls
import com.tracktosearch.ui.component.ProgressiveFullscreenImage
import com.tracktosearch.ui.component.queryExistingFile
import com.tracktosearch.ui.component.savePosterToGallery
import com.tracktosearch.ui.component.zoomSharedTarget
import com.tracktosearch.ui.haptic.HapticSemantic
import com.tracktosearch.ui.haptic.hapticClickable
import com.tracktosearch.ui.haptic.rememberAppHaptics
import com.tracktosearch.ui.util.showToast
import kotlinx.coroutines.launch
import net.engawapg.lib.zoomable.rememberZoomState
import net.engawapg.lib.zoomable.zoomable

// ==================== 海报大图查看 ====================

/**
 * 海报全屏查看 overlay：黑底 + 双击/双指缩放 + 可选保存。
 * 用 AnimatedVisibility 包裹，`sharedKeyPrefix` 非空且共享转场开启时，
 * 图片以 `sharedKeyPrefix` 作为 sharedElement key，与详情页头部海报源（zoomSharedSource）配对，
 * 实现 Telegram 风格的小图→大图缩放过渡。
 * 手势：单击退出；若已放大则单击先复位，再次单击才退出；双击切换 1x/2.5x。
 * 注意：本组件必须一直处于组合中（用 `visible` 控制显隐），不能包在 `if` 里。
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun PosterFullscreenOverlay(
    visible: Boolean,
    posterUrl: String,
    title: String,
    sharedKeyPrefix: String?,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val savedToAlbumToast = stringResource(R.string.gallery_saved_to_album)
    val scope = rememberCoroutineScope()
    // AnimatedVisibility 的内容不是独立窗口，宿主 View 与本函数一致，取一份共用即可
    val haptics = rememberAppHaptics()
    var isSaved by remember { mutableStateOf<Boolean?>(null) } // null=未检查, true=已保存, false=未保存

    // 进入时检查是否已保存（仅打开大图时查询，避免每次进详情页都跑 MediaStore 查询）
    LaunchedEffect(visible, posterUrl, title) {
        if (!visible) return@LaunchedEffect
        val safeName = title.replace(Regex("[^a-zA-Z0-9\\u4e00-\\u9fa5]"), "_")
        val filename = "TrackToSearch_${safeName}.jpg"
        val relativePath = Environment.DIRECTORY_PICTURES + "/TrackToSearch"
        isSaved = queryExistingFile(context, filename, relativePath) != null
    }

    val zoomState = rememberZoomState()

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = tween(200)),
        exit = fadeOut(animationSpec = tween(200))
    ) {
        val animatedVisibilityScope = this

        BackHandler(enabled = true) {
            if (zoomState.scale > 1f) {
                scope.launch { zoomState.changeScale(1f, Offset.Zero) }
            } else {
                onDismiss()
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.92f))
                .hapticClickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    semantic = HapticSemantic.LIGHT_TAP,
                    onClick = {
                        if (zoomState.scale > 1f) {
                            scope.launch { zoomState.changeScale(1f, Offset.Zero) }
                        } else {
                            onDismiss()
                        }
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            // 海报图片（放大显示，拦截点击事件不触发外层dismiss）
            ProgressiveFullscreenImage(
                // 全屏查看用 original 原图:1080p 屏全屏显示约 1050px,
                // w500 源图放大到 1080 解码会模糊,original(2000px+) 保证清晰;
                // 下载大但仅在用户主动查看大图时触发。
                // 渐进底图为 w780（详情页头部已加载过，点开即可见），大图到位后覆盖。
                model = remember(posterUrl) { TmdbImageUrls.swapSize(posterUrl, "original") },
                contentScale = ContentScale.Fit,
                contentDescription = title,
                modifier = Modifier
                    .fillMaxWidth(0.85f)
                    .aspectRatio(2f / 3f)
                    .zoomSharedTarget(
                        key = sharedKeyPrefix,
                        animatedVisibilityScope = animatedVisibilityScope,
                        clipShape = RoundedCornerShape(12.dp)
                    )
                    // 单击退出（已放大时先复位）、双击缩放统一交给 zoomable 自带回调
                    .zoomable(
                        zoomState,
                        onTap = {
                            // 与外层「点背景退出」那个面同一记 LIGHT_TAP：两处行为一样，
                            // 落点不同却手感不同才是怪的
                            haptics.lightTap()
                            if (zoomState.scale > 1f) {
                                scope.launch { zoomState.changeScale(1f, Offset.Zero) }
                            } else {
                                onDismiss()
                            }
                        },
                        onDoubleTap = { tapOffset ->
                            if (zoomState.scale > 1f) {
                                // 已放大 → 还原
                                zoomState.changeScale(1f, Offset.Zero)
                            } else {
                                // 未放大 → 放大到 2.5x,以双击位置为中心
                                zoomState.changeScale(2.5f, tapOffset)
                            }
                        }
                    )
            )

            // 顶部操作栏（在图片之上，也需要拦截点击）
            // 原 Haze 毛玻璃已移除以降低持续渲染开销，改用半透明黑色背景
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .padding(horizontal = 16.dp, vertical = 48.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {}
                    ),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 关闭按钮（半透明黑色背景）
                IconButton(onClick = { haptics.lightTap(); onDismiss() }) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .background(
                                color = Color.Black.copy(alpha = 0.40f),
                                shape = RoundedCornerShape(20.dp)
                            )
                            .border(
                                width = 1.dp,
                                color = Color.White.copy(alpha = 0.35f),
                                shape = RoundedCornerShape(20.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Rounded.Close,
                            contentDescription = stringResource(R.string.detail_back),
                            tint = Color.White,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }

                // 保存按钮（已保存时显示勾选图标，半透明黑色背景）
                IconButton(onClick = {
                    haptics.tap()
                    if (isSaved == true) {
                        context.showToast(savedToAlbumToast)
                    } else {
                        // 保存用与屏幕显示一致的 original 原图，所见即所得（此前存的是 w342 小图）
                        savePosterToGallery(
                            context,
                            scope,
                            TmdbImageUrls.swapSize(posterUrl, "original"),
                            title
                        ) {
                            isSaved = true
                        }
                    }
                }) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .background(
                                color = Color.Black.copy(alpha = 0.40f),
                                shape = RoundedCornerShape(20.dp)
                            )
                            .border(
                                width = 1.dp,
                                color = Color.White.copy(alpha = 0.35f),
                                shape = RoundedCornerShape(20.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isSaved == true) {
                            Icon(
                                Icons.Rounded.Check,
                                contentDescription = stringResource(R.string.content_desc_saved),
                                tint = Color(0xFF4CAF50), // 绿色
                                modifier = Modifier.size(22.dp)
                            )
                        } else {
                            Icon(
                                Icons.Rounded.Download,
                                contentDescription = stringResource(R.string.content_desc_save),
                                tint = Color.White,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
