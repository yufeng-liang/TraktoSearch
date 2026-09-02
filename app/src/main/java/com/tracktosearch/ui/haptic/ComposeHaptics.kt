package com.tracktosearch.ui.haptic

import androidx.compose.foundation.Indication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import android.view.View
import javax.inject.Provider

/**
 * 把 [AppHaptics] 递进 Compose 树。**在 `MainActivity` 里 provide 一次**，位置要在
 * `AppNavigation` 之上 —— `CrashReportDialogHost` 与 `SplashQuoteOverlay` 是 `AppNavigation`
 * 的兄弟节点，provide 在导航内部它们就读不到。
 *
 * 类型是 `Provider<AppHaptics>?` 而不是 `AppHaptics?`，两层各有原因：
 *
 * - `Provider`：解析 `AppHaptics` 会连带跑完 `HapticCapabilities.probe`（`getSystemService`、
 *   到 `VibratorService` 的 IPC、三次类查找），是阻塞调用，见 `HapticModule` 的类注释。
 *   provide 一个 `Provider` 等于只把「怎么拿」递下去，真正 `get()` 由
 *   `TraktSearchApp` 的启动预热在后台线程先做掉。
 * - 可空且默认 null：`@Preview` 与那三十来个不建 Hilt 图的 androidTest 屏幕测试都拿不到这个绑定。
 *   默认给 null，[ComposeHaptics] 整体退成空实现，那些场景照跑，不必为触感各补一份 provide。
 *
 * `staticCompositionLocalOf`：整个进程只 provide 一次、之后永不变，用静态版读取不建订阅。
 */
val LocalAppHaptics: ProvidableCompositionLocal<Provider<AppHaptics>?> =
    staticCompositionLocalOf { null }

/**
 * Compose 侧的触感入口，由 [rememberAppHaptics] 取。13 个语义与 [AppHaptics] 一一对应，
 * 但签名刻意不同 —— 这三处差异就是本类存在的全部理由：
 *
 * 1. **不收 `View`。** `View` 在构造期从 `LocalView.current` 捕获，调用侧不用管。
 *    忘了传 `View` 在转子马达机型上等于整机无触感（那种机器只剩 tier 0），
 *    与其在每个调用点提醒，不如从签名里删掉。
 * 2. **返回 `Unit`。** [AppHaptics.perform] 的 `false` 有四种成因（引擎已 release、
 *    档位为关、系统总开关关、整条链都没接下），调用侧分辨不出，也没有一种是它该处理的。
 *    留着 `Boolean` 只会诱人写 `if (!tap()) { ... }`。
 * 3. **[frequentTick] 自带节流**，见那个方法。
 *
 * ### 只在主线程用
 *
 * tier 0 走 `View.performHapticFeedback`，碰的是 `View` 状态。Compose 的点击、手势、
 * `onValueChange` 回调本来都在主线程上，所以正常用法天然满足；本类内部那两处可变状态
 * （引擎缓存与节流时间戳）也因此不加锁。别从协程的 IO 调度器上调进来。
 *
 * ### 彩蛋不走本类
 *
 * T6 的连续包络编排要 `playEnvelope` 与 `stopOngoing`，本类刻意不转发这两个 ——
 * 它们是「一段几秒的波形」而不是「一次交互反馈」，节流与 `View` 捕获对它们都没意义。
 * 那边直接注入 `Provider<AppHaptics>`。
 *
 * @param view 构造期捕获的宿主 `View`，只交给 tier 0 用。
 * @param provider 触感引擎的惰性入口；null 表示本次组合树里没人 provide
 *   [LocalAppHaptics]（预览、屏幕测试），此时本类所有方法都是空操作。
 * @param nanoTime 单调时钟，只给 [frequentTick] 的节流用。注入而不是直接调
 *   `System.nanoTime()`，是为了让节流能被钉住 —— 靠 `Thread.sleep` 测节流必然是 flaky 的。
 *   与 `AppHaptics` 把 `systemHapticEnabled` 注进来同一个理由。
 */
@Stable
class ComposeHaptics internal constructor(
    private val view: View,
    private val provider: Provider<AppHaptics>?,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    /** 解析成功后缓存的引擎。只在主线程读写，故不加 volatile */
    private var engine: AppHaptics? = null

    /** 解析失败过一次就不再试。Hilt 图坏了不是一次点击能修的，每次点击重试只是重复抛异常 */
    private var resolveFailed = false

    /**
     * 上一次 [frequentTick] 真正发出去的时刻。
     *
     * 初值刻意是「构造时刻往前推一个间隔」而不是 0：[nanoTime] 的原点是任意的，
     * 拿 0 当哨兵要额外判一次，而这样写第一次调用一定能过闸。
     */
    private var lastFrequentTickNanos = nanoTime() - FREQUENT_TICK_MIN_GAP_NANOS

    /**
     * 拿引擎，拿不到返回 null。
     *
     * `runCatching`：正常情况下 `Provider.get()` 只是读一个已经预热好的 `@Singleton`，
     * 但真要是 Hilt 图出了问题，不该由「用户点了个按钮」这件事把进程带走 —— 触感是装饰，
     * 缺了它这次点击的业务逻辑照样得跑完。
     */
    private fun engine(): AppHaptics? {
        engine?.let { return it }
        if (resolveFailed) return null
        val resolved = runCatching { provider?.get() }.getOrNull()
        if (resolved == null) resolveFailed = true else engine = resolved
        return resolved
    }

    // --------------------------------------------------------------------
    // 13 个语义。场景与轻重次序的理由在 HapticSemantic 的 KDoc 里，这里不重复。
    // --------------------------------------------------------------------

    /** 普通按钮、可点卡片 */
    fun tap() = perform(HapticSemantic.TAP)

    /** 列表项、次级操作 */
    fun lightTap() = perform(HapticSemantic.LIGHT_TAP)

    /** Tab 切换、单选、图表选中、分段控件 */
    fun segmentTick() = perform(HapticSemantic.SEGMENT_TICK)

    /** 开关打开、chip 选中、展开 */
    fun toggleOn() = perform(HapticSemantic.TOGGLE_ON)

    /** 开关关闭、chip 取消、收起 */
    fun toggleOff() = perform(HapticSemantic.TOGGLE_OFF)

    /** 开关族的糖：按新状态挑 [toggleOn] 或 [toggleOff] */
    fun toggle(checked: Boolean) = if (checked) toggleOn() else toggleOff()

    /** 提交成功、标记成功、登录成功 */
    fun confirm() = perform(HapticSemantic.CONFIRM)

    /** 操作失败、校验不通过 */
    fun reject() = perform(HapticSemantic.REJECT)

    /** 拖拽起手、长按进入拖动 */
    fun dragStart() = perform(HapticSemantic.DRAG_START)

    /** 下拉刷新到达阈值、侧滑到阈值 */
    fun thresholdArmed() = perform(HapticSemantic.THRESHOLD_ARMED)

    /** 手势翻页落定、面板吸附 */
    fun gestureEnd() = perform(HapticSemantic.GESTURE_END)

    /** 列表滚到尽头、滑条到边界 */
    fun scrollEdge() = perform(HapticSemantic.SCROLL_EDGE)

    /** 弹窗、底部面板出现 */
    fun popupShow() = perform(HapticSemantic.POPUP_SHOW)

    /**
     * 滑块连续拖动、逐珠划过。**本方法自带节流，间隔不足 [FREQUENT_TICK_MIN_GAP_MS] 的直接丢掉。**
     *
     * 为什么必须节流：一次滑块拖动会在几百毫秒里回调几十次，而每次派发都要读两个
     * `Settings.System`（见 [systemHapticFeedbackEnabled]）并沿降级链走一遍 `isAvailable()`。
     * 更要紧的是体感 —— 快到一定程度马达来不及回位，几十记极轻的 tick 会糊成一段持续嗡鸣，
     * 比不震更难受。40 ms 封顶 25 Hz，是「颗粒仍分得清」与「不糊」之间的折中。
     *
     * 节流放在本层而不是 [AppHaptics]，是因为 T6 的彩蛋编排要直接驱动引擎按乐句发密集
     * tick —— 那是设计好的节奏，被引擎悄悄吞掉就不成谱子了。这一层只管交互反馈。
     *
     * 节流窗口跟着 [ComposeHaptics] 实例走，也就是跟着 [rememberAppHaptics] 的调用点走。
     * 同一个 composable 里放两个滑块会共用一个窗口；真遇到这种布局，各自
     * `rememberAppHaptics()` 一份即可。
     */
    fun frequentTick() {
        val now = nanoTime()
        if (now - lastFrequentTickNanos < FREQUENT_TICK_MIN_GAP_NANOS) return
        lastFrequentTickNanos = now
        perform(HapticSemantic.FREQUENT_TICK)
    }

    /**
     * 按语义派发一次，13 个具名方法都是它的糖。
     *
     * 直接调它的是那些手里只有一个 [HapticSemantic] 值的调用方 —— 主要是
     * [hapticClickable] 这类把语义当参数收的 `Modifier` 扩展。
     *
     * 不返回是否成功，理由见类注释第 2 条。
     *
     * 注意本方法**不过 [frequentTick] 那道节流**：传 [HapticSemantic.FREQUENT_TICK] 进来
     * 是原样派发的。连发场景请调 [frequentTick]。
     */
    fun perform(semantic: HapticSemantic) {
        engine()?.perform(view, semantic)
    }

    private companion object {
        /** [frequentTick] 两记之间的最小间隔，25 Hz 封顶 */
        const val FREQUENT_TICK_MIN_GAP_MS = 40L

        /** 同上，纳秒。[System.nanoTime] 的差值直接和它比 */
        const val FREQUENT_TICK_MIN_GAP_NANOS = FREQUENT_TICK_MIN_GAP_MS * 1_000_000L
    }
}

/**
 * 取当前组合树的 [ComposeHaptics]。
 *
 * 给两类调用方用：
 *
 * 1. **Material 组件的 `onClick=` 参数。** `Button`、`IconButton`、`Switch`、`Card` 这些把
 *    点击收成构造参数，`Modifier` 扩展拦不到，只能在 lambda 里自己发一记：
 *    `onClick = { haptics.tap(); onClick() }`。
 * 2. **非点击的交互。** 滑块拖动、到达阈值、滚到边界、面板吸附 —— 这些根本没有 `clickable`
 *    可挂，只有 [ComposeHaptics] 这条路。
 *
 * 纯 `Modifier.clickable` 的场景请直接用 [hapticClickable]，别自己 remember 再拼。
 *
 * 按 `LocalView` 与 [LocalAppHaptics] 两者 remember：前者跨对话框会变（`Dialog` 有自己的
 * 宿主 `View`），换了宿主必须重新捕获，否则触感发到已经 detach 的 `View` 上。
 */
@Composable
fun rememberAppHaptics(): ComposeHaptics {
    val view = LocalView.current
    val provider = LocalAppHaptics.current
    return remember(view, provider) { ComposeHaptics(view, provider) }
}

// ============================================================================
// Modifier 扩展。三个函数的签名是各自对应的 Compose 原函数的**严格超集**：参数名、
// 顺序、默认值逐字照抄，只多一个 `semantic`（外加长按那个 `longPressSemantic`）。
//
// 这条超集约束是为了迁移：现存调用点改造时只需把 `clickable` 换成 `hapticClickable`
// 并补一行 `semantic = ...`，参数列表原样不动，不必逐点重新判断 indication 该怎么传。
// 往这几个函数上加参数时请守住这条。
//
// 没有「短形式」的 hapticCombinedClickable（即不收 interactionSource / indication 的那个
// 重载）：全仓 5 处 combinedClickable 无一例外都传 indication = null，加了也没人用。
// 哪天真有调用点需要，照 combinedClickable 的对应重载补上即可。
// ============================================================================

/**
 * 带触感的 [clickable]，用 `LocalIndication` 的默认涟漪。
 *
 * 对应 `Modifier.clickable(enabled, onClickLabel, role, interactionSource, onClick)`。
 * 需要自己指定或者关掉 indication 的用另一个重载。
 *
 * 触感在 `onClick` **之前**发：手感上「按下去」应该先响，业务逻辑可能还要走网络。
 *
 * `enabled = false` 时 Compose 不会回调 `onClick`，所以也不会有触感 —— 不必额外判。
 * 但**纯粹用来吞掉点击的 `clickable(enabled = false)` 不要换成本函数**
 * （`ErrorStateView.kt:204` 就是一处）：那不是交互面，只是一层挡板。
 *
 * @param semantic 这次点击是什么交互。默认 [HapticSemantic.TAP]，也就是「普通按钮」那一档；
 *   一屏里反复出现的列表项请显式传 [HapticSemantic.LIGHT_TAP]，不然整屏都是顶档点击。
 */
@Composable
fun Modifier.hapticClickable(
    semantic: HapticSemantic = HapticSemantic.TAP,
    enabled: Boolean = true,
    onClickLabel: String? = null,
    role: Role? = null,
    interactionSource: MutableInteractionSource? = null,
    onClick: () -> Unit,
): Modifier {
    val haptics = rememberAppHaptics()
    return this.clickable(
        enabled = enabled,
        onClickLabel = onClickLabel,
        role = role,
        interactionSource = interactionSource,
    ) {
        haptics.perform(semantic)
        onClick()
    }
}

/**
 * 带触感的 [clickable]，自带视觉反馈（缩放、拟物按压、玻璃高光）的交互面用这个。
 *
 * 对应 `Modifier.clickable(interactionSource, indication, enabled, onClickLabel, role, onClick)`，
 * `indication = null` 就是「不要涟漪」—— `ui/component` 下 13 处这么写，是本仓的多数派。
 *
 * [interactionSource] 与 [indication] 无默认值，正是它们把这个重载和另一个区分开：
 * 少了任一个都只有另一个重载适用，编译器不会歧义。
 *
 * 触感时机、`enabled` 与挡板的注意事项同另一个重载。
 *
 * @param semantic 这次点击是什么交互，默认 [HapticSemantic.TAP]。
 */
@Composable
fun Modifier.hapticClickable(
    interactionSource: MutableInteractionSource?,
    indication: Indication?,
    semantic: HapticSemantic = HapticSemantic.TAP,
    enabled: Boolean = true,
    onClickLabel: String? = null,
    role: Role? = null,
    onClick: () -> Unit,
): Modifier {
    val haptics = rememberAppHaptics()
    return this.clickable(
        interactionSource = interactionSource,
        indication = indication,
        enabled = enabled,
        onClickLabel = onClickLabel,
        role = role,
    ) {
        haptics.perform(semantic)
        onClick()
    }
}

/**
 * 带触感的 [combinedClickable]。**长按那一记也归本函数发**，这是它和 Compose 原函数最大的差别。
 *
 * 对应 `Modifier.combinedClickable(interactionSource, indication, enabled, onClickLabel, role,
 * onLongClickLabel, onLongClick, onDoubleClick, hapticFeedbackEnabled, onClick)`。
 *
 * ### 长按：把 Compose 内建那一记接过来
 *
 * `CombinedClickableNode` 在回调 `onLongClick` 之前会自己
 * `performHapticFeedback(HapticFeedbackType.LongPress)`，键盘长按（按住 Enter）那条路也一样。
 * 本函数固定传 `hapticFeedbackEnabled = false` 把它关掉，改由 [longPressSemantic] 发。
 * 两个好处：
 *
 * - **不再双震。** 原先业务代码在 `onLongClick` 里再补一记，同一个常量背靠背两次。
 * - **长按也进四层引擎。** Compose 内建那记是硬编码的 `LongPress` 常量，走不到厂商预置效果，
 *   也不受三档开关管 —— 用户选了「关闭」，长按照样震。
 *
 * `hapticFeedbackEnabled` 只管长按，普通点击与双击本来就没有内建触感。
 *
 * 注意 `Compose Foundation 1.12.0` 才有这个参数，降版本会编不过。
 *
 * ### 双击没有触感
 *
 * [HapticSemantic] 里没有「双击」这一档，也没有调用点要它。真需要时在自己的
 * `onDoubleClick` lambda 里调 [rememberAppHaptics] 拿到的实例发一记，别往本函数加参数 ——
 * 加了就得先在语义词表里给双击定个位置。
 *
 * @param semantic 普通点击的语义，默认 [HapticSemantic.TAP]。
 * @param longPressSemantic 长按的语义，默认 [HapticSemantic.DRAG_START]（沉的一记「抓住了」）。
 *   传 null 表示长按静默 —— 长按之后紧接着弹出的面板自己会发 [HapticSemantic.POPUP_SHOW] 时用得上，
 *   否则两记会挤在一起。[onLongClick] 为 null 时本参数无效。
 */
@Composable
fun Modifier.hapticCombinedClickable(
    interactionSource: MutableInteractionSource?,
    indication: Indication?,
    semantic: HapticSemantic = HapticSemantic.TAP,
    longPressSemantic: HapticSemantic? = HapticSemantic.DRAG_START,
    enabled: Boolean = true,
    onClickLabel: String? = null,
    role: Role? = null,
    onLongClickLabel: String? = null,
    onLongClick: (() -> Unit)? = null,
    onDoubleClick: (() -> Unit)? = null,
    onClick: () -> Unit,
): Modifier {
    val haptics = rememberAppHaptics()
    return this.combinedClickable(
        interactionSource = interactionSource,
        indication = indication,
        enabled = enabled,
        onClickLabel = onClickLabel,
        role = role,
        onLongClickLabel = onLongClickLabel,
        onLongClick = onLongClick?.let { action ->
            {
                longPressSemantic?.let(haptics::perform)
                action()
            }
        },
        onDoubleClick = onDoubleClick,
        // 见 KDoc：关掉 Compose 内建的长按触感，那一记改由上面的 onLongClick 包装发
        hapticFeedbackEnabled = false,
    ) {
        haptics.perform(semantic)
        onClick()
    }
}

