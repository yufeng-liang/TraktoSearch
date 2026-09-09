package com.tracktosearch.ui.haptic

import android.view.View
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Test
import javax.inject.Provider

/**
 * [ComposeHaptics] 三件事：语义映射、[ComposeHaptics.frequentTick] 的节流闸门、拿不到引擎时的行为。
 *
 * 这一层是薄的转发，但三种故障都是静默的：映射写错只是某个交互换了手感；节流失效会在一次滑块
 * 拖动里连发几十记，糊成一段嗡鸣；`Provider` 解析失败若外泄成异常，一次点击会把业务逻辑一起带走。
 *
 * 时钟从构造参数注进来（[ComposeHaptics] 的 `nanoTime`），所以节流用例是确定性的 ——
 * 靠 `Thread.sleep` 卡 40 ms 边界的写法必然 flaky。用例里的间隔全部**写死 40 ms**，
 * 不去引用产品代码那个常量：引用了的话把常量改成 400 ms 用例照样绿，那就白测了。
 *
 * 引擎用真的 [AppHaptics] 加一层只记账的 [RecordingBackend]，不 mock `AppHaptics` ——
 * 顺带把「门面 → 引擎 → backend」这条真链路上的语义传递也一起钉住了。
 * 只有 `View` 是 mock 的：单测的 android.jar 会让 `View` 每个方法抛「not mocked」，造不出真实例。
 */
class ComposeHapticsTest {

    /** 注入给 [ComposeHaptics] 的假时钟，纳秒。用例自己往前推 */
    private var nowNanos = 0L

    /** 记账用的地板层，链上只有它一个 */
    private val backend = RecordingBackend()

    /** [Provider.get] 被调了几次。用来钉「解析失败不重试」 */
    private var providerCalls = 0

    private val engine = AppHaptics(
        capabilities = CAPABILITIES,
        backends = listOf(backend),
        modeState = MutableStateFlow(HapticMode.FOLLOW_SYSTEM),
        systemHapticEnabled = { true },
        // 默认实现走 android.util.Log，纯 JVM 用例里会抛「not mocked」
        onMiss = {},
    )

    /** 被测对象。[provider] 传 null 模拟没人 provide LocalAppHaptics 的组合树 */
    private fun haptics(
        provider: Provider<AppHaptics>? = Provider<AppHaptics> { providerCalls++; engine },
    ) = ComposeHaptics(hapticView(), provider) { nowNanos }

    /**
     * 13 个具名方法各自派发对应的语义，一个不漏一个不错。
     *
     * 末尾那句 `containsExactlyElementsIn` 是防漏的：往 [HapticSemantic] 加语义却忘了在
     * [ComposeHaptics] 上开对应方法时，本用例会红。
     */
    @Test
    fun `13 个具名方法各自派发对应语义`() {
        val cases: List<Pair<HapticSemantic, (ComposeHaptics) -> Unit>> = listOf(
            HapticSemantic.TAP to { h: ComposeHaptics -> h.tap() },
            HapticSemantic.LIGHT_TAP to { h: ComposeHaptics -> h.lightTap() },
            HapticSemantic.SEGMENT_TICK to { h: ComposeHaptics -> h.segmentTick() },
            HapticSemantic.FREQUENT_TICK to { h: ComposeHaptics -> h.frequentTick() },
            HapticSemantic.TOGGLE_ON to { h: ComposeHaptics -> h.toggleOn() },
            HapticSemantic.TOGGLE_OFF to { h: ComposeHaptics -> h.toggleOff() },
            HapticSemantic.CONFIRM to { h: ComposeHaptics -> h.confirm() },
            HapticSemantic.REJECT to { h: ComposeHaptics -> h.reject() },
            HapticSemantic.DRAG_START to { h: ComposeHaptics -> h.dragStart() },
            HapticSemantic.THRESHOLD_ARMED to { h: ComposeHaptics -> h.thresholdArmed() },
            HapticSemantic.GESTURE_END to { h: ComposeHaptics -> h.gestureEnd() },
            HapticSemantic.SCROLL_EDGE to { h: ComposeHaptics -> h.scrollEdge() },
            HapticSemantic.POPUP_SHOW to { h: ComposeHaptics -> h.popupShow() },
        )
        for ((expected, invoke) in cases) {
            backend.performed.clear()
            invoke(haptics())
            assertWithMessage("派发 $expected 的具名方法")
                .that(backend.performed)
                .containsExactly(expected)
        }
        assertWithMessage("每个语义都得有具名方法")
            .that(cases.map { it.first })
            .containsExactlyElementsIn(HapticSemantic.entries)
    }

    /** 开关族的糖：`toggle(checked)` 按新状态挑，不是反过来 */
    @Test
    fun `toggle 按新状态挑开或关`() {
        haptics().toggle(checked = true)
        assertThat(backend.performed).containsExactly(HapticSemantic.TOGGLE_ON)

        backend.performed.clear()
        haptics().toggle(checked = false)
        assertThat(backend.performed).containsExactly(HapticSemantic.TOGGLE_OFF)
    }

    /**
     * 节流闸门正好在 40 ms：39 ms 那记丢掉，40 ms 那记放过。
     *
     * 两个方向都钉住了 —— 把间隔调大（40 → 400）会让 40 ms 那记被丢，
     * 把 `<` 写成 `<=` 会让边界上那记被丢，把整段节流删掉会让 39 ms 那记漏进来。
     */
    @Test
    fun `frequentTick 的节流闸门在 40 毫秒`() {
        val haptics = haptics()

        haptics.frequentTick()
        assertWithMessage("第一次调用不该被节流吞掉")
            .that(backend.performed)
            .containsExactly(HapticSemantic.FREQUENT_TICK)

        nowNanos += 39L * 1_000_000L
        haptics.frequentTick()
        assertWithMessage("距上一记 39 ms，该丢")
            .that(backend.performed)
            .hasSize(1)

        nowNanos += 1L * 1_000_000L
        haptics.frequentTick()
        assertWithMessage("距上一记正好 40 ms，该放过")
            .that(backend.performed)
            .hasSize(2)
    }

    /** 一次滑块拖动打的那几十记：时间不走，就只出去一记 */
    @Test
    fun `同一时刻连发 40 次只出去一记`() {
        val haptics = haptics()
        repeat(40) { haptics.frequentTick() }
        assertThat(backend.performed).containsExactly(HapticSemantic.FREQUENT_TICK)
    }

    /**
     * 节流只长在 [ComposeHaptics.frequentTick] 上，[ComposeHaptics.perform] 不管。
     *
     * 这条是给 T6 的彩蛋编排留的：那边按乐句发密集 tick，是设计好的节奏，
     * 被这一层悄悄吞掉就不成谱子了。把节流搬进 `perform` 会让本用例红。
     */
    @Test
    fun `perform 传 FREQUENT_TICK 不过节流`() {
        val haptics = haptics()
        repeat(5) { haptics.perform(HapticSemantic.FREQUENT_TICK) }
        assertThat(backend.performed).hasSize(5)
    }

    /**
     * 没人 provide [LocalAppHaptics] 时全体静默 —— `@Preview` 与那三十来个不建 Hilt 图的
     * 屏幕测试就是这个状态，它们不该为了触感各补一份 provide，更不该因此崩。
     */
    @Test
    fun `provider 为 null 时全体静默且不抛`() {
        val haptics = haptics(provider = null)

        haptics.tap()
        haptics.frequentTick()
        haptics.perform(HapticSemantic.CONFIRM)

        assertThat(backend.performed).isEmpty()
    }

    /**
     * `Provider.get()` 抛异常时不外泄，而且**只试一次**。
     *
     * 触感是装饰：Hilt 图坏了不该由「用户点了个按钮」这件事把进程带走，缺了它这次点击的业务
     * 逻辑照样得跑完。而每次点击都重试一遍只是重复抛同一个异常，所以失败要闩住。
     */
    @Test
    fun `provider 抛异常时既不外泄也不重试`() {
        val failing = Provider<AppHaptics> {
            providerCalls++
            error("Hilt graph is broken")
        }
        val haptics = haptics(provider = failing)

        repeat(3) { haptics.tap() }

        assertWithMessage("异常被吞掉，没有派发")
            .that(backend.performed)
            .isEmpty()
        assertWithMessage("解析失败闩住，三次点击只试了一次")
            .that(providerCalls)
            .isEqualTo(1)
    }

    /** 解析成功之后也只 `get()` 一次，之后走缓存 */
    @Test
    fun `解析成功后不再重复 get`() {
        val haptics = haptics()
        repeat(3) { haptics.tap() }
        assertThat(providerCalls).isEqualTo(1)
        assertThat(backend.performed).hasSize(3)
    }

    /**
     * 造一个只答得出 `isHapticFeedbackEnabled` 的 View。
     *
     * 单测的 android.jar 会让 `View` 每个方法抛「not mocked」，所以造不出真实例；
     * 反过来这也是道保险 —— 被测代码若去碰 `View` 的别的成员，用例会当场炸而不是静默走过。
     */
    private fun hapticView(): View {
        val view = mockk<View>()
        every { view.isHapticFeedbackEnabled } returns true
        return view
    }

    /**
     * 链上唯一一层：什么都接，只记账。
     *
     * tier 取 0 只是要个合法值 —— 链上只有它一个，排序结果无从改变。
     */
    private class RecordingBackend : HapticBackend {
        override val tier: Int = 0
        override val name: String = "recording"

        /** 收到 perform 的语义，按调用序 */
        val performed = mutableListOf<HapticSemantic>()

        override fun isAvailable(): Boolean = true

        override fun supports(semantic: HapticSemantic): Boolean = true

        override fun perform(view: View?, semantic: HapticSemantic, strength: HapticStrength): Boolean {
            performed += semantic
            return true
        }

        /** 本文件不测包络：[ComposeHaptics] 刻意不转发 `playEnvelope` */
        override fun playEnvelope(timingsMs: IntArray, amplitudes: FloatArray): Boolean = false

        override fun release() = Unit
    }

    private companion object {
        /**
         * 一台「有马达、有振幅控制」的普通机器。
         *
         * 只有 `hasVibrator` 与 `hasAmplitudeControl` 对本文件有意义：前者为 false 会让
         * [AppHaptics] 把降级链清空，后者为 false（转子马达）会只留 tier 0 —— 两种都会让
         * 语义映射的用例失去意义。其余字段是 backend 各自的事，这里给保守值。
         */
        val CAPABILITIES = HapticCapabilities(
            hasVibrator = true,
            hasAmplitudeControl = true,
            supportedPrimitives = emptySet(),
            compositionSizeMax = 0,
            envelopeSupported = false,
            envelopeMaxSize = 0,
            richTapSupported = false,
            hapticPlayerSupported = false,
            miuiSupported = false,
            oplusSupported = false,
        )
    }
}
