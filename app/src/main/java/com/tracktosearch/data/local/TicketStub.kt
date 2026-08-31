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

/** 每位码印几根竖条 */
private const val BARS_PER_DIGIT = 3

/** 竖条宽度档位数，宽度落在 1 到 [BAR_WIDTH_LEVELS] */
private const val BAR_WIDTH_LEVELS = 3

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
 * 由取票码派生条码竖条宽度，长度恒为 [TICKET_CODE_LENGTH] 乘 [BARS_PER_DIGIT] 根。
 *
 * 同码同条码，理由同 [deriveTicketSeat]：票是认人的，不能每次画得不一样。
 * 同一位码内三根竖条按序号错开宽度，相邻竖条不会等宽，看着才像条码而不是一排栅栏。
 *
 * 输入不合法时返回全 1，同样是为了脏数据不崩页面。
 */
fun ticketBarcodeWidths(code: String): List<Int> {
    if (!code.isTicketCode()) return List(TICKET_CODE_LENGTH * BARS_PER_DIGIT) { 1 }
    return code.flatMap { char ->
        val digit = char - '0'
        (0 until BARS_PER_DIGIT).map { index -> (digit + index) % BAR_WIDTH_LEVELS + 1 }
    }
}

/**
 * 是否是可派生票面的取票码。
 *
 * 只认 ASCII 数字：Char.isDigit() 会放过全角与阿拉伯-印度数字，那些字符
 * 送进 toInt() 得到的数值不是用户看到的数字，票面会和码对不上。
 */
private fun String.isTicketCode(): Boolean =
    length == TICKET_CODE_LENGTH && all { it in '0'..'9' }
