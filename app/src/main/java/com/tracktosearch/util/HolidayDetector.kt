package com.tracktosearch.util

import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

enum class Holiday {
    CHRISTMAS,
    SPRING_FESTIVAL,
    HALLOWEEN
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
            else -> null
        }
    }
}
