package com.tracktosearch.ui.screen.login

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.tracktosearch.data.local.TicketStub
import java.util.Locale
import org.junit.After
import org.junit.Test

/**
 * 条码宽度派生。原先测的是 data 层那个从取票码算宽度的版本，但取票码不落盘，
 * 票面实际用的一直是从票根厅/排/座派生的这个，那份因此成了死代码并被删掉，
 * 覆盖跟着挪到这里。
 */
class CinemaTicketBarcodeTest {

    private val defaultLocale = Locale.getDefault()

    @After
    fun tearDown() {
        Locale.setDefault(defaultLocale)
    }

    @Test
    fun ticketBarcodeWidths_printsEighteenBarsWithinThreeLevels() {
        val widths = ticketBarcodeWidths(stub(hall = 2, row = 9, seat = 14))

        assertThat(widths).hasSize(18)
        widths.forEachIndexed { index, width ->
            assertWithMessage("第 $index 根竖条").that(width).isAtLeast(1)
            assertWithMessage("第 $index 根竖条").that(width).isAtMost(3)
        }
    }

    @Test
    fun ticketBarcodeWidths_isStablePerStub() {
        // 同票同条码是票根的前提：用户截图分享出去，回到这一页条码必须还是那一段
        assertThat(ticketBarcodeWidths(stub(2, 9, 14)))
            .isEqualTo(ticketBarcodeWidths(stub(2, 9, 14)))
    }

    @Test
    fun ticketBarcodeWidths_differsBetweenStubs() {
        assertThat(ticketBarcodeWidths(stub(2, 9, 14)))
            .isNotEqualTo(ticketBarcodeWidths(stub(1, 1, 1)))
    }

    @Test
    fun ticketBarcodeWidths_coversTheWholeSeatRangeWithoutGoingOutOfBounds() {
        for (hall in 1..6) {
            for (row in 1..12) {
                for (seat in 1..20) {
                    val widths = ticketBarcodeWidths(stub(hall, row, seat))
                    assertWithMessage("$hall 厅 $row 排 $seat 座").that(widths).hasSize(18)
                    assertWithMessage("$hall 厅 $row 排 $seat 座")
                        .that(widths.all { it in 1..3 }).isTrue()
                }
            }
        }
    }

    @Test
    fun ticketBarcodeWidths_ignoresTheDeviceLocale() {
        // 阿拉伯语环境下默认 locale 的 %02d 会输出另一套数字字形（٠٢），
        // 按字符取数就不再是这三段。format 必须显式给 Locale.ROOT。
        val underRoot = ticketBarcodeWidths(stub(2, 9, 14))

        Locale.setDefault(Locale.forLanguageTag("ar-EG-u-nu-arab"))

        assertThat(ticketBarcodeWidths(stub(2, 9, 14))).isEqualTo(underRoot)
    }

    private fun stub(hall: Int, row: Int, seat: Int) = TicketStub(
        nickname = "小明",
        issuedEpochDay = 20_696L,
        hall = hall,
        row = row,
        seat = seat,
    )
}
