package com.tracktosearch.ui.haptic.backend

import android.content.Context
import android.view.View
import com.apprichtap.haptic.RichTapUtils
import com.apprichtap.haptic.base.PrebakedEffectId
import com.tracktosearch.ui.haptic.HapticBackend
import com.tracktosearch.ui.haptic.HapticCapabilities
import com.tracktosearch.ui.haptic.HapticSemantic
import com.tracktosearch.ui.haptic.HapticStrength
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

/**
 * tier 3：RichTap 波形通路，走 vendored 的 `richtap_sdk_lite.aar`（`ApiInfo.VERSION_NAME`
 * 实测是 `2.0.4-lite_20220511`）。
 *
 * 四层里唯一能画连续包络的一层，所以 [playEnvelope] 是选 lite 版的主要收益。
 * 离散语义走 SDK 的 50 个预置效果（`PrebakedEffectId`，ID 10001..10050），
 * 映射见设计文档 `docs/superpowers/plans/2026-09-01-haptics-overhaul.md` 的「语义词表」tier 3 列，
 * 实现落在纯函数 [richTapEffectFor] 里。
 *
 * 三条与本层有关的红线：
 *
 * 1. 转子马达（`HapticCapabilities.lockedToConstants`）锁 tier 0，本层 [isAvailable] 直接 false。
 * 2. 反射与 IPC 全部丢单线程 `Executor`，[release] 里 shutdown。
 * 3. 整层 `catch (Throwable)`，首次失败即整层禁用（[available] 单向翻 false），不重试。
 *
 * 两个不显眼但会造成静默失败的点，都在 [ensureInitialized] 里挡掉了。SDK 内部有三条播放
 * 通路，`init()` 时自己挑一条，挑到哪条决定了这一层到底能做什么：
 *
 * - RichtapPlayer：反射 ROM 侧的 `createEnvelope` 与 HE 播放，预置效果与包络都是真的，
 *   这才是我们要的那条。
 * - TencentPlayer：走 `android.os.HapticPlayer`，预置效果是真的 HE 波形，但它的
 *   `playEnvelope` 实现直接退化成一记 `createOneShot`（反编译确认）。目标机小米 14 Pro
 *   很可能正落在这条路上，所以本层额外探一次 ROM 侧的 `createEnvelope`，
 *   探不到就只关 [playEnvelope]、保留 [perform]。
 * - GooglePlayer：`isNonRichTapMode()` 为 true 时就是它，预置效果被写成固定 65 ms 的
 *   `createOneShot`，比 AOSP tier 1 还糙；顶着 tier 3 的名头上场是骗降级链，所以整层禁用。
 *
 * `RichTapUtils` 是进程级单例，且 `quit()` 会把静态 `sInstance` 置空 ——
 * 所以本类必须是单例（Hilt `@Singleton`），两个实例会互相把对方拆掉。
 */
class RichTapBackend(
    context: Context,
    private val capabilities: HapticCapabilities,
) : HapticBackend {

    /** SDK 的 `init(Context)` 内部就取 `applicationContext`，这里先取好，不持有 Activity。 */
    private val appContext: Context = context.applicationContext

    /**
     * 单线程：`init()` 会做一次 `getSystemService` 加三次 `Class.forName`，
     * `playExtPrebaked` 还要走到 `VibratorService`，都不能落在主线程上。
     * 单线程而非线程池是硬要求 —— 彩蛋编排依赖事件顺序。
     *
     * `newSingleThreadExecutor` 只建对象、不预启线程，线程要到第一次 `execute` 才创建，
     * 所以整层不可用时这里是零开销（前提是没人白投任务，见 [stop]）。
     * 设成 daemon 与另两个厂商 backend 对齐：一记触感不值得拖住进程退出。
     */
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, EXECUTOR_THREAD_NAME).apply { isDaemon = true }
    }

    /** 整层可用性。构造期按能力快照算一次，之后只允许由 true 单向翻成 false。 */
    @Volatile
    private var available: Boolean = capabilities.hasVibrator &&
        !capabilities.lockedToConstants &&
        capabilities.richTapSupported

    /** 连续包络是否真的可用。要等 [ensureInitialized] 探完 ROM 侧 `createEnvelope` 才可能为 true。 */
    @Volatile
    private var envelopeAvailable: Boolean = false

    /** [release] 的一次性闩，保证幂等。 */
    private val released = AtomicBoolean(false)

    /** 只在 executor 线程上读写，因此不需要 volatile。 */
    private var initialized: Boolean = false

    /**
     * 历史上有没有 init 成功过。**只许由 false 单向翻成 true**，[release] 也不清它。
     *
     * 存在的理由只有一个：[stop] 是主线程调的，得在投任务之前就知道「有没有东西可停」，
     * 而 [initialized] 只在 executor 线程上读写、且 [release] 会把它清回 false，主线程读不得。
     *
     * 刻意不判 [available]：派发失败会把 [available] 翻成 false，而那一刻很可能正有一段
     * 包络在飞，恰恰最需要 [stop] 停得下来。
     */
    @Volatile
    private var everInitialized: Boolean = false

    override val tier: Int = TIER

    override val name: String = NAME

    init {
        // 预热：init() 加上包络通路探测都是慢调用，趁早在自己的单线程上做完。
        // 不预热的话第一次 perform 要顶着 IPC 延迟，而 playEnvelope 会因为还没探完而误报 false。
        if (available) submit { ensureInitialized() }
    }

    override fun isAvailable(): Boolean = available && !released.get()

    /**
     * 13 个语义全部映射到 10001..10050 的预置效果，所以本层能表达的就是「整层是否可用」。
     * 纯查表，无反射、无 IPC，可在主线程调。
     */
    override fun supports(semantic: HapticSemantic): Boolean = isAvailable()

    /** @param view 本层不需要 View 通道，忽略。 */
    override fun perform(view: View?, semantic: HapticSemantic, strength: HapticStrength): Boolean {
        if (!isAvailable()) return false
        val effect = richTapEffectFor(semantic).atStrength(strength)
        return submit {
            if (ensureInitialized()) {
                RichTapUtils.getInstance().playExtPrebaked(effect.effectId, effect.strength)
            }
        }
    }

    /**
     * `RichTapUtils.playEnvelope` 的两个重载在 lite 版里都标了 `@Deprecated`，而且没有替代品：
     * 不废弃的 `playHaptic` 只吃 `.he` 文件，而 `.he` 的格式规范至今没取得
     * （设计文档「未决与未验证」有这条）。所以只能带着 `@Suppress` 用。
     * 这里用四参重载，它内部就是把第五个参数（全局强度）补成 255 后转给五参重载。
     */
    @Suppress("DEPRECATION")
    override fun playEnvelope(timingsMs: IntArray, amplitudes: FloatArray): Boolean {
        if (!isAvailable() || !envelopeAvailable) return false
        val envelope = richTapEnvelopeOf(timingsMs, amplitudes) ?: return false
        return submit {
            if (ensureInitialized() && envelopeAvailable) {
                RichTapUtils.getInstance().playEnvelope(
                    envelope.relativeTimeMs,
                    envelope.scales,
                    envelope.frequencies,
                    false,
                )
            }
        }
    }

    /**
     * 立刻停掉正在播的波形（`RichTapUtils.stop()` = 播放器 stop 加 `Vibrator.cancel()`）。
     *
     * **不在 `HapticBackend` 契约里**，是本类额外提供的成员：契约没有中断通道，而 [playEnvelope]
     * 一旦派发就长达数秒，彩蛋要跟随 `SwiftieMusic` 的三条闸门（`paused`、`ON_STOP`、
     * `AUDIOFOCUS_LOSS`）时必须停得下来。tier 2 与 tier 0 发不出连续包络，tier 1 用
     * `Vibrator.cancel()` 就能停，所以这个需求只有本层独有，没有做进公共契约。
     *
     * 从未 init 成功过就直接返回，**连任务都不投**：[submit] 只看 [released] 闸，白投的那一次
     * 会把单线程 executor 的核心线程真建出来，而它 `allowCoreThreadTimeOut` 是 false、
     * 任务体又恒为空转，于是永久 park 在 `workQueue.take()` 上；本类是 `@Singleton`，
     * 生产上也没人调 [release]，那条线程就再也退不掉了。这条路走得到：`HapticModule` 的
     * `quietDown` 钩子接的就是本方法，任何非 RichTap 机型把开关拨到 `OFF` 都会调进来。
     *
     * 顺带说清这道闸不会漏停：[playEnvelope] 要 [envelopeAvailable] 为 true 才派发，而它是在
     * [ensureInitialized] 里、[everInitialized] 置位之后才写的，两者都是 volatile ——
     * 凡是真派发出去过包络的时刻，这里必然已经能看到 [everInitialized] 为 true。
     *
     * @return true 已派发；false 从未 init 成功过、已 [release]、或 executor 拒收
     */
    fun stop(): Boolean {
        if (!everInitialized) return false
        return submit {
            // release() 之后 SDK 已 quit()，不能再碰；那种情况下 initialized 已被清回 false
            if (initialized) RichTapUtils.getInstance().stop()
        }
    }

    override fun release() {
        if (!released.compareAndSet(false, true)) return
        available = false
        envelopeAvailable = false
        try {
            executor.execute {
                try {
                    if (initialized) {
                        val utils = RichTapUtils.getInstance()
                        utils.stop()
                        // quit() 会 cancel 马达、停播放器，并把 SDK 的静态 sInstance 置空
                        utils.quit()
                    }
                } catch (_: Throwable) {
                    // 拆卸失败没有补救手段，也不该抛出去；executor 紧接着 shutdown
                } finally {
                    initialized = false
                }
            }
        } catch (_: Throwable) {
            // executor 已经关了（并发 release），无事可做
        }
        executor.shutdown()
    }

    /**
     * 把一次调用投到单线程上。整层的 `catch (Throwable)` 就在这里：
     * 任务里抛出任何 `Throwable`（含 `NoClassDefFoundError` 这类 Error）都整层禁用，不重试。
     *
     * @return true 已派发（**不表示马达已经震完**）；false 已 [release] 或 executor 拒收
     */
    private fun submit(task: () -> Unit): Boolean {
        if (released.get()) return false
        return try {
            executor.execute {
                try {
                    task()
                } catch (_: Throwable) {
                    available = false
                    envelopeAvailable = false
                }
            }
            true
        } catch (_: Throwable) {
            // RejectedExecutionException：executor 已 shutdown
            false
        }
    }

    /**
     * 首次调用时把 SDK 初始化起来并探一次包络通路。**只在 executor 线程上调**，
     * 因此 [initialized] 不需要加锁；[everInitialized] 在同一处置位，两者的分工见各自字段 KDoc。
     *
     * `isNonRichTapMode()` 被 SDK 标了 `@Deprecated` 但没给替代品，而它是唯一能问出
     * 「init 之后到底选了哪条内部通路」的接口，只能带着 `@Suppress` 用。
     *
     * @return false 表示本层已被判死，调用方不要继续发波形
     */
    @Suppress("DEPRECATION")
    private fun ensureInitialized(): Boolean {
        if (initialized) return true
        val utils = RichTapUtils.getInstance()
        utils.init(appContext)
        if (utils.isNonRichTapMode()) {
            // SDK 自己退到了 GooglePlayer：预置效果变成写死 65 ms 的 createOneShot，
            // 那是 tier 1 都不如的东西，tier 3 不能顶着 RichTap 的名头替它上场
            available = false
            return false
        }
        initialized = true
        everInitialized = true
        envelopeAvailable = probeEnvelopePath()
        return true
    }

    /**
     * 探一次 ROM 侧真正的包络通路，只在 [ensureInitialized] 里做一次。
     *
     * 复刻的是 SDK 内部 RichtapPlayer 的选路判据：按 `richtap.os.PhonyVibrationEffect` →
     * `android.os.RichTapVibrationEffect` → `android.os.VibrationEffect` 的顺序取第一个能加载的类，
     * 要求它同时有静态 `checkIfRichTapSupport()`（返回 [RICHTAP_UNSUPPORTED] 即不支持）
     * 和 `createEnvelope(int[], int[], int[], boolean, int)`。
     *
     * 判据落在这两个方法上而不是 SDK 的混淆内部类上：ROM 侧类名是平台事实，混淆后的字母不是。
     * 少了 `createEnvelope` 就说明 SDK 会退到 TencentPlayer，那条路上的 `playEnvelope`
     * 直接变成一记 `createOneShot` —— 拿离散点击假装包络，契约明令禁止，所以只关包络。
     */
    private fun probeEnvelopePath(): Boolean = try {
        val holder = ENVELOPE_EFFECT_CLASSES.firstNotNullOfOrNull { name ->
            try {
                Class.forName(name)
            } catch (_: Throwable) {
                null
            }
        }
        if (holder == null) {
            false
        } else {
            // 只判存在性，取不到会抛 NoSuchMethodException，被外层吃掉
            holder.getMethod(
                METHOD_CREATE_ENVELOPE,
                IntArray::class.java,
                IntArray::class.java,
                IntArray::class.java,
                Boolean::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            )
            val support = holder.getMethod(METHOD_CHECK_SUPPORT).invoke(null) as? Int
            support != null && support != RICHTAP_UNSUPPORTED
        }
    } catch (_: Throwable) {
        false
    }

    private companion object {
        /** 3 = RichTap 波形，四层里表达力最强的一层。 */
        const val TIER = 3

        /** 降级矩阵单测靠它断言最终选中了哪一层，取编译期常量保证稳定。 */
        const val NAME = "RichTap"

        /** 线程名带得出来源，便于在 traces 与 ANR 里认出这条通路。 */
        const val EXECUTOR_THREAD_NAME = "haptic-richtap"

        /** 复刻 SDK 内部 RichtapPlayer 的取类顺序，第一个能加载的类就是包络通路的持有者。 */
        val ENVELOPE_EFFECT_CLASSES = listOf(
            "richtap.os.PhonyVibrationEffect",
            "android.os.RichTapVibrationEffect",
            "android.os.VibrationEffect",
        )

        /** ROM 侧建包络效果的方法名，签名 `(int[], int[], int[], boolean, int)`。 */
        const val METHOD_CREATE_ENVELOPE = "createEnvelope"

        /** ROM 侧的静态无参能力查询，SDK 内部也是靠它选路。 */
        const val METHOD_CHECK_SUPPORT = "checkIfRichTapSupport"

        /** `checkIfRichTapSupport()` 返回 2 表示本机不支持 RichTap，与 SDK 内部判据一致。 */
        const val RICHTAP_UNSUPPORTED = 2
    }
}

// ============================================================================
// 以下是本层唯一可单测的部分：不碰 Context、不碰反射、不碰 SDK 实例，纯输入输出。
// ============================================================================

/**
 * 预置效果的强度梯队。取值域 0..255 是 `playExtPrebaked` 自己校验的范围
 * （越界抛 `IllegalArgumentException`，报文写明「between 0 and 255 included」），
 * 反编译确认它最终落到 SDK 内部 HE 播放参数的 amplitude 位，255 即满幅。
 *
 * 只有五档，每一档的**取值**都抄自设计文档「语义词表」tier 1 列里给出的 scale ——
 * 同一个语义在两层里的轻重尽量一致，不然用户换台机器就觉得手感变了。
 *
 * 但这只是取值的出处，不是「哪个语义该拿哪一档」的无条件断言：tier 3 的选档以设计文档
 * tier 3 列、以及同一个效果 ID 内部的轻重次序为准，与 tier 1 冲突时服从前者。
 * 已知两处刻意不与 tier 1 对齐，见 [richTapEffectFor]：
 *
 * - `TOGGLE_ON` 在 tier 1 是 `clickRecipe(0.7)`，tier 3 给 [FULL] —— 开关族里较重的那一半；
 * - `GESTURE_END` 在 tier 1 是 `thudRecipe(0.5)`，tier 3 给 [MEDIUM] —— 同为 `RT_SOFT_CLICK`
 *   时必须重于 `POPUP_SHOW` 的 [LIGHT]，否则两个语义在这一层撞成一个手感。
 *
 * 不再细分：档位越多越难在真机上分辨，只会让映射表更难维护。
 */
internal object RichTapStrength {
    /** 满幅。设计文档 tier 3 列里没标「低幅」的语义都用这一档。 */
    const val FULL = 255

    /** 0.7 × 255。取值抄自 tier 1 的 `toggleOn` scale 0.7；tier 3 的 `TOGGLE_ON` 不用这一档。 */
    const val MEDIUM = 178

    /** 0.5 × 255。取值抄自 tier 1 的 `toggleOff` 与 `gestureEnd` scale 0.5。 */
    const val LIGHT = 128

    /** 0.4 × 255。对齐 tier 1 的 `scrollEdge` scale 0.4。 */
    const val FAINT = 102

    /** 0.3 × 255。比 [FAINT] 再轻一档，只给一次手势里连发几十次的 `frequentTick`。 */
    const val FEATHER = 76
}

/**
 * 一次 `playExtPrebaked` 调用的两个参数。
 *
 * @param effectId `PrebakedEffectId` 里的预置效果 ID，必须落在 10001..10050
 *   （`PREBAKED_ID_MIN`..`PREBAKED_ID_MAX`）。超出这个区间 SDK 会改走另一条把参数
 *   当 AOSP `EFFECT_` 常量用的通路，手感完全不同。
 * @param strength 强度 0..255，见 [RichTapStrength]
 */
internal data class RichTapEffect(val effectId: Int, val strength: Int) {
    /**
     * 档位强度缩放：轻 ×0.6、强 ×1.3（封顶 255、触底至少 1）。
     *
     * [HapticStrength.SYSTEM] 原样返回——那档的强度是「厂商调好的」，不干预。
     * 缩放只动强度位不动效果 ID：轻/强改的是同一个效果的轻重，不是换效果
     * （换效果 = 换性格，滑块连发的轻滴答被顶成重击，2026-09-09 裁定废止）。
     */
    fun atStrength(strength: HapticStrength): RichTapEffect = when (strength) {
        HapticStrength.SYSTEM -> this
        HapticStrength.LIGHT -> RichTapEffect(
            effectId,
            (this.strength * RICHTAP_LIGHT_FACTOR).roundToInt().coerceIn(1, 255),
        )
        HapticStrength.STRONG -> RichTapEffect(
            effectId,
            (this.strength * RICHTAP_STRONG_FACTOR).roundToInt().coerceIn(1, 255),
        )
    }
}

/**
 * RichTap 的四点包络，三个数组长度恒为 [RICHTAP_ENVELOPE_POINTS]。
 *
 * 刻意不是 `data class`：数组的 `equals` 是引用相等，`data class` 会生成一个看起来能比
 * 其实比不了的 `equals`。单测请用 `contentEquals`。
 *
 * @param relativeTimeMs 四个**累计**时刻，单位毫秒，严格递增，首项 0、末项等于总时长。
 *   反编译两条兜底通路确认了这个语义：它们都拿 `relativeTime[3]` 当整段时长去
 *   `createOneShot`，所以这四个数是时间轴上的绝对位置，不是每段的持续时间。
 * @param scales 四个时刻各自的目标振幅 0f..1f。SDK 内部会乘 100 取整交给 ROM，
 *   兜底通路则拿 `max(scales[1], scales[2])` 当整段峰值 —— 所以峰值必须落在中间两位。
 * @param frequencies 四个时刻的频率参数，恒为 [RICHTAP_ENVELOPE_DEFAULT_FREQUENCY]
 */
internal class RichTapEnvelope(
    val relativeTimeMs: IntArray,
    val scales: FloatArray,
    val frequencies: IntArray,
)

/** RichTap 包络的控制点数，SDK 写死 4：只取三个数组的前 4 项，短于 4 会数组越界。 */
internal const val RICHTAP_ENVELOPE_POINTS = 4

/**
 * 能排出 4 个互不相同的时刻所需的最短总时长。
 *
 * 比这更短的「包络」本质上就是一记离散点击，交给上层降级成 [HapticSemantic] 更合适。
 */
internal const val RICHTAP_ENVELOPE_MIN_DURATION_MS = 3

/** 轻/强档对 `playExtPrebaked` 强度位（0..255）的缩放系数，见 [RichTapEffect.atStrength]。 */
internal const val RICHTAP_LIGHT_FACTOR = 0.6f

/** 同上，强档。×1.3 后封顶 255。 */
internal const val RICHTAP_STRONG_FACTOR = 1.3f

/**
 * 频率参数固定传 0，含义是「不覆盖频率」而不是「0 Hz」。
 *
 * 两处证据：SDK 播 50 个内置预置效果时对每一个都传 freq = 0（反编译确认）；
 * 内部参数容器 `base.g` 的 freq 字段默认值也是 0，而 amplitude 默认 255。
 * 另外 `RichTapUtils.playEnvelope` 会拒收负数（报文「freq must be positive」），
 * 所以 HE 2.0 曲线那套 -100..100 的频偏语义在这个 API 上用不上。
 *
 * 目标机小米 14 Pro 的 `frequencyProfile` 全是 NaN，连频率区间都不暴露，
 * 本来也没有频率通道可调 —— 见设计文档「目标机实测」一节。
 */
internal const val RICHTAP_ENVELOPE_DEFAULT_FREQUENCY = 0

/**
 * 语义 → RichTap 预置效果。设计文档「语义词表」的 tier 3 列就是这张表。
 *
 * 效果 ID 取自 SDK 自己的 `PrebakedEffectId`，不写字面量：ID 是 SDK 的事实，
 * 抄成数字以后升级 aar 就要人工核对。已核对过设计文档里点名的 8 个 ID 与 SDK 一致
 * （`RT_CLICK` 10001、`RT_SOFT_CLICK` 10003、`RT_TICK` 10004、`RT_FAILURE` 10006、
 * `RT_SUCCESS` 10007、`RT_RAMP_UP` 10008、`RT_TOGGLE_SWITCH` 10009、`RT_LONG_PRESS` 10010）。
 *
 * 设计文档里标了「低幅」但没给数值的四个语义（`frequentTick`、`toggleOff`、`scrollEdge`，
 * 以及和 `lightTap` 撞同一个效果的 `gestureEnd` 与 `popupShow`），强度按两条约束定：
 * 一是对齐 tier 1 列已给出的 scale（见 [RichTapStrength]），二是同一个效果 ID 内部的轻重
 * 次序必须和 [HapticSemantic] 的梯度一致 —— `gestureEnd` 比 `popupShow` 重一档，
 * 于是同为 `RT_SOFT_CLICK` 时前者拿 MEDIUM、后者拿 LIGHT。
 *
 * `when` 穷举、不写 `else`：以后往枚举里加语义，编译器会逼着补映射。
 */
internal fun richTapEffectFor(semantic: HapticSemantic): RichTapEffect = when (semantic) {
    // 通用点击的顶档，实心一击
    HapticSemantic.TAP -> RichTapEffect(PrebakedEffectId.RT_CLICK, RichTapStrength.FULL)
    // 次级操作，软一点的点击
    HapticSemantic.LIGHT_TAP -> RichTapEffect(PrebakedEffectId.RT_SOFT_CLICK, RichTapStrength.FULL)
    // 卡进格子的刻度感
    HapticSemantic.SEGMENT_TICK -> RichTapEffect(PrebakedEffectId.RT_TICK, RichTapStrength.FULL)
    // 一次拖动里连发几十次，单次必须极轻，取梯队最底一档
    HapticSemantic.FREQUENT_TICK -> RichTapEffect(PrebakedEffectId.RT_TICK, RichTapStrength.FEATHER)
    // 开关族里较重的一半，状态立起来
    HapticSemantic.TOGGLE_ON -> RichTapEffect(PrebakedEffectId.RT_TOGGLE_SWITCH, RichTapStrength.FULL)
    // 同一个开关效果压到一半，保住「这是一次开关」的辨识度
    HapticSemantic.TOGGLE_OFF -> RichTapEffect(PrebakedEffectId.RT_TOGGLE_SWITCH, RichTapStrength.LIGHT)
    // 成功提示型波形，本身就是最重的一笔
    HapticSemantic.CONFIRM -> RichTapEffect(PrebakedEffectId.RT_SUCCESS, RichTapStrength.FULL)
    // 失败提示型波形，重但不惊吓
    HapticSemantic.REJECT -> RichTapEffect(PrebakedEffectId.RT_FAILURE, RichTapStrength.FULL)
    // 沉的一记「抓住了」
    HapticSemantic.DRAG_START -> RichTapEffect(PrebakedEffectId.RT_LONG_PRESS, RichTapStrength.FULL)
    // 上冲感，告诉用户可以松手了
    HapticSemantic.THRESHOLD_ARMED -> RichTapEffect(PrebakedEffectId.RT_RAMP_UP, RichTapStrength.FULL)
    // 落到位的一记轻响，比 LIGHT_TAP 收一点
    HapticSemantic.GESTURE_END -> RichTapEffect(PrebakedEffectId.RT_SOFT_CLICK, RichTapStrength.MEDIUM)
    // 撞墙，且会被反复顶到，再轻一档
    HapticSemantic.SCROLL_EDGE -> RichTapEffect(PrebakedEffectId.RT_TICK, RichTapStrength.FAINT)
    // 面板落得出来，又不抢注意力
    HapticSemantic.POPUP_SHOW -> RichTapEffect(PrebakedEffectId.RT_SOFT_CLICK, RichTapStrength.LIGHT)
}

/**
 * 自己定的总时长上限。彩蛋最长的一段包络 3500 ms，30 秒宽出一个数量级；
 * 顺手挡掉把 `timingsMs` 累加成 `Int` 溢出的畸形入参。
 */
internal const val RICHTAP_ENVELOPE_MAX_DURATION_MS = 30_000

/**
 * 把契约里的逐控制点包络压成 RichTap 的四点包络；表达不了就返回 null。
 *
 * 入参语义照 `HapticBackend.playEnvelope` 与 AOSP API 36 的包络一致：曲线从 0 ms、
 * 振幅 0 起步，第 i 个控制点在 `timingsMs[i]` 毫秒内线性爬到 `amplitudes[i]`。
 *
 * 返回 null 的六种情况，都是「这一层表达不了」而不是错误，调用方据此降级：
 *
 * 1. 空数组，或两个数组长度不等；
 * 2. 有负的时长；
 * 3. 有越出 0f..1f 的振幅，**或有 NaN**。NaN 必须单独判：它跟任何数比大小都是 false，
 *    单靠 `it < 0f || it > 1f` 一路放行到底，最后插值出一段 scale 含 NaN 的包络。
 *    而 SDK 不会替我们把这种入参挡回来 —— 字节码事实（`javap` lite 版 aar 的
 *    `RichTapUtils.playEnvelope([I[F[IZI)`）：scale 校验是偏移 36 `fconst_0`、37 `fcmpg`、
 *    38 `iflt`，`fcmpg` 遇 NaN 压 1、`iflt` 不跳转，所以那句「scale can not be negative」的
 *    `IllegalArgumentException` **根本不会抛**；紧接着偏移 131 `ldc_w 100.0f`、134 `fmul`、
 *    135 `f2i` 把每个 scale 换成整数，而 `f2i(NaN)` 按 JVM 规范恒为 0。
 *    于是后果既不是崩、也不是整层 tier 3 被禁用（那两种反倒看得见），而是含 NaN 的控制点被
 *    静默压成 scale 0，播出一段控制点部分归零的畸形包络 —— 拿别的波形假装成功，
 *    正是契约禁止的那种静默失败，比抛异常更该在入口挡掉。tier 1 的 `playEnvelope`
 *    入口也拦 NaN，两层同一道闸。
 * 4. 总时长不在 [RICHTAP_ENVELOPE_MIN_DURATION_MS]..[RICHTAP_ENVELOPE_MAX_DURATION_MS] 内；
 * 5. 峰值振幅是 0 —— 整段静默，派发出去只会被兜底通路的 `max(1, amp)` 抬成一记极轻嗡鸣；
 * 6. **形状不是单峰**（升到峰再降，允许持平）。这条最要紧：四个控制点只画得出一个
 *    起—峰—落，多峰或中间归零的输入（例如彩蛋签名段那 22 个点里夹着 10 段抬笔静默）
 *    一旦被压成四点，静默会被抹平成一段连续嗡鸣。那正是契约禁止的「拿别的东西假装成功」，
 *    所以老实返回 null，让上层按笔画拆成一段段单峰包络再进来。
 */
internal fun richTapEnvelopeOf(timingsMs: IntArray, amplitudes: FloatArray): RichTapEnvelope? {
    if (timingsMs.isEmpty() || timingsMs.size != amplitudes.size) return null
    if (timingsMs.any { it < 0 }) return null
    if (amplitudes.any { it.isNaN() || it < 0f || it > 1f }) return null

    var accumulated = 0L
    val cumulative = IntArray(timingsMs.size)
    for (index in timingsMs.indices) {
        accumulated += timingsMs[index]
        if (accumulated > RICHTAP_ENVELOPE_MAX_DURATION_MS) return null
        cumulative[index] = accumulated.toInt()
    }
    val total = accumulated.toInt()
    if (total < RICHTAP_ENVELOPE_MIN_DURATION_MS) return null

    val peakIndex = peakAmplitudeIndex(amplitudes)
    if (amplitudes[peakIndex] <= 0f) return null
    if (!isSingleHumped(amplitudes, peakIndex)) return null

    val times = envelopeSampleTimes(cumulative[peakIndex], total)
    return RichTapEnvelope(
        relativeTimeMs = times,
        scales = FloatArray(RICHTAP_ENVELOPE_POINTS) { amplitudeAt(cumulative, amplitudes, times[it]) },
        frequencies = IntArray(RICHTAP_ENVELOPE_POINTS) { RICHTAP_ENVELOPE_DEFAULT_FREQUENCY },
    )
}

/** 峰值所在下标，并列取最靠前的一个。 */
private fun peakAmplitudeIndex(amplitudes: FloatArray): Int {
    var peak = 0
    for (index in 1 until amplitudes.size) {
        if (amplitudes[index] > amplitudes[peak]) peak = index
    }
    return peak
}

/**
 * 形状是否为单峰：峰前不许回落，峰后不许回升，持平都算合规。
 *
 * 曲线起点固定在振幅 0，所以从 0 爬到 `amplitudes[0]` 这一段天然是上升的，不必单独判。
 */
private fun isSingleHumped(amplitudes: FloatArray, peakIndex: Int): Boolean {
    for (index in 1..peakIndex) {
        if (amplitudes[index] < amplitudes[index - 1]) return false
    }
    for (index in peakIndex + 1 until amplitudes.size) {
        if (amplitudes[index] > amplitudes[index - 1]) return false
    }
    return true
}

/**
 * 挑四个采样时刻，保证严格递增（首项 0、末项 [total]）。
 *
 * 峰在中间时第二点直接踩在峰上，第三点取峰与末尾的中点，落段才有形状。
 * 峰在末尾（单调上升的包络）时第三点贴到 `total - 1`：SDK 的两条兜底通路都拿
 * `max(scales[1], scales[2])` 当整段峰值，第三点离末尾越近，退化后的强度越不失真。
 *
 * 调用前已保证 [total] ≥ [RICHTAP_ENVELOPE_MIN_DURATION_MS]，所以两次 `coerceIn` 的区间非空。
 */
private fun envelopeSampleTimes(peakAtMs: Int, total: Int): IntArray {
    val peakInside = peakAtMs in 1 until total
    val second = (if (peakInside) peakAtMs else total / 2).coerceIn(1, total - 2)
    val third = (if (peakInside) (peakAtMs + total) / 2 else total - 1)
        .coerceIn(second + 1, total - 1)
    return intArrayOf(0, second, third, total)
}

/**
 * 在分段线性曲线上取 [atMs] 时刻的振幅。曲线从 0 ms、振幅 0 起步，
 * 第 i 段在 `cumulative[i]` 时刻到达 `amplitudes[i]`。
 */
private fun amplitudeAt(cumulative: IntArray, amplitudes: FloatArray, atMs: Int): Float {
    var previousTime = 0
    var previousValue = 0f
    for (index in cumulative.indices) {
        val time = cumulative[index]
        val value = amplitudes[index]
        if (atMs <= time) {
            val span = time - previousTime
            if (span <= 0) return value
            return previousValue + (value - previousValue) * (atMs - previousTime) / span.toFloat()
        }
        previousTime = time
        previousValue = value
    }
    return amplitudes[amplitudes.size - 1]
}
