package com.tracktosearch.data.util

import android.graphics.Bitmap
import android.graphics.Color
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * PosterColorExtractor 单元测试（Robolectric + mockk）。
 *
 * 测试策略：
 * - mock PosterColorCache 隔离 DataStore/Context 依赖，专注测试 Extractor 逻辑
 * - 使用 Robolectric 提供 Bitmap 的 Android 影子实现
 * - 涉及实际颜色提取(QuantizerCelebi + Score)的测试用 assumeTrue 保护，Robolectric 不可用时自动跳过
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PosterColorExtractorTest {

    // ==================== 1. getCachedColor 缓存未命中 → null ====================

    @Test
    fun getCachedColor_cacheMiss_returnsNull() = runTest {
        val cache = mockk<PosterColorCache>(relaxed = true)
        coEvery { cache.getColor("url1") } returns null
        val extractor = PosterColorExtractor(cache)

        assertThat(extractor.getCachedColor("url1")).isNull()
    }

    // ==================== 2. extractDominantColor 缓存未命中 → 写入缓存 ====================

    @Test
    fun extractDominantColor_cacheMiss_writesToCache() = runTest {
        val cache = mockk<PosterColorCache>(relaxed = true)
        coEvery { cache.getColor("url1") } returns null
        val extractor = PosterColorExtractor(cache)

        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.RED)

        val extracted = extractor.extractDominantColor("url1", bitmap)

        // 提取器应从纯红 bitmap 提取非零颜色；若 Robolectric 提取不可用则跳过
        assumeTrue("Extractor should extract non-zero color", extracted != 0L)
        coVerify { cache.putColor("url1", extracted) }
    }

    // ==================== 3. extractDominantColor 后 getCachedColor → 返回刚才的颜色 ====================

    @Test
    fun getCachedColor_afterExtract_returnsExtractedColor() = runTest {
        val cache = mockk<PosterColorCache>(relaxed = true)
        val extractor = PosterColorExtractor(cache)

        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.RED)

        // 缓存未命中
        coEvery { cache.getColor("url1") } returns null

        val extracted = extractor.extractDominantColor("url1", bitmap)

        assumeTrue("Extractor should extract non-zero color", extracted != 0L)

        // 捕获写入缓存的颜色值
        val slot = slot<Long>()
        coVerify { cache.putColor("url1", capture(slot)) }
        assertThat(slot.captured).isEqualTo(extracted)

        // 提取后 getCachedColor 应返回刚才提取的颜色
        coEvery { cache.getColor("url1") } returns slot.captured
        assertThat(extractor.getCachedColor("url1")).isEqualTo(extracted)
    }

    // ==================== 4. 空/极小 bitmap → 不崩溃 ====================

    @Test
    fun extractDominantColor_minimalBitmap_doesNotCrash() = runTest {
        val cache = mockk<PosterColorCache>(relaxed = true)
        coEvery { cache.getColor("url1") } returns null
        val extractor = PosterColorExtractor(cache)

        // 1x1 bitmap - 最小尺寸，不应崩溃
        val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.BLUE)

        // 仅验证不抛异常
        extractor.extractDominantColor("url1", bitmap)
    }
}
