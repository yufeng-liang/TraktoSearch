package com.tracktosearch.ui.haptic

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.apprichtap.haptic.RichTapUtils

/**
 * 一台设备的触感能力快照：四层引擎靠它决定哪几层能上场。
 *
 * 设备能力在运行期不变，所以整个进程只需 [probe] 一次，结果由调用方（引擎）持有 ——
 * 本类自己不缓存。十一个字段的名字与顺序是与各 backend 的契约，构造时全部具名传入，
 * backend 侧也按名取用：十一个 Boolean / Int 挤在一起，位置传参改一次顺序就是一场静默事故。
 *
 * 探测只描述事实，不含策略。唯一的策略是 [lockedToConstants]：无振幅控制的转子马达锁 tier 0。
 * 「哪个语义走哪一层」由各 backend 的 `supports` 结合本快照判断，本类不掺和；
 * 同理，这里探到 primitive 可用不等于该用 —— 转子马达上仍然只许 tier 0 上场。
 *
 * 目标机实测（小米 14 Pro / HyperOS 3 / Android 16，2026-09-01）：
 * [hasAmplitudeControl] 为 true、[supportedPrimitives] 为空集、[compositionSizeMax] 为 0、
 * [envelopeSupported] 为 false、[miuiSupported] 为 true。这台机上 `Composition` 与 AOSP 包络
 * 两条「细腻」通路都不可用，只剩 tier 3、tier 2、tier 1 的振幅波形与 tier 0。
 * 但 Pixel 上这两条是可用的，所以相关探测代码必须留着，不能因为目标机测出 0 就删掉。
 *
 * 设计依据见 docs/superpowers/plans/2026-09-01-haptics-overhaul.md 的
 * 「四层引擎」与「目标机实测」两节。
 */
data class HapticCapabilities(
    /**
     * 本机有没有马达。取自 `Vibrator.hasVibrator()`（API 1）。
     *
     * `Vibrator` 实例的取法本身分版本：API 31 起走
     * `getSystemService(VIBRATOR_MANAGER_SERVICE)` 再取 `VibratorManager.getDefaultVibrator()`，
     * 以下走已废弃的 `getSystemService(VIBRATOR_SERVICE)`。
     *
     * 为 false 时其余字段一律是保守值（false / 空集 / 0）：没有马达，四层里哪一层都发不出东西。
     */
    val hasVibrator: Boolean,
    /**
     * 能不能按振幅驱动。取自 `Vibrator.hasAmplitudeControl()`（API 26，正好等于 minSdk，无需门控）。
     *
     * 这是全套能力里最要紧的一位：为 false 说明是转子马达，所有非零振幅都会被抬到 100%，
     * 「细腻」全部失真成一声嗡 —— 于是 [lockedToConstants] 直接锁 tier 0。
     */
    val hasAmplitudeControl: Boolean,
    /**
     * `VibrationEffect.Composition` 的 `PRIMITIVE_` 系列里本机支持的那些，存 ID。
     *
     * API 30 起才有查询接口，低版本恒为空集。primitive 的常量本身也是分批引入的：
     * CLICK、TICK、QUICK_RISE、SLOW_RISE、QUICK_FALL 是 API 30，
     * THUD、SPIN、LOW_TICK 是 API 31，各自门控后才收进候选表。
     *
     * 空集意味着 tier 1 的 Composition 通路整条不可用（目标机就是空集），只能退到振幅波形或 tier 0。
     * 用这张表判定某个效果能不能播时必须**整组**看：一个效果用到的 primitive 缺一个，
     * 整段 Composition 一点都不震，属静默失败。
     */
    val supportedPrimitives: Set<Int>,
    /**
     * 一段 `Composition` 最多能塞几个 primitive。**≤ 0 视为 Composition 不可用。**
     *
     * AOSP 没把这个查询开放给应用：`Vibrator.getCompositionSizeMax()` 直到 API 37 的
     * android.jar 里都不存在（整个 SDK 搜不到这个符号），只能反射拿，拿不到就退到
     * 一个保守下限（见 [probe] 的说明）。所以这个值的含义是「至少能塞几个」，
     * 不是「厂商声称能塞几个」，超过它就别拼了。
     *
     * [supportedPrimitives] 为空时直接为 0：没有可用的 primitive，容量多大都拼不出效果。
     */
    val compositionSizeMax: Int,
    /**
     * 支不支持 AOSP 的 PWLE 包络效果。取自 `Vibrator.areEnvelopeEffectsSupported()`，
     * API 36（`BAKLAVA`）才有，以下恒为 false。
     *
     * 目标机实测为 false（`pwleSizeMax` 与 `mMaxEnvelopeEffectSize` 都是 0，
     * 且 `frequencyProfile` 全 NaN，连频率区间都不暴露）。Pixel 支持，所以这条要留。
     * 彩蛋那两段连续包络因此不能只指望 AOSP 包络：小米这边得走 RichTap 或 tier 1 的振幅数组。
     */
    val envelopeSupported: Boolean,
    /**
     * 一段 AOSP 包络最多几个控制点。取自 `Vibrator.getEnvelopeEffectInfo()` 的
     * `VibratorEnvelopeEffectInfo.getMaxSize()`，同样是 API 36 起。
     *
     * [envelopeSupported] 为 false 时恒为 0。彩蛋签名段整段需要 22 个控制点，
     * 而规范只保证 ≥ 16，所以播之前必须拿这个值比一下，超了就拆成逐笔画的独立效果
     * （RichTap 的 `playEnvelope` 不受这个限制）。
     */
    val envelopeMaxSize: Int,
    /**
     * RichTap 通路可用否。取自 `RichTapUtils.getInstance().isSupportedRichTap()`。
     *
     * 该方法内部只做类查找与 `android.os.HapticPlayer.isAvailable()` 判定，不依赖
     * `init(Context)`，所以探测阶段刻意不调 `init` —— 探测应当无副作用。
     * aar 里带着 `android.os.HapticPlayer` 桩类，桩的 `isAvailable()` 返回 false，
     * ROM 侧有真身时（小米把它放进了 boot classpath）才会解析到真的实现。
     * 即便如此仍整层 `catch (Throwable)`：`richtap-api` 是 ROM 侧可选共享库，缺失时内部可能抛 Error。
     */
    val richTapSupported: Boolean,
    /**
     * MiHaptic / IEEE 2861.3 的 HE 波形通路可用否。反射静态无参
     * `android.os.HapticPlayer.isAvailable()`。
     *
     * 小米 HyperOS、vivo OriginOS、三星 One UI 等把 `DynamicEffect` / `HapticPlayer`
     * 放进了 framework（微信的细腻触感走的就是它）。目标机实测为 true，而这台机上
     * RichTap 的包络通路不存在（`createEnvelope` 三个候选类全缺）—— HE 因此是它
     * 唯一画得出连续包络的通路，`HapticPlayerBackend` 的存在理由。
     *
     * 为 true 只代表类在且静态判定通过；`DynamicEffect.create` / 构造器 / `start`
     * 的方法签名由 `HapticPlayerBackend` 在自己的单线程上逐个解析，任何一个查不到
     * 整层禁用。
     */
    val hapticPlayerSupported: Boolean,
    /**
     * MIUI / HyperOS 的线性马达通路可用否。反射静态无参
     * `miui.util.HapticFeedbackUtil.isSupportLinearMotorVibrate()`。
     *
     * 为 true 只代表这层能用，**不代表某个具体效果 ID 能用** ——
     * tier 2 不得按 `sys.haptic.version` 之类的版本号推断可用 ID 范围，
     * 必须对每个要用的 ID 逐个调 `isSupportExtHapticFeedback(id)` 并缓存，那是 backend 的活。
     */
    val miuiSupported: Boolean,
    /**
     * ColorOS（OPPO / OnePlus / realme）的线性马达通路可用否。
     *
     * 判据是两条同时成立：`getSystemService("linearmotor")` 拿到非空对象，
     * 且 `com.oplus.os.WaveformEffect` 能加载 —— 服务在但类不在照样一步都走不下去。
     * OPPO 侧没有 `isSupport` 这类逐效果探测接口，所以只能 `catch (Throwable)` 兜住，
     * 并在首次调用失败后整层禁用，不要每次重试。
     */
    val oplusSupported: Boolean,
    /**
     * 华为（EMUI / AOSP 底的 HarmonyOS）的线性马达通路可用否。
     *
     * 判据只有类存在性：`Class.forName("com.huawei.android.os.VibratorEx")` 能加载。
     * 真类在华为 framework 的 boot classpath 上；官方 hapticskit aar 里那个同名类是
     * 空壳 stub，我们没有打包，所以非华为机型这一步就抛 `ClassNotFoundException`
     * 出局，整层零开销。逐效果可用性由 backend 对 `haptic.*` 键逐个调
     * `isSupportHwVibrator` 探测并缓存——华为是三家里唯一给了逐效果探测接口的。
     */
    val huaweiSupported: Boolean,
) {
    /** 转子马达：无振幅控制 → 锁 tier 0（硬规则 1） */
    val lockedToConstants: Boolean get() = !hasAmplitudeControl

    companion object {
        /**
         * 一次探完所有能力。每个平台查询都要版本门控 + catch (Throwable)。
         *
         * **本方法会阻塞，且刻意不自己开线程。** 里面有 `getSystemService`、到
         * `VibratorService` 的 IPC、三次类查找与反射，全都是慢调用；调用方负责把它丢到 IO
         * 上跑一次（`withContext(Dispatchers.IO)`）再把结果缓存起来，不要在主线程直接调。
         * 之所以不在这里包一个 Executor：后端那条单线程 `Executor` 是为了保证彩蛋的事件顺序，
         * 而探测是一次性的、无顺序要求的，多一个线程只多一处要 shutdown 的资源。
         *
         * 容错策略是**逐项独立**：每个字段自己 `catch (Throwable)`，失败只让该字段退成保守值
         * （false / 空集 / 0），绝不带崩整个探测。捕 `Throwable` 不是捕 `Exception` ——
         * 厂商类缺失抛的 `NoClassDefFoundError` 是 Error，`catch (Exception)` 兜不住。
         *
         * 无马达时直接短路返回全 false：后面的 IPC 与三次厂商反射一概不做。
         * 没有马达，四层里哪一层都发不出东西，探到「RichTap 可用」也毫无意义。
         *
         * 不保留 [context] 引用、不发网络请求、不做任何日志上报。
         */
        fun probe(context: android.content.Context): HapticCapabilities {
            val vibrator = resolveVibrator(context)
            if (!probeHasVibrator(vibrator)) {
                return HapticCapabilities(
                    hasVibrator = false,
                    hasAmplitudeControl = false,
                    supportedPrimitives = emptySet(),
                    compositionSizeMax = 0,
                    envelopeSupported = false,
                    envelopeMaxSize = 0,
                    richTapSupported = false,
                    hapticPlayerSupported = false,
                    miuiSupported = false,
                    oplusSupported = false,
                    huaweiSupported = false,
                )
            }
            // 先算出被后面几项复用的两个结果，避免重复 IPC
            val primitives = probeSupportedPrimitives(vibrator)
            val envelopeSupported = probeEnvelopeSupported(vibrator)
            return HapticCapabilities(
                hasVibrator = true,
                hasAmplitudeControl = probeAmplitudeControl(vibrator),
                supportedPrimitives = primitives,
                compositionSizeMax = probeCompositionSizeMax(vibrator, primitives),
                envelopeSupported = envelopeSupported,
                envelopeMaxSize = probeEnvelopeMaxSize(vibrator, envelopeSupported),
                richTapSupported = probeRichTapSupported(),
                hapticPlayerSupported = probeHapticPlayerSupported(),
                miuiSupported = probeMiuiSupported(),
                oplusSupported = probeOplusSupported(context),
                huaweiSupported = probeHuaweiSupported(),
            )
        }

        /** ColorOS 线性马达的系统服务名，`getSystemService` 只认这个字符串。 */
        private const val OPLUS_LINEAR_MOTOR_SERVICE = "linearmotor"

        /** MIUI / HyperOS 触感工具类，反射用；不在 SDK 里，只有 ROM 上有。 */
        private const val MIUI_HAPTIC_FEEDBACK_UTIL = "miui.util.HapticFeedbackUtil"

        /** ColorOS 波形效果类，只做存在性判定，不触发静态初始化。 */
        private const val OPLUS_WAVEFORM_EFFECT = "com.oplus.os.WaveformEffect"

        /** 华为线性马达扩展类，boot classpath 上的真类；非华为机 forName 即失败。 */
        private const val HUAWEI_VIBRATOR_EX = "com.huawei.android.os.VibratorEx"

        /** MiHaptic / IEEE 2861.3 的播放器类，反射用；类名是协议规定死的。 */
        private const val HAPTIC_PLAYER = "android.os.HapticPlayer"

        /**
         * 反射拿不到 `getCompositionSizeMax()` 时用的保守容量。
         *
         * 取 2 是因为语义词表里最长的 tier 1 组合正好两个 primitive
         * （`CLICK` + `THUD`、`THUD` 两连），既不至于误杀整层，也不虚报容量。
         * 真值拿得到时一律用真值。
         */
        private const val FALLBACK_COMPOSITION_SIZE_MAX = 2

        /**
         * 取 `Vibrator`：API 31 起走 `VibratorManager.getDefaultVibrator()`，
         * 以下走已废弃的 `getSystemService(VIBRATOR_SERVICE)`。
         *
         * 两条路都可能返回 null 或抛（个别 ROM 上服务缺失），拿不到就是没有马达。
         */
        @Suppress("DEPRECATION")
        private fun resolveVibrator(context: Context): Vibrator? = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                manager?.defaultVibrator
            } else {
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
        } catch (_: Throwable) {
            null
        }

        /** `Vibrator.hasVibrator()`（API 1）。取不到实例或查询抛错都按「没有马达」算。 */
        private fun probeHasVibrator(vibrator: Vibrator?): Boolean = try {
            vibrator?.hasVibrator() == true
        } catch (_: Throwable) {
            false
        }

        /**
         * 只查「本机有没有马达」，不做整套探测。
         *
         * 设置页的触感卡片要用它决定副标题说不说「本机没有振动马达」。走 [probe] 会连带做
         * 三次厂商类查找与好几次到 `VibratorService` 的 IPC，还得按 `HapticModule` 的规矩
         * 先在后台线程预热一次；而这里只有两步：取 `Vibrator` + `hasVibrator()`。
         *
         * 仍然别在主线程调：`getSystemService` 与 `hasVibrator()` 都要过 binder。
         */
        internal fun deviceHasVibrator(context: Context): Boolean =
            probeHasVibrator(resolveVibrator(context))

        /** `Vibrator.hasAmplitudeControl()`（API 26 = minSdk，无需门控）。查询失败退成 false，即锁 tier 0。 */
        private fun probeAmplitudeControl(vibrator: Vibrator?): Boolean = try {
            vibrator?.hasAmplitudeControl() == true
        } catch (_: Throwable) {
            false
        }

        /**
         * 要探的 primitive 候选表，按常量各自的引入版本门控。
         *
         * API 30 引入 CLICK、TICK、QUICK_RISE、SLOW_RISE、QUICK_FALL；
         * API 31 补上 THUD、SPIN、LOW_TICK。SPIN 当前语义词表没用到，一并探着，
         * 免得以后加语义时又要回来改探测。低于 API 30 返回空表。
         */
        private fun candidatePrimitives(): IntArray = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> intArrayOf(
                VibrationEffect.Composition.PRIMITIVE_CLICK,
                VibrationEffect.Composition.PRIMITIVE_TICK,
                VibrationEffect.Composition.PRIMITIVE_QUICK_RISE,
                VibrationEffect.Composition.PRIMITIVE_SLOW_RISE,
                VibrationEffect.Composition.PRIMITIVE_QUICK_FALL,
                VibrationEffect.Composition.PRIMITIVE_THUD,
                VibrationEffect.Composition.PRIMITIVE_SPIN,
                VibrationEffect.Composition.PRIMITIVE_LOW_TICK,
            )
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> intArrayOf(
                VibrationEffect.Composition.PRIMITIVE_CLICK,
                VibrationEffect.Composition.PRIMITIVE_TICK,
                VibrationEffect.Composition.PRIMITIVE_QUICK_RISE,
                VibrationEffect.Composition.PRIMITIVE_SLOW_RISE,
                VibrationEffect.Composition.PRIMITIVE_QUICK_FALL,
            )
            else -> IntArray(0)
        }

        /**
         * 逐 primitive 探支持情况，收成 Set。API 30 以下恒为空集。
         *
         * 首选 `arePrimitivesSupported(vararg)`：一次 IPC 拿回等长的 boolean 数组，
         * 比逐个问省 7 次跨进程往返。返回长度对不上（个别 ROM 会）或直接抛，就退到
         * 逐个 `areAllPrimitivesSupported(id)`，单个失败只丢那一个 ID。
         *
         * 注意这里探的是「单个 primitive 支不支持」。判定一个效果能不能播是另一回事：
         * 必须把该效果用到的全部 primitive 一次传进 `areAllPrimitivesSupported(vararg)` 整组探。
         */
        private fun probeSupportedPrimitives(vibrator: Vibrator?): Set<Int> {
            if (vibrator == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return emptySet()
            val candidates = candidatePrimitives()
            if (candidates.isEmpty()) return emptySet()
            try {
                val flags = vibrator.arePrimitivesSupported(*candidates)
                if (flags.size == candidates.size) {
                    return candidates.filterIndexed { index, _ -> flags[index] }.toSet()
                }
            } catch (_: Throwable) {
                // 批量接口在个别 ROM 上会抛，掉到逐个探
            }
            return buildSet {
                for (id in candidates) {
                    val supported = try {
                        vibrator.areAllPrimitivesSupported(id)
                    } catch (_: Throwable) {
                        false
                    }
                    if (supported) add(id)
                }
            }
        }

        /**
         * Composition 容量。没有可用 primitive 时直接 0，否则反射问 `getCompositionSizeMax()`。
         *
         * 这个查询不在公开 SDK 里（android-37 的 android.jar 整包搜不到这个符号），
         * 只能反射；隐藏 API 被拦时 `getMethod` 抛的是 `NoSuchMethodException`，一并被
         * `catch (Throwable)` 兜住，退到 [FALLBACK_COMPOSITION_SIZE_MAX]。
         * 拿到的真值 ≤ 0 是厂商明确说「拼不了」（目标机就是 0），照实返回 0 让上层降级。
         */
        private fun probeCompositionSizeMax(vibrator: Vibrator?, supportedPrimitives: Set<Int>): Int {
            if (vibrator == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return 0
            if (supportedPrimitives.isEmpty()) return 0
            val reported = try {
                vibrator.javaClass.getMethod("getCompositionSizeMax").invoke(vibrator) as? Int
            } catch (_: Throwable) {
                null
            }
            return when {
                reported == null -> FALLBACK_COMPOSITION_SIZE_MAX
                reported <= 0 -> 0
                else -> reported
            }
        }

        /** `Vibrator.areEnvelopeEffectsSupported()`，API 36（`BAKLAVA`）起；以下恒为 false。 */
        private fun probeEnvelopeSupported(vibrator: Vibrator?): Boolean {
            if (vibrator == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) return false
            return try {
                vibrator.areEnvelopeEffectsSupported()
            } catch (_: Throwable) {
                false
            }
        }

        /**
         * `Vibrator.getEnvelopeEffectInfo()` 的 `getMaxSize()`，API 36 起。
         *
         * 不支持包络时直接返回 0，不再发这次 IPC：容量对一条走不通的通路没有意义
         * （目标机 `mMaxEnvelopeEffectSize` 实测就是 0）。返回值为负也一律夹到 0。
         * 版本判断这里重做一次而不是只信 [envelopeSupported]：常量与方法的门控要落在
         * 实际调用处，才经得起后来人挪代码。
         */
        private fun probeEnvelopeMaxSize(vibrator: Vibrator?, envelopeSupported: Boolean): Int {
            if (!envelopeSupported || vibrator == null) return 0
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) return 0
            return try {
                vibrator.envelopeEffectInfo.maxSize.coerceAtLeast(0)
            } catch (_: Throwable) {
                0
            }
        }

        /**
         * `RichTapUtils.getInstance().isSupportedRichTap()`。SDK 在编译路径上，直接引用。
         *
         * 刻意不调 `init(Context)`：该判定内部只做类查找与 `HapticPlayer.isAvailable()`，
         * 不依赖 init，而探测阶段不该留副作用（init 会写 SDK 单例里的 Context 与 Vibrator 字段）。
         * `getInstance()` 只是双检锁单例，类初始化只建一个懒起线程的单线程 Executor，
         * 不违反「probe 自己不开线程」。
         *
         * 仍整层 `catch (Throwable)`：`richtap-api` 是 ROM 侧可选共享库，缺失时内部可能抛 Error。
         */
        private fun probeRichTapSupported(): Boolean = try {
            RichTapUtils.getInstance().isSupportedRichTap()
        } catch (_: Throwable) {
            false
        }

        /**
         * 反射静态无参 `miui.util.HapticFeedbackUtil.isSupportLinearMotorVibrate()`。
         *
         * 非小米机型上 `Class.forName` 抛 `ClassNotFoundException`，小米上偶有签名不一致
         * 抛 `NoSuchMethodException`，返回值类型对不上就 `as?` 成 null —— 三条路都退成 false。
         * 这里只判「这一层能不能用」，具体 ID 的可用性由 backend 逐 ID 探。
         */
        private fun probeMiuiSupported(): Boolean = try {
            val method = Class.forName(MIUI_HAPTIC_FEEDBACK_UTIL)
                .getMethod("isSupportLinearMotorVibrate")
            (method.invoke(null) as? Boolean) == true
        } catch (_: Throwable) {
            false
        }

        /**
         * 反射静态无参 `android.os.HapticPlayer.isAvailable()`（IEEE 2861.3 规定的类名）。
         *
         * 类不在（非 MiHaptic 机型）抛 `ClassNotFoundException`，静态判定返 false 的
         * （ROM 有类但马达通路没就绪）都退成 false。方法签名由 `HapticPlayerBackend`
         * 在自己的单线程上继续解析，这里只做这一道便宜的总闸。
         */
        private fun probeHapticPlayerSupported(): Boolean = try {
            val method = Class.forName(HAPTIC_PLAYER).getMethod("isAvailable")
            (method.invoke(null) as? Boolean) == true
        } catch (_: Throwable) {
            false
        }

        /**
         * ColorOS：`getSystemService("linearmotor")` 非空 **且** `com.oplus.os.WaveformEffect` 可加载。
         *
         * 顺序有意如此 —— 先问服务，非 ColorOS 机型第一步就出局，省一次
         * `ClassNotFoundException` 的构造与栈回溯。类查找传 `initialize = false`：
         * 只判存在性，不触发对方的静态初始化。
         */
        private fun probeOplusSupported(context: Context): Boolean = try {
            if (context.getSystemService(OPLUS_LINEAR_MOTOR_SERVICE) == null) {
                false
            } else {
                Class.forName(OPLUS_WAVEFORM_EFFECT, false, context.classLoader)
                true
            }
        } catch (_: Throwable) {
            false
        }

        /**
         * 华为：`Class.forName("com.huawei.android.os.VibratorEx")` 能加载即这一层有戏。
         *
         * 只判类存在性，不实例化、不逐键问——那两步是 `HuaweiBackend` 在自己的单线程上
         * 做的（13 个键的 `isSupportHwVibrator` 是 13 次 binder 往返，不该让所有机型
         * 在启动预热里陪着付）。类查找传 `initialize = false`：这里用不到静态成员，
         * 不触发对方的静态初始化。loader 传 `null`（bootstrap）：真类在华为 framework
         * 的 boot classpath 上，本来就归它加载；华为设备解析到真类，非华为设备直接
         * `ClassNotFoundException` 退 false。
         */
        private fun probeHuaweiSupported(): Boolean = try {
            Class.forName(HUAWEI_VIBRATOR_EX, false, null)
            true
        } catch (_: Throwable) {
            false
        }
    }
}
