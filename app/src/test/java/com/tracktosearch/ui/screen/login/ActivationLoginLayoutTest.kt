package com.tracktosearch.ui.screen.login

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.tracktosearch.R
import com.tracktosearch.data.local.TicketStub
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** 测试自己套的定尺容器。用它的下沿当「一屏」的底边，省掉 dp 到 px 的换算。 */
private const val VIEWPORT_TAG = "activation_login_viewport"

/**
 * 激活页「一屏装得下」的回归测试。
 *
 * 取票机把像素屏、六格、12 个键、取票键和出票口串成一列，任一处高度往上长一点，
 * 取票键就被顶到折线以下 —— 用户进来第一眼只看到键盘，不滚一下不知道输完码按哪里。
 * 这里不比像素，只守三条：基准机上输入态整屏不滚动、出票态整屏也不滚动、小屏上取票键仍落在首屏内。
 *
 * 两个用例各自用 @Config 把 Robolectric 的窗口调成目标机型，让窗口与容器同尺寸：
 * assertIsDisplayed 判的是节点有没有落在窗口和滚动视口里，窗口比容器矮的话，
 * 容器下半截的东西一律算不可见，断言就失去意义。
 *
 * 两处口径需要知道：Robolectric 报的系统栏 inset 是 0，量出来的是满 915dp / 780dp 的
 * 可用高度，真机上还要扣掉状态栏和导航栏，所以这条线是底线而不是余量；
 * 锁 zh 是因为首屏高度预算按中文文案算过（像素屏第二行、票面三行入口都是中文一行），
 * 默认 en 量的是另一套排版，失败也怪不到这次改版头上。
 */
@RunWith(AndroidJUnit4::class)
class ActivationLoginLayoutTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    @Config(qualifiers = "zh-w412dp-h915dp")
    fun `基准机上整屏不需要滚动`() {
        val scrollState = ScrollState(0)
        setActivationContent(scrollState, width = 412.dp, height = 915.dp)

        // maxValue 测量前是 Int.MAX_VALUE，等于 0 才同时说明「量过了」和「没溢出」，
        // 不会因为根本没测量就误判通过；真溢出时它打印的就是超出的像素数
        composeRule.runOnIdle { assertThat(scrollState.maxValue).isEqualTo(0) }
    }

    @Test
    @Config(qualifiers = "zh-w360dp-h780dp")
    fun `小屏上取票键不用滚动就能按到`() {
        val scrollState = ScrollState(0)
        setActivationContent(scrollState, width = 360.dp, height = 780.dp)

        // 小屏允许滚动，但主操作不能藏在折线以下。
        // 键面用点阵字体绘制，语义树里存的仍是原始字符串，按文案找节点不受字体影响
        val submitBottom = composeRule
            .onNodeWithText(context.getString(R.string.machine_submit))
            .assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot.bottom
        val viewportBottom = composeRule
            .onNodeWithTag(VIEWPORT_TAG)
            .fetchSemanticsNode().boundsInRoot.bottom

        assertThat(submitBottom).isAtMost(viewportBottom)
    }

    @Test
    @Config(qualifiers = "zh-w412dp-h915dp")
    fun `出票之后基准机上整屏也不需要滚动`() {
        // 出票态才是最高的一屏：机器下面多挂一张 178dp 的票。上面那条用例看不到它 ——
        // ticketSlot 默认是空的，票高涨一点、机器高涨一点都不会让它变红
        val scrollState = ScrollState(0)
        setActivationContent(scrollState, width = 412.dp, height = 915.dp, printed = true)

        composeRule.runOnIdle { assertThat(scrollState.maxValue).isEqualTo(0) }
    }

    /** 未激活、六格为空的输入态：用户刚进页面看到的就是这一屏。 */
    private fun setActivationContent(
        scrollState: ScrollState,
        width: Dp,
        height: Dp,
        printed: Boolean = false,
    ) {
        val status = context.getString(R.string.machine_status_ready)
        val detail = context.getString(R.string.login_activation_locked)
        composeRule.setContent {
            MaterialTheme {
                Box(
                    modifier = Modifier
                        .requiredSize(width = width, height = height)
                        .testTag(VIEWPORT_TAG)
                ) {
                    ActivationLoginContent(
                        machineCode = "",
                        machineStatus = status,
                        machineDetail = detail,
                        machinePhase = MachinePhase.Ready,
                        keypadEnabled = true,
                        submitEnabled = false,
                        expiredMessage = null,
                        loginErrorText = null,
                        codeDescription = null,
                        scrollState = scrollState,
                        onDigit = {},
                        onBackspace = {},
                        onPaste = {},
                        onSubmit = {},
                        onWhatIsTrakt = {},
                        ticketSlot = {
                            if (printed) {
                                CinemaTicket(
                                    stub = TicketStub(
                                        nickname = "小明",
                                        issuedEpochDay = 20_696L,
                                        hall = 2,
                                        row = 7,
                                        seat = 12,
                                    ),
                                    phase = TICKET_PRINT_FINAL_PHASE,
                                    loginState = LoginState.IDLE,
                                    doubanBusy = false,
                                    traktEnabled = true,
                                    doubanEnabled = true,
                                    guestEnabled = true,
                                    onTraktLogin = {},
                                    onDoubanLogin = {},
                                    onGuestMode = {},
                                )
                            }
                        },
                    )
                }
            }
        }
        composeRule.waitForIdle()
    }
}
