package com.tracktosearch.data.local

/** 票面上的座位信息，由取票码派生，纯展示，与真实座位无关。 */
data class TicketSeat(
    val hall: Int,
    val row: Int,
    val seat: Int,
)

/** 落盘的票根。取票成功时写入，回访时直接读，不等网络。 */
data class TicketStub(
    val nickname: String,
    val issuedEpochDay: Long,
    val hall: Int,
    val row: Int,
    val seat: Int,
)

/** 取票码位数 */
private const val TICKET_CODE_LENGTH = 6

/** 厅号/排号/座号各自的取模基数，也就是三段的值域宽度 */
private const val HALL_COUNT = 6
private const val ROW_COUNT = 12
private const val SEAT_COUNT = 20

/** 脏数据兜底票面：1 厅 1 排 1 座 */
private val FALLBACK_SEAT = TicketSeat(1, 1, 1)

/**
 * 由取票码派生票面座位。
 *
 * 取模而不是随机：同一个码任何时候取都得印出同一张票——用户截图分享出去再回到这一页，
 * 票面数字变了就不再是「他那张票」。三段各取不同的模，让相邻的两个码落在不同厅、不同排，
 * 人人拿到的票不一样，又不至于是一串看不出规律的噪音。
 *
 * 码不是恰好 [TICKET_CODE_LENGTH] 位纯数字时返回 [FALLBACK_SEAT] 兜底：
 * 票面是装饰信息，不该因为一串脏数据把整个取票页搞崩。
 */
fun deriveTicketSeat(code: String): TicketSeat {
    if (!code.isTicketCode()) return FALLBACK_SEAT
    return TicketSeat(
        hall = code.substring(0, 2).toInt() % HALL_COUNT + 1,
        row = code.substring(2, 4).toInt() % ROW_COUNT + 1,
        seat = code.substring(4, 6).toInt() % SEAT_COUNT + 1,
    )
}

/**
 * 取票码里允许出现的字符：只认 ASCII 数字。
 *
 * 不用 Char.isDigit()：它会放过全角（４）与阿拉伯-印度数字（٤）。这些字符送进
 * toInt() 得到的数值不是用户看到的数字，票面会和码对不上；长度校验也会误判为「已满 6 位」
 * 从而把一串服务端必然拒绝的码放出去。输入过滤与票面派生共用这一条判定，
 * 两处不能有一边宽一边严。
 */
internal fun Char.isTicketDigit(): Boolean = this in '0'..'9'

/** 是否是可派生票面的取票码。 */
private fun String.isTicketCode(): Boolean =
    length == TICKET_CODE_LENGTH && all { it.isTicketDigit() }
