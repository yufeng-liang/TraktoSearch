package com.tracktosearch.ui.screen.auth

import com.google.common.truth.Truth.assertThat
import com.tracktosearch.test.MainDispatcherRule
import com.tracktosearch.data.auth.ActivateResponse
import com.tracktosearch.data.auth.AuthCheckScheduler
import com.tracktosearch.data.auth.AuthManager
import com.tracktosearch.data.auth.AuthState
import com.tracktosearch.data.local.TicketStubStorage
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Before
import org.junit.Test
import org.junit.Rule
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class AuthViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val authManager = mockk<AuthManager>()
    private val authCheckScheduler = mockk<AuthCheckScheduler>(relaxed = true)
    private val ticketStubStorage = mockk<TicketStubStorage>(relaxed = true)

    @Before
    fun setUp() {
        every { authManager.recoveryFailure } returns MutableStateFlow(null)
        every { authManager.nickname } returns MutableStateFlow(null)
        every { authManager.getNextCheckAt() } returns 0L
        // 冷启动读回票根：默认无票，避免落盘发射覆盖用例内新出的票
        every { ticketStubStorage.stub } returns MutableStateFlow(null)
    }

    @Test
    fun `authorized gateway state starts as activated`() = runTest {
        every { authManager.authState } returns MutableStateFlow(AuthState.AUTHORIZED)

        val viewModel = AuthViewModel(authManager, authCheckScheduler, ticketStubStorage)

        assertThat(viewModel.uiState.value.activated).isTrue()
    }

    @Test
    fun `gateway invalidation locks platform and guest entries`() = runTest {
        val authState = MutableStateFlow(AuthState.AUTHORIZED)
        every { authManager.authState } returns authState
        val viewModel = AuthViewModel(authManager, authCheckScheduler, ticketStubStorage)

        assertThat(viewModel.uiState.value.activated).isTrue()

        authState.value = AuthState.EXPIRED
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.activated).isFalse()
    }

    @Test
    fun `updating invite code clears previous error`() = runTest {
        every { authManager.authState } returns MutableStateFlow(AuthState.UNAUTHORIZED)
        val viewModel = AuthViewModel(authManager, authCheckScheduler, ticketStubStorage)

        viewModel.updateInviteCode("")
        viewModel.activate()
        viewModel.updateInviteCode("TS-1234567890")

        assertThat(viewModel.uiState.value.error).isNull()
    }

    @Test
    fun `successful activation unlocks the login page`() = runTest {
        val authState = MutableStateFlow(AuthState.UNAUTHORIZED)
        every { authManager.authState } returns authState
        coEvery { authManager.activate(any(), any(), any(), any()) } answers {
            authState.value = AuthState.AUTHORIZED
            Result.success(activateResponse())
        }
        val viewModel = AuthViewModel(authManager, authCheckScheduler, ticketStubStorage)

        viewModel.updateInviteCode(" TS-1234567890 ")
        viewModel.activate()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.activated).isTrue()
        assertThat(viewModel.uiState.value.isLoading).isFalse()
        assertThat(viewModel.uiState.value.error).isNull()
    }

    @Test
    fun `activated state ignores repeated activation`() = runTest {
        val authState = MutableStateFlow(AuthState.UNAUTHORIZED)
        every { authManager.authState } returns authState
        coEvery { authManager.activate(any(), any(), any(), any()) } answers {
            authState.value = AuthState.AUTHORIZED
            Result.success(activateResponse())
        }
        val viewModel = AuthViewModel(authManager, authCheckScheduler, ticketStubStorage)

        viewModel.updateInviteCode("TS-1234567890")
        viewModel.activate()
        advanceUntilIdle()
        viewModel.activate()
        advanceUntilIdle()

        coVerify(exactly = 1) { authManager.activate(any(), any(), any(), any()) }
        assertThat(viewModel.uiState.value.activated).isTrue()
    }

    @Test
    fun `failed activation keeps login page locked`() = runTest {
        every { authManager.authState } returns MutableStateFlow(AuthState.UNAUTHORIZED)
        coEvery { authManager.activate(any(), any(), any(), any()) } returns Result.failure(Exception("INVALID_INVITE"))
        val viewModel = AuthViewModel(authManager, authCheckScheduler, ticketStubStorage)

        viewModel.updateInviteCode("TS-1234567890")
        viewModel.activate()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.activated).isFalse()
        assertThat(viewModel.uiState.value.error).isEqualTo("INVALID_INVITE")
    }

    @Test
    fun `ticket code input keeps only the first six digits`() = runTest {
        every { authManager.authState } returns MutableStateFlow(AuthState.UNAUTHORIZED)
        val viewModel = AuthViewModel(authManager, authCheckScheduler, ticketStubStorage)

        viewModel.updateInviteCode("12a34b56789")

        assertThat(viewModel.uiState.value.inviteCode).isEqualTo("123456")
    }

    @Test
    fun `ticket code input drops spaces between digits`() = runTest {
        every { authManager.authState } returns MutableStateFlow(AuthState.UNAUTHORIZED)
        val viewModel = AuthViewModel(authManager, authCheckScheduler, ticketStubStorage)

        viewModel.updateInviteCode("  49 20 13  ")

        assertThat(viewModel.uiState.value.inviteCode).isEqualTo("492013")
    }

    @Test
    fun `pasting a sentence extracts the six digit ticket code`() = runTest {
        every { authManager.authState } returns MutableStateFlow(AuthState.UNAUTHORIZED)
        val viewModel = AuthViewModel(authManager, authCheckScheduler, ticketStubStorage)

        val pasted = viewModel.pasteTicketCode("你的取票码是 492013，请尽快使用")

        assertThat(pasted).isTrue()
        assertThat(viewModel.uiState.value.inviteCode).isEqualTo("492013")
    }

    @Test
    fun `paste without digits keeps what the user already typed`() = runTest {
        every { authManager.authState } returns MutableStateFlow(AuthState.UNAUTHORIZED)
        val viewModel = AuthViewModel(authManager, authCheckScheduler, ticketStubStorage)

        viewModel.updateInviteCode("49")
        val pasted = viewModel.pasteTicketCode("没有数字")

        assertThat(pasted).isFalse()
        assertThat(viewModel.uiState.value.inviteCode).isEqualTo("49")
    }

    @Test
    fun `paste rejects fewer than six digits`() = runTest {
        every { authManager.authState } returns MutableStateFlow(AuthState.UNAUTHORIZED)
        val viewModel = AuthViewModel(authManager, authCheckScheduler, ticketStubStorage)

        assertThat(viewModel.pasteTicketCode("12345")).isFalse()
    }

    @Test
    fun `successful activation issues and persists a ticket stub`() = runTest {
        val authState = MutableStateFlow(AuthState.UNAUTHORIZED)
        every { authManager.authState } returns authState
        coEvery { authManager.activate(any(), any(), any(), any()) } answers {
            authState.value = AuthState.AUTHORIZED
            Result.success(activateResponse(nickname = "阿良"))
        }
        val viewModel = AuthViewModel(authManager, authCheckScheduler, ticketStubStorage)

        viewModel.updateInviteCode("492013")
        viewModel.activate()
        advanceUntilIdle()

        val ticket = viewModel.uiState.value.ticket
        assertThat(ticket).isNotNull()
        assertThat(ticket!!.nickname).isEqualTo("阿良")
        assertThat(ticket.issuedEpochDay).isEqualTo(LocalDate.now().toEpochDay())
        coVerify { ticketStubStorage.save(ticket) }
    }

    @Test
    fun `clearing the ticket code keeps the failure message on screen`() = runTest {
        every { authManager.authState } returns MutableStateFlow(AuthState.UNAUTHORIZED)
        coEvery { authManager.activate(any(), any(), any(), any()) } returns Result.failure(Exception("INVALID_INVITE"))
        val viewModel = AuthViewModel(authManager, authCheckScheduler, ticketStubStorage)

        viewModel.updateInviteCode("492013")
        viewModel.activate()
        advanceUntilIdle()

        viewModel.clearTicketCode()

        assertThat(viewModel.uiState.value.inviteCode).isEmpty()
        assertThat(viewModel.uiState.value.error).isEqualTo("INVALID_INVITE")
    }

    @Test
    fun `ticket code input rejects full width and arabic indic digits`() = runTest {
        every { authManager.authState } returns MutableStateFlow(AuthState.UNAUTHORIZED)
        val viewModel = AuthViewModel(authManager, authCheckScheduler, ticketStubStorage)

        // Char.isDigit() 会把这些当数字放进来，凑够 6 位后 activate() 的长度校验也会放行，
        // 结果是发出一串服务端必然拒绝的码，票面座位还静默退回 1 厅 1 排 1 座
        viewModel.updateInviteCode("４９２０１３")
        assertThat(viewModel.uiState.value.inviteCode).isEmpty()

        viewModel.updateInviteCode("٤٩٢٠١٣")
        assertThat(viewModel.uiState.value.inviteCode).isEmpty()

        viewModel.updateInviteCode("４9２0１3")
        assertThat(viewModel.uiState.value.inviteCode).isEqualTo("903")
    }

    @Test
    fun `appending digits reads the current code instead of a stale snapshot`() = runTest {
        every { authManager.authState } returns MutableStateFlow(AuthState.UNAUTHORIZED)
        val viewModel = AuthViewModel(authManager, authCheckScheduler, ticketStubStorage)

        // 连续追加之间没有任何挂起点，模拟同一帧内的两次按键：屏幕上的 inviteCode
        // 此刻还是旧值，追加逻辑必须读 ViewModel 自己的当前值，否则后一位会覆盖前一位
        "492013".forEach { viewModel.appendDigit(it) }

        assertThat(viewModel.uiState.value.inviteCode).isEqualTo("492013")
    }

    @Test
    fun `appending stops at six digits`() = runTest {
        every { authManager.authState } returns MutableStateFlow(AuthState.UNAUTHORIZED)
        val viewModel = AuthViewModel(authManager, authCheckScheduler, ticketStubStorage)

        "4920137".forEach { viewModel.appendDigit(it) }

        assertThat(viewModel.uiState.value.inviteCode).isEqualTo("492013")
    }

    @Test
    fun `appending a digit clears the previous failure message`() = runTest {
        every { authManager.authState } returns MutableStateFlow(AuthState.UNAUTHORIZED)
        coEvery { authManager.activate(any(), any(), any(), any()) } returns
            Result.failure(Exception("INVALID_INVITE"))
        val viewModel = AuthViewModel(authManager, authCheckScheduler, ticketStubStorage)

        viewModel.updateInviteCode("492013")
        viewModel.activate()
        advanceUntilIdle()
        viewModel.clearTicketCode()

        viewModel.appendDigit('4')

        assertThat(viewModel.uiState.value.inviteCode).isEqualTo("4")
        assertThat(viewModel.uiState.value.error).isNull()
    }

    @Test
    fun `deleting drops one digit at a time and no-ops when empty`() = runTest {
        every { authManager.authState } returns MutableStateFlow(AuthState.UNAUTHORIZED)
        val viewModel = AuthViewModel(authManager, authCheckScheduler, ticketStubStorage)

        viewModel.updateInviteCode("492")
        viewModel.deleteLastDigit()
        assertThat(viewModel.uiState.value.inviteCode).isEqualTo("49")

        viewModel.deleteLastDigit()
        viewModel.deleteLastDigit()
        assertThat(viewModel.uiState.value.inviteCode).isEmpty()

        // 空串上再退格是无操作，不该反过来把错误文案顺手清掉
        viewModel.deleteLastDigit()
        assertThat(viewModel.uiState.value.inviteCode).isEmpty()
    }

    @Test
    fun `backspace on an empty code keeps the failure message on screen`() = runTest {
        every { authManager.authState } returns MutableStateFlow(AuthState.UNAUTHORIZED)
        coEvery { authManager.activate(any(), any(), any(), any()) } returns
            Result.failure(Exception("INVALID_INVITE"))
        val viewModel = AuthViewModel(authManager, authCheckScheduler, ticketStubStorage)

        viewModel.updateInviteCode("492013")
        viewModel.activate()
        advanceUntilIdle()
        viewModel.clearTicketCode()

        viewModel.deleteLastDigit()

        assertThat(viewModel.uiState.value.error).isEqualTo("INVALID_INVITE")
    }

    private fun activateResponse(nickname: String = "") = ActivateResponse(
        deviceId = "device-1",
        accessToken = "access-token",
        refreshToken = "refresh-token",
        accessExpiresAt = 0L,
        refreshExpiresAt = 0L,
        nextCheckAt = 0L,
        nickname = nickname
    )
}
