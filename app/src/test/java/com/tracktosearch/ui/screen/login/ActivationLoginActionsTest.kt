package com.tracktosearch.ui.screen.login

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.tracktosearch.R
import com.tracktosearch.data.local.TicketStub
import com.tracktosearch.ui.component.GlassScene
import dev.chrisbanes.haze.HazeState
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
                    statusIsError = false,
                    isLoading = false,
                    keypadEnabled = keypadEnabled,
                    submitEnabled = submitEnabled,
                    hazeState = remember { HazeState() },
                    scene = GlassScene(),
                    onDigit = onDigit,
                    onBackspace = {},
                    onPaste = {},
                    onSubmit = onSubmit,
                    codeDescription = codeDescription,
                )
            }
        }
    }

    private fun setTicket(enabled: Boolean = true) {
        composeRule.setContent {
            MaterialTheme {
                CinemaTicket(
                    stub = ticketStub,
                    phase = TICKET_PRINT_FINAL_PHASE,
                    loginState = LoginState.IDLE,
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
    fun `已取票态键盘置灰但不移除`() {
        // 机器不该只剩半截：键盘整块留在面板上，只是不可按
        setMachine(code = "******", keypadEnabled = false, submitEnabled = false)
        composeRule.onNodeWithText("7").assertIsDisplayed()
        composeRule
            .onNodeWithText(context.getString(R.string.machine_submit))
            .assertIsNotEnabled()
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
