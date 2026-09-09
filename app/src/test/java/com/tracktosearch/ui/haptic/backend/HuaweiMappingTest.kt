package com.tracktosearch.ui.haptic.backend

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.tracktosearch.ui.haptic.HapticSemantic
import org.junit.Test

/**
 * tier 2（华为）效果键表与语义映射的判定单测。
 *
 * 这一层的全部产品行为就是「哪个语义发哪个 `haptic.*` 键」一张表，而键字符串
 * 抄错一个字母是**静默失败**：不崩、不报错，只会让 `isSupportHwVibrator`
 * 对着一个不存在的键返回 false，从此这个语义永远降级——手上只觉得轻了点。
 *
 * 所以期望值一律写成字面量，照反编译 `com.huawei.devices:hapticskit:1.0.0.400`
 * 的 `HapticsKitConstant` 常量池重抄（2026-09-09 解包核对），刻意不写成
 * `HuaweiHapticEffects.DIALER_CLICK` 那种从被测代码取值的形式：期望值与实现同源时，
 * 常量被改坏期望值跟着一起变，这个测试永远是绿的。
 *
 * 只测纯函数。[huaweiEffectFor] 是文件顶层函数，零 Context、零反射、零 android
 * 依赖，纯 JVM 可跑。HuaweiBackend 类本身要 `com.huawei.android.os.VibratorEx`
 * 反射，JVM 里起不来（那正是它的门控），不在本文件射程内。
 */
class HuaweiMappingTest {

    /**
     * 13 个语义在 tier 2 上各发哪个效果键。改这张表就是改产品手感，动手前先回
     * 设计文档与 `huaweiEffectFor` 的注释（映射按华为系统场景语义对位，未经真机校验）。
     */
    private val expectedEffect: Map<HapticSemantic, String> = mapOf(
        HapticSemantic.TAP to "haptic.dialler.click",
        HapticSemantic.LIGHT_TAP to "haptic.camera.click",
        HapticSemantic.SEGMENT_TICK to "haptic.camera.gear_slip",
        HapticSemantic.FREQUENT_TICK to "haptic.contacts.letters_index",
        HapticSemantic.TOGGLE_ON to "haptic.camera.mode_switch",
        HapticSemantic.TOGGLE_OFF to "haptic.camera.portrait_switch",
        HapticSemantic.CONFIRM to "haptic.lockscreen.unlock_click",
        HapticSemantic.REJECT to "haptic.fingerprint.unlock_fail",
        HapticSemantic.DRAG_START to "haptic.desktop.long_press",
        HapticSemantic.THRESHOLD_ARMED to "haptic.battery.charging",
        HapticSemantic.GESTURE_END to "haptic.clock.timer",
        HapticSemantic.SCROLL_EDGE to "haptic.volume.maxmin",
        HapticSemantic.POPUP_SHOW to "haptic.systemui.notifications_expand",
    )

    /**
     * 22 个常量按反编译常量池的取值重抄。名字只用在失败信息里定位，
     * 断言靠的是字面量字符串本身。
     */
    private val constantTable: List<Pair<String, String>> = listOf(
        "DIALER_CLICK" to HuaweiHapticEffects.DIALER_CLICK,
        "CAMERA_CLICK" to HuaweiHapticEffects.CAMERA_CLICK,
        "CAMERA_CLICK_UP" to HuaweiHapticEffects.CAMERA_CLICK_UP,
        "CAMERA_FOCUS" to HuaweiHapticEffects.CAMERA_FOCUS,
        "CAMERA_GEAR_SLIP" to HuaweiHapticEffects.CAMERA_GEAR_SLIP,
        "CAMERA_LONG_PRESS" to HuaweiHapticEffects.CAMERA_LONG_PRESS,
        "CAMERA_MODE_SWITCH" to HuaweiHapticEffects.CAMERA_MODE_SWITCH,
        "CAMERA_PORTRAIT_SWITCH" to HuaweiHapticEffects.CAMERA_PORTRAIT_SWITCH,
        "BATTERY_CHARGING" to HuaweiHapticEffects.BATTERY_CHARGING,
        "CLOCK_TIMER" to HuaweiHapticEffects.CLOCK_TIMER,
        "CLOCK_STOPWATCH" to HuaweiHapticEffects.CLOCK_STOPWATCH,
        "CONTACTS_LETTERS_INDEX" to HuaweiHapticEffects.CONTACTS_LETTERS_INDEX,
        "DIALER_LONG_PRESS" to HuaweiHapticEffects.DIALER_LONG_PRESS,
        "DESKTOP_LONG_PRESS" to HuaweiHapticEffects.DESKTOP_LONG_PRESS,
        "FINGERPRINT_UNLOCK_FAIL" to HuaweiHapticEffects.FINGERPRINT_UNLOCK_FAIL,
        "LOCKSCREEN_UNLOCK_CLICK" to HuaweiHapticEffects.LOCKSCREEN_UNLOCK_CLICK,
        "SYSTEMUI_NOTIFICATIONS_EXPAND" to HuaweiHapticEffects.SYSTEMUI_NOTIFICATIONS_EXPAND,
        "SYSTEMUI_NOTIFICATIONS_LONG_PRESS" to HuaweiHapticEffects.SYSTEMUI_NOTIFICATIONS_LONG_PRESS,
        "VOLUME_MAXMIN" to HuaweiHapticEffects.VOLUME_MAXMIN,
        "VOLUME_TRIGGER" to HuaweiHapticEffects.VOLUME_TRIGGER,
        "WALLET_TIME_SCROLL" to HuaweiHapticEffects.WALLET_TIME_SCROLL,
    )

    @Test
    fun `13 个语义各映射到一个华为效果键`() {
        HapticSemantic.entries.forEach { semantic ->
            val effect = huaweiEffectFor(semantic)
            assertWithMessage("$semantic 的映射").that(effect).isEqualTo(expectedEffect.getValue(semantic))
        }
    }

    @Test
    fun `全部映射键必须落在常量表里`() {
        // 映射表挑的每个键都得是常量表核对过取值的那个，防止映射处手滑写出
        // 一个谁都没定义过的新键——那种键 isSupport 永远 false，语义永远降级
        val known = constantTable.map { it.second }.toSet()
        HapticSemantic.entries.forEach { semantic ->
            val effect = huaweiEffectFor(semantic)
            assertWithMessage("$semantic 映射到了常量表之外的键").that(known).contains(effect)
        }
    }

    @Test
    fun `效果键的格式是 haptic 点小写下划线`() {
        // 华为 framework 按字符串匹配效果键，格式走样（大写、空格、连字符）就是静默失败
        val format = "^haptic\\.[a-z0-9_]+(\\.[a-z0-9_]+)+$"
        constantTable.forEach { (name, key) ->
            assertWithMessage("$name=$key").that(key).matches(format)
        }
    }

    @Test
    fun `常量取值照反编译常量池重抄一遍`() {
        // 与上面 constantTable 的字面量意图相同：这里逐个断言防的是
        // 常量对象被人改了一个字母而映射测试还绿着
        assertThat(HuaweiHapticEffects.DIALER_CLICK).isEqualTo("haptic.dialler.click")
        assertThat(HuaweiHapticEffects.CAMERA_CLICK).isEqualTo("haptic.camera.click")
        assertThat(HuaweiHapticEffects.CAMERA_CLICK_UP).isEqualTo("haptic.camera.click_up")
        assertThat(HuaweiHapticEffects.CAMERA_FOCUS).isEqualTo("haptic.camera.focus")
        assertThat(HuaweiHapticEffects.CAMERA_GEAR_SLIP).isEqualTo("haptic.camera.gear_slip")
        assertThat(HuaweiHapticEffects.CAMERA_LONG_PRESS).isEqualTo("haptic.camera.long_press")
        assertThat(HuaweiHapticEffects.CAMERA_MODE_SWITCH).isEqualTo("haptic.camera.mode_switch")
        assertThat(HuaweiHapticEffects.CAMERA_PORTRAIT_SWITCH).isEqualTo("haptic.camera.portrait_switch")
        assertThat(HuaweiHapticEffects.BATTERY_CHARGING).isEqualTo("haptic.battery.charging")
        assertThat(HuaweiHapticEffects.CLOCK_TIMER).isEqualTo("haptic.clock.timer")
        assertThat(HuaweiHapticEffects.CLOCK_STOPWATCH).isEqualTo("haptic.clock.stopwatch")
        assertThat(HuaweiHapticEffects.CONTACTS_LETTERS_INDEX).isEqualTo("haptic.contacts.letters_index")
        assertThat(HuaweiHapticEffects.DIALER_LONG_PRESS).isEqualTo("haptic.dialler.long_press")
        assertThat(HuaweiHapticEffects.DESKTOP_LONG_PRESS).isEqualTo("haptic.desktop.long_press")
        assertThat(HuaweiHapticEffects.FINGERPRINT_UNLOCK_FAIL).isEqualTo("haptic.fingerprint.unlock_fail")
        assertThat(HuaweiHapticEffects.LOCKSCREEN_UNLOCK_CLICK).isEqualTo("haptic.lockscreen.unlock_click")
        assertThat(HuaweiHapticEffects.SYSTEMUI_NOTIFICATIONS_EXPAND).isEqualTo("haptic.systemui.notifications_expand")
        assertThat(HuaweiHapticEffects.SYSTEMUI_NOTIFICATIONS_LONG_PRESS).isEqualTo("haptic.systemui.notifications_long_press")
        assertThat(HuaweiHapticEffects.VOLUME_MAXMIN).isEqualTo("haptic.volume.maxmin")
        assertThat(HuaweiHapticEffects.VOLUME_TRIGGER).isEqualTo("haptic.volume.trigger")
        assertThat(HuaweiHapticEffects.WALLET_TIME_SCROLL).isEqualTo("haptic.wallet.time_scroll")
    }
}
