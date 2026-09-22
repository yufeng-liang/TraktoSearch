package com.tracktosearch.ui.component

import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.tracktosearch.ui.theme.ThemeTestSupport
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
// plain Application 是硬要求：真实 Application 会初始化 SQLCipher Room，JVM 侧没有 so，
// 整个测试类会在 @Before 阶段全红且原因毫无关系。仓库既有约定同此。
@Config(sdk = [33], application = android.app.Application::class)
class AppDialogTest {

    @get:Rule
    val composeRule = createComposeRule()

    // ==================== dialogButtonColors：纯函数，不依赖组合 ====================

    @Test
    fun `Primary tone 在所有内置色调下都取 primary`() {
        for (case in ThemeTestSupport.allSchemes) {
            val colors = dialogButtonColors(DialogTone.Primary, case.scheme)
            assertWithMessage(case.label).that(colors.containerColor).isEqualTo(case.scheme.primary)
            assertWithMessage(case.label).that(colors.contentColor).isEqualTo(case.scheme.onPrimary)
            assertDisabledColorsMatchDesign(case.label, colors, case.scheme)
        }
    }

    @Test
    fun `Destructive tone 在所有内置色调下都取 error`() {
        for (case in ThemeTestSupport.allSchemes) {
            val colors = dialogButtonColors(DialogTone.Destructive, case.scheme)
            assertWithMessage(case.label).that(colors.containerColor).isEqualTo(case.scheme.error)
            assertWithMessage(case.label).that(colors.contentColor).isEqualTo(case.scheme.onError)
            assertDisabledColorsMatchDesign(case.label, colors, case.scheme)
        }
    }

    // 两档 disabled 配色是设计定稿（Global Constraints），两种 tone 共用同一组值：
    // 回归了肉眼几乎看不出来（都是灰），所以逐色调锁死。
    private fun assertDisabledColorsMatchDesign(
        label: String,
        colors: ButtonColors,
        scheme: ColorScheme,
    ) {
        assertWithMessage(label).that(colors.disabledContainerColor)
            .isEqualTo(scheme.onSurface.copy(alpha = 0.12f))
        assertWithMessage(label).that(colors.disabledContentColor)
            .isEqualTo(scheme.onSurface.copy(alpha = 0.38f))
    }

    @Test
    fun `两种 tone 的容器色不相同，确保破坏性有独立表达`() {
        // 遍历全部色调而不是只取 first()：风险最高的 VINTAGE_TICKET 是手写 scheme，
        // 排在遍历末尾，单取一档正好漏掉它。
        for (case in ThemeTestSupport.allSchemes) {
            assertWithMessage(case.label)
                .that(dialogButtonColors(DialogTone.Primary, case.scheme).containerColor)
                .isNotEqualTo(dialogButtonColors(DialogTone.Destructive, case.scheme).containerColor)
        }
    }

    // ==================== AppDialogActionRow：渲染与点击归属 ====================

    private fun showRow(primary: DialogAction?, secondary: List<DialogAction> = emptyList()) {
        composeRule.setContent {
            MaterialTheme { AppDialogActionRow(primary = primary, secondary = secondary) }
        }
        composeRule.waitForIdle()
    }

    // DialogAction 的 onClick 在第 2 位（末位是 tone），尾部 lambda 语法绑不到它，
    // 所以本文件的构造点一律显式写 onClick=。
    private fun action(label: String, onClick: () -> Unit = {}) =
        DialogAction(label, onClick = onClick)

    @Test
    fun `主按钮和次按钮各自渲染`() {
        showRow(
            primary = action("Confirm"),
            secondary = listOf(action("Cancel")),
        )
        composeRule.onNodeWithText("Confirm").assertIsDisplayed()
        composeRule.onNodeWithText("Cancel").assertIsDisplayed()
    }

    @Test
    fun `primary 为空时不渲染填充按钮，次按钮仍渲染`() {
        showRow(primary = null, secondary = listOf(action("Done")))
        composeRule.onNodeWithText("Done").assertIsDisplayed()
        // 断「可点击节点总数 = 1」，不断「不存在 label 为 Confirm 的节点」：本用例只传了 Done，
        // 树里本来就没有 Confirm，那句不管生产代码怎么写都是绿的。总数才真正压住
        // AppDialogActionRow 里 `if (primary != null)` 那道守卫。
        composeRule.onAllNodes(hasClickAction()).assertCountEquals(1)
    }

    @Test
    fun `点击次按钮只触发次按钮回调`() {
        var primaryCalls = 0
        var secondaryCalls = 0
        showRow(
            primary = DialogAction("Confirm", onClick = { primaryCalls++ }),
            secondary = listOf(DialogAction("Cancel", onClick = { secondaryCalls++ })),
        )
        composeRule.onNodeWithText("Cancel").performClick()
        assertThat(primaryCalls).isEqualTo(0)
        assertThat(secondaryCalls).isEqualTo(1)
    }

    @Test
    fun `点击主按钮只触发主按钮回调`() {
        var primaryCalls = 0
        var secondaryCalls = 0
        showRow(
            primary = DialogAction("Confirm", onClick = { primaryCalls++ }),
            secondary = listOf(DialogAction("Cancel", onClick = { secondaryCalls++ })),
        )
        composeRule.onNodeWithText("Confirm").performClick()
        assertThat(primaryCalls).isEqualTo(1)
        assertThat(secondaryCalls).isEqualTo(0)
    }

    @Test
    fun `enabled 为 false 时按钮语义为不可用且点击无效`() {
        var calls = 0
        showRow(primary = DialogAction("Confirm", { calls++ }, enabled = false))
        composeRule.onNodeWithText("Confirm").assertIsNotEnabled()
        composeRule.onNodeWithText("Confirm").performClick()
        assertThat(calls).isEqualTo(0)
    }
}
