package com.tracktosearch.ui.haptic

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
import java.io.File
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/**
 * [HapticCapabilities] 的探测与判定。挑的都是真会出错的分支：硬规则 1（转子马达锁 tier 0）、
 * API 30 与 31 与 36 三档版本门控、Composition 容量的两种退化路径，以及
 * 「任一平台查询抛 Error 也不许带崩整份探测」。
 *
 * 为什么用 Robolectric：探测几乎每一项都挂在 `Build.VERSION.SDK_INT` 上，而纯 JVM 单测里
 * 它恒为 0 且是 final 字段（JDK 17 封掉了改 final 的反射后门），门控一档都验不了。
 * Robolectric 把 android 类插桩后 `SDK_INT` 变成普通静态字段，才能挪到 29 与 30 与 36 逐档验。
 *
 * 为什么同时用 MockK 而不是 `ShadowVibrator`：`android.os.Vibrator` 的构造器是包私有的，
 * 测试侧写不出自己的假实现；4.16.1 的 `ShadowVibrator` 只影子化了 `areAllPrimitivesSupported`，
 * 既没有批量的 `arePrimitivesSupported`、也没有 `VibratorManager` 的影子，而「批量查询抛错」
 * 与「批量返回长度不符」恰好是要覆盖的两条；OPPO 那条还需要让
 * `getSystemService` 对 linearmotor 返回非空对象，影子给不了。
 *
 * 两条本机跑不到的分支在对应用例里注明了：AOSP 包络的 true 分支（要 Robolectric sdk 36，
 * 而它要求 Java 21，本仓是 JVM 17），以及 `getCompositionSizeMax()` 反射成功的分支
 * （公开 SDK 与 Robolectric 的 android-all 里都没有这个方法）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class HapticCapabilitiesTest {
    private val vibrator = mockk<Vibrator>()
    private val vibratorManager = mockk<VibratorManager>()
    private val context = mockk<Context>()

    /** 批量 primitive 查询每次实际问到的 ID，用来断言「整组一次问完」而不是逐个问。 */
    private val primitiveQueries = mutableListOf<List<Int>>()

    /** 沙箱给的 SDK_INT，用例改过之后要还回去，免得污染同一沙箱里后面的用例。 */
    private var originalSdkInt = 0

    /** VIBRATOR_SERVICE 已废弃但 API 31 以下就靠它，被测代码也是这么取的，跟着抑制一次。 */
    @Suppress("DEPRECATION")
    @Before
    fun setUp() {
        originalSdkInt = Build.VERSION.SDK_INT
        every { context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) } returns vibratorManager
        every { context.getSystemService(Context.VIBRATOR_SERVICE) } returns vibrator
        every { context.getSystemService(OPLUS_LINEAR_MOTOR_SERVICE) } returns null
        every { context.classLoader } returns javaClass.classLoader
        every { vibratorManager.defaultVibrator } returns vibrator
    }

    @After
    fun tearDown() {
        ReflectionHelpers.setStaticField(Build.VERSION::class.java, "SDK_INT", originalSdkInt)
        unmockkAll()
    }

    @Test
    fun `无振幅控制的转子马达锁 tier 0`() {
        // 硬规则 1：没有振幅控制时所有非零振幅会被抬到 100%，只能走 tier 0 的常量
        assertThat(snapshot(hasAmplitudeControl = false).lockedToConstants).isTrue()
        assertThat(snapshot(hasAmplitudeControl = true).lockedToConstants).isFalse()
        // primitive 齐备也救不回来：判据只有振幅控制这一项，不看别的字段
        val rotaryWithPrimitives = snapshot(
            hasAmplitudeControl = false,
            supportedPrimitives = API_31_PRIMITIVES.toSet(),
        )
        assertThat(rotaryWithPrimitives.lockedToConstants).isTrue()
        // 必须是计算属性：copy 改掉振幅控制，结论要跟着翻，不能是构造时算死的快照
        assertThat(rotaryWithPrimitives.copy(hasAmplitudeControl = true).lockedToConstants).isFalse()
    }

    @Test
    fun `无马达时整份快照退成保守值并短路后续探测`() {
        stubMotor(hasVibrator = false)

        val caps = HapticCapabilities.probe(context)

        assertThat(caps).isEqualTo(
            HapticCapabilities(
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
            )
        )
        // 短路：不再问振幅、不再发 primitive 查询、也不去碰 OPPO 那个系统服务
        verify(exactly = 0) { vibrator.hasAmplitudeControl() }
        assertThat(primitiveQueries).isEmpty()
        verify(exactly = 0) { context.getSystemService(OPLUS_LINEAR_MOTOR_SERVICE) }
    }

    @Test
    fun `拿不到 Vibrator 服务时按无马达算`() {
        // 个别 ROM 上 VibratorManager 就是取不到，此时不能崩也不能瞎报有马达
        every { context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) } returns null

        val caps = HapticCapabilities.probe(context)

        assertThat(caps.hasVibrator).isFalse()
        assertThat(caps.supportedPrimitives).isEmpty()
        // 没有马达同样归到锁 tier 0，不会因为查不到振幅而放行高 tier
        assertThat(caps.lockedToConstants).isTrue()
    }

    @Test
    fun `系统服务查询抛 Error 时探测仍返回保守快照`() {
        // NoClassDefFoundError 是 Error 不是 Exception，catch (Exception) 兜不住这一条
        every { context.getSystemService(any<String>()) } throws NoClassDefFoundError("vibrator service gone")

        val caps = HapticCapabilities.probe(context)

        assertThat(caps.hasVibrator).isFalse()
        assertThat(caps.compositionSizeMax).isEqualTo(0)
    }

    @Test
    fun `无振幅控制也照实报告 primitive 而不替 backend 做策略`() {
        stubMotor(amplitudeControl = { false }, primitives = { ids -> BooleanArray(ids.size) { true } })

        val caps = HapticCapabilities.probe(context)

        assertThat(caps.hasAmplitudeControl).isFalse()
        assertThat(caps.lockedToConstants).isTrue()
        // 探测只描述事实：锁 tier 0 是 backend 的活，这里不清零 primitive 与容量
        assertThat(caps.supportedPrimitives).containsExactlyElementsIn(API_31_PRIMITIVES)
        assertThat(caps.compositionSizeMax).isEqualTo(FALLBACK_COMPOSITION_SIZE_MAX)
    }

    @Test
    fun `目标机形态里 primitive 空集时容量为 0 且三条厂商通路都不可用`() {
        // 小米 14 Pro 实测形态：有振幅控制，但一个 primitive 都不支持
        stubMotor()

        val caps = HapticCapabilities.probe(context)

        assertThat(caps.hasVibrator).isTrue()
        assertThat(caps.hasAmplitudeControl).isTrue()
        assertThat(caps.lockedToConstants).isFalse()
        assertThat(caps.supportedPrimitives).isEmpty()
        // 没有可用 primitive 就直接 0，不走保守下限 2 —— 容量多大也拼不出效果
        assertThat(caps.compositionSizeMax).isEqualTo(0)
        assertThat(caps.envelopeSupported).isFalse()
        assertThat(caps.envelopeMaxSize).isEqualTo(0)
        // JVM 上没有 ROM 侧的 richtap-api、没有 miui.util.HapticFeedbackUtil、
        // 没有 linearmotor 服务，也没有 android.os.HapticPlayer —— 四条都探不到；
        // 重点是探不到也不许带崩整份探测
        assertThat(caps.richTapSupported).isFalse()
        assertThat(caps.hapticPlayerSupported).isFalse()
        assertThat(caps.miuiSupported).isFalse()
        assertThat(caps.oplusSupported).isFalse()
    }

    @Test
    fun `Pixel 形态整组一次问完八个 primitive 并退到保守容量`() {
        stubMotor(primitives = { ids -> BooleanArray(ids.size) { true } })

        val caps = HapticCapabilities.probe(context)

        assertThat(caps.supportedPrimitives).containsExactlyElementsIn(API_31_PRIMITIVES)
        // 一次 IPC 问完全部候选，而不是拆成八回跨进程往返
        assertThat(primitiveQueries).hasSize(1)
        assertThat(primitiveQueries.single()).containsExactlyElementsIn(API_31_PRIMITIVES)
        // 公开 SDK 里没有 getCompositionSizeMax()，反射拿不到就退保守下限，而不是 0 也不是崩
        assertThat(caps.compositionSizeMax).isEqualTo(FALLBACK_COMPOSITION_SIZE_MAX)
        // API 36 才有的包络接口在 35 上必须被门控掉，哪怕设备其实支持。
        // 包络为 true 的那一支要 Robolectric sdk 36，而它要求 Java 21，本仓 JVM 17 跑不到
        assertThat(caps.envelopeSupported).isFalse()
        assertThat(caps.envelopeMaxSize).isEqualTo(0)
    }

    @Test
    fun `API 30 上只探那五个 API 30 引入的 primitive`() {
        overrideSdkInt(Build.VERSION_CODES.R)
        stubMotor(primitives = { ids -> BooleanArray(ids.size) { true } })

        val caps = HapticCapabilities.probe(context)

        // THUD 与 SPIN 与 LOW_TICK 是 API 31 才有的常量，30 上一个都不许问
        assertThat(primitiveQueries.single()).containsExactlyElementsIn(API_30_PRIMITIVES)
        assertThat(caps.supportedPrimitives).containsExactlyElementsIn(API_30_PRIMITIVES)
        assertThat(caps.compositionSizeMax).isEqualTo(FALLBACK_COMPOSITION_SIZE_MAX)
    }

    @Test
    fun `API 30 以下不发 primitive 查询也不问容量`() {
        overrideSdkInt(Build.VERSION_CODES.Q)
        stubMotor(primitives = { ids -> BooleanArray(ids.size) { true } })

        val caps = HapticCapabilities.probe(context)

        assertThat(primitiveQueries).isEmpty()
        assertThat(caps.supportedPrimitives).isEmpty()
        assertThat(caps.compositionSizeMax).isEqualTo(0)
        // 门控只砍高版本那几项，minSdk 26 就有的两项仍要照常探到
        assertThat(caps.hasVibrator).isTrue()
        assertThat(caps.hasAmplitudeControl).isTrue()
    }

    @Test
    fun `包络查询抛 Error 时只让包络两项退成 0`() {
        // 把 SDK_INT 抬到 36 打开包络那一支，而沙箱的 android-all 里并没有 API 36 的
        // areEnvelopeEffectsSupported()，调用点会真的抛 NoSuchMethodError —— 又一条
        // catch (Exception) 兜不住、只有 catch (Throwable) 才吃得下的 Error
        overrideSdkInt(Build.VERSION_CODES.BAKLAVA)
        stubMotor(primitives = { ids -> BooleanArray(ids.size) { true } })

        val caps = HapticCapabilities.probe(context)

        assertThat(caps.envelopeSupported).isFalse()
        assertThat(caps.envelopeMaxSize).isEqualTo(0)
        // 逐项独立：包络那两项塌了，前面几项该探到的照样探到
        assertThat(caps.hasVibrator).isTrue()
        assertThat(caps.hasAmplitudeControl).isTrue()
        assertThat(caps.supportedPrimitives).containsExactlyElementsIn(API_31_PRIMITIVES)
    }

    @Test
    fun `振幅查询抛 Error 时只有该项退成 false`() {
        stubMotor(
            amplitudeControl = { throw NoClassDefFoundError("vibrator info missing") },
            primitives = { ids -> BooleanArray(ids.size) { true } },
        )

        val caps = HapticCapabilities.probe(context)

        // 查不到就按转子马达算，宁可锁 tier 0 也不冒险放行振幅波形
        assertThat(caps.hasAmplitudeControl).isFalse()
        assertThat(caps.lockedToConstants).isTrue()
        // 其余字段不受这一项影响
        assertThat(caps.hasVibrator).isTrue()
        assertThat(caps.supportedPrimitives).containsExactlyElementsIn(API_31_PRIMITIVES)
    }

    @Test
    fun `批量 primitive 查询抛错时退到逐个探而不是整份探测塌掉`() {
        stubMotor(primitives = { throw IllegalStateException("batch query unsupported") })

        val caps = HapticCapabilities.probe(context)

        // 批量试过一次；逐个探这一路在本机答不出，于是收成空集
        assertThat(primitiveQueries).hasSize(1)
        assertThat(caps.supportedPrimitives).isEmpty()
        assertThat(caps.compositionSizeMax).isEqualTo(0)
        // 关键是别的字段还在
        assertThat(caps.hasVibrator).isTrue()
        assertThat(caps.hasAmplitudeControl).isTrue()
    }

    @Test
    fun `批量 primitive 返回长度不符时整份丢掉不按下标映射`() {
        // 个别 ROM 会回一个短数组：照下标映射就是越界或错报，必须整份不采信
        stubMotor(primitives = { booleanArrayOf(true) })

        val caps = HapticCapabilities.probe(context)

        assertThat(caps.supportedPrimitives).isEmpty()
        assertThat(caps.compositionSizeMax).isEqualTo(0)
        assertThat(caps.hasVibrator).isTrue()
    }

    @Test
    fun `linearmotor 服务在但 WaveformEffect 类不在时 OPlus 通路仍不可用`() {
        stubMotor()
        every { context.getSystemService(OPLUS_LINEAR_MOTOR_SERVICE) } returns Any()

        val caps = HapticCapabilities.probe(context)

        // 判据是「服务非空」与「com.oplus.os.WaveformEffect 能加载」两条同时成立，
        // 服务在而类不在照样一步都走不下去
        assertThat(caps.oplusSupported).isFalse()
        // 且这个 false 是真的问过服务之后得出的，不是被前面某处短路掉的
        verify { context.getSystemService(OPLUS_LINEAR_MOTOR_SERVICE) }
    }

    @Test
    fun `触感包代码里不许用 FLAG_IGNORE_GLOBAL_SETTING`() {
        val sources = hapticSources()

        assertWithMessage("没定位到 haptic 源码目录，这条红线断言就成了空跑").that(sources).isNotEmpty()
        assertWithMessage("定位到的目录不对").that(sources.map { it.name }).contains("HapticCapabilities.kt")
        // 只看代码：KDoc 里写「禁止用 FLAG_IGNORE_GLOBAL_SETTING」是在钉红线，不是在犯规，
        // 按整份文本搜会把那几处说明也判成违规
        val offenders = sources.filter { FORBIDDEN_FLAG in stripComments(it.readText()) }.map { it.name }
        assertWithMessage("不覆盖用户的系统触感设置：$FORBIDDEN_FLAG 整个触感包都禁用")
            .that(offenders)
            .isEmpty()
    }

    /**
     * 同一条红线，扫描范围从触感包放宽到 `src/main/java` 全部代码。
     *
     * 理由：这两个 flag 的危害与它写在哪个包里无关 —— 任何一处
     * `VibrationAttributes` 上挂了它，用户在系统设置里关掉的触感就会照震。
     * 触感包内的约束由上一条守着，这一条守的是「有人在别的包里直接调 Vibrator」。
     * `FLAG_IGNORE_VIEW_SETTING` 一并纳入：它绕的是 View 级开关，同族问题。
     */
    @Test
    fun `全仓主源码都不许用两个 IGNORE_SETTING flag`() {
        val sources = sourcesUnder(MAIN_SOURCE_RELATIVE_PATH)

        assertWithMessage("没定位到主源码目录，这条红线断言就成了空跑").that(sources).isNotEmpty()
        assertWithMessage("定位到的目录不对").that(sources.map { it.name })
            .contains("HapticCapabilities.kt")

        // 先按整份文本粗筛，只对真的出现过 flag 名的文件去注释 —— 全仓上千个 .kt，
        // 逐个走 stripComments 的字符扫描没必要
        val offenders = sources
            .map { it to it.readText() }
            .filter { (_, text) -> FORBIDDEN_FLAG in text || FORBIDDEN_VIEW_FLAG in text }
            .filter { (_, text) ->
                val code = stripComments(text)
                FORBIDDEN_FLAG in code || FORBIDDEN_VIEW_FLAG in code
            }
            .map { (file, _) -> file.name }

        assertWithMessage(
            "不覆盖用户的系统触感设置：$FORBIDDEN_FLAG / $FORBIDDEN_VIEW_FLAG 全仓禁用"
        ).that(offenders).isEmpty()
    }

    /**
     * 装一台设备。三项查询都收 lambda 而不是常量，是为了让「这一项抛 Error」也能表达。
     *
     * @param primitives 收到的候选 ID 列表，返回等长的支持位；测试可以故意返回错长度或直接抛
     */
    private fun stubMotor(
        hasVibrator: Boolean = true,
        amplitudeControl: () -> Boolean = { true },
        primitives: (List<Int>) -> BooleanArray = { ids -> BooleanArray(ids.size) },
    ) {
        every { vibrator.hasVibrator() } returns hasVibrator
        every { vibrator.hasAmplitudeControl() } answers { amplitudeControl() }
        every { vibrator.arePrimitivesSupported(*anyIntVararg()) } answers {
            val ids = requestedIds(args)
            primitiveQueries += ids
            primitives(ids)
        }
    }

    /** MockK 记 vararg 时既可能给整个数组也可能逐个展开，两种都兜住。 */
    private fun requestedIds(args: List<Any?>): List<Int> = when (val first = args.firstOrNull()) {
        is IntArray -> first.toList()
        else -> args.filterIsInstance<Int>()
    }

    /** 只改 SDK_INT 验门控。Robolectric 插桩后这个字段不是 final，@After 会还原。 */
    private fun overrideSdkInt(value: Int) {
        ReflectionHelpers.setStaticField(Build.VERSION::class.java, "SDK_INT", value)
    }

    /** 造一份快照，只关心振幅控制与 primitive 两项，其余给保守值。 */
    private fun snapshot(
        hasAmplitudeControl: Boolean,
        supportedPrimitives: Set<Int> = emptySet(),
    ) = HapticCapabilities(
        hasVibrator = true,
        hasAmplitudeControl = hasAmplitudeControl,
        supportedPrimitives = supportedPrimitives,
        compositionSizeMax = if (supportedPrimitives.isEmpty()) 0 else FALLBACK_COMPOSITION_SIZE_MAX,
        envelopeSupported = false,
        envelopeMaxSize = 0,
        richTapSupported = false,
        hapticPlayerSupported = false,
        miuiSupported = false,
        oplusSupported = false,
    )

    /**
     * 找出某个相对路径下全部 .kt。
     *
     * 单测的工作目录一般是 app 模块目录，从仓库根跑时又是仓库根，所以逐级往上找、
     * 两种前缀都试。找不到就返回空列表，让调用方把「定位失败」判成失败而不是悄悄通过。
     */
    private fun sourcesUnder(relativePath: String): List<File> {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            for (prefix in listOf("", "app/")) {
                val candidate = File(dir, prefix + relativePath)
                if (candidate.isDirectory) {
                    return candidate.walkTopDown()
                        .filter { it.isFile && it.extension == "kt" }
                        .sortedBy { it.name }
                        .toList()
                }
            }
            dir = dir.parentFile
        }
        return emptyList()
    }

    private fun hapticSources(): List<File> = sourcesUnder(HAPTIC_SOURCE_RELATIVE_PATH)

    /**
     * 去掉 Kotlin 注释只留代码，让红线断言分得清「文档里写着禁止」与「代码里真的用了」。
     *
     * 块注释按 Kotlin 的规则可嵌套，所以数深度而不是找第一个结束符；字符串与字符字面量里的
     * 斜杠星号不算注释起点，否则一个 URL 字面量就能把后面半行代码从检查里抹掉。
     */
    private fun stripComments(source: String): String {
        val code = StringBuilder()
        var index = 0
        var blockDepth = 0
        while (index < source.length) {
            val two = if (source.length - index >= 2) source.substring(index, index + 2) else ""
            when {
                blockDepth > 0 -> when (two) {
                    "/*" -> { blockDepth++; index += 2 }
                    "*/" -> { blockDepth--; index += 2 }
                    else -> index++
                }
                two == "/*" -> { blockDepth = 1; index += 2 }
                two == "//" -> while (index < source.length && source[index] != '\n') index++
                source.startsWith("\"\"\"", index) -> {
                    val end = source.indexOf("\"\"\"", index + 3)
                    index = if (end < 0) source.length else end + 3
                }
                source[index] == '"' || source[index] == '\'' -> {
                    val quote = source[index]
                    index++
                    while (index < source.length && source[index] != quote) {
                        index += if (source[index] == '\\') 2 else 1
                    }
                    index++
                }
                else -> code.append(source[index++])
            }
        }
        return code.toString()
    }

    private companion object {
        /** 与被测类里那个私有常量同值：反射拿不到 getCompositionSizeMax 时的保守容量。 */
        const val FALLBACK_COMPOSITION_SIZE_MAX = 2

        const val OPLUS_LINEAR_MOTOR_SERVICE = "linearmotor"

        const val FORBIDDEN_FLAG = "FLAG_IGNORE_GLOBAL_SETTING"

        /**
         * 同族的第二个开关：`FLAG_IGNORE_VIEW_SETTING` 绕的是 View 级的
         * `isHapticFeedbackEnabled`，一样是在替用户改主意，一样禁用。
         */
        const val FORBIDDEN_VIEW_FLAG = "FLAG_IGNORE_VIEW_SETTING"

        const val HAPTIC_SOURCE_RELATIVE_PATH = "src/main/java/com/tracktosearch/ui/haptic"

        const val MAIN_SOURCE_RELATIVE_PATH = "src/main/java"

        /** API 30 引入的五个 primitive。 */
        val API_30_PRIMITIVES = listOf(
            VibrationEffect.Composition.PRIMITIVE_CLICK,
            VibrationEffect.Composition.PRIMITIVE_TICK,
            VibrationEffect.Composition.PRIMITIVE_QUICK_RISE,
            VibrationEffect.Composition.PRIMITIVE_SLOW_RISE,
            VibrationEffect.Composition.PRIMITIVE_QUICK_FALL,
        )

        /** API 31 起再补三个，探测的候选表应当正好是这八个。 */
        val API_31_PRIMITIVES = API_30_PRIMITIVES + listOf(
            VibrationEffect.Composition.PRIMITIVE_THUD,
            VibrationEffect.Composition.PRIMITIVE_SPIN,
            VibrationEffect.Composition.PRIMITIVE_LOW_TICK,
        )
    }
}
