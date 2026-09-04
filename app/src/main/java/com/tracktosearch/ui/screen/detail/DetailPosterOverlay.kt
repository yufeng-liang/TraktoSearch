package com.tracktosearch.ui.screen.detail

import android.os.Environment
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.tracktosearch.R
import com.tracktosearch.data.remote.tmdb.TmdbImageUrls
import com.tracktosearch.ui.component.ProgressiveFullscreenImage
import com.tracktosearch.ui.component.queryExistingFile
import com.tracktosearch.ui.component.rememberFullscreenZoomableImageState
import com.tracktosearch.ui.component.rememberResetOrDismiss
import com.tracktosearch.ui.component.savePosterToGallery
import com.tracktosearch.ui.component.zoomSharedTarget
import com.tracktosearch.ui.component.zoomTransitionPhase
import com.tracktosearch.ui.component.appSharedOverlayChrome
import com.tracktosearch.ui.util.showToast
import com.tracktosearch.ui.theme.WatchedGreen
import kotlinx.coroutines.launch

// ==================== 海报大图查看 ====================

/**
 * 海报全屏查看 overlay：黑底 + 双击/双指缩放 + 可选保存。
 * 用 AnimatedVisibility 包裹，`sharedKeyPrefix` 非空且共享转场开启时，
 * 图片以 `sharedKeyPrefix` 作为 sharedElement key，与详情页头部海报源（zoomSharedSource）配对，
 * 实现 Telegram 风格的小图→大图缩放过渡。
 * 手势（单击退出、双击 1x/2.5x、双指缩放）由 telephoto 统一承担。
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
    var isSaved by remember { mutableStateOf<Boolean?>(null) } // null=未检查, true=已保存, false=未保存

    // 进入时检查是否已保存（仅打开大图时查询，避免每次进详情页都跑 MediaStore 查询）
    LaunchedEffect(visible, posterUrl, title) {
        if (!visible) return@LaunchedEffect
        val safeName = title.replace(Regex("[^a-zA-Z0-9\\u4e00-\\u9fa5]"), "_")
        val filename = "TrackToSearch_${safeName}.jpg"
        val relativePath = Environment.DIRECTORY_PICTURES + "/TrackToSearch"
        isSaved = queryExistingFile(context, filename, relativePath) != null
    }

    AnimatedVisibility(
        visible = visible,
        // 进入用 snap：共享元素由 spring 负责，alpha 叠 fade 会双重动画；
        // 退出保留短 fade：缩回缩略图后遮罩平滑消失
        enter = fadeIn(animationSpec = snap()),
        exit = fadeOut(animationSpec = tween(120))
    ) {
        val animatedVisibilityScope = this
        // 状态提到这里：黑边单击也要走「放大时先复位」的同一份协议，
        // 否则放大后点到海报以外的黑底会直接把查看器关掉
        val imageState = rememberFullscreenZoomableImageState()
        val resetOrDismiss = rememberResetOrDismiss(imageState, onDismiss)
        // 开合阶段判定见 zoomTransitionPhase：本组件的 enter 是 snap，
        // 只看 AnimatedVisibility 的 transition.isRunning 会在第二帧就判定转场结束。
        val phase = zoomTransitionPhase(animatedVisibilityScope)

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.92f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = resetOrDismiss
                )
                .testTag("poster_fullscreen_overlay"),
            contentAlignment = Alignment.Center
        ) {
            // 海报图片（放大显示，点击由 ProgressiveFullscreenImage 统一接管：
            // 放大时先复位、未放大才关闭，返回键同协议）
            ProgressiveFullscreenImage(
                // 全屏查看用 original 原图:1080p 屏全屏显示约 1050px,
                // w500 源图放大到 1080 解码会模糊,original(2000px+) 保证清晰;
                // 下载大但仅在用户主动查看大图时触发。
                // 渐进底图走 telephoto 原生占位图机制（详情页头部的 w780 已加载过，点开即可见）。
                model = remember(posterUrl) { TmdbImageUrls.swapSize(posterUrl, "original") },
                contentScale = ContentScale.Fit,
                contentDescription = title,
                state = imageState,
                modifier = Modifier
                    .fillMaxWidth(0.85f)
                    .aspectRatio(2f / 3f)
                    .zoomSharedTarget(
                        key = sharedKeyPrefix,
                        animatedVisibilityScope = animatedVisibilityScope,
                        clipRadius = 12.dp
                        // thumbnailCrop 用默认 Crop：两端都是 2:3，海报本身也是 2:3，
                        // Crop 与 Fit 在这里等价，取 Crop 换来只量一次的 scaleToBounds。
                    ),
                // 转场动画期间锁手势，避免与共享元素转场互相打架
                gesturesEnabled = !phase.running,
                deferZoomable = phase.opening,
                onRequestDismiss = onDismiss,
                backHandlerEnabled = true
            )

            // 顶部操作栏（在图片之上，也需要拦截点击）
            // 原 Haze 毛玻璃已移除以降低持续渲染开销，改用半透明黑色背景
            // 不参与配对：等海报基本落位再淡入，并抬进转场 overlay，否则会被飞行中的海报盖住
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .appSharedOverlayChrome(animatedVisibilityScope)
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
                IconButton(onClick = onDismiss) {
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
                                tint = WatchedGreen,
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
