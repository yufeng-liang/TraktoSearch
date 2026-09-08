package com.tracktosearch.ui.haptic.backend

import android.view.View
import com.tracktosearch.ui.haptic.HapticBackend
import com.tracktosearch.ui.haptic.HapticCapabilities
import com.tracktosearch.ui.haptic.HapticSemantic
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

/**
 * tier 3：MiHaptic / IEEE 2861.3 的 HE 波形通路，全程反射 `android.os.DynamicEffect` +
 * `android.os.HapticPlayer`。小米 HyperOS、vivo OriginOS、三星 One UI 等把这套类放进了
 * framework（腾讯牵头、瑞声 AAC 参与的跨厂商标准，微信的细腻触感走的就是它）。
 *
 * ### 为什么要有这一层（真机事实，小米 14 Pro / HyperOS 3，2026-09-08 探针验证）
 *
 * - 小米 14 Pro 上 `richtap.os.PhonyVibrationEffect` / `android.os.RichTapVibrationEffect`
 *   都不存在，vendored 的 RichTap SDK 只能走腾讯通路（`HapticPlayer`），其包络退化成
 *   一记 `createOneShot` —— `RichTapBackend` 的包络通路在这台机上恒为 false。
 * - 但 ROM 直接内置了 `DynamicEffect.create(HE JSON)` + `HapticPlayer(effect).start(1,0,0)`：
 *   探针实播一段两笔画包络，logcat 显示 `AacRichTapConvert: he_event_num:2, f0:135Hz`、
 *   performer 实际执行、总时长分毫不差 —— 这是驱动马达的真波形，不是通用振幅台阶。
 * - 换句话说：这台机上「唯一画得出连续包络」的层就是本层。`AppHaptics.playEnvelope` 的
 *   包络链按 tier 降序，RichTap 拒收后正好落到这里。
 *
 * ### HE JSON 的硬规则（解析器不抛异常、只静默丢弃，所以全靠这里自觉遵守）
 *
 * 小米 `PatternHeImpl` 与 RichTap 参考实现（`RichTapCoreForAndroidT` 的 `HapticPlayer.java`）、
 * vivo `VivoHeParse` 同源，校验规则经真机探针逐条验证：
 *
 * - 顶层 `Metadata.Version` 是**整数** 1（V1 走顶层 `Pattern`）；event 数 ≤ 16。
 * - `Event.RelativeTime` 0..50000 且须递增；continuous 的 `Duration` 0..5000。
 * - `Parameters.Intensity` / `Frequency` 是整数 0..100。
 * - `Curve` 每 event ≤ 16 点；**首点必须 `{Time:0, Intensity:0}`、末点必须
 *   `{Time:Duration, Intensity:0}`**（真机实测 3 点的曲线报 `Bad point num` 被静默丢弃，
 *   即最少 4 点：首末归零 + 至少两个中间点）。
 * - 曲线点 `Time` 相对 event 起点；`Intensity` 是 **0..1 的小数**，乘在 `Parameters.Intensity`
 *   上（传 60 会按 60×100 校验越界，整条 pattern 静默作废）；`Frequency` 是 -100..100 的加性偏移。
 * - `DynamicEffect.create(String)` 不做解析（只存字符串），解析在 `start()` 内、且失败
 *   不抛给调用方 —— 所以入参合法性必须在 [hapticPlayerPatternOf] 里挡干净，那也是本层
 *   唯一可单测的部分。
 *
 * ### 只做包络，不做离散
 *
 * [supports] 对全部语义恒 false：离散语义在厂商 tier 2 有调过音的预置效果（MIUI 26 个 ID），
 * 没理由用 HE 现拼。本层上场的只有 [playEnvelope]，而它只接「控制点台阶」这一种输入，
 * 转换规则见 [hapticPlayerPatternOf]。
 *
 * ### 红线落点
 *
 * - 转子马达（`lockedToConstants`）锁 tier 0，本层 [isAvailable] 恒 false（硬规则 1）。
 * - 反射与 binder IPC 全丢单线程 `Executor`（线程名 `haptic-hapticplayer`），[release] 里
 *   shutdown（红线 4）。
 * - 整层 `catch (Throwable)`，首次失败即整层禁用，不重试（红线 5）。
 * - 系统触感总开关由引擎侧（[com.tracktosearch.ui.haptic.AppHaptics]）在调本层之前判，
 *   这条通路不经过 `View.performHapticFeedback`，框架不会替我们拦。
 *
 * @param capabilities 只读三个字段（`hasVibrator`、`lockedToConstants`、`hapticPlayerSupported`），
 *   不留引用。本层不需要 `Context`：`HapticPlayer` 的构造器只吃 `DynamicEffect`。
 */
class HapticPlayerBackend(
    capabilities: HapticCapabilities,
) : HapticBackend {

    /**
     * 本层有没有资格上场，构造期算一次。为 false 时永不提交任务，
     * [executor] 那条线程也就永远不会创建。
     */
    private val eligible: Boolean = capabilities.hasVibrator &&
        !capabilities.lockedToConstants &&
        capabilities.hapticPlayerSupported

    /** 反射与 binder IPC 的唯一执行线程；FIFO 保事件顺序（彩蛋编排依赖）。 */
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, THREAD_NAME).apply { isDaemon = true }
    }

    /** 探测只做一次的闸门；CAS 赢的那一次负责把 probe 甩给 executor。 */
    private val probeStarted = AtomicBoolean(false)

    /** 整层禁用的单向闸：探测失败、派发失败、[release] 任一来源置位，之后不再翻回。 */
    @Volatile
    private var disabled = false

    /** 探测拿到的反射句柄；为 null 时任务直接放过。只在 executor 线程写、主线程读。 */
    @Volatile
    private var reflection: HapticPlayerReflection? = null

    /** 正在播（或最后一次播）的 player，[stop] 用。只被 executor 线程写。 */
    @Volatile
    private var currentPlayer: Any? = null

    /**
     * 历史上有没有真的派发过一次播放。**只许 false 单向翻 true。**
     *
     * 与 `RichTapBackend.everInitialized` 同一形状：[stop] 在主线程被调（引擎的静音钩子），
     * 必须在投任务之前就知道有没有东西可停；而「正在播」的 [currentPlayer] 要等 executor
     * 上的任务跑完才非空。从未播过就投停止任务，只会把单线程的核心线程白白建出来。
     */
    @Volatile
    private var everPlayed = false

    override val tier: Int = TIER

    override val name: String = NAME

    /**
     * 整层可用否。纯 volatile 读，可落在每次派发的主线程路径上。
     *
     * 首次调用把探测甩给 executor（只甩一次），本方法不等它 —— 探测期间 [supports]
     * 全 false（本层本来就恒 false），包络链会先落到别的层，探完之后的调用恢复正常。
     */
    override fun isAvailable(): Boolean {
        if (!eligible) return false
        if (!probeStarted.get() && probeStarted.compareAndSet(false, true)) {
            submit { probe() }
        }
        return !disabled
    }

    /** 恒 false：离散语义归厂商 tier 2 的预置效果，本层只画包络（见类注释）。 */
    override fun supports(semantic: HapticSemantic): Boolean = false

    /** 恒 false，理由同 [supports]。 */
    override fun perform(view: View?, semantic: HapticSemantic): Boolean = false

    /**
     * 把一段控制点台阶包络转成 HE JSON 播出去。
     *
     * 入参校验全在 [hapticPlayerPatternOf] 里（那一步是纯函数）：任何不合法、或这台机的
     * HE 规则表达不了（事件数超 16、总长超 50 秒）都返回 false 让引擎降级 ——
     * **不许拿一记离散点击假装成功**，那是契约明令禁止的静默失败。
     *
     * 返回 true 只表示「已排进单线程队列」；解析失败发生在 ROM 侧且不抛回来，
     * 所以合规性全靠转换函数保证，而不是运行期报错。
     */
    override fun playEnvelope(timingsMs: IntArray, amplitudes: FloatArray): Boolean {
        if (!isAvailable()) return false
        val pattern = hapticPlayerPatternOf(timingsMs, amplitudes) ?: return false
        val json = heJsonOf(pattern)
        return submit {
            val state = reflection ?: return@submit
            try {
                val effect = state.create.invoke(null, json)
                val player = state.ctor.newInstance(effect)
                state.start.invoke(
                    player,
                    START_LOOP_ONCE,
                    START_INTERVAL_EFFECT_OWN,
                    START_AMPLITUDE_EFFECT_OWN,
                )
                currentPlayer = player
                everPlayed = true
            } catch (_: Throwable) {
                markDisabled()
            }
        }
    }

    /**
     * 停掉正在播的 HE 波形。不在 `HapticBackend` 契约里，与 `RichTapBackend.stop()`、
     * `AospWaveformBackend.cancel()` 同一形状：只有真能画长波形的层需要停止通道，
     * 引擎侧由 `HapticModule` 把三者的停止一起包进 `AppHaptics` 的 `quietDown`。
     *
     * 从未播过就直接返回、连任务都不投（见 [everPlayed]）。幂等；release 之后是空操作。
     */
    fun stop() {
        if (!everPlayed) return
        submit {
            try {
                currentPlayer?.let { player ->
                    reflection?.stop?.invoke(player)
                }
            } catch (_: Throwable) {
                markDisabled()
            }
        }
    }

    /** 释放：停播、禁用整层、shutdown 单线程。幂等。 */
    override fun release() {
        markDisabled()
        executor.shutdown()
    }

    /**
     * 把任务排进单线程队列。提交本身会抛（已 shutdown / 线程建不出来是 Error），
     * 一律吃掉并禁用整层 —— 与 `MiuiBackend.submit` 同一形状。
     */
    private fun submit(task: () -> Unit): Boolean = try {
        executor.execute(Runnable { task() })
        true
    } catch (_: Throwable) {
        disabled = true
        false
    }

    /**
     * 在 executor 线程上跑一次的完整探测：解析三个反射句柄。
     *
     * `HapticCapabilities.probe` 已经用静态 `isAvailable()` 判过整层可用性，这里不再重做
     * （那是慢调用，且结论不会变）；本方法只负责把本层要用的方法签名解析出来，
     * 任何一个查不到就整层禁用 —— `NoSuchMethodException` 在别的 ROM 修订上是常态。
     */
    private fun probe() {
        if (disabled) return
        try {
            val effectClass = Class.forName(DYNAMIC_EFFECT)
            val playerClass = Class.forName(HAPTIC_PLAYER)
            val create = effectClass.getMethod(METHOD_CREATE, String::class.java)
            val ctor = playerClass.getConstructor(effectClass)
            val start = playerClass.getMethod(
                METHOD_START,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            )
            val stop = playerClass.getMethod(METHOD_STOP)
            reflection = HapticPlayerReflection(create, ctor, start, stop)
        } catch (_: Throwable) {
            markDisabled()
        }
    }

    /** 整层禁用并丢掉句柄。刻意不 shutdown [executor] —— 那是 [release] 的活。 */
    private fun markDisabled() {
        disabled = true
        reflection = null
        currentPlayer = null
    }

    /** 探测拿到的四个反射句柄；整体替换，读到的必然配套。 */
    private class HapticPlayerReflection(
        val create: Method,
        val ctor: Constructor<*>,
        val start: Method,
        val stop: Method,
    )

    private companion object {
        /** 3 = 波形层，与 RichTap 同档：都能画连续包络，表达力同级、通路不同。 */
        const val TIER = 3

        /** 降级矩阵单测靠这个名字断言最终停在哪一层，取编译期常量保证稳定。 */
        const val NAME = "HapticPlayer"

        /** 单线程线程名，带得出来源，便于在 trace / ANR 栈里认出这条队列。 */
        const val THREAD_NAME = "haptic-hapticplayer"

        /** IEEE 2861.3 的两个核心类，类名与方法名是协议规定死的（厂商不得改名）。 */
        const val DYNAMIC_EFFECT = "android.os.DynamicEffect"
        const val HAPTIC_PLAYER = "android.os.HapticPlayer"

        /** `DynamicEffect.create(String json)`：静态，注意它不解析、只存字符串。 */
        const val METHOD_CREATE = "create"

        /** `HapticPlayer.start(int loop, int interval, int amplitude)`（真机验证过的重载）。 */
        const val METHOD_START = "start"

        /** `HapticPlayer.stop()`。 */
        const val METHOD_STOP = "stop"

        /** loop=1：播一遍。真机日志确认这个语义（`start loop:1,...`）。 */
        const val START_LOOP_ONCE = 1

        /** interval=0：无循环间隔。 */
        const val START_INTERVAL_EFFECT_OWN = 0

        /** amplitude=0：用 HE 文件自身的强度，不整体覆盖（真机实播验证）。 */
        const val START_AMPLITUDE_EFFECT_OWN = 0
    }
}

// ============================================================================
// 以下是本层唯一可单测的部分：控制点台阶 → HE Pattern 的转换与 JSON 渲染。
// 不碰 Context、不碰反射，纯输入输出。
// ============================================================================

/** HE 的一个曲线点。曲线点 Time 相对 event 起点；Intensity 是 0..1 的乘数；Frequency 是加性偏移。 */
internal data class HeCurvePoint(val timeMs: Int, val intensity: Float, val frequency: Int)

/** HE 的一个事件：一段持续震动（continuous）。transient 本层用不上，不建模。 */
internal data class HeEvent(
    val relativeTimeMs: Int,
    val durationMs: Int,
    /** 0..100 的整数强度，曲线点的乘数落在这上面。 */
    val intensity: Int,
    /** 0..100 的整数频率。本层没有频率通道，恒取 [HE_DEFAULT_FREQUENCY]。 */
    val frequency: Int,
    val curve: List<HeCurvePoint>,
)

/** 一份完整的 HE Pattern（V1，顶层 `Pattern`）。 */
internal data class HePattern(val events: List<HeEvent>)

/** HE 规则的硬上限（解析器静默截断/丢弃，所以必须在生成侧挡住）。 */
internal object HeLimits {
    /** 每份 pattern 最多 16 个 event。 */
    const val MAX_EVENTS = 16

    /** 每个 event 最长 5000 ms。 */
    const val MAX_EVENT_DURATION_MS = 5000

    /** 每条曲线最多 16 个点（含强制的首末归零点）。 */
    const val MAX_CURVE_POINTS = 16

    /** 曲线最少 4 个点：首末归零 + 至少两个中间点（真机实测 3 点报 Bad point num）。 */
    const val MIN_CURVE_POINTS = 4

    /** 整份 pattern 最长 50 秒。 */
    const val MAX_TOTAL_MS = 50_000

    /** 无频率通道时的中性频率：平台 [0,100] 的中点，真机实播验证过。 */
    const val DEFAULT_FREQUENCY = 50
}

/**
 * 把 `HapticBackend.playEnvelope` 契约里的控制点台阶转成 HE Pattern；表达不了返回 null。
 *
 * 契约语义：曲线从 0 ms、振幅 0 起步，第 i 个控制点在 `timingsMs[i]` 毫秒内线性爬到
 * `amplitudes[i]`。转换规则：
 *
 * - 把输入切成**极大非零段**（run），每段一个 continuous event，段与段之间的归零间隙
 *   自然成为静默 —— 签名段「落笔连续、抬笔静默」的多峰包络因此不必拆开调用。
 * - run 的 `Parameters.Intensity` 取该段峰值 ×100；曲线点 Intensity 是各点振幅占峰值的
 *   比例（0..1 乘数），首末各补一个归零点（HE 硬规则，也正好表达「落笔/抬笔」的起收）。
 * - 中间点不足两个时（单点等幅段，签名段的每一笔就是）在段内 1/3 与 2/3 处补两个峰
 *   值点 —— 既是 HE 最少 4 点的要求，也让单点台阶变成「起笔—行笔—收笔」。
 * - 中间点多于 14 个时均匀抽稀到 14 个（加首末共 16，HE 上限）。
 * - 返回 null 的情形：入参不合法（空、两串不等长、时长非正、振幅越界或 NaN）、
 *   全静默（峰值 0）、run 数超 [HeLimits.MAX_EVENTS]、任一 run 超长而拆分后仍超限、
 *   总时长超 [HeLimits.MAX_TOTAL_MS]。
 *
 * `when`/分支不依赖 `HapticSemantic`，纯台阶数学，可裸跑 JVM 单测。
 */
internal fun hapticPlayerPatternOf(timingsMs: IntArray, amplitudes: FloatArray): HePattern? {
    if (timingsMs.isEmpty() || timingsMs.size != amplitudes.size) return null
    if (timingsMs.any { it <= 0 }) return null
    if (amplitudes.any { it.isNaN() || it < 0f || it > 1f }) return null
    val totalMs = timingsMs.sumOf { it.toLong() }
    if (totalMs <= 0L || totalMs > HeLimits.MAX_TOTAL_MS) return null

    // 累计时刻表：cumulative[i] = 第 i 个控制点到达的绝对时刻
    val cumulative = IntArray(timingsMs.size)
    var acc = 0
    for (index in timingsMs.indices) {
        acc += timingsMs[index]
        cumulative[index] = acc
    }

    // 切极大非零段
    val runs = mutableListOf<IntRange>()
    var start = -1
    for (index in amplitudes.indices) {
        val nonzero = amplitudes[index] > 0f
        if (nonzero && start < 0) start = index
        if ((!nonzero || index == amplitudes.lastIndex) && start >= 0) {
            val end = if (nonzero && index == amplitudes.lastIndex) index else index - 1
            if (end >= start) runs += start..end
            start = -1
        }
    }
    if (runs.isEmpty()) return null

    val events = mutableListOf<HeEvent>()
    for (run in runs) {
        val runStartMs = if (run.first == 0) 0 else cumulative[run.first - 1]
        val runEndMs = cumulative[run.last]
        val durationMs = runEndMs - runStartMs
        if (durationMs <= 0) return null
        val peak = run.maxOf { amplitudes[it] }
        if (peak <= 0f) return null

        // 中间点：run 内各控制点的（段内相对时刻, 占峰值比例）。时刻夹进 (0, duration)：
        // 落在 0 或 duration 上的点（比如上升包络的末点恰好压在段尾）会被挤到边界内侧，
        // 保住收尾形状 —— 首末的归零点是强制的，中间点与它们撞在同一时刻属于乱序。
        val mids = sanitizeMids(
            run.map { index ->
                HeCurvePoint(
                    timeMs = cumulative[index] - runStartMs,
                    intensity = (amplitudes[index] / peak).coerceIn(0f, 1f),
                    frequency = 0,
                )
            },
            durationMs,
        )

        // 抽稀到上限内；不足两个中间点时由 buildHeEvent 在段内 1/3、2/3 处补峰值点
        val midPoints = if (mids.size > HeLimits.MAX_CURVE_POINTS - 2) {
            downsample(mids, HeLimits.MAX_CURVE_POINTS - 2)
        } else {
            mids
        }

        // 超长段拆分：HE 单 event 上限 5000ms，拆点处首末归零会留一道谷，
        // 但比整段拒收好 —— 现有谱子最长一段 3500ms，这条只是兜底
        if (durationMs > HeLimits.MAX_EVENT_DURATION_MS) {
            val pieceCount = (durationMs + HeLimits.MAX_EVENT_DURATION_MS - 1) / HeLimits.MAX_EVENT_DURATION_MS
            val pieceDuration = (durationMs + pieceCount - 1) / pieceCount
            for (piece in 0 until pieceCount) {
                val pieceStart = runStartMs + piece * pieceDuration
                val thisDuration = if (piece == pieceCount - 1) runEndMs - pieceStart else pieceDuration
                if (thisDuration <= 0) break
                val pieceMids = sanitizeMids(
                    midPoints.map { p ->
                        HeCurvePoint(
                            p.timeMs - piece * pieceDuration,
                            p.intensity,
                            p.frequency,
                        )
                    },
                    thisDuration,
                )
                events += buildHeEvent(pieceStart, thisDuration, peak, pieceMids)
            }
        } else {
            events += buildHeEvent(runStartMs, durationMs, peak, midPoints)
        }
    }

    if (events.size > HeLimits.MAX_EVENTS) return null
    if (events.any { it.durationMs > HeLimits.MAX_EVENT_DURATION_MS }) return null
    return HePattern(events)
}

/** 拼一个合规 event：Parameters 取峰值档，曲线强制首末归零，中间点数量已在限内。 */
private fun buildHeEvent(relativeTimeMs: Int, durationMs: Int, peak: Float, mids: List<HeCurvePoint>): HeEvent {
    val midPoints = if (mids.size >= 2) {
        mids
    } else {
        val level = mids.firstOrNull()?.intensity ?: 1f
        listOf(
            HeCurvePoint(durationMs / 3, level, 0),
            HeCurvePoint(durationMs * 2 / 3, level, 0),
        )
    }
    return HeEvent(
        relativeTimeMs = relativeTimeMs,
        durationMs = durationMs,
        intensity = (peak * 100).roundToInt().coerceIn(1, 100),
        frequency = HeLimits.DEFAULT_FREQUENCY,
        curve = buildList {
            add(HeCurvePoint(0, 0f, 0))
            addAll(midPoints)
            add(HeCurvePoint(durationMs, 0f, 0))
        },
    )
}

/**
 * 把中间点时刻夹进 `(0, duration)` 开区间，同一时刻挤到一起的保留最后一个。
 *
 * 夹而不是丢：上升包络的末点恰好压在 duration 上，丢掉它会让峰值被拦腰截断；
 * 夹到 `duration - 1` 至少保住收尾的高度。挤成同一时刻的几个点里最后一个离真实包络
 * 的走向最近。`groupBy` 按键的首见顺序输出，保序。
 */
private fun sanitizeMids(raw: List<HeCurvePoint>, durationMs: Int): List<HeCurvePoint> {
    if (raw.isEmpty()) return raw
    val upperBound = (durationMs - 1).coerceAtLeast(1)
    return raw
        .map { it.copy(timeMs = it.timeMs.coerceIn(1, upperBound)) }
        .groupBy(keySelector = { point -> point.timeMs })
        .map { (_, points) -> points.last() }
}

/** 均匀抽稀到 [count] 个，保序。 */
private fun downsample(points: List<HeCurvePoint>, count: Int): List<HeCurvePoint> {
    if (points.size <= count) return points
    return List(count) { index ->
        points[index * (points.size - 1) / (count - 1)]
    }
}

/** HE V1 的 JSON 渲染。曲线点 Intensity 用四位小数定点，避免浮点 toString 出科学计数法。 */
internal fun heJsonOf(pattern: HePattern): String = buildString {
    append("{\"Metadata\":{\"Version\":1,\"Created\":\"2026-09-08\",\"Description\":\"")
    append(HE_DESCRIPTION)
    append("\"},\"Pattern\":[")
    pattern.events.forEachIndexed { index, event ->
        if (index > 0) append(',')
        append("{\"Event\":{\"Type\":\"continuous\",\"RelativeTime\":")
        append(event.relativeTimeMs)
        append(",\"Duration\":")
        append(event.durationMs)
        append(",\"Parameters\":{\"Intensity\":")
        append(event.intensity)
        append(",\"Frequency\":")
        append(event.frequency)
        append(",\"Curve\":[")
        event.curve.forEachIndexed { pointIndex, point ->
            if (pointIndex > 0) append(',')
            append("{\"Time\":")
            append(point.timeMs)
            append(",\"Intensity\":")
            append(formatHeFloat(point.intensity))
            append(",\"Frequency\":")
            append(point.frequency)
            append('}')
        }
        append("]}}}")
    }
    append("]}")
}

private const val HE_DESCRIPTION = "tracktosearch envelope"

/** 曲线点强度定点四位小数（0.0000..1.0000），JSON 数值合法且无科学计数法。 */
private fun formatHeFloat(value: Float): String = String.format(java.util.Locale.ROOT, "%.4f", value)
