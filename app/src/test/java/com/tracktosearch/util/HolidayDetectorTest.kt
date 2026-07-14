package com.tracktosearch.util

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate

class HolidayDetectorTest {

    private val detector = HolidayDetector()

    // ===== 固定日期节日 =====

    @Test
    fun detect_nationalDay_returnsNationalDay() {
        assertThat(detector.detect(LocalDate.of(2026, 10, 1)))
            .isEqualTo(Holiday.NATIONAL_DAY)
    }

    @Test
    fun detect_laborDay_returnsLaborDay() {
        assertThat(detector.detect(LocalDate.of(2026, 5, 1)))
            .isEqualTo(Holiday.LABOR_DAY)
    }

    @Test
    fun detect_christmas_returnsChristmas() {
        assertThat(detector.detect(LocalDate.of(2026, 12, 25)))
            .isEqualTo(Holiday.CHRISTMAS)
    }

    @Test
    fun detect_halloween_returnsHalloween() {
        assertThat(detector.detect(LocalDate.of(2026, 10, 31)))
            .isEqualTo(Holiday.HALLOWEEN)
    }

    // ===== 农历节日（源码用近似日期范围，非真实农历计算）=====

    @Test
    fun detect_springFestival_returnsSpringFestival() {
        // 源码春节范围：1/25 ~ 2/15
        assertThat(detector.detect(LocalDate.of(2026, 2, 10)))
            .isEqualTo(Holiday.SPRING_FESTIVAL)
    }

    @Test
    fun detect_dragonBoat_returnsDragonBoat() {
        // 源码端午范围：6/10 ~ 6/20
        assertThat(detector.detect(LocalDate.of(2026, 6, 19)))
            .isEqualTo(Holiday.DRAGON_BOAT)
    }

    @Test
    fun detect_midAutumn_returnsMidAutumn() {
        // 源码中秋范围：9/15 ~ 10/5
        assertThat(detector.detect(LocalDate.of(2026, 9, 25)))
            .isEqualTo(Holiday.MID_AUTUMN)
    }

    // ===== 非节日日期 =====

    @Test
    fun detect_nonHolidayDate_returnsNull() {
        assertThat(detector.detect(LocalDate.of(2026, 7, 14))).isNull()
    }

    // ===== 边界与优先级验证 =====

    @Test
    fun detect_nationalDayOverlapsMidAutumn_returnsNationalDay() {
        // 10/1 ~ 10/5 同时落在 NATIONAL_DAY(10/1~10/7) 与 MID_AUTUMN(9/15~10/5) 范围内
        // when 表达式按顺序匹配，NATIONAL_DAY 优先返回
        assertThat(detector.detect(LocalDate.of(2026, 10, 3)))
            .isEqualTo(Holiday.NATIONAL_DAY)
    }
}
