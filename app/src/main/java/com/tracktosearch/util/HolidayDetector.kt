package com.tracktosearch.util

import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

enum class Holiday {
    CHRISTMAS,
    SPRING_FESTIVAL,
    HALLOWEEN,
    // 新增节日
    NATIONAL_DAY,
    MID_AUTUMN,
    DRAGON_BOAT,
    LABOR_DAY
}

@Singleton
class HolidayDetector @Inject constructor() {

    fun detect(date: LocalDate = LocalDate.now()): Holiday? {
        val month = date.monthValue
        val day = date.dayOfMonth

        return when {
            // 圣诞节：12/20 ~ 12/26
            month == 12 && day in 20..26 -> Holiday.CHRISTMAS
            // 春节（近似）：1/25 ~ 2/15
            month == 1 && day >= 25 -> Holiday.SPRING_FESTIVAL
            month == 2 && day <= 15 -> Holiday.SPRING_FESTIVAL
            // 万圣节：10/28 ~ 10/31
            month == 10 && day in 28..31 -> Holiday.HALLOWEEN
            // 国庆节：10/1 ~ 10/7
            month == 10 && day in 1..7 -> Holiday.NATIONAL_DAY
            // 中秋节（近似）：9/15 ~ 10/5
            (month == 9 && day >= 15) || (month == 10 && day <= 5) -> Holiday.MID_AUTUMN
            // 端午节（近似）：6/10 ~ 6/20
            month == 6 && day in 10..20 -> Holiday.DRAGON_BOAT
            // 劳动节：5/1 ~ 5/5
            month == 5 && day in 1..5 -> Holiday.LABOR_DAY
            else -> null
        }
    }
}
