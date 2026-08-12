package com.tracktosearch.ui.screen.feedback

import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.tracktosearch.R
import com.tracktosearch.data.auth.AuthManager
import com.tracktosearch.data.local.DoubanAuthStorage
import com.tracktosearch.data.local.UserProfileStorage
import com.tracktosearch.data.remote.feedback.FeedbackDetail
import com.tracktosearch.data.remote.feedback.FeedbackDetailResponse
import com.tracktosearch.data.remote.feedback.FeedbackListItem
import com.tracktosearch.data.remote.feedback.MineResponse
import com.tracktosearch.data.remote.feedback.SubmitFeedbackResponse
import com.tracktosearch.data.repository.FeedbackCacheStore
import com.tracktosearch.data.repository.FeedbackRepository
import com.tracktosearch.test.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.just
import io.mockk.every
import io.mockk.mockk
import io.mockk.Runs
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FeedbackViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val feedbackRepository = mockk<FeedbackRepository>()
    private val cacheStore = mockk<FeedbackCacheStore>(relaxed = true)
    private val authManager = mockk<AuthManager>()
    private val userProfileStorage = mockk<UserProfileStorage>()
    private val doubanAuthStorage = mockk<DoubanAuthStorage>()
    private val crashLogRecordStore = mockk<com.tracktosearch.data.local.CrashLogRecordStore>(relaxed = true)
    private val context = mockk<Context>(relaxed = true)

    private fun createViewModel(): FeedbackViewModel {
        every { authManager.nickname } returns MutableStateFlow(null)
        coEvery { userProfileStorage.getProfile() } returns null
        coEvery { cacheStore.loadFromDisk() } just Runs
        every { cacheStore.getCachedList() } returns null
        coEvery { cacheStore.getCachedDetail(any()) } returns null
        every { doubanAuthStorage.doubanProfile } returns MutableStateFlow(null)
                every { context.getString(R.string.error_load_failed) } returns "Load failed"
        every { context.getString(R.string.feedback_submit_failed) } returns "Submit failed"
        every { context.getString(R.string.error_operation_failed) } returns "Operation failed"
        return FeedbackViewModel(feedbackRepository, cacheStore, authManager, userProfileStorage, doubanAuthStorage, crashLogRecordStore, context)
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
    fun `loadList refresh replaces previous items`() = runTest {
        val oldItem = FeedbackListItem("old", "BUG", "old", null, "PENDING", 1700000000L)
        val newItem = FeedbackListItem("new", "FEATURE", "new", null, "PENDING", 1700000100L)
        coEvery { feedbackRepository.getMine(any(), any()) } returnsMany listOf(
            Result.success(MineResponse(listOf(oldItem), 20, 0, 1, false)),
            Result.success(MineResponse(listOf(newItem), 20, 0, 1, false))
        )
        val viewModel = createViewModel()

        viewModel.loadList(refresh = true)
        advanceUntilIdle()
        viewModel.loadList(refresh = true)
        advanceUntilIdle()

        val state = viewModel.listState.value as FeedbackViewModel.ListState.Success
        assertThat(state.items.map { it.id }).containsExactly("new")
    }

    @Test
    fun `loadList failure updates listState to Error`() = runTest {
        coEvery { feedbackRepository.getMine(any(), any()) } returns Result.failure(Exception("LOAD_FAILED"))
        val viewModel = createViewModel()

        viewModel.loadList(refresh = true)
        advanceUntilIdle()

        val state = viewModel.listState.value
        assertThat(state).isInstanceOf(FeedbackViewModel.ListState.Error::class.java)
        assertThat((state as FeedbackViewModel.ListState.Error).message).isEqualTo("Load failed")
    }

    @Test
    fun `loadList shows cached items before refresh completes`() = runTest {
        val cached = FeedbackListItem("cached", "BUG", "cached content", null, "PENDING", 1700000000L)
        val remoteResult = CompletableDeferred<Result<MineResponse>>()
        val viewModel = createViewModel()

        every { cacheStore.getCachedList() } returns MineResponse(listOf(cached), 20, 0, 1, false)
        coEvery { feedbackRepository.getMine(any(), any()) } coAnswers { remoteResult.await() }

        viewModel.loadList(refresh = true)
        runCurrent()

        val state = viewModel.listState.value as FeedbackViewModel.ListState.Success
        assertThat(state.items.single().id).isEqualTo("cached")

        remoteResult.complete(Result.success(MineResponse(emptyList(), 20, 0, 0, false)))
        advanceUntilIdle()
        assertThat((viewModel.listState.value as FeedbackViewModel.ListState.Success).items).isEmpty()
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
    fun `loadDetail keeps cached content visible while replies refresh`() = runTest {
        val cachedDetail = FeedbackDetail(
            "fb1", "f1", "friend", null, null, null, "BUG", "cached content", null, null,
            "1.0", "14", "Pixel", "PENDING", 1700000000L
        )
        val remoteResult = CompletableDeferred<Result<FeedbackDetailResponse>>()
        val viewModel = createViewModel()

        coEvery { cacheStore.getCachedDetail("fb1") } returns FeedbackDetailResponse(cachedDetail, emptyList())
        coEvery { feedbackRepository.getDetail("fb1") } coAnswers { remoteResult.await() }

        viewModel.loadDetail("fb1")
        runCurrent()

        val state = viewModel.detailState.value as FeedbackViewModel.DetailState.Success
        assertThat(state.data.feedback.content).isEqualTo("cached content")
        assertThat(state.isRefreshing).isTrue()

        remoteResult.complete(Result.success(FeedbackDetailResponse(cachedDetail.copy(content = "remote content"), emptyList())))
        advanceUntilIdle()
        val refreshed = viewModel.detailState.value as FeedbackViewModel.DetailState.Success
        assertThat(refreshed.data.feedback.content).isEqualTo("remote content")
        assertThat(refreshed.isRefreshing).isFalse()
    }

    @Test
    fun `submit success updates submitState to Success`() = runTest {
        coEvery { feedbackRepository.submit(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns Result.success(
            SubmitFeedbackResponse("fb-new", 1700000000L)
        )
        val viewModel = createViewModel()

        viewModel.submit("BUG", "test content", emptyList(), emptyList())
        advanceUntilIdle()

        val state = viewModel.submitState.value
        assertThat(state).isInstanceOf(FeedbackViewModel.SubmitState.Success::class.java)
        assertThat((state as FeedbackViewModel.SubmitState.Success).id).isEqualTo("fb-new")
    }

    @Test
    fun `submit screenshot upload failure updates submitState to Error`() = runTest {
        coEvery { feedbackRepository.uploadScreenshot(any(), any(), any()) } returns Result.failure(Exception("too large"))
        val viewModel = createViewModel()

        viewModel.submit("BUG", "test content", listOf(byteArrayOf(1, 2)), listOf("image/jpeg"))
        advanceUntilIdle()

        val state = viewModel.submitState.value
        assertThat(state).isInstanceOf(FeedbackViewModel.SubmitState.Error::class.java)
        assertThat((state as FeedbackViewModel.SubmitState.Error).message).isEqualTo("Submit failed")
    }

    @Test
    fun `submit main call failure updates submitState to Error`() = runTest {
        coEvery { feedbackRepository.submit(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns Result.failure(Exception("SUBMIT_FAILED"))
        val viewModel = createViewModel()

        viewModel.submit("BUG", "test content", emptyList(), emptyList())
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
