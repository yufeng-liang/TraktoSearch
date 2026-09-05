package com.tracktosearch.ui.haptic

import androidx.compose.runtime.Composable
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.flow.Flow

/**
 * 把 ViewModel 发出的成功 / 失败结果接到马达上。每屏一行：
 *
 * ```kotlin
 * HapticOutcomeEffect(viewModel.hapticOutcomes)
 * ```
 *
 * ### 为什么收集器在界面侧，而不是 ViewModel 里直接调
 *
 * `AppHaptics.perform(view, semantic)` 的 `view` 可空，在 ViewModel 里注入
 * `Provider<AppHaptics>` 调 `perform(null, …)` 是编译得过的。四条理由不那么做：
 *
 * 1. **传 null 等于摘掉 tier 0。** 转子马达机型只剩 tier 0 那一层，传 null 时那台机器上
 *    整个输出侧一声不响。`perform` 的 `view` 刻意不给默认值就是为了防这个。
 * 2. **ViewModel 活得比屏幕长。** 网络回来时用户可能已经离开这一屏、甚至 App 已在后台。
 *    震一记而屏幕上什么都没有，比不震更差。只有界面侧能挂生命周期。
 * 3. **线程。** `AppHaptics` 要求主线程，而已知有结果发布在 IO 上（`DoubanLoginScreen`
 *    的登录成功、`DoubanSyncManager` 的**所有**终态）。在 ViewModel 里直调要逐点判线程；
 *    收集在这里一律主线程，零判断。
 * 4. **测不了。** ViewModel 直调要在每个纯 JVM 的 VM 测试里塞一个 mock；走事件流则只是
 *    断言一个枚举值。
 *
 * ### 闸门为什么是 RESUMED 而不是 STARTED
 *
 * toast 迟到还看得见，触感迟到只是莫名震一下。`STARTED` 涵盖「被弹窗盖住」「多窗口里
 * 失焦那一半」这些用户注意力不在这儿的状态，那时候震属于纯干扰。
 *
 * [HapticOutcomeEmitter] 那边 `replay = 0`，所以闸门关着的时候到达的结果是**丢掉**而不是
 * 排队 —— 两处配合才有「不补震」这个效果，改一边都不成立。
 *
 * @param outcomes 结果流，通常是 [HapticOutcomeEmitter.outcomes]。
 */
@Composable
fun HapticOutcomeEffect(outcomes: Flow<HapticOutcome>) {
    val haptics = rememberAppHaptics()
    val lifecycleOwner = LocalLifecycleOwner.current
    HapticOutcomeEffect(outcomes = outcomes, haptics = haptics, lifecycleOwner = lifecycleOwner)
}

/**
 * 可测的那一层：把 `haptics` 与 `lifecycleOwner` 从 composition local 里挪到参数上。
 *
 * 生命周期闸门是这个设计的全部理由，而它只能靠递一个 `TestLifecycleOwner` 来测 ——
 * 真实的 `LocalLifecycleOwner` 在 Robolectric 里推不到 `STARTED` 停一停再上 `RESUMED`。
 */
@Composable
internal fun HapticOutcomeEffect(
    outcomes: Flow<HapticOutcome>,
    haptics: ComposeHaptics,
    lifecycleOwner: LifecycleOwner,
) {
    LaunchedEffect(outcomes, haptics, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            outcomes.collect { haptics.perform(it.semantic()) }
        }
    }
}
