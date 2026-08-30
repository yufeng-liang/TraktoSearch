package com.tracktosearch.ui.screen.statistics

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** [buildHeatmapGrid] 的纯计算逻辑：网格形状、未来日期、计数映射、翻页。 */
class HeatmapGridTest {

    private val dateKeyFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    /** 固定基准日：2026-08-27（周四），避免测试随运行时间漂移。 */
    private fun today(): Date = Calendar.getInstance().apply {
        set(2026, Calendar.AUGUST, 27, 12, 0, 0)
        set(Calendar.MILLISECOND, 0)
    }.time

    private fun buildGrid(
        heatmapData: Map<String, Int> = emptyMap(),
        weekOffset: Int = 0
    ): HeatmapGrid = buildHeatmapGrid(
        heatmapData = heatmapData,
        weekOffset = weekOffset,
        locale = Locale.CHINESE,
        today = today()
    )

    @Test
    fun `今天之后的格子标记为未来且不带日期 key`() {
        val grid = buildGrid()
        val cells = grid.weeks.flatten()
        val todayIndex = cells.indexOfFirst { dateKeyFormat.format(it.date) == "2026-08-27" }

        assertThat(cells[todayIndex].isFuture).isFalse()
        assertThat(cells[todayIndex].dateKey).isEqualTo("2026-08-27")
        // 2026-08-27 是周四，同周的周五、周六、周日属于未来
        val future = cells.drop(todayIndex + 1)
        assertThat(future).hasSize(3)
        assertThat(future.all { it.isFuture }).isTrue()
        assertThat(future.all { it.dateKey.isEmpty() }).isTrue()
    }

    @Test
    fun `观看次数按日期 key 映射到对应格子`() {
        val grid = buildGrid(heatmapData = mapOf("2026-08-27" to 4, "2026-08-20" to 1))
        val cells = grid.weeks.flatten()

        assertThat(cells.first { it.dateKey == "2026-08-27" }.count).isEqualTo(4)
        assertThat(cells.first { it.dateKey == "2026-08-20" }.count).isEqualTo(1)
        assertThat(cells.first { it.dateKey == "2026-08-26" }.count).isEqualTo(0)
    }

    @Test
    fun `往前翻一页整体前移 13 周且不含未来格子`() {
        val current = buildGrid()
        val previous = buildGrid(weekOffset = -13)

        val currentStart = current.weeks.first().first().date
        val previousStart = previous.weeks.first().first().date
        val shiftDays = (currentStart.time - previousStart.time) / (24L * 60 * 60 * 1000)

        assertThat(shiftDays).isEqualTo(13L * 7)
        assertThat(previous.weeks.flatten().none { it.isFuture }).isTrue()
        assertThat(previous.rangeStart).isNotEqualTo(current.rangeStart)
    }

}
