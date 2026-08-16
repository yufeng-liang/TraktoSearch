package com.tracktosearch.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.tracktosearch.data.remote.tmdb.TmdbImageUrls
import com.tracktosearch.ui.theme.LocalVisualEffectMode
import com.tracktosearch.ui.theme.VisualEffectMode
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

private const val MAX_AMBIENT_POSTERS = 12
private val POSTER_CACHE_SIZES = listOf(
    TmdbImageUrls.W200,
    TmdbImageUrls.W342,
    TmdbImageUrls.W500,
    TmdbImageUrls.W780,
    TmdbImageUrls.H632
)

/** 将 TMDB 相对路径或完整 URL 统一成海报颜色缓存使用的 key。 */
/** 同一海报可能以不同 TMDB 尺寸写入缓存，读取时尝试所有已使用尺寸。 */
internal fun posterCacheKeyCandidates(path: String): List<String> {
    val value = path.trim().takeIf { it.isNotEmpty() } ?: return emptyList()
    val tmdbPattern = Regex("^(https?://[^/]+/.*?/t/p/)([^/]+)(/.*)$")
    val match = tmdbPattern.matchEntire(value)
    if (match != null) {
        return POSTER_CACHE_SIZES.map { size ->
            val sizeName = size.substringAfterLast('/')
            "${match.groupValues[1]}$sizeName${match.groupValues[3]}"
        }
    }
    if (value.startsWith("http", ignoreCase = true)) return listOf(value)
    return POSTER_CACHE_SIZES.map { size -> TmdbImageUrls.build(value, size) }
}

/**
 * 只读取已经存在的海报颜色缓存，绝不触发图片或网络请求。
 * 缓存未命中时先使用主题背景，命中后再平滑驱动 Glass 环境色更新。
 */
@Composable
internal fun rememberCachedPosterAmbientColor(
    posterUrls: List<String>,
    fallback: Color
): Color {
    // Blur/拟态模式不使用海报环境色，跳过缓存读取避免无谓 I/O
    if (LocalVisualEffectMode.current != VisualEffectMode.GLASS) return fallback
    val context = LocalContext.current
    val extractor = remember {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            PosterColorExtractorProvider::class.java
        ).posterColorExtractor()
    }
    val cacheKeyCandidates = remember(posterUrls) {
        posterUrls.mapNotNull { posterCacheKeyCandidates(it).takeIf { keys -> keys.isNotEmpty() } }
            .distinctBy { it.first() }
            .take(MAX_AMBIENT_POSTERS)
    }
    var ambientColor by remember(fallback) { mutableStateOf(fallback) }

    LaunchedEffect(cacheKeyCandidates, fallback) {
        ambientColor = fallback
        if (cacheKeyCandidates.isEmpty()) return@LaunchedEffect

        val cachedColors = coroutineScope {
            cacheKeyCandidates.map { candidates ->
                async {
                    var cachedArgb: Long? = null
                    for (url in candidates) {
                        val argb = extractor.getCachedColor(url)
                        if (argb != null && argb != 0L) {
                            cachedArgb = argb
                            break
                        }
                    }
                    cachedArgb?.let(::Color)
                }
            }.awaitAll().filterNotNull()
        }
        ambientColor = resolveCachedPosterAmbientColor(cachedColors, fallback)
    }

    return ambientColor
}
