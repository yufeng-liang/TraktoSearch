package com.tracktosearch.ui.screen.person

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.tracktosearch.R
import com.tracktosearch.data.remote.tmdb.TmdbImageUrls
import com.tracktosearch.ui.component.OpenImageViewerItem
import com.tracktosearch.ui.component.openImageViewer
import com.tracktosearch.ui.component.recordOpenImageBounds
import com.tracktosearch.ui.component.rememberOpenImageBounds
import com.tracktosearch.ui.haptic.HapticSemantic
import com.tracktosearch.ui.haptic.hapticClickable
import com.tracktosearch.ui.haptic.rememberAppHaptics

// ==================== 全部人物图片内联网格面板 ====================

/**
 * 全部人物图片内联面板（替代原 ModalBottomSheet）。
 *
 * 用 AnimatedVisibility 包全屏网格；面板与页面同 window，网格缩略图的 window 矩形有效，
 * 单元点击直接带缩略图缩放转场打开 OpenImage 查看器，面板保持打开，返回即回到网格原位。
 */
@Composable
internal fun AllPersonImagesPanel(
    visible: Boolean,
    images: List<String>,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as? Activity
    // 网格缩略图矩形表：与图片数据下标一一对应，点开时交给 OpenImage 做转场落点
    val gridBounds = rememberOpenImageBounds()
    // 全屏看 original：网格/顶部横栏展示 h632 缩略图，用户主动看大图时才下原图
    val viewerItems = remember(images) {
        images.map {
            OpenImageViewerItem(largeUrl = TmdbImageUrls.swapSize(it, "original"), coverUrl = it)
        }
    }
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = tween(200)),
        exit = fadeOut(animationSpec = tween(200))
    ) {
        // 返回键关闭面板（OpenImage 查看器是独立 Activity，在它之上时返回键先关查看器）
        BackHandler(enabled = true) { onDismiss() }
        val haptics = rememberAppHaptics()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .statusBarsPadding()
                .testTag("person_images_panel")
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.person_images),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                // 面板的「关闭」按取消档给轻一记；上面那个 BackHandler 与返回手势照旧静默
                IconButton(onClick = {
                    haptics.lightTap()
                    onDismiss()
                }) {
                    Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.common_close))
                }
            }
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier
                    .weight(1f)
                    .testTag("person_images_grid"),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(images, key = { index, _ -> "person_img_all_$index" }, contentType = { _, _ -> "image" }) { index, url ->
                    SubcomposeAsyncImage(
                        model = remember(url) {
                            ImageRequest.Builder(context)
                                .data(url)
                                .size(300)
                                .crossfade(false)
                                .build()
                        },
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(2f / 3f)
                            .clip(RoundedCornerShape(6.dp))
                            // 记录该格的 window 矩形，OpenImage 打开动画以它为落点
                            .recordOpenImageBounds(index, gridBounds)
                            // 网格项进查看器，按列表项给轻一档
                            .hapticClickable(semantic = HapticSemantic.LIGHT_TAP) {
                                val currentActivity = activity
                                if (currentActivity != null) {
                                    openImageViewer(
                                        activity = currentActivity,
                                        items = viewerItems,
                                        bounds = gridBounds,
                                        clickedIndex = index
                                    )
                                }
                            }
                    )
                }
            }
        }
    }
}
