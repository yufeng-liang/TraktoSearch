package com.tracktosearch.ui.screen.swiftie

/**
 * 彩蛋序列的时长账本。**所有**时间都从这里取，没有任何段落自己写死毫秒数。
 *
 * 总长由需求方自备的配乐倒推（实测 125.998s = [TOTAL_MS]），因此各段相加必须正好
 * [TOTAL_MS]。`SwiftieTimelineTest` 会守着这条不变式。
 *
 * ## 为什么终局排在倒滑之前
 *
 * 配乐末尾 1:58–2:02 唱的是 Lover，所以 [REWIND_START] 与 [LOVER_BLOOM_END] 是
 * **配乐钉死的两个点**，不能挪。绽放收在 2:03，离总长只剩 2998ms，而签名 8s +
 * 手链 4.5s + 定格 6.09s 共 18.59s 放不下 —— 于是终局整块排在倒滑之前，
 * 倒滑与绽放成为收尾：12 个时代 → 签名 → 手链 → 定格 → 飞回 Lover → 绽放 → 淡出。
 */
object SwiftieTimeline {

    /**
     * 配乐总长。
     *
     * `swiftie_theme.ogg`（Opus）的容器时长 125.997979s，取到毫秒。
     * 需求方口述的「2 分 05 秒」实测是 2:06 —— 源 MP3 为 126.067s，
     * 转 Opus 后被预跳裁掉 69ms。**换音轨必须同步改这个值**，
     * 否则 Lover 绽放会与配乐错开（`SwiftieTimelineTest` 守着）。
     */
    const val TOTAL_MS: Long = 125_998L

    // ---- 配乐钉死的两个点（Spec §5，实测自备配乐）----

    /** 配乐 1:58 起唱 Lover：播放头从这一刻开始倒滑。 */
    const val REWIND_START: Long = 118_000L

    /** 2:03 绽放收束，其后只留淡出。 */
    const val LOVER_BLOOM_END: Long = 123_000L

    // ---- Eras 卡片 ----

    /** 单张卡片的固定开销：400 长出 + 5000 停留 + 400 回落 + 100 段间停顿。 */
    const val CARD_BASE_MS: Long = 5_900L

    /** 曲目逐行点亮的 stagger，每首一行。 */
    const val CARD_PER_TRACK_MS: Long = 130L

    /** 第 1 张（起点）与第 7 张（归宿）多停 600ms。 */
    const val CARD_ANCHOR_BONUS_MS: Long = 600L

    /** 加时的两张：索引 0 = Taylor Swift，6 = Lover。 */
    val ANCHOR_INDICES: Set<Int> = setOf(0, 6)

    /**
     * 12 张专辑曲目数，顺序与 Eras 一致。
     *
     * 索引 10 的 TTPD 用 **The Anthology 版 31 首**（需求方指定），其余为标准版。
     * Phase D 的 `SwiftieErasData` 必须与此逐项吻合，那边有交叉断言。
     */
    val ERA_TRACK_COUNTS: List<Int> = listOf(11, 13, 14, 16, 13, 15, 18, 16, 15, 13, 31, 12)

    fun cardDurationMs(index: Int, trackCount: Int): Long =
        CARD_BASE_MS + CARD_PER_TRACK_MS * trackCount +
            if (index in ANCHOR_INDICES) CARD_ANCHOR_BONUS_MS else 0L

    /** 12 张卡片合计 96310ms。 */
    val ERAS_CARDS_MS: Long = ERA_TRACK_COUNTS
        .withIndex()
        .sumOf { (index, count) -> cardDurationMs(index, count) }

    // ---- 段落边界（毫秒，相对 T0 = 提交命中）----

    /** T0–400：`X` → 闪粉 `13`，下行淡出，同帧挂载 mesh 付掉着色器编译。 */
    const val SOLVE_MS: Long = 400L

    /** T400–1100：圆形扩张吞屏。 */
    const val DIFFUSION_START: Long = SOLVE_MS
    const val DIFFUSION_MS: Long = 700L

    /** T1100–3100：轴线铺开 + 刻度淡入。 */
    const val ERAS_INTRO_START: Long = DIFFUSION_START + DIFFUSION_MS
    const val ERAS_INTRO_MS: Long = 2_000L

    /** T3100：第一张卡片开始长出。 */
    const val ERAS_CARDS_START: Long = ERAS_INTRO_START + ERAS_INTRO_MS

    /**
     * T99410：第 12 张卡片走完。
     *
     * 与 [REWIND_START] **不再是同一个值** —— 终局插在两者之间，
     * 所以判「是否还在卡片段内」只能用这个常量。
     */
    val ERAS_CARDS_END: Long = ERAS_CARDS_START + ERAS_CARDS_MS

    /** T99410–107410：签名逐段揭示。 */
    val SIGNATURE_START: Long = ERAS_CARDS_END
    const val SIGNATURE_MS: Long = 8_000L

    /** T107410–111910：手链弹性落下。 */
    val BRACELET_START: Long = SIGNATURE_START + SIGNATURE_MS
    const val BRACELET_MS: Long = 4_500L

    /**
     * T111910–118000：定格合影，留出截图时间。
     *
     * 这一段是账本里唯一的**弹性段**：前面各段都由内容决定长度，
     * 后面各段由配乐钉死，误差全落在这里。改曲目数只会让定格变长变短，
     * 不会把 Lover 绽放错开配乐。`SwiftieTimelineTest` 守着它不许变负。
     */
    val FINAL_HOLD_START: Long = BRACELET_START + BRACELET_MS
    val FINAL_HOLD_MS: Long = REWIND_START - FINAL_HOLD_START

    /** T118000–119500：播放头倒滑回第 7 段。 */
    const val REWIND_MS: Long = 1_500L

    /** T119500–123000：Lover 段亮起放大铺满，压着配乐里那句 Lover。 */
    val LOVER_BLOOM_START: Long = REWIND_START + REWIND_MS
    val LOVER_BLOOM_MS: Long = LOVER_BLOOM_END - LOVER_BLOOM_START

    /** T123000–125998：整层淡出，露出已经在运动的星云背景。 */
    val FADE_OUT_START: Long = LOVER_BLOOM_END
    val FADE_OUT_MS: Long = TOTAL_MS - FADE_OUT_START

    /** 主题三写入的那一帧：扩散刚好铺满全屏。早一帧会露出颜色跳变。 */
    val THEME_COMMIT_AT: Long = ERAS_INTRO_START

    /** mesh 运动预热：签名快写完时才开始（写完前 1460ms），前面 105s 一帧不出。 */
    val MOTION_PREHEAT_AT: Long = SIGNATURE_START + 6_540L

    /** 第 [index] 张卡片的起始时刻。 */
    fun eraStartMs(index: Int): Long {
        var start = ERAS_CARDS_START
        for (i in 0 until index) {
            start += cardDurationMs(i, ERA_TRACK_COUNTS[i])
        }
        return start
    }

    /** [elapsedMs] 落在第几张卡片上；不在 Eras 卡片段内返回 null。 */
    fun eraIndexAt(elapsedMs: Long): Int? {
        if (elapsedMs < ERAS_CARDS_START || elapsedMs >= ERAS_CARDS_END) return null
        var cursor = ERAS_CARDS_START
        ERA_TRACK_COUNTS.forEachIndexed { index, count ->
            val next = cursor + cardDurationMs(index, count)
            if (elapsedMs < next) return index
            cursor = next
        }
        return ERA_TRACK_COUNTS.lastIndex
    }
}
