package com.tracktosearch.ui.screen.person

import android.os.Environment
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.tracktosearch.ui.component.ZoomableImageOverlay
import com.tracktosearch.ui.component.queryExistingFile
import com.tracktosearch.ui.component.savePosterToGallery
import com.tracktosearch.ui.component.zoomSharedSource
import com.tracktosearch.ui.haptic.HapticSemantic
import com.tracktosearch.ui.haptic.hapticClickable
import com.tracktosearch.ui.haptic.rememberAppHaptics
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

    // 全屏改用 original：telephoto 从磁盘子采样分块解码，配合动态缩放上限能放到原生像素，
    // 而 h632（高 632px）在 1080p 屏上稍微放大就糊，等于白搭子采样。
    // 缩略图与网格仍用 h632，只有用户主动看大图时才下原图。
    val fullSizeImages = remember(images) { images.map { TmdbImageUrls.swapSize(it, "original") } }

    // 已保存检查（按 initialIndex 一次性检查；键住 visible+initialIndex，
    // 每次打开查看器时重新检查对应页，关闭时不查询）
    LaunchedEffect(visible, initialIndex) {
        if (!visible) return@LaunchedEffect
        val index = initialIndex
        if (index in savedImages.value) return@LaunchedEffect
        val fileName = "TrackToSearch_person_${index}.jpg"
        val relativePath = Environment.DIRECTORY_PICTURES + "/TrackToSearch"
        val exists = queryExistingFile(context, fileName, relativePath) != null
        if (exists) {
            savedImages.value += index
        }
    }

    ZoomableImageOverlay(
        visible = visible,
        images = fullSizeImages,
        initialIndex = initialIndex,
        sharedKeyPrefix = sharedKeyPrefix,
        onDismiss = onDismiss,
        onSave = { idx ->
            if (idx in savedImages.value) {
                context.showToast(alreadySavedToast)
            } else {
                // 存与屏幕显示一致的原图，所见即所得
                savePosterToGallery(context, scope, fullSizeImages[idx], "person_$idx") {
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
 * 用 AnimatedVisibility 包全屏网格，网格单元用 caller-managed visibility 的共享元素，
 * 点击后从网格原位缩放飞出进入全屏查看器；面板保持打开，返回即回到网格原位。
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
        // 返回键关闭面板（内联后屏幕级 BackHandler 仍生效，需在此拦截）。
        // 全屏查看器在 PersonScreen 中排在本面板之后组合，其 BackHandler 注册更晚、优先级更高，
        // 所以查看器打开时返回键先关查看器，再按一次才关面板。
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
                            // caller-managed visibility：查看器打开本 key 时网格项置不可见，
                            // 面板本身可以保持打开，返回即回到网格原位
                            .zoomSharedSource(key = "person-grid-$personId-$index")
                            // 网格项进全屏查看器，按列表项给轻一档
                            .hapticClickable(semantic = HapticSemantic.LIGHT_TAP) { onImageClick(index) }
                    )
                }
            }
        }
    }
}
