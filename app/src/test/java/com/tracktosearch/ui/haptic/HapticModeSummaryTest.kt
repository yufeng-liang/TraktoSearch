package com.tracktosearch.ui.haptic

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * [hapticModeSummary] 的判定单测：设置页那一行副标题说什么，全靠这几条分支的**先后顺序**。
 *
 * 顺序错了不崩不报错，只会让用户读到一句正确但没用的话 —— 系统总开关关着时回显「强」，
 * 于是他以为应用坏了；或者反过来，自己选了「关闭」却被告知「系统已关闭触感」，
 * 跑去系统里把开关打开，回来发现还是不震。
 *
 * 期望值是一张手抄的 4 × 2 × 2 全组合表，没写成 `when` 的镜像：
 * 期望值与实现同源时，实现改坏期望值跟着一起变，这个测试永远是绿的。
 *
 * 被测代码零 android 依赖，纯 JVM 跑。
 */
class HapticModeSummaryTest {

    /**
     * 全部 16 种组合（四档 × 有无马达 × 系统总开关），逐条手写。
     *
     * key 是 `(mode, hasVibrator, systemHapticEnabled)`。
     */
    private val expected: Map<Triple<HapticMode, Boolean, Boolean>, HapticModeSummary> = mapOf(
        // 没马达：四档一律 NO_VIBRATOR，系统开关是什么都不再重要
        Triple(HapticMode.FOLLOW_SYSTEM, false, true) to HapticModeSummary.NO_VIBRATOR,
        Triple(HapticMode.FOLLOW_SYSTEM, false, false) to HapticModeSummary.NO_VIBRATOR,
        Triple(HapticMode.LIGHT, false, true) to HapticModeSummary.NO_VIBRATOR,
        Triple(HapticMode.LIGHT, false, false) to HapticModeSummary.NO_VIBRATOR,
        Triple(HapticMode.OFF, false, true) to HapticModeSummary.NO_VIBRATOR,
        Triple(HapticMode.OFF, false, false) to HapticModeSummary.NO_VIBRATOR,
        Triple(HapticMode.BOOST, false, true) to HapticModeSummary.NO_VIBRATOR,
        Triple(HapticMode.BOOST, false, false) to HapticModeSummary.NO_VIBRATOR,
        // 有马达 + 系统允许：如实回显用户选的档
        Triple(HapticMode.FOLLOW_SYSTEM, true, true) to HapticModeSummary.FOLLOW_SYSTEM,
        Triple(HapticMode.LIGHT, true, true) to HapticModeSummary.LIGHT,
        Triple(HapticMode.OFF, true, true) to HapticModeSummary.OFF,
        Triple(HapticMode.BOOST, true, true) to HapticModeSummary.BOOST,
        // 有马达 + 系统关着：三档落不了地，报 SYSTEM_DISABLED；用户自己关的仍报 OFF
        Triple(HapticMode.FOLLOW_SYSTEM, true, false) to HapticModeSummary.SYSTEM_DISABLED,
        Triple(HapticMode.LIGHT, true, false) to HapticModeSummary.SYSTEM_DISABLED,
        Triple(HapticMode.OFF, true, false) to HapticModeSummary.OFF,
        Triple(HapticMode.BOOST, true, false) to HapticModeSummary.SYSTEM_DISABLED,
    )

    @Test
    fun `16 种组合逐条对上`() {
        val actual = expected.keys.associateWith { (mode, hasVibrator, systemEnabled) ->
            hapticModeSummary(mode, HapticSystemState(systemEnabled, hasVibrator))
        }
        assertThat(actual).containsExactlyEntriesIn(expected)
    }

    @Test
    fun `组合表覆盖满 四档 × 有无马达 × 系统开关`() {
        assertThat(HapticMode.entries).hasSize(EXPECTED_MODE_COUNT)
        assertThat(expected).hasSize(EXPECTED_MODE_COUNT * 2 * 2)
        // 加一档就必须回来补四行，而不是让新档静默落进 else 分支
        assertThat(expected.keys.map { it.first }.toSet())
            .containsExactlyElementsIn(HapticMode.entries)
    }

    @Test
    fun `没马达压过用户自己选的关闭`() {
        // 反过来（先判 OFF）会在无马达机器上回显「关闭」：话没错，但把「机器根本震不了」
        // 这件唯一值得说的事咽下去了，用户会以为打开就能震
        val summary = hapticModeSummary(
            HapticMode.OFF,
            HapticSystemState(systemHapticEnabled = true, hasVibrator = false)
        )
        assertThat(summary).isEqualTo(HapticModeSummary.NO_VIBRATOR)
    }

    @Test
    fun `用户自己选的关闭压过系统总开关`() {
        // 报 SYSTEM_DISABLED 读起来像「系统拦着你」，而实际是应用自己关的：
        // 用户去系统里打开开关回来，还是不震
        val summary = hapticModeSummary(
            HapticMode.OFF,
            HapticSystemState(systemHapticEnabled = false, hasVibrator = true)
        )
        assertThat(summary).isEqualTo(HapticModeSummary.OFF)
    }

    @Test
    fun `系统关着时 跟随系统 轻 强 都落不了地`() {
        // 这三档都不绕过系统总开关（见 HapticMode 的 KDoc），所以都得说出绕不过这件事
        listOf(HapticMode.FOLLOW_SYSTEM, HapticMode.LIGHT, HapticMode.BOOST).forEach { mode ->
            assertWithMessage("$mode 在系统触感关闭时必须报 SYSTEM_DISABLED，不能回显档位本身")
                .that(
                    hapticModeSummary(
                        mode,
                        HapticSystemState(systemHapticEnabled = false, hasVibrator = true)
                    )
                )
                .isEqualTo(HapticModeSummary.SYSTEM_DISABLED)
        }
    }

    @Test
    fun `乐观占位下四档如实回显`() {
        // 读盘之前用 OPTIMISTIC，此时绝不能先闪一句「没有马达」或「系统已关闭」
        HapticMode.entries.forEach { mode ->
            val summary = hapticModeSummary(mode, HapticSystemState.OPTIMISTIC)
            assertWithMessage("$mode 在乐观占位下报了 $summary，等于对着多数机器先说错话")
                .that(summary)
                .isNoneOf(HapticModeSummary.NO_VIBRATOR, HapticModeSummary.SYSTEM_DISABLED)
        }
    }

    @Test
    fun `六个取值都可达，没有死分支`() {
        // 少掉任何一支都意味着某种情况不再有对应文案：漏 SYSTEM_DISABLED 是最贵的那种，
        // 选了强档毫无感觉又没有解释，看起来就是应用坏了
        val reachable = expected.values.toSet()
        assertThat(reachable).containsExactlyElementsIn(HapticModeSummary.entries)
    }

    private companion object {
        /** 四档：跟随系统 / 轻 / 关闭 / 强，与 2026-09-09 档位重设计一致。 */
        const val EXPECTED_MODE_COUNT = 4
    }
}
