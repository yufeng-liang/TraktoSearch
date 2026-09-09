package com.tracktosearch.ui.haptic.backend

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
 * tier 2：华为（EMUI 10+ / HarmonyOS 2-4，AOSP 底）的语义效果通路，全程反射。
 *
 * ### 通路从哪来（2026-09-09 反编译 `com.huawei.devices:hapticskit:1.0.0.400` 核对）
 *
 * 华为把线性马达能力以 Haptics Kit 开放，SDK 内部只有三步：
 * `new VibratorEx()`（无参构造）→ `isSupportHwVibrator(键)` 逐效果探测 →
 * `setHwVibrator(键)` 派发，效果键是 `haptic.<场景>.<动作>` 格式的字符串
 * （见 [HuaweiHapticEffects]）。真类 `com.huawei.android.os.VibratorEx` 在华为
 * framework 的 boot classpath 上；aar 里那个同名类是**空壳 stub**（三个方法全空实现），
 * 只负责让三方 App 编译期有符号可引用——类加载是 parent-first 的，华为设备上
 * `Class.forName` 解析到的是 framework 真类，非华为设备上解析不到、抛
 * `ClassNotFoundException`，本层自然出局。
 *
 * ### 为什么不用官方 SDK
 *
 * aar 里除了这三个方法还捆着一整套 `com.huawei.hianalytics` 埋点（`HapticsKit`
 * 构造就会初始化上报线程），且要给工程加华为 maven 仓库。我们只需要三个反射调用，
 * 纯反射既零依赖也不带埋点。代价是没有 SDK 的机型白名单兜底——那个白名单
 * （`LIO-.*` / `NLE-.*`）本来就违反硬规则 3「不按机型推断」，我们只信
 * `isSupportHwVibrator` 的逐键事实。
 *
 * ### 覆盖范围
 *
 * - EMUI 10/11（2019-2021 华为机）与 HarmonyOS 2-4（还能装 APK 的华为机）。
 * - HarmonyOS NEXT 不装 Android APK，与本层无关。
 * - 2020 年前后的老荣耀（华为子品牌时代，同一套 framework）大概率也认这套键，
 *   探测通过即白赚，探测不过自动出局。
 *
 * ### 与 MIUI / OPlus 两层的同与不同
 *
 * 同：只能「选」不能「画」，[playEnvelope] 恒为 false，彩蛋包络走 tier 3 或 tier 1；
 * 三条红线（转子马达锁 tier 0、不按机型版本推断、整层 `catch (Throwable)` 首败即禁用）
 * 的落法与 [OplusBackend] 一致。
 *
 * 不同：华为给了 `isSupportHwVibrator(键)` 这个**逐效果探测接口**（三家里唯一），
 * 所以 [supports] 按键逐个回答，探测期对 13 个语义要用的键各问一次并缓存——
 * 不像 OPlus 只能「整层 ready 就一律 true」。
 *
 * ### 未经真机验证的防御
 *
 * 手边只有小米 14 Pro，本层写完时没有任何华为真机过过手。防线有三道：
 * `HapticCapabilities.huaweiSupported` 的类存在性门控、逐键 `isSupportHwVibrator`
 * 缓存（不认的键 [supports] 返 false，引擎降级 tier 1 / tier 0，不静默丢事件）、
 * 首次反射失败整层禁用。调用形状逐字节核对过官方 SDK 的反汇编（`a.class` 的
 * `setParameter` 就是 isSupport 通过才 set），语法层面的风险已排干净；
 * 剩下的只有「键存在但手感与预期场景不贴」——那要第一台华为真机说了算，
 * 调整只需改 [huaweiEffectFor] 一张表。
 *
 * ### 线程模型
 *
 * 类查找、实例化与两次 binder 调用全丢给一条单线程 `Executor`（线程名
 * `haptic-huawei`），FIFO 保派发顺序（彩蛋编排依赖顺序）。[isAvailable] 与
 * [supports] 在主线程只读 volatile 字段。预热窗口与 [MiuiBackend] / [OplusBackend]
 * 相同：引擎装配阶段先调一次 [isAvailable] 焐热，别让第一次点击落在降级层上。
 *
 * [strength] 与 MIUI ext 通路、OPlus 同一取舍：华为效果键没有强度参数
 * （`haptic.grade.strength1-5` 是键盘专用档），轻/强档在这层不生效，只保语义正确。
 *
 * @param capabilities 只读三个字段（`hasVibrator`、`lockedToConstants`、`huaweiSupported`），不留引用
 */
class HuaweiBackend(
    capabilities: HapticCapabilities,
) : HapticBackend {
    /**
     * 本层有没有资格上场，构造期算一次，之后不变。
     *
     * 三项全取自快照，纯字段读：有马达、不是转子马达（硬规则 1）、这一层探到可用。
     * 为 false 时永不提交任何任务，[executor] 那条线程也就永远不会创建。
     */
    private val eligible: Boolean = capabilities.hasVibrator &&
        !capabilities.lockedToConstants &&
        capabilities.huaweiSupported

    /**
     * 反射与 IPC 的唯一执行线程。
     *
     * `newSingleThreadExecutor` 只建对象、不预启线程，线程要到第一次 `execute` 才创建，
     * 所以非华为机型上这里是零开销。FIFO 队列顺带保住派发顺序（彩蛋编排依赖顺序），
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
    private var reflection: HuaweiReflection? = null

    /**
     * 逐键探测的结果缓存（13 个语义用到的键，去重后逐一问过 `isSupportHwVibrator`）。
     *
     * 探完才整体替换，主线程侧看到的要么是空集要么是完整集，不会读到半张。
     * 空集且未禁用 = 探测还在路上，[supports] 全 false，引擎降级——正常预热窗口。
     */
    @Volatile
    private var supportedEffects: Set<String> = emptySet()

    /** tier 2：厂商调好的预置效果，比 tier 1 自拼的振幅数组手感好，但画不了波形 */
    override val tier: Int = 2

    /** 单测靠这个名字断言降级矩阵最终停在了哪一层，取值固定不变 */
    override val name: String = "Huawei"

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
     * 这个语义的键本机认不认。按 [huaweiEffectFor] 算出键、查 [supportedEffects] 缓存。
     *
     * 缓存是空的两种含义由 [disabled] 分开：探测未完成（预热窗口，引擎降级）与
     * 探测完成但一个键都不认（那台华为机没给三方开放任何效果，同样降级）。
     * 语义参与判断是华为层的特权——MIUI 的 String 键与 OPPO 的效果 ID 都没有
     * 逐效果探测接口，只有华为给了 `isSupportHwVibrator`。
     */
    override fun supports(semantic: HapticSemantic): Boolean {
        if (!isAvailable()) return false
        return huaweiEffectFor(semantic) in supportedEffects
    }

    /**
     * 派发一次语义效果。[view] 用不上：`setHwVibrator` 挂在反射出来的 `VibratorEx`
     * 实例上，不走 View 通道，传 null 也照样能发。
     *
     * 映射在调用线程上算（纯 `when`，零成本），只把算好的键带进队列。
     * [strength] 刻意忽略，见类注释。
     *
     * 返回 true 只表示「已排进单线程队列」，不表示马达震了。真正的调用在 Executor 上，
     * 那边失败只能把整层禁用、让后续调用降级，这一次已经报了 true —— 反射与 IPC
     * 不能在主线程同步做，这是接口的固有限制。所以本层靠 [supports] 在主线程侧先行拦截。
     */
    override fun perform(view: View?, semantic: HapticSemantic, strength: HapticStrength): Boolean {
        if (!supports(semantic)) return false
        val effect = huaweiEffectFor(semantic)
        return submit { dispatch(effect) }
    }

    /**
     * 恒为 false。效果键背后是华为调好的固定预置波形，没有振幅数组入口；
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
     * 在 Executor 线程上跑一次的完整探测：类查找 → 无参构造实例 → 解析两个方法 →
     * 对 13 个语义要用的键逐个 `isSupportHwVibrator` 并缓存。
     *
     * 任何一步抛错即整层禁用。实例化与逐键探测都放这里而不是
     * `HapticCapabilities.probe`：那边跑在启动预热的 IO 线程上，本层自己的 binder
     * 往返（13 次 isSupport）不该让所有机型陪着付，非华为机更是一步都不会进来。
     */
    private fun probe() {
        if (disabled) return
        try {
            val clazz = Class.forName(HUAWEI_VIBRATOR_EX)
            val instance = clazz.getConstructor().newInstance()
            val isSupport = clazz.getMethod(METHOD_IS_SUPPORT, String::class.java)
            val setHwVibrator = clazz.getMethod(METHOD_SET_HW_VIBRATOR, String::class.java)
            val supported = HapticSemantic.entries
                .map { huaweiEffectFor(it) }
                .toSet()
                .filterTo(HashSet()) { key ->
                    isSupport.invoke(instance, key) as? Boolean ?: false
                }
            reflection = HuaweiReflection(instance, setHwVibrator)
            supportedEffects = supported
        } catch (_: Throwable) {
            markDisabled()
        }
    }

    /**
     * 在 Executor 线程上真正派发一记。键在 [supportedEffects] 里才可能走到这，
     * 所以这里不再重复 isSupport——探测到派发之间 ROM 不会撤效果。
     *
     * `setHwVibrator` 返回 void，`invoke` 的返回值是 null；调用成功与否只能靠
     * 抛不抛错判断。首次抛错即整层禁用，不每次重试：ROM 侧一旦不认这个调用，
     * 重试只是重复付反射与 IPC 的钱。
     */
    private fun dispatch(effect: String) {
        if (disabled) return
        val state = reflection ?: return
        try {
            state.setHwVibrator.invoke(state.instance, effect)
        } catch (_: Throwable) {
            markDisabled()
        }
    }

    /** 整层禁用并丢掉句柄。刻意不 shutdown [executor] —— 那是 [release] 的活 */
    private fun markDisabled() {
        disabled = true
        reflection = null
        supportedEffects = emptySet()
    }

    /**
     * 探测拿到的一整套反射句柄；整体替换而不逐字段改，读到的必然是配套的实例与方法。
     *
     * `isSupportHwVibrator` 只在 [probe] 里用，不进这个快照——派发路径不需要它。
     */
    private class HuaweiReflection(
        val instance: Any,
        val setHwVibrator: Method,
    )

    private companion object {
        /** 华为 framework 的线性马达扩展类，boot classpath 上；aar 里的同名 stub 不会被解析到 */
        const val HUAWEI_VIBRATOR_EX = "com.huawei.android.os.VibratorEx"

        /** `VibratorEx.isSupportHwVibrator(String): boolean`，探测期逐键问 */
        const val METHOD_IS_SUPPORT = "isSupportHwVibrator"

        /** `VibratorEx.setHwVibrator(String): void`，派发 */
        const val METHOD_SET_HW_VIBRATOR = "setHwVibrator"

        /** 单线程的线程名，带得出来源，便于在 systrace 与 ANR 栈里认出这条队列 */
        const val THREAD_NAME = "haptic-huawei"
    }
}
