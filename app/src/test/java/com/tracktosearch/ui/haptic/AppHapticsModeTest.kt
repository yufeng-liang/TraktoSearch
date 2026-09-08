package com.tracktosearch.ui.haptic

import android.view.View
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Test

/**
 * [AppHaptics] 的三态开关、系统总开关与连续包络判定。
 *
 * 挑的都是改坏了既不崩也不报错、只会静默走偏的判定：
 *
 * - 「关闭」档漏一层，用户明确关掉的触感照样震；
 * - 「增强」档少调一次 [HapticSemantic.boosted] 就等于开关没作用，多调一次会把滑块拖动的
 *   `FREQUENT_TICK` 一路顶成 `TAP`，一次拖动累出几十记实心点击；
 * - 「增强」档若绕过系统总开关，就是变相的 `FLAG_IGNORE_GLOBAL_SETTING`，红线；
 * - 包络链排序错了，本该走 RichTap 的彩蛋波形落到播不了的一层，整段无声；
 * - `release` 漏掉被降级链摘掉的那层，就是线程泄漏 —— 每层都持着自己的单线程 `Executor`。
 *
 * 期望值刻意不从被测代码推导：上移一档的 13 条期望写死在 [BOOSTED_ORDER]，照设计文档
 * 「语义词表」抄，不写成 `it.boosted()` —— 那样实现被改坏期望值跟着一起变，测试永远是绿的。
 *
 * 纯 JVM 跑，不上 Robolectric：五个 backend 全用本地假实现，档位、系统开关、静音钩子与 onMiss
 * 都是构造参数注进去的，被测路径上一个平台调用都没有。唯一的 android 类是 `View`，只为验
 * `isHapticFeedbackEnabled` 那道闸，用 MockK 造一个（与 [HapticCapabilitiesTest] 同一套路：
 * `View` 在单测的 android.jar 里所有方法都会抛「not mocked」，构造不出真实例）。
 *
 * 一处刻意不算进「backend 被调用」的：构造期每层各被问一次 `isAvailable()`，那是 MIUI 与
 * OPlus 两层要求的焐热。[engine] 建完就把这段流水挪进 [warmUp] 并清掉 [journal]，
 * 它本身由「构造期每层各问一次」那条用例单独钉住。
 *
 * 设计依据见 docs/superpowers/plans/2026-09-01-haptics-overhaul.md 的「三态开关」
 * 与「四层引擎」两节。
 */
class AppHapticsModeTest {

    /** 三态开关。用例里直接改 `value`，模拟用户在设置里切档 */
    private val mode = MutableStateFlow(HapticMode.FOLLOW_SYSTEM)

    /**
     * 注入的「系统触感总开关」。写成 var 而不是构造时的常量：
     * 要在同一个 [AppHaptics] 实例上翻它，才验得出这个判定没被缓存在构造期。
     */
    private var systemEnabled = true

    /** `quietDown` 被调次数。进入关闭档只该停一次已排出的波形 */
    private var quietDownCalls = 0

    /** `onMiss` 收到的语义。被开关拦下不算 miss，这个表该是空的 */
    private val missed = mutableListOf<HapticSemantic>()

    /** 五层共用的调用流水，跨层记序，用来断言降级次序与「一层都没碰」 */
    private val journal = mutableListOf<String>()

    /** [engine] 构造期那段焐热流水，从 [journal] 里摘出来单独断言 */
    private var warmUp: List<String> = emptyList()

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `关闭档 13 个语义连包络全跑一遍，五层一次都不碰`() {
        mode.value = HapticMode.OFF
        val haptics = engine(fiveLayers())

        val results = dispatchAll(haptics, hapticView())
        val direct = haptics.perform(hapticView(), HapticSemantic.CONFIRM)
        val envelope = haptics.playEnvelope(TIMINGS, AMPLITUDES)

        assertWithMessage("关闭档 13 个语义方法都必须返回 false").that(results).doesNotContain(true)
        assertWithMessage("perform 与 13 个糖方法走同一道闸").that(direct).isFalse()
        assertWithMessage("关闭档连彩蛋的连续包络也不播").that(envelope).isFalse()
        // 构造期那段焐热已被 engine() 摘走，这里剩下任何一条都说明闸门漏了
        assertWithMessage("关闭档不许碰任何一层，实际流水 $journal").that(journal).isEmpty()
        assertWithMessage("用户关掉触感不是「本机发不出」，不该记 onMiss").that(missed).isEmpty()
    }

    @Test
    fun `关闭档进入后只停一次已排出的波形，离开再回来重新武装`() {
        mode.value = HapticMode.OFF
        val haptics = engine(fiveLayers())
        val view = hapticView()

        repeat(FREQUENT_TICK_BURST) { haptics.frequentTick(view) }
        assertWithMessage("一次滑块拖动几十记 frequentTick 只该停一次，否则单线程上堆几十个停止任务")
            .that(quietDownCalls).isEqualTo(1)

        mode.value = HapticMode.FOLLOW_SYSTEM
        haptics.tap(view)
        assertWithMessage("跟随系统档不该去停波形").that(quietDownCalls).isEqualTo(1)

        mode.value = HapticMode.OFF
        repeat(3) { haptics.tap(view) }
        assertWithMessage("离开关闭档再回来要重新停一次，否则第二次关掉时正在播的波形停不下来")
            .that(quietDownCalls).isEqualTo(2)

        // 彩蛋的三条闸门（paused、ON_STOP、AUDIOFOCUS_LOSS）与档位无关，跟随系统档也得停得下来
        mode.value = HapticMode.FOLLOW_SYSTEM
        haptics.stopOngoing()
        assertWithMessage("stopOngoing 不看档位").that(quietDownCalls).isEqualTo(3)
    }

    @Test
    fun `跟随系统档原样派发 13 个语义，View 一路带到 backend`() {
        val only = FakeBackend(tier = TIER_RICHTAP, name = NAME_RICHTAP)
        val haptics = engine(listOf(only))
        val view = hapticView()

        val results = dispatchAll(haptics, view)

        assertWithMessage("13 个糖方法要覆盖全表：往枚举里加语义就得回来补一行 dispatchAll")
            .that(NAMED_METHOD_ORDER).containsExactlyElementsIn(HapticSemantic.entries)
        assertThat(results).doesNotContain(false)
        assertWithMessage("跟随系统档不上移：每个方法各自派发自己那个语义，顺序照调用序")
            .that(only.performed).containsExactlyElementsIn(NAMED_METHOD_ORDER).inOrder()
        assertWithMessage("问 supports 的语义必须与真正派发的一致，否则会选中一层表达不了的通路")
            .that(only.supportsAsked).containsExactlyElementsIn(NAMED_METHOD_ORDER).inOrder()
        assertWithMessage("View 要原样传下去 —— tier 0 丢了 View 就返 false，转子马达机型整机无触感")
            .that(only.views.distinct()).containsExactly(view)
        assertThat(missed).isEmpty()
    }

    @Test
    fun `增强档派发上移一档后的语义，五个顶档原样不动`() {
        mode.value = HapticMode.BOOST
        val only = FakeBackend(tier = TIER_RICHTAP, name = NAME_RICHTAP)
        val haptics = engine(listOf(only))

        val results = dispatchAll(haptics, hapticView())

        assertThat(results).doesNotContain(false)
        assertWithMessage("增强档要把语义整体上移一档，期望表照设计文档「语义词表」抄")
            .that(only.performed).containsExactlyElementsIn(BOOSTED_ORDER).inOrder()
        assertWithMessage("supports 要用上移之后的语义问，不能拿原语义问完却发上移后的")
            .that(only.supportsAsked).containsExactlyElementsIn(BOOSTED_ORDER).inOrder()

        val dispatched = NAMED_METHOD_ORDER.zip(only.performed).toMap()
        TOP_TIER.forEach { semantic ->
            assertWithMessage("$semantic 已是顶档，增强档必须原样发，不能再往上找")
                .that(dispatched.getValue(semantic)).isEqualTo(semantic)
        }
        assertWithMessage("boosted() 只许调一次：连着调会把 FREQUENT_TICK 一路顶成 TAP，一次拖动几十记实心点击")
            .that(dispatched.getValue(HapticSemantic.FREQUENT_TICK))
            .isEqualTo(HapticSemantic.SEGMENT_TICK)
    }

    @Test
    fun `增强档先上移再问 supports，上一层表达不了就往下降级`() {
        mode.value = HapticMode.BOOST
        // 一层「什么都能发，就是发不了 TAP」的通路。现实里就是某个 ROM 缺了那一个效果 ID
        val high = FakeBackend(
            tier = TIER_RICHTAP,
            name = NAME_RICHTAP,
            supported = (HapticSemantic.entries - HapticSemantic.TAP).toSet(),
        )
        val low = FakeBackend(tier = TIER_WAVEFORM, name = NAME_WAVEFORM)
        val haptics = engine(listOf(high, low))

        val handled = haptics.lightTap(hapticView())

        assertThat(handled).isTrue()
        assertWithMessage("LIGHT_TAP 上移成 TAP，问上层的必须是 TAP 而不是 LIGHT_TAP")
            .that(high.supportsAsked).containsExactly(HapticSemantic.TAP)
        assertWithMessage("上层报了表达不了就不该被派发").that(high.performed).isEmpty()
        assertWithMessage("要落到下一层，且落下去的仍是上移后的语义")
            .that(low.performed).containsExactly(HapticSemantic.TAP)
    }

    @Test
    fun `系统触感关闭时跟随系统与增强都不发，改回来立刻生效`() {
        systemEnabled = false
        val haptics = engine(fiveLayers())
        val view = hapticView()

        val follow = haptics.tap(view)
        mode.value = HapticMode.BOOST
        val boost = haptics.tap(view)
        val envelope = haptics.playEnvelope(TIMINGS, AMPLITUDES)

        assertWithMessage("系统触感关了，跟随系统档不发").that(follow).isFalse()
        assertWithMessage("增强档也不许绕过系统总开关 —— 与禁用 FLAG_IGNORE_GLOBAL_SETTING 同一条红线")
            .that(boost).isFalse()
        assertWithMessage("包络走厂商通路与 RichTap，系统不会替我们拦，必须在引擎里拦")
            .that(envelope).isFalse()
        assertWithMessage("被系统开关拦下不许碰任何一层，实际流水 $journal").that(journal).isEmpty()
        assertWithMessage("系统关了不是「本机发不出」，不该记 onMiss").that(missed).isEmpty()

        // 用户在系统设置里把触感打开，下一次点击就该有 —— 这个判定不许缓存在构造期
        systemEnabled = true
        assertWithMessage("系统开关每次派发重读，不缓存").that(haptics.tap(view)).isTrue()
    }

    @Test
    fun `View 关掉触感就不发，View 为 null 仍走不需要 View 的通路`() {
        val only = FakeBackend(tier = TIER_RICHTAP, name = NAME_RICHTAP)
        val haptics = engine(listOf(only))
        val enabled = hapticView()

        val muted = haptics.tap(hapticView(enabled = false))

        assertWithMessage("View 自己的触感开关也是用户设置的一部分，关着就不震").that(muted).isFalse()
        assertWithMessage("被 View 开关拦下不许碰任何一层，实际流水 $journal").that(journal).isEmpty()
        assertWithMessage("View 关掉触感不是「本机发不出」，不该记 onMiss").that(missed).isEmpty()

        assertThat(haptics.tap(enabled)).isTrue()
        assertWithMessage("view 为 null 不等于用户关了触感：tier 1 以上都不需要 View，照发")
            .that(haptics.tap(null)).isTrue()
        assertThat(only.views).containsExactly(enabled, null).inOrder()
    }

    @Test
    fun `运行中切档下一次调用立刻生效，不用重建引擎`() {
        mode.value = HapticMode.OFF
        val only = FakeBackend(tier = TIER_RICHTAP, name = NAME_RICHTAP)
        val haptics = engine(listOf(only))
        val view = hapticView()

        val off = haptics.segmentTick(view)
        mode.value = HapticMode.FOLLOW_SYSTEM
        val follow = haptics.segmentTick(view)
        mode.value = HapticMode.BOOST
        val boost = haptics.segmentTick(view)
        mode.value = HapticMode.OFF
        val offAgain = haptics.segmentTick(view)

        assertThat(listOf(off, offAgain)).doesNotContain(true)
        assertThat(listOf(follow, boost)).doesNotContain(false)
        assertWithMessage("档位每次派发同步读：关→跟随→增强→关，只有中间两次落到 backend，且第二次是上移后的")
            .that(only.performed)
            .containsExactly(HapticSemantic.SEGMENT_TICK, HapticSemantic.LIGHT_TAP).inOrder()
    }

    @Test
    fun `包络按 tier 降序逐层问，全部拒绝也不抛`() {
        val layers = fiveLayers()
        val haptics = engine(layers)

        val played = haptics.playEnvelope(TIMINGS, AMPLITUDES)

        assertWithMessage("本机播不了包络就返 false，调用方据此退成每段起点一记 tick 的稀疏编排")
            .that(played).isFalse()
        assertWithMessage(
            "包络链按 tier 降序，不套离散链那个把 tier 2 提到 tier 3 前面的换位；" +
                "tier 1 被跳过是「厂商层可用时不发通用波形」的策略（真机反馈：通用振幅台阶" +
                "在线性马达上是普通震动），见 AppHaptics.playEnvelope",
        )
            .that(journal.filter { it.endsWith(ENVELOPE_CALL) })
            .containsExactly(
                NAME_RICHTAP + ENVELOPE_CALL,
                NAME_MIUI + ENVELOPE_CALL,
                NAME_OPLUS + ENVELOPE_CALL,
                NAME_CONSTANTS + ENVELOPE_CALL,
            ).inOrder()
        assertWithMessage("包络不问 supports —— 那是离散语义的判据")
            .that(layers.flatMap { it.supportsAsked }).isEmpty()
        assertWithMessage("包络的 false 是给调用方的信号，不是 miss，不该记 onMiss")
            .that(missed).isEmpty()
    }

    @Test
    fun `包络落在 tier 降序里第一个接下的那层，后面的不再问`() {
        engine(fiveLayers(richTapEnvelope = true)).also { topAccepts ->
            assertThat(topAccepts.playEnvelope(TIMINGS, AMPLITUDES)).isTrue()
        }
        assertWithMessage("tier 3 接下就停在 tier 3，不该再往下问（连厂商层都不必问）")
            .that(journal.filter { it.endsWith(ENVELOPE_CALL) })
            .containsExactly(NAME_RICHTAP + ENVELOPE_CALL)

        // engine() 会把上一段流水清掉，下面只看第二个引擎的动静。
        // 没有厂商层的机器（Pixel 一类）：tier 3 拒绝后 tier 1 正常兜底
        engine(
            listOf(
                FakeBackend(tier = TIER_RICHTAP, name = NAME_RICHTAP),
                FakeBackend(tier = TIER_WAVEFORM, name = NAME_WAVEFORM, envelopeResult = true),
                FakeBackend(tier = TIER_CONSTANTS, name = NAME_CONSTANTS),
            ),
        ).also { waveformAccepts ->
            assertThat(waveformAccepts.playEnvelope(TIMINGS, AMPLITUDES)).isTrue()
        }
        assertWithMessage("tier 3 拒绝后落到 tier 1，落定之后 tier 0 不该再被问")
            .that(journal.filter { it.endsWith(ENVELOPE_CALL) })
            .containsExactly(
                NAME_RICHTAP + ENVELOPE_CALL,
                NAME_WAVEFORM + ENVELOPE_CALL,
            ).inOrder()
    }

    @Test
    fun `增强档不碰包络的控制点`() {
        mode.value = HapticMode.BOOST
        val waveform = FakeBackend(tier = TIER_WAVEFORM, name = NAME_WAVEFORM, envelopeResult = true)
        val haptics = engine(listOf(waveform))

        assertWithMessage("增强档对包络没有影响，该层接下就是 true").that(
            haptics.playEnvelope(TIMINGS, AMPLITUDES),
        ).isTrue()
        assertWithMessage("增强档只上移语义档位，不许给包络乘系数：系统 VibrationScaler 已按用户档位乘过一遍")
            .that(waveform.envelopeCalls)
            .containsExactly(TIMINGS.toList() to AMPLITUDES.toList())
    }

    @Test
    fun `release 逐个关掉五层，被降级链摘掉的那层也要关，重复调用只关一次`() {
        // RichTap 这层报不可用，构造期就被从两条链里摘掉 —— 但它照样持着自己那条单线程 Executor
        val layers = fiveLayers(richTapAvailable = false)
        val haptics = engine(layers)

        haptics.release()
        haptics.release()

        layers.forEach { backend ->
            assertWithMessage("${backend.name} 的 release 必须恰好一次：漏了是线程泄漏，多了说明不幂等")
                .that(backend.releaseCalls).isEqualTo(1)
        }

        // 无马达机型上五层全被摘掉，线程却一条都不少，同样要全关
        val noMotor = fiveLayers()
        engine(noMotor, hasVibrator = false).release()
        noMotor.forEach { backend ->
            assertWithMessage("无马达机型上 ${backend.name} 也要 release，它的 Executor 还在")
                .that(backend.releaseCalls).isEqualTo(1)
        }
    }

    @Test
    fun `release 之后一切静音，也不再碰 backend`() {
        val haptics = engine(fiveLayers())
        haptics.release()
        journal.clear()

        val results = dispatchAll(haptics, hapticView())
        val envelope = haptics.playEnvelope(TIMINGS, AMPLITUDES)
        haptics.stopOngoing()

        assertThat(results).doesNotContain(true)
        assertThat(envelope).isFalse()
        assertWithMessage("release 之后不许再派发、更不许重新起线程，实际流水 $journal")
            .that(journal).isEmpty()
        assertWithMessage("release 之后 stopOngoing 也不该再动 backend").that(quietDownCalls).isEqualTo(0)
        assertThat(missed).isEmpty()
    }

    @Test
    fun `构造期每层各问一次 isAvailable，探测不留给第一次点击`() {
        engine(fiveLayers())

        assertWithMessage("MIUI 与 OPlus 靠构造期这一下把逐 ID 探测甩上自己的线程；不焐热第一次点击会掉到下一层")
            .that(warmUp)
            .containsExactly(
                NAME_RICHTAP + AVAILABILITY_CALL,
                NAME_MIUI + AVAILABILITY_CALL,
                NAME_OPLUS + AVAILABILITY_CALL,
                NAME_WAVEFORM + AVAILABILITY_CALL,
                NAME_CONSTANTS + AVAILABILITY_CALL,
            ).inOrder()
    }

    // ------------------------------------------------------------------------
    // 以下是脚手架
    // ------------------------------------------------------------------------

    /**
     * 建一个引擎，并把构造期那段焐热流水挪进 [warmUp]、清空 [journal]。
     *
     * 清一次是刻意的：构造期每层各被问一次 `isAvailable()`，那不是派发，混在流水里会让
     * 「一层都没碰」的断言永远判红。焐热本身由专门那条用例钉住。
     */
    private fun engine(
        backends: List<HapticBackend>,
        hasVibrator: Boolean = true,
        hasAmplitudeControl: Boolean = true,
    ): AppHaptics {
        val haptics = AppHaptics(
            capabilities = capabilities(hasVibrator, hasAmplitudeControl),
            backends = backends,
            modeState = mode,
            systemHapticEnabled = { systemEnabled },
            quietDown = { quietDownCalls++ },
            onMiss = { missed += it },
        )
        warmUp = journal.toList()
        journal.clear()
        return haptics
    }

    /**
     * 能力快照。[AppHaptics] 只读 `hasVibrator` 与 `lockedToConstants`（= 没有振幅控制），
     * 其余七个字段是各 backend 自己的判据，这里照目标机实测填一份不影响结论的值。
     */
    private fun capabilities(hasVibrator: Boolean, hasAmplitudeControl: Boolean) = HapticCapabilities(
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
     * 造一个只答得出 `isHapticFeedbackEnabled` 的 View。
     *
     * 单测里的 android.jar 会让 View 的每个方法抛「not mocked」，所以造不出真实例；
     * 反过来这也是道保险 —— 被测代码若去碰 View 的别的成员，用例会当场炸而不是静默走过。
     */
    private fun hapticView(enabled: Boolean = true): View {
        val view = mockk<View>()
        every { view.isHapticFeedbackEnabled } returns enabled
        return view
    }

    /**
     * 照生产的接线摆一套五层（顺序与 `HapticModule` 里那份 `listOf` 一致）。
     *
     * 默认全部可用、离散语义照收、包络一律拒绝 —— 包络的默认拒绝对上生产事实：
     * tier 2 是固定预置效果、tier 0 只有常量，两者恒返 false。
     */
    private fun fiveLayers(
        richTapAvailable: Boolean = true,
        richTapEnvelope: Boolean = false,
        waveformEnvelope: Boolean = false,
    ): List<FakeBackend> = listOf(
        FakeBackend(
            tier = TIER_RICHTAP,
            name = NAME_RICHTAP,
            available = richTapAvailable,
            envelopeResult = richTapEnvelope,
        ),
        FakeBackend(tier = TIER_VENDOR, name = NAME_MIUI),
        FakeBackend(tier = TIER_VENDOR, name = NAME_OPLUS),
        FakeBackend(tier = TIER_WAVEFORM, name = NAME_WAVEFORM, envelopeResult = waveformEnvelope),
        FakeBackend(tier = TIER_CONSTANTS, name = NAME_CONSTANTS),
    )

    /**
     * 13 个语义方法逐个调一遍，返回各自的返回值。
     *
     * 顺序与 [NAMED_METHOD_ORDER] 逐行对齐，靠这一对表钉住「每个糖方法派发的是自己那个语义」——
     * 13 个一行一个的转发方法里抄错一行，除了这里没人看得出来。
     */
    private fun dispatchAll(haptics: AppHaptics, view: View?): List<Boolean> = listOf(
        haptics.tap(view),
        haptics.lightTap(view),
        haptics.segmentTick(view),
        haptics.frequentTick(view),
        haptics.toggleOn(view),
        haptics.toggleOff(view),
        haptics.confirm(view),
        haptics.reject(view),
        haptics.dragStart(view),
        haptics.thresholdArmed(view),
        haptics.gestureEnd(view),
        haptics.scrollEdge(view),
        haptics.popupShow(view),
    )

    /**
     * 一层假通路。写成 inner 而不是顶层类：同包的隔壁测试文件也各有自己的 fake，
     * 顶层同名类会撞成同一个 JVM 类名。
     *
     * 每个方法都往共用的 [journal] 里记一笔，跨层的先后次序靠它断言。
     */
    private inner class FakeBackend(
        override val tier: Int,
        override val name: String,
        private val available: Boolean = true,
        private val supported: Set<HapticSemantic> = HapticSemantic.entries.toSet(),
        private val performResult: Boolean = true,
        private val envelopeResult: Boolean = false,
    ) : HapticBackend {

        /** 收到 perform 的语义，按调用序。增强档这里应当是上移之后那个 */
        val performed = mutableListOf<HapticSemantic>()

        /** 被问 supports 的语义，按调用序 */
        val supportsAsked = mutableListOf<HapticSemantic>()

        /** perform 时拿到的 View，tier 0 全靠它 */
        val views = mutableListOf<View?>()

        /** 收到的包络控制点。存 List 而不是原数组：IntArray 的 equals 是引用比较 */
        val envelopeCalls = mutableListOf<Pair<List<Int>, List<Float>>>()

        /** release 次数，必须恰好一次 */
        var releaseCalls = 0

        override fun isAvailable(): Boolean {
            journal += name + AVAILABILITY_CALL
            return available
        }

        override fun supports(semantic: HapticSemantic): Boolean {
            journal += name + SUPPORTS_CALL
            supportsAsked += semantic
            return semantic in supported
        }

        override fun perform(view: View?, semantic: HapticSemantic): Boolean {
            journal += name + PERFORM_CALL
            views += view
            performed += semantic
            return performResult
        }

        override fun playEnvelope(timingsMs: IntArray, amplitudes: FloatArray): Boolean {
            journal += name + ENVELOPE_CALL
            envelopeCalls += timingsMs.toList() to amplitudes.toList()
            return envelopeResult
        }

        override fun release() {
            journal += name + RELEASE_CALL
            releaseCalls++
        }
    }

    private companion object {
        /** [dispatchAll] 里 13 个方法的调用序，逐行对齐 */
        val NAMED_METHOD_ORDER = listOf(
            HapticSemantic.TAP,
            HapticSemantic.LIGHT_TAP,
            HapticSemantic.SEGMENT_TICK,
            HapticSemantic.FREQUENT_TICK,
            HapticSemantic.TOGGLE_ON,
            HapticSemantic.TOGGLE_OFF,
            HapticSemantic.CONFIRM,
            HapticSemantic.REJECT,
            HapticSemantic.DRAG_START,
            HapticSemantic.THRESHOLD_ARMED,
            HapticSemantic.GESTURE_END,
            HapticSemantic.SCROLL_EDGE,
            HapticSemantic.POPUP_SHOW,
        )

        /**
         * 增强档下 13 个方法各自真正派发的语义，与 [NAMED_METHOD_ORDER] 逐项对齐。
         *
         * 照设计文档「语义词表」逐条抄，刻意不写成 `NAMED_METHOD_ORDER.map { it.boosted() }`：
         * 期望值与实现同源时，实现被改坏期望值跟着一起变，这条断言就永远是绿的。
         */
        val BOOSTED_ORDER = listOf(
            HapticSemantic.TAP,             // TAP 顶档
            HapticSemantic.TAP,             // LIGHT_TAP 升成主操作那记实心点击
            HapticSemantic.LIGHT_TAP,       // SEGMENT_TICK
            HapticSemantic.SEGMENT_TICK,    // FREQUENT_TICK 只升一档，连发场景不能累出重震
            HapticSemantic.TAP,             // TOGGLE_ON
            HapticSemantic.TOGGLE_ON,       // TOGGLE_OFF 先升到同族的开
            HapticSemantic.CONFIRM,         // CONFIRM 顶档
            HapticSemantic.REJECT,          // REJECT 顶档
            HapticSemantic.DRAG_START,      // DRAG_START 顶档
            HapticSemantic.THRESHOLD_ARMED, // THRESHOLD_ARMED 顶档
            HapticSemantic.TAP,             // GESTURE_END 要「到位」的实感
            HapticSemantic.SEGMENT_TICK,    // SCROLL_EDGE 会被连着顶，升太重变惩罚
            HapticSemantic.LIGHT_TAP,       // POPUP_SHOW
        )

        /** 五个顶档：增强档必须原样发 */
        val TOP_TIER = setOf(
            HapticSemantic.TAP,
            HapticSemantic.CONFIRM,
            HapticSemantic.REJECT,
            HapticSemantic.DRAG_START,
            HapticSemantic.THRESHOLD_ARMED,
        )

        /** 彩蛋包络的三个控制点，够验「原样传下去」 */
        val TIMINGS = intArrayOf(20, 40, 30)

        /** 与 [TIMINGS] 等长的目标振幅 */
        val AMPLITUDES = floatArrayOf(0.2f, 0.8f, 0.1f)

        /** 一次滑块拖动的量级：连发几十记，静音闸只该响一次 */
        const val FREQUENT_TICK_BURST = 40

        const val TIER_RICHTAP = 3
        const val TIER_VENDOR = 2
        const val TIER_WAVEFORM = 1
        const val TIER_CONSTANTS = 0

        // 名字照生产的 backend 抄，流水读起来就是真机上的降级次序
        const val NAME_RICHTAP = "RichTap"
        const val NAME_MIUI = "MIUI"
        const val NAME_OPLUS = "OPlus"
        const val NAME_WAVEFORM = "AospWaveform"
        const val NAME_CONSTANTS = "AOSP-Constants"

        const val AVAILABILITY_CALL = ".isAvailable"
        const val SUPPORTS_CALL = ".supports"
        const val PERFORM_CALL = ".perform"
        const val ENVELOPE_CALL = ".playEnvelope"
        const val RELEASE_CALL = ".release"
    }
}
