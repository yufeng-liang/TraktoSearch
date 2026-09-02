package com.tracktosearch.ui.screen.login

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.tracktosearch.R
import com.tracktosearch.data.local.TicketStub
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ActivationLoginActionsTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    // 20696 是 2026-08-31 的 epoch day。写成字面量而不是 LocalDate.now()：
    // 票面印的是取票当天，用当天算日期断言会跟着运行日期漂移。
    private val ticketStub = TicketStub(
        nickname = "小明",
        issuedEpochDay = 20_696L,
        hall = 2,
        row = 7,
        seat = 12,
    )

    private fun setMachine(
        code: String,
        phase: MachinePhase = MachinePhase.Ready,
        submitEnabled: Boolean = code.length == 6,
        keypadEnabled: Boolean = true,
        codeDescription: String? = null,
        onDigit: (Char) -> Unit = {},
        onSubmit: () -> Unit = {},
    ) {
        composeRule.setContent {
            MaterialTheme {
                TicketMachine(
                    code = code,
                    statusText = "READY",
                    detailText = null,
                    phase = phase,
                    keypadEnabled = keypadEnabled,
                    submitEnabled = submitEnabled,
                    onDigit = onDigit,
                    onBackspace = {},
                    onPaste = {},
                    onSubmit = onSubmit,
                    codeDescription = codeDescription,
                )
            }
        }
    }

    private fun setTicket(
        enabled: Boolean = true,
        loginState: LoginState = LoginState.IDLE,
    ) {
        composeRule.setContent {
            MaterialTheme {
                CinemaTicket(
                    stub = ticketStub,
                    phase = TICKET_PRINT_FINAL_PHASE,
                    loginState = loginState,
                    traktEnabled = enabled,
                    doubanEnabled = enabled,
                    guestEnabled = enabled,
                    onTraktLogin = {},
                    onCancelAuth = {},
                    onDoubanLogin = {},
                    onGuestMode = {},
                )
            }
        }
    }

    @Test
    fun `只有未取票到已取票那一次跳变才播打印动画`() {
        assertThat(shouldPlayTicketPrint(false, true)).isTrue()
        assertThat(shouldPlayTicketPrint(true, true)).isFalse()
        assertThat(shouldPlayTicketPrint(false, false)).isFalse()
    }

    @Test
    fun `取票键在不足六位时禁用`() {
        setMachine(code = "4920")
        composeRule
            .onNodeWithText(context.getString(R.string.machine_submit))
            .assertIsNotEnabled()
    }

    @Test
    fun `取票键在满六位时点亮`() {
        setMachine(code = "492013")
        composeRule
            .onNodeWithText(context.getString(R.string.machine_submit))
            .assertIsEnabled()
    }

    @Test
    fun `按数字键把该位喂回调用方`() {
        val pressed = mutableListOf<Char>()
        setMachine(code = "", onDigit = { pressed += it })
        composeRule.onNodeWithText("7").performClick()
        assertThat(pressed).containsExactly('7')
    }

    @Test
    fun `已取票态键盘淡成残影但仍占着面板`() {
        // 机器不该只剩半截：键盘整块留在面板上，只是不再是控件。
        // 淡出后它对读屏是纯噪音（念一遍「1 2 3 4…」没有任何用），所以整块被
        // clearAndSetSemantics 摘掉 —— 这条验的就是「摘掉了，但没删掉」
        setMachine(code = "******", keypadEnabled = false, submitEnabled = false)
        composeRule.onNodeWithTag(MACHINE_KEYPAD_TAG).assertExists()
        composeRule.onAllNodesWithText("7").assertCountEquals(0)
        composeRule
            .onNodeWithText(context.getString(R.string.machine_submit))
            .assertIsNotEnabled()
    }

    @Test
    fun `输入态键盘每个键都在读屏树里`() {
        // 自绘键盘拿不到系统输入法白送的播报，可用状态下 12 个键必须自己可寻址
        setMachine(code = "")
        for (digit in "0123456789") {
            composeRule.onNodeWithText(digit.toString()).assertExists()
        }
        composeRule
            .onNodeWithContentDescription(
                context.getString(R.string.machine_key_backspace_desc)
            )
            .assertExists()
    }

    @Test
    fun `输入途中六格整体播报已输入的位与还差几位`() {
        // 自绘键盘没有系统输入法白送的播报，六格合成一个语义节点，这是唯一的播报来源
        setMachine(code = "49")
        composeRule
            .onNodeWithContentDescription(
                context.getString(R.string.machine_code_progress, "4 9", 4)
            )
            .assertExists()
    }

    @Test
    fun `已取票态六格不把占位星号念给读屏`() {
        // 屏上那串 ****** 是占位符不是脱敏后的真码，念「取票码 * * * * * *，还需 0 位」
        // 既不是实话也没有信息，此时该由调用方给一句「已使用」
        setMachine(
            code = "******",
            keypadEnabled = false,
            submitEnabled = false,
            codeDescription = context.getString(R.string.machine_code_collected),
        )
        composeRule
            .onNodeWithContentDescription(context.getString(R.string.machine_code_collected))
            .assertExists()
        composeRule
            .onAllNodesWithContentDescription(
                context.getString(R.string.machine_code_progress, "* * * * * *", 0)
            )
            .assertCountEquals(0)
    }

    @Test
    fun `票上三个入口自上而下是 Trakt 豆瓣 访客`() {
        setTicket()
        val traktTop = composeRule
            .onNodeWithText(context.getString(R.string.ticket_entry_trakt))
            .assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot.top
        val doubanTop = composeRule
            .onNodeWithText(context.getString(R.string.ticket_entry_douban))
            .assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot.top
        val guestTop = composeRule
            .onNodeWithText(context.getString(R.string.ticket_entry_guest))
            .assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot.top

        assertThat(traktTop).isLessThan(doubanTop)
        assertThat(doubanTop).isLessThan(guestTop)
    }

    @Test
    fun `票面印出昵称与取票当天日期`() {
        setTicket()
        composeRule.onNodeWithText("小明").assertIsDisplayed()
        composeRule.onNodeWithText("2026-08-31").assertIsDisplayed()
    }

    @Test
    fun `票号印在条码下面且与条码同源`() {
        // 020712 = 2 号厅 7 排 12 座，跟 ticketBarcodeWidths 展开的是同一个串
        setTicket()
        composeRule
            .onNodeWithText(ticketSerial(ticketStub), useUnmergedTree = true)
            .assertIsDisplayed()
    }

    @Test
    fun `条码留在撕口线以上的票根那半截`() {
        // 真票撕开后带走的是带条码的这一截。条码掉到入口行下面就不是票根了，
        // 而这件事在装机截图上要盯着虚线看才看得出来
        setTicket()
        val serialBottom = composeRule
            .onNodeWithText(ticketSerial(ticketStub), useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot.bottom
        val traktTop = composeRule
            .onNodeWithText(context.getString(R.string.ticket_entry_trakt))
            .fetchSemanticsNode().boundsInRoot.top

        assertThat(serialBottom).isLessThan(traktTop)
    }

    @Test
    fun `授权中的提示和取消跟 Trakt 挤在同一行`() {
        // 取 unmerged 树：整行是 clickable，合并语义之后行文案和行尾那两样会塌成同一个节点，
        // 量出来的永远是整行的框，「在不在同一行」也就无从判断
        setTicket(loginState = LoginState.AUTHORIZING)
        val label = composeRule
            .onNodeWithText(context.getString(R.string.ticket_entry_trakt), useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val hint = composeRule
            .onNodeWithText(
                context.getString(R.string.ticket_entry_authorizing),
                useUnmergedTree = true
            )
            .fetchSemanticsNode().boundsInRoot
        val cancel = composeRule
            .onNodeWithText(context.getString(R.string.common_cancel), useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot

        for ((name, bounds) in listOf("提示" to hint, "取消" to cancel)) {
            // 竖向有重叠就是同一行；早先这两样印在票面下面，重叠为零
            assertWithMessage("$name 与行文案竖向不重叠，不在同一行")
                .that(bounds.top).isLessThan(label.bottom)
            assertWithMessage("$name 与行文案竖向不重叠，不在同一行")
                .that(bounds.bottom).isGreaterThan(label.top)
            assertWithMessage("$name 没排在行文案右边")
                .that(bounds.left).isAtLeast(label.right)
        }
    }

    @Test
    fun `验码期间取票键换成进度圈`() {
        // 跑马灯在这一档是无限流水，autoAdvance 会一直等它结束。这里只看首帧的静态结果
        composeRule.mainClock.autoAdvance = false
        setMachine(code = "492013", phase = MachinePhase.Verifying)
        // 键面文字还在就说明这一档没接进去
        composeRule.onAllNodesWithText(context.getString(R.string.machine_submit))
            .assertCountEquals(0)
    }

    @Test
    fun `每个激活错误码都有对应的像素屏短状态`() {
        // 像素屏第一行的短状态与第二行的完整文案是两套映射，任一边漏掉一个错误码，
        // 用户就会看到「取票失败」这类兜底文案，丢掉「下一步该干什么」。
        val errorCodes = listOf(
            "MIGRATION_DEVICE_NOT_FOUND",
            "MIGRATION_DEVICE_MISMATCH",
            "DEVICE_ALREADY_BOUND",
            "INVITE_BOUND",
            "DEVICE_LIMIT_REACHED",
            "INVITE_ALREADY_USED",
            "INVITE_REVOKED",
            "INVITE_EXPIRED",
            "FRIEND_DISABLED",
            "INVALID_INVITE",
            "RATE_LIMITED",
            "timeout",
            "Unable to resolve host",
            "Failed to connect",
            "Network is unreachable",
        )
        for (code in errorCodes) {
            assertThat(machineStatusString(code)).isNotEqualTo(R.string.machine_status_failed)
            assertThat(authErrorString(code)).isNotEqualTo(R.string.auth_error_generic)
        }
        assertThat(machineStatusString("SOMETHING_ELSE"))
            .isEqualTo(R.string.machine_status_failed)
        assertThat(authErrorString("SOMETHING_ELSE"))
            .isEqualTo(R.string.auth_error_generic)
    }
}
