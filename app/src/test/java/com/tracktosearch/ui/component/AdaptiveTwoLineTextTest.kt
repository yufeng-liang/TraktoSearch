package com.tracktosearch.ui.component

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AdaptiveTwoLineTextTest {

    @Test
    fun `font size cache reuses the final size for the same measurement inputs`() {
        val cache = AdaptiveTwoLineTitleFontSizeCache(maxSize = 2)
        val key = titleFontSizeCacheKey()
        var calculationCount = 0

        val first = cache.getOrPut(key) {
            calculationCount += 1
            14.sp
        }
        val second = cache.getOrPut(key) {
            calculationCount += 1
            13.sp
        }

        assertThat(first).isEqualTo(14.sp)
        assertThat(second).isEqualTo(14.sp)
        assertThat(calculationCount).isEqualTo(1)
    }

    @Test
    fun `font size cache evicts the least recently used entry`() {
        val cache = AdaptiveTwoLineTitleFontSizeCache(maxSize = 2)
        val firstKey = titleFontSizeCacheKey(text = "first")
        val secondKey = titleFontSizeCacheKey(text = "second")
        val thirdKey = titleFontSizeCacheKey(text = "third")

        cache.getOrPut(firstKey) { 13.sp }
        cache.getOrPut(secondKey) { 14.sp }
        cache.getOrPut(firstKey) { error("recently used entry should remain cached") }
        cache.getOrPut(thirdKey) { 15.sp }

        var recalculated = false
        val second = cache.getOrPut(secondKey) {
            recalculated = true
            12.sp
        }

        assertThat(recalculated).isTrue()
        assertThat(second).isEqualTo(12.sp)
    }

    @Test
    fun `font size cache key separates every measurement input`() {
        val base = titleFontSizeCacheKey()
        val variants = listOf(
            base.copy(text = "another"),
            base.copy(style = base.style.copy(fontWeight = FontWeight.Bold)),
            base.copy(maxFontSize = 16.sp),
            base.copy(minFontSize = 12.sp),
            base.copy(maxWidthPx = 241),
            base.copy(density = 3f),
            base.copy(fontScale = 1.2f),
            base.copy(layoutDirection = LayoutDirection.Rtl)
        )

        assertThat(variants).containsNoDuplicates()
        variants.forEach { variant ->
            assertThat(variant).isNotEqualTo(base)
        }
    }

    private fun titleFontSizeCacheKey(
        text: String = "title"
    ): AdaptiveTwoLineTitleFontSizeCacheKey = AdaptiveTwoLineTitleFontSizeCacheKey(
        text = text,
        style = TextStyle(fontWeight = FontWeight.Normal),
        maxFontSize = 15.sp,
        minFontSize = 13.sp,
        maxWidthPx = 240,
        density = 2.75f,
        fontScale = 1f,
        layoutDirection = LayoutDirection.Ltr
    )
}
