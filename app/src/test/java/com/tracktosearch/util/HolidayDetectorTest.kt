package com.tracktosearch.util

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate

class HolidayDetectorTest {

    private val detector = HolidayDetector()

    @Test
    fun detect_knownHolidays() {
        val cases = listOf(
            LocalDate.of(2026, 10, 1) to Holiday.NATIONAL_DAY,
            LocalDate.of(2026, 5, 1) to Holiday.LABOR_DAY,
            LocalDate.of(2026, 12, 25) to Holiday.CHRISTMAS,
            LocalDate.of(2026, 10, 31) to Holiday.HALLOWEEN,
            LocalDate.of(2026, 2, 10) to Holiday.SPRING_FESTIVAL,
            LocalDate.of(2026, 6, 19) to Holiday.DRAGON_BOAT,
            LocalDate.of(2026, 9, 25) to Holiday.MID_AUTUMN,
        )

        cases.forEach { (date, expected) ->
            assertThat(detector.detect(date)).isEqualTo(expected)
        }
    }

    @Test
    fun detect_nonHolidayAndOverlapPriority() {
        assertThat(detector.detect(LocalDate.of(2026, 7, 14))).isNull()
        // 10 月 3 日同时落在国庆与中秋近似范围，国庆优先。
        assertThat(detector.detect(LocalDate.of(2026, 10, 3))).isEqualTo(Holiday.NATIONAL_DAY)
    }
}
