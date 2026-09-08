package com.tracktosearch.ui.haptic

import android.content.Context
import android.provider.Settings
import android.util.Log
import android.view.View
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.StateFlow

/**
 * 触感语义门面：调用侧只声明「这是什么交互」，挑层、降级、派发都归本类。
 *
 * 构造期把传进来的 backend 排成两条降级链，之后每次派发只沿链走一遍
 * `isAvailable()` 与 `supports()` 与 `perform()` —— 全是查表加一次 `Executor.execute`，
 * 所以语义方法可以直接落在主线程的点击回调里并立刻返回。
 * 本类**自己不建线程**：反射、厂商 IPC、`Vibrator` 调用这些阻塞动作都在各 backend
 * 自己那条单线程 `Executor` 上，本类只负责决定谁上场。
 *
 * 13 个语义方法与 [perform] 请在主线程调：tier 0 直接碰 View，
 * 而 View 的状态不该在别的线程上摸。
 *
 * ### 两条链，不是一条
 *
 * 设计文档要求「同一台机上 RichTap 与厂商通路都可用时，离散语义走厂商预置效果，
 * 彩蛋那两段连续包络走 RichTap」，所以单一的 tier 降序不够用，这里排了两条：
 *
 * - [perform] 走离散链：tier 2 排在 tier 3 前面（见 [discreteRank]），
 *   厂商自己调过的预置效果优先，tier 2 不可用时才轮到 RichTap。
 * - [playEnvelope] 走包络链：tier 降序，但**有厂商预置效果（tier 2）可用时跳过 tier 1**
 *   （真机反馈定的规矩，理由见 [playEnvelope]）。tier 2 与 tier 0 对连续包络恒返 false，
 *   所以实际落点只可能是 tier 3、tier 1，或「整体返 false 让调用方退离散替身」。
 *
 * ### 三态开关
 *
 * 档位从 [modeState] 同步读，每次派发读一次：
 *
 * - [HapticMode.FOLLOW_SYSTEM]：正常走链。
 * - [HapticMode.OFF]：整体短路，一次都不发；进入 OFF 之后的第一次调用还会调一次
 *   [quietDown]，把已经排出去的连续波形停掉。
 * - [HapticMode.BOOST]：派发 [HapticSemantic.boosted] 的结果，只上移一档。
 *   **不乘任何系数**（系统的 `VibrationScaler` 已按用户档位乘过 0.6 到 1.4），
 *   **也不绕过系统总开关**。
 *
 * 系统总开关有两道，一道都不许绕（红线：禁止 `FLAG_IGNORE_GLOBAL_SETTING`）：
 * [systemHapticEnabled] 这个注入的判定，以及传进来的 View 自己的 `isHapticFeedbackEnabled`。
 * tier 0 走 `View.performHapticFeedback`，系统本来会替我们判一遍；tier 1 标了
 * `USAGE_TOUCH`，也归系统缩放；但 tier 2 与 tier 3 走的是厂商 IPC 与 RichTap 自己的通路，
 * 那两道判定在那条路上根本不存在 —— 所以必须在这里判。
 *
 * ### 构造成本与预热
 *
 * 本类的构造是廉价的，贵的是它的依赖：`HapticCapabilities.probe` 里有 `getSystemService`、
 * 到 `VibratorService` 的 IPC 与三次类查找，是阻塞调用。所以**第一次拿到本类实例这件事
 * 必须发生在非主线程**（注入 `Provider<AppHaptics>` 或 `Lazy<AppHaptics>`，
 * 在 IO 上 `get()` 一次预热），详见 `com.tracktosearch.di.HapticModule` 的说明。
 * 本类刻意不自己包一层懒初始化：那只会把阻塞从「启动时的某个后台线程」挪到
 * 「用户第一次点按钮的主线程」，更糟。
 *
 * 构造期还顺手做两件事，按代码里的实际先后：先给 backend 焐热 —— 对每层调一次
 * `isAvailable()`，MIUI 与 OPlus 两层靠这一下把逐 ID 探测甩上它们自己的线程，不焐热的话
 * 第一次点击会掉到下一层去；再调一次 [systemHapticEnabled] 把 `Settings` 的进程内缓存填上。
 *
 * 焐热**不是无条件**的：构造期挑可用层的那个 `when` 有两条短路分支，各自跳过一部分。
 * 这是有意的 —— 焐热本身只是省资源的优化，在发不出细腻触感的机器上焐 tier 1 以上毫无意义。
 *
 * - `hasVibrator` 为 false：两条链直接给空表，**一层都不焐**，连 `isAvailable()` 都不调。
 * - `lockedToConstants` 为 true（转子马达）：过滤条件是 `tier == TIER_CONSTANTS &&
 *   isAvailable()`，`&&` 在左边就短路了，所以**只有 tier 0 那层被焐热**，tier 1 以上一次都
 *   不问 —— 反正它们本来也不许上场（硬规则 1）。
 *
 * 两种情况都无害：被跳过的层要么整机用不上，要么已被降级链摘掉。`AppHapticsDegradationTest`
 * 里两条用例分别钉住这两个分支的「零调用」，所以别为了对上文档反过来补一轮无条件焐热。
 *
 * ### 与设备能力的关系
 *
 * 三条硬规则里有两条落在这里。`hasVibrator` 为 false 时两条链都是空的 —— tier 0 判不出
 * 有没有马达（无马达设备上 `performHapticFeedback` 照样返回 true），只能由引擎短路；
 * `lockedToConstants` 为 true（转子马达）时只留 tier 0。第三条（Composition 整组探测）
 * 归 tier 1 自己。
 *
 * 依赖一条不变式：五个 backend 的 `isAvailable()` 都是单向 true 到 false，
 * 没有「先 false 后来又 true」的，所以构造期过滤掉的永远不会复活。
 * 新增 backend 必须保持这条，否则那一层会被永久摘掉。
 *
 * 设计依据见 docs/superpowers/plans/2026-09-01-haptics-overhaul.md 的
 * 「四层引擎」「语义词表」「厂商通路矩阵」「三态开关」四节。
 *
 * @param capabilities 设备能力快照，只在构造期读，本类不持有它。
 * @param backends 全部 backend。顺序只用来给同 tier 的打破平手（两次排序都是稳定的）；
 *   [release] 会逐个关，包括被降级链过滤掉的那些 —— 它们也持有线程。
 * @param modeState 三态开关，同步读 `value`。触感判定落在每次点击的主线程路径上，
 *   等一次 Flow 收集就迟了。
 * @param systemHapticEnabled 「系统触感总开关是不是开着」。注入而不是在类里直接读
 *   `Settings.System`，单测才能把两种取值各钉一遍；生产实现见 [systemHapticFeedbackEnabled]。
 *   **要求不抛异常**，构造期与每次派发都会调它。
 * @param quietDown 把已排出去的连续波形停掉。只在进入 [HapticMode.OFF] 后的第一次调用
 *   与 [stopOngoing] 里触发，默认什么都不做。
 * @param onMiss 整条链都没接下这次派发时的观察点，默认写一行 `Log.d`
 *   （release 由 ProGuard 的 `-assumenosideeffects` 剥掉）。**单测请传自己的实现**：
 *   默认实现碰 `android.util.Log`，纯 JVM 单测里会抛「not mocked」。
 */
class AppHaptics(
    capabilities: HapticCapabilities,
    backends: List<HapticBackend>,
    private val modeState: StateFlow<HapticMode>,
    private val systemHapticEnabled: () -> Boolean,
    private val quietDown: () -> Unit = {},
    private val onMiss: (HapticSemantic) -> Unit = ::logHapticMiss,
) {
    /** 全部 backend。[release] 要逐个关 —— 被降级链过滤掉的那几层也各自持有一条线程 */
    private val allBackends: List<HapticBackend> = backends.toList()

    /** 离散语义的降级链，tier 2 优先（见 [discreteRank]）。构造期定好，之后不再变 */
    private val discreteChain: List<HapticBackend>

    /** 连续包络的降级链，tier 降序。实际落点只会是 tier 3 或 tier 1 */
    private val envelopeChain: List<HapticBackend>

    /** [release] 之后一切返回 false。可能在非主线程置位而主线程读，故 volatile */
    @Volatile
    private var released = false

    /**
     * OFF 档的静音闸：进入 OFF 之后只调一次 [quietDown]，离开 OFF 时重新武装。
     *
     * 不闩这一下的话，一次滑块拖动里几十记 `frequentTick` 会往 RichTap 那条单线程上
     * 塞几十个停止任务。
     */
    private val silenced = AtomicBoolean(false)

    init {
        val usable: List<HapticBackend> = when {
            // 没有马达：哪一层都发不出东西。tier 0 自己判不出来 ——
            // 无马达设备上 performHapticFeedback 照样返回 true，只能在这里短路。
            !capabilities.hasVibrator -> emptyList()
            // 硬规则 1：转子马达没有振幅控制，非零振幅会被抬到 100%，只许 tier 0 上场。
            // tier 1 以上的 isAvailable() 自己也会返 false，这里再过一遍是刻意的双保险。
            capabilities.lockedToConstants ->
                allBackends.filter { it.tier == TIER_CONSTANTS && it.isAvailable() }
            else -> allBackends.filter { it.isAvailable() }
        }
        discreteChain = usable.sortedByDescending { discreteRank(it.tier) }
        envelopeChain = usable.sortedByDescending { it.tier }
        // 预热 Settings 的进程内缓存：首次读要走一次 binder，别留给第一次点击
        systemHapticEnabled()
    }

    // ------------------------------------------------------------------------
    // 13 个语义。场景与轻重次序的理由全在 HapticSemantic 的 KDoc 里，这里只留一句提示。
    // ------------------------------------------------------------------------

    /** 普通按钮、可点卡片。通用点击里最重的一档 */
    fun tap(view: View?): Boolean = perform(view, HapticSemantic.TAP)

    /** 列表项、次级操作。比 [tap] 轻一档 */
    fun lightTap(view: View?): Boolean = perform(view, HapticSemantic.LIGHT_TAP)

    /** Tab 切换、单选、图表选中、分段控件。有卡进格子的刻度感 */
    fun segmentTick(view: View?): Boolean = perform(view, HapticSemantic.SEGMENT_TICK)

    /** 滑块连续拖动、逐珠划过。一次手势里连发几十次，单次极轻 */
    fun frequentTick(view: View?): Boolean = perform(view, HapticSemantic.FREQUENT_TICK)

    /** 开关打开、chip 选中、展开 */
    fun toggleOn(view: View?): Boolean = perform(view, HapticSemantic.TOGGLE_ON)

    /** 开关关闭、chip 取消、收起。比 [toggleOn] 轻 */
    fun toggleOff(view: View?): Boolean = perform(view, HapticSemantic.TOGGLE_OFF)

    /** 提交成功、标记成功、登录成功 */
    fun confirm(view: View?): Boolean = perform(view, HapticSemantic.CONFIRM)

    /** 操作失败、校验不通过 */
    fun reject(view: View?): Boolean = perform(view, HapticSemantic.REJECT)

    /** 拖拽起手、长按进入拖动。沉的一记「抓住了」 */
    fun dragStart(view: View?): Boolean = perform(view, HapticSemantic.DRAG_START)

    /** 下拉刷新到达阈值、侧滑到阈值。告诉用户可以松手了 */
    fun thresholdArmed(view: View?): Boolean = perform(view, HapticSemantic.THRESHOLD_ARMED)

    /** 手势翻页落定、面板吸附 */
    fun gestureEnd(view: View?): Boolean = perform(view, HapticSemantic.GESTURE_END)

    /** 列表滚到尽头、滑条到边界。会被反复顶到，所以轻 */
    fun scrollEdge(view: View?): Boolean = perform(view, HapticSemantic.SCROLL_EDGE)

    /** 弹窗、底部面板出现 */
    fun popupShow(view: View?): Boolean = perform(view, HapticSemantic.POPUP_SHOW)

    /**
     * 按语义派发一次离散触感，沿离散链从高优先级往低走。
     *
     * 13 个具名方法都只是本方法的糖；直接调它的是那些手里只有一个 [HapticSemantic] 值的
     * 调用方（彩蛋编排、以后的 `Modifier` 扩展）。
     *
     * 降级规则：某一层 `supports()` 为 true 但 `perform()` 返 false 时**继续往下降级**，
     * 不是就此放弃 —— 厂商层的单线程队列被拒、tier 1 的 `Vibrator` 中途失效都属这一类。
     * 整条链都没接下就整次调用无声，只留一行 [onMiss]，不抛异常。
     *
     * **View 的触感开关不在降级之列**，它在下面第 3 行就把整次调用挡掉了：那道开关是用户设置，
     * 不是某一层的能力问题，绕开它去试厂商层就等于无视用户。所以
     * `AospConstantsBackend.perform` 里那句同样的 `isHapticFeedbackEnabled` 判定，
     * 经本方法进来时永远为真 —— 它挡的是有人绕过本类直接驱动 tier 0 的情况（单测就是这么做的）。
     *
     * @param view tier 0 靠它走 `View.performHapticFeedback`，其余层忽略。
     *   传 null 等于把 tier 0 摘掉，而转子马达机型只剩 tier 0 —— 那种机器上传 null
     *   就是整个 App 一点触感都没有。所以这个参数刻意不给默认值，
     *   Compose 侧请从 `LocalView.current` 取一个传进来。
     * @return true 已有一层接下（**不表示马达已经震完**：厂商层与 tier 1 都是投到自己的
     *   单线程上之后就返回的）；false 本次无声
     */
    fun perform(view: View?, semantic: HapticSemantic): Boolean {
        if (released) return false
        val effective = effectiveSemantic(semantic) ?: return false
        // View 自己的触感开关也是用户设置的一部分，关着就不震
        if (view != null && !view.isHapticFeedbackEnabled) return false
        for (backend in discreteChain) {
            if (backend.isAvailable() &&
                backend.supports(effective) &&
                backend.perform(view, effective)
            ) {
                return true
            }
        }
        onMiss(effective)
        return false
    }

    /**
     * 播一段连续振幅包络（彩蛋用），沿包络链按 tier 降序找第一个接下的 ——
     * 只有一条例外：**有厂商预置效果（tier 2）可用的机器上跳过 tier 1**。
     *
     * 这条例外是真机反馈定的（小米 14 Pro / HyperOS，2026-09-08）：tier 1 的
     * `createWaveform` 走的是无过驱动、无制动的通用驱动路径，线性马达机型上体感是
     * 转子式的「普通震动」，不是马达调过的手感。与其播一段糟蹋彩蛋的嗡鸣，宁可整段
     * 返 false —— 调用方的谱子里每段包络都排好了离散替身，替身沿 [perform] 的离散链
     * 走，正落在厂商调过的预置效果上。tier 3 的两条波形通路（RichTap SDK 与 MiHaptic
     * HE）接得下的机器不受影响；没有 tier 2 的机器（Pixel 等）也仍由 tier 1 兜底。
     *
     * 「厂商层可用」按调用时的 [HapticBackend.isAvailable] 判而不是构造期定死，且是
     * **惰性查** —— 只有真的轮到 tier 1 才去问厂商层：tier 2 在运行中因派发失败整层
     * 禁用后，tier 1 立刻恢复兜底，包络不会两头落空；tier 3 接得住的机器一次都不多问。
     *
     * 返回 false 是**要处理的结果**而不是错误：调用方据此退成「每段起点一记 tick」的
     * 稀疏编排。正因为 false 是有人接住的信号，这里不像 [perform] 那样记 [onMiss]。
     *
     * [HapticMode.BOOST] 对本方法没有影响：包络的振幅是调用方给的曲线，
     * 「增强」只上移语义档位，不乘系数。
     *
     * @param timingsMs 每个控制点的持续毫秒
     * @param amplitudes 每个控制点的目标振幅 0f..1f，与 [timingsMs] 等长。
     *   长度不等或振幅越界由各 backend 自己判成 false，本方法不抛异常
     * @return true 已有一层接下；false 本机播不了这段包络（或本机有更好的离散替身可退）
     */
    fun playEnvelope(timingsMs: IntArray, amplitudes: FloatArray): Boolean {
        if (released) return false
        if (allowedMode() == null) return false
        for (backend in envelopeChain) {
            // 厂商预置效果可用时跳过 tier 1（策略理由见上）。**惰性查**：只有真的轮到
            // tier 1 才去问厂商层 —— tier 3 接得住的机器一次都不多问，流水与开销都干净
            if (backend.tier == TIER_WAVEFORM &&
                envelopeChain.any { it.tier == TIER_VENDOR && it.isAvailable() }
            ) {
                continue
            }
            if (backend.isAvailable() && backend.playEnvelope(timingsMs, amplitudes)) return true
        }
        return false
    }

    /**
     * 立刻停掉已经排出去的连续波形。
     *
     * `HapticBackend` 契约里没有中断通道，而 [playEnvelope] 一段能长到几秒 —— 彩蛋要跟随
     * `SwiftieMusic` 的三条闸门（`paused`、`ON_STOP`、`AUDIOFOCUS_LOSS`）时得停得下来，
     * 所以门面这边留一个出口，免得调用方绕过门面直接去摸某个具体 backend。
     *
     * 实际能停掉多少取决于注入的 [quietDown]，默认什么都不做。生产装配（`HapticModule`）
     * 把 tier 3 与 tier 1 两层的停止一起包进去：`RichTapBackend.stop()` 在从未 init 成功的
     * 机型上会直接返回，那时真正在播的是 tier 1 那段波形，得靠
     * `AospWaveformBackend.cancel()` 才停得住。
     *
     * **刻意不看三态开关，也不看系统总开关。** 「停」永远该生效 —— 用户把档位拨到
     * [HapticMode.OFF] 的那一刻，正在播的那段更应该立刻断掉，而不是等它播完。
     */
    fun stopOngoing() {
        if (released) return
        quietDown()
    }

    /**
     * 逐个 release backend —— 每层都要 shutdown 自己那条单线程，漏一个就是线程泄漏。
     *
     * 幂等；之后 [perform] 与 [playEnvelope] 恒返 false。进程级单例正常不需要调，
     * 留给单测与将来可能收窄的作用域。
     *
     * 逐个包一层：契约要求 `release` 不抛，但真抛出来不该拖住其余几层的回收。
     */
    fun release() {
        if (released) return
        released = true
        for (backend in allBackends) {
            runCatching { backend.release() }
        }
    }

    /**
     * 这一次到底允不允许派发；允许就返回当前档位。
     *
     * 顺带管 OFF 档那道静音闸：进入 OFF 的第一次调用停一次已排出去的波形，之后不再重复；
     * 离开 OFF 时重新武装。之所以是「下一次派发时才停」而不是订阅 [modeState] ——
     * 本类没有协程作用域也不该有，而切到 OFF 之后调用方照样会调进来（短路在引擎内部），
     * 所以这个惰性边沿足够用。
     *
     * `when` 穷举、不写 `else`：往 [HapticMode] 加档位时编译器会逼着表态。
     */
    private fun allowedMode(): HapticMode? = when (val mode = modeState.value) {
        HapticMode.OFF -> {
            if (silenced.compareAndSet(false, true)) quietDown()
            null
        }
        HapticMode.FOLLOW_SYSTEM, HapticMode.BOOST -> {
            silenced.set(false)
            if (systemHapticEnabled()) mode else null
        }
    }

    /**
     * 按档位算出真正要派发的语义；返回 null 表示这次不该震。
     *
     * 「增强」只调**一次** [HapticSemantic.boosted]：连着调会把滑块拖动的 `FREQUENT_TICK`
     * 一路顶成 `TAP`，一次拖动几十记实心点击。
     */
    private fun effectiveSemantic(semantic: HapticSemantic): HapticSemantic? =
        when (allowedMode()) {
            HapticMode.BOOST -> semantic.boosted()
            HapticMode.FOLLOW_SYSTEM -> semantic
            // allowedMode() 不会返回 OFF；和 null 并在一起只为让 when 保持穷举
            HapticMode.OFF, null -> null
        }

    private companion object {
        /** tier 0：AOSP 常量层。转子马达锁在这一层（硬规则 1） */
        const val TIER_CONSTANTS = 0
    }
}

// ============================================================================
// 以下三个是本文件可单测的部分。前两个纯输入输出，第三个只碰 Settings，可上 Robolectric。
// ============================================================================

/** tier 2：厂商语义效果层（MIUI 与 OPlus） */
private const val TIER_VENDOR = 2

/** tier 1：AOSP 振幅波形层。包络链在厂商层可用时会跳过它，见 [AppHaptics.playEnvelope] */
private const val TIER_WAVEFORM = 1

/** 换位后 tier 2 的排序权重。只需大于最高的真实 tier（3），取 4 */
private const val DISCRETE_RANK_VENDOR = 4

/** [logHapticMiss] 的日志 tag */
private const val LOG_TAG = "AppHaptics"

/**
 * 离散语义的排序权重：把厂商预置效果（tier 2）排到 RichTap（tier 3）前面。
 *
 * 其余 tier 原样返回，于是离散链的次序是 tier 2、3、1、0，**不是**单纯的 tier 降序；
 * [AppHaptics.playEnvelope] 那条包络链才是老实的 3、2、1、0。只有两条通路同时可用时这个
 * 换位才改变结果，所以写成静态权重而不是运行期条件判断。
 *
 * ### 依据与出处
 *
 * docs/superpowers/plans/2026-09-01-haptics-overhaul.md 的「厂商通路矩阵」末段那条裁定，原文：
 *
 * > 同一台机上两条都可用时（例如支持 RichTap 的 OPPO 机型），离散语义走 OPlus 的预置效果
 * > —— 厂商自己调过的手感比我们拼的波形好；彩蛋那两段连续包络走 RichTap `playEnvelope`。
 *
 * 即：tier 2 的效果 ID 是厂商自己调过音的，手感好过我们在 tier 3 上拼的波形；而 tier 3 是
 * 唯一画得出连续包络的一层，那份表达力留给彩蛋。同一条规则的另一半写在 `HapticBackend.tier`
 * 的 KDoc 里，两处措辞一致，改一处就得改另一处。
 *
 * **要把这里改回 tier 降序，先读上面那段。** 单纯的 tier 降序读起来完全合理，代价是目标机
 * （RichTap 与厂商通路都可用的机型）上整片离散手感被无声换掉 —— 不崩不报错，只是手感变了。
 * `AppHapticsDegradationTest` 里有用例钉着这个次序，改成恒等函数会立刻判红。
 */
internal fun discreteRank(tier: Int): Int =
    if (tier == TIER_VENDOR) DISCRETE_RANK_VENDOR else tier

/**
 * `AppHaptics` 的 `onMiss` 默认实现：整条链都没接下时写一行。
 *
 * 用 `Log.d` 是因为 `proguard-rules.pro` 里对 `Log.d` 声明了 `-assumenosideeffects`，
 * release 构建会把整个调用剥掉，线上不留日志。触感层不做任何上报。
 */
internal fun logHapticMiss(semantic: HapticSemantic) {
    Log.d(LOG_TAG, "no backend handled $semantic")
}

/** `Settings.System` 这两个键的「开」 */
private const val SETTING_ON = 1

/** 同上，「关」 */
private const val SETTING_OFF = 0

/**
 * 「系统触感总开关是不是开着」的生产实现，`AppHaptics` 的 `systemHapticEnabled` 参数传它。
 *
 * 查两个 `Settings.System`，任一为 0 就不震：
 *
 * - `VIBRATE_ON`：系统设置里那个振动总开关，没被废弃。
 * - `HAPTIC_FEEDBACK_ENABLED`：触摸反馈开关。android-37 的 SDK 里标了废弃，但公开 SDK
 *   没给替代品（`Vibrator.getVibrationIntensity` 是 hide），而 AOSP 的 `VibrationSettings`
 *   至今仍把它折进 `USAGE_TOUCH` 的强度里，所以它仍是唯一可读的判据。
 *
 * 设计文档只点了 `HAPTIC_FEEDBACK_ENABLED` 一条，这里多查一条 `VIBRATE_ON` 是有意的：
 * tier 0 与 tier 1 的下发路径上系统会自己判这两条，而 tier 2 与 tier 3 走厂商 IPC，
 * 不判就等于用户把振动全关了我们还在震 —— 那是红线。
 *
 * 读不到时**放行**（返回 true）：个别 ROM 可能没有这两个键，因为读不出来就把整个 App 的
 * 触感判死更糟，而 tier 0 与 tier 1 那两层系统仍然会自己拦。
 *
 * 每次派发都会调一次。`Settings.System.getInt` 首次要走一次 binder，之后由
 * `NameValueCache` 的 generation tracker 挡住，是进程内的内存读，可以留在主线程路径上；
 * 首次那一下由 `AppHaptics` 构造期预热掉。
 *
 * 不缓存返回值：用户在系统设置里改了开关，下一次点击就该生效。
 */
@Suppress("DEPRECATION")
internal fun systemHapticFeedbackEnabled(context: Context): Boolean = try {
    val resolver = context.contentResolver
    val vibrateOn = Settings.System.getInt(resolver, Settings.System.VIBRATE_ON, SETTING_ON)
    val touchFeedback =
        Settings.System.getInt(resolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, SETTING_ON)
    vibrateOn != SETTING_OFF && touchFeedback != SETTING_OFF
} catch (_: Throwable) {
    true
}
