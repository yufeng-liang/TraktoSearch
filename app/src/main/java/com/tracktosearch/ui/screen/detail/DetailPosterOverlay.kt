package com.tracktosearch.ui.screen.detail

import android.os.Environment
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
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
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.tracktosearch.R
import com.tracktosearch.ui.component.queryExistingFile
import com.tracktosearch.ui.component.savePosterToGallery
import com.tracktosearch.ui.util.showToast
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials
import kotlinx.coroutines.launch
import net.engawapg.lib.zoomable.rememberZoomState
import net.engawapg.lib.zoomable.zoomable

// ==================== 海报大图查看 ====================

@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
internal fun PosterFullscreenOverlay(
    posterUrl: String,
    title: String,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isSaved by remember { mutableStateOf<Boolean?>(null) } // null=未检查, true=已保存, false=未保存

    // 进入时检查是否已保存
    LaunchedEffect(posterUrl, title) {
        val safeName = title.replace(Regex("[^a-zA-Z0-9\\u4e00-\\u9fa5]"), "_")
        val filename = "TrackToSearch_${safeName}.jpg"
        val relativePath = Environment.DIRECTORY_PICTURES + "/TrackToSearch"
        isSaved = queryExistingFile(context, filename, relativePath) != null
    }

    // 海报大图 overlay 的 Haze 状态
    val posterHazeState = remember { HazeState() }
    val zoomState = rememberZoomState()

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
            .hazeSource(state = posterHazeState)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
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
        AsyncImage(
            model = remember(posterUrl) {
                ImageRequest.Builder(context)
                    .data(posterUrl)
                    .crossfade(false)
                    .size(1080) // 加载高清大图
                    .build()
            },
            contentDescription = title,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxWidth(0.85f)
                .aspectRatio(2f / 3f)
                .zoomable(zoomState)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {} // 拦截点击，不触发外层 dismiss
                )
        )

        // 顶部操作栏（在图片之上，也需要拦截点击）
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
            // 关闭按钮（毛玻璃效果）
            IconButton(onClick = onDismiss) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .hazeEffect(
                            state = posterHazeState,
                            style = HazeMaterials.thin()
                        ) {
                            blurRadius = 18.dp
                            noiseFactor = 0f
                        }
                        .border(
                            width = 1.dp,
                            color = Color.White.copy(alpha = 0.35f),
                            shape = RoundedCornerShape(20.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource(R.string.detail_back),
                        tint = Color.White,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            // 保存按钮（已保存时显示勾选图标）
            IconButton(onClick = {
                if (isSaved == true) {
                    context.showToast(context.getString(R.string.gallery_saved_to_album))
                } else {
                    savePosterToGallery(context, scope, posterUrl, title) {
                        isSaved = true
                    }
                }
            }) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .hazeEffect(
                            state = posterHazeState,
                            style = HazeMaterials.thin()
                        ) {
                            blurRadius = 18.dp
                            noiseFactor = 0f
                        }
                        .border(
                            width = 1.dp,
                            color = Color.White.copy(alpha = 0.35f),
                            shape = RoundedCornerShape(20.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (isSaved == true) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = stringResource(R.string.content_desc_saved),
                            tint = Color(0xFF4CAF50), // 绿色
                            modifier = Modifier.size(22.dp)
                        )
                    } else {
                        Icon(
                            Icons.Default.Download,
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
