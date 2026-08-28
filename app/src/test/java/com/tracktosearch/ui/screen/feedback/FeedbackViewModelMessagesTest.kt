package com.tracktosearch.ui.screen.feedback

import com.google.common.truth.Truth.assertThat
import com.tracktosearch.data.auth.AuthManager
import com.tracktosearch.data.local.DoubanAuthStorage
import com.tracktosearch.data.local.UserProfileStorage
import com.tracktosearch.data.remote.feedback.MessageItem
import com.tracktosearch.data.remote.feedback.MessagesResponse
import com.tracktosearch.data.repository.FeedbackCacheStore
import com.tracktosearch.data.repository.FeedbackRepository
import com.tracktosearch.test.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.every
import android.content.Context
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FeedbackViewModelMessagesTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val feedbackRepository = mockk<FeedbackRepository>()
    private val cacheStore = mockk<FeedbackCacheStore>(relaxed = true)
    private val authManager = mockk<AuthManager>()
    private val userProfileStorage = mockk<UserProfileStorage>()
    private val doubanAuthStorage = mockk<DoubanAuthStorage>()
    private val context = mockk<Context>(relaxed = true)
    private val crashLogRecordStore = mockk<com.tracktosearch.data.local.CrashLogRecordStore>(relaxed = true)

    private fun createViewModel(): FeedbackViewModel {
        every { authManager.nickname } returns MutableStateFlow(null)
        coEvery { userProfileStorage.getProfile() } returns null
        every { doubanAuthStorage.doubanProfile } returns MutableStateFlow(null)
        return FeedbackViewModel(feedbackRepository, cacheStore, authManager, userProfileStorage, doubanAuthStorage, crashLogRecordStore, context)
    }

    private fun message(id: String, unread: Boolean): MessageItem = MessageItem(
        id = id,
        feedback_id = "f1",
        display_id = "BUG001",
        type = "BUG",
        author_role = "developer",
        content = id,
        screenshots = emptyList(),
        created_at = 1700000000L,
        is_unread = unread
    )

    @Test
    fun switchingFromUnreadBackToAllRestoresReadMessages() = runTest {
        coEvery { feedbackRepository.getMessages(50, 0) } returns Result.success(
            MessagesResponse(listOf(message("read", false), message("unread", true)), 50, 0, 2, false)
        )
        val viewModel = createViewModel()

        viewModel.loadMessages(refresh = true)
        advanceUntilIdle()
        viewModel.setMessagesFilter(FeedbackViewModel.MessageFilter.UNREAD)
        assertThat((viewModel.messagesState.value as FeedbackViewModel.MessagesState.Success).items.map { it.id })
            .containsExactly("unread")
        viewModel.setMessagesFilter(FeedbackViewModel.MessageFilter.ALL)

        assertThat((viewModel.messagesState.value as FeedbackViewModel.MessagesState.Success).items.map { it.id })
            .containsExactly("read", "unread")
    }

    @Test
    fun markingFeedbackReadUpdatesMessageListState() = runTest {
        coEvery { feedbackRepository.getMessages(50, 0) } returns Result.success(
            MessagesResponse(listOf(message("m1", true), message("m2", true)), 50, 0, 2, false)
        )
        coEvery { feedbackRepository.markAsRead("f1") } returns Result.success(Unit)
        val viewModel = createViewModel()

        viewModel.loadMessages(refresh = true)
        advanceUntilIdle()
        viewModel.markAsRead("f1")
        advanceUntilIdle()

        val state = viewModel.messagesState.value as FeedbackViewModel.MessagesState.Success
        assertThat(state.items.map { it.is_unread }).containsExactly(false, false)
    }

    @Test
    fun cachedMessagesRemainVisibleWhileRefreshIsInFlight() = runTest {
        val cached = MessagesResponse(listOf(message("cached", true)), 50, 0, 1, false)
        val networkResult = CompletableDeferred<Result<MessagesResponse>>()
        every { cacheStore.getCachedMessages() } returns cached
        coEvery { feedbackRepository.getMessages(50, 0) } coAnswers { networkResult.await() }
        val viewModel = createViewModel()

        viewModel.loadMessages(refresh = true)
        runCurrent()

        assertThat((viewModel.messagesState.value as FeedbackViewModel.MessagesState.Success).items.map { it.id })
            .containsExactly("cached")

        networkResult.complete(Result.success(MessagesResponse(listOf(message("fresh", false)), 50, 0, 1, false)))
        advanceUntilIdle()
        assertThat((viewModel.messagesState.value as FeedbackViewModel.MessagesState.Success).items.map { it.id })
            .containsExactly("fresh")
    }

    @Test
    fun refreshFailureKeepsCachedMessagesVisible() = runTest {
        val cached = MessagesResponse(listOf(message("cached", true)), 50, 0, 1, false)
        every { cacheStore.getCachedMessages() } returns cached
        coEvery { feedbackRepository.getMessages(50, 0) } returns Result.failure(IllegalStateException("NETWORK_FAILED"))
        val viewModel = createViewModel()

        viewModel.loadMessages(refresh = true)
        advanceUntilIdle()

        assertThat((viewModel.messagesState.value as FeedbackViewModel.MessagesState.Success).items.map { it.id })
            .containsExactly("cached")
    }

    // ==================== 静默刷新指示器 ====================

    @Test
    fun isRefreshingIsTrueWhileCachedMessagesRefreshAndFalseAfterwards() = runTest {
        val cached = MessagesResponse(listOf(message("cached", true)), 50, 0, 1, false)
        val networkResult = CompletableDeferred<Result<MessagesResponse>>()
        every { cacheStore.getCachedMessages() } returns cached
        coEvery { feedbackRepository.getMessages(50, 0) } coAnswers { networkResult.await() }
        val viewModel = createViewModel()

        viewModel.loadMessages(refresh = true)
        runCurrent()

        assertThat((viewModel.messagesState.value as FeedbackViewModel.MessagesState.Success).isRefreshing).isTrue()

        networkResult.complete(Result.success(MessagesResponse(listOf(message("fresh", false)), 50, 0, 1, false)))
        advanceUntilIdle()
        assertThat((viewModel.messagesState.value as FeedbackViewModel.MessagesState.Success).isRefreshing).isFalse()
    }

    @Test
    fun isRefreshingResetsAfterRefreshFailure() = runTest {
        val cached = MessagesResponse(listOf(message("cached", true)), 50, 0, 1, false)
        every { cacheStore.getCachedMessages() } returns cached
        coEvery { feedbackRepository.getMessages(50, 0) } returns Result.failure(IllegalStateException("NETWORK_FAILED"))
        val viewModel = createViewModel()

        viewModel.loadMessages(refresh = true)
        advanceUntilIdle()

        assertThat((viewModel.messagesState.value as FeedbackViewModel.MessagesState.Success).isRefreshing).isFalse()
    }

    @Test
    fun firstLoadWithoutCacheStaysLoadingInsteadOfShowingIndicator() = runTest {
        val networkResult = CompletableDeferred<Result<MessagesResponse>>()
        every { cacheStore.getCachedMessages() } returns null
        coEvery { feedbackRepository.getMessages(50, 0) } coAnswers { networkResult.await() }
        val viewModel = createViewModel()

        viewModel.loadMessages(refresh = true)
        runCurrent()

        // 无缓存时首次加载走骨架屏，不显示顶栏进度条
        assertThat(viewModel.messagesState.value).isEqualTo(FeedbackViewModel.MessagesState.Loading)

        networkResult.complete(Result.success(MessagesResponse(listOf(message("fresh", false)), 50, 0, 1, false)))
        advanceUntilIdle()
        assertThat((viewModel.messagesState.value as FeedbackViewModel.MessagesState.Success).isRefreshing).isFalse()
    }

    @Test
    fun loadMoreAlsoShowsRefreshIndicator() = runTest {
        val networkResult = CompletableDeferred<Result<MessagesResponse>>()
        coEvery { feedbackRepository.getMessages(50, 0) } returns Result.success(
            MessagesResponse(listOf(message("m1", true)), 50, 0, 2, true)
        )
        coEvery { feedbackRepository.getMessages(50, 1) } coAnswers { networkResult.await() }
        val viewModel = createViewModel()

        viewModel.loadMessages(refresh = true)
        advanceUntilIdle()
        assertThat((viewModel.messagesState.value as FeedbackViewModel.MessagesState.Success).isRefreshing).isFalse()

        // 「加载更多」按钮自身没有进度反馈，翻页同样用顶栏进度条提示
        viewModel.loadMessages(refresh = false)
        runCurrent()
        assertThat((viewModel.messagesState.value as FeedbackViewModel.MessagesState.Success).isRefreshing).isTrue()

        networkResult.complete(Result.success(MessagesResponse(listOf(message("m2", true)), 50, 1, 2, false)))
        advanceUntilIdle()
        val state = viewModel.messagesState.value as FeedbackViewModel.MessagesState.Success
        assertThat(state.isRefreshing).isFalse()
        assertThat(state.items.map { it.id }).containsExactly("m1", "m2")
    }
}
