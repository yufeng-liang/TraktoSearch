package com.tracktosearch.ui.screen.feedback

import com.google.common.truth.Truth.assertThat
import com.tracktosearch.data.auth.AuthManager
import com.tracktosearch.data.local.DoubanAuthStorage
import com.tracktosearch.data.local.UserProfileStorage
import com.tracktosearch.data.remote.feedback.FeedbackDetail
import com.tracktosearch.data.remote.feedback.FeedbackDetailResponse
import com.tracktosearch.data.remote.feedback.FeedbackListItem
import com.tracktosearch.data.remote.feedback.MineResponse
import com.tracktosearch.data.remote.feedback.SubmitFeedbackResponse
import com.tracktosearch.data.repository.FeedbackRepository
import com.tracktosearch.test.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FeedbackViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val feedbackRepository = mockk<FeedbackRepository>()
    private val authManager = mockk<AuthManager>()
    private val userProfileStorage = mockk<UserProfileStorage>()
    private val doubanAuthStorage = mockk<DoubanAuthStorage>()

    private fun createViewModel(): FeedbackViewModel {
        every { authManager.nickname } returns MutableStateFlow(null)
        coEvery { userProfileStorage.getProfile() } returns null
        every { doubanAuthStorage.doubanProfile } returns MutableStateFlow(null)
        return FeedbackViewModel(feedbackRepository, authManager, userProfileStorage, doubanAuthStorage)
    }

    @Test
    fun `loadList success updates listState to Success`() = runTest {
        val items = listOf(
            FeedbackListItem("fb1", "BUG", "content1", null, "PENDING", 1700000000L)
        )
        coEvery { feedbackRepository.getMine(any(), any()) } returns Result.success(
            MineResponse(items, 20, 0, 1, false)
        )
        val viewModel = createViewModel()

        viewModel.loadList(refresh = true)
        advanceUntilIdle()

        val state = viewModel.listState.value
        assertThat(state).isInstanceOf(FeedbackViewModel.ListState.Success::class.java)
        val success = state as FeedbackViewModel.ListState.Success
        assertThat(success.items).hasSize(1)
        assertThat(success.items[0].id).isEqualTo("fb1")
        assertThat(success.hasMore).isFalse()
        assertThat(success.offset).isEqualTo(1)
    }

    @Test
    fun `loadList failure updates listState to Error`() = runTest {
        coEvery { feedbackRepository.getMine(any(), any()) } returns Result.failure(Exception("LOAD_FAILED"))
        val viewModel = createViewModel()

        viewModel.loadList(refresh = true)
        advanceUntilIdle()

        val state = viewModel.listState.value
        assertThat(state).isInstanceOf(FeedbackViewModel.ListState.Error::class.java)
        assertThat((state as FeedbackViewModel.ListState.Error).message).isEqualTo("LOAD_FAILED")
    }

    @Test
    fun `loadDetail success updates detailState to Success`() = runTest {
        val detail = FeedbackDetail(
            "fb1", "f1", "friend", null, null, null, "BUG", "content", null, null,
            "1.0", "14", "Pixel", "PENDING", 1700000000L
        )
        coEvery { feedbackRepository.getDetail("fb1") } returns Result.success(
            FeedbackDetailResponse(detail, emptyList())
        )
        val viewModel = createViewModel()

        viewModel.loadDetail("fb1")
        advanceUntilIdle()

        assertThat(viewModel.detailState.value).isInstanceOf(FeedbackViewModel.DetailState.Success::class.java)
    }

    @Test
    fun `submit success updates submitState to Success`() = runTest {
        coEvery { feedbackRepository.submit(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns Result.success(
            SubmitFeedbackResponse("fb-new", 1700000000L)
        )
        val viewModel = createViewModel()

        viewModel.submit("BUG", "test content", null, emptyList(), emptyList())
        advanceUntilIdle()

        val state = viewModel.submitState.value
        assertThat(state).isInstanceOf(FeedbackViewModel.SubmitState.Success::class.java)
        assertThat((state as FeedbackViewModel.SubmitState.Success).id).isEqualTo("fb-new")
    }

    @Test
    fun `submit screenshot upload failure updates submitState to Error`() = runTest {
        coEvery { feedbackRepository.uploadScreenshot(any(), any(), any()) } returns Result.failure(Exception("too large"))
        val viewModel = createViewModel()

        viewModel.submit("BUG", "test content", null, listOf(byteArrayOf(1, 2)), listOf("image/jpeg"))
        advanceUntilIdle()

        val state = viewModel.submitState.value
        assertThat(state).isInstanceOf(FeedbackViewModel.SubmitState.Error::class.java)
        assertThat((state as FeedbackViewModel.SubmitState.Error).message).contains("SCREENSHOT_UPLOAD_FAILED")
    }

    @Test
    fun `submit main call failure updates submitState to Error`() = runTest {
        coEvery { feedbackRepository.submit(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns Result.failure(Exception("SUBMIT_FAILED"))
        val viewModel = createViewModel()

        viewModel.submit("BUG", "test content", null, emptyList(), emptyList())
        advanceUntilIdle()

        val state = viewModel.submitState.value
        assertThat(state).isInstanceOf(FeedbackViewModel.SubmitState.Error::class.java)
    }

    @Test
    fun `resetSubmitState returns to Idle`() = runTest {
        val viewModel = createViewModel()
        viewModel.resetSubmitState()

        assertThat(viewModel.submitState.value).isEqualTo(FeedbackViewModel.SubmitState.Idle)
    }
}
