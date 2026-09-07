package com.tracktosearch.ui.haptic

import android.view.View
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Provider

/**
 * [PopupShowEffect] 的边沿检测。
 *
 * 这个 effect 全部的价值就在「只在 false → true 那一次响」：多响一次就是重复震，
 * 少响一次就是弹窗悄悄冒出来。三条边界各一个用例，外加一条「首帧就是 true 不响」——
 * 那一条对应的是**弹窗开着旋屏**：重建组合时基线重新取当前值，所以不补震。
 *
 * 引擎用真的 [AppHaptics] 加一层只记账的 backend，与 `HapticOutcomeEffectTest` 同一套做法。
 */
@RunWith(AndroidJUnit4::class)
class PopupShowEffectTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val backend = RecordingBackend()

    private val engine = AppHaptics(
        capabilities = CAPABILITIES,
        backends = listOf(backend),
        modeState = MutableStateFlow(HapticMode.FOLLOW_SYSTEM),
        systemHapticEnabled = { true },
        // 默认实现走 android.util.Log，纯 JVM 用例里会抛「not mocked」
        onMiss = {},
    )

    private val haptics by lazy {
        ComposeHaptics(hapticView(), Provider<AppHaptics> { engine })
    }

    private val visible = mutableStateOf(false)

    @Test
    fun `false 到 true 发一记 POPUP_SHOW`() {
        setContent()

        flip(true)

        assertThat(backend.performed).containsExactly(HapticSemantic.POPUP_SHOW)
    }

    /**
     * 弹窗开着旋屏：组合重建、基线重新取当前值（true），所以不该补震一记。
     * 这也是 `remember` 而不是 `rememberSaveable` 存基线的理由。
     */
    @Test
    fun `首帧就是 true 时不发`() {
        visible.value = true
        setContent()

        assertThat(backend.performed).isEmpty()
    }

    /** 关掉再开是两次「冒出来」，该有两记。 */
    @Test
    fun `关掉再打开重新武装`() {
        setContent()

        flip(true)
        flip(false)
        flip(true)

        assertThat(backend.performed)
            .containsExactly(HapticSemantic.POPUP_SHOW, HapticSemantic.POPUP_SHOW)
    }

    /**
     * `visible` 没变的重组不重复发。
     *
     * `LaunchedEffect(visible)` 本身就按 key 去重，这条守的是「哪天有人把 key 改成
     * `Unit` 或者干脆删掉」—— 那种改法在别处看不出问题，只有这里会红。
     */
    @Test
    fun `visible 不变的重组不重复发`() {
        setContent()

        flip(true)
        // 同一个值再写一遍：状态没变，Compose 也不会重组，但 effect 的 key 若被改坏就会重跑
        flip(true)
        composeRule.runOnIdle { recomposeTrigger.value += 1 }
        composeRule.waitForIdle()

        assertThat(backend.performed).containsExactly(HapticSemantic.POPUP_SHOW)
    }

    private val recomposeTrigger = mutableStateOf(0)

    private fun flip(value: Boolean) {
        composeRule.runOnIdle { visible.value = value }
        composeRule.waitForIdle()
    }

    private fun setContent() {
        composeRule.setContent {
            // 读一下触发器，让用例能主动逼一次重组
            @Suppress("UNUSED_EXPRESSION")
            recomposeTrigger.value
            val show by visible
            PopupShowEffect(visible = show, haptics = haptics)
        }
        composeRule.waitForIdle()
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

    /** 链上唯一一层：什么都接，只记账。 */
    private class RecordingBackend : HapticBackend {
        override val tier: Int = 0
        override val name: String = "recording"

        /** 收到 perform 的语义，按调用序 */
        val performed = mutableListOf<HapticSemantic>()

        override fun isAvailable(): Boolean = true

        override fun supports(semantic: HapticSemantic): Boolean = true

        override fun perform(view: View?, semantic: HapticSemantic): Boolean {
            performed += semantic
            return true
        }

        override fun playEnvelope(timingsMs: IntArray, amplitudes: FloatArray): Boolean = false

        override fun release() = Unit
    }

    private companion object {
        /** 一台「有马达、有振幅控制」的普通机器，与 `HapticOutcomeEffectTest` 同一份。 */
        val CAPABILITIES = HapticCapabilities(
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
    }
}
