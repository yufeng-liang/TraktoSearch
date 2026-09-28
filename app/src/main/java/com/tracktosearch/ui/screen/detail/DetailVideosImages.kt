package com.tracktosearch.ui.screen.detail

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BrokenImage
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.compose.SubcomposeAsyncImage
import com.tracktosearch.R
import com.tracktosearch.data.remote.tmdb.TmdbImageUrls
import com.tracktosearch.data.remote.tmdb.dto.TmdbVideo
import com.tracktosearch.ui.component.AppBottomSheet
import com.tracktosearch.ui.component.ShimmerState
import com.tracktosearch.ui.component.recordOpenImageBounds
import com.tracktosearch.ui.component.rememberShimmer
import com.tracktosearch.ui.component.shimmer
import com.tracktosearch.ui.haptic.HapticSemantic
import com.tracktosearch.ui.haptic.hapticClickable
import com.tracktosearch.ui.haptic.rememberAppHaptics
import kotlinx.coroutines.launch

// ==================== 预告片与截图 ====================

@Composable
internal fun VideosAndImagesSection(
    videos: List<TmdbVideo>,
    backdrops: List<String>,
    onVideoClick: (TmdbVideo) -> Unit = {},
    onBackdropClick: (Int) -> Unit = {},
    onShowAll: () -> Unit = {},
    // 剧照缩略图矩形记录表（下标 = backdrops 下标），由详情页持有并作为 OpenImage 查看器转场起点
    backdropBounds: MutableMap<Int, Rect>,
    // 与页面其他骨架共享的同一条 shimmer，避免每格各跑一条无限动画
    shimmer: ShimmerState
) {
    val totalCount = videos.size + backdrops.size
    Column(modifier = Modifier.padding(bottom = 12.dp)) {
        DetailSectionHeader(
            title = stringResource(R.string.detail_videos_section),
            actionText = stringResource(R.string.detail_videos_all, totalCount),
            onActionClick = onShowAll
        )
        LazyRow(
            modifier = Modifier.testTag("videos_images_row"),
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
                    index = index,
                    bounds = backdropBounds,
                    shimmer = shimmer
                )
            }
            // 提示插在截图与预告片之间：浏览截图时不受干扰，滚到预告片栏才出现
            if (videos.isNotEmpty()) {
                item(key = "trailer_network_notice", contentType = "notice") {
                    Box(
                        modifier = Modifier
                            .width(190.dp)
                            .height(135.dp)
                            .padding(vertical = 8.dp)
                    ) {
                        TrailerNetworkNotice()
                    }
                }
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

/** 网络要求属于整个预告片区域，不依赖图片请求的临时加载状态。 */
@Composable
internal fun TrailerNetworkNotice(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .testTag("trailer_network_notice")
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            Icons.Rounded.Info,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp).size(16.dp)
        )
        Text(
            stringResource(R.string.trailer_youtube_restricted),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
    }
}

/** 弹层里的单行居中版：整行内容按宽度水平居中，图标与文字垂直对齐。 */
@Composable
internal fun TrailerNetworkNoticeCompact(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .testTag("trailer_network_notice_compact")
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Rounded.Info,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(14.dp)
        )
        Text(
            stringResource(R.string.trailer_youtube_restricted_single_line),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun TrailerThumbnailPlaceholder() {
    Box(
        Modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(8.dp)
    ) {
        Icon(
            Icons.Rounded.BrokenImage,
            contentDescription = stringResource(R.string.trailer_thumbnail_failed),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
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
            error = { TrailerThumbnailPlaceholder() }
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
internal fun BackdropCard(
    backdropUrl: String,
    onClick: () -> Unit = {},
    index: Int = 0,
    // 全屏查看已迁移 OpenImage：缩略图矩形记录到 bounds，点击由详情页读取作为转场起点
    bounds: MutableMap<Int, Rect>,
    shimmer: ShimmerState
) {
    Box(
        modifier = Modifier
            .width(240.dp)
            .height(135.dp)
            .clip(RoundedCornerShape(8.dp))
            // 点剧照是看图，不震
            .hapticClickable(semantic = null, onClick = onClick)
            .recordOpenImageBounds(index, bounds)
            // 只给首张挂 tag：LazyRow 里每张都挂，By.res 匹配到的就是当时排在最前的任意一张，
            // 基准点开的是哪张都不确定。首张在栏目里位置固定，可重复。
            .then(if (index == 0) Modifier.testTag("detail_backdrop_card") else Modifier)
    ) {
        // 不再需要 zoom 共享元素配对：OpenImage 以 recordOpenImageBounds 的矩形做打开/返回动画
        ProgressiveBackdrop(
            backdropUrl = backdropUrl,
            contentScale = ContentScale.Crop,
            shimmer = shimmer,
            modifier = Modifier.fillMaxSize()
        )
    }
}

/**
 * 剧照渐进占位：加载大图期间先用 w300 小尺寸版本垫底。
 * 小图通常在列表预取或上次浏览时已进 Coil 磁盘缓存，可即时显示，避免大图回源前留白。
 * 若 URL 非 TMDB 结构（豆瓣剧照、Trakt fanart，无尺寸可换），加载期间铺 shimmer 垫底不额外发请求，
 * 但主图照常加载——早先版本在这种情况下直接 return 掉了主图，导致豆瓣来源的截图永远空白。
 */
@Composable
private fun ProgressiveBackdrop(
    backdropUrl: String,
    contentScale: ContentScale,
    shimmer: ShimmerState,
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
            // 小图也没命中（加载中/失败）时先看到 shimmer，避免整格留白；
            // 小图先到就叠在上面继续渐进放大，shimmer 被盖住。
            Box(modifier = Modifier.fillMaxSize().shimmer(shimmer)) {
                if (smallUrl != null) {
                    AsyncImage(
                        model = smallUrl,
                        contentDescription = null,
                        contentScale = contentScale,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        },
        error = {
            // 加载失败：低调断图图标，让用户区分「还没加载」和「这张没拉下来」；
            // 格子仍可点击进 OpenImage 看大图侧的失败处理
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.BrokenImage,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(28.dp)
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
    // 剧照缩略图矩形记录表（下标 = backdrops 下标）；sheet 内容在自己的 Dialog window 里，
    // 这里与页面共用同一张表会覆盖为 sheet 内的坐标，作为查看器转场落点
    backdropBounds: MutableMap<Int, Rect>,
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

    AppBottomSheet(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.detail_videos_all_title)
    ) {
        // 弹层内容是独立 subcomposition（有自己的宿主 View），单独取一份
        val sheetHaptics = rememberAppHaptics()
        // 三列网格共享一条 shimmer，避免每格各跑一条无限动画；与详情页栏目骨架同用低对比档
        val sheetShimmer = rememberShimmer(subtle = true)
        Column(modifier = Modifier.fillMaxWidth()) {
            // Tab 行
            if (tabCount > 1) {
                PrimaryTabRow(
                    selectedTabIndex = selectedTabIndex,
                    modifier = Modifier.fillMaxWidth(),
                    // 透出 sheet 底色，Tab 栏不自带填充色
                    containerColor = Color.Transparent
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
                        Column(modifier = Modifier.fillMaxSize()) {
                            // 说明位于列表外，滚动或缩略图重试都不会将它回收。
                            TrailerNetworkNoticeCompact(Modifier.padding(horizontal = 16.dp))
                            LazyColumn(
                                modifier = Modifier.weight(1f).testTag("detail_all_videos_list"),
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
                    }
                    showBackdrops -> {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(3),
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            gridItemsIndexed(
                                backdrops,
                                key = { index, url -> "backdrop_grid_${index}_$url" },
                                contentType = { _, _ -> "backdrop" }
                            ) { index, backdropUrl ->
                                FullBackdropItem(
                                    backdropUrl = backdropUrl,
                                    index = index,
                                    bounds = backdropBounds,
                                    shimmer = sheetShimmer,
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
            SubcomposeAsyncImage(
                model = thumbnailUrl,
                contentDescription = video.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                error = { TrailerThumbnailPlaceholder() }
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
    index: Int,
    bounds: MutableMap<Int, Rect>,
    shimmer: ShimmerState,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(8.dp))
            // 点剧照是看图，不震
            .hapticClickable(semantic = null, onClick = onClick)
            .recordOpenImageBounds(index, bounds)
    ) {
        ProgressiveBackdrop(
            backdropUrl = backdropUrl,
            contentScale = ContentScale.Crop,
            shimmer = shimmer,
            modifier = Modifier.fillMaxSize()
        )
    }
}
