package com.tracktosearch.ui.haptic.backend

import android.content.Context
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.tracktosearch.ui.haptic.HapticCapabilities
import com.tracktosearch.ui.haptic.HapticSemantic
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [tierOneRecipeOf] 与 [toWaveformAmplitude] 的映射单测。**纯 JVM，不上 Robolectric。**
 *
 * tier 1 的错法几乎全是静默的：primitive 抄错一个 ID，整段 `Composition` 一点都不震；
 * 振幅换算把一个正振幅四舍五入成 0，那一段就悄悄没了；某条配方的 scale 定太低，
 * 全段都被压成 0，用户只看到「这个按钮没反馈」，日志里什么都没有。
 * 所以这张表逐格钉死，并额外钉住几条一旦破掉就只能靠手指发现的不变式。
 *
 * 期望的 primitive ID 刻意写成整型字面量（见私有 companion），不写成
 * `VibrationEffect.Composition.PRIMITIVE_X`：一是让期望值与被测代码不同源，
 * 二是整份测试连一个 android 类都不必加载。字面量自身抄错的风险由
 * 「写死的 primitive ID 与 SDK 常量一致」那条兜住 —— 它是全文唯一引用 SDK 常量的地方，
 * 而 Java 的 `static final int` 会在编译期内联成字面量，所以那条也仍是纯 JVM。
 *
 * scale 一列对着设计文档 `docs/superpowers/plans/2026-09-01-haptics-overhaul.md`
 * 「语义词表」的 tier 1 列抄：文档钉死了 `toggleOn` 0.7、`toggleOff` 0.5、`gestureEnd` 0.5、
 * `scrollEdge` 0.4，其余未标 scale 的按满幅算；`frequentTick` 文档只写「低 scale」没给数，
 * 实现取 0.35，这里按 0.35 钉住并另有一条不变式说明它必须低于 `scrollEdge`。
 *
 * 刻意不断言各段台阶的绝对振幅：那些数值取自标称峰值 `NOMINAL_PEAK = 210 / 255`，
 * 是唯一一处等着上真机复核的手感参数，钉死它只会让调音的人来删测试。
 * 这里断言的是**次序与关系**（谁比谁轻、增强后不许变轻），那些才是改数值也不该破的东西。
 *
 * 本文件里还有一个 [AospWaveformEnvelopeGuardTest]，那组是 `playEnvelope` 的入参校验 ——
 * 那段逻辑没有纯函数出口，只能连着实例一起验，所以单独拆一个类、单独上 Robolectric，
 * 免得把这十一条纯函数断言也拖进沙箱。
 */
class AospWaveformMappingTest {

    /**
     * 13 个语义在 Composition 子通路上的完整表达：primitive ID、scale、起播前的停顿。
     *
     * `delayMs` 一列同样逐格写出而不是只写第一笔：首笔非 0 会让每次点击整体迟到，
     * 而两笔配方的第二笔为 0 会让两笔叠在同一瞬间糊成一记重击 —— 两种都是手感事故。
     */
    private val expectedPrimitives: Map<HapticSemantic, List<PrimitiveStep>> = mapOf(
        HapticSemantic.TAP to listOf(PrimitiveStep(CLICK, 1f, 0)),
        HapticSemantic.LIGHT_TAP to listOf(PrimitiveStep(TICK, 1f, 0)),
        HapticSemantic.SEGMENT_TICK to listOf(PrimitiveStep(LOW_TICK, 1f, 0)),
        HapticSemantic.FREQUENT_TICK to listOf(PrimitiveStep(LOW_TICK, 0.35f, 0)),
        HapticSemantic.TOGGLE_ON to listOf(PrimitiveStep(CLICK, 0.7f, 0)),
        HapticSemantic.TOGGLE_OFF to listOf(PrimitiveStep(TICK, 0.5f, 0)),
        HapticSemantic.CONFIRM to listOf(
            PrimitiveStep(CLICK, 1f, 0),
            PrimitiveStep(THUD, 1f, CONFIRM_GAP_MS),
        ),
        HapticSemantic.REJECT to listOf(
            PrimitiveStep(THUD, 1f, 0),
            PrimitiveStep(THUD, 1f, REJECT_GAP_MS),
        ),
        HapticSemantic.DRAG_START to listOf(PrimitiveStep(THUD, 1f, 0)),
        HapticSemantic.THRESHOLD_ARMED to listOf(PrimitiveStep(QUICK_RISE, 1f, 0)),
        HapticSemantic.GESTURE_END to listOf(PrimitiveStep(THUD, 0.5f, 0)),
        HapticSemantic.SCROLL_EDGE to listOf(PrimitiveStep(LOW_TICK, 0.4f, 0)),
        HapticSemantic.POPUP_SHOW to listOf(PrimitiveStep(TICK, 1f, 0)),
    )

    @Test
    fun `13 个语义的 primitive 与 scale 逐格对上语义词表 tier 1 列`() {
        val actual = HapticSemantic.entries.associateWith { tierOneRecipeOf(it).primitives }

        assertThat(actual).containsExactlyEntriesIn(expectedPrimitives)
    }

    @Test
    fun `本测试写死的 primitive ID 与 SDK 的 PRIMITIVE 常量一致`() {
        // 全文唯一引用 SDK 常量的地方，为的是兜住上面那批字面量手抄出错。
        // static final int 在编译期就被内联，所以这条也不会在运行期加载任何 android 类
        assertThat(CLICK).isEqualTo(VibrationEffect.Composition.PRIMITIVE_CLICK)
        assertThat(THUD).isEqualTo(VibrationEffect.Composition.PRIMITIVE_THUD)
        assertThat(SPIN).isEqualTo(VibrationEffect.Composition.PRIMITIVE_SPIN)
        assertThat(QUICK_RISE).isEqualTo(VibrationEffect.Composition.PRIMITIVE_QUICK_RISE)
        assertThat(SLOW_RISE).isEqualTo(VibrationEffect.Composition.PRIMITIVE_SLOW_RISE)
        assertThat(QUICK_FALL).isEqualTo(VibrationEffect.Composition.PRIMITIVE_QUICK_FALL)
        assertThat(TICK).isEqualTo(VibrationEffect.Composition.PRIMITIVE_TICK)
        assertThat(LOW_TICK).isEqualTo(VibrationEffect.Composition.PRIMITIVE_LOW_TICK)
        // 八个取值互不相同是平台事实；重复就说明有一格抄成了邻居
        assertThat(ALL_PRIMITIVE_IDS.toSet()).hasSize(ALL_PRIMITIVE_IDS.size)
    }

    @Test
    fun `13 条配方一条都不许整段静默，也不许出现非法台阶`() {
        HapticSemantic.entries.forEach { semantic ->
            val recipe = tierOneRecipeOf(semantic)
            assertWithMessage("$semantic 没有振幅台阶：supports() 会返回 false，这个语义在本层直接消失")
                .that(recipe.steps)
                .isNotEmpty()
            assertWithMessage("$semantic 没有 primitive：Composition 子通路对它永远走不通，只剩台阶兜底")
                .that(recipe.primitives)
                .isNotEmpty()
            recipe.steps.forEachIndexed { index, step ->
                assertWithMessage("$semantic 第 $index 段时长 ${step.durationMs} ms 不是正数，createWaveform 会忽略它")
                    .that(step.durationMs)
                    .isGreaterThan(0)
                assertWithMessage(
                    "$semantic 第 $index 段时长 ${step.durationMs} ms 不是爬坡粒度 $RAMP_STEP_MS ms 的整数倍，" +
                        "多出来那一截会被 HAL 的爬坡吃掉",
                )
                    .that(step.durationMs % RAMP_STEP_MS)
                    .isEqualTo(0)
                // NaN 会被 toWaveformAmplitude 吃成 0，那一段就悄悄没了；
                // QUICK_RISE 那条按 index / (steps - 1) 算进度，步数被改成 1 就正好除出 NaN
                assertWithMessage("$semantic 第 $index 段振幅是 NaN，会被换算成 0，这一段悄悄不震")
                    .that(step.amplitude)
                    .isNotNaN()
                assertWithMessage("$semantic 第 $index 段振幅 ${step.amplitude} 为负，只会被夹成静默")
                    .that(step.amplitude)
                    .isAtLeast(0)
                // 越过 1f 会被夹成 255：夹掉的那部分让两个语义在真机上听起来一样重，
                // 而 scale 的差异就此失效，属于「表面上分了档，实际没分」
                assertWithMessage("$semantic 第 $index 段振幅 ${step.amplitude} 越过 1f，夹平之后 scale 分档就失效了")
                    .that(step.amplitude)
                    .isAtMost(1)
            }
            assertWithMessage("$semantic 全部台阶都被换算成 0，整条效果一点都不震，属静默失败")
                .that(peakOf(semantic))
                .isGreaterThan(0)
            assertWithMessage("$semantic 首笔 primitive 的 delayMs 不是 0，每次触发都会整体迟到")
                .that(recipe.primitives.first().delayMs)
                .isEqualTo(0)
            recipe.primitives.forEachIndexed { index, primitive ->
                assertWithMessage("$semantic 第 $index 笔的 scale ${primitive.scale} 越界，addPrimitive 只收 0f..1f")
                    .that(primitive.scale)
                    .isAtLeast(0)
                assertWithMessage("$semantic 第 $index 笔的 scale ${primitive.scale} 越界，addPrimitive 只收 0f..1f")
                    .that(primitive.scale)
                    .isAtMost(1)
            }
        }
    }

    @Test
    fun `探测组是整组的：每个语义的组等于它用到的全部 primitive`() {
        val groups = HapticSemantic.entries.associateWith { groupOf(it) }

        // 硬规则 2：一个效果用到的全部 primitive 必须一次传进 areAllPrimitivesSupported。
        // CONFIRM 是全表唯一跨两个 primitive 的语义，也就是这条规则唯一能被验到的地方 ——
        // 只探 CLICK 就发的话，在支持 CLICK 不支持 THUD 的机器上整段一点都不震
        assertWithMessage("CONFIRM 的探测组必须同时含 CLICK 与 THUD，缺一个就整段静默")
            .that(groups.getValue(HapticSemantic.CONFIRM))
            .containsExactly(CLICK, THUD)
        assertWithMessage("跨多个 primitive 的语义变了：CONFIRM 之外再有一个就得回来补进这条断言")
            .that(groups.filterValues { it.size > 1 }.keys)
            .containsExactly(HapticSemantic.CONFIRM)
        // 组内元素必须覆盖配方里出现的每一个 ID，一个都不许漏在组外
        HapticSemantic.entries.forEach { semantic ->
            val used = tierOneRecipeOf(semantic).primitives.map { it.primitiveId }.toSet()
            assertWithMessage("$semantic 的探测组 ${groups.getValue(semantic)} 与实际用到的 $used 不一致")
                .that(groups.getValue(semantic).toSet())
                .isEqualTo(used)
        }
        // 13 个语义只落在 6 组不同组合上：整层最多 6 次 areAllPrimitivesSupported 的跨进程往返，
        // 之后全命中缓存。组数变多就意味着点击路径上多了几次 IPC
        assertWithMessage("不同探测组的数量变了，整层的 IPC 次数与缓存假设跟着变")
            .that(groups.values.distinct())
            .containsExactly(
                listOf(CLICK),
                listOf(THUD),
                listOf(QUICK_RISE),
                listOf(TICK),
                listOf(LOW_TICK),
                listOf(CLICK, THUD),
            )
        val used = groups.values.flatten().toSet()
        assertWithMessage("tier 1 词表只用这五个 primitive")
            .that(used)
            .containsExactly(CLICK, THUD, QUICK_RISE, TICK, LOW_TICK)
        // SLOW_RISE 与 QUICK_FALL 只出现在彩蛋的连续包络里（那条走 playEnvelope），
        // SPIN 整份词表没用到；它们冒出来就说明有语义被换了 primitive
        assertWithMessage("SPIN / SLOW_RISE / QUICK_FALL 不在 tier 1 词表里，出现即有语义被换了 primitive")
            .that(used)
            .containsNoneOf(SPIN, SLOW_RISE, QUICK_FALL)
    }

    @Test
    fun `容量判据要看未去重的笔数，最长配方正好落在保守容量上`() {
        val reject = tierOneRecipeOf(HapticSemantic.REJECT)

        // THUD 两连：去重后只有一个 ID，可要往 Composition 里塞两笔。
        // 容量判据若拿去重后的 1 去比，容量只有 1 的机器上第二笔就塞不进去了
        assertWithMessage("REJECT 是 THUD 两连，笔数与去重后的 ID 数刻意不等，用来钉住容量判据看的是笔数")
            .that(reject.primitives)
            .hasSize(2)
        assertThat(reject.primitives.map { it.primitiveId }.distinct()).hasSize(1)
        // 公开 SDK 没开放 getCompositionSizeMax()，反射被拦时 HapticCapabilities 退到保守容量 2。
        // 哪条配方超过 2 笔，它的 Composition 子通路在所有反射被拦的机器上就静默走不通了
        assertWithMessage(
            "有配方的笔数超过了 HapticCapabilities 的保守容量 $FALLBACK_COMPOSITION_SIZE_MAX，" +
                "反射拿不到真实容量的机器上这条语义的 Composition 通路会被整条跳过",
        )
            .that(HapticSemantic.entries.maxOf { tierOneRecipeOf(it).primitives.size })
            .isEqualTo(FALLBACK_COMPOSITION_SIZE_MAX)
    }

    @Test
    fun `两笔配方在两条子通路上的间隔必须同源`() {
        listOf(HapticSemantic.CONFIRM, HapticSemantic.REJECT).forEach { semantic ->
            val recipe = tierOneRecipeOf(semantic)
            val gapMs = recipe.primitives[1].delayMs
            val silent = recipe.steps.filter { toWaveformAmplitude(it.amplitude) == 0 }

            assertWithMessage("$semantic 的振幅台阶里应恰好有一段静默间隙，把两笔隔开")
                .that(silent)
                .hasSize(1)
            // 两条子通路是同一手感目标的两种写法：间隔各自写死就会在某次调音里悄悄漂开，
            // 于是同一台机器上换条子通路手感就变了，而两条路都「没报错」
            assertWithMessage(
                "$semantic 两条子通路的间隔漂了：Composition 是 $gapMs ms，" +
                    "振幅台阶是 ${silent.single().durationMs} ms",
            )
                .that(silent.single().durationMs)
                .isEqualTo(gapMs)
            assertWithMessage("$semantic 第二笔的 delayMs 是 0，两笔会叠在同一瞬间糊成一记重击")
                .that(gapMs)
                .isGreaterThan(0)
        }
        // CONFIRM 是「收到 + 落地」连成一句，REJECT 是两下明确的「不行」，
        // 两者的间隔一样长就会把失败听成成功
        assertWithMessage("REJECT 的间隔不再长于 CONFIRM，两个语义在体感上分不开了")
            .that(tierOneRecipeOf(HapticSemantic.REJECT).primitives[1].delayMs)
            .isGreaterThan(tierOneRecipeOf(HapticSemantic.CONFIRM).primitives[1].delayMs)
    }

    @Test
    fun `振幅换算的边界逐个钉死`() {
        // 0 是「马达不转」，配方里的静默间隙全靠它；映成 1 的话间隙也在轻轻震
        assertWithMessage("0f 是配方里的静默间隙，必须映成整数 0")
            .that(toWaveformAmplitude(0f))
            .isEqualTo(0)
        assertWithMessage("1f 必须映成 createWaveform 的上限 255")
            .that(toWaveformAmplitude(1f))
            .isEqualTo(MAX_AMPLITUDE)
        // 负零与负值：按 0 算而不是取绝对值，也不是抛
        assertThat(toWaveformAmplitude(-0f)).isEqualTo(0)
        assertThat(toWaveformAmplitude(-0.5f)).isEqualTo(0)
        assertThat(toWaveformAmplitude(-1f)).isEqualTo(0)
        assertThat(toWaveformAmplitude(Float.NEGATIVE_INFINITY)).isEqualTo(0)
        // NaN 若漏下去，createWaveform 收到的整数是 0（NaN 比较全 false 的经典坑），
        // 或者更糟：某处先乘再取整得到负数直接抛 IllegalArgumentException
        assertWithMessage("NaN 必须在换算这一步吃掉，不能带到 createWaveform")
            .that(toWaveformAmplitude(Float.NaN))
            .isEqualTo(0)
        // 越界的一律夹到 255，不能把 256 以上的整数交给 createWaveform（那是直接抛）
        assertWithMessage("超过 1f 必须夹到 255，越界整数会让 createWaveform 抛")
            .that(toWaveformAmplitude(1.5f))
            .isEqualTo(MAX_AMPLITUDE)
        assertThat(toWaveformAmplitude(Float.MAX_VALUE)).isEqualTo(MAX_AMPLITUDE)
        assertThat(toWaveformAmplitude(Float.POSITIVE_INFINITY)).isEqualTo(MAX_AMPLITUDE)
        // 半幅按四舍五入取 128，不是截断成 127
        assertThat(toWaveformAmplitude(0.5f)).isEqualTo(128)
        // 正好一格：1 / 255 必须回到 1
        assertThat(toWaveformAmplitude(1f / MAX_AMPLITUDE)).isEqualTo(1)
    }

    @Test
    fun `任何正振幅都不许被四舍五入吞成 0`() {
        // 四舍五入把 0.001f 压成 0 就等于把一次「要震」悄悄吞掉：调用方拿不到任何信号，
        // 引擎也不会降级 —— 它以为这一层已经发出去了
        val tinyButPositive = listOf(
            Float.MIN_VALUE,
            1e-20f,
            1e-9f,
            1e-4f,
            0.001f,
            1f / 1024f,
            1.9f / MAX_AMPLITUDE,
        )

        tinyButPositive.forEach { fraction ->
            assertWithMessage("$fraction 是正振幅，压成 0 就是把一次该震的悄悄吞掉")
                .that(toWaveformAmplitude(fraction))
                .isAtLeast(1)
        }
    }

    @Test
    fun `振幅换算在整个 0f 到 1f 区间单调不减且不越界`() {
        // 非单调就意味着两个语义的轻重次序会在某一段上颠过来，而两边都「有震动」，
        // 只能靠手指发现。所以整段扫一遍，而不是抽查几个点
        var previous = toWaveformAmplitude(0f)
        assertThat(previous).isEqualTo(0)

        for (index in 1..SWEEP_STEPS) {
            val fraction = index.toFloat() / SWEEP_STEPS
            val current = toWaveformAmplitude(fraction)
            assertWithMessage("$fraction 换算出 $current，比前一格的 $previous 还小，换算不再单调")
                .that(current)
                .isAtLeast(previous)
            assertWithMessage("$fraction 换算出 $current，正振幅落到了 0")
                .that(current)
                .isAtLeast(1)
            assertWithMessage("$fraction 换算出 $current，越过了 createWaveform 的上限")
                .that(current)
                .isAtMost(MAX_AMPLITUDE)
            previous = current
        }
        assertThat(previous).isEqualTo(MAX_AMPLITUDE)
    }

    @Test
    fun `增强档不许把语义换成更轻的效果`() {
        // 「增强」只改效果的选择、不乘系数（系统 VibrationScaler 已经按用户档位乘过一轮），
        // 所以唯一能验的就是：换过去的那个语义在 tier 1 上不能比原来更轻。
        // 破了这条，用户在设置里选「增强」反而变轻，而两边都有震动，没人会去查
        HapticSemantic.entries.forEach { semantic ->
            val boosted = semantic.boosted()
            assertWithMessage(
                "$semantic 增强后换成 $boosted，峰值从 ${peakOf(semantic)} 掉到 ${peakOf(boosted)}，" +
                    "增强档反而更轻",
            )
                .that(peakOf(boosted))
                .isAtLeast(peakOf(semantic))
        }
    }

    @Test
    fun `FREQUENT_TICK 是唯一最轻的一档，升级链严格变重`() {
        val peaks = HapticSemantic.entries.associateWith { peakOf(it) }
        val lightest = peaks.values.min()

        // HapticSemantic 把 FREQUENT_TICK 钉成整条梯度最轻的一档：它一次拖动里连发几十次，
        // 单次稍重整段手势就累成惩罚。并列最轻也不行 —— 那说明有别的语义被调到了同一档
        assertWithMessage("FREQUENT_TICK 应是全表唯一最轻的一档，它一次手势里要连发几十次")
            .that(peaks.filterValues { it == lightest }.keys)
            .containsExactly(HapticSemantic.FREQUENT_TICK)
        // boosted() 的最长一条链，四档必须严格拉开：任意相邻两档等值，那一步升级就白升了
        listOf(
            HapticSemantic.FREQUENT_TICK,
            HapticSemantic.SEGMENT_TICK,
            HapticSemantic.LIGHT_TAP,
            HapticSemantic.TAP,
        ).zipWithNext().forEach { (lighter, heavier) ->
            assertWithMessage(
                "$lighter 的峰值 ${peaks.getValue(lighter)} 没有低于 $heavier 的 ${peaks.getValue(heavier)}，" +
                    "这一步升级白升了",
            )
                .that(peaks.getValue(lighter))
                .isLessThan(peaks.getValue(heavier))
        }
        assertWithMessage("边界会被连着顶，SCROLL_EDGE 必须明显轻于 SEGMENT_TICK")
            .that(peaks.getValue(HapticSemantic.SCROLL_EDGE))
            .isLessThan(peaks.getValue(HapticSemantic.SEGMENT_TICK))
        assertWithMessage("关比开轻是开关族的辨识度所在")
            .that(peaks.getValue(HapticSemantic.TOGGLE_OFF))
            .isLessThan(peaks.getValue(HapticSemantic.TOGGLE_ON))
        assertWithMessage("落定要「到位」但不能像拖拽起手那么沉")
            .that(peaks.getValue(HapticSemantic.GESTURE_END))
            .isLessThan(peaks.getValue(HapticSemantic.DRAG_START))
        // Composition 子通路上没有台阶可比，只能比 scale：同一个 LOW_TICK 的两个语义，
        // 次序必须和台阶那条一致，否则换条子通路轻重就反了
        assertWithMessage("同为 LOW_TICK，FREQUENT_TICK 的 scale 必须低于 SCROLL_EDGE，否则换条子通路次序就反了")
            .that(scaleOf(HapticSemantic.FREQUENT_TICK) < scaleOf(HapticSemantic.SCROLL_EDGE))
            .isTrue()
        assertWithMessage("同为 TICK，TOGGLE_OFF 的 scale 必须低于 LIGHT_TAP")
            .that(scaleOf(HapticSemantic.TOGGLE_OFF) < scaleOf(HapticSemantic.LIGHT_TAP))
            .isTrue()
        assertWithMessage("同为 THUD，GESTURE_END 的 scale 必须低于 DRAG_START")
            .that(scaleOf(HapticSemantic.GESTURE_END) < scaleOf(HapticSemantic.DRAG_START))
            .isTrue()
    }

    /**
     * 一条配方在 `createWaveform` 子通路上的峰值整数振幅。0 表示整条效果一点都不震。
     *
     * 取整数而不是浮点比例，是因为用户实际感受到的就是换算后的这个整数：
     * 两个语义的浮点比例差 0.001 而取整后同值，在真机上就是同一档手感。
     */
    private fun peakOf(semantic: HapticSemantic): Int =
        tierOneRecipeOf(semantic).steps.maxOf { toWaveformAmplitude(it.amplitude) }

    /** 单 primitive 语义的 scale。多笔配方调用它会直接抛，正好挡住误用。 */
    private fun scaleOf(semantic: HapticSemantic): Float =
        tierOneRecipeOf(semantic).primitives.single().scale

    /**
     * 一个语义交给 `areAllPrimitivesSupported(vararg)` 的探测组。
     *
     * 去重加排序与被测实现同法：排序是为了让缓存能命中同一个键，去重是因为
     * THUD 两连问一次就够。数量判据不能用这个去重后的表，见容量那条测试。
     */
    private fun groupOf(semantic: HapticSemantic): List<Int> =
        tierOneRecipeOf(semantic).primitives.map { it.primitiveId }.distinct().sorted()

    /**
     * primitive ID 取自 `javap -constants` 对 `platforms/android-37.0/android.jar` 的输出。
     *
     * 名字与 `VibrationEffect.Composition` 的字段去掉 `PRIMITIVE_` 前缀后同名，
     * 这样每一格期望值读起来就是常量名。语义枚举一律写成 `HapticSemantic.XXX`，不会混淆。
     */
    private companion object {
        /** `PRIMITIVE_CLICK`，API 30 */
        const val CLICK = 1

        /** `PRIMITIVE_THUD`，API 31 */
        const val THUD = 2

        /** `PRIMITIVE_SPIN`，API 31。tier 1 词表没用到，只用来断言它没被误用 */
        const val SPIN = 3

        /** `PRIMITIVE_QUICK_RISE`，API 30 */
        const val QUICK_RISE = 4

        /** `PRIMITIVE_SLOW_RISE`，API 30。只出现在彩蛋的连续包络里，不该进离散配方 */
        const val SLOW_RISE = 5

        /** `PRIMITIVE_QUICK_FALL`，API 30。同上 */
        const val QUICK_FALL = 6

        /** `PRIMITIVE_TICK`，API 30 */
        const val TICK = 7

        /** `PRIMITIVE_LOW_TICK`，API 31 */
        const val LOW_TICK = 8

        /** `confirm` 里 CLICK 与 THUD 之间的停顿，毫秒。设计文档只说「CLICK + THUD」，数值由实现定 */
        const val CONFIRM_GAP_MS = 40

        /** `reject` 里两记 THUD 之间的停顿，毫秒。同样由实现定，只钉住它必须长于 CONFIRM */
        const val REJECT_GAP_MS = 70

        /** 目标机实测的 HAL 爬坡粒度，台阶时长都该是它的整数倍 */
        const val RAMP_STEP_MS = 5

        /** `createWaveform` 振幅的整数上限，出处是它的文档「between 0 and 255」 */
        const val MAX_AMPLITUDE = 255

        /** 与 `HapticCapabilities` 里那个私有常量同值：反射拿不到真实容量时的保守下限 */
        const val FALLBACK_COMPOSITION_SIZE_MAX = 2

        /** 单调性扫描的格数。比 255 个可能取值密得多，才扫得到取整边界上的翻转 */
        const val SWEEP_STEPS = 4000

        /** 八个 primitive ID，只用来断言互不相同 */
        val ALL_PRIMITIVE_IDS =
            listOf(CLICK, THUD, SPIN, QUICK_RISE, SLOW_RISE, QUICK_FALL, TICK, LOW_TICK)
    }
}

/**
 * [AospWaveformBackend.playEnvelope] 的入参校验。
 *
 * 为什么这一组不在上面那个纯 JVM 类里：`timingsMs` 与 `amplitudes` 的长度校验写在
 * `playEnvelope` 里，而它的第一道门是 `isAvailable()`，要一个真的 `Vibrator` 才过得去；
 * 越界那一步又发生在私有的 `envelopeEffectFor` 里
 * （`timingsMs.indices.map { WaveformStep(timingsMs[it], amplitudes[it]) }`，
 * 长度不等就是 `ArrayIndexOutOfBoundsException`），从任何纯函数都看不见。
 * 换句话说：**这段校验逻辑没有纯函数出口**，只能连着实例一起验。
 *
 * 于是照本仓 `HapticCapabilitiesTest` 已经跑通的那套来：Robolectric 提供 `AudioAttributes`
 * 与 `VibrationAttributes` 的真实实现，`Vibrator` 用 MockK（它的构造器是包私有的，
 * 测试侧写不出自己的假实现，而 `ShadowVibrator` 也没有 `VibratorManager` 的影子）。
 *
 * 固定在 sdk 33：这一档 `VibrationAttributes.createForUsage` 与
 * `Vibrator.vibrate(effect, attributes)` 都在（两者都是 API 33），
 * 而 API 36 的 `WaveformEnvelopeBuilder` 还没到，于是包络必然落在
 * `createWaveform` 的振幅台阶上 —— 那正是目标机上真正会走的那条子通路。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class AospWaveformEnvelopeGuardTest {
    private val vibrator = mockk<Vibrator>()
    private val vibratorManager = mockk<VibratorManager>()
    private val context = mockk<Context>()

    /** 派发到单线程 Executor 的那一次 `vibrate` 真的到了没有。 */
    private val dispatched = CountDownLatch(1)

    /**
     * `Vibrator.cancel()` 被下发了几次。
     *
     * 用计数而不是 `verify`：`cancel()` 把活儿投到 executor 上，主线程这边立刻返回，
     * `verify` 会在任务还没跑到时判假 —— 那是个时序假阴性，不是真的没停。
     */
    private val cancelCount = AtomicInteger(0)

    private lateinit var backend: AospWaveformBackend

    @Before
    fun setUp() {
        every { context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) } returns vibratorManager
        every { vibratorManager.defaultVibrator } returns vibrator
        every { vibrator.vibrate(any<VibrationEffect>(), any<VibrationAttributes>()) } answers {
            dispatched.countDown()
        }
        every { vibrator.cancel() } answers { cancelCount.incrementAndGet() }
        backend = AospWaveformBackend(context, linearMotor())
    }

    /** release() 必须 shutdown 自己那个单线程，漏了就是线程泄漏，还会污染同沙箱后面的用例。 */
    @After
    fun tearDown() {
        backend.release()
        unmockkAll()
    }

    @Test
    fun `两个数组长度不等时拒绝，不按下标越界取值`() {
        // 先证明这台设备上包络本来是能播的：后面那几个 false 才只可能来自入参校验，
        // 而不是 isAvailable() 一刀切掉的
        assertWithMessage("等长的合法入参应当被接受，否则后面的 false 说明不了任何事")
            .that(backend.playEnvelope(intArrayOf(20, 20), floatArrayOf(0.3f, 0.6f)))
            .isTrue()

        // 时长多一格：越界发生在 timingsMs.indices 那一轮里，会抛 ArrayIndexOutOfBoundsException，
        // 而且是在 executor 线程上抛 —— 调用方拿到的是 true，异常被整层吞掉，只留下「整层被禁用」
        assertWithMessage("时长比振幅多一格必须当场拒绝，不能按下标去取不存在的振幅")
            .that(backend.playEnvelope(intArrayOf(20, 20, 20), floatArrayOf(0.3f, 0.6f)))
            .isFalse()
        assertWithMessage("振幅比时长多一格同样拒绝：多出来的那格会被悄悄丢掉，包络就变形了")
            .that(backend.playEnvelope(intArrayOf(20, 20), floatArrayOf(0.3f, 0.6f, 0.9f)))
            .isFalse()
        assertThat(backend.playEnvelope(intArrayOf(20), FloatArray(0))).isFalse()
        assertThat(backend.playEnvelope(IntArray(0), floatArrayOf(0.5f))).isFalse()
        // 两个都空：长度相等，但 createWaveform 收到空数组会直接抛
        assertWithMessage("空入参虽然长度相等，也必须拒绝：createWaveform 收到空数组会抛")
            .that(backend.playEnvelope(IntArray(0), FloatArray(0)))
            .isFalse()
    }

    @Test
    fun `时长非正与振幅越界或非数时拒绝，不把非法值交给 createWaveform`() {
        assertWithMessage("时长 0 的控制点不该被接受")
            .that(backend.playEnvelope(intArrayOf(0), floatArrayOf(0.5f)))
            .isFalse()
        assertWithMessage("负时长交给 createWaveform 会抛 IllegalArgumentException")
            .that(backend.playEnvelope(intArrayOf(-5), floatArrayOf(0.5f)))
            .isFalse()
        assertThat(backend.playEnvelope(intArrayOf(20), floatArrayOf(1.5f))).isFalse()
        assertThat(backend.playEnvelope(intArrayOf(20), floatArrayOf(-0.1f))).isFalse()
        assertWithMessage("NaN 振幅必须在这里挡住")
            .that(backend.playEnvelope(intArrayOf(20), floatArrayOf(Float.NaN)))
            .isFalse()
        // 非法值藏在一串合法值中间：只看第一格或只看最后一格都会漏
        assertWithMessage("夹在合法值中间的 NaN 也要挡住，校验不能只看第一格")
            .that(backend.playEnvelope(intArrayOf(20, 20, 20), floatArrayOf(0.2f, Float.NaN, 0.4f)))
            .isFalse()
        assertWithMessage("夹在合法值中间的 0 时长也要挡住")
            .that(backend.playEnvelope(intArrayOf(20, 0, 20), floatArrayOf(0.2f, 0.3f, 0.4f)))
            .isFalse()
        assertWithMessage("夹在合法值中间的越界振幅也要挡住")
            .that(backend.playEnvelope(intArrayOf(20, 20, 20), floatArrayOf(0.2f, 1.2f, 0.4f)))
            .isFalse()
    }

    @Test
    fun `合法包络真的走到了 vibrate，没在 executor 线程上静默塌掉`() {
        // 振幅含 0f 的收尾一格，且时长不是爬坡粒度的整数倍 —— 调用方给什么就该原样下发，
        // 本层不替它修正
        assertThat(backend.playEnvelope(intArrayOf(20, 40, 23), floatArrayOf(0.2f, 0.8f, 0f))).isTrue()

        assertWithMessage(
            "派发后 $DISPATCH_TIMEOUT_SECONDS 秒内没等到 vibrate：" +
                "效果构造在 executor 线程上抛了，异常被整层吃掉，调用方那一侧只看到 true",
        )
            .that(dispatched.await(DISPATCH_TIMEOUT_SECONDS, TimeUnit.SECONDS))
            .isTrue()
        // executor 里抛过就会把整层禁死，这里必须还活着
        assertWithMessage("整层被禁用了，说明刚那次派发在 executor 线程上出过错")
            .that(backend.isAvailable())
            .isTrue()
    }

    @Test
    fun `转子马达上整层不上场，合法包络也不播`() {
        // 硬规则 1：没有振幅控制时所有非零振幅都会被抬到 100%，包络画得多细都失真成一声嗡。
        // 校验顺序把 isAvailable() 放在最前面，就是为了不让这台机器进到下发那一步
        val rotary = AospWaveformBackend(context, linearMotor().copy(hasAmplitudeControl = false))

        try {
            assertThat(rotary.isAvailable()).isFalse()
            assertWithMessage("转子马达上包络必须直接拒绝")
                .that(rotary.playEnvelope(intArrayOf(20, 20), floatArrayOf(0.3f, 0.6f)))
                .isFalse()
            assertThat(rotary.supports(HapticSemantic.TAP)).isFalse()
            assertThat(rotary.perform(null, HapticSemantic.TAP)).isFalse()
        } finally {
            rotary.release()
        }
    }

    /**
     * `cancel()` 存在的全部理由：`playEnvelope` 把一整段波形交给 `VibratorService` 之后就
     * 再没有抓手，彩蛋最长的一段是签名的 7300 ms。用户按 ✕ 退出、按住暂停、或者息屏时，
     * 屏幕上什么都没了而手里还在震完剩下的几秒。
     *
     * 三条断言分别钉三种写错的方式：不下发（停不掉）、顺手置 `disabled`（这一层从此哑掉，
     * 而它是非 RichTap 机型上唯一能画波形的一层）、顺手 shutdown executor
     * （下一段包络再也起不来，且 `execute` 会抛 `RejectedExecutionException`）。
     */
    @Test
    fun `cancel 真的停掉在播的波形，但不禁用本层也不关掉 executor`() {
        assertThat(backend.playEnvelope(intArrayOf(20, 40), floatArrayOf(0.3f, 0.6f))).isTrue()
        assertWithMessage("包络本身没派发出去，后面关于 cancel 的断言就说明不了任何事")
            .that(dispatched.await(DISPATCH_TIMEOUT_SECONDS, TimeUnit.SECONDS))
            .isTrue()

        backend.cancel()

        assertWithMessage(
            "$DISPATCH_TIMEOUT_SECONDS 秒内没等到 Vibrator.cancel()：" +
                "退出彩蛋之后那段包络会自己播完",
        )
            .that(awaitCancelAtLeast(1))
            .isTrue()
        assertWithMessage("cancel 不该把整层禁掉 —— 它的意思是「现在安静」，不是「这层坏了」")
            .that(backend.isAvailable())
            .isTrue()
        assertWithMessage("cancel 不该 shutdown executor：停完之后本层还要继续用")
            .that(backend.playEnvelope(intArrayOf(20), floatArrayOf(0.5f)))
            .isTrue()
    }

    /**
     * `release()` 之后再调 `cancel()` 不许抛。
     *
     * 这不是洁癖：`quietDown` 挂在退出 / 息屏路径上，而 `release()` 也在同一条路径上，
     * 两者的先后由宿主生命周期决定。`release()` 已经 shutdown 了 executor，此时若 `cancel()`
     * 还往里 `execute`，抛出来的 `RejectedExecutionException` 正好落在用户离开彩蛋的那一刻。
     */
    @Test
    fun `release 之后 cancel 是安全的空操作`() {
        backend.release()
        assertWithMessage("release 自己那次 cancel 也要真的到，否则本用例的前提不成立")
            .that(awaitCancelAtLeast(1))
            .isTrue()
        val afterRelease = cancelCount.get()

        backend.cancel()
        backend.cancel()

        assertWithMessage("release 之后不该再往下发 cancel —— executor 已经关了")
            .that(cancelCount.get())
            .isEqualTo(afterRelease)
        assertThat(backend.isAvailable()).isFalse()
    }

    /** 轮询等 `cancel` 计数到位。`cancel()` 是异步投递，主线程直接读会假阴性。 */
    private fun awaitCancelAtLeast(target: Int): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(DISPATCH_TIMEOUT_SECONDS)
        while (System.nanoTime() < deadline) {
            if (cancelCount.get() >= target) return true
            Thread.sleep(POLL_INTERVAL_MS)
        }
        return cancelCount.get() >= target
    }

    /**
     * 目标机形态：有马达、有振幅控制，但一个 primitive 都不支持、也没有 AOSP 包络。
     *
     * 于是包络必然落在 `createWaveform` 的振幅台阶上，正是小米 14 Pro 上真正会走的那条子通路。
     */
    private fun linearMotor() = HapticCapabilities(
        hasVibrator = true,
        hasAmplitudeControl = true,
        supportedPrimitives = emptySet(),
        compositionSizeMax = 0,
        envelopeSupported = false,
        envelopeMaxSize = 0,
        richTapSupported = false,
        miuiSupported = false,
        oplusSupported = false,
    )

    private companion object {
        /** 等派发的上限。单线程 Executor 上就一个任务，正常远快于此，超时即代表那一步抛了 */
        const val DISPATCH_TIMEOUT_SECONDS = 5L

        /** 轮询 `cancel` 计数的间隔。取小值：正常情况下第一轮就到了 */
        const val POLL_INTERVAL_MS = 10L
    }
}
