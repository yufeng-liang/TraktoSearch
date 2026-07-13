package com.tracktosearch.ui.component

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.compose.ui.res.stringResource
import com.tracktosearch.R
import com.tracktosearch.data.util.PosterColorExtractor
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import androidx.core.graphics.drawable.toBitmap

@EntryPoint
@InstallIn(SingletonComponent::class)
interface PosterColorExtractorProvider {
    fun posterColorExtractor(): PosterColorExtractor
}

@OptIn(ExperimentalSharedTransitionApi::class, ExperimentalFoundationApi::class)
@Composable
fun MovieCard(
    title: String,
    year: Int?,
    genres: String,
    posterUrl: String?,
    tmdbId: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    isInWatchlist: Boolean = false,
    isWatched: Boolean = false
) {
    val sharedTransitionScope = LocalSharedTransitionScope.current
    val animatedVisibilityScope = LocalAnimatedVisibilityScope.current
    // 当前活跃海报 tmdbId(-1=都不启用 / 具体值=只有匹配的启用)
    val activePosterTmdbId = LocalActivePosterTmdbId.current
    val setActivePosterTmdbId = LocalActivePosterClickSetter.current
    // 当前活跃点击 token,每次点击递增;只有 token 匹配的卡片实例才启用 sharedElement
    val activeClickToken = LocalActivePosterClickToken.current
    // 记录"我自己被点击时"获得的 token
    var myClickToken by rememberSaveable { mutableStateOf(0) }
    // 只有"当前可见 tab"且"被用户点击激活"的海报才启用 sharedElement
    // isCurrentTab 避免 HorizontalPager 常驻的非当前 tab 同 tmdbId 海报参与匹配
    // clickToken 匹配避免同页面不同栏目下同 tmdbId 海报参与匹配(转场飘错根因)
    val isCurrentTab = LocalIsCurrentTab.current
    val enableShared = tmdbId == activePosterTmdbId
        && isCurrentTab
        && myClickToken != 0
        && myClickToken == activeClickToken
    val context = LocalContext.current
    // 通过 EntryPoint 获取 PosterColorExtractor 单例,用于提前提取海报主色写入缓存
    val posterColorExtractor = remember {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            PosterColorExtractorProvider::class.java
        ).posterColorExtractor()
    }
    // 卡片可见性状态:海报加载成功 + 卡片仍在屏幕上 1.5s 后才提取主色,
    // 避免快速滑动时大量卡片同时触发 Palette CPU 密集型计算影响帧率
    var posterLoaded by remember { mutableStateOf(false) }
    var colorExtracted by remember { mutableStateOf(false) }
    // 当前缓存的 bitmap,供延迟提取使用
    var posterLoadedBitmap: android.graphics.Bitmap? by remember { mutableStateOf(null) }

    // posterUrl 变化时重置提取状态
    LaunchedEffect(posterUrl) {
        posterLoaded = false
        colorExtracted = false
        posterLoadedBitmap = null
    }

    // 海报加载成功 + 卡片仍在组合树中,延迟 1.5s 后提取主色
    // 快速滑过的卡片会在 DisposableEffect 中取消协程,不会浪费 CPU
    LaunchedEffect(posterLoaded, posterUrl) {
        if (posterLoaded && !colorExtracted && posterUrl != null) {
            delay(1500L)
            // 再检查一次:可能此时卡片已滑出屏幕但尚未被 dispose
            if (posterLoaded && !colorExtracted) {
                val bitmap = posterLoadedBitmap
                if (bitmap != null) {
                    withContext(Dispatchers.Default) {
                        posterColorExtractor.extractDominantColor(posterUrl, bitmap)
                    }
                    colorExtracted = true
                }
            }
        }
    }

    // ImageRequest 尺寸与 DetailHeaderContent 保持一致(264),让 Coil 内存缓存同一份解码图,
    // 避免 sharedElement 转场时详情页需要重新解码导致图片"空"瞬间跳动
    val imageRequest = remember(posterUrl) {
        ImageRequest.Builder(context)
            .data(posterUrl)
            .size(264)
            .crossfade(false)
            .listener(
                onSuccess = { _, result ->
                    // 仅标记海报已加载,不立即提取主色
                    // 由 LaunchedEffect + delay 控制提取时机
                    posterLoadedBitmap = result.drawable.toBitmap()
                    posterLoaded = true
                }
            )
            .build()
    }

    // 卡片离开屏幕时清理 bitmap 引用,帮助 GC
    DisposableEffect(posterUrl) {
        onDispose {
            posterLoadedBitmap = null
        }
    }

    // 包装点击回调:点击时记录当前海报为活跃状态,并获取新的 token
    // remember 包裹避免每次重组创建新 lambda 实例,减少不必要 recomposition
    val wrappedOnClick = remember(onClick, tmdbId) {
        {
            if (tmdbId > 0) {
                myClickToken = setActivePosterTmdbId(tmdbId)
            }
            onClick()
        }
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (onLongClick != null) {
                    Modifier.combinedClickable(onClick = wrappedOnClick, onLongClick = onLongClick)
                } else {
                    Modifier.clickable(onClick = wrappedOnClick)
                }
            ),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column {
            val imageModifier = if (enableShared && sharedTransitionScope != null && animatedVisibilityScope != null && LocalSharedTransitionEnabled.current) {
                with(sharedTransitionScope) {
                    Modifier
                        .sharedElement(
                            rememberSharedContentState(key = "poster-$tmdbId"),
                            animatedVisibilityScope = animatedVisibilityScope
                        )
                        .fillMaxWidth()
                        .aspectRatio(2f / 3f)
                        .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                }
            } else {
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(2f / 3f)
                    .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
            }

            Box {
                AsyncImage(
                    model = imageRequest,
                    contentDescription = title,
                    modifier = imageModifier,
                    contentScale = ContentScale.Crop,
                    placeholder = null,
                    error = null,
                    fallback = null
                )

                // 海报左上角状态角标
                if (isWatched || isInWatchlist) {
                    Row(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(4.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                color = if (isWatched) Color(0xCC000000)
                                else MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
                            )
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        if (isWatched) {
                            Icon(
                                imageVector = Icons.Rounded.CheckCircle,
                                contentDescription = null,
                                modifier = Modifier.size(12.dp),
                                tint = Color.White
                            )
                            Text(
                                text = stringResource(R.string.cd_watched_badge),
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                                color = Color.White
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Rounded.Bookmark,
                                contentDescription = null,
                                modifier = Modifier.size(10.dp),
                                tint = Color.White
                            )
                            Text(
                                text = stringResource(R.string.cd_watchlist_badge),
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                                color = Color.White
                            )
                        }
                    }
                }
                // 年份角标（海报右下角）
                if (year != null && year != 0) {
                    Text(
                        text = "$year",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        ),
                        color = Color.Black,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(4.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color.White.copy(alpha = 0.9f))
                            .padding(horizontal = 4.dp, vertical = 1.dp)
                    )
                }
            }
            Column(
                modifier = Modifier.padding(6.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (genres.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = genres,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
