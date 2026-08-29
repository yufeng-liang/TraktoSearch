package com.tracktosearch.ui.screen.statistics

import com.google.common.truth.Truth.assertThat
import java.util.TimeZone
import org.junit.Test

class StatisticsShareImageTest {

    private val utc = TimeZone.getTimeZone("UTC")

    @Test
    fun `首个文件名包含毫秒时间戳且无重复后缀`() {
        val filename = statisticsShareFilename(0L, timeZone = utc)

        assertThat(filename).isEqualTo("TrackToSearch_statistics_19700101_000000_000.png")
    }

    @Test
    fun `同一时间戳重名时追加递增后缀`() {
        val original = statisticsShareFilename(0L, timeZone = utc)
        val duplicate = statisticsShareFilename(0L, duplicateIndex = 2, timeZone = utc)

        assertThat(duplicate).isEqualTo("TrackToSearch_statistics_19700101_000000_000_2.png")
        assertThat(duplicate).isNotEqualTo(original)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `重复序号不能为负数`() {
        statisticsShareFilename(0L, duplicateIndex = -1, timeZone = utc)
    }
}
