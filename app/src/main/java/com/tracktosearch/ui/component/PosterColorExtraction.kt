package com.tracktosearch.ui.component

import android.graphics.Bitmap
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.tracktosearch.data.util.PosterColorExtractor
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference

@EntryPoint
@InstallIn(SingletonComponent::class)
interface PosterColorExtractorProvider {
    fun posterColorExtractor(): PosterColorExtractor
}

/** 从应用级 EntryPoint 取主色提取器单例。 */
internal fun posterColorExtractor(context: Context): PosterColorExtractor =
    EntryPointAccessors.fromApplication(
        context.applicationContext,
        PosterColorExtractorProvider::class.java
    ).posterColorExtractor()

/**
 * 列表卡片的主色预提取：海报加载成功后停留 [delayMillis] 再提取主色并写入
 * [com.tracktosearch.data.util.PosterColorCache]。
 *
 * 目的：点进详情页时 `DetailViewModel.prefetchPosterColor` 能直接命中缓存，
 * 沉浸色随首帧一起出现，而不是等详情页海报解码完再取色（表现为「先空白、后变色」）。
 *
 * 延迟与「离开即取消」都是为了避免拖累滚动：快速划过或已滑走的卡片不会白算 CPU。
 * 缓存已有该海报主色时直接跳过。
 *
 * @return 传给 `PosterCard(onImageSuccess = ...)` 的回调
 */
@Composable
internal fun rememberPosterColorExtraction(
    posterUrl: String?,
    extractor: PosterColorExtractor,
    delayMillis: Long = 500L
): (Bitmap) -> Unit {
    val scope = rememberCoroutineScope()
    var extracted by remember { mutableStateOf(false) }
    val jobRef = remember { AtomicReference<Job?>(null) }
    val shouldExtract = remember(posterUrl, extractor) {
        posterUrl != null &&
            extractor.peekCachedColor(posterUrl) == null &&
            extractor.peekCachedColorCandidates(posterUrl) == null
    }

    LaunchedEffect(posterUrl) {
        extracted = false
        jobRef.getAndSet(null)?.cancel()
    }

    DisposableEffect(posterUrl) {
        onDispose { jobRef.getAndSet(null)?.cancel() }
    }

    val onImageSuccess: (Bitmap) -> Unit = remember(posterUrl, shouldExtract, delayMillis, extractor) {
        { bitmap ->
            if (shouldExtract && posterUrl != null) {
                jobRef.getAndSet(null)?.cancel()
                jobRef.set(
                    scope.launch {
                        delay(delayMillis)
                        if (!extracted) {
                            withContext(Dispatchers.Default) {
                                extractor.extractDominantColor(posterUrl, bitmap)
                            }
                            extracted = true
                        }
                    }
                )
            }
        }
    }
    return onImageSuccess
}
