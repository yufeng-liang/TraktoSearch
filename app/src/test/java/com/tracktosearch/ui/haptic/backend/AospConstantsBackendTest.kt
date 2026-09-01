package com.tracktosearch.ui.haptic.backend

import android.view.HapticFeedbackConstants
import android.view.View
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.tracktosearch.ui.haptic.HapticSemantic
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Test

/**
 * [AospConstantsBackend] 的类级行为。此前被覆盖的只有纯函数 [aospConstantFor]，类本身零覆盖。
 *
 * tier 0 是整条降级链的地板：它返 false 时引擎已无更低的层可退；转子马达机型上更是只有这一层
 * 能上场（硬规则 1 把 tier 1 以上全锁掉）。所以本层出错一律是静默的 —— 不抛异常、不打日志，
 * 用户只感觉到「点了没反应」。挑的用例都对着这一类改法：
 *
 * - 把 `perform` 改成恒返 true：引擎以为地板接下了，实际一记都没发出去；
 * - 围着 `performHapticFeedback` 加一条 try-catch 兜底链：它返 `Boolean` 且不抛异常，
 *   那条链永不触发，是死代码（旧实现 ui/util 里的 HapticExt 那条正是如此）；
 * - 顺手换成带 flag 的双参重载：那是 `FLAG_IGNORE_GLOBAL_SETTING` 唯一的入口，红线明令禁止；
 * - 照厂商层抄一条「首次失败即整层禁用」：本层返 false 最常见的原因（系统或 View 关了触感、
 *   View 还没 attach）都会恢复，禁掉之后用户重开系统触感也再也不震；
 * - 把 semantic 忘在参数上、给 `performHapticFeedback` 写死一个常量：13 个交互震成同一档。
 *
 * 纯 JVM 跑，不上 Robolectric：被测路径上的平台调用只有 `View` 的两个方法，用 MockK 造一个
 * （`View` 在单测的 android.jar 里所有方法都会抛「not mocked」，构造不出真实例，理由见
 * [com.tracktosearch.ui.haptic.HapticCapabilitiesTest] 的类说明）；`HapticFeedbackConstants`
 * 的字段都是带 ConstantValue 的 static final int，编译期内联成字面量，运行时不加载该类。
 *
 * [AospConstantsBackend.perform] 里会读一次 `Build.VERSION.SDK_INT`，纯 JVM 下它恒为 0。
 * 「常量选对了没有」那条用例因此只挑 [aospConstantFor] 里**无版本门控**的语义写期望值，
 * 取什么 SDK 都成立；九个带门控语义的逐档取值归 [AospConstantsMappingTest]，
 * 那边把 API 26..45 全域逐格钉过，本文件不重复。
 *
 * MockK 用严格模式：只桩了 `isHapticFeedbackEnabled` 与不带 flag 的单参
 * `performHapticFeedback`，被测代码碰 `View` 的任何别的成员都会当场炸，而不是静默走过。
 * 「只调单参重载」那条用例再用 `confirmVerified` 收口，把 API 37 新加的
 * `performHapticFeedback(HapticFeedbackRequest)` 也一起挡住 —— 它的 Builder 有 `setFlags`，
 * 同样是一条能塞 flag 的路。
 *
 * 依据见 docs/superpowers/plans/2026-09-01-haptics-overhaul.md 的「四层引擎」一节，
 * 以及 [com.tracktosearch.ui.haptic.HapticBackend] 上的八条红线。
 */
class AospConstantsBackendTest {

    private val backend = AospConstantsBackend()

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `View 返 false 时 perform 也返 false，13 个语义一个都不许被吞成 true`() {
        val view = hapticView(accepts = false)

        val results = HapticSemantic.entries.map { backend.perform(view, it) }

        assertWithMessage(
            "performHapticFeedback 返 false 就是「这一记没发出去」；吞掉改返 true 之后" +
                "引擎以为地板接下了，链上再没有更低的层可试，用户什么都感觉不到",
        ).that(results).doesNotContain(true)
        assertWithMessage("这串 false 必须是问过平台之后得到的，不是提前 return 出来的")
            .that(results).hasSize(HapticSemantic.entries.size)
        verify(exactly = HapticSemantic.entries.size) { view.performHapticFeedback(any<Int>()) }
    }

    @Test
    fun `perform 逐次跟随 View 的返回值，既不恒真也不缓存第一次的结果`() {
        // 13 次交替 true/false：恒返 true、恒返 false、只记住第一次的结果，三种改法都在这里判红
        val answers = HapticSemantic.entries.mapIndexed { index, _ -> index % 2 == 0 }
        val view = hapticView()
        every { view.performHapticFeedback(any<Int>()) } returnsMany answers

        val results = HapticSemantic.entries.map { backend.perform(view, it) }

        assertWithMessage("每次派发的结果只由那一次 performHapticFeedback 的返回值决定")
            .that(results).containsExactlyElementsIn(answers).inOrder()
    }

    @Test
    fun `view 为 null 时 perform 返 false，一个 View 都不碰`() {
        val view = hapticView()

        val results = HapticSemantic.entries.map { backend.perform(null, it) }

        assertWithMessage("本层唯一的通路是 View，没有 View 就是发不出去，返 false 让引擎收工")
            .that(results).doesNotContain(true)
        verify(exactly = 0) { view.isHapticFeedbackEnabled }
        verify(exactly = 0) { view.performHapticFeedback(any<Int>()) }
        // 零交互：null 时不许去别处翻一个 View 来顶替（KDoc 明写「不去别处翻一个」）
        confirmVerified(view)

        // 上面那串 false 得是 null 造成的，不是这一层本身坏了；再传一次 null 计数也不许涨，
        // 说明实现没有把上一次用过的 View 悄悄缓存下来顶替 null
        assertThat(backend.perform(view, HapticSemantic.TAP)).isTrue()
        assertThat(backend.perform(null, HapticSemantic.TAP)).isFalse()
        verify(exactly = 1) { view.performHapticFeedback(any<Int>()) }
    }

    @Test
    fun `View 关掉触感就不发，也不因这次 false 把地板永久关掉`() {
        val view = hapticView(enabled = false)

        val results = HapticSemantic.entries.map { backend.perform(view, it) }

        assertWithMessage("View 上的触感开关也是用户设置的一部分，关着就不震")
            .that(results).doesNotContain(true)
        verify(exactly = HapticSemantic.entries.size) { view.isHapticFeedbackEnabled }
        // 这道闸必须拦在调用之前：漏过去就等于给 performHapticFeedback 加了 FLAG_IGNORE_VIEW_SETTING
        verify(exactly = 0) { view.performHapticFeedback(any<Int>()) }
        verify(exactly = 0) { view.performHapticFeedback(any(), any()) }
        confirmVerified(view)

        // 用户把 View 的触感打开，下一次点击就该有：返 false 的原因会恢复，
        // 不许照厂商层那条「首次失败即整层禁用」处理，那会让重开触感之后整个 App 再也不震
        every { view.isHapticFeedbackEnabled } returns true
        assertThat(backend.perform(view, HapticSemantic.TAP)).isTrue()
        assertWithMessage("刚才那串 false 不该把地板关掉").that(backend.isAvailable()).isTrue()
    }

    @Test
    fun `release 之后 isAvailable 与 perform 恒 false，重复调用不抛`() {
        val view = hapticView()
        assertWithMessage("本层没有要探测的能力，构造完就该可用 —— 引擎要在能力探测跑完之前先把地板架好")
            .that(backend.isAvailable()).isTrue()

        backend.release()
        backend.release()

        assertWithMessage("release 幂等，第二次也只是把同一个标志位再置一遍")
            .that(backend.isAvailable()).isFalse()
        val results = HapticSemantic.entries.map { backend.perform(view, it) }
        assertWithMessage("release 之后 13 个语义一律返 false").that(results).doesNotContain(true)
        // 进程正在收尾，马达不该再动：返回值对了但照样调下去，就是 release 形同虚设
        verify(exactly = 0) { view.performHapticFeedback(any<Int>()) }
        assertWithMessage("released 单向不回，多问几次也不许翻回可用")
            .that(backend.isAvailable()).isFalse()
    }

    @Test
    fun `supports 对 13 个语义全返 true，地板不能再降`() {
        HapticSemantic.entries.forEach { semantic ->
            assertWithMessage(
                "$semantic 报不支持就等于降级链没有地板：转子马达机型上只有本层能上场，" +
                    "它一摇头这个交互整机无触感，而每个语义都有常量或退化目标兜着",
            ).that(backend.supports(semantic)).isTrue()
        }

        backend.release()

        HapticSemantic.entries.forEach { semantic ->
            assertWithMessage(
                "supports 刻意不看 released：它是纯查表的能力声明，" +
                    "「这一层还活着吗」归 isAvailable 回答，两处都判会让引擎的判据重复",
            ).that(backend.supports(semantic)).isTrue()
        }
    }

    @Test
    fun `playEnvelope 恒返 false，空数组与不等长与越界都只返 false 不抛`() {
        ENVELOPE_CASES.forEach { (timings, amplitudes) ->
            assertWithMessage(
                "本层只有离散常量，画不出连续包络。拿一记常量假装成功，引擎就不会退成" +
                    "「每段起点一记 tick」的稀疏编排。本组入参：timings ${timings.size} 个、" +
                    "amplitudes ${amplitudes.size} 个",
            ).that(backend.playEnvelope(timings, amplitudes)).isFalse()
        }

        backend.release()

        val (timings, amplitudes) = ENVELOPE_CASES.first()
        assertWithMessage("release 之后同样返 false，不许重新起任何东西")
            .that(backend.playEnvelope(timings, amplitudes)).isFalse()
    }

    @Test
    fun `只调不带 flag 的单参重载，View 上没有第二种动静`() {
        val view = hapticView()

        val results = HapticSemantic.entries.map { backend.perform(view, it) }

        assertWithMessage("View 接下了就该返 true").that(results).doesNotContain(false)
        verify(exactly = HapticSemantic.entries.size) { view.isHapticFeedbackEnabled }
        verify(exactly = HapticSemantic.entries.size) { view.performHapticFeedback(any<Int>()) }
        // 带 flag 的双参重载是 FLAG_IGNORE_GLOBAL_SETTING 唯一的入口，一次都不许被调。
        // 它没被桩，真被调时严格模式的 MockK 会先抛「no answer found」，这条 verify 是第二道保险
        verify(exactly = 0) { view.performHapticFeedback(any(), any()) }
        // 收口：View 上除了那道闸与单参重载，不该有别的动静。API 37 新加的
        // performHapticFeedback(HapticFeedbackRequest) 靠这条挡住 —— 它的 Builder 有 setFlags
        confirmVerified(view)
    }

    @Test
    fun `perform 把语义喂进映射表，不是给平台写死一个常量`() {
        val constants = mutableListOf<Int>()
        val view = mockk<View>()
        every { view.isHapticFeedbackEnabled } returns true
        every { view.performHapticFeedback(capture(constants)) } returns true

        HapticSemantic.entries.forEach { backend.perform(view, it) }

        assertWithMessage("13 个语义都该抵达平台调用").that(constants)
            .hasSize(HapticSemantic.entries.size)
        val bySemantic = HapticSemantic.entries.zip(constants).toMap()
        // 下面挑的四个语义在 aospConstantFor 里无版本门控，期望值与 Build.VERSION.SDK_INT
        // 取什么无关（纯 JVM 下它恒为 0，本用例刻意不依赖这个事实）；
        // 九个带门控语义的逐档取值归 AospConstantsMappingTest
        assertWithMessage("TAP 是通用点击的顶档，走 VIRTUAL_KEY，无门控")
            .that(bySemantic.getValue(HapticSemantic.TAP))
            .isEqualTo(HapticFeedbackConstants.VIRTUAL_KEY)
        assertWithMessage("LIGHT_TAP 走 CLOCK_TICK，无门控")
            .that(bySemantic.getValue(HapticSemantic.LIGHT_TAP))
            .isEqualTo(HapticFeedbackConstants.CLOCK_TICK)
        assertWithMessage("SCROLL_EDGE 在 AOSP 没有对应常量，词表直接给 CLOCK_TICK")
            .that(bySemantic.getValue(HapticSemantic.SCROLL_EDGE))
            .isEqualTo(HapticFeedbackConstants.CLOCK_TICK)
        assertWithMessage("POPUP_SHOW 同样给 CLOCK_TICK")
            .that(bySemantic.getValue(HapticSemantic.POPUP_SHOW))
            .isEqualTo(HapticFeedbackConstants.CLOCK_TICK)
        assertWithMessage("REJECT 在任何档位上都不与 TAP 同常量：它借的是 LONG_PRESS 或 REJECT 那记沉的")
            .that(bySemantic.getValue(HapticSemantic.REJECT))
            .isNotEqualTo(bySemantic.getValue(HapticSemantic.TAP))
        assertWithMessage(
            "13 个语义只用到 ${constants.distinct()} 这些常量，太少了 —— " +
                "semantic 大概根本没被喂进 aospConstantFor，13 个交互会震成同一档",
        ).that(constants.distinct().size).isAtLeast(MIN_DISTINCT_CONSTANTS)
    }

    @Test
    fun `tier 为 0、name 与 tier 1 的 AOSP 层区分得开，release 之后仍可读`() {
        assertWithMessage(
            "tier 必须是 0：报高了会插到振幅波形层前面，" +
                "「转子马达锁 tier 0」那条硬规则就锁不住它 —— 那条按 tier 大于 0 判",
        ).that(backend.tier).isEqualTo(0)
        assertWithMessage("tier 1 的振幅波形层同样是 AOSP，两层同名就分不出降级矩阵最终选中了谁")
            .that(backend.name).isEqualTo(EXPECTED_NAME)

        backend.release()

        assertWithMessage("release 之后 name 仍应可读（HapticBackend 契约），日志与断言还要用它")
            .that(backend.name).isEqualTo(EXPECTED_NAME)
        assertThat(backend.tier).isEqualTo(0)
    }

    /**
     * 造一个只答得出 `isHapticFeedbackEnabled` 与单参 `performHapticFeedback` 的 View。
     *
     * 严格模式是刻意的：这两个之外的任何调用都会抛，于是「悄悄换了个重载」这类改动在
     * verify 之前就已经炸了。`View` 在单测的 android.jar 里所有方法都抛「not mocked」，
     * 本来也构造不出真实例。
     *
     * @param enabled View 自己那道触感开关
     * @param accepts `performHapticFeedback` 的返回值，即「框架接下这一记了吗」
     */
    private fun hapticView(enabled: Boolean = true, accepts: Boolean = true): View {
        val view = mockk<View>()
        every { view.isHapticFeedbackEnabled } returns enabled
        every { view.performHapticFeedback(any<Int>()) } returns accepts
        return view
    }

    private companion object {
        /**
         * 四组包络入参：一段合法的三控制点、空数组、长度不等、振幅越界。
         *
         * 本层对四组的答案都是 false，且都不许抛 —— 契约要求越界与不等长走返回值而不是异常。
         * 实现里两个参数刻意不读、连长度校验都不做，所以这四组测的正是「无论传什么都返 false」。
         */
        val ENVELOPE_CASES: List<Pair<IntArray, FloatArray>> = listOf(
            intArrayOf(20, 40, 30) to floatArrayOf(0.2f, 0.8f, 0.1f),
            intArrayOf() to floatArrayOf(),
            intArrayOf(10) to floatArrayOf(0.5f, 0.5f),
            intArrayOf(-1) to floatArrayOf(2f),
        )

        /**
         * 13 个语义至少该落在三个不同常量上。
         *
         * 三这个下界与 `Build.VERSION.SDK_INT` 无关：任何档位上 TAP 恒是 `VIRTUAL_KEY`、
         * LIGHT_TAP 恒是 `CLOCK_TICK`、REJECT 是 `LONG_PRESS` 或 `REJECT`，三者互不相同。
         * 写死一个常量的改法会让这里只剩 1。
         */
        const val MIN_DISTINCT_CONSTANTS = 3

        /** 生产里的层名，`AppHapticsModeTest` 的流水断言也照这个字面量摆 */
        const val EXPECTED_NAME = "AOSP-Constants"
    }
}
