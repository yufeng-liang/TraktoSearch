package com.tracktosearch.ui.haptic

import android.view.View
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Test

/**
 * [AppHaptics] 的降级「选择」单测：钉住一次派发究竟问了哪几层、按什么次序问、谁最后接下。
 *
 * 这套判定的故障全是静默的 —— 选错一层不崩不报错，只让某个交互在某类设备上悄悄换了手感，
 * 或者一点都不震。三种最贵的写法各对应下面一条测试：
 *
 * - 某层 `perform()` 返 false 就整次放弃、不再往下降级 → 厂商层排队被拒时整个 App 无触感。
 * - 降级链按入参顺序走 → Hilt 模块里的声明顺序一改手感跟着变，而两边读起来都对。
 * - 转子马达上放 tier 1 以上的层上场 → 非零振幅全被抬到 100%，一记轻点震成一声嗡（硬规则 1）。
 *
 * ### 离散链的期望次序是 2、3、1、0，不是单纯的 tier 降序
 *
 * 设计文档「厂商通路矩阵」末段钉死：同一台机上 RichTap 与厂商反射通路都可用时，
 * **离散语义走厂商预置效果**（厂商自己调过的手感比我们拼的波形好），
 * **彩蛋那两段连续包络走 RichTap**（唯一画得出包络的一层）。实现把这条落在 `discreteRank()`：
 * 离散链 tier 2、3、1、0；包络链 tier 降序 3、2、1、0，但**厂商层可用时跳过 tier 1**
 * （真机反馈：`createWaveform` 的通用波形在线性马达机型上是「普通震动」，包络宁可
 * 整体返 false 让调用方退离散替身）。所以本文件对两条链断言的次序不同。
 * 哪天有人把离散链「修」回单纯 tier 降序，目标机（RichTap 与 MIUI 都可用）上的手感会整片变掉，
 * 而 tier 降序读起来完全合理 —— 这正是要钉住它的理由。
 *
 * ### 构造期那一轮 isAvailable() 也在断言范围内
 *
 * 引擎构造期对每层各调一次 `isAvailable()`：MIUI 与 OPlus 靠这一下把逐 ID 探测甩上自己的
 * 单线程，不焐热第一次点击就会掉到下一层去。这一轮属构造期行为，所以 [dispatchTrace] 在
 * [engine] 里被清一次、只留派发阶段的调用；[FakeBackend] 的计数器则是全程累计的，
 * `availableCalls` 含构造期那一次。
 *
 * 全程零 android 调用：`view` 一律传 [NO_VIEW]（null），假 backend 不看它；真 tier 0 才需要
 * 真 View。纯 JVM 跑，不用 Robolectric 也不用 MockK。`onMiss` 必须自己传 —— 默认实现走
 * `android.util.Log`，纯 JVM 用例里会抛「not mocked」。
 *
 * 三态开关与 `release()` 的行为不在本文件，那是同伴的测试文件。
 */
class AppHapticsDegradationTest {

    /** 派发阶段的调用流水，形如 `"MIUI.supports"`。四层共享一份，用来钉次序 */
    private val dispatchTrace = mutableListOf<String>()

    /** 整条链都没接下时的落点。替掉默认的 `Log.d` 实现，顺便当「本次无声」的断言依据 */
    private val misses = mutableListOf<HapticSemantic>()

    /**
     * 13 个具名方法各自该派发的语义。逐条手抄，不写成从被测代码推导的形式：
     * 这张表要挡的正是「`toggleOff()` 里复制粘贴成了 `TOGGLE_ON`」这类改动 ——
     * 编译得过、跑得过，只有手感悄悄错了一格。
     */
    private val namedMethods: Map<HapticSemantic, (AppHaptics) -> Boolean> = mapOf(
        HapticSemantic.TAP to { engine: AppHaptics -> engine.tap(NO_VIEW) },
        HapticSemantic.LIGHT_TAP to { engine: AppHaptics -> engine.lightTap(NO_VIEW) },
        HapticSemantic.SEGMENT_TICK to { engine: AppHaptics -> engine.segmentTick(NO_VIEW) },
        HapticSemantic.FREQUENT_TICK to { engine: AppHaptics -> engine.frequentTick(NO_VIEW) },
        HapticSemantic.TOGGLE_ON to { engine: AppHaptics -> engine.toggleOn(NO_VIEW) },
        HapticSemantic.TOGGLE_OFF to { engine: AppHaptics -> engine.toggleOff(NO_VIEW) },
        HapticSemantic.CONFIRM to { engine: AppHaptics -> engine.confirm(NO_VIEW) },
        HapticSemantic.REJECT to { engine: AppHaptics -> engine.reject(NO_VIEW) },
        HapticSemantic.DRAG_START to { engine: AppHaptics -> engine.dragStart(NO_VIEW) },
        HapticSemantic.THRESHOLD_ARMED to { engine: AppHaptics -> engine.thresholdArmed(NO_VIEW) },
        HapticSemantic.GESTURE_END to { engine: AppHaptics -> engine.gestureEnd(NO_VIEW) },
        HapticSemantic.SCROLL_EDGE to { engine: AppHaptics -> engine.scrollEdge(NO_VIEW) },
        HapticSemantic.POPUP_SHOW to { engine: AppHaptics -> engine.popupShow(NO_VIEW) },
    )

    @Test
    fun `最高优先级那层接下之后，其余各层的 supports 与 perform 一次都不调`() {
        val vendor = fake(TIER_VENDOR, NAME_MIUI)
        val richTap = fake(TIER_RICHTAP, NAME_RICHTAP)
        val waveform = fake(TIER_WAVEFORM, NAME_WAVEFORM)
        val constants = fake(TIER_CONSTANTS, NAME_CONSTANTS)
        val engine = engine(listOf(richTap, vendor, waveform, constants))

        assertThat(engine.perform(NO_VIEW, HapticSemantic.TAP)).isTrue()

        assertThat(vendor.performedSemantics).containsExactly(HapticSemantic.TAP)
        listOf(richTap, waveform, constants).forEach { skipped ->
            assertWithMessage(
                "$NAME_MIUI 已经接下了，${skipped.name} 还被问了 ${skipped.supportsCalls} 次 supports、" +
                    "${skipped.performCalls} 次 perform；重复下发就是一次点击震两下",
            )
                .that(skipped.supportsCalls + skipped.performCalls)
                .isEqualTo(0)
        }
        // 接下的那层也只被问一遍：isAvailable、supports、perform 各一次，没有重试
        assertThat(dispatchTrace).containsExactly(
            "$NAME_MIUI.isAvailable",
            "$NAME_MIUI.supports",
            "$NAME_MIUI.perform",
        ).inOrder()
        assertThat(misses).isEmpty()
    }

    @Test
    fun `高优先级层 supports 为 false 时被跳过，且不许对它调 perform`() {
        val vendor = fake(
            tier = TIER_VENDOR,
            name = NAME_MIUI,
            supported = ALL_SEMANTICS - HapticSemantic.FREQUENT_TICK,
        )
        val richTap = fake(TIER_RICHTAP, NAME_RICHTAP)
        val engine = engine(listOf(vendor, richTap))

        assertThat(engine.perform(NO_VIEW, HapticSemantic.FREQUENT_TICK)).isTrue()

        assertWithMessage("supports 返 false 的层还是被 perform 了：tier 2 上那等于下发一个没探到的效果 ID")
            .that(vendor.performCalls)
            .isEqualTo(0)
        assertThat(vendor.supportsCalls).isEqualTo(1)
        assertThat(richTap.performedSemantics).containsExactly(HapticSemantic.FREQUENT_TICK)

        // supports 是逐语义判定，不是整层开关：同一台机上别的语义仍该落回优先级更高的那层
        assertThat(engine.perform(NO_VIEW, HapticSemantic.TAP)).isTrue()
        assertThat(vendor.performedSemantics).containsExactly(HapticSemantic.TAP)
        assertThat(richTap.performedSemantics).containsExactly(HapticSemantic.FREQUENT_TICK)
    }

    @Test
    fun `supports 为 true 但 perform 返 false 时继续往下降级，不是就此放弃`() {
        val vendor = fake(TIER_VENDOR, NAME_MIUI, performResult = false)
        val richTap = fake(TIER_RICHTAP, NAME_RICHTAP, performResult = false)
        val waveform = fake(TIER_WAVEFORM, NAME_WAVEFORM, performResult = false)
        val constants = fake(TIER_CONSTANTS, NAME_CONSTANTS)
        val engine = engine(listOf(richTap, vendor, waveform, constants))

        assertWithMessage("前三层都说自己能表达这个语义却没发出去，引擎应一路降到 tier 0，而不是当作已派发")
            .that(engine.perform(NO_VIEW, HapticSemantic.CONFIRM))
            .isTrue()

        assertThat(constants.performedSemantics).containsExactly(HapticSemantic.CONFIRM)
        assertThat(dispatchTrace).containsExactly(
            "$NAME_MIUI.isAvailable",
            "$NAME_MIUI.supports",
            "$NAME_MIUI.perform",
            "$NAME_RICHTAP.isAvailable",
            "$NAME_RICHTAP.supports",
            "$NAME_RICHTAP.perform",
            "$NAME_WAVEFORM.isAvailable",
            "$NAME_WAVEFORM.supports",
            "$NAME_WAVEFORM.perform",
            "$NAME_CONSTANTS.isAvailable",
            "$NAME_CONSTANTS.supports",
            "$NAME_CONSTANTS.perform",
        ).inOrder()
        assertThat(misses).isEmpty()
    }

    @Test
    fun `整条链都没接下时不抛异常、整次调用无声，且每层都真的试过一遍`() {
        val layers = listOf(
            fake(TIER_RICHTAP, NAME_RICHTAP, performResult = false),
            fake(TIER_VENDOR, NAME_MIUI, performResult = false),
            fake(TIER_WAVEFORM, NAME_WAVEFORM, performResult = false),
            fake(TIER_CONSTANTS, NAME_CONSTANTS, performResult = false),
        )
        val engine = engine(layers)

        assertThat(engine.perform(NO_VIEW, HapticSemantic.SCROLL_EDGE)).isFalse()

        layers.forEach { layer ->
            assertWithMessage("${layer.name} 一次都没被试到就下了「本机发不出」的结论")
                .that(layer.performCalls)
                .isEqualTo(1)
        }
        assertWithMessage("整条链都没接下应恰好记一次 onMiss，多记或不记都会让线上诊断失真")
            .that(misses)
            .containsExactly(HapticSemantic.SCROLL_EDGE)
    }

    @Test
    fun `isAvailable 为 false 的层从不入选，构造期判死的层也不会复活`() {
        val richTap = fake(TIER_RICHTAP, NAME_RICHTAP, available = false)
        val vendor = fake(TIER_VENDOR, NAME_MIUI)
        val engine = engine(listOf(richTap, vendor))

        // 构造期给每层各焐热一次：MIUI 与 OPlus 靠这一下把逐 ID 探测甩上自己的单线程，
        // 少了它第一次点击会掉到下一层去 —— 只有第一次错，最难查
        assertWithMessage("构造期没给 ${richTap.name} 焐热").that(richTap.availableCalls).isEqualTo(1)
        assertWithMessage("构造期没给 ${vendor.name} 焐热").that(vendor.availableCalls).isEqualTo(1)

        // 构造之后才翻成可用。契约要求 isAvailable() 单向 true 到 false，链在构造期定死，
        // 所以这一层不该复活；反过来说，若哪天改成每次派发重算链，这条会判红
        richTap.available = true

        assertThat(engine.perform(NO_VIEW, HapticSemantic.TAP)).isTrue()

        assertThat(vendor.performedSemantics).containsExactly(HapticSemantic.TAP)
        assertWithMessage("构造期报不可用的层后来又被问了 supports 或 perform，说明降级链是每次派发重算的")
            .that(richTap.supportsCalls + richTap.performCalls)
            .isEqualTo(0)
        assertThat(richTap.availableCalls).isEqualTo(1)
    }

    @Test
    fun `无马达设备两条链都是空的，连 tier 0 也不许上场`() {
        val richTap = fake(TIER_RICHTAP, NAME_RICHTAP, envelopeResult = true)
        val constants = fake(TIER_CONSTANTS, NAME_CONSTANTS)
        val engine = engine(listOf(richTap, constants), hasVibrator = false)

        assertThat(engine.perform(NO_VIEW, HapticSemantic.TAP)).isFalse()
        assertThat(engine.playEnvelope(TIMINGS_MS, AMPLITUDES)).isFalse()

        // tier 0 自己判不出有没有马达：无马达设备上 performHapticFeedback 照样返回 true，
        // 不在引擎里短路就会一路报「已派发」，调用方永远拿不到 false
        assertWithMessage("无马达设备上 tier 0 仍被下发，返回值会假报成已派发")
            .that(constants.availableCalls + constants.performCalls)
            .isEqualTo(0)
        assertThat(richTap.envelopeCalls).isEqualTo(0)
        assertThat(misses).containsExactly(HapticSemantic.TAP)
    }

    @Test
    fun `转子马达锁 tier 0，tier 1 以上即便可用也不入选`() {
        // hasAmplitudeControl 为 false 就是转子马达，lockedToConstants 是它的唯一含义
        assertThat(capabilitiesOf(hasVibrator = true, hasAmplitudeControl = false).lockedToConstants)
            .isTrue()
        val richTap = fake(TIER_RICHTAP, NAME_RICHTAP, envelopeResult = true)
        val vendor = fake(TIER_VENDOR, NAME_MIUI)
        val waveform = fake(TIER_WAVEFORM, NAME_WAVEFORM, envelopeResult = true)
        val constants = fake(TIER_CONSTANTS, NAME_CONSTANTS)
        val engine = engine(
            backends = listOf(richTap, vendor, waveform, constants),
            hasAmplitudeControl = false,
        )

        assertThat(engine.perform(NO_VIEW, HapticSemantic.TAP)).isTrue()
        assertThat(constants.performedSemantics).containsExactly(HapticSemantic.TAP)
        listOf(richTap, vendor, waveform).forEach { locked ->
            assertWithMessage(
                "${locked.name}（tier ${locked.tier}）在无振幅控制的设备上上场了：" +
                    "非零振幅会被抬到 100%，一记轻点震成一声嗡",
            )
                .that(locked.availableCalls + locked.supportsCalls + locked.performCalls)
                .isEqualTo(0)
        }

        // 包络链同样只剩 tier 0，而 tier 0 画不出包络 —— 彩蛋在这类设备上必须退成稀疏编排。
        // 这里 richTap 与 waveform 都声明能播包络，被选中就是硬规则 1 破了
        assertThat(engine.playEnvelope(TIMINGS_MS, AMPLITUDES)).isFalse()
        assertThat(richTap.envelopeCalls).isEqualTo(0)
        assertThat(waveform.envelopeCalls).isEqualTo(0)
    }

    @Test
    fun `离散链次序与入参顺序无关，恒为 tier 2 3 1 0`() {
        INPUT_ORDERS.forEach { order ->
            assertWithMessage("入参顺序 $order 改变了离散链的尝试次序，说明链依赖了入参而不是 tier")
                .that(attemptOrder(order) { engine -> engine.perform(NO_VIEW, HapticSemantic.TAP) })
                .containsExactly(NAME_MIUI, NAME_RICHTAP, NAME_WAVEFORM, NAME_CONSTANTS)
                .inOrder()
        }
    }

    @Test
    fun `包络链按 tier 降序，但厂商层可用时跳过 tier 1`() {
        // 全层都「发不出去」所以整条链被走满：次序断言看的是谁被问到，不是谁接下。
        // tier 1 被跳过是真机反馈定的策略（见 AppHaptics.playEnvelope）：厂商预置效果
        // 可用的机器上，通用振幅波形在线性马达上是「普通震动」，宁可整段拒收。
        INPUT_ORDERS.forEach { order ->
            assertWithMessage("入参顺序 $order 改变了包络链的尝试次序")
                .that(attemptOrder(order) { engine -> engine.playEnvelope(TIMINGS_MS, AMPLITUDES) })
                .containsExactly(NAME_RICHTAP, NAME_MIUI, NAME_CONSTANTS)
                .inOrder()
        }
    }

    @Test
    fun `没有厂商层的机器，包络链老实地走到 tier 1`() {
        val ordersWithoutVendor = INPUT_ORDERS.map { order -> order.filterNot { it == TIER_VENDOR } }
        ordersWithoutVendor.forEach { order ->
            assertWithMessage("入参顺序 $order 改变了包络链的尝试次序")
                .that(attemptOrder(order) { engine -> engine.playEnvelope(TIMINGS_MS, AMPLITUDES) })
                .containsExactly(NAME_RICHTAP, NAME_WAVEFORM, NAME_CONSTANTS)
                .inOrder()
        }
    }

    @Test
    fun `厂商层可用时，包络宁可拒绝也不落 tier 1 的通用波形`() {
        // 目标机（小米 14 Pro）布局：RichTap 在（预置效果可用）但包络通路不可用
        val richTap = fake(TIER_RICHTAP, NAME_RICHTAP)
        val vendor = fake(TIER_VENDOR, NAME_MIUI)
        val waveform = fake(TIER_WAVEFORM, NAME_WAVEFORM, envelopeResult = true)
        val constants = fake(TIER_CONSTANTS, NAME_CONSTANTS)
        val engine = engine(listOf(richTap, vendor, waveform, constants))

        assertWithMessage(
            "厂商层可用的机器上包络被 tier 1 接走：createWaveform 的通用波形在线性马达" +
                "机型上是「普通震动」，返 false 让调用方退离散替身才是更好的落点",
        )
            .that(engine.playEnvelope(TIMINGS_MS, AMPLITUDES))
            .isFalse()
        assertWithMessage("tier 1 连 isAvailable 都不该被问（跳过发生在问之前）")
            .that(waveform.availableCalls)
            .isEqualTo(1) // 只剩构造期焐热那一次
        assertThat(waveform.envelopeCalls).isEqualTo(0)
        assertThat(richTap.envelopeCalls).isEqualTo(1)
        assertThat(vendor.envelopeCalls).isEqualTo(1)
    }

    @Test
    fun `厂商层运行中被禁用后，tier 1 恢复包络兜底`() {
        val vendor = fake(TIER_VENDOR, NAME_MIUI)
        val waveform = fake(TIER_WAVEFORM, NAME_WAVEFORM, envelopeResult = true)
        val engine = engine(listOf(vendor, waveform))

        // 模拟 MIUI 派发失败后整层禁用：isAvailable 单向 true→false，契约允许
        vendor.available = false

        assertWithMessage("tier 2 被禁用后包络两头落空：tier 1 应立刻恢复兜底")
            .that(engine.playEnvelope(TIMINGS_MS, AMPLITUDES))
            .isTrue()
        assertThat(waveform.envelopeCalls).isEqualTo(1)
    }

    @Test
    fun `同机两条通路都可用时，离散语义走厂商预置效果、连续包络走 RichTap`() {
        val richTap = fake(TIER_RICHTAP, NAME_RICHTAP, envelopeResult = true)
        // 真实 tier 2 只能选固定预置效果，画不出包络，所以 envelopeResult 保持默认的 false
        val vendor = fake(TIER_VENDOR, NAME_MIUI)
        val engine = engine(listOf(richTap, vendor))

        assertThat(engine.perform(NO_VIEW, HapticSemantic.TAP)).isTrue()
        assertWithMessage("离散语义没落在厂商预置效果上：设计文档要求两条都可用时 tier 2 优先")
            .that(vendor.performedSemantics)
            .containsExactly(HapticSemantic.TAP)
        assertThat(richTap.performCalls).isEqualTo(0)

        assertThat(engine.playEnvelope(TIMINGS_MS, AMPLITUDES)).isTrue()
        assertWithMessage("连续包络没走 RichTap：tier 2 只能选预置效果，画不出包络")
            .that(richTap.envelopeCalls)
            .isEqualTo(1)
        assertThat(vendor.envelopeCalls).isEqualTo(0)
    }

    @Test
    fun `tier 3 明明也接得下，离散语义仍必须让给 tier 2，而同一布局下包络归 tier 3`() {
        // 对照组：同一份配置的 tier 3 单独上场时是接得下 TAP 的。有了这一下，
        // 下面它被跳过就只可能是排序的结果，而不是「这一层表达不了这个语义」
        val soloRichTap = fake(TIER_RICHTAP, NAME_RICHTAP, envelopeResult = true)
        assertThat(engine(listOf(soloRichTap)).perform(NO_VIEW, HapticSemantic.TAP)).isTrue()
        assertThat(soloRichTap.performedSemantics).containsExactly(HapticSemantic.TAP)

        // 目标机布局：tier 3 与 tier 2 都可用、都声称支持全部语义
        val richTap = fake(TIER_RICHTAP, NAME_RICHTAP, envelopeResult = true)
        val vendor = fake(TIER_VENDOR, NAME_MIUI)
        val engine = engine(listOf(richTap, vendor))

        assertThat(engine.perform(NO_VIEW, HapticSemantic.TAP)).isTrue()
        assertWithMessage(
            "离散语义没落在 $NAME_MIUI（tier $TIER_VENDOR）上。若刚按「tier 降序」改了 " +
                "discreteRank()，请先读设计文档「厂商通路矩阵」末段：同机两条通路都可用时" +
                "离散语义走厂商预置效果 —— 那是裁定，不是笔误",
        )
            .that(vendor.performedSemantics)
            .containsExactly(HapticSemantic.TAP)
        assertWithMessage(
            "$NAME_RICHTAP 在离散链上被问了 ${richTap.supportsCalls} 次 supports、" +
                "${richTap.performCalls} 次 perform；tier 2 排它前面且已接下，它该一次不调",
        )
            .that(richTap.supportsCalls + richTap.performCalls)
            .isEqualTo(0)
        assertThat(dispatchTrace).containsExactly(
            "$NAME_MIUI$SUFFIX_AVAILABLE",
            "$NAME_MIUI$SUFFIX_SUPPORTS",
            "$NAME_MIUI$SUFFIX_PERFORM",
        ).inOrder()

        // 同一台机、同一组 backend，换成连续包络就必须反过来落 tier 3。
        // engine() 只清构造期那一轮，派发流水得在这里自己清一次
        dispatchTrace.clear()
        assertThat(engine.playEnvelope(TIMINGS_MS, AMPLITUDES)).isTrue()
        assertWithMessage("连续包络没落在 $NAME_RICHTAP 上：tier 2 只能选固定预置效果，画不出包络")
            .that(dispatchTrace)
            .containsExactly("$NAME_RICHTAP$SUFFIX_AVAILABLE", "$NAME_RICHTAP$SUFFIX_ENVELOPE")
            .inOrder()
        assertThat(vendor.envelopeCalls).isEqualTo(0)
        assertThat(misses).isEmpty()
    }

    @Test
    fun `13 个语义逐个都能沿链降级，没有哪个语义被特殊短路`() {
        HapticSemantic.entries.forEach { semantic ->
            val vendor = fake(TIER_VENDOR, NAME_MIUI, supported = emptySet())
            val richTap = fake(TIER_RICHTAP, NAME_RICHTAP, performResult = false)
            val constants = fake(TIER_CONSTANTS, NAME_CONSTANTS)
            val engine = engine(listOf(constants, richTap, vendor))

            assertWithMessage("$semantic 没能一路降到 tier 0")
                .that(engine.perform(NO_VIEW, semantic))
                .isTrue()
            assertThat(vendor.performCalls).isEqualTo(0)
            assertWithMessage("$semantic 传到 tier 3 时变了样")
                .that(richTap.performedSemantics)
                .containsExactly(semantic)
            assertWithMessage("$semantic 传到 tier 0 时变了样，跟随系统档不该改写语义")
                .that(constants.performedSemantics)
                .containsExactly(semantic)
            assertThat(misses).isEmpty()
        }
    }

    @Test
    fun `13 个具名方法各自派发自己的语义，没有复制粘贴串行`() {
        assertWithMessage("具名方法表漏了语义：新增语义要同时补 AppHaptics 的方法与本表")
            .that(namedMethods.keys)
            .containsExactlyElementsIn(HapticSemantic.entries)
        namedMethods.forEach { (semantic, call) ->
            val vendor = fake(TIER_VENDOR, NAME_MIUI)
            val engine = engine(listOf(vendor))

            assertThat(call(engine)).isTrue()
            assertWithMessage("$semantic 的具名方法实际派发出的是 ${vendor.performedSemantics}")
                .that(vendor.performedSemantics)
                .containsExactly(semantic)
        }
    }

    // ------------------------------------------------------------------------
    // 脚手架
    // ------------------------------------------------------------------------

    /**
     * 造一层假 backend。默认「可用、支持全部语义、派发成功、播不了包络」，即一层称职的 tier 2。
     *
     * @param supported 这层声称能表达的语义，用来演「supports 返 false 该被跳过」
     * @param performResult 离散派发的返回值，false 演「声称支持却没发出去，应继续降级」
     * @param envelopeResult 连续包络的返回值，只有 tier 3 与 tier 1 在真机上会给 true
     */
    private fun fake(
        tier: Int,
        name: String,
        available: Boolean = true,
        supported: Set<HapticSemantic> = ALL_SEMANTICS,
        performResult: Boolean = true,
        envelopeResult: Boolean = false,
    ): FakeBackend = FakeBackend(
        tier = tier,
        name = name,
        available = available,
        supported = supported,
        performResult = performResult,
        envelopeResult = envelopeResult,
    )

    /**
     * 装一台设备：给定几层 backend 与两项设备能力，返回引擎。
     *
     * 构造期那一轮焐热调用不属降级判定，[dispatchTrace] 与 [misses] 在这里清一次，
     * 让每条测试的流水从派发阶段起算；[FakeBackend] 的计数器不清，仍是全程累计。
     */
    private fun engine(
        backends: List<FakeBackend>,
        hasVibrator: Boolean = true,
        hasAmplitudeControl: Boolean = true,
    ): AppHaptics {
        val engine = AppHaptics(
            capabilities = capabilitiesOf(hasVibrator, hasAmplitudeControl),
            backends = backends,
            modeState = MutableStateFlow(HapticMode.FOLLOW_SYSTEM),
            systemHapticEnabled = { true },
            onMiss = { semantic -> misses += semantic },
        )
        dispatchTrace.clear()
        misses.clear()
        return engine
    }

    /**
     * 目标机（小米 14 Pro、HyperOS 3）那份能力快照，只有两项由参数控制。
     *
     * [AppHaptics] 只读 `hasVibrator` 与 `lockedToConstants`（即 `!hasAmplitudeControl`）两项，
     * 其余七项照实测值填 —— 填成一台不可能存在的设备，会让后来人误以为它们也参与了选择。
     */
    private fun capabilitiesOf(hasVibrator: Boolean, hasAmplitudeControl: Boolean) =
        HapticCapabilities(
            hasVibrator = hasVibrator,
            hasAmplitudeControl = hasAmplitudeControl,
            supportedPrimitives = emptySet(),
            compositionSizeMax = 0,
            envelopeSupported = false,
            envelopeMaxSize = 0,
            richTapSupported = true,
            hapticPlayerSupported = true,
            miuiSupported = true,
            oplusSupported = false,
        )

    /**
     * 按 [inputTiers] 的顺序装四层「声称支持但一律发不出去」的 backend，跑一次 [dispatch]，
     * 返回真正被尝试到的层名（按尝试次序）。
     *
     * 全层都返 false，所以整条链会被走满，流水就是完整的尝试次序 ——
     * 只看谁最后接下是看不出次序的：那只能证明它排在前面某处。
     */
    private fun attemptOrder(inputTiers: List<Int>, dispatch: (AppHaptics) -> Boolean): List<String> {
        val backends = inputTiers.map { tier ->
            fake(
                tier = tier,
                name = TIER_NAMES.getValue(tier),
                performResult = false,
                envelopeResult = false,
            )
        }
        val engine = engine(backends)
        dispatch(engine)
        return dispatchTrace
            .filter { it.endsWith(SUFFIX_PERFORM) || it.endsWith(SUFFIX_ENVELOPE) }
            .map { it.substringBeforeLast('.') }
    }

    /**
     * 一层可编程的假 backend：只记账，不碰任何 android API。
     *
     * 写成 `inner` 是为了直接往外层的 [dispatchTrace] 记流水 —— 四层共用一份流水才看得出
     * 跨层的尝试次序。[available] 刻意是 `var`：用来演「构造期报不可用、之后才翻成可用」，
     * 验证降级链只在构造期算一次。计数器全程累计，`availableCalls` 含构造期焐热那一次。
     */
    private inner class FakeBackend(
        override val tier: Int,
        override val name: String,
        var available: Boolean,
        private val supported: Set<HapticSemantic>,
        private val performResult: Boolean,
        private val envelopeResult: Boolean,
    ) : HapticBackend {

        var availableCalls = 0
            private set

        var supportsCalls = 0
            private set

        var performCalls = 0
            private set

        var envelopeCalls = 0
            private set

        /** perform 收到的语义，按顺序。断言「派发出去的是哪个语义」用它 */
        val performedSemantics = mutableListOf<HapticSemantic>()

        override fun isAvailable(): Boolean {
            availableCalls++
            dispatchTrace += "$name$SUFFIX_AVAILABLE"
            return available
        }

        override fun supports(semantic: HapticSemantic): Boolean {
            supportsCalls++
            dispatchTrace += "$name$SUFFIX_SUPPORTS"
            return semantic in supported
        }

        override fun perform(view: View?, semantic: HapticSemantic, strength: HapticStrength): Boolean {
            performCalls++
            dispatchTrace += "$name$SUFFIX_PERFORM"
            performedSemantics += semantic
            return performResult
        }

        override fun playEnvelope(timingsMs: IntArray, amplitudes: FloatArray): Boolean {
            envelopeCalls++
            dispatchTrace += "$name$SUFFIX_ENVELOPE"
            return envelopeResult
        }

        override fun release() = Unit
    }

    /**
     * 四层的 tier 与名字照 `com.tracktosearch.ui.haptic.backend` 里五个真实现抄，
     * 这样断言读起来就是「离散链先问 MIUI」而不是「先问 tier 2」。
     */
    private companion object {
        /** tier 3：RichTap 波形，唯一画得出连续包络的一层 */
        const val TIER_RICHTAP = 3

        /** tier 2：厂商语义效果（MIUI 与 OPlus），离散语义在这一层优先 */
        const val TIER_VENDOR = 2

        /** tier 1：AOSP 振幅波形 */
        const val TIER_WAVEFORM = 1

        /** tier 0：AOSP 常量，降级链的地板，转子马达锁在这里 */
        const val TIER_CONSTANTS = 0

        const val NAME_RICHTAP = "RichTap"
        const val NAME_MIUI = "MIUI"
        const val NAME_WAVEFORM = "AospWaveform"
        const val NAME_CONSTANTS = "AOSP-Constants"

        const val SUFFIX_AVAILABLE = ".isAvailable"
        const val SUFFIX_SUPPORTS = ".supports"
        const val SUFFIX_PERFORM = ".perform"
        const val SUFFIX_ENVELOPE = ".playEnvelope"

        /** tier 到层名，[attemptOrder] 按入参 tier 装配时用 */
        val TIER_NAMES: Map<Int, String> = mapOf(
            TIER_RICHTAP to NAME_RICHTAP,
            TIER_VENDOR to NAME_MIUI,
            TIER_WAVEFORM to NAME_WAVEFORM,
            TIER_CONSTANTS to NAME_CONSTANTS,
        )

        /**
         * 四种入参顺序：文档次序、完全倒过来，以及两种乱序。
         *
         * 都要得出同一条链。真实的入参顺序由 Hilt 模块里 backend 的声明顺序决定，
         * 而那份顺序是随手写的 —— 一旦链依赖了它，改模块声明就会静悄悄改掉全 App 的手感。
         */
        val INPUT_ORDERS: List<List<Int>> = listOf(
            listOf(TIER_RICHTAP, TIER_VENDOR, TIER_WAVEFORM, TIER_CONSTANTS),
            listOf(TIER_CONSTANTS, TIER_WAVEFORM, TIER_VENDOR, TIER_RICHTAP),
            listOf(TIER_WAVEFORM, TIER_RICHTAP, TIER_CONSTANTS, TIER_VENDOR),
            listOf(TIER_VENDOR, TIER_CONSTANTS, TIER_RICHTAP, TIER_WAVEFORM),
        )

        /** 全部 13 个语义，假 backend 的默认支持集 */
        val ALL_SEMANTICS: Set<HapticSemantic> = HapticSemantic.entries.toSet()

        /**
         * 一段随便的三点包络。本文件只关心「哪一层被问到」，不关心曲线本身，
         * 长度与振幅的合法性判定归各 backend。
         */
        val TIMINGS_MS: IntArray = intArrayOf(40, 60, 40)

        /** 与 [TIMINGS_MS] 等长的振幅，取值都在 0f..1f 内 */
        val AMPLITUDES: FloatArray = floatArrayOf(0.2f, 0.9f, 0.1f)

        /**
         * 13 个语义方法与 [AppHaptics.perform] 的 `view` 参数一律传 null。
         *
         * 假 backend 不看它，于是整份测试零 android 调用、纯 JVM 跑。真机上传 null 等于把
         * tier 0 摘掉（`View.performHapticFeedback` 需要一个 View），所以生产侧必须从
         * `LocalView.current` 取一个传进来 —— 转子马达机型只剩 tier 0，传 null 就是全程无触感。
         */
        val NO_VIEW: View? = null
    }
}
