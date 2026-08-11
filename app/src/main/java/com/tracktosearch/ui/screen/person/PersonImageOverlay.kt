package com.tracktosearch.ui.screen.person

import android.os.Environment
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.tracktosearch.R
import com.tracktosearch.ui.component.LocalSharedTransitionEnabled
import com.tracktosearch.ui.component.LocalSharedTransitionScope
import com.tracktosearch.ui.component.ZoomableImageOverlay
import com.tracktosearch.ui.component.queryExistingFile
import com.tracktosearch.ui.component.savePosterToGallery
import com.tracktosearch.ui.util.showToast

// ==================== 人物图片大图查看 ====================

/**
 * 人物图片全屏查看 overlay：薄委托。
 * 图片展示与缩放逻辑交给通用 [ZoomableImageOverlay]，这里仅保留保存逻辑。
 */
@Composable
internal fun PersonImagePagerOverlay(
    visible: Boolean,
    images: List<String>,
    initialIndex: Int,
    sharedKeyPrefix: String?,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val alreadySavedToast = stringResource(R.string.poster_already_saved)
    val scope = rememberCoroutineScope()
    val savedImages = remember { mutableStateOf<Set<Int>>(emptySet()) }

    // 已保存检查（按 initialIndex 一次性检查）
    LaunchedEffect(Unit) {
        val fileName = "TrackToSearch_person_${initialIndex}.webp"
        val relativePath = Environment.DIRECTORY_PICTURES + "/TrackToSearch"
        if (queryExistingFile(context, fileName, relativePath) != null) {
            savedImages.value += initialIndex
        }
    }

    ZoomableImageOverlay(
        visible = visible,
        images = images,
        initialIndex = initialIndex,
        sharedKeyPrefix = sharedKeyPrefix,
        onDismiss = onDismiss,
        onSave = { idx ->
            if (idx in savedImages.value) {
                context.showToast(alreadySavedToast)
            } else {
                savePosterToGallery(context, scope, images[idx], "TrackToSearch_person_$idx.webp") {
                    savedImages.value += idx
                }
            }
        },
        isSavedAt = { idx -> idx in savedImages.value }
    )
}

// ==================== 全部人物图片内联网格面板 ====================

/**
 * 全部人物图片内联面板（替代原 ModalBottomSheet）。
 * 用 AnimatedVisibility 包全屏网格，网格单元加 sharedElement，
 * 点击后从网格原位缩放飞出进入全屏查看器。
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun AllPersonImagesPanel(
    visible: Boolean,
    images: List<String>,
    personId: Int,
    onImageClick: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = tween(200)),
        exit = fadeOut(animationSpec = tween(200))
    ) {
        val sharedTransitionScope = LocalSharedTransitionScope.current
        val sharedEnabled = LocalSharedTransitionEnabled.current
        val animatedVisibilityScope = this
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .statusBarsPadding()
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
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.common_close))
                }
            }
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.fillMaxHeight(0.8f),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(images, key = { index, _ -> "person_img_all_$index" }, contentType = { _, _ -> "image" }) { index, url ->
                    val sharedModifier = if (sharedTransitionScope != null && sharedEnabled) {
                        with(sharedTransitionScope) {
                            Modifier.sharedElement(
                                rememberSharedContentState(key = "person-grid-$personId-$index"),
                                animatedVisibilityScope = animatedVisibilityScope
                            )
                        }
                    } else Modifier
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
                            .then(sharedModifier)
                            .clickable { onImageClick(index) }
                    )
                }
            }
        }
    }
}
