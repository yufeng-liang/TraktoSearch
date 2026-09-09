package com.tracktosearch.ui.haptic

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.tracktosearch.ui.haptic.backend.AospConstantsBackend
import com.tracktosearch.ui.haptic.backend.AospWaveformBackend
import com.tracktosearch.ui.haptic.backend.HapticPlayerBackend
import com.tracktosearch.ui.haptic.backend.MiuiBackend
import com.tracktosearch.ui.haptic.backend.OplusBackend
import com.tracktosearch.ui.haptic.backend.RichTapBackend
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 设备能力探测跑批：把这台机器上「到底哪几层可用、哪些效果 ID 真被支持」打印出来。
 *
 * 这不是回归测试而是**取数工具**。实施计划的「未决与未验证」里有一批只有装到真机上
 * 才能回答的问题（真实的 `isSupportedRichTap()`、MIUI 26 个 ID 逐个是否
 * `isSupportExtHapticFeedback`、OPPO 通路可达性、API 36 的包络分支），
 * 跑这个类一次就能把它们一起收掉，输出抄回计划文档。
 *
 * 所以断言刻意宽：这里断言的是「探测本身不抛、结论自洽」，不是「必须支持某一层」——
 * 换一台别的机器跑，它照样该通过，只是打印的内容不同。真正的判断留给读输出的人。
 *
 * 输出同时走 `println`（进 instrumentation 的 `system-out`，落在
 * `app/build/outputs/androidTest-results/connected/` 的 XML 里）与 `Log.i`（进 logcat），
 * 两条路哪条方便就用哪条。
 */
@RunWith(AndroidJUnit4::class)
class HapticDeviceCapabilityProbeTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun report(line: String) {
        println("[haptic-probe] $line")
        Log.i(TAG, line)
    }

    @Test
    fun 打印本机能力快照() {
        val capabilities = HapticCapabilities.probe(context)

        report("device=${Build.MODEL} / ${Build.DEVICE}, sdk=${Build.VERSION.SDK_INT}, build=${Build.DISPLAY}")
        report("hasVibrator=${capabilities.hasVibrator}")
        report("hasAmplitudeControl=${capabilities.hasAmplitudeControl} (lockedToConstants=${capabilities.lockedToConstants})")
        report("supportedPrimitives=${capabilities.supportedPrimitives.sorted()}")
        report("compositionSizeMax=${capabilities.compositionSizeMax}")
        report("envelopeSupported=${capabilities.envelopeSupported} envelopeMaxSize=${capabilities.envelopeMaxSize}")
        report("richTapSupported=${capabilities.richTapSupported}")
        report("miuiSupported=${capabilities.miuiSupported}")
        report("oplusSupported=${capabilities.oplusSupported}")

        // 探测不抛就算过。这一条同时是「probe 在真机上不会因为某个厂商类缺失而整体炸掉」的证据
        assertThat(capabilities.compositionSizeMax).isAtLeast(0)
    }

    /**
     * 六层各自的 `isAvailable()` 与语义覆盖。
     *
     * 逐层问而不是只问 [AppHaptics]：门面只告诉你「有人接了」，
     * 而计划里要记的是「**哪一层**接的、每层各覆盖了哪些语义」。
     */
    @Test
    fun 打印六层的可用性与语义覆盖() {
        val capabilities = HapticCapabilities.probe(context)
        val backends = listOf(
            RichTapBackend(context, capabilities),
            HapticPlayerBackend(capabilities),
            MiuiBackend(context, capabilities),
            OplusBackend(context, capabilities),
            AospWaveformBackend(context, capabilities),
            AospConstantsBackend(),
        )
        try {
            backends.forEach { backend ->
                // 厂商层的探测甩在自己的单线程上，第一次 isAvailable() 只是把它踢起来；
                // supports 要等探测落表才有意义，所以先问一次再等
                backend.isAvailable()
            }
            Thread.sleep(PROBE_SETTLE_MS)

            backends.forEach { backend ->
                val available = backend.isAvailable()
                val supported = HapticSemantic.entries.filter { available && backend.supports(it) }
                report(
                    "tier=${backend.tier} ${backend.javaClass.simpleName} " +
                        "available=$available supports=${supported.size}/${HapticSemantic.entries.size} $supported"
                )
            }

            // 包络链的两条 tier 3 通路谁真的接得住包络，顺带问一句
            runCatching {
                val he = HapticPlayerBackend(capabilities)
                try {
                    he.isAvailable()
                    Thread.sleep(PROBE_SETTLE_MS)
                    report(
                        "HapticPlayerBackend.playEnvelope 两段包络=" +
                            he.playEnvelope(intArrayOf(60, 60), floatArrayOf(0.3f, 0f)),
                    )
                } finally {
                    he.release()
                }
            }

            val covered = HapticSemantic.entries.filter { semantic ->
                backends.any { it.isAvailable() && it.supports(semantic) }
            }
            report("覆盖合计=${covered.size}/${HapticSemantic.entries.size}")
            val uncovered = HapticSemantic.entries - covered.toSet()
            if (uncovered.isNotEmpty()) report("**没有任何一层覆盖**：$uncovered")

            if (capabilities.hasVibrator) {
                // tier 0 是地板，13 个语义全量映射到 HapticFeedbackConstants，
                // 所以有马达的机器上不该有任何语义落空
                assertWithMessage("有马达却有语义没人覆盖，地板层出问题了").that(uncovered).isEmpty()
            }
        } finally {
            backends.forEach { it.release() }
        }
    }

    /**
     * MIUI 的 26 个效果 ID 逐个问 `isSupportExtHapticFeedback`。
     *
     * 自己反射而不是问 [MiuiBackend]：那边的 `supportedIds` 是私有的，而且它只探
     * [miuiEffectFor] 用到的 12 个。计划里要的是 26 个的全表。
     */
    @Test
    fun 打印MIUI二十六个效果ID的支持情况() {
        val supported = linkedMapOf<Int, Boolean>()
        val failure = runCatching {
            val clazz = Class.forName("miui.util.HapticFeedbackUtil")
            val linear = clazz.getMethod("isSupportLinearMotorVibrate").invoke(null) as? Boolean
            report("miui.isSupportLinearMotorVibrate=$linear")
            if (linear != true) return@runCatching
            val instance = clazz
                .getConstructor(Context::class.java, Boolean::class.javaPrimitiveType)
                .newInstance(context, true)
            val isSupportExt = clazz.getMethod("isSupportExtHapticFeedback", Int::class.javaPrimitiveType)
            for (id in MIUI_ID_FIRST..MIUI_ID_LAST) {
                supported[id] = runCatching {
                    isSupportExt.invoke(instance, id) as? Boolean == true
                }.getOrDefault(false)
            }
        }.exceptionOrNull()

        if (failure != null) {
            // 非 MIUI 机型走这条：类不存在。这不是失败，是这台机器没有 tier 2 的 MIUI 通路
            report("MIUI 反射不可用：${failure.javaClass.simpleName}: ${failure.message}")
            return
        }
        if (supported.isEmpty()) {
            report("MIUI 类在但 isSupportLinearMotorVibrate 为假，tier 2 整层不可用")
            return
        }

        supported.forEach { (id, ok) ->
            report("MIUI ${"0x%08x".format(id)} ${miuiIdName(id)} = $ok")
        }
        report("MIUI 支持合计=${supported.values.count { it }}/${supported.size}")

        // 这台机器认这套接口的话，映射表挑中的那几个效果一个都不该缺。
        // 这里问的是 MiuiBackend 自己的结论（supportedIds 与 miuiEffectFor 都是私有的，
        // 从外面只能通过 supports 看）—— 与上面 26 个 ID 的全表互为对照
        val backend = MiuiBackend(context, HapticCapabilities.probe(context))
        try {
            backend.isAvailable()
            Thread.sleep(PROBE_SETTLE_MS)
            val missing = HapticSemantic.entries.filterNot { backend.supports(it) }
            report("MiuiBackend 覆盖不到的语义（${missing.size} 个）：$missing")
        } finally {
            backend.release()
        }
    }

    /**
     * RichTap 的真实可用性。`richTapSupported` 只说「类在 boot classpath 上」，
     * 而 SDK 内部 `init()` 之后还可能自己退到 GooglePlayer 通路 —— 那种情况下
     * tier 3 必须让位，[RichTapBackend.isAvailable] 会转 false。这条把结论打出来。
     */
    @Test
    fun 打印RichTap的真实可用性() {
        val capabilities = HapticCapabilities.probe(context)
        report("capabilities.richTapSupported=${capabilities.richTapSupported}（只表示类在 boot classpath）")

        val backend = RichTapBackend(context, capabilities)
        try {
            backend.isAvailable()
            Thread.sleep(PROBE_SETTLE_MS)
            val available = backend.isAvailable()
            report("RichTapBackend.isAvailable()=$available（init 之后的真实结论）")
            report("RichTapBackend.supports(CONFIRM)=${available && backend.supports(HapticSemantic.CONFIRM)}")

            val envelope = backend.playEnvelope(intArrayOf(30, 30), floatArrayOf(0.4f, 0f))
            report("RichTapBackend.playEnvelope 两点包络=$envelope")

            if (!capabilities.richTapSupported) {
                assertWithMessage("类都不在，这一层不该报可用").that(available).isFalse()
            }
        } finally {
            backend.release()
        }
    }

    /**
     * API 36 引入的连续包络分支，以及 tier 1 在本机实际走哪条路。
     */
    @Test
    fun 打印tier1的包络与Composition通路() {
        val capabilities = HapticCapabilities.probe(context)
        report("sdk=${Build.VERSION.SDK_INT}，API 36 包络分支${if (Build.VERSION.SDK_INT >= 36) "在射程内" else "够不着"}")
        report("envelopeSupported=${capabilities.envelopeSupported} envelopeMaxSize=${capabilities.envelopeMaxSize}")

        val backend = AospWaveformBackend(context, capabilities)
        try {
            val available = backend.isAvailable()
            report("AospWaveformBackend.isAvailable()=$available")
            val supported = HapticSemantic.entries.filter { available && backend.supports(it) }
            report("tier 1 覆盖 ${supported.size}/${HapticSemantic.entries.size}：$supported")
            report("tier 1 playEnvelope=${backend.playEnvelope(intArrayOf(30, 30), floatArrayOf(0.4f, 0f))}")

            if (capabilities.lockedToConstants) {
                assertWithMessage("转子马达机型上 tier 1 不该可用").that(available).isFalse()
            }
        } finally {
            backend.release()
        }
    }

    /**
     * HyperOS 上 26 个 0x10000000 段 ID 全被拒时的诊断实验（2026-09-09 真机发现）：
     * 1) 构造器第二参换成 false 再探一遍；
     * 2) 小整数段 0..64 逐个探——微信在 dumpsys 里是 `effectId=8;keyword=MIUI_SDK`，
     *    怀疑 HyperOS 3 的扩展 ID 换了段；
     * 3) 打印 HapticFeedbackUtil 的全部公开方法（找 isSupportHapticVersion2 这类新口子）；
     * 4) 无视探测结果直接 performExtHapticFeedback(0x10000001) 一次，事后用
     *    dumpsys vibrator_manager 查有没有真震（isSupport 可能只是口径变了）。
     */
    @Test
    fun 诊断MIUI扩展ID被全拒的原因() {
        val failure = runCatching {
            val clazz = Class.forName("miui.util.HapticFeedbackUtil")
            report("methods=${clazz.methods.map { "${it.name}(${it.parameterTypes.joinToString(",") { p -> p.simpleName }})" }.sorted().joinToString(" | ")}")

            val ctorFlagFalse = clazz
                .getConstructor(Context::class.java, Boolean::class.javaPrimitiveType)
                .newInstance(context, false)
            val isSupportExt = clazz.getMethod("isSupportExtHapticFeedback", Int::class.javaPrimitiveType)

            val extIdsWithFalseCtor = (MIUI_ID_FIRST..MIUI_ID_LAST).count {
                runCatching { isSupportExt.invoke(ctorFlagFalse, it) as? Boolean == true }.getOrDefault(false)
            }
            report("ctor=false 时 26 个 0x10000000 段 ID 支持=$extIdsWithFalseCtor")

            val smallSupported = (0..64).filter { id ->
                runCatching { isSupportExt.invoke(ctorFlagFalse, id) as? Boolean == true }.getOrDefault(false)
            }
            report("ctor=false 时 0..64 段支持的 ID=$smallSupported")

            val ctorFlagTrue = clazz
                .getConstructor(Context::class.java, Boolean::class.javaPrimitiveType)
                .newInstance(context, true)
            val smallSupportedTrue = (0..64).filter { id ->
                runCatching { isSupportExt.invoke(ctorFlagTrue, id) as? Boolean == true }.getOrDefault(false)
            }
            report("ctor=true 时 0..64 段支持的 ID=$smallSupportedTrue")

            // 无视探测直接派一记标准点击，之后在 PC 侧用 dumpsys 查结果
            val performExt = clazz.getMethod("performExtHapticFeedback", Int::class.javaPrimitiveType)
            val fired = runCatching { performExt.invoke(ctorFlagTrue, MIUI_ID_FIRST + 1) }.exceptionOrNull()
            report("performExtHapticFeedback(0x10000001) 异常=${fired?.let { "${it.javaClass.simpleName}: ${it.message}" } ?: "无（看 dumpsys 有没有真震）"}")
        }.exceptionOrNull()
        if (failure != null) report("诊断失败：${failure.javaClass.simpleName}: ${failure.message}")
    }

    /**
     * HyperOS 小整数段 ID 的名字表与实弹验证：
     * 1) 反射打印 HapticFeedbackUtil / miui.view.MiuiHapticFeedbackConstants /
     *    miuix.view.HapticFeedbackConstants 的全部 public static 字段（官方常量名与取值）；
     * 2) isSupportedEffect(0..64) 与 isSupportExtHapticFeedback 的对照；
     * 3) performExtHapticFeedback(8) 实弹一记（dumpsys 里微信就是 effectId=8）。
     */
    @Test
    fun 打印MIUI常量表并实弹小ID() {
        runCatching {
            for (name in listOf(
                "miui.util.HapticFeedbackUtil",
                "miui.view.MiuiHapticFeedbackConstants",
                "miuix.view.HapticFeedbackConstants",
                "miui.view.HapticFeedbackConstants",
            )) {
                val clazz = runCatching { Class.forName(name) }.getOrNull() ?: continue
                val fields = clazz.fields
                    .filter { java.lang.reflect.Modifier.isStatic(it.modifiers) }
                    .sortedBy { runCatching { it.get(null).toString() }.getOrDefault("") }
                report("${clazz.name} 共 ${fields.size} 个静态字段")
                fields.forEach { field ->
                    runCatching { report("  ${clazz.simpleName}.${field.name} = ${field.get(null)}") }
                }
            }

            val utilClass = Class.forName("miui.util.HapticFeedbackUtil")
            val instance = utilClass
                .getConstructor(Context::class.java, Boolean::class.javaPrimitiveType)
                .newInstance(context, true)
            val isSupportedEffect = utilClass.getMethod("isSupportedEffect", Int::class.javaPrimitiveType)
            val supportedEffect = (0..64).filter { id ->
                runCatching { isSupportedEffect.invoke(instance, id) as? Boolean == true }.getOrDefault(false)
            }
            report("isSupportedEffect(0..64) 支持的 ID=$supportedEffect")

            // 实弹：小 ID 8 走 ext 通路（之后在 PC 侧查 dumpsys）
            val performExt = utilClass.getMethod("performExtHapticFeedback", Int::class.javaPrimitiveType)
            val fired8 = runCatching { performExt.invoke(instance, 8) }.exceptionOrNull()
            report(
                "performExtHapticFeedback(8) 异常=" +
                    (fired8?.let { "${it.javaClass.simpleName}: ${it.message}" } ?: "无（看 dumpsys）"),
            )
        }.onFailure { report("诊断失败：${it.javaClass.simpleName}: ${it.message}") }
    }

    /**
     * String 键 API（HyperOS 3 现行口子）实弹：`performHapticFeedback(key, boolean)` 与
     * `(key, boolean, int strength)`。微信 dumpsys 里 `keyword=MIUI_SDK;effectId=8;
     * TAG=haptic_feedback_config_strength` 疑似就是这条路的框架侧痕迹。
     * 每个变体打返回值，之后在 PC 侧查 dumpsys 是否出现 keyword=MIUI_SDK 的新记录。
     */
    @Test
    fun 实弹MIUI字符串键API() {
        runCatching {
            // MIUI 疑似按「进程有没有前台 Activity」抑制三方触感（instrumentation 默认
            // 无界面）。异步把主界面拉起来再开火；startActivitySync 会等主线程空闲，
            // 开屏动画不放行，45 秒必超时。
            context.startActivity(
                android.content.Intent(context, Class.forName("com.tracktosearch.MainActivity"))
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            Thread.sleep(4000)

            val utilClass = Class.forName("miui.util.HapticFeedbackUtil")
            val instance = utilClass
                .getConstructor(Context::class.java, Boolean::class.javaPrimitiveType)
                .newInstance(context, true)

            fun fire(method: String, vararg args: Any?) {
                val m = when (args.size) {
                    2 -> utilClass.getMethod(method, String::class.java, Boolean::class.javaPrimitiveType)
                    3 -> utilClass.getMethod(
                        method,
                        String::class.java,
                        Boolean::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType,
                    )
                    else -> error("只支持两参/三参")
                }
                val result = runCatching { m.invoke(instance, *args) }
                report(
                    "$method(${args.joinToString(",")}) -> " +
                        (result.exceptionOrNull()?.let { "${it.javaClass.simpleName}: ${it.message}" }
                            ?: "返回 ${result.getOrNull()}"),
                )
            }

            fire("performHapticFeedback", "tap_normal", true)
            Thread.sleep(600)
            fire("performHapticFeedback", "tap_normal", false)
            Thread.sleep(600)
            fire("performHapticFeedback", "gear_light", true)
            Thread.sleep(600)
            fire("performHapticFeedback", "switch", true, 50)
            Thread.sleep(600)
            fire("performHapticFeedback", "button_large", true)
        }.onFailure { report("诊断失败：${it.javaClass.simpleName}: ${it.message}") }
    }

    /**
     * 对照组：同条件下打一记 RichTap 预置效果（昨晚已验证这条通路能震、能进 dumpsys）。
     * 用于区分「String 键 API 没震」到底是锁屏抑制还是通路本身死。
     */
    @Test
    fun 对照实弹RichTap预置() {
        val capabilities = HapticCapabilities.probe(context)
        // 同样先拉起前台 Activity，排除「无前台界面被 MIUI 抑制」的干扰；
        // 异步启动 + 等待，理由见上
        context.startActivity(
            android.content.Intent(context, Class.forName("com.tracktosearch.MainActivity"))
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        Thread.sleep(4000)
        val backend = RichTapBackend(context, capabilities)
        try {
            backend.isAvailable()
            Thread.sleep(PROBE_SETTLE_MS)
            val dispatched = backend.perform(null, HapticSemantic.CONFIRM, HapticStrength.SYSTEM)
            report("RichTap perform(CONFIRM) 派发=$dispatched（等 dumpsys 对照）")
            Thread.sleep(1500)
        } finally {
            backend.release()
        }
    }

    private companion object {
        const val TAG = "HapticProbe"

        /** 厂商层的探测甩在自己的单线程上，给它一点时间落表再问 supports */
        const val PROBE_SETTLE_MS = 1500L

        const val MIUI_ID_FIRST = 0x10000000
        const val MIUI_ID_LAST = 0x10000019

        /** 26 个 ID 的名字，顺序即取值，只为让输出可读 */
        val MIUI_ID_NAMES = listOf(
            "VIRTUAL_RELEASE", "TAP_NORMAL", "TAP_LIGHT", "FLICK", "SWITCH",
            "MESH_HEAVY", "MESH_NORMAL", "MESH_LIGHT", "LONG_PRESS", "POPUP_NORMAL",
            "POPUP_LIGHT", "PICK_UP", "SCROLL_EDGE", "TRIGGER_DRAWER", "FLICK_LIGHT",
            "HOLD", "BOUNDARY_SPATIAL", "BOUNDARY_TIME", "BUTTON_LARGE", "BUTTON_MIDDLE",
            "BUTTON_SMALL", "KEYBOARD", "ALERT", "TRIGGER_HEAVY", "TRIGGER_NORMAL",
            "TRIGGER_LIGHT",
        )

        fun miuiIdName(id: Int): String =
            MIUI_ID_NAMES.getOrElse(id - MIUI_ID_FIRST) { "UNKNOWN" }
    }
}
