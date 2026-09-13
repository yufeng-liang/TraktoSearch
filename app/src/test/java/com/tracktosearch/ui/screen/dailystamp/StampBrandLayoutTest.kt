package com.tracktosearch.ui.screen.dailystamp

import com.google.common.truth.Truth.assertThat
import com.tracktosearch.ui.screen.splash.StampTearBottom
import org.junit.Test

/**
 * 导出图底部那行落款的几何。
 *
 * 这一行是竖排（图标在上、名字在下），整块要在**页底到撕口虚线之间**（[StampTearBottom]）
 * 居中：虚线之上是日签的版面，越过它就是一页里多出来一段；而钉死在页底又会显得下半张空。
 * 这两条约束都写成断言——改图标尺寸、间距或字号会先在这里挂掉。
 *
 * 用的是「853 设计 dp 排到 1650px」这一档（常见机型日签卡录下来的尺寸），见 StampDesignHeight。
 */
class StampBrandLayoutTest {

    private val unit = 1650f / 853f
    private val width = 927f
    private val height = 1650f

    /** ascent / descent 按 14sp 衬线粗体的实际比例给：约 -0.95em / 0.22em */
    private val nameAscentPx = -14f * unit * 0.95f
    private val nameDescentPx = 14f * unit * 0.22f

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

    /** 撕口虚线在页面上的那条线，从页顶往下量 */
    private fun tearLinePx(): Float = height - StampTearBottom.value * unit

    @Test
    fun `整块在页底到撕口虚线之间居中`() {
        val layout = layout()
        val topGap = layout.iconTopPx - tearLinePx()
        val bottomGap = height - layout.nameBottomPx()
        assertThat(topGap).isWithin(0.01f).of(bottomGap)
    }

    @Test
    fun `整块落在那一格里`() {
        val layout = layout()
        // 图标顶要压在虚线之下、名字底要留在页内：越过去就是骑在虚线中间或者掉出纸外
        assertThat(layout.iconTopPx).isGreaterThan(tearLinePx())
        assertThat(layout.nameBottomPx()).isLessThan(height)
    }

    @Test
    fun `图标在上、名字在下`() {
        val layout = layout()
        assertThat(layout.nameBaselinePx).isGreaterThan(layout.iconTopPx + layout.iconSidePx)
    }

    @Test
    fun `图标按设计尺寸换算`() {
        assertThat(layout().iconSidePx).isWithin(0.01f).of(32.5f * unit)
    }

    @Test
    fun `图标加大之后整块仍装得进撕口以下那一段`() {
        // 图标与名字一起长高之后，那一整段（撕口虚线到页底）还得容得下整块，上下留出空
        val layout = layout(nameWidthPx = 210f)
        val block = layout.nameBottomPx() - layout.iconTopPx
        assertThat(block).isLessThan(StampTearBottom.value * unit)
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
