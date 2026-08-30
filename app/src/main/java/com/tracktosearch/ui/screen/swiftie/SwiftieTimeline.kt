package com.tracktosearch.ui.screen.swiftie

/**
 * 彩蛋序列的时长账本。**所有**时间都从这里取，没有任何段落自己写死毫秒数。
 *
 * 总长由需求方自备的 2 分钟配乐倒推（Spec §5），因此各段相加必须正好
 * [TOTAL_MS]。`SwiftieTimelineTest` 会守着这条不变式。
 */
object SwiftieTimeline {

    /** 配乐总长。 */
    const val TOTAL_MS: Long = 120_000L

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
     * 12 张专辑标准版曲目数，顺序与 Eras 一致。
     * Phase D 的 `SwiftieErasData` 必须与此逐项吻合，那边有交叉断言。
     */
    val ERA_TRACK_COUNTS: List<Int> = listOf(11, 13, 14, 16, 13, 15, 18, 16, 15, 13, 16, 12)

    fun cardDurationMs(index: Int, trackCount: Int): Long =
        CARD_BASE_MS + CARD_PER_TRACK_MS * trackCount +
            if (index in ANCHOR_INDICES) CARD_ANCHOR_BONUS_MS else 0L

    /** 12 张卡片合计 94360ms。 */
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

    /** T97460–98960：播放头倒滑回第 7 段。 */
    val REWIND_START: Long = ERAS_CARDS_START + ERAS_CARDS_MS
    const val REWIND_MS: Long = 1_500L

    /** T98960–100460：Lover 段亮起放大铺满。 */
    val LOVER_BLOOM_START: Long = REWIND_START + REWIND_MS
    const val LOVER_BLOOM_MS: Long = 1_500L

    /** T100460–108460：签名逐段揭示。 */
    val SIGNATURE_START: Long = LOVER_BLOOM_START + LOVER_BLOOM_MS
    const val SIGNATURE_MS: Long = 8_000L

    /** T108460–112960：手链弹性落下。 */
    val BRACELET_START: Long = SIGNATURE_START + SIGNATURE_MS
    const val BRACELET_MS: Long = 4_500L

    /** T112960–118460：定格合影，留出截图时间。 */
    val FINAL_HOLD_START: Long = BRACELET_START + BRACELET_MS
    const val FINAL_HOLD_MS: Long = 5_500L

    /** T118460–120000：彩蛋层淡出。 */
    val FADE_OUT_START: Long = FINAL_HOLD_START + FINAL_HOLD_MS
    const val FADE_OUT_MS: Long = 1_540L

    /** 主题三写入的那一帧：扩散刚好铺满全屏。早一帧会露出颜色跳变。 */
    val THEME_COMMIT_AT: Long = ERAS_INTRO_START

    /** mesh 运动预热：签名快写完时才开始，前面 107s 一帧不出。 */
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
        if (elapsedMs < ERAS_CARDS_START || elapsedMs >= REWIND_START) return null
        var cursor = ERAS_CARDS_START
        ERA_TRACK_COUNTS.forEachIndexed { index, count ->
            val next = cursor + cardDurationMs(index, count)
            if (elapsedMs < next) return index
            cursor = next
        }
        return ERA_TRACK_COUNTS.lastIndex
    }
}
