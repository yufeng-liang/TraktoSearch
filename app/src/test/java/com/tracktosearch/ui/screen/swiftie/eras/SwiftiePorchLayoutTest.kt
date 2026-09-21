package com.tracktosearch.ui.screen.swiftie.eras

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 首专夜色门廊的纵向排布。
 *
 * 守的是一条链：屋檐 → 窗 → 栏杆 → 地板，每一档要整个落在下一档以上。
 * 窗与栏杆这一档最容易被改坏 —— 栏杆**画在窗之前**，窗底一旦压到栏杆上缘，半透明的暖底
 * 就把横档和立柱糊成「一截盖住、一截透出来」，屏幕上读作窗户和栏杆重叠
 * （2026-09-21 用户 23116PN5BC 截图即此）。
 * 数值全是屏高比例，与分辨率无关。
 */
class SwiftiePorchLayoutTest {

    /** 两档结构之间最少留出的那道墙：小于这个数就是贴在一起了。 */
    private val minGap = 0.02f

    @Test
    fun 窗整体在栏杆以上() {
        val winBottom = PORCH_WIN_TOP + PORCH_WIN_HEIGHT

        assertThat(winBottom + minGap).isLessThan(PORCH_RAIL_TOP)
    }

    @Test
    fun 窗不顶到屋檐() {
        assertThat(PORCH_WIN_TOP - minGap).isGreaterThan(PORCH_EAVE_BOTTOM)
    }
}
