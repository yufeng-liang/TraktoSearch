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
 * **配乐钉死的两个点**，不能挪。绽放收在 2:03，离总长只剩 2998ms，而签名 5.4s +
 * 手链（与签名同场）+ 定格放不下 —— 于是终局整块排在倒滑之前，
 * 倒滑与绽放成为收尾：12 个时代 → 终局（签名 + 手链 + 合影）→ 飞回 Lover → 绽放 → 淡出。
 *
 * ## 终局为什么只有一段
 *
 * 签名与手链**同场**：手链在签名写到一半时就从两侧进场（见 [BRACELET_ENTRY_MS]），
 * 三条滚到位时签名还没收笔。于是账本上不需要「手链段」这个边界，
 * `SwiftieSequencePhase` 里也就没有 BRACELET 这个相位 —— 整条序列 10 段而不是 11 段。
 *
 * 2026-09-13 之前是「签名 8000ms 独占 → 手链 4500ms 独占（含 1700ms 左右摆动）」，
 * 需求方定案改成：手链不摆、提前进场、签名写快点，省下的 7100ms 全部给定格。
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

    /** 其余 11 张的曲目时间账本，每首一行。补齐最多曲目版本后仍要留住阅读时间。 */
    const val CARD_PER_TRACK_MS: Long = 117L

    /** TTPD 的逐行打印仍按 130ms：31 行打字机是表演本身，不跟其余 11 张一起加速。 */
    const val TTPD_CARD_PER_TRACK_MS: Long = 130L

    /**
     * Lover 段额外停留。
     *
     * 2026-09-18 定案：TS1 去掉这 600ms，只保留 Lover —— 起点与归宿不再对称加时。
     */
    const val CARD_ANCHOR_BONUS_MS: Long = 600L

    /**
     * 触感上的两张锚点：索引 0 = Taylor Swift，6 = Lover。
     *
     * 这两张落地仍用重档 `CONFIRM`。**它与时间加时不是同一集合**：
     * TS1 保留重落，但不再多停 600ms。
     */
    val ANCHOR_INDICES: Set<Int> = setOf(0, 6)

    /** 真正获得额外停留的只有 Lover。 */
    val CARD_ANCHOR_BONUS_INDICES: Set<Int> = setOf(6)

    /** TTPD 在 [ERA_TRACK_COUNTS] 里的位置。打字机那一拍要按索引认人。 */
    const val TTPD_INDEX: Int = 10

    /**
     * TTPD 段开头留给打字机独奏的前摇。
     *
     * 这 3200ms 里屏幕上**没有卡片**：背景那台打字机自己敲两行词（`I love you,` /
     * `it's ruining my life`，见 `SwiftieEraBackdrop` 的 `rememberTypewriterStubLines`），
     * 然后纸才从滚筒出来 —— 卡片就是那张纸（见 `SwiftieEraCard` 的 `feedProgress`）。
     * 没有这一拍，纸是「凭空长出来的」，打字机白画。
     *
     * 从 1400ms 拉到 2600ms 正好是**两整行**：一行打完要有时间读出来，「打一行就出纸」
     * 读不出打字机的样子。加在账本上而不是从 TTPD 自己的 5000ms 静置里挪：静置那段是
     * 31 行读完之后留给眼睛的，挪走就变成「行刚点完就收卡」。代价落在唯一的弹性段
     * [FINAL_HOLD_MS] 上（需求方已确认）。
     *
     * 2026-09-18：TS1 去掉的 600ms 转到这一段，2600 → 3200，让两行独奏再慢一点。
     */
    const val TTPD_PREROLL_MS: Long = 3_200L

    /**
     * TTPD 逐行打印额外占用的时间。
     *
     * 31 行仍按正常时间表占 `130 × 31 = 4030ms`，再从定格挪来的 3s 加在这里，
     * 于是整列打印占 7030ms。**逐行时刻由 `SwiftieEraTracklist` 与触感谱共用同一个
     * helper**，两边不再各算一份。
     */
    const val TTPD_TRACK_REVEAL_BONUS_MS: Long = 3_000L

    /**
     * 12 张专辑曲目数，顺序与 Eras 一致。
     *
     * 索引 1/2/3/4 都取各专辑曲目最多的 Taylor's Version：Fearless 26、Speak Now 22、
     * Red 30、1989 21；索引 4 的 1989 含 2014 豪华版 16 首与重录独有 5 首；
     * 索引 7/8/9 分别使用 folklore 豪华版 17 首、evermore 豪华版 17 首、
     * Midnights 跨版本合辑 24 首（The Til Dawn Edition 23 首 + Late Night 加曲
     * You're Losing Me）；索引 10 的 TTPD 用 **The Anthology 版 31 首**。
     * Phase D 的 `SwiftieErasData` 必须与此逐项吻合，那边有交叉断言。
     */
    val ERA_TRACK_COUNTS: List<Int> =
        listOf(11, 26, 22, 30, 21, 15, 18, 17, 17, 24, 31, 12)

    /** TTPD 整列打印的窗口长度：31 行普通时间表再加 [TTPD_TRACK_REVEAL_BONUS_MS]。 */
    val TTPD_TRACK_REVEAL_MS: Long =
        TTPD_CARD_PER_TRACK_MS * ERA_TRACK_COUNTS[TTPD_INDEX] + TTPD_TRACK_REVEAL_BONUS_MS

    fun cardDurationMs(index: Int, trackCount: Int): Long =
        CARD_BASE_MS +
            (if (index == TTPD_INDEX) TTPD_CARD_PER_TRACK_MS else CARD_PER_TRACK_MS) * trackCount +
            (if (index in CARD_ANCHOR_BONUS_INDICES) CARD_ANCHOR_BONUS_MS else 0L) +
            (if (index == TTPD_INDEX) TTPD_TRACK_REVEAL_BONUS_MS else 0L) +
            (if (index == TTPD_INDEX) TTPD_PREROLL_MS else 0L)

    /** 12 张卡片合计 106551ms（含四张 TV 独有曲目与其余最多版本）。 */
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
     * T109651：第 12 张卡片走完。
     *
     * 与 [REWIND_START] **不再是同一个值** —— 终局插在两者之间，
     * 所以判「是否还在卡片段内」只能用这个常量。
     */
    val ERAS_CARDS_END: Long = ERAS_CARDS_START + ERAS_CARDS_MS

    /**
     * 终局一整段：签名（写字 4400 + 抬笔停顿 300 + 收笔闪光 700）与手链同场。
     *
     * T109651–115051。三个加数分别来自 `SwiftieSignature` 的 `SIGNATURE_WRITE_MS` /
     * `SIGNATURE_PAUSE_TOTAL_MS` / `SIGNATURE_FLASH_MS`，**相加必须正好等于本值**
     * （`SwiftieTimelineTest` 守着）。
     *
     * 原先是 8000ms（写字 6800）。2026-09-13 需求方定案：写字加速到 4400ms
     * （快约 1.55 倍），并把抬笔停顿从 500 压到 300 —— 签名不再是「一段要等完的表演」，
     * 而是手链进场的背景。
     */
    const val SIGNATURE_MS: Long = 5_400L

    /** 签名段起点。 */
    val SIGNATURE_START: Long = ERAS_CARDS_END

    /**
     * 手链进场相对 [SIGNATURE_START] 的偏移。
     *
     * 800ms 落在签名写到 18% 的地方：先看到笔在写，手链再从两侧滚进来，三条都停住时
     * 签名还剩两成没写完（见 `SwiftieBracelet` 的 `BRACELET_SETTLED_MS`）。
     * 这个值是「手链什么时候开始」的唯一来源 —— `SwiftieFinaleStage` 与
     * `SwiftieStaticFinale` 都从它算，不许各写一份。
     */
    val BRACELET_ENTRY_MS: Long = 800L

    /**
     * T115051–118000：定格合影，留出截图时间。
     *
     * 这一段是账本里唯一的**弹性段**：前面各段都由内容决定长度，
     * 后面各段由配乐钉死，误差全落在这里。改曲目数、改手链进场时刻都只会让定格变长变短，
     * 不会把 Lover 绽放错开配乐。`SwiftieTimelineTest` 守着它不许变负。
     *
     * 2026-09-13 从 3490ms 涨到 10590ms —— 终局整块从 12500ms 缩到 5400ms，
     * 省下的 7100ms 全部落在这里（需求方定案：给合影，不给卡片）。
     *
     * 2026-09-18 从中挪出 3000ms 给 TTPD 的逐行打印，10590 → 7590；
     * 2026-09-20 补齐 folklore / evermore / Midnights 最多版本，再补 Midnights 加曲，
     * 7590 → 5510 → 5380；同日再补四张 Taylor's Version 独有 40 首，
     * 其余 11 张降到 117ms/首，5380 → 2949。
     * TS1 去掉的 600ms 则转给 TTPD 前摇。两个配乐钉死的端点都没动。
     */
    val FINAL_HOLD_START: Long = SIGNATURE_START + SIGNATURE_MS
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

    /**
     * mesh 运动预热：签名快写完时才开始，前面一路一帧不出。
     *
     * `SIGNATURE_WRITE_MS − 260`：那 260ms 是给「签名收笔之后到预热真的开始跑」
     * 留的余量，写字加速之后跟着一起往前挪（2026-09-13 之前是 6800 − 260 = 6540）。
     */
    val MOTION_PREHEAT_AT: Long = SIGNATURE_START + 4_140L

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
