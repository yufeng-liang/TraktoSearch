package com.tracktosearch.ui.screen.detail

import android.content.Intent
import android.net.Uri
import android.os.Environment
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.BrokenImage
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.tracktosearch.R
import com.tracktosearch.data.remote.tmdb.dto.TmdbVideo
import com.tracktosearch.ui.component.queryExistingFile
import com.tracktosearch.ui.component.savePosterToGallery
import com.tracktosearch.ui.util.showToast
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import kotlinx.coroutines.launch
import net.engawapg.lib.zoomable.rememberZoomState
import net.engawapg.lib.zoomable.zoomable

// ==================== 预告片与截图 ====================

@Composable
internal fun VideosAndImagesSection(
    videos: List<TmdbVideo>,
    backdrops: List<String>,
    onVideoClick: (TmdbVideo) -> Unit = {},
    onBackdropClick: (Int) -> Unit = {},
    onShowAll: () -> Unit = {}
) {
    val totalCount = videos.size + backdrops.size
    Column(modifier = Modifier.padding(bottom = 12.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.detail_videos_section),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = stringResource(R.string.detail_videos_all, totalCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable(onClick = onShowAll)
            )
        }
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(horizontal = 0.dp)
        ) {
            itemsIndexed(backdrops, key = { index, url -> "backdrop_${index}_$url" }) { index, backdropUrl ->
                BackdropCard(
                    backdropUrl = backdropUrl,
                    onClick = { onBackdropClick(index) }
                )
            }
            itemsIndexed(videos, key = { index, video -> "video_${index}_${video.key}" }) { _, video ->
                VideoCard(
                    video = video,
                    onClick = { onVideoClick(video) }
                )
            }
        }
    }
}

@Composable
internal fun VideoCard(
    video: TmdbVideo,
    onClick: () -> Unit
) {
    val thumbnailUrl = "https://img.youtube.com/vi/${video.key}/hqdefault.jpg"
    val typeLabel = when (video.type) {
        "Trailer" -> stringResource(R.string.detail_video_trailer)
        "Teaser" -> stringResource(R.string.detail_video_teaser)
        "Clip" -> stringResource(R.string.detail_video_clip)
        "Featurette" -> stringResource(R.string.detail_video_clip)
        "Behind the Scenes" -> stringResource(R.string.detail_video_clip)
        else -> video.type
    }
    Box(
        modifier = Modifier
            .width(240.dp)
            .height(135.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
    ) {
        SubcomposeAsyncImage(
            model = thumbnailUrl,
            contentDescription = video.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
            error = {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Rounded.BrokenImage,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.trailer_youtube_restricted),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        )
        // 半透明渐变遮罩
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .align(Alignment.BottomCenter)
                .background(
                    androidx.compose.ui.graphics.Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f))
                    )
                )
        )
        // 播放按钮
        Box(
            modifier = Modifier
                .size(40.dp)
                .align(Alignment.Center)
                .background(Color.White.copy(alpha = 0.85f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.PlayArrow,
                contentDescription = null,
                tint = Color.Black,
                modifier = Modifier.size(28.dp)
            )
        }
        // 类型标签
        Surface(
            shape = RoundedCornerShape(4.dp),
            color = Color.Black.copy(alpha = 0.6f),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(6.dp)
        ) {
            Text(
                text = typeLabel,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
        // 视频标题
        Text(
            text = video.name,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 8.dp, end = 8.dp, bottom = 6.dp)
        )
    }
}

@Composable
internal fun BackdropCard(backdropUrl: String, onClick: () -> Unit = {}) {
    Box(
        modifier = Modifier
            .width(240.dp)
            .height(135.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
    ) {
        AsyncImage(
            model = backdropUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
    }
}

// ==================== 全部预告片与截图弹窗 ====================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FullVideosImagesSheet(
    videos: List<TmdbVideo>,
    backdrops: List<String>,
    onDismiss: () -> Unit,
    onVideoClick: (TmdbVideo) -> Unit = {},
    onBackdropClick: (Int) -> Unit = {}
) {
    val hasVideos = videos.isNotEmpty()
    val hasBackdrops = backdrops.isNotEmpty()
    val tabCount = (if (hasVideos) 1 else 0) + (if (hasBackdrops) 1 else 0)
    // 默认选中截图Tab（如果有）
    val initialPage = if (hasBackdrops) 0 else 0
    val pagerState = rememberPagerState(initialPage = initialPage, pageCount = { tabCount })

    // 同步 pager 和 tab
    val selectedTabIndex = pagerState.currentPage
    val scope = rememberCoroutineScope()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        dragHandle = null
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // 标题栏
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.detail_videos_all_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.common_close)
                    )
                }
            }

            // Tab 行
            if (tabCount > 1) {
                PrimaryTabRow(
                    selectedTabIndex = selectedTabIndex,
                    modifier = Modifier.fillMaxWidth(),
                    containerColor = Color.Transparent
                ) {
                    if (hasBackdrops) {
                        Tab(
                            selected = selectedTabIndex == 0,
                            onClick = { scope.launch { pagerState.animateScrollToPage(0) } },
                            text = { Text(stringResource(R.string.detail_videos_backdrops_section, backdrops.size)) }
                        )
                    }
                    if (hasVideos) {
                        val videoTabIndex = if (hasBackdrops) 1 else 0
                        Tab(
                            selected = selectedTabIndex == videoTabIndex,
                            onClick = { scope.launch { pagerState.animateScrollToPage(videoTabIndex) } },
                            text = { Text(stringResource(R.string.detail_videos_trailers_section, videos.size)) }
                        )
                    }
                }
            }

            // HorizontalPager 内容
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.8f),
                userScrollEnabled = tabCount > 1
            ) { page ->
                // 计算当前 page 对应的内容（截图在前，预告片在后）
                val showBackdrops = if (hasBackdrops) page == 0 else false
                val showVideos = if (hasBackdrops) page == 1 else page == 0

                when {
                    showVideos -> {
                        LazyColumn(
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            itemsIndexed(videos, key = { index, video -> "video_${index}_${video.key}" }) { _, video ->
                                FullVideoItem(
                                    video = video,
                                    onClick = { onVideoClick(video) }
                                )
                            }
                        }
                    }
                    showBackdrops -> {
                        LazyColumn(
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            itemsIndexed(backdrops, key = { index, url -> "backdrop_${index}_$url" }) { index, backdropUrl ->
                                FullBackdropItem(
                                    backdropUrl = backdropUrl,
                                    onClick = { onBackdropClick(index) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun FullVideoItem(
    video: TmdbVideo,
    onClick: () -> Unit
) {
    val thumbnailUrl = "https://img.youtube.com/vi/${video.key}/hqdefault.jpg"
    val typeLabel = when (video.type) {
        "Trailer" -> stringResource(R.string.detail_video_trailer)
        "Teaser" -> stringResource(R.string.detail_video_teaser)
        "Clip" -> stringResource(R.string.detail_video_clip)
        "Featurette" -> stringResource(R.string.detail_video_clip)
        "Behind the Scenes" -> stringResource(R.string.detail_video_clip)
        else -> video.type
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 缩略图
        Box(
            modifier = Modifier
                .width(120.dp)
                .height(68.dp)
                .clip(RoundedCornerShape(6.dp))
        ) {
            AsyncImage(
                model = thumbnailUrl,
                contentDescription = video.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            // 播放按钮
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .align(Alignment.Center)
                    .background(Color.White.copy(alpha = 0.85f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.PlayArrow,
                    contentDescription = null,
                    tint = Color.Black,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = video.name,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Surface(
                shape = RoundedCornerShape(4.dp),
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Text(
                    text = typeLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                )
            }
        }
    }
}

@Composable
internal fun FullBackdropItem(
    backdropUrl: String,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(120.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
    ) {
        AsyncImage(
            model = backdropUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
    }
    Spacer(modifier = Modifier.height(6.dp))
}

// ==================== 预告片播放界面 ====================

@Composable
internal fun YouTubePlayerOverlay(
    videoKey: String,
    videoTitle: String,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val thumbnailUrl = "https://img.youtube.com/vi/$videoKey/hqdefault.jpg"
    val watchUrl = "https://www.youtube.com/watch?v=$videoKey"

    BackHandler(onBack = onDismiss)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .statusBarsPadding()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
        ) {
            // 缩略图 + 播放按钮
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black.copy(alpha = 0.3f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {} // 阻止穿透
                    ),
                contentAlignment = Alignment.Center
            ) {
                SubcomposeAsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(thumbnailUrl)
                        .crossfade(false)
                        .build(),
                    contentDescription = videoTitle,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    error = {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    imageVector = Icons.Rounded.BrokenImage,
                                    contentDescription = null,
                                    tint = Color.White.copy(alpha = 0.7f),
                                    modifier = Modifier.size(48.dp)
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = stringResource(R.string.trailer_youtube_restricted),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Color.White.copy(alpha = 0.7f),
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                )
                // 播放按钮
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                        .clickable {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(watchUrl))
                            context.startActivity(intent)
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Rounded.PlayArrow,
                        contentDescription = stringResource(R.string.detail_video_play),
                        tint = Color.White,
                        modifier = Modifier.size(40.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 视频标题
            if (videoTitle.isNotEmpty()) {
                Text(
                    text = videoTitle,
                    color = Color.White,
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // 在浏览器中打开按钮
            FilledTonalButton(onClick = {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(watchUrl))
                context.startActivity(intent)
            }) {
                Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.detail_video_open_browser), maxLines = 1)
            }
        }

        // 关闭按钮
        IconButton(
            onClick = onDismiss,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp)
                .background(Color.Black.copy(alpha = 0.4f), CircleShape)
        ) {
            Icon(
                Icons.Rounded.Close,
                contentDescription = stringResource(R.string.detail_close),
                tint = Color.White,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

// ==================== 截图滑动查看 ====================

@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
internal fun BackdropPagerOverlay(
    backdrops: List<String>,
    initialIndex: Int,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val alreadySavedToast = stringResource(R.string.poster_already_saved)
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(initialPage = initialIndex, pageCount = { backdrops.size })
    // 追踪每张截图的保存状态
    val savedBackdrops = remember { mutableStateOf<Set<Int>>(emptySet()) }
    val zoomState = rememberZoomState()

    // 检查当前截图是否已保存
    LaunchedEffect(pagerState.currentPage) {
        val index = pagerState.currentPage
        if (index in savedBackdrops.value) return@LaunchedEffect
        val fileName = "TrackToSearch_backdrop_${index}.jpg"
        val relativePath = Environment.DIRECTORY_PICTURES + "/TrackToSearch"
        val exists = queryExistingFile(context, fileName, relativePath) != null
        if (exists) {
            savedBackdrops.value = savedBackdrops.value + index
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
                val backdropUrl = backdrops[page].replace("/w780/", "/original/")
                AsyncImage(
                    model = remember(backdropUrl) {
                        ImageRequest.Builder(context)
                            .data(backdropUrl)
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
            IconButton(onClick = onDismiss) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(Color.Black.copy(alpha = 0.4f), CircleShape),
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

            // 保存按钮（右侧，已保存显示打勾）
            val currentIndex = pagerState.currentPage
            val isSaved = currentIndex in savedBackdrops.value
            IconButton(onClick = {
                if (isSaved) {
                    context.showToast(alreadySavedToast)
                    return@IconButton
                }
                val currentUrl = backdrops[currentIndex].replace("/w780/", "/original/")
                val fileName = "TrackToSearch_backdrop_${currentIndex}.jpg"
                savePosterToGallery(context, scope, currentUrl, fileName) {
                    savedBackdrops.value = savedBackdrops.value + currentIndex
                }
            }) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(Color.Black.copy(alpha = 0.4f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        if (isSaved) Icons.Rounded.Check else Icons.Rounded.Download,
                        contentDescription = if (isSaved) stringResource(R.string.detail_saved) else stringResource(R.string.detail_save),
                        tint = if (isSaved) Color(0xFF4CAF50) else Color.White,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }

        // 页码
        Text(
            text = "${pagerState.currentPage + 1}/${backdrops.size}",
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
