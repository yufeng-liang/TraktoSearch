package com.tracktosearch.ui.screen.detail

import androidx.compose.ui.graphics.Color
import com.google.common.truth.Truth.assertThat
import com.tracktosearch.data.local.DetailSectionStorage
import com.tracktosearch.data.local.DoubanAuthStorage
import com.tracktosearch.data.local.LanguageStorage
import com.tracktosearch.data.local.TokenStorage
import com.tracktosearch.data.local.ViewedItemStorage
import com.tracktosearch.data.local.db.DoubanSyncedItemDao
import com.tracktosearch.data.remote.douban.DoubanRepository
import com.tracktosearch.data.remote.dto.DiskType
import com.tracktosearch.data.repository.MediaType
import com.tracktosearch.data.repository.RatingsRepository
import com.tracktosearch.data.repository.ResourceRepository
import com.tracktosearch.data.repository.TmdbRepository
import com.tracktosearch.data.repository.TraktRepository
import com.tracktosearch.data.repository.UserReviewRepository
import com.tracktosearch.data.util.CommentTranslator
import com.tracktosearch.data.util.PosterColorExtractor
import com.tracktosearch.test.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
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
 * DetailViewModel 单元测试。
 *
 * 测试聚焦于同步方法和简单异步方法，避免测试 loadDetail 完整流程（太复杂）。
 *
 * 测试策略：
 * 1. @Before 清理 companion object 的 detailCache 静态缓存，避免测试间状态泄漏
 * 2. @Before stub init 块副作用：tokenStorage.accessToken=flowOf(null)、
 *    detailSectionStorage.sectionConfigs=MutableStateFlow(emptyList())、
 *    doubanAuthStorage.getCredentials()=null（避免 syncDoubanMark 执行复杂逻辑）
 * 3. relaxed mock 对返回 Result<T> 的方法默认返回 Result.failure(NPE)，必须显式 stub
 * 4. 通过反射设置 private 字段（currentTraktId/currentMediaType/isLoggedIn 等）和 _uiState
 * 5. 异步方法用 runTest + advanceUntilIdle
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DetailViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    // 14个 mock 依赖
    private lateinit var tmdbRepository: TmdbRepository
    private lateinit var traktRepository: TraktRepository
    private lateinit var resourceRepository: ResourceRepository
    private lateinit var ratingsRepository: RatingsRepository
    private lateinit var viewedItemStorage: ViewedItemStorage
    private lateinit var commentTranslator: CommentTranslator
    private lateinit var tokenStorage: TokenStorage
    private lateinit var detailSectionStorage: DetailSectionStorage
    private lateinit var languageStorage: LanguageStorage
    private lateinit var doubanRepository: DoubanRepository
    private lateinit var doubanAuthStorage: DoubanAuthStorage
    private lateinit var doubanSyncedItemDao: DoubanSyncedItemDao
    private lateinit var posterColorExtractor: PosterColorExtractor
    private lateinit var userReviewRepository: UserReviewRepository

    private lateinit var viewModel: DetailViewModel

    @Before
    fun setup() {
        // 1. 清理 companion object 静态缓存
        val cacheField = DetailViewModel::class.java.getDeclaredField("detailCache")
        cacheField.isAccessible = true
        val cache = cacheField.get(null) as MutableMap<*, *>
        cache.clear()

        // 2. 创建所有 mock
        tmdbRepository = mockk(relaxed = true)
        traktRepository = mockk(relaxed = true)
        resourceRepository = mockk(relaxed = true)
        ratingsRepository = mockk(relaxed = true)
        viewedItemStorage = mockk(relaxed = true)
        commentTranslator = mockk(relaxed = true)
        tokenStorage = mockk(relaxed = true)
        detailSectionStorage = mockk(relaxed = true)
        languageStorage = mockk(relaxed = true)
        doubanRepository = mockk(relaxed = true)
        doubanAuthStorage = mockk(relaxed = true)
        doubanSyncedItemDao = mockk(relaxed = true)
        posterColorExtractor = mockk(relaxed = true)
        userReviewRepository = mockk(relaxed = true)

        // 3. init 块副作用 stub
        every { tokenStorage.accessToken } returns flowOf(null)
        every { detailSectionStorage.sectionConfigs } returns MutableStateFlow(emptyList())
        every { doubanAuthStorage.getCredentials() } returns null

        // 4. resourceRepository.filterItems 默认返回空列表
        every { resourceRepository.filterItems(any(), any(), any()) } returns emptyList()

        viewModel = DetailViewModel(
            tmdbRepository, traktRepository, resourceRepository, ratingsRepository,
            viewedItemStorage, commentTranslator, tokenStorage, detailSectionStorage,
            languageStorage, doubanRepository, doubanAuthStorage, doubanSyncedItemDao,
            posterColorExtractor, userReviewRepository
        )
    }

    // ==================== 反射辅助 ====================

    /** 通过反射设置 ViewModel 的 private 字段 */
    private fun setPrivateField(fieldName: String, value: Any?) {
        val field = DetailViewModel::class.java.getDeclaredField(fieldName)
        field.isAccessible = true
        field.set(viewModel, value)
    }

    /** 通过反射修改 _uiState 的值 */
    @Suppress("UNCHECKED_CAST")
    private fun setUiState(transform: (DetailUiState) -> DetailUiState) {
        val field = DetailViewModel::class.java.getDeclaredField("_uiState")
        field.isAccessible = true
        val stateFlow = field.get(viewModel) as MutableStateFlow<DetailUiState>
        stateFlow.value = transform(stateFlow.value)
    }

    /** 配置已登录状态：设置内部字段 + stub getCachedAccessToken */
    private fun setupLoggedInState(traktId: Int = 100, mediaType: MediaType = MediaType.MOVIE) {
        setPrivateField("currentTraktId", traktId)
        setPrivateField("currentMediaType", mediaType)
        setPrivateField("currentTmdbId", 200)
        setPrivateField("isLoggedIn", true)
        every { tokenStorage.getCachedAccessToken() } returns "fake-token"
    }

    // ==================== 同步方法测试（7个）====================

    /**
     * 测试点1：updatePosterColor 更新海报主色
     */
    @Test
    fun `updatePosterColor_更新海报主色`() {
        viewModel.updatePosterColor(Color.Red)

        assertThat(viewModel.uiState.value.posterDominantColor).isEqualTo(Color.Red)
    }

    /**
     * 测试点2：toggleSource 添加新资源源
     */
    @Test
    fun `toggleSource_添加新资源源`() {
        // 验证初始状态
        assertThat(viewModel.uiState.value.enabledSources).isEqualTo(ResourceRepository.ALL_SOURCES)

        // 添加一个不在 ALL_SOURCES 中的源
        viewModel.toggleSource("new_source")

        assertThat(viewModel.uiState.value.enabledSources).contains("new_source")
    }

    /**
     * 测试点3：toggleDiskType 切换磁盘类型（移除已启用的类型）
     */
    @Test
    fun `toggleDiskType_移除已启用的磁盘类型`() {
        // 验证初始状态
        assertThat(viewModel.uiState.value.enabledDiskTypes).isEqualTo(ResourceRepository.ALL_DISK_TYPES)

        // 切换（移除）一个已启用的类型
        viewModel.toggleDiskType(DiskType.QUARK)

        assertThat(viewModel.uiState.value.enabledDiskTypes).doesNotContain(DiskType.QUARK)
    }

    /**
     * 测试点4：toggleSeason 展开季
     */
    @Test
    fun `toggleSeason_展开季`() = runTest {
        setPrivateField("currentTraktId", 100)
        coEvery { traktRepository.getSeasonEpisodes(any(), any()) } returns Result.success(emptyList())

        viewModel.toggleSeason(1)
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.expandedSeasons).contains(1)
    }

    /**
     * 测试点5：dismissMarkWatchedDialog 关闭标记已看弹窗
     */
    @Test
    fun `dismissMarkWatchedDialog_关闭标记已看弹窗`() {
        // 先设置 showMarkWatchedDialog = true
        setUiState { it.copy(showMarkWatchedDialog = true) }
        assertThat(viewModel.uiState.value.showMarkWatchedDialog).isTrue()

        viewModel.dismissMarkWatchedDialog()

        assertThat(viewModel.uiState.value.showMarkWatchedDialog).isFalse()
    }

    /**
     * 测试点6：dismissRatingDialog 关闭评分弹窗
     */
    @Test
    fun `dismissRatingDialog_关闭评分弹窗`() {
        // 先设置 showRatingDialog = true
        setUiState { it.copy(showRatingDialog = true) }
        assertThat(viewModel.uiState.value.showRatingDialog).isTrue()

        viewModel.dismissRatingDialog()

        assertThat(viewModel.uiState.value.showRatingDialog).isFalse()
        assertThat(viewModel.uiState.value.pendingDoubanAction).isNull()
    }

    /**
     * 测试点7：dismissLoginPrompt 关闭登录提示
     */
    @Test
    fun `dismissLoginPrompt_关闭登录提示`() {
        // 先设置 showLoginPrompt = true
        setUiState { it.copy(showLoginPrompt = true) }
        assertThat(viewModel.uiState.value.showLoginPrompt).isTrue()

        viewModel.dismissLoginPrompt()

        assertThat(viewModel.uiState.value.showLoginPrompt).isFalse()
    }

    // ==================== 异步方法测试（9个）====================

    /**
     * 测试点8：toggleWatchlist 未登录时弹出登录提示
     */
    @Test
    fun `toggleWatchlist_未登录_弹出登录提示`() {
        // getCachedAccessToken 默认返回 null（relaxed mock）
        viewModel.toggleWatchlist()

        assertThat(viewModel.uiState.value.showLoginPrompt).isTrue()
    }

    /**
     * 测试点9：toggleWatchlist 已登录添加想看成功
     */
    @Test
    fun `toggleWatchlist_已登录_添加想看成功`() = runTest {
        setupLoggedInState(traktId = 100, mediaType = MediaType.MOVIE)
        // 初始 isMarkedWatchlist = false（默认）
        coEvery {
            traktRepository.addToWatchlist(any(), any(), any())
        } returns Result.success(mockk(relaxed = true))

        viewModel.toggleWatchlist()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.isMarkedWatchlist).isTrue()
        assertThat(viewModel.uiState.value.isMarkingWatchlist).isFalse()
    }

    /**
     * 测试点10：toggleWatchlist 已登录取消想看成功
     */
    @Test
    fun `toggleWatchlist_已登录_取消想看成功`() = runTest {
        setupLoggedInState()
        // 设置初始 isMarkedWatchlist = true
        setUiState { it.copy(isMarkedWatchlist = true) }
        coEvery {
            traktRepository.removeFromWatchlist(any(), any(), any())
        } returns Result.success(mockk(relaxed = true))

        viewModel.toggleWatchlist()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.isMarkedWatchlist).isFalse()
        assertThat(viewModel.uiState.value.isMarkingWatchlist).isFalse()
    }

    /**
     * 测试点11：toggleWatched 未登录时弹出登录提示
     */
    @Test
    fun `toggleWatched_未登录_弹出登录提示`() {
        // getCachedAccessToken 默认返回 null（relaxed mock）
        viewModel.toggleWatched()

        assertThat(viewModel.uiState.value.showLoginPrompt).isTrue()
    }

    /**
     * 测试点12：toggleWatched 已登录电影标记已看成功
     */
    @Test
    fun `toggleWatched_已登录_电影标记已看成功`() = runTest {
        setupLoggedInState(traktId = 100, mediaType = MediaType.MOVIE)
        // 初始 isMarkedWatched = false（默认）
        coEvery {
            traktRepository.markAsWatched(any(), any(), any())
        } returns Result.success(mockk(relaxed = true))

        viewModel.toggleWatched()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.isMarkedWatched).isTrue()
        assertThat(viewModel.uiState.value.isMarkingWatched).isFalse()
        assertThat(viewModel.uiState.value.showRatingDialog).isTrue()
    }

    /**
     * 测试点13：toggleWatched 已登录取消已看成功
     */
    @Test
    fun `toggleWatched_已登录_取消已看成功`() = runTest {
        setupLoggedInState()
        // 设置初始 isMarkedWatched = true
        setUiState { it.copy(isMarkedWatched = true) }
        coEvery {
            traktRepository.removeWatched(any(), any(), any())
        } returns Result.success(mockk(relaxed = true))

        viewModel.toggleWatched()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.isMarkedWatched).isFalse()
        assertThat(viewModel.uiState.value.isMarkedWatchlist).isTrue()
    }

    /**
     * 测试点14：setRating 未登录时弹出登录提示
     */
    @Test
    fun `setRating_未登录_弹出登录提示`() = runTest {
        // 不设置 isLoggedIn（默认 false）
        viewModel.setRating(8)
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.showLoginPrompt).isTrue()
    }

    /**
     * 测试点15：removeRating 取消评分成功
     */
    @Test
    fun `removeRating_取消评分成功`() = runTest {
        setupLoggedInState()
        // 设置初始 userRating = 8
        setUiState { it.copy(userRating = 8) }
        coEvery {
            traktRepository.removeRating(any(), any())
        } returns Result.success(Unit)

        viewModel.removeRating()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.userRating).isNull()
        assertThat(viewModel.uiState.value.isRating).isFalse()
    }

    /**
     * 测试点16：markResourceViewed 标记资源已查看
     */
    @Test
    fun `markResourceViewed_标记资源已查看`() = runTest {
        viewModel.markResourceViewed("https://example.com")
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.viewedUrls).contains("https://example.com")
        coVerify { viewedItemStorage.markViewed("https://example.com") }
    }
}
