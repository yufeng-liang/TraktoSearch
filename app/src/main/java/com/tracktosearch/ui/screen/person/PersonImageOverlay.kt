package com.tracktosearch.ui.screen.person

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.tracktosearch.R
import com.tracktosearch.data.remote.tmdb.TmdbImageUrls
import com.tracktosearch.ui.component.AppBottomSheet
import com.tracktosearch.ui.component.bottomScrollFade
import com.tracktosearch.ui.component.OpenImageViewerItem
import com.tracktosearch.ui.component.openImageViewer
import com.tracktosearch.ui.component.recordOpenImageBounds
import com.tracktosearch.ui.theme.floatingSheetColor
import com.tracktosearch.ui.component.rememberOpenImageBounds
import com.tracktosearch.ui.haptic.hapticClickable

// ==================== 全部人物图片底部弹窗 ====================

/**
 * 全部人物图片弹窗（AppBottomSheet）。
 *
 * 与详情页 FullVideosImagesSheet 同款：弹层的 Dialog 是全屏窗口，网格缩略图的
 * boundsInWindow 就是屏幕坐标，点击直接带转场打开 OpenImage 查看器；sheet 保持打开，
 * 返回即回到网格原位（真机验证通过）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AllPersonImagesPanel(
    visible: Boolean,
    images: List<String>,
    onDismiss: () -> Unit
) {
    if (!visible) return
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
    AppBottomSheet(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.person_images)
    ) {
        val gridState = rememberLazyGridState()
        val fadeColor = floatingSheetColor()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("person_images_panel")
        ) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                state = gridState,
                modifier = Modifier
                    .bottomScrollFade(gridState, fadeColor)
                    .fillMaxWidth()
                    .fillMaxHeight(0.8f)
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
                            // 网格项进全屏查看器是看图，不震（用户规则：点击图片无触感）
                            .hapticClickable(semantic = null) {
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
