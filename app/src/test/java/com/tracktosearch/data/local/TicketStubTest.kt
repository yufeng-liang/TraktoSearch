package com.tracktosearch.data.local

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

class TicketStubTest {

    @Test
    fun deriveTicketSeat_mapsOneCodeToFixedHallRowSeat() {
        val seat = deriveTicketSeat("492013")

        // 期望值手算写死：在测试里把公式重算一遍的话，公式改错时两边一起错，这条就白写了
        assertThat(seat.hall).isEqualTo(2)
        assertThat(seat.row).isEqualTo(9)
        assertThat(seat.seat).isEqualTo(14)
    }

    @Test
    fun deriveTicketSeat_allZeroCodeLandsOnFirstSeatNotZero() {
        assertThat(deriveTicketSeat("000000")).isEqualTo(TicketSeat(1, 1, 1))
    }

    @Test
    fun deriveTicketSeat_largestCodeStaysInsideEachRange() {
        assertSeatInRange("999999", deriveTicketSeat("999999"))
    }

    @Test
    fun deriveTicketSeat_keepsEveryCodeInsideHallRowSeatRanges() {
        listOf("000000", "123456", "999999", "492013", "070707").forEach { code ->
            assertSeatInRange(code, deriveTicketSeat(code))
        }
    }

    @Test
    fun deriveTicketSeat_isStableForTheSameCode() {
        // 同码同票是票根的前提：用户截图分享出去，回到这一页票面数字必须还是那几个
        assertThat(deriveTicketSeat("492013")).isEqualTo(deriveTicketSeat("492013"))
    }

    @Test
    fun deriveTicketSeat_fallsBackInsteadOfThrowingOnDirtyCode() {
        DIRTY_CODES.forEach { code ->
            assertWithMessage("脏码「$code」").that(deriveTicketSeat(code))
                .isEqualTo(TicketSeat(1, 1, 1))
        }
    }

    @Test
    fun ticketBarcodeWidths_printsEighteenBarsWithinThreeLevels() {
        val widths = ticketBarcodeWidths("492013")

        assertThat(widths).hasSize(18)
        widths.forEachIndexed { index, width ->
            assertWithMessage("第 $index 根竖条").that(width).isAtLeast(1)
            assertWithMessage("第 $index 根竖条").that(width).isAtMost(3)
        }
    }

    @Test
    fun ticketBarcodeWidths_isStablePerCodeAndDiffersBetweenCodes() {
        assertThat(ticketBarcodeWidths("492013")).isEqualTo(ticketBarcodeWidths("492013"))
        assertThat(ticketBarcodeWidths("492013")).isNotEqualTo(ticketBarcodeWidths("000000"))
    }

    @Test
    fun ticketBarcodeWidths_fallsBackToFlatBarsOnDirtyCode() {
        DIRTY_CODES.forEach { code ->
            assertWithMessage("脏码「$code」").that(ticketBarcodeWidths(code))
                .isEqualTo(List(18) { 1 })
        }
    }

    private fun assertSeatInRange(code: String, seat: TicketSeat) {
        assertWithMessage("码「$code」的厅号").that(seat.hall).isAtLeast(1)
        assertWithMessage("码「$code」的厅号").that(seat.hall).isAtMost(6)
        assertWithMessage("码「$code」的排号").that(seat.row).isAtLeast(1)
        assertWithMessage("码「$code」的排号").that(seat.row).isAtMost(12)
        assertWithMessage("码「$code」的座号").that(seat.seat).isAtLeast(1)
        assertWithMessage("码「$code」的座号").that(seat.seat).isAtMost(20)
    }

    private companion object {
        /** 长度不对、含非数字：都不该抛异常，票面是装饰信息 */
        val DIRTY_CODES = listOf("", "12345", "12345a", "1234567")
    }
}
