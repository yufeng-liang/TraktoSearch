package com.tracktosearch.ui.screen.person

import android.os.Environment
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.tracktosearch.R
import com.tracktosearch.ui.component.queryExistingFile
import com.tracktosearch.ui.component.savePosterToGallery
import com.tracktosearch.ui.util.showToast
import kotlinx.coroutines.launch
import net.engawapg.lib.zoomable.rememberZoomState
import net.engawapg.lib.zoomable.zoomable

// ==================== 人物图片大图滑动查看 ====================

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun PersonImagePagerOverlay(
    images: List<String>,
    initialIndex: Int,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(initialPage = initialIndex, pageCount = { images.size })
    // 追踪每张图片的保存状态
    val savedImages = remember { mutableStateOf<Set<Int>>(emptySet()) }
    val zoomState = rememberZoomState()

    // 检查当前图片是否已保存
    LaunchedEffect(pagerState.currentPage) {
        val index = pagerState.currentPage
        if (index in savedImages.value) return@LaunchedEffect
        val url = images.getOrNull(index) ?: return@LaunchedEffect
        val fileName = "TrackToSearch_person_${index}.webp"
        val relativePath = Environment.DIRECTORY_PICTURES + "/TrackToSearch"
        val exists = queryExistingFile(context, fileName, relativePath) != null
        if (exists) {
            savedImages.value = savedImages.value + index
        }
    }

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
            .statusBarsPadding()
    ) {
        // 图片区域（可点击背景退出）
        Box(
            modifier = Modifier
                .fillMaxSize()
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
            HorizontalPager(
                state = pagerState,
                userScrollEnabled = zoomState.scale <= 1f,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                val imageUrl = images[page]
                AsyncImage(
                    model = remember(imageUrl) {
                        ImageRequest.Builder(context)
                            .data(imageUrl)
                            .crossfade(false)
                            .size(1080)
                            .build()
                    },
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(zoomState) {
                            detectTapGestures(
                                onDoubleTap = { tapOffset ->
                                    // 双击切换放大/还原
                                    if (zoomState.scale > 1f) {
                                        // 已放大 → 还原
                                        scope.launch { zoomState.changeScale(1f, Offset.Zero) }
                                    } else {
                                        // 未放大 → 放大到 2.5x,以双击位置为中心
                                        scope.launch { zoomState.changeScale(2.5f, tapOffset) }
                                    }
                                }
                            )
                        }
                        .zoomable(zoomState)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {} // 阻止穿透到背景
                        )
                )
            }
        }

        // 顶部按钮栏（关闭在左，保存在右）
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // 关闭按钮（左侧）
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .size(40.dp)
                    .background(Color.Black.copy(alpha = 0.4f), CircleShape)
            ) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = stringResource(R.string.detail_close),
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }

            // 保存按钮（右侧）
            val currentIndex = pagerState.currentPage
            val isSaved = currentIndex in savedImages.value
            IconButton(
                onClick = {
                    if (isSaved) {
                        context.showToast(context.getString(R.string.poster_already_saved))
                        return@IconButton
                    }
                    val currentUrl = images[currentIndex]
                    val fileName = "TrackToSearch_person_${currentIndex}.webp"
                    savePosterToGallery(context, scope, currentUrl, fileName) {
                        savedImages.value = savedImages.value + currentIndex
                    }
                },
                modifier = Modifier
                    .size(40.dp)
                    .background(Color.Black.copy(alpha = 0.4f), CircleShape)
            ) {
                Icon(
                    if (isSaved) Icons.Filled.Check else Icons.Default.Download,
                    contentDescription = if (isSaved) stringResource(R.string.cd_saved) else stringResource(R.string.cd_save),
                    tint = if (isSaved) Color(0xFF4CAF50) else Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }
        }

        // 页码
        Text(
            text = "${pagerState.currentPage + 1}/${images.size}",
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 32.dp)
                .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 4.dp)
        )
    }
}

// ==================== 全部人物图片弹窗 ====================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AllPersonImagesSheet(
    images: List<String>,
    onImageClick: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
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
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.detail_close))
                }
            }
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.fillMaxHeight(0.85f),
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
                            .clickable { onImageClick(index) }
                    )
                }
            }
        }
    }
}
