package com.tracktosearch.ui.screen.detail

import android.content.Intent
import android.net.Uri
import android.os.Environment
import androidx.activity.compose.BackHandler
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material.icons.rounded.Close
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.tracktosearch.R
import com.tracktosearch.data.remote.tmdb.TmdbImageUrls
import com.tracktosearch.data.remote.tmdb.dto.TmdbVideo
import com.tracktosearch.ui.component.ZoomableImageOverlay
import com.tracktosearch.ui.component.queryExistingFile
import com.tracktosearch.ui.component.savePosterToGallery
import com.tracktosearch.ui.component.zoomSharedSource
import com.tracktosearch.ui.haptic.HapticSemantic
import com.tracktosearch.ui.haptic.hapticClickable
import com.tracktosearch.ui.haptic.rememberAppHaptics
import com.tracktosearch.ui.util.showToast
import kotlinx.coroutines.launch

// ==================== 预告片与截图 ====================

@Composable
internal fun VideosAndImagesSection(
    videos: List<TmdbVideo>,
    backdrops: List<String>,
    onVideoClick: (TmdbVideo) -> Unit = {},
    onBackdropClick: (Int) -> Unit = {},
    onShowAll: () -> Unit = {},
    sharedKeyPrefix: String? = null
) {
    val totalCount = videos.size + backdrops.size
    Column(modifier = Modifier.padding(bottom = 12.dp)) {
        DetailSectionHeader(
            title = stringResource(R.string.detail_videos_section),
            actionText = stringResource(R.string.detail_videos_all, totalCount),
            onActionClick = onShowAll
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(horizontal = 0.dp)
        ) {
            itemsIndexed(
                backdrops,
                key = { _, url -> "backdrop_$url" },
                contentType = { _, _ -> "backdrop" }
            ) { index, backdropUrl ->
                BackdropCard(
                    backdropUrl = backdropUrl,
                    onClick = { onBackdropClick(index) },
                    sharedKeyPrefix = sharedKeyPrefix,
                    index = index
                )
            }
            itemsIndexed(
                videos,
                key = { _, video -> "video_${video.key}" },
                contentType = { _, _ -> "video" }
            ) { _, video ->
                VideoCard(
                    video = video,
                    onClick = { onVideoClick(video) }
                )
            }
        }
    }
}

/**
 * 视频类型 → 本地化文案。
 * Featurette/幕后/花絮等此前都被折叠进 detail_video_clip 或直接展示英文原文，现各自独立映射。
 */
@Composable
private fun videoTypeLabel(type: String): String = when (type) {
    "Trailer" -> stringResource(R.string.detail_video_trailer)
    "Teaser" -> stringResource(R.string.detail_video_teaser)
    "Clip" -> stringResource(R.string.detail_video_clip)
    "Featurette" -> stringResource(R.string.detail_video_featurette)
    "Behind the Scenes" -> stringResource(R.string.detail_video_behind_the_scenes)
    "Bloopers" -> stringResource(R.string.detail_video_blooper)
    else -> stringResource(R.string.detail_video_other)
}

@Composable
internal fun VideoCard(
    video: TmdbVideo,
    onClick: () -> Unit
) {
    val thumbnailUrl = "https://img.youtube.com/vi/${video.key}/hqdefault.jpg"
    val typeLabel = videoTypeLabel(video.type)
    Box(
        modifier = Modifier
            .width(240.dp)
            .height(135.dp)
            .clip(RoundedCornerShape(8.dp))
            .hapticClickable(semantic = HapticSemantic.LIGHT_TAP, onClick = onClick)
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

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun BackdropCard(
    backdropUrl: String,
    onClick: () -> Unit = {},
    sharedKeyPrefix: String? = null,
    index: Int = 0
) {
    Box(
        modifier = Modifier
            .width(240.dp)
            .height(135.dp)
            .clip(RoundedCornerShape(8.dp))
            .hapticClickable(semantic = HapticSemantic.LIGHT_TAP, onClick = onClick)
            // 只给首张挂 tag：LazyRow 里每张都挂，By.res 匹配到的就是当时排在最前的任意一张，
            // 基准点开的是哪张、配对的是哪个 key 都不确定。首张在栏目里位置固定，可重复。
            .then(if (index == 0) Modifier.testTag("detail_backdrop_card") else Modifier)
    ) {
        // sharedKeyPrefix 非空且共享转场开启时,与全屏端 "$sharedKeyPrefix-$page" 配对,实现缩放转场。
        // 用 caller-managed visibility(zoomSharedSource):全屏端打开本 key 时缩略图侧置不可见,
        // 保证同一 key 同时只有一侧是 target,否则转场方向会反。
        ProgressiveBackdrop(
            backdropUrl = backdropUrl,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .zoomSharedSource(key = sharedKeyPrefix?.let { "$it-$index" })
        )
    }
}

/**
 * 剧照渐进占位：加载大图期间先用 w300 小尺寸版本垫底。
 * 小图通常在列表预取或上次浏览时已进 Coil 磁盘缓存，可即时显示，避免大图回源前留白。
 * 若 URL 非 TMDB 结构（豆瓣剧照、Trakt fanart，无尺寸可换），只用纯色垫底不额外发请求，
 * 但主图照常加载——早先版本在这种情况下直接 return 掉了主图，导致豆瓣来源的截图永远空白。
 */
@Composable
private fun ProgressiveBackdrop(
    backdropUrl: String,
    contentScale: ContentScale,
    modifier: Modifier = Modifier
) {
    val smallUrl = remember(backdropUrl) {
        val swapped = TmdbImageUrls.swapSize(backdropUrl, "w300")
        if (swapped == backdropUrl) null else swapped
    }
    SubcomposeAsyncImage(
        model = backdropUrl,
        contentDescription = null,
        contentScale = contentScale,
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
        loading = {
            // 非 TMDB /t/p/ 结构（豆瓣剧照、Trakt fanart）没有小尺寸可换，
            // 只留纯色底，不额外发请求；但主图一定要照常加载。
            if (smallUrl != null) {
                AsyncImage(
                    model = smallUrl,
                    contentDescription = null,
                    contentScale = contentScale,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    )
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
    val pagerState = rememberPagerState(initialPage = 0, pageCount = { tabCount })

    // 同步 pager 和 tab
    val selectedTabIndex = pagerState.currentPage
    val scope = rememberCoroutineScope()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        dragHandle = null
    ) {
        // ModalBottomSheet 的内容是独立 subcomposition（有自己的宿主 View），单独取一份
        val sheetHaptics = rememberAppHaptics()
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
                IconButton(onClick = { sheetHaptics.lightTap(); onDismiss() }) {
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
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    if (hasBackdrops) {
                        Tab(
                            selected = selectedTabIndex == 0,
                            onClick = {
                                sheetHaptics.segmentTick()
                                scope.launch { pagerState.animateScrollToPage(0) }
                            },
                            text = { Text(stringResource(R.string.detail_videos_backdrops_section, backdrops.size)) }
                        )
                    }
                    if (hasVideos) {
                        val videoTabIndex = if (hasBackdrops) 1 else 0
                        Tab(
                            selected = selectedTabIndex == videoTabIndex,
                            onClick = {
                                sheetHaptics.segmentTick()
                                scope.launch { pagerState.animateScrollToPage(videoTabIndex) }
                            },
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
    val typeLabel = videoTypeLabel(video.type)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .hapticClickable(semantic = HapticSemantic.LIGHT_TAP, onClick = onClick)
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
            .hapticClickable(semantic = HapticSemantic.LIGHT_TAP, onClick = onClick)
    ) {
        ProgressiveBackdrop(
            backdropUrl = backdropUrl,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
    }
}

// ==================== 预告片播放界面 ====================

@Composable
internal fun YouTubePlayerOverlay(
    videoKey: String,
    videoTitle: String,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val haptics = rememberAppHaptics()
    val thumbnailUrl = "https://img.youtube.com/vi/$videoKey/hqdefault.jpg"
    val watchUrl = "https://www.youtube.com/watch?v=$videoKey"

    BackHandler(onBack = onDismiss)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .statusBarsPadding()
            .hapticClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                semantic = HapticSemantic.LIGHT_TAP,
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
                        // 这层浮层就是为了「看这条预告」而开的，播放与下面那颗「在浏览器中打开」
                        // 是同一个动作的两个落点，都是本浮层的主按钮 → 按显著度给 tap()，
                        // 不按外跳静默（否则整层唯一会震的是关闭 ×，主次颠倒）
                        .hapticClickable(semantic = HapticSemantic.TAP) {
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
                haptics.tap()
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
            onClick = { haptics.lightTap(); onDismiss() },
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

@Composable
internal fun BackdropPagerOverlay(
    visible: Boolean,
    backdrops: List<String>,
    initialIndex: Int,
    sharedKeyPrefix: String?,
    onDismiss: () -> Unit,
    enter: EnterTransition = fadeIn(animationSpec = tween(200)),
    exit: ExitTransition = fadeOut(animationSpec = tween(200))
) {
    val context = LocalContext.current
    val alreadySavedToast = stringResource(R.string.poster_already_saved)
    val scope = rememberCoroutineScope()
    // 追踪每张截图的保存状态
    val savedBackdrops = remember { mutableStateOf<Set<Int>>(emptySet()) }
    // 大图用 original 清晰度
    val originalUrls = remember(backdrops) { backdrops.map { it.replace("/w780/", "/original/") } }

    // 检查初始截图是否已保存（一次性检查，与原有按页检查语义近似）
    // 键住 visible：详情页常驻组合时 initialIndex 恒为 0，未打开查看器不做磁盘查询
    LaunchedEffect(visible, initialIndex) {
        if (!visible) return@LaunchedEffect
        val index = initialIndex
        if (index in savedBackdrops.value) return@LaunchedEffect
        val fileName = "TrackToSearch_backdrop_${index}.jpg"
        val relativePath = Environment.DIRECTORY_PICTURES + "/TrackToSearch"
        val exists = queryExistingFile(context, fileName, relativePath) != null
        if (exists) {
            savedBackdrops.value += index
        }
    }

    // 图片展示与缩放逻辑委托给通用全屏组件，仅保留保存状态检查与保存逻辑
    ZoomableImageOverlay(
        visible = visible,
        images = originalUrls,
        initialIndex = initialIndex,
        sharedKeyPrefix = sharedKeyPrefix,
        onDismiss = onDismiss,
        onSave = { idx ->
            val url = originalUrls.getOrNull(idx) ?: return@ZoomableImageOverlay
            if (idx in savedBackdrops.value) {
                context.showToast(alreadySavedToast)
            } else {
                savePosterToGallery(context, scope, url, "backdrop_$idx") {
                    savedBackdrops.value += idx
                }
            }
        },
        isSavedAt = { idx -> idx in savedBackdrops.value },
        enter = enter,
        exit = exit
    )
}
