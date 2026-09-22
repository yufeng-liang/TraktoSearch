package com.tracktosearch.ui.component

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
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
        }
    }

    @Test
    fun `Destructive tone 在所有内置色调下都取 error`() {
        for (case in ThemeTestSupport.allSchemes) {
            val colors = dialogButtonColors(DialogTone.Destructive, case.scheme)
            assertWithMessage(case.label).that(colors.containerColor).isEqualTo(case.scheme.error)
            assertWithMessage(case.label).that(colors.contentColor).isEqualTo(case.scheme.onError)
        }
    }

    @Test
    fun `两种 tone 的容器色不相同，确保破坏性有独立表达`() {
        val scheme = ThemeTestSupport.allSchemes.first().scheme
        assertThat(dialogButtonColors(DialogTone.Primary, scheme).containerColor)
            .isNotEqualTo(dialogButtonColors(DialogTone.Destructive, scheme).containerColor)
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
        composeRule.onNode(hasClickAction() and hasText("Confirm")).assertDoesNotExist()
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
