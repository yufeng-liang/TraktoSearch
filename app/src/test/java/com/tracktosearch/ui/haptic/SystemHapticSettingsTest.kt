package com.tracktosearch.ui.haptic

import android.content.ContentResolver
import android.content.Context
import android.content.ContextWrapper
import android.provider.Settings
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * [systemHapticFeedbackEnabled] 的四条分支。
 *
 * 这个函数是「不覆盖用户的系统触感设置」这条红线在 **tier 2 与 tier 3 上的唯一实现**：
 * 那两层走厂商 IPC 与 RichTap 自己的通路，AOSP 不替我们判 `VIBRATE_ON` 与
 * `HAPTIC_FEEDBACK_ENABLED`（tier 0 与 tier 1 才有系统那道拦）。所以它读错键、把 `&&`
 * 写成 `||`、或者把兜底方向弄反，都不会让任何别的测试变红 —— 后果是用户在系统设置里
 * 把振动全关了，App 照样在震。本文件就是把这四条钉住。
 *
 * ### 为什么用 Robolectric
 *
 * 判定完全落在 `Settings.System` 上，而纯 JVM 单测里 `Settings.System.getInt` 是
 * android.jar 的桩方法，一调就抛 `Stub!`。Robolectric 把它插桩成一份进程内的 map
 * （`ShadowSettings.ShadowSystem`），才能用 `putInt` 逐档摆出四种系统状态。
 * 那份 map 由 Robolectric 的 `@Resetter` 在每个用例前清回默认值，用例之间不串味；
 * 「两个键都缺」那条用例还会自己先断言键真的读不出来，免得默认值悄悄替它作答。
 *
 * `@Config` 照 [HapticCapabilitiesTest] 已跑通的那一套：sdk 钉 35（sdk 36 要 Java 21，
 * 本仓是 JVM 17），`application` 换成裸 `Application` 把清单里那个会拉起后台线程的
 * Application 隔离掉。被测函数不看 `SDK_INT`，钉版本只为不受 robolectric.properties 影响。
 *
 * ### 第四条为什么必须抛 Error 而不是 Exception
 *
 * 实现写的是 `catch (_: Throwable)`。这不是过度防御：这两个键在 android-37 的 SDK 里
 * 已标废弃，哪天 ROM 把字段抽掉就是 `NoSuchFieldError`／`NoClassDefFoundError`，
 * 都是 `Error` 不是 `Exception`，`catch (Exception)` 一条都兜不住 —— 而这里兜不住等于
 * 每次点击都从触感引擎里抛出去。所以 Error 与 Exception 两种都各钉一条用例。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class SystemHapticSettingsTest {

    /** 被测函数只碰 `context.contentResolver`，沙箱的 Application 就够用。 */
    private val context: Context = RuntimeEnvironment.getApplication()

    @Test
    fun `VIBRATE_ON 为 0 时不震`() {
        // 只关振动总开关，触摸反馈仍开着 —— 判定必须是「任一为 0 就不震」，
        // 写成 || 或者干脆漏读这个键，这条就红
        writeSetting(KEY_VIBRATE_ON, OFF)
        writeSetting(KEY_HAPTIC_FEEDBACK_ENABLED, ON)

        assertWithMessage("用户关掉了振动总开关，走厂商 IPC 的 tier 2 与 tier 3 必须跟着闭嘴")
            .that(systemHapticFeedbackEnabled(context))
            .isFalse()
    }

    @Test
    fun `HAPTIC_FEEDBACK_ENABLED 为 0 时不震`() {
        // 反过来只关触摸反馈。设计文档点的就是这一条键，漏读它等于红线在 tier 2 上失效
        writeSetting(KEY_VIBRATE_ON, ON)
        writeSetting(KEY_HAPTIC_FEEDBACK_ENABLED, OFF)

        assertWithMessage("用户关掉了触摸反馈，厂商通路上没人替我们拦，只能在这里拦")
            .that(systemHapticFeedbackEnabled(context))
            .isFalse()
    }

    @Test
    fun `两个键都关时不震`() {
        writeSetting(KEY_VIBRATE_ON, OFF)
        writeSetting(KEY_HAPTIC_FEEDBACK_ENABLED, OFF)

        assertThat(systemHapticFeedbackEnabled(context)).isFalse()
    }

    @Test
    fun `两个键都开时放行`() {
        // 正例。缺了它，一个恒返 false 的实现能把上面三条全绿地骗过去
        writeSetting(KEY_VIBRATE_ON, ON)
        writeSetting(KEY_HAPTIC_FEEDBACK_ENABLED, ON)

        assertWithMessage("两个系统开关都开着，引擎不该自己加一道禁令")
            .that(systemHapticFeedbackEnabled(context))
            .isTrue()

        // 判据是「只有 0 算关」而不是「只有 1 算开」：与「读不到时放行」同一个保守方向,
        // 宁可多震也不拿一个没见过的取值把用户的触感判死
        writeSetting(KEY_VIBRATE_ON, UNEXPECTED_NON_ZERO)
        assertWithMessage("非 0 取值一律当开，不许收紧成「必须等于 1」")
            .that(systemHapticFeedbackEnabled(context))
            .isTrue()
    }

    @Test
    fun `两个键都没写入时放行`() {
        // 先自证前提：这两个键在沙箱里真的读不出来。少了这一步，哪天 Robolectric 给
        // 它们补上默认值，这条用例就会在「其实读到了 1」的情况下照样绿，白站一个位置
        assertWithMessage("前提不成立：VIBRATE_ON 在沙箱里已有值，这条用例验不到「键缺失」")
            .that(readRawSetting(KEY_VIBRATE_ON))
            .isNull()
        assertWithMessage("前提不成立：HAPTIC_FEEDBACK_ENABLED 在沙箱里已有值")
            .that(readRawSetting(KEY_HAPTIC_FEEDBACK_ENABLED))
            .isNull()

        // 个别 ROM 没有这两个键。此时把整个 App 的触感判死比多震一下更糟，
        // 而 tier 0 与 tier 1 那两层系统仍然会自己拦
        assertWithMessage("键不存在时该放行，不是判死")
            .that(systemHapticFeedbackEnabled(context))
            .isTrue()
    }

    @Test
    fun `读取抛 Error 时放行`() {
        // NoClassDefFoundError 是 Error 不是 Exception —— 实现必须是 catch (Throwable)，
        // 只 catch Exception 的话这里不是变红，而是把异常从每次点击的路径上抛出去
        val hostile = ThrowingContext(NoClassDefFoundError("settings provider gone"))

        assertWithMessage("读不出来就放行，且这一下必须被吃掉，不许抛给调用方")
            .that(systemHapticFeedbackEnabled(hostile))
            .isTrue()
    }

    @Test
    fun `读取抛 Exception 时放行`() {
        // 另一半：SecurityException 这类普通异常同样只能放行
        val hostile = ThrowingContext(SecurityException("settings read denied"))

        assertThat(systemHapticFeedbackEnabled(hostile)).isTrue()
    }

    @Test
    fun `开关翻转后下一次调用立刻跟上`() {
        // KDoc 承诺不缓存：用户在系统设置里改了开关，下一次点击就该生效。
        // 谁给这个函数加个 memo 或者让 AppHaptics 把首次结果记下来，这条就红
        writeSetting(KEY_VIBRATE_ON, ON)
        writeSetting(KEY_HAPTIC_FEEDBACK_ENABLED, ON)
        assertThat(systemHapticFeedbackEnabled(context)).isTrue()

        writeSetting(KEY_VIBRATE_ON, OFF)

        assertWithMessage("同一个 context 上再问一次，必须读到用户刚改的值")
            .that(systemHapticFeedbackEnabled(context))
            .isFalse()
    }

    /** 往沙箱的 `Settings.System` 写一个键。 */
    private fun writeSetting(key: String, value: Int) {
        Settings.System.putInt(context.contentResolver, key, value)
    }

    /** 读键的原始字符串：缺失时返回 null，用来自证「键真的没写入」。 */
    private fun readRawSetting(key: String): String? =
        Settings.System.getString(context.contentResolver, key)

    /**
     * `getContentResolver()` 必抛的 Context。
     *
     * 包一层真的 Application 而不是 `ContextWrapper(null)`：被测函数今天只碰
     * `contentResolver`，但万一以后多碰一样东西，这里该照常委托下去而不是又炸一次，
     * 那样才验得清「炸的是读设置这一步」。
     */
    private class ThrowingContext(
        private val failure: Throwable,
    ) : ContextWrapper(RuntimeEnvironment.getApplication()) {
        override fun getContentResolver(): ContentResolver = throw failure
    }

    private companion object {
        /** 没被废弃，与被测实现读的是同一个键。 */
        val KEY_VIBRATE_ON: String = Settings.System.VIBRATE_ON

        /**
         * android-37 的 SDK 里标了废弃，但公开 SDK 没给替代品，被测实现也是照这个键读的
         * （理由见它的 KDoc），测试跟着抑制一次。
         */
        @Suppress("DEPRECATION")
        val KEY_HAPTIC_FEEDBACK_ENABLED: String = Settings.System.HAPTIC_FEEDBACK_ENABLED

        const val ON = 1

        const val OFF = 0

        /** 这两个键实际只有 0 与 1，摆一个别的取值专门验「非 0 一律当开」。 */
        const val UNEXPECTED_NON_ZERO = 2
    }
}
