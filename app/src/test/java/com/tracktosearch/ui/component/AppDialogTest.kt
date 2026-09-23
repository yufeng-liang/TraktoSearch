package com.tracktosearch.ui.component

import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.sp
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.tracktosearch.ui.theme.Typography
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

    // ==================== AppAlertDialog：槽位编排与内容限高 ====================

    @Test
    fun `title 与 message 渲染，按钮回调接通`() {
        var confirmed = 0
        composeRule.setContent {
            MaterialTheme {
                AppAlertDialog(
                    onDismissRequest = {},
                    title = "Head",
                    message = "Body",
                    confirm = DialogAction("Confirm", onClick = { confirmed++ }),
                    dismiss = DialogAction("Cancel", onClick = {}),
                )
            }
        }
        composeRule.onNodeWithText("Head").assertIsDisplayed()
        composeRule.onNodeWithText("Body").assertIsDisplayed()
        composeRule.onNodeWithText("Cancel").performClick()
        composeRule.onNodeWithText("Confirm").performClick()
        assertThat(confirmed).isEqualTo(1)
    }

    @Test
    fun `supportMessage 渲染为副句，且不与主句同节点`() {
        composeRule.setContent {
            MaterialTheme {
                AppAlertDialog(
                    onDismissRequest = {},
                    title = "Head",
                    message = "Main line",
                    supportMessage = "Sub line",
                    confirm = DialogAction("Confirm", onClick = {}),
                )
            }
        }
        // 两条都按「精确整串」匹配：主副句若被并进同一个 Text，节点值是
        // "Main line\nSub line"，这两句会同时找不到——所以这里不需要额外的断言
        // 来证明它们不在同一个节点上。
        composeRule.onNodeWithText("Main line").assertIsDisplayed()
        composeRule.onNodeWithText("Sub line").assertIsDisplayed()
    }

    @Test
    fun `title + supportMessage 即可组出两行标题，不需要整槽自绘`() {
        // 原 titleContent 槽唯一的用法就是这个两行结构，而 discover 板块设置弹窗改走
        // 本配方后不再自绘。副句必须独立成节点：并进主句会让字号字重重新脱离组件管控。
        composeRule.setContent {
            MaterialTheme {
                AppAlertDialog(
                    onDismissRequest = {},
                    title = "Head",
                    supportMessage = "Sub line",
                    confirm = DialogAction("Confirm", onClick = {}),
                )
            }
        }
        composeRule.onNodeWithText("Head").assertIsDisplayed()
        composeRule.onNodeWithText("Sub line").assertIsDisplayed()
    }

    @Test
    fun `content 槽可滚动，用于承载超长内容`() {
        composeRule.setContent {
            MaterialTheme {
                AppAlertDialog(
                    onDismissRequest = {},
                    title = "Head",
                    confirm = DialogAction("Confirm", onClick = {}),
                    content = { Text("Long content") },
                )
            }
        }
        // 限高本身不在单测断言（Robolectric 密度会让像素断言不稳），由真机截图核验；
        // 这里只锁「内容槽一定带滚动能力」这一条契约。
        composeRule.onNodeWithTag(DialogContentTag).assert(hasScrollAction())
    }

    @Test
    fun `没有按钮时不渲染按钮行，仍渲染标题`() {
        composeRule.setContent {
            MaterialTheme {
                AppAlertDialog(onDismissRequest = {}, title = "Only head")
            }
        }
        composeRule.onNodeWithText("Only head").assertIsDisplayed()
        composeRule.onAllNodes(hasClickAction()).assertCountEquals(0)
    }

    // ==================== AppFloatingDialog：裸 Dialog 外壳 ====================

    @Test
    fun `AppFloatingDialog 渲染标题、内容与按钮行`() {
        var confirmed = 0
        composeRule.setContent {
            MaterialTheme {
                AppFloatingDialog(
                    onDismissRequest = {},
                    title = "Floating head",
                    confirm = DialogAction("Confirm", onClick = { confirmed++ }),
                    dismiss = DialogAction("Cancel", onClick = {}),
                ) {
                    Text("Floating body")
                }
            }
        }
        composeRule.onNodeWithText("Floating head").assertIsDisplayed()
        composeRule.onNodeWithText("Floating body").assertIsDisplayed()
        composeRule.onNodeWithText("Confirm").performClick()
        assertThat(confirmed).isEqualTo(1)
    }

    @Test
    fun `AppFloatingDialog 无 title 与无按钮时只渲染内容`() {
        composeRule.setContent {
            MaterialTheme {
                AppFloatingDialog(onDismissRequest = {}) { Text("Body only") }
            }
        }
        composeRule.onNodeWithText("Body only").assertIsDisplayed()
        composeRule.onAllNodes(hasClickAction()).assertCountEquals(0)
    }

    // ==================== AppBottomSheet：弹层标题栏与关闭按钮 ====================

    @Test
    fun `AppBottomSheet 带标题时渲染统一标题栏与关闭按钮`() {
        var dismissed = 0
        composeRule.setContent {
            MaterialTheme {
                AppBottomSheet(onDismissRequest = { dismissed++ }, title = "Sheet head") {
                    Text("Sheet body")
                }
            }
        }
        composeRule.onNodeWithText("Sheet head").assertIsDisplayed()
        composeRule.onNodeWithText("Sheet body").assertIsDisplayed()
        // 关闭按钮是 Icon(contentDescription)，不是 Text 节点，onNodeWithText 匹配不到
        composeRule.onNodeWithContentDescription("Close").performClick()
        assertThat(dismissed).isEqualTo(1)
    }

    @Test
    fun `AppBottomSheet 无标题时不渲染标题栏`() {
        composeRule.setContent {
            MaterialTheme {
                AppBottomSheet(onDismissRequest = {}) { Text("Plain body") }
            }
        }
        composeRule.onNodeWithText("Plain body").assertIsDisplayed()
        // 标题栏若误渲染，IconButton 合并出的节点会同时带点击与 contentDescription=Close，
        // 按 contentDescription 断言才有鉴别力（按 Text 断是恒真的假绿）
        composeRule.onAllNodes(hasClickAction() and hasContentDescription("Close"))
            .assertCountEquals(0)
    }

    // ==================== 标题两档：纯函数，不依赖组合 ====================
    //
    // 语义树不暴露字号，组合侧断不了，所以断这两个纯函数（与 dialogButtonColors 同理）。
    // 锁的是绝对值而不是「等于 Typography.titleLarge」：后者是恒真式，上游哪天调了
    // titleLarge 它也照样绿，而 18/16 这两档正是这次统一要守住的数。

    @Test
    fun `对话框标题档为 18sp Bold`() {
        val style = dialogTitleStyle(Typography)
        assertWithMessage("对话框/浮动卡片标题字号").that(style.fontSize).isEqualTo(18.sp)
        assertWithMessage("对话框/浮动卡片标题字重").that(style.fontWeight).isEqualTo(FontWeight.Bold)
    }

    @Test
    fun `面板标题档为 16sp Bold`() {
        val style = sheetTitleStyle(Typography)
        assertWithMessage("底部面板标题字号").that(style.fontSize).isEqualTo(16.sp)
        assertWithMessage("底部面板标题字重").that(style.fontWeight).isEqualTo(FontWeight.Bold)
    }

    @Test
    fun `两档标题字号不同，确保没有并成一档`() {
        // 并档是这次统一最容易发生的退化：两个函数都还「对」，但面板标题在窄屏会挤到
        // maxLines=1 截断。字号相等即红。
        assertWithMessage("对话框档与面板档必须不同字号")
            .that(dialogTitleStyle(Typography).fontSize)
            .isNotEqualTo(sheetTitleStyle(Typography).fontSize)
    }
}
