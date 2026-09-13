package com.tracktosearch.ui.screen.dailystamp

import com.google.common.truth.Truth.assertThat
import com.tracktosearch.ui.screen.splash.StampStripHeight
import com.tracktosearch.ui.screen.splash.StampTearBottom
import org.junit.Test

/**
 * 导出图底部那行落款的几何。
 *
 * 这一行是竖排（图标在上、名字在下），整块必须**留在页面底部那一条里**——那一条的上边界
 * 就是撕口虚线（[StampTearBottom]）：图标顶越过虚线，导出的图上就是一枚图标骑在虚线中间。
 * 一个像素都不许过，所以这条约束直接写成断言：改图标尺寸、间距或字号会先在这里挂掉。
 *
 * 用的是「853 设计 dp 排到 1650px」这一档（常见机型日签卡录下来的尺寸），见 StampDesignHeight。
 */
class StampBrandLayoutTest {

    private val unit = 1650f / 853f
    private val width = 927f
    private val height = 1650f

    /** ascent / descent 按 11sp 衬线粗体的实际比例给：约 -0.95em / 0.22em */
    private val nameAscentPx = -11f * unit * 0.95f
    private val nameDescentPx = 11f * unit * 0.22f

    private fun layout(nameWidthPx: Float = 210f) = stampBrandLayout(
        widthPx = width,
        heightPx = height,
        unit = unit,
        nameWidthPx = nameWidthPx,
        nameAscentPx = nameAscentPx,
        nameDescentPx = nameDescentPx,
    )

    /** 名字那一行的底线：基线下移一个 descent */
    private fun StampBrandLayout.nameBottomPx(): Float = nameBaselinePx + nameDescentPx

    @Test
    fun `整块落在撕口虚线之下`() {
        val layout = layout()
        val tearY = height - StampTearBottom.value * unit
        assertThat(layout.iconTopPx).isGreaterThan(tearY)
    }

    @Test
    fun `整块落在页面之内`() {
        val layout = layout()
        assertThat(layout.nameBottomPx()).isLessThan(height)
        assertThat(layout.iconTopPx).isGreaterThan(0f)
    }

    @Test
    fun `图标在上、名字在下`() {
        val layout = layout()
        assertThat(layout.nameBaselinePx).isGreaterThan(layout.iconTopPx + layout.iconSidePx)
    }

    @Test
    fun `图标按设计尺寸换算`() {
        assertThat(layout().iconSidePx).isWithin(0.01f).of(20f * unit)
    }

    @Test
    fun `名字底线落在页面留出来的那一条里`() {
        val layout = layout()
        val stripTop = height - StampStripHeight.value * unit
        assertThat(layout.nameBottomPx()).isGreaterThan(stripTop)
    }

    @Test
    fun `名字水平居中`() {
        val layout = layout(nameWidthPx = 210f)
        assertThat(layout.nameLeftPx).isWithin(0.01f).of((width - 210f) / 2f)
    }

    @Test
    fun `图标与名字间距不随名字长短变`() {
        val narrow = layout(nameWidthPx = 60f)
        val wide = layout(nameWidthPx = 400f)
        assertThat(wide.iconTopPx).isEqualTo(narrow.iconTopPx)
        assertThat(wide.nameBaselinePx).isEqualTo(narrow.nameBaselinePx)
    }
}
