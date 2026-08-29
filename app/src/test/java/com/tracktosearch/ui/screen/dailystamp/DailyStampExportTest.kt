package com.tracktosearch.ui.screen.dailystamp

import android.graphics.Bitmap
import android.graphics.Color
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 日签导出位图的空白保护：透明图、纯白图不能进入相册或分享缓存。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DailyStampExportTest {

    @Test
    fun `纯透明位图不可导出`() {
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.TRANSPARENT)
        }

        assertThat(bitmap.hasDailyStampVisualContent()).isFalse()
        assertThrows(IllegalArgumentException::class.java) {
            requireExportableStampBitmap(bitmap)
        }
    }

    @Test
    fun `纯白位图不可导出`() {
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
        }

        assertThat(bitmap.hasDailyStampVisualContent()).isFalse()
        assertThrows(IllegalArgumentException::class.java) {
            requireExportableStampBitmap(bitmap)
        }
    }

    @Test
    fun `包含卡面颜色的位图可以导出`() {
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
            setPixel(7, 6, Color.rgb(238, 137, 83))
        }

        assertThat(bitmap.hasDailyStampVisualContent()).isTrue()
        assertThat(requireExportableStampBitmap(bitmap)).isSameInstanceAs(bitmap)
    }
}
