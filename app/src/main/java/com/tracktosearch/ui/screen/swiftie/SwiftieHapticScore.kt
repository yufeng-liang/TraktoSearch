package com.tracktosearch.ui.screen.swiftie

import com.tracktosearch.ui.haptic.HapticSemantic
import com.tracktosearch.ui.screen.swiftie.bracelet.BRACELET_DROP_MS
import com.tracktosearch.ui.screen.swiftie.bracelet.BRACELET_SWAY_MAX_DEG
import com.tracktosearch.ui.screen.swiftie.bracelet.BRACELET_SWAY_MS
import com.tracktosearch.ui.screen.swiftie.bracelet.braceletSwayDegrees
import com.tracktosearch.ui.screen.swiftie.eras.AXIS_TICK_FADE_IN_AT
import com.tracktosearch.ui.screen.swiftie.eras.CARD_GROW_MS
import com.tracktosearch.ui.screen.swiftie.eras.SWIFTIE_ERA_EDGES
import com.tracktosearch.ui.screen.swiftie.eras.SwiftieErasData
import com.tracktosearch.ui.screen.swiftie.eras.TRACK_REVEAL_START_MS
import com.tracktosearch.ui.screen.swiftie.eras.TRACK_STAGGER_MS
import com.tracktosearch.ui.screen.swiftie.eras.swiftieEraCenterFraction
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 谱子上一条事件的身份。
 *
 * 每一类对应设计文档
 * `docs/superpowers/plans/2026-09-01-haptics-overhaul.md`「彩蛋编排」那张表里的**一行**，
 * 名字与行序一致。降级替身、reducedMotion 过滤与单测断言全按这个枚举分类 ——
 * 不要靠时间窗口去猜某个事件属于哪一段，段落边界会随曲目数漂。
 */
enum class SwiftieHapticCueKind {
    /** 圆形扩散吞屏 700ms：振幅 0→0.9 的上升包络 */
    DIFFUSION_RISE,

    /** [DIFFUSION_RISE] 的替身：一记 `thresholdArmed()`（表里的「最低层」） */
    DIFFUSION_RISE_TICK,

    /** 扩散铺满全屏那一帧：一记 `confirm()` */
    DIFFUSION_FILL,

    /**
     * 标尺出现了：13 个刻度淡入起点那一刻的一记。
     *
     * **不是 12 记拉链。** `SwiftieErasAxis` 的 13 个刻度共用同一个 `tickPhase` alpha，
     * 没有任何按 index 的错开，所以「一格一格拉开」在屏幕上没有对应物 ——
     * 那样的触感是在描述没发生的事。
     */
    AXIS_TICK,

    /** 卡片自轴上长出走完那一刻的落地 */
    CARD_LAND,

    /** 锚点卡片（`ANCHOR_INDICES` = Taylor Swift、Lover）的落地，比其余十张重 */
    CARD_LAND_ANCHOR,

    /** 曲目列铺完的收尾一记，**不逐曲目** */
    TRACKLIST_DONE,

    /** 签名 11 段笔画：落笔连续、抬笔静默的整段包络 */
    SIGNATURE_WRITE,

    /** [SIGNATURE_WRITE] 的替身：每段笔画起点一记 `segmentTick()` */
    SIGNATURE_STROKE,

    /** 写完整字通体闪：0→1→0 的 200ms 短包络 */
    SIGNATURE_FLASH,

    /** [SIGNATURE_FLASH] 的替身：一记 `confirm()` */
    SIGNATURE_FLASH_TICK,

    /** 三条手链落下，按绘制顺序（后→前）三记，逐记转轻 */
    BRACELET_DROP,

    /** 摆动余震：振幅跟着 `braceletSwayDegrees` 的 |sin| 衰减 */
    BRACELET_SWAY,

    /** [BRACELET_SWAY] 的替身：前三个摆幅峰值各一记 */
    BRACELET_SWAY_TICK,

    /** 倒滑跨卡片边界，五记 */
    REWIND_TICK,

    /** Lover 绽放：全曲最重一笔，0→0.85→0 的慢升包络 */
    LOVER_BLOOM,

    /** [LOVER_BLOOM] 的替身：上冲一记 + 峰值一记（表里「最低层」的 `SLOW_RISE` + `CLICK`） */
    LOVER_BLOOM_TICK,
    ;

    /**
     * 密集事件：`SwiftieReducedMotion` 开着时整类丢掉。
     *
     * 两类：摆动余震那段包络与它的替身。它们是「同一件事在手上抖很多下」，
     * 减少动效时该消失的正是这种。
     *
     * [AXIS_TICK] **不在**这里 —— 它从 12 记拉链收成了一记「标尺出现了」，
     * 那是和 [DIFFUSION_FILL] 同一类的路标，属于结构性事件。
     * 逐珠划过也不在谱子里：它由手指驱动，见 `SwiftieBracelet` 的拖动回调。
     * 其余各类都是**结构性**的（卡片落地、签名闪、Lover 绽放），减少动效时必须留着。
     */
    internal val dense: Boolean
        get() = when (this) {
            BRACELET_SWAY, BRACELET_SWAY_TICK -> true
            else -> false
        }

    /**
     * 本条是哪一段包络的退化替身；不是替身就返回 null。
     *
     * 那段包络被某一层接下时本条必须丢掉，否则同一段落会同时听到包络与替身。
     * 判断落在 `SwiftieHapticConductor` 里 —— 谱子是纯函数，问不到 `playEnvelope` 的返回值。
     */
    internal val standInFor: SwiftieHapticCueKind?
        get() = when (this) {
            DIFFUSION_RISE_TICK -> DIFFUSION_RISE
            SIGNATURE_STROKE -> SIGNATURE_WRITE
            SIGNATURE_FLASH_TICK -> SIGNATURE_FLASH
            BRACELET_SWAY_TICK -> BRACELET_SWAY
            LOVER_BLOOM_TICK -> LOVER_BLOOM
            else -> null
        }
}

/** 谱子上的一条事件。[atMs] 是**序列全局**已用毫秒，不是段内偏移。 */
sealed interface SwiftieHapticCue {
    val atMs: Long
    val kind: SwiftieHapticCueKind
}

/**
 * 一记离散触感，派发走 `AppHaptics.perform(view, semantic)`。
 *
 * @param semantic 只声明「这是什么交互」；挑层与降级归引擎，这里不碰任何平台常量。
 */
data class SwiftieDiscreteCue(
    override val atMs: Long,
    override val kind: SwiftieHapticCueKind,
    val semantic: HapticSemantic,
) : SwiftieHapticCue

/**
 * 一段连续振幅包络，派发走 `AppHaptics.playEnvelope`。
 *
 * 用 `List` 而不是 `IntArray` / `FloatArray`：数组的 `equals` 是引用相等，
 * 谱子要能被 `assertThat(cues).isEqualTo(...)` 整体比较。派发时再 `toIntArray()`。
 *
 * @param timingsMs 每个控制点持续多少毫秒，全为正（tier 1 的 `playEnvelope` 拒收 ≤ 0）
 * @param amplitudes 每个控制点的目标振幅 0f..1f，与 [timingsMs] 等长
 */
data class SwiftieEnvelopeCue(
    override val atMs: Long,
    override val kind: SwiftieHapticCueKind,
    val timingsMs: List<Int>,
    val amplitudes: List<Float>,
) : SwiftieHapticCue {
    /** 整段播完要多久。定格与淡出两段的静默断言靠它。 */
    val durationMs: Long get() = timingsMs.sumOf { it.toLong() }
}

/**
 * 霉粉彩蛋 125.998 秒序列的触感谱子。
 *
 * **纯函数式**：不持有 `View`、不碰 Compose、不自己读时钟，只回答一个问题 ——
 * 「`elapsedMs` 落在这段区间里时该发什么」。所以它能被逐毫秒钉住，
 * 也因此那三条会咬人的规则（seek 丢弃、闸门静音、包络替身抑制）都不在这里，
 * 而在 [SwiftieHapticConductor]。
 *
 * ### 每一个时刻都从别处派生，本类零个写死的毫秒数
 *
 * [SwiftieTimeline] 给段落边界，`AXIS_TICK_FADE_IN_AT` 给轴线那一记，
 * `CARD_GROW_MS` / `TRACK_REVEAL_START_MS` /
 * `TRACK_STAGGER_MS` 给卡片内部的节拍，`SIGNATURE_WRITE_MS` / `SIGNATURE_PAUSE_TOTAL_MS` /
 * `SwiftieSignaturePath` 的两张权重表给签名段，`BRACELET_DROP_MS` / `BRACELET_SWAY_MS`
 * 给手链段，`SWIFTIE_ERA_EDGES` 给倒滑跨过的五条刻度。改了曲目数或换了配乐，谱子跟着动。
 *
 * 唯一几个本文件自己定的数是**包络的形状**（切几格、峰值多高），它们不是时刻，
 * 集中在文件末尾并逐个写明出处。
 *
 * ### 总量
 *
 * 默认档 53 记离散 + 5 段包络。
 * 最重要的一条不变式：**卡片段总共只有 24 记** —— 12 张卡各一记落地、12 段曲目列
 * 铺完各一记收尾，**不逐曲目**。逐曲目就是 96 秒里 180 次震动，手会麻，也什么都表达不了。
 *
 * ### 设计依据
 *
 * `docs/superpowers/plans/2026-09-01-haptics-overhaul.md` 的「彩蛋编排」与
 * 「编排的实现约束」两节。表里每一行的「最低层」列在本类里体现为两件事之一：
 * 包络行给一份 [SwiftieHapticCueKind.standInFor] 替身，离散行直接挑一个语义 ——
 * 再往下的降级是 `AppHaptics` 两条链的事，本类不参与。
 *
 * @param reducedMotion 系统要求「减少动效」时为 true：丢掉全部
 *   [SwiftieHapticCueKind.dense] 事件，只留结构性的那些。
 *
 *   **这条路今天只有单测走得到，别当死代码删掉。** `SwiftieEggScreen` 里
 *   `sequenceRunning = quiz.solved && !reducedMotion`，减少动效开着时整条序列不跑，
 *   直接进 `SwiftieStaticFinale` 那张静态终态图。留着是因为它是规格明写的要求、
 *   而且已被单测钉住：哪天静态终态变成有动画的，删了就是满编排照放。
 */
class SwiftieHapticScore(private val reducedMotion: Boolean = false) {

    /**
     * 全谱，按 [SwiftieHapticCue.atMs] 升序；同一毫秒上的先后见 [sameMillisRank]。
     *
     * 次序不是装饰。平台上「发一记触感」会掐掉正在播的波形，所以同一毫秒上谁先谁后
     * 决定了哪一条活下来，见 [sameMillisRank] 的两个例子。
     */
    val cues: List<SwiftieHapticCue> = buildScore(reducedMotion)

    /**
     * 区间 `(afterMs, throughMs]` 内该发的事件，左开右闭。
     *
     * 左开是为了配合「每帧把游标推到当前 `elapsedMs`」的驱动方式：同一记事件不会因为
     * 两帧的区间共用一个端点而发两次。谱子上没有落在 0ms 的事件（最早一条在
     * `DIFFUSION_START`），所以左开不会吞掉开头。
     *
     * [afterMs] ≥ [throughMs] 时返回空表 —— 时钟没走，就什么都不该发。
     */
    fun cuesIn(afterMs: Long, throughMs: Long): List<SwiftieHapticCue> {
        if (afterMs >= throughMs) return emptyList()
        return cues.filter { it.atMs > afterMs && it.atMs <= throughMs }
    }

    /** 只用于日志与单测的可读摘要。 */
    override fun toString(): String =
        "SwiftieHapticScore(reducedMotion=$reducedMotion, cues=${cues.size})"
}

// ============================================================================
// 以下全是私有的谱子构造。逐段一个函数，段与段之间不共享可变状态。
// ============================================================================

/**
 * 按段落顺序把全谱拼出来，最后统一排序与过滤。
 */
private fun buildScore(reducedMotion: Boolean): List<SwiftieHapticCue> =
    (
        diffusionCues() +
            axisIntroCues() +
            erasCardCues() +
            signatureCues() +
            braceletCues() +
            rewindCues() +
            loverBloomCues()
        )
        .filterNot { reducedMotion && it.kind.dense }
        .sortedWith(
            compareBy<SwiftieHapticCue>(
                { it.atMs },
                { it.sameMillisRank() },
                { it.kind.ordinal },
            )
        )

/**
 * 同一毫秒上的先后。**平台上后发的那一记会掐掉前一记还在播的波形**，
 * 所以这个次序决定同一毫秒上哪一条活得下来。三档：
 *
 * - `0` 与包络无关的离散事件，排在最前。
 * - `1` 包络。必须排在自己替身之前 —— 签名段的包络与它第一记替身同在
 *   `SIGNATURE_START` 上，指挥要先知道包络被接下了才能把替身丢掉
 *   （见 [SwiftieHapticConductor]）。
 * - `2` 包络的替身。
 *
 * 第 0 档现在是**第二道防线**：手链第三记落地与摆动余震曾经压在同一毫秒上，
 * 那一记会把整段 1700ms 的余震掐死。真正的修法是把余震包络后移一格
 * （见 [braceletCues]），这条排序规则留着兜住以后再冒出来的同毫秒碰撞。
 */
private fun SwiftieHapticCue.sameMillisRank(): Int = when {
    this is SwiftieEnvelopeCue -> 1
    kind.standInFor != null -> 2
    else -> 0
}

/**
 * 扩散段：一段上升包络铺满全屏，铺满那一帧一记 `confirm()`。
 *
 * 包络的落点与视觉严格同步 —— `SwiftieDiffusion` 的 `progress` 正是
 * `(elapsed - DIFFUSION_START) / DIFFUSION_MS`，圆铺满那一帧就是 `ERAS_INTRO_START`。
 */
private fun diffusionCues(): List<SwiftieHapticCue> {
    val rise = riseEnvelope(
        atMs = SwiftieTimeline.DIFFUSION_START,
        kind = SwiftieHapticCueKind.DIFFUSION_RISE,
        durationMs = SwiftieTimeline.DIFFUSION_MS,
        points = DIFFUSION_RISE_POINTS,
        peak = DIFFUSION_PEAK_AMPLITUDE,
    )
    return listOf(
        rise,
        // 替身：表里的「最低层」是 thresholdArmed()，tier 1 上正是 PRIMITIVE_QUICK_RISE，
        // 一条上冲曲线退成一记上冲，语义没变
        SwiftieDiscreteCue(
            atMs = SwiftieTimeline.DIFFUSION_START,
            kind = SwiftieHapticCueKind.DIFFUSION_RISE_TICK,
            semantic = HapticSemantic.THRESHOLD_ARMED,
        ),
        SwiftieDiscreteCue(
            atMs = SwiftieTimeline.ERAS_INTRO_START,
            kind = SwiftieHapticCueKind.DIFFUSION_FILL,
            semantic = HapticSemantic.CONFIRM,
        ),
    )
}

/**
 * 轴线段：**一记** `segmentTick()`，压在 13 个刻度开始淡入那一刻。
 *
 * 不是 12 记拉链。`SwiftieErasAxis` 里那 13 条刻度共用同一个 `tickPhase`
 * （`(intro - AXIS_TICK_FADE_IN_AT) / AXIS_TICK_FADE_IN_SPAN`），
 * 13 条线一起淡入、没有按 index 的错开 —— 屏幕上没有「一格一格拉开」这件事，
 * 手上就不该有。这一记的语义是「标尺出现了」，那是确定为真的。
 *
 * 时刻从 `AXIS_TICK_FADE_IN_AT` 反推，所以它跟着视觉走：以后有人给刻度加 stagger，
 * 这里要跟着改成多记，单测会先判红提醒。
 *
 * 用 `roundToInt` 而不是 `toLong`：`AXIS_TICK_FADE_IN_AT` 是 `Float`，`0.35f` 的真值
 * 0.34999999 乘 2000 恰好舍进 700.0f，今天两种写法同值 —— 但截断对小数是**系统性早一格**，
 * 换个入场时长或换个比例就会静静地早一毫秒（`LOVER_BLOOM_MS × 0.7f` 截断得 2449 不是 2450
 * 就是这么来的）。四舍五入在这里没有代价，就别留那个坑。
 */
private fun axisIntroCues(): List<SwiftieHapticCue> = listOf(
    SwiftieDiscreteCue(
        atMs = SwiftieTimeline.ERAS_INTRO_START +
            (SwiftieTimeline.ERAS_INTRO_MS * AXIS_TICK_FADE_IN_AT).roundToInt().toLong(),
        kind = SwiftieHapticCueKind.AXIS_TICK,
        semantic = HapticSemantic.SEGMENT_TICK,
    )
)

/**
 * 卡片段：**总共 24 记**，12 张卡各一记落地 + 12 段曲目列铺完各一记收尾。
 *
 * - 落地落在长出走完那一刻（`eraStartMs(i) + CARD_GROW_MS`），不是起手那一刻：
 *   `SwiftieEraCard` 的 `EaseOutBack` 在这里才收住，手上那一记要和眼里那一下对齐。
 * - 锚点两张（`ANCHOR_INDICES` = Taylor Swift、Lover）用 `CONFIRM`，tier 1 上是
 *   `CLICK` + `THUD` 两笔，tier 0 上是 `CONFIRM`（退 `VIRTUAL_KEY`）；其余十张用
 *   `GESTURE_END`，tier 1 上是半幅 `THUD`，tier 0 上退到 `CLOCK_TICK`
 *   —— 正是设计文档给这两行标的「最低层」`tap()` 与 `lightTap()`。
 * - 曲目列收尾用 `FREQUENT_TICK`（整条梯度最轻的一档）：它是一句话的句号，
 *   不该盖过同一张卡的落地。时刻取自 `TRACK_REVEAL_START_MS + stagger × 曲目数`，
 *   与 `SwiftieEraTracklist` 里最后一行淡入的起点同一个式子。
 */
private fun erasCardCues(): List<SwiftieHapticCue> =
    SwiftieTimeline.ERA_TRACK_COUNTS.flatMapIndexed { index, trackCount ->
        val start = SwiftieTimeline.eraStartMs(index)
        val anchored = index in SwiftieTimeline.ANCHOR_INDICES
        listOf(
            SwiftieDiscreteCue(
                atMs = start + CARD_GROW_MS,
                kind = if (anchored) {
                    SwiftieHapticCueKind.CARD_LAND_ANCHOR
                } else {
                    SwiftieHapticCueKind.CARD_LAND
                },
                semantic = if (anchored) HapticSemantic.CONFIRM else HapticSemantic.GESTURE_END,
            ),
            SwiftieDiscreteCue(
                atMs = start + TRACK_REVEAL_START_MS + TRACK_STAGGER_MS * trackCount,
                kind = SwiftieHapticCueKind.TRACKLIST_DONE,
                semantic = HapticSemantic.FREQUENT_TICK,
            ),
        )
    }

/**
 * 触感用的笔画时间表，**与屏幕上那支笔用的是同一张表**。
 *
 * [buildSignatureWindows] 只吃两串权重加两个预算，纯函数，所以谱子直接调它 ——
 * 不需要 `Context`、不需要字号、不需要 `Paint.getTextPath`，逐毫秒的单测保得住。
 * 权重表 [SwiftieSignaturePath.WRITE_WEIGHT] 是把 `Pacifico` 花体每个字形的宽度
 * 预先量好落成常量的（宽字形写得久，笔速才恒定），于是「哪一笔写多久、哪个间隙停多久」
 * 视觉与触感逐毫秒同一，不再是从前那种等宽近似。
 *
 * 段数也随表走：现在是 12 笔（`i` 上那一点单独算一笔），别写死。
 */
private fun signatureHapticWindows(): List<SignatureWindow> = buildSignatureWindows(
    writeWeights = SwiftieSignaturePath.WRITE_WEIGHT,
    pauseWeights = SwiftieSignaturePath.PAUSE_WEIGHT,
    writeMs = SIGNATURE_WRITE_MS,
    pauseMs = SIGNATURE_PAUSE_TOTAL_MS,
)

/**
 * 签名段：一条「落笔连续、抬笔静默」的长包络 + 写完那一下的通体闪。
 *
 * 长包络是 12 段等幅 + 11 段归零交错排出来的 23 个控制点，总长正好
 * `SIGNATURE_WRITE_MS + SIGNATURE_PAUSE_TOTAL_MS`，收在闪光起点上。
 * 每段的时长与每个间隙的时长都不等 —— 它们来自 [signatureHapticWindows] 那张权重表，
 * 与屏幕上笔尖的走停严格同步。
 *
 * 这条包络**必然是多峰的**（0.25 / 0 / 0.25 / 0 …），所以 RichTap 那层接不下 ——
 * `richTapEnvelopeOf` 只收单峰、且会重采样成 4 个控制点。它会返回 false，
 * 引擎接着往下降到 tier 1 的 `createWaveform`，那条路对控制点数与形状都不设限。
 *
 * 频率没有通道可用：目标机的 `frequencyProfile` 全是 NaN，`playEnvelope` 只接振幅，
 * 所以设计文档里「`tipYAt()` 映射到频率」这一半落不了地，见回报。
 */
private fun signatureCues(): List<SwiftieHapticCue> {
    val strokes = signatureHapticWindows()
    val timingsMs = mutableListOf<Int>()
    val amplitudes = mutableListOf<Float>()
    strokes.forEachIndexed { index, stroke ->
        timingsMs += (stroke.endMs - stroke.startMs).toInt()
        amplitudes += SIGNATURE_WRITE_AMPLITUDE
        // 末段之后没有间隙 —— 紧接着就是闪光。中间那些间隙各自长短不同，
        // 由下一段的落笔时刻反推，别按平均值补
        val gapMs = strokes.getOrNull(index + 1)?.let { it.startMs - stroke.endMs } ?: 0L
        if (gapMs > 0L) {
            timingsMs += gapMs.toInt()
            amplitudes += 0f
        }
    }
    val flashAtMs = SwiftieTimeline.SIGNATURE_START + strokes.writeEndMs
    return buildList {
        add(
            SwiftieEnvelopeCue(
                atMs = SwiftieTimeline.SIGNATURE_START,
                kind = SwiftieHapticCueKind.SIGNATURE_WRITE,
                timingsMs = timingsMs,
                amplitudes = amplitudes,
            )
        )
        // 替身：每段起点一记 segmentTick()，一支笔停停走走的刻度感
        strokes.forEach { stroke ->
            add(
                SwiftieDiscreteCue(
                    atMs = SwiftieTimeline.SIGNATURE_START + stroke.startMs,
                    kind = SwiftieHapticCueKind.SIGNATURE_STROKE,
                    semantic = HapticSemantic.SEGMENT_TICK,
                )
            )
        }
        add(
            humpEnvelope(
                atMs = flashAtMs,
                kind = SwiftieHapticCueKind.SIGNATURE_FLASH,
                durationMs = SIGNATURE_FLASH_ENVELOPE_MS,
                points = SIGNATURE_FLASH_POINTS,
                peak = 1f,
                riseFraction = 0.5f,
            )
        )
        add(
            SwiftieDiscreteCue(
                atMs = flashAtMs,
                kind = SwiftieHapticCueKind.SIGNATURE_FLASH_TICK,
                semantic = HapticSemantic.CONFIRM,
            )
        )
    }
}

/**
 * 手链段：三记落地 + 一段摆动余震。**35 颗珠子不各响一记。**
 *
 * 落地按绘制顺序（后→前）三记逐记转轻，语义取 `THUD` 一族里能拿到的三档：
 * `DRAG_START`（满幅 THUD）→ `GESTURE_END`（半幅 THUD）→ `SCROLL_EDGE`
 * （0.4 幅 `LOW_TICK`）。三记摊在 `BRACELET_DROP_MS` 上，末记压在落地那一刻。
 *
 * 余震包络的振幅直接取 `braceletSwayDegrees` 的绝对值除以 `BRACELET_SWAY_MAX_DEG` ——
 * 和屏幕上那条 `exp(-2.4t) · sin(4πt)` 是同一条曲线，不另抄一份。
 * 它同样是多峰的（|sin| 在 1700ms 里过四个峰），所以也只有 tier 1 接得下。
 * **起点比末记落地晚一格**，理由见下面的行内注释。
 *
 * 表里还有一行「可拖交互逐珠划过」不在谱子里：那是手指驱动的，不是时间驱动的，
 * 见 `SwiftieBracelet` 的拖动回调。
 */
private fun braceletCues(): List<SwiftieHapticCue> {
    val dropSemantics = listOf(
        HapticSemantic.DRAG_START,
        HapticSemantic.GESTURE_END,
        HapticSemantic.SCROLL_EDGE,
    )
    val swayTimingsMs = evenSteps(BRACELET_SWAY_MS, SWAY_ENVELOPE_POINTS)
    // 余震包络比落地晚**一格**（约 100ms）起。落地那一刻本来两件事压在同一毫秒上，
    // 而平台上后发的一记会掐掉正在播的波形 —— 那记落地会把整段 1700ms 的余震掐死。
    // 后移一格就够：视觉上「落地即起晃」差 100ms 不可感知。
    // buildScore 里那条「无关离散排在包络之前」的排序规则留着当第二道防线
    val swayStartMs = SwiftieTimeline.BRACELET_START + BRACELET_DROP_MS + swayTimingsMs.first()
    return buildList {
        dropSemantics.forEachIndexed { index, semantic ->
            add(
                SwiftieDiscreteCue(
                    atMs = SwiftieTimeline.BRACELET_START +
                        BRACELET_DROP_MS * (index + 1) / dropSemantics.size,
                    kind = SwiftieHapticCueKind.BRACELET_DROP,
                    semantic = semantic,
                )
            )
        }
        add(
            SwiftieEnvelopeCue(
                atMs = swayStartMs,
                kind = SwiftieHapticCueKind.BRACELET_SWAY,
                timingsMs = swayTimingsMs,
                amplitudes = swayAmplitudes(SWAY_ENVELOPE_POINTS),
            )
        )
        // 替身：前三个摆幅峰值各一记。|sin(4πt)| 的峰在 t = 1/8、3/8、5/8，
        // 第四个峰（7/8）已经被 exp 衰减压到几乎无感，不值一记
        repeat(SWAY_STAND_IN_COUNT) { index ->
            add(
                SwiftieDiscreteCue(
                    atMs = swayStartMs + BRACELET_SWAY_MS * (2 * index + 1) / 8,
                    kind = SwiftieHapticCueKind.BRACELET_SWAY_TICK,
                    semantic = HapticSemantic.FREQUENT_TICK,
                )
            )
        }
    }
}

/**
 * 倒滑段：播放头从轴末端飞回 Lover 段中心，跨过的每条卡片刻度一记 `segmentTick()`。
 *
 * 跨过的是第 11..7 条刻度，正好五条 —— 数量不是写死的，由
 * `SWIFTIE_ERA_EDGES` 里落在 Lover 中心右侧的那几条边界数出来。曲目数一改，
 * 边界跟着挪，这里数出来的还是它们。
 *
 * 每记的时刻按**匀速**播放头反推。屏幕上那条播放头走的是 `EaseInOutCubic`，
 * 所以中段会差百来毫秒，见回报里的逐行对照。
 */
private fun rewindCues(): List<SwiftieHapticCue> {
    val loverCenter = swiftieEraCenterFraction(SwiftieErasData.LOVER_INDEX)
    val span = 1f - loverCenter
    return SWIFTIE_ERA_EDGES
        // 落在 Lover 中心右侧的那几条。1f 是起点不是跨点，排掉
        .filter { edge -> edge > loverCenter && edge < 1f }
        // 倒着走：先跨过最右边那条刻度
        .sortedDescending()
        .map { edge ->
            val progress = (1f - edge) / span
            SwiftieDiscreteCue(
                atMs = SwiftieTimeline.REWIND_START +
                    (SwiftieTimeline.REWIND_MS * progress).toLong(),
                kind = SwiftieHapticCueKind.REWIND_TICK,
                semantic = HapticSemantic.SEGMENT_TICK,
            )
        }
}

/**
 * Lover 绽放：全曲最重一笔，一条 0→0.85→0 的慢升包络压着配乐里那句 Lover。
 *
 * 这一条是单峰的，所以 RichTap 那层接得下（重采样成 4 点，峰值落在中间两位）；
 * 也是整条序列里唯一一段两层都播得出来的包络。
 *
 * 替身按表里的「最低层」`PRIMITIVE_SLOW_RISE` + `CLICK` 拆成两记：起点一记
 * `thresholdArmed()`（tier 1 的 `QUICK_RISE`，四层里最接近 `SLOW_RISE` 的上冲），
 * 峰值一记 `confirm()`（tier 1 的 `CLICK` + `THUD`）。
 */
private fun loverBloomCues(): List<SwiftieHapticCue> {
    val bloom = humpEnvelope(
        atMs = SwiftieTimeline.LOVER_BLOOM_START,
        kind = SwiftieHapticCueKind.LOVER_BLOOM,
        durationMs = SwiftieTimeline.LOVER_BLOOM_MS,
        points = LOVER_BLOOM_POINTS,
        peak = LOVER_BLOOM_PEAK_AMPLITUDE,
        riseFraction = LOVER_BLOOM_RISE_FRACTION,
    )
    // 峰值那一格的末尾，直接从包络自己的时长表上数出来。
    // **别拿 LOVER_BLOOM_MS × LOVER_BLOOM_RISE_FRACTION 去算** —— 0.7f 的真值是
    // 0.69999998，3500 乘上去再 toLong() 得到 2449 而不是 2450，替身会比峰值早一毫秒；
    // 而且格数是取整来的（14 格里 10 格上升 = 0.714），本来就不等于 0.7
    val peakAtMs = SwiftieTimeline.LOVER_BLOOM_START +
        bloom.timingsMs
            .take(humpRiseSteps(LOVER_BLOOM_POINTS, LOVER_BLOOM_RISE_FRACTION))
            .sumOf { it.toLong() }
    return listOf(
        bloom,
        SwiftieDiscreteCue(
            atMs = SwiftieTimeline.LOVER_BLOOM_START,
            kind = SwiftieHapticCueKind.LOVER_BLOOM_TICK,
            semantic = HapticSemantic.THRESHOLD_ARMED,
        ),
        SwiftieDiscreteCue(
            atMs = peakAtMs,
            kind = SwiftieHapticCueKind.LOVER_BLOOM_TICK,
            semantic = HapticSemantic.CONFIRM,
        ),
    )
}

// ----------------------------------------------------------------------------
// 包络形状。三个构造器都保证「时长之和 == 段落时长」，段落边界不许被包络挤过去。
// ----------------------------------------------------------------------------

/**
 * 把 [durationMs] 切成 [points] 个整数毫秒台阶，余数全给末格。
 *
 * 余数必须落在某一格上而不是被丢掉：包络播完的时刻是静默段断言的依据，
 * 差一毫秒就可能把一段包络算进「全静默」的定格段里。
 */
private fun evenSteps(durationMs: Long, points: Int): List<Int> {
    val base = (durationMs / points).toInt()
    val remainder = (durationMs - base.toLong() * points).toInt()
    return List(points) { index -> if (index == points - 1) base + remainder else base }
}

/**
 * 单调上升的包络：末格恰好到 [peak]。扩散吞屏用它。
 *
 * 峰值落在最后一个控制点上，所以 RichTap 那层重采样时会走「峰在末尾」的分支
 * （见 `RichTapBackend` 的 `envelopeSampleTimes`），退化后的强度不失真。
 */
private fun riseEnvelope(
    atMs: Long,
    kind: SwiftieHapticCueKind,
    durationMs: Long,
    points: Int,
    peak: Float,
): SwiftieEnvelopeCue = SwiftieEnvelopeCue(
    atMs = atMs,
    kind = kind,
    timingsMs = evenSteps(durationMs, points),
    amplitudes = List(points) { index -> peak * (index + 1) / points },
)

/**
 * 单峰包络：前 [riseFraction] 升到 [peak]，其余落回 0。签名闪光与 Lover 绽放用它。
 *
 * 上升段的末格**恰好**是 [peak]，下落段的末格恰好是 0 —— 峰值踩在一个真控制点上，
 * 而不是被两个控制点夹在中间插值出来，这样 RichTap 那层重采样也能拿到真峰值。
 * 收在 0 是刻意的：一段几秒的包络需要一个干净的收尾，硬切会听成「被掐断」。
 */
private fun humpEnvelope(
    atMs: Long,
    kind: SwiftieHapticCueKind,
    durationMs: Long,
    points: Int,
    peak: Float,
    riseFraction: Float,
): SwiftieEnvelopeCue {
    val riseSteps = humpRiseSteps(points, riseFraction)
    val fallSteps = points - riseSteps
    return SwiftieEnvelopeCue(
        atMs = atMs,
        kind = kind,
        timingsMs = evenSteps(durationMs, points),
        amplitudes = List(points) { index ->
            if (index < riseSteps) {
                peak * (index + 1) / riseSteps
            } else {
                peak * (fallSteps - 1 - (index - riseSteps)) / fallSteps
            }
        },
    )
}

/**
 * [humpEnvelope] 的上升段占几格。至少 1 格、至多 `points - 1` 格 —— 两端都留一格，
 * 免得「单峰」退化成纯上升或纯下落。
 *
 * 单独抽出来是因为算峰值时刻的地方也要它：格数是取整来的，
 * 不等于 `points × riseFraction` 那个小数。
 */
private fun humpRiseSteps(points: Int, riseFraction: Float): Int =
    (points * riseFraction).roundToInt().coerceIn(1, points - 1)

/**
 * 摆动余震的振幅：直接量屏幕上那条摆角曲线。
 *
 * 在每格中点取一次 `braceletSwayDegrees`，除以 `BRACELET_SWAY_MAX_DEG` 归一。
 * 取绝对值是因为触感没有左右 —— 往左晃和往右晃在手上是同一记。
 * 于是 |sin(4πt)| 在 1700ms 里过四个峰，包络是多峰的，只有 tier 1 播得出来。
 *
 * 采样点是从**视觉的**摆动起点（`BRACELET_DROP_MS`）算的，而包络自己晚一格才起
 * （见 [braceletCues]），所以整条曲线在手上比眼里晚约 100ms。形状一模一样，只是整体后移。
 */
private fun swayAmplitudes(points: Int): List<Float> {
    val stepMs = BRACELET_SWAY_MS.toFloat() / points
    return List(points) { index ->
        val sampleAtMs = BRACELET_DROP_MS + (stepMs * (index + 0.5f)).toLong()
        (abs(braceletSwayDegrees(sampleAtMs)) / BRACELET_SWAY_MAX_DEG).coerceIn(0f, 1f)
    }
}

// ----------------------------------------------------------------------------
// 包络的形状参数。**这里没有一个毫秒数** —— 时长全部来自各段自己的常量，
// 下面只定「一段切几格」与「振幅到多高」。控制点数都按 100ms 上下一格取，
// 那是目标机 rampStepDurationMs = 5 的整数倍，HAL 在相邻台阶之间自己爬坡。
// ----------------------------------------------------------------------------

/** 扩散吞屏 700ms 切 7 格，每格 100ms。 */
private const val DIFFUSION_RISE_POINTS = 7

/** 扩散包络的峰值，设计文档写的是 0→0.9。 */
private const val DIFFUSION_PEAK_AMPLITUDE = 0.9f

/** 落笔时的振幅，设计文档写的 0.25 —— 是一支笔在纸上走，不是一记敲击。 */
private const val SIGNATURE_WRITE_AMPLITUDE = 0.25f

/**
 * 通体闪那一下的包络长度。
 *
 * 视觉上的闪光是 `SIGNATURE_FLASH_MS`（700ms）的正弦一进一出，触感只取前 200ms ——
 * 设计文档明写「0→1→0 短包络 200 ms」。闪光是「一下」，不是「一段」，
 * 手上跟满 700ms 会变成一段嗡鸣。
 */
private const val SIGNATURE_FLASH_ENVELOPE_MS = 200L

/** 闪光 200ms 切 5 格，每格 40ms。 */
private const val SIGNATURE_FLASH_POINTS = 5

/** 摆动 1700ms 切 17 格，每格 100ms —— 够画出 |sin| 的四个峰。 */
private const val SWAY_ENVELOPE_POINTS = 17

/** 摆动替身的记数，设计文档写的「3 记衰减」。 */
private const val SWAY_STAND_IN_COUNT = 3

/** 绽放 3500ms 切 14 格，每格 250ms。 */
private const val LOVER_BLOOM_POINTS = 14

/** 绽放的峰值，设计文档写的 0→0.85→0。全曲最重的一笔。 */
private const val LOVER_BLOOM_PEAK_AMPLITUDE = 0.85f

/**
 * 绽放包络里上升段占多少。
 *
 * 0.7 让升段占 2450ms、落段占 1050ms —— 「慢升」的重点在慢，落得快一点才收得住，
 * 而且峰值正好压在配乐那句 Lover 上。
 */
private const val LOVER_BLOOM_RISE_FRACTION = 0.7f







