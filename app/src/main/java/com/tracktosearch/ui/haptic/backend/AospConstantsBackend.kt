package com.tracktosearch.ui.haptic.backend

import android.annotation.SuppressLint
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import com.tracktosearch.ui.haptic.HapticBackend
import com.tracktosearch.ui.haptic.HapticSemantic
import com.tracktosearch.ui.haptic.HapticStrength

/**
 * tier 0：`View.performHapticFeedback(constant)`，整条降级链的地板。
 *
 * 常量交给 ROM 自行映射到线性马达 —— 我们既不画波形也不给振幅，所以它在 minSdk 26 上
 * 就能独立工作，也不受「转子马达锁 tier 0」那条硬规则限制：这一层正是那条规则要锁进来的目标。
 *
 * 与其余三层不同，本层刻意**没有**下面这些东西，每一处都有理由：
 *
 * - **没有反射、没有单线程 Executor。** 本层唯一的调用是 `View.performHapticFeedback`，
 *   它要读 View 自己的状态（attach 信息与 View 上的触感开关位），只能在 View 所在的主线程调；
 *   而且它有返回值，投进 Executor 就拿不回来了。Compose 自带的 `LocalHapticFeedback`
 *   走的也是主线程直调。于是 [release] 里没有线程要 shutdown，本层不可能泄漏线程。
 * - **没有 try-catch。** `View.performHapticFeedback` 返回 `Boolean` 且不抛异常，
 *   围着它写兜底链就是死代码 —— 旧实现 `ui/util/HapticExt.kt` 那条链正是如此。
 * - **没有「首次失败即整层禁用」。** 返回 false 最常见的原因是用户在系统里关了触感，
 *   或 View 还没 attach，两者都会恢复；因一次 false 就把地板永久关掉，
 *   用户重开系统触感后整个 App 再也不震。厂商层那条整层禁用规则不适用于本层。
 *
 * 语义到常量的映射（含低版本退化目标）全在 [aospConstantFor] 里，纯函数、可单测。
 * 档位与强度都归引擎：本层收到的已是最终语义；常量通路没有强度参数，轻/强档在这层不生效。
 *
 * 依据见 docs/superpowers/plans/2026-09-01-haptics-overhaul.md 的「四层引擎」与「语义词表」两节。
 */
class AospConstantsBackend : HapticBackend {

    /** 0 = AOSP 常量层，降级链的最后一站。 */
    override val tier: Int = 0

    /**
     * tier 1 的振幅波形层同样是 AOSP，两层若都叫 `"AOSP"`，
     * 「降级矩阵最终选中了哪一层」的断言就无从区分，所以这里带上 `Constants` 后缀。
     */
    override val name: String = "AOSP-Constants"

    /**
     * [release] 之后置 true，单向不回。
     *
     * 标 `@Volatile`：[release] 可能不在主线程（引擎随进程退出清理），而 [perform] 在主线程读。
     */
    @Volatile
    private var released: Boolean = false

    /**
     * 恒为可用，直到 [release]。
     *
     * 刻意不接 `HapticCapabilities`：本层没有任何要探测的能力，构造完就能用，于是引擎可以在
     * 能力探测跑完之前先把地板架好（`HapticCapabilities.probe` 是阻塞调用，得丢到 IO 上）。
     *
     * 「本机没有马达」由引擎按 `HapticCapabilities.hasVibrator` 整体短路，本层判不出来：
     * 无马达设备上 `performHapticFeedback` 照样返回 true（框架把效果交给 VibratorService
     * 就算成功），所以别指望这一层的返回值能反映有没有马达。
     */
    override fun isAvailable(): Boolean = !released

    /**
     * 13 个语义全部支持：地板不能再降，每个语义都有常量或退化目标兜着（见 [aospConstantFor]）。
     *
     * 刻意不看 [released]：这里是纯查表的能力声明，「这一层还活着吗」归 [isAvailable] 回答。
     */
    override fun supports(semantic: HapticSemantic): Boolean = true

    /**
     * 发一次常量触感，把 `View.performHapticFeedback` 的返回值原样交回。
     *
     * [strength] 刻意忽略：常量通路没有强度参数（ROM 拿常量自己去映射波形，应用侧
     * 无从插手），轻/强档在本层不生效——本层是转子马达机型与兜底场景的地板，
     * 那两类机器上「档位只改强度」退化为「档位不改什么」，可接受且已写进设计文档。
     *
     * 三处提前返回 false：已 [release]；[view] 为 null（本层必须有 View，不去别处翻一个）；
     * View 自己的触感开关关着。最后一条框架内部也会查一遍并返回 false，这里先查是为了省掉
     * 那次到 WindowSession 的 binder 往返。
     *
     * 用不带 flag 的单参重载：`FLAG_IGNORE_GLOBAL_SETTING` 会盖掉用户的系统触感设置，
     * 全局约束里明令禁止；`FLAG_IGNORE_VIEW_SETTING` 同理会盖掉上面那个 View 开关。
     *
     * 返回 false 时引擎已无更低的层可退。最常见的原因是用户关了系统触感，属预期结果，不是缺陷。
     */
    override fun perform(view: View?, semantic: HapticSemantic, strength: HapticStrength): Boolean {
        if (released || view == null || !view.isHapticFeedbackEnabled) return false
        return view.performHapticFeedback(aospConstantFor(semantic, Build.VERSION.SDK_INT))
    }

    /**
     * 恒为 false：本层只有离散常量，画不出连续包络。
     *
     * 不许拿一记常量假装成功 —— 引擎靠这个返回值决定要不要退成「每段起点一记 tick」的稀疏编排。
     * 两个参数因此刻意不读，连长度校验都不做：无论传什么本层都不支持。
     */
    override fun playEnvelope(timingsMs: IntArray, amplitudes: FloatArray): Boolean = false

    /**
     * 只置一个标志位，幂等。本层没有反射实例、没有 Executor、不持有 Context 或 View，
     * 没有别的资源要回收。
     */
    override fun release() {
        released = true
    }
}

/**
 * 语义 → `HapticFeedbackConstants`，含低版本退化目标。本层唯一可单测的部分。
 *
 * [sdkInt] 由调用方传入，而不是在函数里读 `Build.VERSION.SDK_INT` —— 这样单测能把
 * 26 / 30 / 34 三档各跑一遍；真实的 `Build.VERSION.SDK_INT` 只在
 * [AospConstantsBackend.perform] 里读那一次。
 *
 * 函数体只有整型常量与比较：`HapticFeedbackConstants` 与 `Build.VERSION_CODES` 的字段都带
 * ConstantValue，会被编译成字面量，运行时根本不加载这两个 android 类。所以本函数在纯 JVM 的
 * `src/test` 里能直接跑，不必上 Robolectric，也不必上 androidTest。
 *
 * 门控只有两档，都是常量自己的引入版本（对着 `platforms/android-37.0/data/api-versions.xml`
 * 逐条核过，不是照版本号猜的）：
 *
 * - API 30 引入 `CONFIRM`、`REJECT`、`GESTURE_END`
 * - API 34 引入 `SEGMENT_TICK`、`SEGMENT_FREQUENT_TICK`、`TOGGLE_ON`、`TOGGLE_OFF`、
 *   `DRAG_START`、`GESTURE_THRESHOLD_ACTIVATE`
 * - `VIRTUAL_KEY`（API 5）、`CLOCK_TICK`（API 21）、`LONG_PRESS`（API 3）都低于 minSdk 26，
 *   无需门控，也正因如此才能当退化目标
 *
 * 设计文档的「语义词表」把 `GESTURE_END` 标成 API 34，实为 API 30（与 `CONFIRM`、`REJECT`
 * 同批引入）。这里按 30 门控：API 30..33 上就能用语义正确的常量，比退回 `CLOCK_TICK` 更贴文档本意。
 *
 * `when` 穷举、不写 `else`：加语义时编译器会逼着补上映射，免得新语义悄悄落到某个默认常量上。
 *
 * `@SuppressLint("NewApi")`：版本门控落在 [sdkInt] 这个入参上，而 lint 的 NewApi 只认
 * `Build.VERSION.SDK_INT` 形式的判断、跟不进参数，会把 API 30 与 34 的常量误报成越版本引用。
 *
 * 映射逐条照设计文档「语义词表」最右列：
 * docs/superpowers/plans/2026-09-01-haptics-overhaul.md
 */
@SuppressLint("NewApi")
internal fun aospConstantFor(semantic: HapticSemantic, sdkInt: Int): Int = when (semantic) {
    // 普通按钮、可点卡片。VIRTUAL_KEY = 1，API 5，minSdk 26 上恒可用，无需门控
    HapticSemantic.TAP -> HapticFeedbackConstants.VIRTUAL_KEY

    // 列表项、次级操作。CLOCK_TICK = 4，API 21，恒可用；也是本表用得最多的退化目标
    HapticSemantic.LIGHT_TAP -> HapticFeedbackConstants.CLOCK_TICK

    // Tab 切换、单选、图表选中、分段控件。SEGMENT_TICK = 26 是 API 34 才有的「卡进格子」刻度
    HapticSemantic.SEGMENT_TICK -> if (sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        HapticFeedbackConstants.SEGMENT_TICK
    } else {
        HapticFeedbackConstants.CLOCK_TICK
    }

    // 滑块连续拖动、逐珠划过。SEGMENT_FREQUENT_TICK = 27（API 34）是给一次手势里连发几十次用的
    HapticSemantic.FREQUENT_TICK -> if (sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        HapticFeedbackConstants.SEGMENT_FREQUENT_TICK
    } else {
        HapticFeedbackConstants.CLOCK_TICK
    }

    // 开关打开、chip 选中、展开。TOGGLE_ON = 21（API 34），退到 VIRTUAL_KEY 保住「立起来」的实感
    HapticSemantic.TOGGLE_ON -> if (sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        HapticFeedbackConstants.TOGGLE_ON
    } else {
        HapticFeedbackConstants.VIRTUAL_KEY
    }

    // 开关关闭、chip 取消、收起。TOGGLE_OFF = 22（API 34），比开轻一档，所以退到 CLOCK_TICK
    HapticSemantic.TOGGLE_OFF -> if (sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        HapticFeedbackConstants.TOGGLE_OFF
    } else {
        HapticFeedbackConstants.CLOCK_TICK
    }

    // 提交成功、标记成功、登录成功。CONFIRM = 16（API 30），退到 VIRTUAL_KEY
    HapticSemantic.CONFIRM -> if (sdkInt >= Build.VERSION_CODES.R) {
        HapticFeedbackConstants.CONFIRM
    } else {
        HapticFeedbackConstants.VIRTUAL_KEY
    }

    // 操作失败、校验不通过。REJECT = 17（API 30），退到 LONG_PRESS = 0（API 3）借它那记沉的
    HapticSemantic.REJECT -> if (sdkInt >= Build.VERSION_CODES.R) {
        HapticFeedbackConstants.REJECT
    } else {
        HapticFeedbackConstants.LONG_PRESS
    }

    // 拖拽起手、长按进入拖动。DRAG_START = 25（API 34），退到 LONG_PRESS
    HapticSemantic.DRAG_START -> if (sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        HapticFeedbackConstants.DRAG_START
    } else {
        HapticFeedbackConstants.LONG_PRESS
    }

    // 下拉刷新到阈值、侧滑到阈值。GESTURE_THRESHOLD_ACTIVATE = 23（API 34），退到 VIRTUAL_KEY
    HapticSemantic.THRESHOLD_ARMED -> if (sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE
    } else {
        HapticFeedbackConstants.VIRTUAL_KEY
    }

    // 手势翻页落定、面板吸附。GESTURE_END = 13，API 30（文档标 34 有误，见函数 KDoc）
    HapticSemantic.GESTURE_END -> if (sdkInt >= Build.VERSION_CODES.R) {
        HapticFeedbackConstants.GESTURE_END
    } else {
        HapticFeedbackConstants.CLOCK_TICK
    }

    // 列表滚到尽头、滑条到边界。AOSP 没有对应常量，词表直接给 CLOCK_TICK，无版本门控
    HapticSemantic.SCROLL_EDGE -> HapticFeedbackConstants.CLOCK_TICK

    // 弹窗、底部面板出现。同样没有对应常量，词表给 CLOCK_TICK：让面板落得出来又不抢注意力
    HapticSemantic.POPUP_SHOW -> HapticFeedbackConstants.CLOCK_TICK
}
