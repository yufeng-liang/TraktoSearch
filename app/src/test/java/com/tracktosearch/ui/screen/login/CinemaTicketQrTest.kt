package com.tracktosearch.ui.screen.login

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.tracktosearch.data.local.TicketStub
import java.util.Locale
import org.junit.After
import org.junit.Test

/**
 * 票根二维码图案与票号派生。
 *
 * 图案不是真二维码（扫不出内容，见 [ticketQrModules] 的说明），所以这里测的是
 * 「它长得像不像」：尺寸、三个定位角、两条时序线的规格，以及同票同图、异票异图。
 */
class CinemaTicketQrTest {

    private val defaultLocale = Locale.getDefault()

    @After
    fun tearDown() {
        Locale.setDefault(defaultLocale)
    }

    @Test
    fun ticketQrModules_fillsAFixedTwentyOneSquareGrid() {
        // 21×21 是 QR 版本 1 的规格。格数一变，定位角占的比例就不再像二维码
        assertThat(TICKET_QR_MODULE_COUNT).isEqualTo(21)
        assertThat(ticketQrModules("020712")).hasSize(21 * 21)
    }

    @Test
    fun ticketQrModules_printsThreeFinderPatternsAndLeavesTheFourthCornerEmpty() {
        val modules = ticketQrModules("020712")
        val origins = listOf(0 to 0, 14 to 0, 0 to 14)

        for ((originX, originY) in origins) {
            for (dy in 0..6) {
                for (dx in 0..6) {
                    val ring = maxOf(kotlin.math.abs(dx - 3), kotlin.math.abs(dy - 3))
                    // 外环（ring 3）实、第二环（ring 2）空、中心 3×3（ring 0/1）实
                    assertWithMessage("定位角 ($originX,$originY) 的 ($dx,$dy)")
                        .that(modules.at(originX + dx, originY + dy))
                        .isEqualTo(ring != 2)
                }
            }
        }
        // 第四个角没有定位角：真二维码就只有三个，四个角对称反而不像
        val fourthCornerIsFinder = (14..20).all { y ->
            (14..20).all { x ->
                val ring = maxOf(kotlin.math.abs(x - 17), kotlin.math.abs(y - 17))
                modules.at(x, y) == (ring != 2)
            }
        }
        assertThat(fourthCornerIsFinder).isFalse()
    }

    @Test
    fun ticketQrModules_keepsTheSeparatorRingAroundEachFinderEmpty() {
        // 分隔白边是解码器切出定位角的前提，散列出来的模块不能贴上去
        val modules = ticketQrModules("020712")

        for (i in 0..7) {
            assertWithMessage("左上分隔边第 $i 格").that(modules.at(i, 7)).isFalse()
            assertWithMessage("左上分隔边第 $i 格").that(modules.at(7, i)).isFalse()
        }
    }

    @Test
    fun ticketQrModules_printsTheTimingLines() {
        // 第 6 行与第 6 列是时序线，奇偶交替，两端接到定位角上
        val modules = ticketQrModules("020712")

        for (i in 8..11) {
            assertWithMessage("横向时序线第 $i 格").that(modules.at(i, 6)).isEqualTo(i % 2 == 0)
            assertWithMessage("纵向时序线第 $i 格").that(modules.at(6, i)).isEqualTo(i % 2 == 0)
        }
    }

    @Test
    fun ticketQrModules_isStablePerSerial() {
        // 同票同图是票根的前提：用户截图分享出去，回到这一页码必须还是那一张
        assertThat(ticketQrModules("020914")).isEqualTo(ticketQrModules("020914"))
    }

    @Test
    fun ticketQrModules_differsBetweenSerials() {
        assertThat(ticketQrModules("020914")).isNotEqualTo(ticketQrModules("010101"))
    }

    @Test
    fun ticketQrModules_mixesLightAndDarkModules() {
        // 散列退化成一片全黑或全白时，图案还是 441 格、定位角也还在，
        // 只有密度能看出来它已经不像二维码了
        for (serial in listOf("020712", "010101", "061220", "000000")) {
            val dark = ticketQrModules(serial).count { it }
            assertWithMessage("票号 $serial 的黑格数").that(dark).isGreaterThan(21 * 21 / 5)
            assertWithMessage("票号 $serial 的黑格数").that(dark).isLessThan(21 * 21 * 4 / 5)
        }
    }

    @Test
    fun ticketQrModules_coversTheWholeSeatRangeWithoutGoingOutOfBounds() {
        for (hall in 1..6) {
            for (row in 1..12) {
                for (seat in 1..20) {
                    val modules = ticketQrModules(ticketSerial(stub(hall, row, seat)))
                    assertWithMessage("$hall 厅 $row 排 $seat 座").that(modules).hasSize(21 * 21)
                }
            }
        }
    }

    @Test
    fun ticketQrModules_ignoresTheDeviceLocale() {
        // 阿拉伯语环境下默认 locale 的 %02d 会输出另一套数字字形（٠٢），
        // 票号一变图案就变，票面上的码和号码于是各说各话。format 必须显式给 Locale.ROOT
        val underRoot = ticketQrModules(ticketSerial(stub(2, 9, 14)))

        Locale.setDefault(Locale.forLanguageTag("ar-EG-u-nu-arab"))

        assertThat(ticketQrModules(ticketSerial(stub(2, 9, 14)))).isEqualTo(underRoot)
    }

    @Test
    fun ticketSerial_isSixDigitsOfHallRowSeat() {
        assertThat(ticketSerial(stub(hall = 3, row = 4, seat = 4))).isEqualTo("030404")
        assertThat(ticketSerial(stub(hall = 6, row = 12, seat = 20))).isEqualTo("061220")
    }

    @Test
    fun ticketSerial_ignoresTheDeviceLocale() {
        val underRoot = ticketSerial(stub(2, 9, 14))

        Locale.setDefault(Locale.forLanguageTag("ar-EG-u-nu-arab"))

        assertThat(ticketSerial(stub(2, 9, 14))).isEqualTo(underRoot)
    }

    private fun List<Boolean>.at(x: Int, y: Int) = this[y * TICKET_QR_MODULE_COUNT + x]

    private fun stub(hall: Int, row: Int, seat: Int) = TicketStub(
        nickname = "小明",
        issuedEpochDay = 20_696L,
        hall = hall,
        row = row,
        seat = seat,
    )
}
