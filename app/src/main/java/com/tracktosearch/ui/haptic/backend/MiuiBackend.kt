package com.tracktosearch.ui.haptic.backend

import android.content.Context
import android.view.View
import com.tracktosearch.ui.haptic.HapticBackend
import com.tracktosearch.ui.haptic.HapticCapabilities
import com.tracktosearch.ui.haptic.HapticSemantic
import java.lang.reflect.Method
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * tier 2：小米 / Redmi / POCO（MIUI 与 HyperOS）的语义效果通路，全程反射，**双路**。
 *
 * 两条子通路，探测期自动二选一：
 *
 * 1. **String 键通路（HyperOS 3 实测可用）**：`performHapticFeedback(EFFECT_KEY_xxx, true)`。
 *    `HapticFeedbackUtil` 上有 40 多个 `EFFECT_KEY_*` 字符串常量（tap_normal、gear_light、
 *    switch、alert……），配合 `performHapticFeedback(String, boolean)` 派发。可用性判据是
 *    **读 ROM 自己的常量字段**（红线条约 3 的精神：以设备事实为准，不按版本号推断）——
 *    方法签名解析得出来、且 13 个语义要用的键字段全都存在，才进这条路。
 * 2. **ext ID 通路（老 MIUI 12-14）**：静态无参 `isSupportLinearMotorVibrate()` 判整层、
 *    `(Context, boolean)` 构造实例、逐 ID `isSupportExtHapticFeedback(int)` 探可用性、
 *    `performExtHapticFeedback(int)` 派发。26 个效果 ID 见 [MiuiHapticEffects]。
 *
 * ### 为什么必须双路（2026-09-09 真机实测，小米 14 Pro / HyperOS 3）
 *
 * - HyperOS 3 上 26 个 0x10000000 段 ext ID 对三方 App **全关**：`isSupportExtHapticFeedback`
 *   逐 ID 返 false，无视探测直接派发也静默无效；小整数段探测会报支持但实弹同样无效
 *   （**那条探测会说谎，别信**）。老 ext 通路在这类 ROM 上整层空转。
 * - String 键通路实测真震（VibratorManagerService 起播记录 + 手感确认），且是
 *   `mUsage=HARDWARE_FEEDBACK` 的小米调好效果——三方 App 在这台机上唯一摸得到
 *   MIUI 原生效果的口子。
 * - 老 MIUI 上 `performHapticFeedback(String, boolean)` 签名不存在，反射解析失败自然
 *   落回 ext 通路，两边互不干扰。
 *
 * 这一层仍然只能「选」不能「画」：效果背后是小米自己调过的固定波形，没有参数可调 ——
 * 所以 [playEnvelope] 恒为 false，彩蛋的连续包络走 tier 3（MiHaptic HE / RichTap）。
 *
 * 三条红线在本层的落法：
 *
 * 1. 硬规则 1：`HapticCapabilities.lockedToConstants` 为 true（转子马达）时 [isAvailable]
 *    恒为 false，tier 2 一步都不许走。
 * 2. 硬规则 3：不读 `sys.haptic.version`、不看机型白名单。String 路的判据是 ROM 类上的
 *    常量字段与方法签名是否解析得出；ext 路的判据是逐 ID `isSupportExtHapticFeedback(id)`
 *    并缓存结果。
 * 3. 整层 `catch (Throwable)` 而非 `catch (Exception)`：类不在时抛的
 *    `NoClassDefFoundError` 是 Error。任何一步失败即整层永久禁用，不每次重试。
 *
 * 线程模型：类查找、反射构造与到 `VibratorService` 的 IPC 全丢给一条单线程 `Executor`
 * （线程名 `haptic-miui`）。单线程不只为省资源 —— FIFO 队列保派发顺序，彩蛋编排依赖顺序。
 * [isAvailable] 与 [supports] 在主线程只读 volatile 字段，不碰反射。
 *
 * 有一个预热窗口：探测在首次 [isAvailable] 被调用时才甩给 Executor，探完之前 [supports]
 * 对所有语义返回 false，引擎会降级到 tier 3 或 tier 0。所以引擎应在装配阶段先调一次
 * [isAvailable] 把这层焐热，别等第一次点击 —— 那一次点击会落到下一层去。
 *
 * 本层不经过 `View.performHapticFeedback`，因此系统触感总开关不会被自动尊重：
 * 系统在 `View.performHapticFeedback` 里做的 `isHapticFeedbackEnabled` 判定在这条路上
 * 不存在。红线「不覆盖用户的系统触感设置」因此必须由引擎在调用本层之前判；
 * 本层做不到，也不该自己去读 Settings —— 那是引擎的职责，两处各读一遍只会漂移。
 *
 * 全文没有 `Build.VERSION.SDK_INT` 门控：用到的符号全来自 ROM 侧的
 * `miui.util.HapticFeedbackUtil`，不是 AOSP SDK 常量，存在性判定本身就是它的门控。
 * Android 9 起有非 SDK 接口访问限制，但 `miui` 包由 MIUI 自己放行；万一哪天被拦，
 * `getMethod` 抛 `NoSuchMethodException`，落到整层禁用，不会崩。
 *
 * HiMiuix（LGPL-2.1）的代码一行都没抄：这里用的只是常量取值与 API 形状，属平台事实。
 *
 * @param context 反射实例会长期持有，构造时立刻取 `applicationContext`，避免泄漏 Activity
 * @param capabilities 只读三个字段（`hasVibrator`、`lockedToConstants`、`miuiSupported`），不留引用
 */
class MiuiBackend(
    context: Context,
    capabilities: HapticCapabilities,
) : HapticBackend {

    /** 反射实例长期持有 Context，必须是 Application 级；个别宿主取不到时退回原 Context */
    private val appContext: Context = context.applicationContext ?: context

    /**
     * 本层有没有资格上场，构造期算一次，之后不变。
     *
     * 三项全取自快照，纯字段读：有马达、不是转子马达（硬规则 1）、这一层探到可用。
     * 为 false 时永不提交任何任务，[executor] 那条线程也就永远不会创建。
     */
    private val eligible: Boolean = capabilities.hasVibrator &&
        !capabilities.lockedToConstants &&
        capabilities.miuiSupported

    /**
     * 反射与 IPC 的唯一执行线程。
     *
     * `newSingleThreadExecutor` 只建对象、不预启线程，线程要到第一次 `execute` 才创建，
     * 所以非小米机型上这里是零开销。FIFO 队列顺带保住派发顺序（彩蛋编排依赖顺序），
     * 单线程是硬要求而非优化。设成 daemon：一记触感不值得拖住进程退出。
     */
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, THREAD_NAME).apply { isDaemon = true }
    }

    /** 探测只做一次的闸门；CAS 赢的那一次负责把 [probe] 甩给 [executor] */
    private val probeStarted = AtomicBoolean(false)

    /**
     * 整层禁用的单向闸：一旦置 true 就不再翻回 false（首次失败即禁用，不重试）。
     *
     * 三种来源 —— 探测失败、派发失败、[release]。写在 Executor 线程、读在主线程，故 volatile。
     */
    @Volatile
    private var disabled = false

    /** 探测拿到的反射句柄；失败或 [release] 后置空，Executor 里的任务读到 null 直接放过 */
    @Volatile
    private var reflection: MiuiReflection? = null

    /**
     * 逐 ID 探测的结果缓存（硬规则 3，ext ID 通路用），key 是 MIUI 效果 ID。
     *
     * 探完才整体替换，主线程侧看到的要么是空表要么是完整表，不会读到半张。
     */
    @Volatile
    private var supportedIds: Map<Int, Boolean> = emptyMap()

    /**
     * String 键通路的句柄与语义→键值表；非 null 即本层处于 String 模式。
     *
     * 与 [supportedIds] 一样探完整体替换；[reflection] 为 null 且本字段非 null 时，
     * ext 通路没有被解析（String 路成功即短路，两者互斥）。
     */
    @Volatile
    private var stringDispatch: MiuiStringDispatch? = null

    /** tier 2：厂商调好的预置效果，比 tier 1 自拼的振幅数组手感好，但画不了波形 */
    override val tier: Int = 2

    /** 单测靠这个名字断言降级矩阵最终停在了哪一层，取值固定不变 */
    override val name: String = "MIUI"

    /**
     * 本层整体可用否。纯 volatile 读，可以落在每次点击的主线程路径上。
     *
     * 首次调用顺手把探测甩给 [executor]（只甩一次），本方法不等它 —— 探测期间照样返回 true，
     * 而 [supports] 全 false，引擎自然降级。先 `get()` 再 CAS：焐热之后这里只剩两次 volatile 读。
     *
     * 结尾读 [disabled] 而不是直接 `return true`：提交被拒（已 [release]）时 [submit]
     * 会把整层禁用，这一次调用就该看到 false。
     */
    override fun isAvailable(): Boolean {
        if (!eligible) return false
        if (!probeStarted.get() && probeStarted.compareAndSet(false, true)) {
            submit { probe() }
        }
        return !disabled
    }

    /**
     * 这个语义在本机有没有对应的可用效果。只查探测缓存，不做任何推断。
     *
     * String 模式（[stringDispatch] 非 null）看键表；ext 模式看逐 ID 表。
     * 探测未完成时两张表都是空的，一律 false —— 这是预热窗口的正常表现，不是错误。
     */
    override fun supports(semantic: HapticSemantic): Boolean {
        if (!isAvailable()) return false
        stringDispatch?.let { return semantic in it.keys }
        return supportedIds[miuiEffectFor(semantic)] == true
    }

    /**
     * 派发一次语义效果。[view] 用不上：两条子通路都挂在反射出来的 util 实例上，
     * 不走 View 通道，传 null 也照样能发。
     *
     * 返回 true 只表示「已排进单线程队列」，不表示马达震了。真正的调用在 Executor 上，
     * 那边失败只能把整层禁用、让后续调用降级，这一次已经报了 true —— 反射与 IPC 不能在主线程
     * 同步做，这是接口的固有限制。所以本层靠 [supports] 的探测缓存在主线程侧先行拦截。
     */
    override fun perform(view: View?, semantic: HapticSemantic): Boolean {
        val dispatch = stringDispatch
        if (dispatch != null) {
            val key = dispatch.keys[semantic] ?: return false
            return submit { dispatchKey(dispatch, key) }
        }
        if (!supports(semantic)) return false
        val effectId = miuiEffectFor(semantic)
        return submit { dispatch(effectId) }
    }

    /**
     * 恒为 false。26 个 ID 是固定预置波形，没有振幅数组入口；
     * 拿一记离散点击假装成功会让引擎的降级判断整条失效（见 [HapticBackend.playEnvelope]）。
     */
    override fun playEnvelope(timingsMs: IntArray, amplitudes: FloatArray): Boolean = false

    /**
     * 释放：禁用整层、丢掉反射句柄与探测缓存、shutdown 那条单线程（漏了就是线程泄漏）。
     *
     * 幂等 —— [disabled] 是单向闸，`ExecutorService.shutdown` 本身可重复调。
     * 用 `shutdown` 而非 `shutdownNow`：不去打断可能正在飞的 Binder 调用，
     * 队列里剩下的任务开头会读到 [disabled] 自行放过。释放后 [isAvailable] 恒 false，
     * [perform] 与 [playEnvelope] 恒 false，不会重新起线程。
     */
    override fun release() {
        markDisabled()
        executor.shutdown()
    }

    /**
     * 把任务排进单线程队列。
     *
     * 提交本身也会抛：已 shutdown 抛 `RejectedExecutionException`，线程建不出来抛
     * `OutOfMemoryError`（是 Error，所以这里也必须 `catch (Throwable)`）。一律吃掉并禁用整层。
     *
     * 显式包一层 `Runnable`：Kotlin 函数类型到 Java 单方法接口的隐式转换只对字面量成立，
     * 这里传进来的是个函数值，得走 SAM 构造。
     *
     * @return true 已排队；false 排不进去，调用方按失败处理
     */
    private fun submit(task: () -> Unit): Boolean = try {
        executor.execute(Runnable { task() })
        true
    } catch (_: Throwable) {
        disabled = true
        false
    }

    /**
     * 在 Executor 线程上跑一次的完整探测：静态判定 → 试 String 键通路 → 退 ext ID 通路。
     *
     * `isSupportLinearMotorVibrate()` 这里重做一次而不是只信构造时的快照：快照可能是别处传进来的
     * 陈旧值，而这条静态判定是本层唯一的总闸；代价是一次反射，且只做一次。
     *
     * String 通路解析失败（老 MIUI 没那个方法签名，或缺键字段）返回 null，落回 ext 通路；
     * ext 通路的两个 Method 任一查不到就整层禁用 —— 探不到就不许发，这是硬规则 3 的必然结论：
     * 没有 `isSupportExtHapticFeedback` 就无法逐 ID 判断，而按 ROM 版本号推断是明令禁止的。
     * 单个 ID 探测抛错只把那个 ID 记成 false，不牵连整层。
     */
    private fun probe() {
        if (disabled) return
        try {
            val clazz = Class.forName(MIUI_HAPTIC_FEEDBACK_UTIL)
            val linearSupported = clazz.getMethod(METHOD_IS_SUPPORT_LINEAR).invoke(null) as? Boolean
            if (linearSupported != true) {
                markDisabled()
                return
            }
            resolveStringDispatch(clazz)?.let {
                stringDispatch = it
                return
            }
            val instance = clazz
                .getConstructor(Context::class.java, Boolean::class.javaPrimitiveType)
                .newInstance(appContext, MIUI_UTIL_CTOR_FLAG)
            val isSupportExt = clazz.getMethod(METHOD_IS_SUPPORT_EXT, Int::class.javaPrimitiveType)
            val performExt = clazz.getMethod(METHOD_PERFORM_EXT, Int::class.javaPrimitiveType)
            val probed = LinkedHashMap<Int, Boolean>()
            for (effectId in miuiProbeIds()) {
                probed[effectId] = try {
                    (isSupportExt.invoke(instance, effectId) as? Boolean) == true
                } catch (_: Throwable) {
                    false
                }
            }
            reflection = MiuiReflection(instance, performExt)
            supportedIds = probed
        } catch (_: Throwable) {
            markDisabled()
        }
    }

    /**
     * 解析 String 键通路；这台 ROM 上走不通就返回 null，调用方落回 ext ID 通路。
     *
     * 可用性判据：`performHapticFeedback(String, boolean)` 签名解析得出，且 13 个语义要用的
     * `EFFECT_KEY_*` 常量字段一个不缺（值从字段现读，不写死）。字段缺失只说明这个键这台
     * ROM 没定义，不抛不闹；有一个缺就整路放弃 —— 半条 String 半条 ext 的混合模式两头都要
     * 焐，复杂度不值。刻意**不实弹探测**：启动预热时震一下是坏体验，而真机实测
     * （2026-09-09，小米 14 Pro）这条路一旦解析得通就真的会响。
     */
    private fun resolveStringDispatch(clazz: Class<*>): MiuiStringDispatch? = try {
        val performKey = clazz.getMethod(
            METHOD_PERFORM_KEY,
            String::class.java,
            Boolean::class.javaPrimitiveType,
        )
        val instance = clazz
            .getConstructor(Context::class.java, Boolean::class.javaPrimitiveType)
            .newInstance(appContext, MIUI_UTIL_CTOR_FLAG)
        val keys = buildMap {
            for (semantic in HapticSemantic.entries) {
                val value = runCatching {
                    clazz.getField(miuiEffectKeyFieldFor(semantic)).get(null) as? String
                }.getOrNull() ?: return null
                put(semantic, value)
            }
        }
        MiuiStringDispatch(instance, performKey, keys)
    } catch (_: Throwable) {
        null
    }

    /**
     * 在 Executor 线程上真正调 `performHapticFeedback(key, flag)`（String 键通路）。
     *
     * 首次抛错即整层禁用，与 [dispatch] 同一策略：ROM 侧一旦不认这个调用，重试只是重复
     * 付反射与 IPC 的钱。
     */
    private fun dispatchKey(dispatch: MiuiStringDispatch, key: String) {
        if (disabled) return
        try {
            dispatch.performKey.invoke(dispatch.instance, key, MIUI_KEY_FLAG)
        } catch (_: Throwable) {
            markDisabled()
        }
    }

    /**
     * 在 Executor 线程上真正调 `performExtHapticFeedback(id)`。
     *
     * 首次抛错即整层禁用，不每次重试：ROM 侧一旦不认这个调用，重试只是重复付反射与 IPC 的钱。
     */
    private fun dispatch(effectId: Int) {
        if (disabled) return
        val state = reflection ?: return
        try {
            state.performExt.invoke(state.instance, effectId)
        } catch (_: Throwable) {
            markDisabled()
        }
    }

    /** 整层禁用并丢掉句柄与缓存。刻意不 shutdown [executor] —— 那是 [release] 的活 */
    private fun markDisabled() {
        disabled = true
        reflection = null
        stringDispatch = null
        supportedIds = emptyMap()
    }

    /** 探测拿到的 ext ID 通路反射句柄；整体替换而不逐字段改，读到的必然是配套的实例与方法 */
    private class MiuiReflection(
        val instance: Any,
        val performExt: Method,
    )

    /** String 键通路的句柄与键表；整体替换，读到的必然配套。 */
    private class MiuiStringDispatch(
        val instance: Any,
        val performKey: Method,
        /** 语义 → `EFFECT_KEY_*` 的字段值（从 ROM 现读，不写死）。13 个语义一表齐全 */
        val keys: Map<HapticSemantic, String>,
    )

    private companion object {
        /** MIUI / HyperOS 触感工具类，不在 SDK 里、只有 ROM 上有；设计文档「调用形状」小节 */
        const val MIUI_HAPTIC_FEEDBACK_UTIL = "miui.util.HapticFeedbackUtil"

        /** 静态无参，判整层可用；设计文档「四层引擎」表 tier 2 行的能力探测 */
        const val METHOD_IS_SUPPORT_LINEAR = "isSupportLinearMotorVibrate"

        /** 实例方法 `(int)`，逐 ID 探测；硬规则 3 唯一认可的判据 */
        const val METHOD_IS_SUPPORT_EXT = "isSupportExtHapticFeedback"

        /** 实例方法 `(int)`，派发一个效果 ID */
        const val METHOD_PERFORM_EXT = "performExtHapticFeedback"

        /**
         * 实例方法 `(String, boolean)`，String 键派发；HyperOS 3 的现行口子。
         * 用参数类型定位到这个重载，同名的其他签名（int/VibrationAttributes/Uri）不误伤。
         */
        const val METHOD_PERFORM_KEY = "performHapticFeedback"

        /**
         * String 键派发的 boolean 实参。ROM 未公开含义，两档（true/false）真机实弹都有效，
         * 照抄 true —— 与 `MIUI_UTIL_CTOR_FLAG` 同一原则：不凭猜测改线上行为。
         */
        const val MIUI_KEY_FLAG = true

        /**
         * `HapticFeedbackUtil(Context, boolean)` 的第二个实参。
         *
         * 设计文档「调用形状」小节写死 `newInstance(context, true)`，两份现存实现也都传 true。
         * 这个形参在 MIUI 侧没有公开文档、含义不明，所以照抄不改 —— 改成 false 属于凭猜测动线上行为。
         */
        const val MIUI_UTIL_CTOR_FLAG = true

        /** 单线程的线程名，带得出来源，便于在 systrace 与 ANR 栈里认出这条队列 */
        const val THREAD_NAME = "haptic-miui"
    }
}

/**
 * 语义 → MIUI 效果 ID。本层唯一可单测的部分：不碰 Context、不碰反射，纯输入输出。
 *
 * 映射照设计文档 docs/superpowers/plans/2026-09-01-haptics-overhaul.md
 * 「MIUI 语义效果表（tier 2）」末尾那段「语义词表接到 tier 2 的映射」，
 * 外加同一节补进词表的两条 [HapticSemantic.SCROLL_EDGE] 与 [HapticSemantic.POPUP_SHOW]。
 *
 * `when` 穷举、不写 `else`：往 [HapticSemantic] 加语义时编译器会逼着补映射，
 * 而 [miuiProbeIds] 从枚举推导，会自动多探那个新 ID，不会漏。
 *
 * 返回的是 ID 本身，不是「可用的 ID」—— 可用性是 `isSupportExtHapticFeedback` 的结论，
 * 由 [MiuiBackend.supports] 查探测缓存回答，纯函数不掺和。
 */
internal fun miuiEffectFor(semantic: HapticSemantic): Int = when (semantic) {
    // 标准点击：通用点击的顶档
    HapticSemantic.TAP -> MiuiHapticEffects.MIUI_TAP_NORMAL
    // 轻点击：比标准点击轻一档，给一屏里反复出现的列表项
    HapticSemantic.LIGHT_TAP -> MiuiHapticEffects.MIUI_TAP_LIGHT
    // 轻齿轮：有「卡进格子」的刻度感，正对 Tab、单选、分段控件
    HapticSemantic.SEGMENT_TICK -> MiuiHapticEffects.MIUI_GEAR_LIGHT
    // 轻网格：连发场景专用，一次手势里几十下也不吵
    HapticSemantic.FREQUENT_TICK -> MiuiHapticEffects.MIUI_MESH_LIGHT
    // 开关：ON 与 OFF 共用同一个 ID —— MIUI 只给了一个开关效果，方向差异在这层表达不了
    HapticSemantic.TOGGLE_ON -> MiuiHapticEffects.MIUI_SWITCH
    HapticSemantic.TOGGLE_OFF -> MiuiHapticEffects.MIUI_SWITCH
    // 大按钮：成功那一笔要实心
    HapticSemantic.CONFIRM -> MiuiHapticEffects.MIUI_BUTTON_LARGE
    // 警示：失败语义，重但不到惊吓
    HapticSemantic.REJECT -> MiuiHapticEffects.MIUI_ALERT
    // 保持：起手那记沉的「抓住了」
    HapticSemantic.DRAG_START -> MiuiHapticEffects.MIUI_HOLD
    // 空间边界：到阈值的强提示，「可以松手了」
    HapticSemantic.THRESHOLD_ARMED -> MiuiHapticEffects.MIUI_BOUNDARY_SPATIAL
    // 甩动：手势落定、面板吸附
    HapticSemantic.GESTURE_END -> MiuiHapticEffects.MIUI_FLICK
    // 滚动到边：撞墙的一记，且会被反复顶到
    HapticSemantic.SCROLL_EDGE -> MiuiHapticEffects.MIUI_SCROLL_EDGE
    // 弹窗：让面板落得出来，又不抢注意力
    HapticSemantic.POPUP_SHOW -> MiuiHapticEffects.MIUI_POPUP_NORMAL
}

/**
 * 要逐 ID 探测的效果集合：13 个语义映射出的去重结果，当前 12 个
 * （[HapticSemantic.TOGGLE_ON] 与 [HapticSemantic.TOGGLE_OFF] 共用 `MIUI_SWITCH`）。
 *
 * 从 `HapticSemantic.entries` 推导而不是手写列表：加语义时不会忘了补探测。
 * 顺序跟枚举声明顺序一致，探测结果表因此是确定的，可以直接在单测里断言。
 * 26 个常量里剩下的 14 个当前没有语义用到，不探 —— 探了也没人问。
 */
internal fun miuiProbeIds(): List<Int> =
    HapticSemantic.entries.map { miuiEffectFor(it) }.distinct()

/**
 * 语义 → `HapticFeedbackUtil` 的 `EFFECT_KEY_*` 常量**字段名**（String 键通路的映射）。
 * 本层唯一新增的可单测部分：不碰 Context、不碰反射，纯输入输出。
 *
 * 字段名是 ROM 侧公开静态常量的名字——HyperOS 3 实测 51 个静态字段的全表里这里有
 * 用到的每一个；**取值不写死**，探测时从字段现读，键名不变而值变了的 ROM 也能跟上。
 *
 * 语义选键与 ext ID 表（[miuiEffectFor]）同口径，两处的理由互相引用：ID 表的注释
 * 说的是效果选择，这里不再重复。
 */
internal fun miuiEffectKeyFieldFor(semantic: HapticSemantic): String = when (semantic) {
    HapticSemantic.TAP -> "EFFECT_KEY_TAP_NORMAL"
    HapticSemantic.LIGHT_TAP -> "EFFECT_KEY_TAP_LIGHT"
    HapticSemantic.SEGMENT_TICK -> "EFFECT_KEY_GEAR_LIGHT"
    HapticSemantic.FREQUENT_TICK -> "EFFECT_KEY_MESH_LIGHT"
    // 开与关共用 switch 键：MIUI 只给了一个开关效果，方向差异在这层表达不了
    HapticSemantic.TOGGLE_ON -> "EFFECT_KEY_SWITCH"
    HapticSemantic.TOGGLE_OFF -> "EFFECT_KEY_SWITCH"
    HapticSemantic.CONFIRM -> "EFFECT_KEY_BUTTON_LARGE"
    HapticSemantic.REJECT -> "EFFECT_KEY_ALERT"
    HapticSemantic.DRAG_START -> "EFFECT_KEY_HOLD"
    HapticSemantic.THRESHOLD_ARMED -> "EFFECT_KEY_BOUNDARY_SPATIAL"
    HapticSemantic.GESTURE_END -> "EFFECT_KEY_FLICK"
    HapticSemantic.SCROLL_EDGE -> "EFFECT_KEY_SCROLL_EDGE"
    HapticSemantic.POPUP_SHOW -> "EFFECT_KEY_POPUP_NORMAL"
}

/**
 * `miui.view.MiuiHapticFeedbackConstants` 的 26 个效果 ID，从 `0x10000000` 起连续到 `0x10000019`。
 *
 * 名字与取值照设计文档 docs/superpowers/plans/2026-09-01-haptics-overhaul.md
 * 「MIUI 语义效果表（tier 2）」那张表逐条抄，`MIUI_` 前缀保留，方便跟表和反编译字段表对照。
 * 该表的两个来源已交叉核对：小米自家应用里 `miuix.view.HapticFeedbackConstants` 的字段表，
 * 与第三方库 HiMiuix 的实现一致。HiMiuix 是 LGPL-2.1，一行代码都没抄 ——
 * 常量取值与 API 形状是平台事实，不是它的著作物。
 *
 * 26 个全列而只有 12 个被 [miuiEffectFor] 用到：取值已经核对过一遍，留着比日后再查一遍便宜。
 * 彩蛋答题期的键盘会用 [MIUI_KEYBOARD]（设计文档「彩蛋编排」），那是 T6 的活。
 * 另有 4 个键盘 RTP（193 与 194 是 clicky 的按下与抬起，195 与 196 是 linear 的）
 * 不在这 26 个连续 ID 之内，也不在本层射程内，故不收。
 */
internal object MiuiHapticEffects {
    /** 虚拟键抬起 */
    const val MIUI_VIRTUAL_RELEASE = 0x10000000

    /** 标准点击 */
    const val MIUI_TAP_NORMAL = 0x10000001

    /** 轻点击 */
    const val MIUI_TAP_LIGHT = 0x10000002

    /** 甩动 */
    const val MIUI_FLICK = 0x10000003

    /** 开关 */
    const val MIUI_SWITCH = 0x10000004

    /** 重网格 */
    const val MIUI_MESH_HEAVY = 0x10000005

    /** 网格 */
    const val MIUI_MESH_NORMAL = 0x10000006

    /** 轻网格 */
    const val MIUI_MESH_LIGHT = 0x10000007

    /** 长按。刻意不进语义词表：`combinedClickable` 自己已经发过一次 `LongPress` */
    const val MIUI_LONG_PRESS = 0x10000008

    /** 弹窗 */
    const val MIUI_POPUP_NORMAL = 0x10000009

    /** 轻弹窗 */
    const val MIUI_POPUP_LIGHT = 0x1000000a

    /** 拾起 */
    const val MIUI_PICK_UP = 0x1000000b

    /** 滚动到边 */
    const val MIUI_SCROLL_EDGE = 0x1000000c

    /** 抽屉触发 */
    const val MIUI_TRIGGER_DRAWER = 0x1000000d

    /** 轻甩动 */
    const val MIUI_FLICK_LIGHT = 0x1000000e

    /** 保持 */
    const val MIUI_HOLD = 0x1000000f

    /** 空间边界 */
    const val MIUI_BOUNDARY_SPATIAL = 0x10000010

    /** 时间边界 */
    const val MIUI_BOUNDARY_TIME = 0x10000011

    /** 大按钮 */
    const val MIUI_BUTTON_LARGE = 0x10000012

    /** 中按钮 */
    const val MIUI_BUTTON_MIDDLE = 0x10000013

    /** 小按钮 */
    const val MIUI_BUTTON_SMALL = 0x10000014

    /** 轻齿轮 */
    const val MIUI_GEAR_LIGHT = 0x10000015

    /** 重齿轮 */
    const val MIUI_GEAR_HEAVY = 0x10000016

    /** 键盘。留给 T6 彩蛋答题期的输入反馈 */
    const val MIUI_KEYBOARD = 0x10000017

    /** 警示 */
    const val MIUI_ALERT = 0x10000018

    /** Z 轴开关 */
    const val MIUI_ZAXIS_SWITCH = 0x10000019
}
