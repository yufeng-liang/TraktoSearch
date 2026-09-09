package com.tracktosearch.ui.haptic

import android.view.View
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
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
 * [HapticOutcomeEffect] 的生命周期闸门。
 *
 * 闸门是这个设计的**全部理由** —— 不要闸门的话，在 ViewModel 里直接调 `perform(null, …)`
 * 更省事。所以这里测的不是「能不能转发」，而是「关着的时候真的不响、而且事后不补」。
 *
 * 递一个自建的 [LifecycleOwner]（[LifecycleRegistry.createUnsafe]，不引入
 * lifecycle-runtime-testing）而不是用真实的 `LocalLifecycleOwner`：Robolectric 里没法把
 * 宿主 Activity 稳定地停在 `STARTED` 上再推到 `RESUMED`，而这正是要跨的那道边。
 *
 * 引擎用真的 [AppHaptics] 加一层只记账的 backend，与 `ComposeHapticsTest` 同一套做法 ——
 * 顺带把「结果 → 语义 → backend」这条真链路一起钉住。
 */
@RunWith(AndroidJUnit4::class)
class HapticOutcomeEffectTest {

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

    private val emitter = HapticOutcomeEmitter()

    private val owner = FakeLifecycleOwner()

    private val haptics by lazy {
        ComposeHaptics(hapticView(), Provider<AppHaptics> { engine })
    }

    @Test
    fun `RESUMED 时成功发 CONFIRM、失败发 REJECT`() {
        setContent(Lifecycle.State.RESUMED)

        emit { emitter.success() }
        emit { emitter.failure() }

        assertThat(backend.performed)
            .containsExactly(HapticSemantic.CONFIRM, HapticSemantic.REJECT)
            .inOrder()
    }

    /**
     * 只到 `STARTED` 就不响：被弹窗盖住、多窗口里失焦那一半都停在这一档，
     * 那时候震属于纯干扰。
     */
    @Test
    fun `只到 STARTED 时一记都不发`() {
        setContent(Lifecycle.State.STARTED)

        emit { emitter.success() }

        assertThat(backend.performed).isEmpty()
    }

    /**
     * 闸门关着时到达的结果**丢掉**，不排队。升到 `RESUMED` 之后只响新到的那一记。
     *
     * 这条同时钉住产品代码两侧：收集器的 `repeatOnLifecycle(RESUMED)` 与
     * [HapticOutcomeEmitter] 的 `replay = 0`。改一边都会让这条红。
     */
    @Test
    fun `暂停期间的结果不补震，恢复后只响新的`() {
        setContent(Lifecycle.State.STARTED)

        emit { emitter.failure() }
        assertThat(backend.performed).isEmpty()

        emit { owner.registry.currentState = Lifecycle.State.RESUMED }
        // 补震的话这里就已经有一记 REJECT 了
        assertThat(backend.performed).isEmpty()

        emit { emitter.success() }
        assertThat(backend.performed).containsExactly(HapticSemantic.CONFIRM)
    }

    /** 生命周期降回 `STARTED` 之后重新静音 —— 闸门是双向的。 */
    @Test
    fun `从 RESUMED 退回 STARTED 后重新静音`() {
        setContent(Lifecycle.State.RESUMED)

        emit { emitter.success() }
        assertThat(backend.performed).containsExactly(HapticSemantic.CONFIRM)

        emit { owner.registry.currentState = Lifecycle.State.STARTED }
        emit { emitter.failure() }

        assertThat(backend.performed).containsExactly(HapticSemantic.CONFIRM)
    }

    /** 在主线程上做一件事，然后等 Compose 与协程都跑完。 */
    private fun emit(block: () -> Unit) {
        composeRule.runOnIdle(block)
        composeRule.waitForIdle()
    }

    private fun setContent(state: Lifecycle.State) {
        // 状态在组合之前推到位：放在 composable 体里就是组合期副作用，重组时会再跑一遍
        owner.registry.currentState = state
        composeRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                HapticOutcomeEffect(
                    outcomes = emitter.outcomes,
                    haptics = haptics,
                    lifecycleOwner = owner,
                )
            }
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

    /**
     * 用例自己推状态的宿主。
     *
     * `createUnsafe` 而不是普通构造：普通构造的 [LifecycleRegistry] 会校验状态变更发生在
     * 主线程上，而这里的推动来自用例代码，不值得为它套一层 looper 判断。
     */
    private class FakeLifecycleOwner : LifecycleOwner {
        @Suppress("VisibleForTests")
        val registry: LifecycleRegistry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }

    /** 链上唯一一层：什么都接，只记账。 */
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

        override fun playEnvelope(timingsMs: IntArray, amplitudes: FloatArray): Boolean = false

        override fun release() = Unit
    }

    private companion object {
        /** 一台「有马达、有振幅控制」的普通机器，与 `ComposeHapticsTest` 同一份。 */
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
            huaweiSupported = false,
        )
    }
}
