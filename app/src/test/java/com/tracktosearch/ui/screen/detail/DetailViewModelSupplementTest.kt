package com.tracktosearch.ui.screen.detail

import androidx.compose.ui.graphics.Color
import com.google.common.truth.Truth.assertThat
import com.tracktosearch.R
import com.tracktosearch.data.local.DetailSectionStorage
import com.tracktosearch.data.local.DoubanAuthStorage
import com.tracktosearch.data.local.DoubanCredentials
import com.tracktosearch.data.local.LanguageStorage
import com.tracktosearch.data.local.TokenStorage
import com.tracktosearch.data.local.ViewedItemStorage
import com.tracktosearch.data.local.db.DoubanSyncedItem
import com.tracktosearch.data.local.db.DoubanSyncedItemDao
import com.tracktosearch.data.remote.douban.DoubanRepository
import com.tracktosearch.data.remote.douban.DoubanRexxarMediaType
import com.tracktosearch.data.remote.douban.DoubanRexxarRepository
import com.tracktosearch.data.remote.douban.DoubanRexxarShortComment
import com.tracktosearch.data.remote.douban.DoubanRexxarShortCommentPage
import com.tracktosearch.data.remote.douban.MarkWriteResult
import com.tracktosearch.data.remote.dto.DiskType
import com.tracktosearch.data.remote.dto.ResourceItem
import com.tracktosearch.data.remote.tmdb.dto.TmdbReview
import com.tracktosearch.data.remote.tmdb.dto.TmdbReviewsResponse
import com.tracktosearch.data.remote.trakt.dto.TraktComment
import com.tracktosearch.data.repository.MediaType
import com.tracktosearch.data.repository.MediaMetadataRepository
import com.tracktosearch.data.repository.RatingsRepository
import com.tracktosearch.data.repository.ResourceRepository
import com.tracktosearch.data.repository.ResourceQuery
import com.tracktosearch.data.repository.TmdbRepository
import com.tracktosearch.data.repository.TraktRepository
import com.tracktosearch.data.repository.UserReviewRepository
import com.tracktosearch.data.session.SessionMode
import com.tracktosearch.data.session.SessionModeManager
import com.tracktosearch.data.util.CommentTranslator
import com.tracktosearch.data.util.PosterColorExtractor
import com.tracktosearch.test.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * DetailViewModel 补充测试。
 *
 * 覆盖 DetailViewModelTest 未覆盖的分支：
 * - toggleSource / toggleDiskType 的移除和最后一个守卫分支
 * - toggleShowHighRelevanceOnly 切换
 * - translateComments / translateSingleComment
 * - loadMoreComments 分页和去重
 * - retryDoubanSync 各分支（状态字段验证，toastEvent 单独验证）
 * - onCleared 缓存清理
 * - toggleSeason 折叠分支和加载失败回滚
 * - 防抖守卫（isMarking* / isRating）
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class DetailViewModelSupplementTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var tmdbRepository: TmdbRepository
    private lateinit var mediaMetadataRepository: MediaMetadataRepository
    private lateinit var traktRepository: TraktRepository
    private lateinit var resourceRepository: ResourceRepository
    private lateinit var ratingsRepository: RatingsRepository
    private lateinit var viewedItemStorage: ViewedItemStorage
    private lateinit var commentTranslator: CommentTranslator
    private lateinit var tokenStorage: TokenStorage
    private lateinit var detailSectionStorage: DetailSectionStorage
    private lateinit var languageStorage: LanguageStorage
    private lateinit var doubanRepository: DoubanRepository
    private lateinit var doubanRexxarRepository: DoubanRexxarRepository
    private lateinit var doubanAuthStorage: DoubanAuthStorage
    private lateinit var doubanSyncedItemDao: DoubanSyncedItemDao
    private lateinit var sessionModeManager: SessionModeManager
    private lateinit var traktConnected: MutableStateFlow<Boolean>
    private lateinit var posterColorExtractor: PosterColorExtractor
    private lateinit var userReviewRepository: UserReviewRepository
    private lateinit var aiProfileBehaviorRecorder: com.tracktosearch.data.ai.AiProfileBehaviorRecorder

    private lateinit var viewModel: DetailViewModel

    @Before
    fun setup() {
        // 清理静态缓存
        val cacheField = DetailViewModel::class.java.getDeclaredField("detailCache")
        cacheField.isAccessible = true
        val cache = cacheField.get(null) as MutableMap<*, *>
        cache.clear()

        tmdbRepository = mockk(relaxed = true)
        mediaMetadataRepository = mockk(relaxed = true)
        traktRepository = mockk(relaxed = true)
        resourceRepository = mockk(relaxed = true)
        ratingsRepository = mockk(relaxed = true)
        viewedItemStorage = mockk(relaxed = true)
        commentTranslator = mockk(relaxed = true)
        tokenStorage = mockk(relaxed = true)
        detailSectionStorage = mockk(relaxed = true)
        languageStorage = mockk(relaxed = true)
        doubanRepository = mockk(relaxed = true)
        doubanRexxarRepository = mockk(relaxed = true)
        doubanAuthStorage = mockk(relaxed = true)
        doubanSyncedItemDao = mockk(relaxed = true)
        sessionModeManager = mockk(relaxed = true)
        traktConnected = MutableStateFlow(false)
        every { sessionModeManager.traktConnected } returns traktConnected
        every { sessionModeManager.sessionMode } returns flowOf(SessionMode.TRAKT)
        posterColorExtractor = mockk(relaxed = true)
        userReviewRepository = mockk(relaxed = true)
        aiProfileBehaviorRecorder = mockk(relaxed = true)

        every { tokenStorage.accessToken } returns flowOf(null)
        every { detailSectionStorage.sectionConfigs } returns MutableStateFlow(emptyList())
        every { doubanAuthStorage.getCredentials() } returns null
        every { languageStorage.language } returns MutableStateFlow(LanguageStorage.LANGUAGE_ENGLISH)
        coEvery {
            doubanRexxarRepository.getDetail(any(), any(), any())
        } returns Result.failure(IllegalStateException("Rexxar not stubbed"))
        coEvery {
            doubanRexxarRepository.getPhotos(any(), any(), any(), any(), any())
        } returns Result.failure(IllegalStateException("Rexxar photos not stubbed"))
        coEvery { mediaMetadataRepository.getSummaries(any(), any()) } returns emptyList()
        every { resourceRepository.filterItems(any(), any(), any()) } returns emptyList()

        viewModel = DetailViewModel(
            tmdbRepository, mediaMetadataRepository, traktRepository, resourceRepository, ratingsRepository,
            viewedItemStorage, commentTranslator, tokenStorage, detailSectionStorage,
            languageStorage, doubanRepository, doubanRexxarRepository, doubanAuthStorage, doubanSyncedItemDao,
            sessionModeManager,
            posterColorExtractor, userReviewRepository, aiProfileBehaviorRecorder
        )
    }

    // ==================== 反射辅助 ====================

    private fun setPrivateField(fieldName: String, value: Any?) {
        val field = DetailViewModel::class.java.getDeclaredField(fieldName)
        field.isAccessible = true
        field.set(viewModel, value)
    }

    @Suppress("UNCHECKED_CAST")
    private fun setUiState(transform: (DetailUiState) -> DetailUiState) {
        val field = DetailViewModel::class.java.getDeclaredField("_uiState")
        field.isAccessible = true
        val stateFlow = field.get(viewModel) as MutableStateFlow<DetailUiState>
        stateFlow.value = transform(stateFlow.value)
    }

    private fun setupLoggedInState(traktId: Int = 100, mediaType: MediaType = MediaType.MOVIE) {
        setPrivateField("currentTraktId", traktId)
        setPrivateField("currentMediaType", mediaType)
        setPrivateField("currentTmdbId", 200)
        setPrivateField("isLoggedIn", true)
        every { tokenStorage.getCachedAccessToken() } returns "fake-token"
        traktConnected.value = true
    }

    private fun callOnCleared() {
        val method = androidx.lifecycle.ViewModel::class.java.getDeclaredMethod("onCleared")
        method.isAccessible = true
        method.invoke(viewModel)
    }

    private fun invokeFetchComments() {
        val method = DetailViewModel::class.java.getDeclaredMethod("fetchComments")
        method.isAccessible = true
        method.invoke(viewModel)
    }

    // ==================== toggleSource 分支 ====================


    @Test
    fun toggleSource_最后一个源不允许移除() {
        val onlySource = "only_source"
        setUiState { it.copy(enabledSources = setOf(onlySource)) }

        // 返回值就是界面判「发 toggle 还是发 reject」的依据：被守卫挡下时必须是 false，
        // 否则 chip 会在什么都没变的情况下震一记「已关闭」
        val toggled = viewModel.toggleSource(onlySource)

        assertThat(toggled).isFalse()
        assertThat(viewModel.uiState.value.enabledSources).containsExactly(onlySource)
    }

    @Test
    fun toggleSource_真的切换时返回true() {
        setUiState { it.copy(enabledSources = setOf("a", "b")) }

        assertThat(viewModel.toggleSource("b")).isTrue()
        assertThat(viewModel.uiState.value.enabledSources).containsExactly("a")

        assertThat(viewModel.toggleSource("c")).isTrue()
        assertThat(viewModel.uiState.value.enabledSources).containsExactly("a", "c")
    }

    // ==================== toggleDiskType 分支 ====================

    @Test
    fun toggleDiskType_最后一个类型不允许移除() {
        setUiState { it.copy(enabledDiskTypes = setOf(DiskType.QUARK)) }

        assertThat(viewModel.toggleDiskType(DiskType.QUARK)).isFalse()
        assertThat(viewModel.uiState.value.enabledDiskTypes).containsExactly(DiskType.QUARK)
    }

    @Test
    fun toggleDiskType_真的切换时返回true() {
        setUiState { it.copy(enabledDiskTypes = setOf(DiskType.QUARK, DiskType.ALI)) }

        assertThat(viewModel.toggleDiskType(DiskType.ALI)).isTrue()
        assertThat(viewModel.uiState.value.enabledDiskTypes).containsExactly(DiskType.QUARK)
    }


    // ==================== toggleShowHighRelevanceOnly ====================



    @Test
    fun toggleShowHighRelevanceOnly_强标题视频保留并可恢复全部资源() {
        val video = ResourceItem(
            name = "蜘蛛侠：崭新之日 最新",
            diskType = DiskType.QUARK,
            fileSize = "1G",
            url = "https://example.com/video",
            source = ResourceRepository.SOURCE_PANSOU,
            fileCount = 1
        )
        val audio = video.copy(
            name = "蜘蛛侠：崭新之日 电影中文推广曲 FLAC",
            url = "https://example.com/audio"
        )
        val weak = video.copy(
            name = "电子情书 1080p",
            url = "https://example.com/weak"
        )
        val all = listOf(video, audio, weak)

        every { resourceRepository.filterItems(any(), any(), any()) } answers { firstArg() }
        setPrivateField("allResources", all)
        setPrivateField(
            "currentResourceQuery",
            ResourceQuery(
                title = "蜘蛛侠：崭新之日",
                year = 2026,
                mediaType = MediaType.MOVIE
            )
        )
        setPrivateField(
            "currentHighRelevanceMap",
            mapOf(video.url to true, audio.url to false, weak.url to false)
        )
        setUiState {
            it.copy(
                enabledSources = ResourceRepository.ALL_SOURCES,
                enabledDiskTypes = ResourceRepository.ALL_DISK_TYPES
            )
        }

        viewModel.toggleShowHighRelevanceOnly()

        assertThat(viewModel.uiState.value.resources.map { it.name })
            .containsExactly(video.name)
        assertThat(viewModel.uiState.value.lowRelevanceHiddenCount).isEqualTo(2)

        viewModel.toggleShowHighRelevanceOnly()

        assertThat(viewModel.uiState.value.resources.map { it.name })
            .containsExactly(video.name, audio.name, weak.name)
        assertThat(viewModel.uiState.value.lowRelevanceHiddenCount).isEqualTo(0)
    }

    @Test
    fun fetchComments_doubanResultsArePrimaryAndSkipFallbackSources() = runTest {
        setPrivateField("currentSessionMode", SessionMode.DOUBAN)
        setPrivateField("currentDoubanId", "db-1")
        setPrivateField("currentMediaType", MediaType.MOVIE)
        setPrivateField("currentTraktId", 0)
        setPrivateField("currentTmdbId", 0)
        coEvery {
            doubanRexxarRepository.getShortComments("db-1", DoubanRexxarMediaType.MOVIE, 0, 10, false)
        } returns Result.success(
            DoubanRexxarShortCommentPage(
                total = 1,
                start = 0,
                count = 1,
                comments = listOf(
                    DoubanRexxarShortComment(
                        id = "douban-comment-1",
                        authorName = "豆瓣用户",
                        ratingStars = 4,
                        text = "豆瓣短评",
                        createdAt = "2024-01-01"
                    )
                )
            )
        )

        invokeFetchComments()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.comments).hasSize(1)
        assertThat(viewModel.uiState.value.comments.single().source).isEqualTo("Douban")
        assertThat(viewModel.uiState.value.comments.single().comment).isEqualTo("豆瓣短评")
        assertThat(viewModel.uiState.value.commentSource).isEqualTo(CommentSource.DOUBAN)
        coVerify(exactly = 0) { traktRepository.getComments(any(), any(), any(), any()) }
        coVerify(exactly = 0) { tmdbRepository.getReviews(any(), any(), any()) }
    }

    @Test
    fun fetchComments_emptyDoubanResultFallsBackToTraktAndTmdb() = runTest {
        setPrivateField("currentSessionMode", SessionMode.TRAKT)
        setPrivateField("currentDoubanId", "db-empty")
        setPrivateField("currentMediaType", MediaType.MOVIE)
        setPrivateField("currentTraktId", 100)
        setPrivateField("currentTmdbId", 200)
        traktConnected.value = true
        coEvery {
            doubanRexxarRepository.getShortComments("db-empty", DoubanRexxarMediaType.MOVIE, 0, 10, false)
        } returns Result.success(DoubanRexxarShortCommentPage())
        coEvery {
            traktRepository.getComments(100, MediaType.MOVIE, limit = 10, page = 1)
        } returns Result.success(listOf(TraktComment(id = 1, comment = "Trakt comment")))
        coEvery {
            tmdbRepository.getReviews(200, MediaType.MOVIE, page = 1)
        } returns TmdbReviewsResponse(
            results = listOf(TmdbReview(id = "tmdb-1", author = "TMDB user", content = "TMDB comment"))
        )

        invokeFetchComments()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.comments).hasSize(2)
        assertThat(viewModel.uiState.value.comments.map { it.source })
            .containsExactly("Trakt", "TMDB")
        assertThat(viewModel.uiState.value.commentSource).isEqualTo(CommentSource.FALLBACK)
    }

    @Test
    fun loadMoreComments_doubanSourceUsesDoubanStartOffset() = runTest {
        setPrivateField("currentSessionMode", SessionMode.DOUBAN)
        setPrivateField("currentDoubanId", "db-page")
        setPrivateField("currentMediaType", MediaType.MOVIE)
        setPrivateField("currentTraktId", 0)
        setPrivateField("currentTmdbId", 0)
        setUiState {
            it.copy(
                comments = listOf(TraktComment(id = 1, comment = "第一页", source = "Douban")),
                commentSource = CommentSource.DOUBAN,
                doubanCommentPage = 1,
                hasMoreComments = true
            )
        }
        coEvery {
            doubanRexxarRepository.getShortComments("db-page", DoubanRexxarMediaType.MOVIE, 10, 10, false)
        } returns Result.success(
            DoubanRexxarShortCommentPage(
                total = 2,
                start = 10,
                count = 1,
                comments = listOf(DoubanRexxarShortComment(id = "douban-comment-2", text = "第二页"))
            )
        )

        viewModel.loadMoreComments()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.comments).hasSize(2)
        assertThat(viewModel.uiState.value.comments.last().source).isEqualTo("Douban")
        assertThat(viewModel.uiState.value.doubanCommentPage).isEqualTo(2)
        assertThat(viewModel.uiState.value.hasMoreComments).isFalse()
        coVerify(exactly = 0) { traktRepository.getComments(any(), any(), any(), any()) }
    }

    // ==================== translateComments ====================


    @Test
    fun translateComments_成功更新translatedComments() = runTest {
        val comments = listOf(
            TraktComment(id = 1, comment = "hello"),
            TraktComment(id = 2, comment = "world")
        )
        val translated0 = TraktComment(id = 1, comment = "你好")
        val translated1 = TraktComment(id = 2, comment = "世界")
        setUiState { it.copy(comments = comments) }
        // translateCommentsFlow 是非 suspend 函数返回 Flow，用 every + flowOf stub
        every { commentTranslator.translateCommentsFlow(comments) } returns
            flowOf(0 to translated0, 1 to translated1)

        viewModel.translateComments()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.translatedComments).hasSize(2)
        assertThat(viewModel.uiState.value.translatedComments[0].comment).isEqualTo("你好")
        assertThat(viewModel.uiState.value.translatedComments[1].comment).isEqualTo("世界")
        assertThat(viewModel.uiState.value.isTranslating).isFalse()
        assertThat(viewModel.uiState.value.translationProgress).isNull()
    }



    // ==================== translateSingleComment ====================



    @Test
    fun translateSingleComment_成功添加到translatedComments() = runTest {
        val comment = TraktComment(id = 1, comment = "test")
        val translated = TraktComment(id = 1, comment = "测试")
        setUiState { it.copy(comments = listOf(comment)) }
        coEvery { commentTranslator.translateSingleComment(comment) } returns translated

        viewModel.translateSingleComment(1)
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.translatedComments).hasSize(1)
        assertThat(viewModel.uiState.value.translatedComments[0].comment).isEqualTo("测试")
        assertThat(viewModel.uiState.value.translatingCommentId).isNull()
    }


    @Test
    fun translateSingleComment_异常时translatingCommentId置null() = runTest {
        val comment = TraktComment(id = 1, comment = "test")
        setUiState { it.copy(comments = listOf(comment)) }
        coEvery { commentTranslator.translateSingleComment(any()) } throws RuntimeException("error")

        viewModel.translateSingleComment(1)
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.translatingCommentId).isNull()
    }

    // ==================== loadMoreComments ====================


    @Test
    fun loadMoreComments_isLoadingMore为true防抖返回() = runTest {
        setUiState { it.copy(hasMoreComments = true, isLoadingMoreComments = true) }

        viewModel.loadMoreComments()
        advanceUntilIdle()

        coVerify(exactly = 0) { traktRepository.getComments(any(), any(), any(), any()) }
    }

    @Test
    fun loadMoreComments_invalidTraktId_doesNotRequestTraktComments() = runTest {
        setPrivateField("currentTraktId", 0)
        setPrivateField("currentMediaType", MediaType.MOVIE)
        setUiState { it.copy(hasMoreComments = true) }

        viewModel.loadMoreComments()
        advanceUntilIdle()

        coVerify(exactly = 0) { traktRepository.getComments(any(), any(), any(), any()) }
        assertThat(viewModel.uiState.value.isLoadingMoreComments).isFalse()
    }

    @Test
    fun loadMoreComments_成功加载并去重() = runTest {
        setupLoggedInState(traktId = 100, mediaType = MediaType.MOVIE)
        val existingComment = TraktComment(id = 1, comment = "已有")
        val newComment = TraktComment(id = 2, comment = "新评论")
        setUiState {
            it.copy(
                comments = listOf(existingComment),
                commentPage = 1,
                tmdbCommentPage = 0,
                hasMoreComments = true
            )
        }
        coEvery {
            traktRepository.getComments(100, MediaType.MOVIE, limit = 10, page = 2)
        } returns Result.success(listOf(newComment))
        // tmdbRepository.getReviews 返回 null（未配置分页）
        coEvery { tmdbRepository.getReviews(any(), any(), any()) } returns null

        viewModel.loadMoreComments()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.comments).hasSize(2)
        assertThat(viewModel.uiState.value.isLoadingMoreComments).isFalse()
        assertThat(viewModel.uiState.value.commentPage).isEqualTo(2)
    }

    @Test
    fun loadMoreComments_重复评论被过滤() = runTest {
        setupLoggedInState(traktId = 100, mediaType = MediaType.MOVIE)
        val existingComment = TraktComment(id = 1, comment = "已有")
        setUiState {
            it.copy(
                comments = listOf(existingComment),
                commentPage = 1,
                tmdbCommentPage = 0,
                hasMoreComments = true
            )
        }
        coEvery {
            traktRepository.getComments(any(), any(), any(), any())
        } returns Result.success(listOf(existingComment))
        coEvery { tmdbRepository.getReviews(any(), any(), any()) } returns null

        viewModel.loadMoreComments()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.comments).hasSize(1)
    }

    @Test
    fun loadMoreComments_trakt返回failure时isLoadingMore置false() = runTest {
        // getComments 返回 Result.failure 时，getOrDefault 返回 emptyList，
        // try 块正常执行完毕会把 isLoadingMoreComments 置 false
        setupLoggedInState(traktId = 100, mediaType = MediaType.MOVIE)
        setUiState {
            it.copy(
                comments = emptyList(),
                commentPage = 1,
                tmdbCommentPage = 0,
                hasMoreComments = true
            )
        }
        coEvery {
            traktRepository.getComments(any(), any(), any(), any())
        } returns Result.failure(RuntimeException("API error"))
        coEvery { tmdbRepository.getReviews(any(), any(), any()) } returns null

        viewModel.loadMoreComments()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.isLoadingMoreComments).isFalse()
    }

    // ==================== toggleSeason 折叠分支 ====================


    @Test
    fun toggleSeason_加载失败回滚expandedSeasons() = runTest {
        setPrivateField("currentTraktId", 100)
        coEvery { traktRepository.getSeasonEpisodes(any(), any()) } returns Result.failure(RuntimeException("API error"))

        viewModel.toggleSeason(1)
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.expandedSeasons).doesNotContain(1)
    }

    // ==================== 防抖守卫 ====================

    @Test
    fun toggleWatched_isMarkingWatched为true防抖早返回() = runTest {
        setupLoggedInState()
        setUiState { it.copy(isMarkingWatched = true) }

        viewModel.toggleWatched()
        advanceUntilIdle()

        coVerify(exactly = 0) { traktRepository.markAsWatched(any(), any(), any()) }
        coVerify(exactly = 0) { traktRepository.removeWatched(any(), any(), any()) }
    }

    @Test
    fun toggleWatchlist_isMarkingWatchlist为true防抖早返回() = runTest {
        setupLoggedInState()
        setUiState { it.copy(isMarkingWatchlist = true) }

        viewModel.toggleWatchlist()
        advanceUntilIdle()

        coVerify(exactly = 0) { traktRepository.addToWatchlist(any(), any(), any()) }
        coVerify(exactly = 0) { traktRepository.removeFromWatchlist(any(), any(), any()) }
    }

    @Test
    fun setRating_isRating为true防抖早返回() = runTest {
        setupLoggedInState()
        setUiState { it.copy(isRating = true) }

        viewModel.setRating(8)
        advanceUntilIdle()

        coVerify(exactly = 0) { traktRepository.addRating(any(), any(), any()) }
    }


    @Test
    fun toggleEpisodeWatched_相同季集防抖早返回() = runTest {
        setupLoggedInState()
        setUiState { it.copy(togglingEpisode = 1 to 1) }

        viewModel.toggleEpisodeWatched(seasonNumber = 1, episodeNumber = 1, episodeTraktId = 100)
        advanceUntilIdle()

        coVerify(exactly = 0) { traktRepository.markEpisodeWatched(any()) }
        coVerify(exactly = 0) { traktRepository.unmarkEpisodeWatched(any(), any(), any(), any(), any(), any()) }
    }

    // ==================== retryDoubanSync（状态字段验证）====================


    @Test
    fun retryDoubanSync_isDoubanSyncing为true防抖返回() = runTest {
        setUiState {
            it.copy(
                pendingDoubanAction = DoubanSyncAction.WISH,
                isDoubanSyncing = true
            )
        }

        viewModel.retryDoubanSync()
        advanceUntilIdle()

        coVerify(exactly = 0) { doubanRepository.findDoubanId(any(), any(), any()) }
    }

    @Test
    fun retryDoubanSync_doubanId已就绪_未登录Toast_retryable为true() = runTest {
        setPrivateField("currentTraktId", 100)
        setPrivateField("currentMediaType", MediaType.MOVIE)
        setUiState {
            it.copy(
                pendingDoubanAction = DoubanSyncAction.WISH,
                doubanIdForSync = "1234567"
            )
        }
        every { doubanAuthStorage.getCredentials() } returns null

        viewModel.retryDoubanSync()
        advanceUntilIdle()

        // 未登录豆瓣 → doubanSyncRetryable = true
        assertThat(viewModel.uiState.value.doubanSyncRetryable).isTrue()
        assertThat(viewModel.uiState.value.pendingDoubanAction).isEqualTo(DoubanSyncAction.WISH)
    }

    @Test
    fun retryDoubanSync_doubanId未就绪_同步表命中_设置doubanIdForSync() = runTest {
        setPrivateField("currentTraktId", 100)
        setPrivateField("currentMediaType", MediaType.MOVIE)
        setPrivateField("currentImdbId", "tt1234567")
        setUiState {
            it.copy(
                pendingDoubanAction = DoubanSyncAction.WISH,
                doubanIdForSync = null
            )
        }
        val syncedItem = mockk<DoubanSyncedItem>(relaxed = true)
        every { syncedItem.doubanId } returns "7654321"
        coEvery { doubanSyncedItemDao.getByImdbId("tt1234567") } returns syncedItem
        every { doubanAuthStorage.getCredentials() } returns null

        viewModel.retryDoubanSync()
        advanceUntilIdle()

        // 同步表命中后 doubanIdForSync 被设置，但未登录豆瓣 → retryable=true
        assertThat(viewModel.uiState.value.doubanIdForSync).isEqualTo("7654321")
        assertThat(viewModel.uiState.value.ratingSource).isEqualTo(DetailRatingSource.DOUBAN)
        assertThat(viewModel.uiState.value.doubanSyncRetryable).isTrue()
    }

    @Test
    fun retryDoubanSync_doubanId未就绪_同步表未命中_走findDoubanId() = runTest {
        setPrivateField("currentTraktId", 100)
        setPrivateField("currentMediaType", MediaType.MOVIE)
        setPrivateField("currentImdbId", "tt1234567")
        setPrivateField("currentTmdbId", 42)
        setUiState {
            it.copy(
                pendingDoubanAction = DoubanSyncAction.WISH,
                doubanIdForSync = null
            )
        }
        coEvery { doubanSyncedItemDao.getByImdbId(any()) } returns null
        coEvery { doubanRepository.findDoubanId(100, "tt1234567", "movie", 42) } returns "9999999"
        every { doubanAuthStorage.getCredentials() } returns null

        viewModel.retryDoubanSync()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.doubanIdForSync).isEqualTo("9999999")
        assertThat(viewModel.uiState.value.ratingSource).isEqualTo(DetailRatingSource.DOUBAN)
        coVerify(exactly = 1) { doubanRepository.findDoubanId(100, "tt1234567", "movie", 42) }
    }

    @Test
    fun retryDoubanSync_doubanId未就绪_findDoubanId返回null_retryable为true() = runTest {
        setPrivateField("currentTraktId", 100)
        setPrivateField("currentMediaType", MediaType.MOVIE)
        setPrivateField("currentImdbId", "tt1234567")
        setUiState {
            it.copy(
                pendingDoubanAction = DoubanSyncAction.WISH,
                doubanIdForSync = null
            )
        }
        coEvery { doubanSyncedItemDao.getByImdbId(any()) } returns null
        coEvery { doubanRepository.findDoubanId(any(), any(), any()) } returns null

        viewModel.retryDoubanSync()
        advanceUntilIdle()

        // findDoubanId 返回 null → 不设置 doubanIdForSync，发射 Toast，retryable 保持
        assertThat(viewModel.uiState.value.doubanIdForSync).isNull()
        // pendingDoubanAction 保留（retryDoubanSync 不清除，syncDoubanMark 未被调用）
        assertThat(viewModel.uiState.value.pendingDoubanAction).isEqualTo(DoubanSyncAction.WISH)
    }


    @Test
    fun retryDoubanSync_已登录豆瓣_同步成功_retryable为false() = runTest {
        setPrivateField("currentTraktId", 100)
        setPrivateField("currentMediaType", MediaType.MOVIE)
        setUiState {
            it.copy(
                pendingDoubanAction = DoubanSyncAction.WISH,
                doubanIdForSync = "1234567"
            )
        }
        val cred = mockk<DoubanCredentials>(relaxed = true)
        every { cred.cookie } returns "fake_cookie"
        every { doubanAuthStorage.getCredentials() } returns cred
        coEvery { doubanRepository.fetchCsrfToken(any(), any()) } returns "fake_ck"
        coEvery { doubanRepository.markInterestByCk(any(), any(), any(), any()) } returns
            com.tracktosearch.data.remote.douban.MarkWriteResult(success = true, statusCode = 200, message = "ok")

        viewModel.retryDoubanSync()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.doubanSyncRetryable).isFalse()
        assertThat(viewModel.uiState.value.pendingDoubanAction).isNull()
    }

    @Test
    fun retryDoubanSync_已登录豆瓣_同步失败_retryable为true() = runTest {
        setPrivateField("currentTraktId", 100)
        setPrivateField("currentMediaType", MediaType.MOVIE)
        setUiState {
            it.copy(
                pendingDoubanAction = DoubanSyncAction.WISH,
                doubanIdForSync = "1234567"
            )
        }
        val cred = mockk<DoubanCredentials>(relaxed = true)
        every { cred.cookie } returns "fake_cookie"
        every { doubanAuthStorage.getCredentials() } returns cred
        coEvery { doubanRepository.fetchCsrfToken(any(), any()) } returns "fake_ck"
        coEvery { doubanRepository.markInterestByCk(any(), any(), any(), any()) } returns
            com.tracktosearch.data.remote.douban.MarkWriteResult(success = false, statusCode = 500, message = "fail")

        viewModel.retryDoubanSync()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.doubanSyncRetryable).isTrue()
        assertThat(viewModel.uiState.value.pendingDoubanAction).isEqualTo(DoubanSyncAction.WISH)
    }

    // ==================== onCleared ====================

    @Test
    fun onCleared_currentTraktId大于0时清理静态缓存() {
        setPrivateField("currentTraktId", 12345)
        // 放入一个缓存条目（用 Any 类型避免类型不匹配）
        val cacheField = DetailViewModel::class.java.getDeclaredField("detailCache")
        cacheField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val cache = cacheField.get(null) as MutableMap<Any, *>
        val detailCacheKeyConstructor = Class.forName(
            "com.tracktosearch.ui.screen.detail.DetailCacheKey"
        ).getDeclaredConstructor(
            Int::class.javaPrimitiveType!!,
            Int::class.javaPrimitiveType!!,
            String::class.java,
            MediaType::class.java
        )
        detailCacheKeyConstructor.isAccessible = true
        val cacheKey = detailCacheKeyConstructor.newInstance(12345, 0, null, MediaType.MOVIE)
        setPrivateField("currentDetailCacheKey", cacheKey)
        // 构造一个最小 CachedDetailData（用反射创建）
        val cachedDataClass = DetailViewModel.Companion::class.java
            .declaredClasses.find { it.simpleName == "CachedDetailData" }!!
        val constructor = cachedDataClass.declaredConstructors.first()
        constructor.isAccessible = true
        // CachedDetailData 参数：uiState, allResources, currentKeyword, currentOriginalTitle,
        // currentImdbId, currentTraktRating(Double), currentTmdbId(Int),
        // currentMediaType(MediaType), currentCollectionId(Int)
        val emptyState = DetailUiState()
        val args = arrayOfNulls<Any>(constructor.parameterCount)
        args[0] = emptyState // uiState
        for (i in 1 until args.size) {
            args[i] = when (constructor.parameterTypes[i]) {
                Int::class.javaPrimitiveType -> 0
                Double::class.javaPrimitiveType -> 0.0
                String::class.java -> ""
                List::class.java -> emptyList<Any>()
                MediaType::class.java -> MediaType.MOVIE
                else -> null
            }
        }
        val cachedData = constructor.newInstance(*args)
        @Suppress("UNCHECKED_CAST")
        (cacheField.get(null) as MutableMap<Any, Any>)[cacheKey] = cachedData
        assertThat(cache).containsKey(cacheKey)

        callOnCleared()

        assertThat(cache).doesNotContainKey(cacheKey)
    }



    // ==================== updatePosterColor 补充 ====================



    @Test
    fun dualLoginDoubanItemWithoutTraktId_usesDoubanForWatchlist() = runTest {
        setupDoubanBackedTraktDetail()
        every { doubanAuthStorage.getCredentials() } returns DoubanCredentials("user", "fake_cookie")
        coEvery { doubanRepository.markWish("db-1", "fake_cookie") } returns true
        advanceUntilIdle()

        viewModel.toggleWatchlist()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.isMarkedWatchlist).isTrue()
        coVerify { doubanRepository.markWish("db-1", "fake_cookie") }
        coVerify(exactly = 0) { traktRepository.addToWatchlist(any(), any(), any()) }
        coVerify(exactly = 0) { traktRepository.removeFromWatchlist(any(), any(), any()) }
    }

    @Test
    fun dualLoginDoubanItem_removeWishFailure_keepsRetryAction() = runTest {
        setupDoubanBackedTraktDetail()
        every { doubanAuthStorage.getCredentials() } returns DoubanCredentials("user", "fake_cookie")
        coEvery { doubanSyncedItemDao.deleteByDoubanId("db-1") } returns Unit
        coEvery { doubanRepository.removeInterest("db-1", "fake_cookie") } returns false
        setUiState { it.copy(isMarkedWatchlist = true) }

        viewModel.toggleWatchlist()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.isMarkedWatchlist).isFalse()
        assertThat(viewModel.uiState.value.doubanSyncRetryable).isTrue()
        assertThat(viewModel.uiState.value.pendingDoubanAction).isEqualTo(DoubanSyncAction.REMOVE_WISH)
        coVerify(exactly = 1) { doubanRepository.removeInterest("db-1", "fake_cookie") }
    }

    @Test
    fun dualLoginDoubanItemWithoutTraktId_usesDoubanForWatched() = runTest {
        setupDoubanBackedTraktDetail()
        every { doubanAuthStorage.getCredentials() } returns DoubanCredentials("user", "fake_cookie")
        coEvery { doubanRepository.markCollect("db-1", "fake_cookie", null) } returns true
        advanceUntilIdle()

        viewModel.toggleWatched()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.isMarkedWatched).isTrue()
        coVerify { doubanRepository.markCollect("db-1", "fake_cookie", null) }
        coVerify(exactly = 0) { traktRepository.markAsWatched(any(), any(), any()) }
        coVerify(exactly = 0) { traktRepository.removeWatched(any(), any(), any()) }
    }

    @Test
    fun dualLoginDoubanItemWithoutTraktId_usesDoubanForRatingAndComment() = runTest {
        setupDoubanBackedTraktDetail()
        every { doubanAuthStorage.getCredentials() } returns DoubanCredentials("user", "fake_cookie")
        coEvery { doubanRepository.fetchCsrfToken("db-1", "fake_cookie") } returns "fake_ck"
        coEvery {
            doubanRepository.markWatchedWithRating("db-1", "fake_cookie", "fake_ck", any(), any())
        } returns MarkWriteResult(success = true, statusCode = 200, message = "ok")
        advanceUntilIdle()

        viewModel.setRating(8)
        advanceUntilIdle()
        viewModel.setRatingWithComment(10, "note")
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.userRating).isEqualTo(10)
        coVerify(exactly = 2) { doubanRepository.markWatchedWithRating(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { traktRepository.addRating(any(), any(), any()) }
        coVerify(exactly = 0) { traktRepository.postComment(any(), any(), any()) }
    }

    @Test
    fun dualLoginDoubanItemWithoutTraktId_usesDoubanForRemovingRating() = runTest {
        setupDoubanBackedTraktDetail(userRating = 8)
        every { doubanAuthStorage.getCredentials() } returns DoubanCredentials("user", "fake_cookie")
        coEvery { doubanRepository.markInterest("db-1", "collect", "fake_cookie", null) } returns true
        advanceUntilIdle()

        viewModel.removeRating()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.userRating).isNull()
        coVerify { doubanRepository.markInterest("db-1", "collect", "fake_cookie", null) }
        coVerify(exactly = 0) { traktRepository.removeRating(any(), any()) }
    }

    @Test
    fun doubanBackedItemSubmitMarkWatchedDoesNotCallTrakt() = runTest {
        setupDoubanBackedTraktDetail()
        setPrivateField("currentMediaType", MediaType.SHOW)
        setUiState { it.copy(showMarkWatchedDialog = true) }

        viewModel.submitMarkWatched(listOf(101, 102))
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.showMarkWatchedDialog).isFalse()
        assertThat(viewModel.uiState.value.isMarkingWatched).isFalse()
        assertThat(viewModel.uiState.value.watchedChanged).isFalse()
        coVerify(exactly = 0) { traktRepository.markEpisodesWatched(any(), any(), any()) }
    }

    @Test
    fun cachedDetailStateDoesNotReplayWatchlistSceneRevision() {
        val cachedState = DetailUiState(watchlistAddedRevision = 7L)

        assertThat(cachedState.withoutTransientSceneState().watchlistAddedRevision).isEqualTo(0L)
    }

    private fun setupDoubanBackedTraktDetail(userRating: Int? = null) {
        setupLoggedInState(traktId = 0)
        setPrivateField("currentDoubanId", "db-1")
        setPrivateField("currentImdbId", "tt1234567")
        setUiState {
            it.copy(
                doubanIdForSync = "db-1",
                userRating = userRating
            )
        }
    }
}
