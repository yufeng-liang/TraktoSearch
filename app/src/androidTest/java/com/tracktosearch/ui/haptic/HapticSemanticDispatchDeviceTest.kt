package com.tracktosearch.ui.haptic

import android.view.View
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.tracktosearch.ui.haptic.backend.AospConstantsBackend
import com.tracktosearch.ui.haptic.backend.AospWaveformBackend
import com.tracktosearch.ui.haptic.backend.MiuiBackend
import com.tracktosearch.ui.haptic.backend.OplusBackend
import com.tracktosearch.ui.haptic.backend.RichTapBackend
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 语义派发的真机断言：13 个 [HapticSemantic] 在这台机器上**每一个都得有人接**。
 *
 * 纯 JVM 单测只能证明「降级链的排序规则对」—— 那边的 backend 全是 mock，
 * `isAvailable()` 返什么由测试自己写。真机上才知道这台机器实际探到了哪几层、
 * 以及那几层是不是真的把 13 个语义全覆盖住了。漏掉一个语义的后果是那种交互
 * **静默无声**：既不崩也不报，只是手上没反应，靠人工试手感才发现。
 *
 * 这里刻意不去断言「马达真的震了」—— 测不到。断言的是
 * [AppHaptics.perform] 的返回值：true 表示降级链上有一层接下了这次派发。
 *
 * ### 为什么自己装 [AppHaptics] 而不是从 Hilt 取
 *
 * 要控制两个输入：`systemHapticEnabled` 与档位。真机的系统触感开关可能是关的，
 * 用户档位也可能停在「关闭」，那时候整条链正确地返回 false —— 测的就不再是引擎了。
 * 所以这两项在测试里固定住，让断言只反映「这台机器的能力够不够覆盖 13 个语义」。
 * backend 列表与顺序照抄 `HapticModule.provideAppHaptics`。
 */
@RunWith(AndroidJUnit4::class)
class HapticSemanticDispatchDeviceTest {

    @get:Rule
    val rule = createComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    /**
     * 拿一个**已挂到窗口上**的 View：tier 0 走的是 `View.performHapticFeedback`，
     * 而未 attach 的 View 上那个调用是会返回 false 的。
     */
    private fun attachedView(): View {
        lateinit var captured: View
        rule.setContent {
            val view = LocalView.current
            SideEffect { captured = view }
        }
        rule.waitForIdle()
        // View 自己的触感开关也是用户设置的一部分，AppHaptics 会照它拦；测试里固定为开
        captured.isHapticFeedbackEnabled = true
        return captured
    }

    private fun appHaptics(
        mode: HapticMode,
        misses: MutableList<HapticSemantic> = mutableListOf(),
    ): Pair<AppHaptics, HapticCapabilities> {
        val capabilities = HapticCapabilities.probe(context)
        val haptics = AppHaptics(
            capabilities = capabilities,
            backends = listOf(
                RichTapBackend(context, capabilities),
                MiuiBackend(context, capabilities),
                OplusBackend(context, capabilities),
                AospWaveformBackend(context, capabilities),
                AospConstantsBackend(),
            ),
            modeState = MutableStateFlow(mode),
            systemHapticEnabled = { true },
            onMiss = { misses += it },
        )
        return haptics to capabilities
    }

    @Test
    fun 每个语义在本机都有一层接下() {
        val view = attachedView()
        val misses = mutableListOf<HapticSemantic>()
        val (haptics, capabilities) = appHaptics(HapticMode.FOLLOW_SYSTEM, misses)
        try {
            val accepted = HapticSemantic.entries.filter { haptics.perform(view, it) }

            if (!capabilities.hasVibrator) {
                // 无马达机型（多数模拟器）：整条链应当**全部**拒绝，而不是假装接下了。
                // tier 0 的 performHapticFeedback 在无马达设备上照样返回 true，
                // AppHaptics 必须在构造期就把链清空 —— 这条断言守的正是那道短路
                assertWithMessage("本机无马达，任何语义都不该有人接").that(accepted).isEmpty()
                return
            }

            assertWithMessage("这些语义在本机没有任何一层接下，对应交互会静默无声")
                .that(HapticSemantic.entries - accepted.toSet())
                .isEmpty()
            assertThat(misses).isEmpty()
        } finally {
            haptics.release()
        }
    }

    @Test
    fun 增强档不会让任何语义掉出降级链() {
        val view = attachedView()
        val misses = mutableListOf<HapticSemantic>()
        val (haptics, capabilities) = appHaptics(HapticMode.BOOST, misses)
        try {
            if (!capabilities.hasVibrator) return

            // BOOST 把语义整体上移一档。上移之后的那一档在本机同样得有人接 ——
            // 「增强」不该把某个语义从有声变成无声
            val accepted = HapticSemantic.entries.filter { haptics.perform(view, it) }
            assertWithMessage("增强档下这些语义掉出了降级链")
                .that(HapticSemantic.entries - accepted.toSet())
                .isEmpty()
            assertThat(misses).isEmpty()
        } finally {
            haptics.release()
        }
    }

    @Test
    fun 关闭档下任何语义都不震() {
        val view = attachedView()
        val misses = mutableListOf<HapticSemantic>()
        val (haptics, _) = appHaptics(HapticMode.OFF, misses)
        try {
            val accepted = HapticSemantic.entries.filter { haptics.perform(view, it) }

            assertWithMessage("用户选了「关闭」，这些语义还是震了").that(accepted).isEmpty()
            // OFF 是用户的选择，不是「链上没人接」。记 onMiss 会把用户设置误报成能力缺失
            assertWithMessage("OFF 档的静音不该记成 miss").that(misses).isEmpty()
        } finally {
            haptics.release()
        }
    }

    /**
     * `view = null` 等于把 tier 0 摘掉。转子马达机型只剩 tier 0，那种机器上传 null
     * 就是整个 App 一点触感都没有 —— 这条把该事实钉在测试里，顺便记录本机属于哪种。
     */
    @Test
    fun 传null时只有tier0会被摘掉() {
        val misses = mutableListOf<HapticSemantic>()
        val (haptics, capabilities) = appHaptics(HapticMode.FOLLOW_SYSTEM, misses)
        try {
            val accepted = HapticSemantic.entries.filter { haptics.perform(null, it) }

            if (capabilities.lockedToConstants || !capabilities.hasVibrator) {
                assertWithMessage("本机只剩 tier 0，摘掉之后不该还有人接").that(accepted).isEmpty()
            } else {
                assertWithMessage("本机有 tier 1 以上，摘掉 tier 0 后语义仍应全覆盖")
                    .that(HapticSemantic.entries - accepted.toSet())
                    .isEmpty()
            }
        } finally {
            haptics.release()
        }
    }

    /**
     * 能力探测本身不许抛，且必须自洽。`probe` 内部逐项 `catch (Throwable)`，
     * 任何一项失败只退成保守值 —— 这条断言的是「退下来之后的组合仍然讲得通」。
     */
    @Test
    fun 能力探测自洽() {
        val capabilities = HapticCapabilities.probe(context)

        assertThat(capabilities.lockedToConstants).isEqualTo(!capabilities.hasAmplitudeControl)
        if (capabilities.supportedPrimitives.isNotEmpty()) {
            assertWithMessage("探到了 primitive 却说拼不出 Composition")
                .that(capabilities.compositionSizeMax).isAtLeast(1)
        }
        if (capabilities.envelopeSupported) {
            assertWithMessage("说支持包络却给出 0 个控制点")
                .that(capabilities.envelopeMaxSize).isAtLeast(1)
        }
        if (!capabilities.hasVibrator) {
            // 没有马达时上面那几层的「支持」都没有意义，探测不该报 true
            assertThat(capabilities.hasAmplitudeControl).isFalse()
            assertThat(capabilities.supportedPrimitives).isEmpty()
        }
    }
}
