package com.tracktosearch.ui.screen.login

/** 出票动画总时长。 */
const val TICKET_PRINT_DURATION_MS = 1400

/** 出票过程中某一帧的形态。 */
data class PrintPhase(
    /** 票已露出的比例，0f..1f，乘票高得到露出高度。 */
    val revealFraction: Float,
    /** 额外下移量（dp 数值），模拟纸张过冲后回弹。 */
    val overshootDp: Float,
    /** 票面上已淡入的入口行数，0..3。 */
    val rowsVisible: Int,
    /**
     * 走纸步序号，0 表示纸头还在探出、尚未开始步进。
     *
     * 触觉挂在这个值的每次递增上，而不是挂 [revealFraction]：探头阶段的
     * [revealFraction] 是连续插值，按它去抖会变成每帧一次的连续震动。
     */
    val feedStep: Int,
)

// 三段边界一律由毫秒除总时长算出，改 TICKET_PRINT_DURATION_MS 时时序自动跟着走，
// 不用回来逐个改小数
private const val PHASE_A_END = 120f / TICKET_PRINT_DURATION_MS
private const val PHASE_B_END = 1100f / TICKET_PRINT_DURATION_MS
private const val PHASE_C_END = 1250f / TICKET_PRINT_DURATION_MS

/** 票头先探出的一小截：纸从出票口露头，之后才开始走纸 */
private const val HEAD_REVEAL = 0.03f

/** 走纸分几步。热敏打印机是步进出纸，步数决定听上去有几声「咔」 */
private const val PHASE_B_STEPS = 6

/** 过冲时最大下移量（dp 数值） */
private const val OVERSHOOT_DP = 4f

/** 票面每行淡入的间隔 */
private const val ROW_FADE_INTERVAL_MS = 50f

/** 票面上逐行淡入的登录入口行数：Trakt 登录、豆瓣登录、访客进入 */
private const val TICKET_ROWS = 3

/** 系统关闭动画（ANIMATOR_DURATION_SCALE == 0）时直接用终态，跳过整段推出。 */
val TICKET_PRINT_FINAL_PHASE: PrintPhase =
    PrintPhase(1f, 0f, TICKET_ROWS, PHASE_B_STEPS)

/**
 * 归一化进度对应的出票形态。
 *
 * 时序单独拿出来算而不是写成一串 Compose 动画：这样每一帧的形态都能在 JVM 单测里逐个断言，
 * 不必起 Compose 测试环境去猜某个时刻票露出了多少。
 *
 * 越界的 [progress] 夹回 0f..1f 而不是抛异常：动画时钟在极端帧率或系统缩放下会给出略微
 * 出界的值，出票口不该因此崩掉。
 */
fun phaseAt(progress: Float): PrintPhase {
    val clamped = progress.coerceIn(0f, 1f)
    return when {
        // 阶段 A：票头探出
        clamped < PHASE_A_END -> PrintPhase(
            revealFraction = HEAD_REVEAL * (clamped / PHASE_A_END),
            overshootDp = 0f,
            rowsVisible = 0,
            feedStep = 0,
        )
        // 阶段 B：步进走纸，段内保持常量，跨段才跳一级
        clamped < PHASE_B_END -> {
            val stepWidth = (PHASE_B_END - PHASE_A_END) / PHASE_B_STEPS
            val step = ((clamped - PHASE_A_END) / stepWidth).toInt()
                .coerceIn(0, PHASE_B_STEPS - 1)
            PrintPhase(
                // 夹住 1f：末步的浮点累积可能微微越过 1f，那就比阶段 C 的 1f 还大，
                // 露出高度会在交界处回跳一下
                revealFraction = (HEAD_REVEAL + (1f - HEAD_REVEAL) * (step + 1) / PHASE_B_STEPS)
                    .coerceAtMost(1f),
                overshootDp = 0f,
                rowsVisible = 0,
                feedStep = step + 1,
            )
        }
        // 阶段 C：纸走到底后过冲，再弹回原位
        clamped < PHASE_C_END -> {
            val settled = (clamped - PHASE_B_END) / (PHASE_C_END - PHASE_B_END)
            PrintPhase(
                revealFraction = 1f,
                overshootDp = OVERSHOOT_DP * (1f - settled),
                rowsVisible = 0,
                feedStep = PHASE_B_STEPS,
            )
        }
        // 阶段 D：票停稳后逐行印出三个入口
        else -> {
            val elapsedMs = (clamped - PHASE_C_END) * TICKET_PRINT_DURATION_MS
            PrintPhase(
                revealFraction = 1f,
                overshootDp = 0f,
                rowsVisible = (1 + (elapsedMs / ROW_FADE_INTERVAL_MS).toInt())
                    .coerceIn(1, TICKET_ROWS),
                feedStep = PHASE_B_STEPS,
            )
        }
    }
}
