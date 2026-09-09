package com.tracktosearch.ui.haptic.backend

import android.content.Context
import android.view.View
import com.tracktosearch.ui.haptic.HapticBackend
import com.tracktosearch.ui.haptic.HapticCapabilities
import com.tracktosearch.ui.haptic.HapticSemantic
import com.tracktosearch.ui.haptic.HapticStrength
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * tier 2：OPPO、一加、realme（ColorOS）的语义效果通路，全程反射。
 *
 * 调用形状照设计文档 docs/superpowers/plans/2026-09-01-haptics-overhaul.md
 * 「厂商通路矩阵 → OPPO 通路细节」那段代码，三步：`getSystemService("linearmotor")`
 * 取 `com.oplus.os.LinearmotorVibrator`、用 `com.oplus.os.WaveformEffect.Builder`
 * 链式拼一个效果、`LinearmotorVibrator.vibrate(WaveformEffect)` 派发。
 * 效果 ID 见 [OplusHapticEffects]，强度三档见 [OplusEffectStrength]，
 * 语义到「效果 ID 加强度」的映射见 [oplusEffectFor]。
 *
 * 这一层只能「选」不能「画」：ID 背后是 OPPO 自己调过的固定波形，没有振幅数组入口，
 * 所以 [playEnvelope] 恒为 false，彩蛋的连续包络得走 tier 3 或 tier 1。
 *
 * **本层没有逐效果探测。** OPPO 侧不存在 `isSupport` 这类接口 —— 既没有「这个 ID 可用吗」，
 * 也没有「这条通路可用吗」的公开判定，唯一的反馈渠道就是调用本身抛不抛错。
 * 所以 [supports] 对 13 个语义一律返回同一个答案（整层 ready 就是 true），
 * 不是漏了探测，是探不了。误差全靠 [dispatch] 的 `catch (Throwable)` 加首次失败整层禁用兜住。
 *
 * 三条红线在本层的落法：
 *
 * 1. 硬规则 1：`HapticCapabilities.lockedToConstants` 为 true（转子马达）时 [isAvailable]
 *    恒为 false，tier 2 一步都不许走。
 * 2. 硬规则 3 的等价物：不看 ColorOS 版本号、不看机型白名单。OPPO 没给逐 ID 判据，
 *    那就一个推断都不做 —— 按版本号猜可用 ID 范围是明令禁止的，猜「哪些机型能用」同理。
 * 3. 整层 `catch (Throwable)` 而非 `catch (Exception)`：类不在时抛的
 *    `NoClassDefFoundError` 是 Error。任何一步失败即整层永久禁用，不每次重试。
 *
 * 线程模型：类查找、`getSystemService` 与到 `LinearmotorVibratorService` 的 IPC 全丢给一条
 * 单线程 `Executor`（线程名 `haptic-oplus`）。单线程不只为省资源 —— FIFO 队列保派发顺序，
 * 彩蛋编排依赖顺序。[isAvailable] 与 [supports] 在主线程只读 volatile 字段，不碰反射。
 *
 * 有一个预热窗口：探测在首次 [isAvailable] 被调用时才甩给 Executor，探完之前 [supports]
 * 一律返回 false，引擎会降级到 tier 1 或 tier 0。所以引擎应在装配阶段先调一次 [isAvailable]
 * 把这层焐热，别等第一次点击 —— 那一次点击会落到下一层去。
 *
 * **`vibrate()` 是 void，而且会把失败吞掉。** 反编译的 `LinearmotorVibrator.vibrate` 在内部
 * 服务引用为 null 时只打一行 log 就 return，既不抛也不报。也就是说反射调用成功返回
 * 并不等于马达动了：这一层唯一无法自我诊断的失败模式，就是拿到了一个内部服务为空的
 * `LinearmotorVibrator`。真出现这种机器，本层会一直报 available 而全程静默，
 * 引擎也不会降级。缓解手段是 [hasLinearMotorFeature] 那道预检（见该方法注释），
 * 但根治不了，必须一台 ColorOS 真机验一次。
 *
 * 本层刻意不调两个 Builder 方法：
 *
 * - `setStrengthSettingEnabled(boolean)`：默认值是 true，意思是「这次振动仍受系统振动强度设置管」。
 *   传 false 就等于绕过用户在系统里调的强度 —— 那是红线「禁止 `FLAG_IGNORE_GLOBAL_SETTING`」
 *   在 OPPO 侧的同一件事。所以永远不碰它，让默认的 true 生效。
 * - `setUsageHint(int)`：默认值 0（未指定）。OPPO 自家的 `com.coui.appcompat.vibrateutil.VibrateUtils`
 *   在触感反馈这条路上也没调它，没有可依据的取值表，凭猜传一个数只会引入未知行为。
 *
 * 本层同样不经过 `View.performHapticFeedback`，因此系统触感总开关不会被自动尊重：
 * AOSP 在 `View.performHapticFeedback` 里做的 `isHapticFeedbackEnabled` 与
 * `Settings.System.HAPTIC_FEEDBACK_ENABLED` 判定在这条路上不存在（强度设置另算，见上）。
 * 红线「不覆盖用户的系统触感设置」因此必须由引擎在调用本层之前判；本层做不到，
 * 也不该自己去读 Settings —— 那是引擎的职责，两处各读一遍只会漂移。
 *
 * 全文没有 `Build.VERSION.SDK_INT` 门控：用到的符号全来自 ROM 侧的 `com.oplus.*`，
 * 不是 AOSP SDK 常量，存在性判定本身就是它的门控。Android 9 起有非 SDK 接口访问限制，
 * 若哪天 `com.oplus.os` 被拦，`getMethod` 抛 `NoSuchMethodException`，落到上面第 3 条整层禁用。
 *
 * 只认 `com.oplus.os` 这一个包名。ColorOS 11 及更早叫 `com.oppo.os`，另有 `com.heytap.addon.os`
 * 这层壳，本层刻意都不认 —— `HapticCapabilities.oplusSupported` 的判据也只查 `com.oplus.os`，
 * 两边多认一个就得同步改两个文件。老包名的机型会一路退到 tier 1 或 tier 0，仍然有触感。
 *
 * @param context 反射拿到的服务对象长期持有，构造时立刻取 `applicationContext`，避免泄漏 Activity
 * @param capabilities 只读三个字段（`hasVibrator`、`lockedToConstants`、`oplusSupported`），不留引用
 */
class OplusBackend(
    context: Context,
    capabilities: HapticCapabilities,
) : HapticBackend {
    /** 反射拿到的服务对象长期持有 Context，必须是 Application 级；个别宿主取不到时退回原 Context */
    private val appContext: Context = context.applicationContext ?: context

    /**
     * 本层有没有资格上场，构造期算一次，之后不变。
     *
     * 三项全取自快照，纯字段读：有马达、不是转子马达（硬规则 1）、这一层探到可用。
     * 为 false 时永不提交任何任务，[executor] 那条线程也就永远不会创建。
     */
    private val eligible: Boolean = capabilities.hasVibrator &&
        !capabilities.lockedToConstants &&
        capabilities.oplusSupported

    /**
     * 反射与 IPC 的唯一执行线程。
     *
     * `newSingleThreadExecutor` 只建对象、不预启线程，线程要到第一次 `execute` 才创建，
     * 所以非 ColorOS 机型上这里是零开销。FIFO 队列顺带保住派发顺序（彩蛋编排依赖顺序），
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

    /** 探测拿到的反射句柄；失败或 [release] 后置空。非空即「整层 ready」，[supports] 只看这个 */
    @Volatile
    private var reflection: OplusReflection? = null

    /** tier 2：厂商调好的预置效果，比 tier 1 自拼的振幅数组手感好，但画不了波形 */
    override val tier: Int = 2

    /** 单测靠这个名字断言降级矩阵最终停在了哪一层，取值固定不变 */
    override val name: String = "OPlus"

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
     * 整层 ready 就对 13 个语义一律返回 true —— [semantic] 刻意不参与判断。
     *
     * [oplusEffectFor] 是全映射（13 个语义都有落点），而 OPPO 侧没有 `isSupport` 这类接口，
     * 所以「这个效果 ID 在这台机上可用吗」这个问题在本层无从回答，返回值也就不区分语义。
     * 后人别在这里补一张手写的可用性白名单：那正是硬规则 3 禁止的按机型或版本号推断。
     *
     * 探测未完成时 [reflection] 是 null，一律 false —— 这是预热窗口的正常表现，不是错误。
     */
    override fun supports(semantic: HapticSemantic): Boolean {
        if (!isAvailable()) return false
        return reflection != null
    }

    /**
     * 派发一次语义效果。[view] 用不上：`vibrate` 挂在反射出来的 `LinearmotorVibrator` 实例上，
     * 不走 View 通道，传 null 也照样能发。
     *
     * 映射在调用线程上算（纯 `when`，零成本），只把算好的 [OplusEffectSpec] 带进队列。
     *
     * [strength] 刻意忽略：`setEffectStrength` 的合法区间没有公开文档，瞎猜偏移可能
     * 在某些机型上拼不出效果。轻/强档在这层不生效，只保语义正确——
     * 与 ext ID 通路的老 MIUI 同一取舍。
     *
     * 返回 true 只表示「已排进单线程队列」，不表示马达震了。真正的调用在 Executor 上，
     * 那边失败只能把整层禁用、让后续调用降级，这一次已经报了 true —— 反射与 IPC 不能在主线程
     * 同步做，这是接口的固有限制。所以本层靠 [supports] 在主线程侧先行拦截。
     */
    override fun perform(view: View?, semantic: HapticSemantic, strength: HapticStrength): Boolean {
        if (!supports(semantic)) return false
        val spec = oplusEffectFor(semantic)
        return submit { dispatch(spec) }
    }

    /**
     * 恒为 false。效果 ID 背后是固定预置波形，没有振幅数组入口；
     * 拿一记离散点击假装成功会让引擎的降级判断整条失效（见 [HapticBackend.playEnvelope]）。
     */
    override fun playEnvelope(timingsMs: IntArray, amplitudes: FloatArray): Boolean = false

    /**
     * 释放：禁用整层、丢掉反射句柄、shutdown 那条单线程（漏了就是线程泄漏）。
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
     * @return true 已排队；false 排不进去，调用方按失败处理
     */
    private fun submit(task: () -> Unit): Boolean = try {
        executor.execute(task)
        true
    } catch (_: Throwable) {
        disabled = true
        false
    }

    /**
     * 在 Executor 线程上跑一次的完整探测：特性预检 → 取服务 → 类查找 → 缓存 `Method`。
     *
     * 四个必需成员缺一个就整层禁用：`WaveformEffect.Builder` 的无参构造、`setEffectType(int)`、
     * `build()`，加上 `LinearmotorVibrator.vibrate(WaveformEffect)`。少了任何一个都拼不出效果。
     * 另外三个 setter 当可选处理（见 [optionalMethod]）：ColorOS 各代 Builder 的字段数并不一致，
     * 已知有只剩 `setEffectType` 与 `setEffectLoop` 的精简实现，缺了强度或异步标记仍然能震，
     * 为此把整层判死是过度反应。
     *
     * `Method` 全部缓存在 [OplusReflection] 里，之后每次派发直接 `invoke`，不重复 `getMethod`。
     * 整体一次性替换 [reflection] 而不逐字段赋值，主线程侧读到的要么是 null 要么是配套的一整套。
     */
    private fun probe() {
        if (disabled) return
        try {
            if (!hasLinearMotorFeature()) {
                markDisabled()
                return
            }
            val service = appContext.getSystemService(OPLUS_LINEAR_MOTOR_SERVICE)
            if (service == null) {
                markDisabled()
                return
            }
            reflection = buildReflection(service)
        } catch (_: Throwable) {
            markDisabled()
        }
    }

    /**
     * 查齐一套反射句柄。抛出即代表本层不可用，由 [probe] 统一吃掉。
     *
     * 类查找传 `initialize = false`：只要 `Class` 对象用来匹配 `getMethod` 的形参与建 Builder，
     * 不必在这一步触发对方的静态初始化。用 [appContext] 的 ClassLoader ——
     * `com.oplus.os` 在 framework 里，由 boot ClassLoader 加载，是应用 ClassLoader 的父级。
     */
    private fun buildReflection(service: Any): OplusReflection {
        val loader = appContext.classLoader
        val effectClass = Class.forName(OPLUS_WAVEFORM_EFFECT, false, loader)
        val builderClass = Class.forName(OPLUS_WAVEFORM_EFFECT_BUILDER, false, loader)
        return OplusReflection(
            vibrator = service,
            vibrate = service.javaClass.getMethod(METHOD_VIBRATE, effectClass),
            builderCtor = builderClass.getConstructor(),
            setEffectType = builderClass.getMethod(METHOD_SET_EFFECT_TYPE, Int::class.javaPrimitiveType),
            build = builderClass.getMethod(METHOD_BUILD),
            setEffectStrength = optionalMethod(builderClass, METHOD_SET_EFFECT_STRENGTH, Int::class.javaPrimitiveType),
            setEffectLoop = optionalMethod(builderClass, METHOD_SET_EFFECT_LOOP, Boolean::class.javaPrimitiveType),
            setAsynchronous = optionalMethod(builderClass, METHOD_SET_ASYNCHRONOUS, Boolean::class.javaPrimitiveType),
        )
    }

    /** 可选 setter：查不到返回 null，[dispatch] 跳过这一步而不是判死整层 */
    private fun optionalMethod(owner: Class<*>, name: String, paramType: Class<*>?): Method? = try {
        owner.getMethod(name, paramType)
    } catch (_: Throwable) {
        null
    }

    /**
     * OPPO 自家的通路预检：`OplusFeatureConfigManager.hasFeature("oplus.software.vibrator_luxunvibrator")`。
     *
     * 这一步**超出设计文档**，是从 OPPO 自家支持库 `com.coui.appcompat.vibrateutil.VibrateUtils`
     * 的反编译实现里抄来的形状 —— 它在 `getSystemService("linearmotor")` 之前先问这个特性开关，
     * 不成立就直接返回 null。加它的理由只有一个：`vibrate()` 是 void 且吞掉失败（见类注释），
     * 所以这是本层唯一能在「静默不震」发生之前拿到的信号。
     *
     * **拿不到判据时一律放行。** 类不在（老 ColorOS 叫 `com.color.content.ColorFeatureConfigManager`）、
     * 单例为 null、返回值不是 Boolean —— 三种情况都返回 true，交给后面的取服务与类查找去判。
     * 只有明确返回 false 才拦，那正是 OPPO 自己的判断。
     */
    private fun hasLinearMotorFeature(): Boolean = try {
        val clazz = Class.forName(OPLUS_FEATURE_CONFIG_MANAGER)
        val manager = clazz.getMethod(METHOD_GET_INSTANCE).invoke(null)
        if (manager == null) {
            true
        } else {
            clazz.getMethod(METHOD_HAS_FEATURE, String::class.java)
                .invoke(manager, OPLUS_FEATURE_LINEAR_MOTOR) as? Boolean ?: true
        }
    } catch (_: Throwable) {
        true
    }

    /**
     * 在 Executor 线程上真正拼一个 `WaveformEffect` 并派发。
     *
     * Builder 是链式的，每个 setter 都 `return this`（已对反编译源码逐个核过），
     * 但这里仍然接住返回值再喂给下一步 —— 消费一个 fluent API 就该按它的契约走，
     * 万一某代 ROM 返回的是新实例，忽略返回值会拼出一个空效果，而这属于静默失败。
     *
     * 顺序照设计文档「OPPO 通路细节」的代码：先 `setEffectType`，再强度、循环标记、异步标记。
     * `build()` 返回 null 视为 ROM 侧行为异常，一并整层禁用 —— 拿 null 去调 `vibrate`
     * 只会被对方静静丢掉（反编译源码里 `effect == null` 直接 return），不如就此降级。
     *
     * 首次抛错即整层禁用，不每次重试：ROM 侧一旦不认这个调用，重试只是重复付反射与 IPC 的钱。
     */
    private fun dispatch(spec: OplusEffectSpec) {
        if (disabled) return
        val state = reflection ?: return
        try {
            var builder: Any = state.builderCtor.newInstance()
            builder = state.setEffectType.invoke(builder, spec.effectType) ?: builder
            state.setEffectStrength?.let { builder = it.invoke(builder, spec.strength) ?: builder }
            state.setEffectLoop?.let { builder = it.invoke(builder, EFFECT_LOOP) ?: builder }
            state.setAsynchronous?.let { builder = it.invoke(builder, EFFECT_ASYNCHRONOUS) ?: builder }
            val effect = state.build.invoke(builder)
            if (effect == null) {
                markDisabled()
                return
            }
            state.vibrate.invoke(state.vibrator, effect)
        } catch (_: Throwable) {
            markDisabled()
        }
    }

    /** 整层禁用并丢掉句柄。刻意不 shutdown [executor] —— 那是 [release] 的活 */
    private fun markDisabled() {
        disabled = true
        reflection = null
    }

    /**
     * 探测拿到的一整套反射句柄；整体替换而不逐字段改，读到的必然是配套的实例与方法。
     *
     * 前五个是必需的，缺一个就拼不出效果；后三个是可选的，为 null 时 [dispatch] 跳过那一步。
     */
    private class OplusReflection(
        val vibrator: Any,
        val vibrate: Method,
        val builderCtor: Constructor<*>,
        val setEffectType: Method,
        val build: Method,
        val setEffectStrength: Method?,
        val setEffectLoop: Method?,
        val setAsynchronous: Method?,
    )

    private companion object {
        /** ColorOS 线性马达的系统服务名，`getSystemService` 只认这个字符串；设计文档「OPPO 通路细节」 */
        const val OPLUS_LINEAR_MOTOR_SERVICE = "linearmotor"

        /** 效果载体，`vibrate` 的形参类型。不在 SDK 里，只有 ColorOS 的 framework 上有 */
        const val OPLUS_WAVEFORM_EFFECT = "com.oplus.os.WaveformEffect"

        /** 上面那个类的静态嵌套 Builder，`$` 是 JVM 内部类分隔符，不是 Kotlin 模板 */
        const val OPLUS_WAVEFORM_EFFECT_BUILDER = "com.oplus.os.WaveformEffect\$Builder"

        /** ColorOS 特性开关中心，只用来做 [hasLinearMotorFeature] 那道预检 */
        const val OPLUS_FEATURE_CONFIG_MANAGER = "com.oplus.content.OplusFeatureConfigManager"

        /** 「这台机有富触感线性马达」的特性名，取自 OPPO 自家 `VibrateUtils` 的反编译实现 */
        const val OPLUS_FEATURE_LINEAR_MOTOR = "oplus.software.vibrator_luxunvibrator"

        /** `LinearmotorVibrator.vibrate(WaveformEffect)`，返回 void */
        const val METHOD_VIBRATE = "vibrate"

        /** `Builder.build()`，返回 `WaveformEffect` */
        const val METHOD_BUILD = "build"

        /** `Builder.setEffectType(int)`，必需 —— 不设效果 ID 就只剩默认的 -1（无效） */
        const val METHOD_SET_EFFECT_TYPE = "setEffectType"

        /** `Builder.setEffectStrength(int)`，可选，取值见 [OplusEffectStrength] */
        const val METHOD_SET_EFFECT_STRENGTH = "setEffectStrength"

        /** `Builder.setEffectLoop(boolean)`，可选 */
        const val METHOD_SET_EFFECT_LOOP = "setEffectLoop"

        /** `Builder.setAsynchronous(boolean)`，可选 */
        const val METHOD_SET_ASYNCHRONOUS = "setAsynchronous"

        /** `OplusFeatureConfigManager.getInstance()`，静态无参 */
        const val METHOD_GET_INSTANCE = "getInstance"

        /** `OplusFeatureConfigManager.hasFeature(String)`，实例方法 */
        const val METHOD_HAS_FEATURE = "hasFeature"

        /**
         * `setEffectLoop` 的实参，恒 false。
         *
         * Builder 默认也是 false，这里显式传一遍：循环振动没有停止通道
         * （[HapticBackend] 没有 cancel 类方法），一旦某代 ROM 把默认值改成 true 就停不下来。
         */
        const val EFFECT_LOOP = false

        /**
         * `setAsynchronous` 的实参，恒 true。
         *
         * 照设计文档「OPPO 通路细节」的代码传 true：异步派发，`vibrate` 不等马达跑完就返回。
         * 本层已经在单线程 Executor 上了，同步等待只会把后面排队的触感一起拖慢。
         */
        const val EFFECT_ASYNCHRONOUS = true

        /** 单线程的线程名，带得出来源，便于在 systrace 与 ANR 栈里认出这条队列 */
        const val THREAD_NAME = "haptic-oplus"
    }
}

/**
 * 一次 OPPO 派发要的全部参数：一个效果 ID 加一个强度档。
 *
 * 拆成值对象而不是返回两个 Int，是为了让 [oplusEffectFor] 单测能一次断言到底
 * （`assertEquals(OplusEffectSpec(2, 1), oplusEffectFor(TAP))`），
 * 也避免调用点把两个 Int 的顺序传反 —— 那种错编译期发现不了。
 *
 * @param effectType `WaveformEffect.Builder.setEffectType` 的实参，取值见 [OplusHapticEffects]
 * @param strength `WaveformEffect.Builder.setEffectStrength` 的实参，取值见 [OplusEffectStrength]
 */
internal data class OplusEffectSpec(
    val effectType: Int,
    val strength: Int,
)

/**
 * 语义 → OPPO「效果 ID 加强度档」。本层唯一可单测的部分：不碰 Context、不碰反射，纯输入输出。
 *
 * 效果 ID 照设计文档 docs/superpowers/plans/2026-09-01-haptics-overhaul.md
 * 「厂商通路矩阵 → OPPO 通路细节」末尾那段「语义词表接到 OPPO 的映射」逐条抄，
 * 12 条钉死的映射一个都没改。第 13 条 [HapticSemantic.POPUP_SHOW] 设计文档没给，
 * 这里定为与 [HapticSemantic.LIGHT_TAP] 同一个效果，理由见该分支的注释。
 *
 * 强度档是设计文档没规定的一维，规则是「效果 ID 定性格，强度档定音量」：
 * 按 [HapticSemantic] 类注释里那条由轻到重的梯度分成三段 —— 连发与次级交互给
 * [OplusEffectStrength.LIGHT]，通用点击与状态提示给 [OplusEffectStrength.MEDIUM]，
 * 只有「成功」和「抓住了」这两笔给 [OplusEffectStrength.STRONG]。
 * 这不是在 `HapticMode.BOOST` 之外偷偷叠一层缩放：`setStrengthSettingEnabled` 保持默认 true，
 * 用户在系统里调的振动强度照旧生效，档位只是三个预置效果变体之间的选择。
 *
 * `when` 穷举、不写 `else`：往 [HapticSemantic] 加语义时编译器会逼着补映射。
 *
 * 返回的是「该发什么」，不是「发得出来吗」—— 后者在 OPPO 侧根本无从判断，
 * 见 [OplusBackend.supports]。所以本函数是全映射，13 个语义都有落点，没有 null。
 */
internal fun oplusEffectFor(semantic: HapticSemantic): OplusEffectSpec = when (semantic) {
    // 2 中等短振一次：通用点击的顶档，实心的一击
    HapticSemantic.TAP ->
        OplusEffectSpec(OplusHapticEffects.EFFECT_MODERATE_SHORT_VIBRATE_ONCE, OplusEffectStrength.MEDIUM)
    // 1 弱短振一次：比 TAP 轻一档，给一屏里反复出现的列表项
    HapticSemantic.LIGHT_TAP ->
        OplusEffectSpec(OplusHapticEffects.EFFECT_WEAK_SHORT_VIBRATE_ONCE, OplusEffectStrength.LIGHT)
    // 0 最弱短振一次：Tab、单选、分段控件的刻度感，三档短振里最轻的那个
    HapticSemantic.SEGMENT_TICK ->
        OplusEffectSpec(OplusHapticEffects.EFFECT_WEAKEST_SHORT_VIBRATE_ONCE, OplusEffectStrength.LIGHT)
    // 68 弱颗粒感：OPPO 给连续刻度专门做的效果，一次手势里几十下也不吵
    HapticSemantic.FREQUENT_TICK ->
        OplusEffectSpec(OplusHapticEffects.EFFECT_CUSTOMIZED_WEAK_GRANULAR, OplusEffectStrength.LIGHT)
    // 2 中等短振一次：开关族里较重的一半，状态「立起来」，与 TAP 同效果同档
    HapticSemantic.TOGGLE_ON ->
        OplusEffectSpec(OplusHapticEffects.EFFECT_MODERATE_SHORT_VIBRATE_ONCE, OplusEffectStrength.MEDIUM)
    // 1 弱短振一次：关比开轻，状态「落回去」
    HapticSemantic.TOGGLE_OFF ->
        OplusEffectSpec(OplusHapticEffects.EFFECT_WEAK_SHORT_VIBRATE_ONCE, OplusEffectStrength.LIGHT)
    // 3 中等短振两次：成功那一笔，两下连击本身就是「办成了」的语法，配最重的档
    HapticSemantic.CONFIRM ->
        OplusEffectSpec(OplusHapticEffects.EFFECT_MODERATE_SHORT_VIBRATE_TWICE, OplusEffectStrength.STRONG)
    // 9 大幅度：失败语义。刻意只给 MEDIUM —— 这已经是低号段里幅度最大的效果，
    // 再叠 STRONG 就成了语义词表明确要避免的「重到惊吓」
    HapticSemantic.REJECT ->
        OplusEffectSpec(OplusHapticEffects.EFFECT_OTHER_BIG_SCALE, OplusEffectStrength.MEDIUM)
    // 49 立体触感：起手那记沉的「抓住了」，要抓得住所以给 STRONG
    HapticSemantic.DRAG_START ->
        OplusEffectSpec(OplusHapticEffects.EFFECT_CUSTOMIZED_THREE_DIMENSION_TOUCH, OplusEffectStrength.STRONG)
    // 50 扩散：到阈值的上冲感，「可以松手了」
    HapticSemantic.THRESHOLD_ARMED ->
        OplusEffectSpec(OplusHapticEffects.EFFECT_CUSTOMIZED_SPREAD_OUT, OplusEffectStrength.MEDIUM)
    // 73 吸附到中位：手势落定、面板 snap，OPPO 就是给这个场景做的
    HapticSemantic.GESTURE_END ->
        OplusEffectSpec(OplusHapticEffects.EFFECT_CUSTOMIZED_ATTACH_TO_MIDDLE, OplusEffectStrength.LIGHT)
    // 154 滑条到边界：撞墙的一记，且会被反复顶到，所以最轻
    HapticSemantic.SCROLL_EDGE ->
        OplusEffectSpec(OplusHapticEffects.EFFECT_OTHER_STRENGTH_LEVEL_BAR_EDGE, OplusEffectStrength.LIGHT)
    // 1 弱短振一次：设计文档的 OPPO 映射漏了这一条。取与 LIGHT_TAP 相同的落点 ——
    // 语义词表里 popupShow 在 tier 3、tier 1、tier 0 三层用的都跟 lightTap 是同一个效果，
    // 本层跟着一致最省事，也不至于凭猜挑一个没人听过的效果。
    // 若真机上觉得弹窗该有自己的性格，74 呼吸式扩散是第一候选，改这一行即可。
    HapticSemantic.POPUP_SHOW ->
        OplusEffectSpec(OplusHapticEffects.EFFECT_WEAK_SHORT_VIBRATE_ONCE, OplusEffectStrength.LIGHT)
}

/**
 * `WaveformEffect.setEffectStrength` 的三个强度档。
 *
 * 取值照设计文档 docs/superpowers/plans/2026-09-01-haptics-overhaul.md
 * 「厂商通路矩阵 → OPPO 通路细节」那段注释：0 LIGHT、1 MEDIUM、2 STRONG，
 * 与反编译的 `com.oplus.os.WaveformEffect` 字段表一致。
 *
 * Builder 侧的实现细节：`setEffectStrength` 只接受 ≤ 2400 的值，超了自己改成 -1；
 * -1 是「不指定强度」的默认值，含义是完全交给系统设置。本层永远传 0 / 1 / 2 里的一个，
 * 不会走到那条边界上。
 */
internal object OplusEffectStrength {
    /** 轻 */
    const val LIGHT = 0

    /** 中 */
    const val MEDIUM = 1

    /** 重 */
    const val STRONG = 2
}

/**
 * `com.oplus.os.WaveformEffect` 的效果 ID，只收交互相关的低号段。
 *
 * 名字与取值照设计文档 docs/superpowers/plans/2026-09-01-haptics-overhaul.md
 * 「厂商通路矩阵 → OPPO 通路细节」那张表逐条抄，OPPO 原始的 `EFFECT_` 前缀保留，
 * 方便跟反编译字段表直接对照。
 *
 * ColorOS 一共有 313 个以上的效果，这里刻意只列 17 个：其余绝大多数是铃声、闹钟、通知音、
 * 游戏击杀音效（`EFFECT_ALARM_*`、`EFFECT_RINGTONE_*`、`EFFECT_AFGAME_*` 这些号段），
 * 跟 UI 触感一点关系都没有，全抄进来只会让人以为它们都是候选。
 *
 * 17 个里当前被 [oplusEffectFor] 用到 9 个。剩下 8 个留着有各自的理由：
 * [EFFECT_WEAK_EMULATION_KEYBOARD_DOWN] 与 [EFFECT_WEAK_EMULATION_KEYBOARD_UP] 是彩蛋答题期
 * 键盘要用的（设计文档「彩蛋编排」，T6 的活；语义词表里没有键盘语义，所以本层不给入口）；
 * 其余几个是设计文档点名过的近邻候选，取值已经核对过一遍，留着比日后再查一遍便宜。
 */
internal object OplusHapticEffects {
    /** 0 最弱短振一次：三档轻重点击里最轻的 */
    const val EFFECT_WEAKEST_SHORT_VIBRATE_ONCE = 0

    /** 1 弱短振一次：三档里的中间档 */
    const val EFFECT_WEAK_SHORT_VIBRATE_ONCE = 1

    /** 2 中等短振一次：三档里最重的 */
    const val EFFECT_MODERATE_SHORT_VIBRATE_ONCE = 2

    /**
     * 3 中等短振两次：双击确认。
     *
     * 反编译源码里 `EFFECT_MODERATE_SHORT_VIBRATE_TWICE` 与
     * `EFFECT_MODERATE_SHORT_VIBRATE_TRIPLE` 是同一个值 3，OPPO 自己也没区分开，
     * 所以真机上到底震两下还是三下未验证。
     */
    const val EFFECT_MODERATE_SHORT_VIBRATE_TWICE = 3

    /** 9 大幅度：低号段里幅度最大的通用效果 */
    const val EFFECT_OTHER_BIG_SCALE = 9

    /** 10 小幅度：与 9 成对的小幅度版本，当前没有语义用到 */
    const val EFFECT_OTHER_SMALL_SCALE = 10

    /** 49 立体触感：有纵深感的一记，拖拽起手用 */
    const val EFFECT_CUSTOMIZED_THREE_DIMENSION_TOUCH = 49

    /** 50 扩散：向外铺开的上冲感，到阈值用 */
    const val EFFECT_CUSTOMIZED_SPREAD_OUT = 50

    /** 51 收敛：与 50 反向的向内收，当前没有语义用到 */
    const val EFFECT_CUSTOMIZED_CONVERGE = 51

    /** 68 弱颗粒感：OPPO 给连续刻度专门做的效果，滑块与逐珠划过用 */
    const val EFFECT_CUSTOMIZED_WEAK_GRANULAR = 68

    /** 69 强颗粒感：68 的重版本，当前没有语义用到 */
    const val EFFECT_CUSTOMIZED_STRONG_GRANULAR = 69

    /** 73 吸附到中位：面板 snap、手势落定用 */
    const val EFFECT_CUSTOMIZED_ATTACH_TO_MIDDLE = 73

    /** 74 呼吸式扩散：50 的慢版本，弹窗出现的备选落点（见 [oplusEffectFor] 的 POPUP_SHOW 分支） */
    const val EFFECT_CUSTOMIZED_BREATHE_SPREAD_OUT = 74

    /** 154 强度滑条到边界：撞墙的一记轻响 */
    const val EFFECT_OTHER_STRENGTH_LEVEL_BAR_EDGE = 154

    /** 304 弱模拟键盘按下：彩蛋答题期键盘用，T6 的活 */
    const val EFFECT_WEAK_EMULATION_KEYBOARD_DOWN = 304

    /** 305 弱模拟键盘抬起：与 304 成对 */
    const val EFFECT_WEAK_EMULATION_KEYBOARD_UP = 305

    /** 408 滚轮选择器：时间选择器那种连续滚动，当前没有语义用到 */
    const val EFFECT_SCROLL_ON_TIME_PICKER = 408
}
