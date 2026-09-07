package com.tracktosearch.ui.haptic

/**
 * 设备侧那两个「用户选了也不一定震」的前提。
 *
 * 两项都不是应用能改的，只能读出来告诉用户。设置页在每次回到前台时重读一遍
 * （用户可能刚从系统设置里把总开关关掉又切回来），所以这里是个快照而不是常量。
 *
 * @property systemHapticEnabled 系统触感总开关，取 `Settings.System.VIBRATE_ON` 与
 *   `HAPTIC_FEEDBACK_ENABLED` 的合值，见 [systemHapticFeedbackEnabled]。
 * @property hasVibrator 本机有没有马达，取 `Vibrator.hasVibrator()`，
 *   见 `HapticCapabilities.deviceHasVibrator`。
 */
data class HapticSystemState(
    val systemHapticEnabled: Boolean,
    val hasVibrator: Boolean,
) {
    companion object {
        /**
         * 读到真值之前的乐观占位：两项都按可用算。
         *
         * 反过来（默认按不可用）会让设置页在读盘那一两帧里先闪一句「本机没有振动马达」，
         * 绝大多数机器上那句话还是错的。宁可晚一帧显示限制，也不要先说错话。
         */
        val OPTIMISTIC = HapticSystemState(systemHapticEnabled = true, hasVibrator = true)
    }
}

/**
 * 设置页触感卡片副标题该说哪一句。
 *
 * 五个取值不是「三档 + 两个错误」，而是**同一个位置上互斥的五句话**：
 * 用户选的档位只有在设备真会震的前提下才值得回显，否则那句「增强」是在骗人。
 */
enum class HapticModeSummary {
    /** 本机没有马达：选哪一档都不会有反馈 */
    NO_VIBRATOR,

    /** 系统触感总开关关着：应用侧无权覆盖 */
    SYSTEM_DISABLED,

    /** 跟随系统（正常回显档位） */
    FOLLOW_SYSTEM,

    /** 关闭（正常回显档位） */
    OFF,

    /** 增强（正常回显档位） */
    BOOST,
}

/**
 * 按「设备事实优先于用户选择」定档，四条分支的**先后顺序本身就是设计**。
 *
 * 1. **没马达最先判。** 这条是永久的硬事实，连「关闭」都不必再说 ——
 *    用户自己关掉的和机器根本震不了，后者才是他要知道的那件事。
 * 2. **其次是用户自己选的「关闭」。** 此时即便系统总开关也关着，也不报
 *    [SYSTEM_DISABLED]：那句话读起来像「系统拦着你」，而实际上是应用自己关了，
 *    等用户回头把系统打开，会以为应用该震了，结果还是不震。
 * 3. **再判系统总开关。** 只对 [HapticMode.FOLLOW_SYSTEM] 与 [HapticMode.BOOST] 有意义：
 *    这两档下用户的选择完全落不了地。缺了这句，选了「增强」却一点感觉都没有，
 *    看起来就是应用坏了 —— 这是「三态开关」一节要求不得绕过系统设置的直接后果：
 *    既然不绕过，就得把绕不过这件事说清楚。
 * 4. 剩下的才回显档位本身。
 *
 * 纯函数，无 Android 依赖，可直接在 `src/test` 里跑满 3 × 2 × 2 的组合。
 *
 * 设计依据见 docs/superpowers/plans/2026-09-01-haptics-overhaul.md 的「三态开关」一节。
 */
fun hapticModeSummary(mode: HapticMode, state: HapticSystemState): HapticModeSummary = when {
    !state.hasVibrator -> HapticModeSummary.NO_VIBRATOR
    mode == HapticMode.OFF -> HapticModeSummary.OFF
    !state.systemHapticEnabled -> HapticModeSummary.SYSTEM_DISABLED
    mode == HapticMode.BOOST -> HapticModeSummary.BOOST
    else -> HapticModeSummary.FOLLOW_SYSTEM
}
