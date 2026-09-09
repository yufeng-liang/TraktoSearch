package com.tracktosearch.ui.haptic

/**
 * 触感四态总开关：跟随系统（默认）/ 轻 / 关闭 / 强。
 *
 * 轻/强两档走**强度通道**而不是语义上移：同样的效果整体调轻/调重，效果「性格」不变。
 * 这条是 2026-09-09 调研后改的——原设计里「强」把语义上移一档（LIGHT_TAP 借 TAP 的效果），
 * 但上移会改性格：滑块连发的轻网格被顶成齿轮，一次拖动几十下重震，违反「连发必须极轻」。
 * 调研拿到 MIUI 的官方强度参数（`performHapticFeedback(key, boolean, strength)`，
 * 0 弱 / 1 中 / 2 强 / -100 跟随系统）后，强度有了不改性格的直达通路，语义上移废止。
 *
 * 仍然不做强度滑条：轻/强/跟随系统三档与 MIUI 原生强度档一一对应，再细就与
 * ROM 系统设置里的触感强度滑条打架。**跟随系统档（默认）不传强度**，效果用
 * ROM 自己的默认强度——那套默认就是「厂商调好的」。
 *
 * 持久化在 DataStore（形状照 [com.tracktosearch.data.local.SharedTransitionStorage]）。
 * 存储层请写枚举的 `name` 而不是 `ordinal`，读到缺值或无法识别的旧值时回落 [DEFAULT]。
 *
 * 设计依据见 docs/superpowers/plans/2026-09-01-haptics-overhaul.md 的「三态开关」一节
 * 与 2026-09-09 增补（档位重设计）。
 */
enum class HapticMode {
    /**
     * 跟随系统（默认档）。
     *
     * 正常走四层引擎：按 [HapticCapabilities] 探到的能力选最高可用 tier，
     * [HapticSemantic] 原样下发、强度用 ROM 默认（不传强度参数）。
     * MIUI String 通路上即两参重载（-100 跟随系统），恰是系统应用自己的行为。
     *
     * 尊重系统触感设置：目标 View 的 `isHapticFeedbackEnabled` 为 false，
     * 或 `Settings.System.HAPTIC_FEEDBACK_ENABLED` 读到 0 时，本档不震。
     * 这个判定交给系统与引擎层，禁止用 `FLAG_IGNORE_GLOBAL_SETTING` 绕过。
     */
    FOLLOW_SYSTEM,

    /**
     * 轻。
     *
     * 只改强度不改语义：MIUI String 通路传强度 0（官方弱档），RichTap 预置强度
     * ×0.6，tier 1 振幅 ×0.6；tier 0 常量没有强度通道，本档在该层不生效（地板层，
     * 转子马达机型才常年停在 tier 0）。彩蛋的连续包络不受档位影响（谱子说了算）。
     *
     * 同样不绕过系统总开关：系统关了就是关了。
     */
    LIGHT,

    /**
     * 关闭。
     *
     * 引擎整体短路：一次调用都不派发，不进降级链，彩蛋编排的连续包络也不播。
     * 静音钩子会停掉已排入的连续波形，免得手里还在震完剩下的几秒。
     */
    OFF,

    /**
     * 强（内部名沿用 BOOST，存储的是枚举 `name`，改名等于把老用户的档位静默重置）。
     *
     * 只改强度不改语义：MIUI String 通路传强度 2（官方强档），RichTap 预置强度
     * ×1.3（封顶 255），tier 1 振幅 ×1.2；tier 0 常量没有强度通道，本档在该层不生效。
     * 彩蛋的连续包络不受档位影响。
     *
     * 不绕过系统总开关：系统触感关了就是关了——本档只在系统允许震动时才比
     * [FOLLOW_SYSTEM] 更重，绝不因为用户在应用内选了「强」就去抢系统的决定权。
     *
     * 「顺手改成语义上移」或「顺手乘个系数叠加系统 VibrationScaler」都改错过一轮，
     * 动手前先回设计文档的「三态开关」与 2026-09-09 增补两节。
     */
    BOOST,
    ;

    companion object {
        /** 默认档：跟随系统。存储层缺值、解析失败与单测都从这里取，不要各处再写一遍字面量。 */
        val DEFAULT: HapticMode = FOLLOW_SYSTEM
    }
}

/**
 * 一次派发的强度档。引擎按 [HapticMode] 算出来随语义一起下发，各 backend 自行映射：
 *
 * - MIUI String 通路：`performHapticFeedback(key, true, strength)` 的第三参，
 *   0 / -100 / 2（官方强度档，`sys.haptic.<key>` 属性表里的就是这套值）
 * - RichTap 预置：`playExtPrebaked(id, strength)` 的 0..255，见 `RichTapStrength` 缩放
 * - tier 1：振幅台阶与 primitive scale 乘系数
 * - tier 0 常量：没有强度通道，[LIGHT]/[STRONG] 在该层不生效
 *
 * [SYSTEM] 表示「不传强度、用 ROM 默认」——各层都有自己的「不干预」形态
 * （MIUI 两参重载、RichTap 原档位、tier 1 系数 1）。
 */
enum class HapticStrength {
    /** 弱：整体轻一档（[HapticMode.LIGHT]） */
    LIGHT,

    /** 跟随系统：不传强度，用 ROM 默认（[HapticMode.FOLLOW_SYSTEM]） */
    SYSTEM,

    /** 强：整体重一档（[HapticMode.BOOST]） */
    STRONG,
}

/**
 * 档位对应的派发强度。[HapticMode.OFF] 返 null——那一档在引擎里整体短路，
 * 根本走不到「问强度」这一步，调用方用可空类型把这个状态显式表达出来。
 */
val HapticMode.strength: HapticStrength?
    get() = when (this) {
        HapticMode.FOLLOW_SYSTEM -> HapticStrength.SYSTEM
        HapticMode.LIGHT -> HapticStrength.LIGHT
        HapticMode.OFF -> null
        HapticMode.BOOST -> HapticStrength.STRONG
    }
