package com.tracktosearch.ui.haptic.backend

import com.tracktosearch.ui.haptic.HapticSemantic

/**
 * 华为 Haptics Kit 的效果键全集，键值照反编译 `com.huawei.devices:hapticskit:1.0.0.400`
 * 的 `HapticsKitConstant` 常量池逐条抄（2026-09-09 解包核对），格式 `haptic.<场景>.<动作>`。
 *
 * 这些键是华为 EMUI / HarmonyOS（AOSP 底）framework 里自己调过的预置波形，
 * 与 MIUI 的 `EFFECT_KEY_*`、OPPO 的效果 ID 同一性质：只能「选」，不能画波形、不能调强度。
 * 派发形状见 [HuaweiBackend]：`VibratorEx.setHwVibrator(键)`，派发前逐键
 * `VibratorEx.isSupportHwVibrator(键)` 探测——华为是三家里唯一给了逐效果探测接口的。
 *
 * 全集 45 个键里这张表收 17 个：其余是键盘五档强度（`haptic.grade.strength1-5`）、
 * 语音输入、指纹录入、锁屏解锁失败这类三方 App 用不上的系统场景。列出的 17 个
 * 并非全部被 [huaweiEffectFor] 用到，未用的留着当近邻候选，取值已核对，比日后再
 * 解包一次便宜。
 */
internal object HuaweiHapticEffects {
    /** 拨号盘按键。华为系统里最高频的通用点击反馈，实心一击 */
    const val DIALER_CLICK = "haptic.dialler.click"

    /** 相机界面轻点。比拨号键场景化更轻的一记 */
    const val CAMERA_CLICK = "haptic.camera.click"

    /** 相机快门抬起 */
    const val CAMERA_CLICK_UP = "haptic.camera.click_up"

    /** 相机对焦。短促的确认感 */
    const val CAMERA_FOCUS = "haptic.camera.focus"

    /** 相机齿轮滑动。切换焦段/变焦时一格一格的刻度感 */
    const val CAMERA_GEAR_SLIP = "haptic.camera.gear_slip"

    /** 相机长按 */
    const val CAMERA_LONG_PRESS = "haptic.camera.long_press"

    /** 拍照/录像模式切换。状态「立起来」的那记 */
    const val CAMERA_MODE_SWITCH = "haptic.camera.mode_switch"

    /** 人像模式切换。与模式切换成对的另一个变体 */
    const val CAMERA_PORTRAIT_SWITCH = "haptic.camera.portrait_switch"

    /** 电池接入充电。插电瞬间「接上了」的一记 */
    const val BATTERY_CHARGING = "haptic.battery.charging"

    /** 计时器走完。事件完成的「叮」一下 */
    const val CLOCK_TIMER = "haptic.clock.timer"

    /** 秒表按键。清脆的短点 */
    const val CLOCK_STOPWATCH = "haptic.clock.stopwatch"

    /** 联系人字母索引侧栏。一次滑动里逐个字母划过，连发极轻 */
    const val CONTACTS_LETTERS_INDEX = "haptic.contacts.letters_index"

    /** 拨号长按 */
    const val DIALER_LONG_PRESS = "haptic.dialler.long_press"

    /** 桌面长按进入编辑。「抓住了」的沉的一记 */
    const val DESKTOP_LONG_PRESS = "haptic.desktop.long_press"

    /** 指纹解锁失败。失败语义的落点 */
    const val FINGERPRINT_UNLOCK_FAIL = "haptic.fingerprint.unlock_fail"

    /** 锁屏解锁成功的落定一击 */
    const val LOCKSCREEN_UNLOCK_CLICK = "haptic.lockscreen.unlock_click"

    /** 通知面板展开。面板「落」出来的出现感 */
    const val SYSTEMUI_NOTIFICATIONS_EXPAND = "haptic.systemui.notifications_expand"

    /** 通知面板长按 */
    const val SYSTEMUI_NOTIFICATIONS_LONG_PRESS = "haptic.systemui.notifications_long_press"

    /** 音量到极值。撞墙/到头的一记 */
    const val VOLUME_MAXMIN = "haptic.volume.maxmin"

    /** 音量调节触发 */
    const val VOLUME_TRIGGER = "haptic.volume.trigger"

    /** 钱包卡片时间滚动 */
    const val WALLET_TIME_SCROLL = "haptic.wallet.time_scroll"
}

/**
 * 语义 → 华为效果键。本层唯一可单测的部分：不碰 Context、不碰反射，纯输入输出。
 *
 * 映射按华为效果键的**系统场景语义**对位到 [HapticSemantic] 的语义梯度：
 *
 * - 通用点击给拨号键（华为系统里最通用的那记），次级轻点给相机轻点；
 * - 分段刻度给相机齿轮、密集连发给联系人字母索引——华为给这两类「一次手势里
 *   连续几十下」的场景各做了一个键，正好对上梯度最轻的两档；
 * - 开关族用相机的两个模式切换键分摊开与关；
 * - 成功给锁屏解锁、失败给指纹失败——华为自己的「通过/不通过」就是这两个场景；
 * - 长按起手给桌面长按、到阈值给充电接入、撞墙给音量极值、弹窗给通知面板展开。
 *
 * **轻重未经华为真机校验**（手边只有小米 14 Pro）：各键的实际手感是华为在自家
 * 系统场景里调的，三方借用时档位感可能有偏差。第一台华为真机上过手后，
 * 不满意的落点改这一张表即可；键不被 `isSupportHwVibrator` 认时引擎自动降级
 * tier 1 / tier 0，不会静默丢事件。
 *
 * `when` 穷举、不写 `else`：往 [HapticSemantic] 加语义时编译器会逼着补映射。
 */
internal fun huaweiEffectFor(semantic: HapticSemantic): String = when (semantic) {
    // 拨号键：华为系统里最高频的点击反馈，通用点击顶档的落点
    HapticSemantic.TAP -> HuaweiHapticEffects.DIALER_CLICK
    // 相机轻点：场景上比拨号键轻一档，给一屏里反复出现的列表项
    HapticSemantic.LIGHT_TAP -> HuaweiHapticEffects.CAMERA_CLICK
    // 齿轮滑动：一格一格卡进格子的刻度感，Tab/分段控件
    HapticSemantic.SEGMENT_TICK -> HuaweiHapticEffects.CAMERA_GEAR_SLIP
    // 字母索引：一次滑动逐个字母连发，专给密集 tick 的极轻档
    HapticSemantic.FREQUENT_TICK -> HuaweiHapticEffects.CONTACTS_LETTERS_INDEX
    // 模式切换：状态「立起来」
    HapticSemantic.TOGGLE_ON -> HuaweiHapticEffects.CAMERA_MODE_SWITCH
    // 人像切换：同一族的另一个变体，开关族里较轻的一半
    HapticSemantic.TOGGLE_OFF -> HuaweiHapticEffects.CAMERA_PORTRAIT_SWITCH
    // 解锁成功：华为系统里「办成了」的落定一击
    HapticSemantic.CONFIRM -> HuaweiHapticEffects.LOCKSCREEN_UNLOCK_CLICK
    // 指纹失败：华为自己的失败语义场景
    HapticSemantic.REJECT -> HuaweiHapticEffects.FINGERPRINT_UNLOCK_FAIL
    // 桌面长按进编辑：沉的一记「抓住了」
    HapticSemantic.DRAG_START -> HuaweiHapticEffects.DESKTOP_LONG_PRESS
    // 充电接入：插上电那记「接上了」的上冲，「可以松手了」
    HapticSemantic.THRESHOLD_ARMED -> HuaweiHapticEffects.BATTERY_CHARGING
    // 计时器走完：事件落定的「叮」，面板吸附的轻响
    HapticSemantic.GESTURE_END -> HuaweiHapticEffects.CLOCK_TIMER
    // 音量极值：到头撞墙的一记
    HapticSemantic.SCROLL_EDGE -> HuaweiHapticEffects.VOLUME_MAXMIN
    // 通知面板展开：面板「落」出来的出现感
    HapticSemantic.POPUP_SHOW -> HuaweiHapticEffects.SYSTEMUI_NOTIFICATIONS_EXPAND
}
