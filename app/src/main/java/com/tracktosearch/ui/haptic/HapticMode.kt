package com.tracktosearch.ui.haptic

/**
 * 触感三态总开关：跟随系统 / 关闭 / 增强。
 *
 * 只有三档，不做强度滑条 —— ROM 的系统设置里本来就有一条触感强度滑条，
 * 应用内再造一条只会跟系统档位互相打架。绝对强度一律交给系统，
 * 应用侧只决定「发哪个语义」与「发不发」。
 *
 * 持久化在 DataStore（存储类用 StateFlow 镜像磁盘值）。
 * 存储层请写枚举的 `name` 而不是 `ordinal`，读到缺值或无法识别的旧值时回落 [DEFAULT]。
 *
 * 设计依据见 docs/superpowers/plans/2026-09-01-haptics-overhaul.md 的「三态开关」一节。
 */
enum class HapticMode {
    /**
     * 跟随系统（默认档）。
     *
     * 正常走四层引擎：按 [HapticCapabilities] 探到的能力选最高可用 tier，
     * [HapticSemantic] 原样下发，既不上移档位也不缩放振幅。
     *
     * 尊重系统触感设置：目标 View 的 `isHapticFeedbackEnabled` 为 false，
     * 或 `Settings.System.HAPTIC_FEEDBACK_ENABLED` 读到 0 时，本档不震。
     * 这个判定交给系统与引擎层，禁止用 `FLAG_IGNORE_GLOBAL_SETTING` 绕过。
     */
    FOLLOW_SYSTEM,

    /**
     * 关闭。
     *
     * 引擎整体短路：一次调用都不派发，不进降级链，彩蛋编排的连续包络也不播。
     * RichTap 后端额外调一次 `switchHaptic(false)`，免得 SDK 内部还留着已排入的波形。
     */
    OFF,

    /**
     * 增强。
     *
     * 唯一行为是把语义整体上移一档（走 [HapticSemantic.boosted]）：
     * LIGHT_TAP 用 TAP 的效果、SEGMENT_TICK 用 LIGHT_TAP 的效果，以此类推，
     * 已经在顶档的语义返回自身。
     *
     * 不乘任何系数。系统的 `VibrationScaler` 已经按用户选的触感强度档位
     * 乘过 0.6 / 0.8 / 1.0 / 1.2 / 1.4，应用侧再叠一层就是重复缩放 ——
     * 用户把系统调到最强时会直接顶到削波，调到最弱时又被我们的系数拉回来，两头都不对。
     * 所以「增强」改的是效果的选择，不是振幅的数值。
     *
     * 也不绕过系统总开关。系统触感关了就是关了：本档只在系统允许震动时才比
     * [FOLLOW_SYSTEM] 更重，绝不因为用户在应用内选了「增强」就去抢系统的决定权。
     *
     * 上面两条最容易在后续迭代里被「顺手加个系数」或「增强就该无视系统开关」改回去，
     * 动手前先回看设计文档的「三态开关」与「目标机实测」两节。
     */
    BOOST,
    ;

    companion object {
        /** 默认档：跟随系统。存储层缺值、解析失败与单测都从这里取，不要各处再写一遍字面量。 */
        val DEFAULT: HapticMode = FOLLOW_SYSTEM
    }
}
