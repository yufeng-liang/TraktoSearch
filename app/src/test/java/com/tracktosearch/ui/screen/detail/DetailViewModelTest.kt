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
import com.tracktosearch.data.remote.trakt.dto.TraktSyncResponse
import com.tracktosearch.data.remote.trakt.dto.TraktEpisode
import com.tracktosearch.data.repository.MediaType
import com.tracktosearch.data.repository.MultiRatings
import com.tracktosearch.data.repository.RatingsRepository
import com.tracktosearch.data.repository.ResourceRepository
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
@Config(sdk = [33], application = android.app.Application::class)
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
    private lateinit var sessionModeManager: SessionModeManager
    private lateinit var traktConnected: MutableStateFlow<Boolean>
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
        sessionModeManager = mockk(relaxed = true)
        traktConnected = MutableStateFlow(false)
        every { sessionModeManager.traktConnected } returns traktConnected
        every { sessionModeManager.sessionMode } returns flowOf(SessionMode.TRAKT)
        posterColorExtractor = mockk(relaxed = true)
        userReviewRepository = mockk(relaxed = true)

        // 3. init 块副作用 stub
        every { tokenStorage.accessToken } returns flowOf(null)
        every { detailSectionStorage.sectionConfigs } returns MutableStateFlow(emptyList())
        every { doubanAuthStorage.getCredentials() } returns null
        // localizeEpisodeTitles 会调用 languageStorage.language.first()，
        // relaxed mock 的 Flow 调用 first() 会抛异常，全局 stub 为英文让该方法提前 return
        every { languageStorage.language } returns MutableStateFlow(LanguageStorage.LANGUAGE_ENGLISH)

        // 4. resourceRepository.filterItems 默认返回空列表
        every { resourceRepository.filterItems(any(), any(), any()) } returns emptyList()

        viewModel = DetailViewModel(
            tmdbRepository, traktRepository, resourceRepository, ratingsRepository,
            viewedItemStorage, commentTranslator, tokenStorage, detailSectionStorage,
            languageStorage, doubanRepository, doubanAuthStorage, doubanSyncedItemDao,
            sessionModeManager,
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
        traktConnected.value = true
    }

    @Test
    fun `仅登录豆瓣时详情页不查询Trakt用户评分`() = runTest {
        setupLoggedInState()
        traktConnected.value = false

        val method = DetailViewModel::class.java.getDeclaredMethod("fetchUserRating")
        method.isAccessible = true
        method.invoke(viewModel)
        advanceUntilIdle()

        coVerify(exactly = 0) { traktRepository.getUserRating(any(), any()) }
    }

    @Test
    fun `缓存恢复时无效Trakt ID不读取本地或远程用户评分`() = runTest {
        every {
            ratingsRepository.fetchRatingsStream(any(), any(), any())
        } returns flowOf(MultiRatings())
        coEvery { viewedItemStorage.getViewedUrls() } returns emptySet()

        viewModel.loadDetail(
            traktId = 0,
            tmdbId = 0,
            title = "Cached fallback",
            mediaType = MediaType.MOVIE
        )
        advanceUntilIdle()

        setPrivateField("currentDetailCacheKey", null)
        viewModel.loadDetail(
            traktId = 0,
            tmdbId = 0,
            title = "Cached fallback",
            mediaType = MediaType.MOVIE
        )
        advanceUntilIdle()

        coVerify(exactly = 0) { userReviewRepository.getReview(any()) }
        coVerify(exactly = 0) { traktRepository.getUserRating(any(), any()) }
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
        assertThat(viewModel.uiState.value.watchlistChanged).isTrue()
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

    // ==================== 标记操作补充测试（成功路径/失败回滚/复合分支）====================

    /**
     * 测试点17：setRating 已登录成功添加评分
     */
    @Test
    fun `setRating_已登录_成功添加评分`() = runTest {
        setupLoggedInState()
        coEvery { traktRepository.addRating(any(), any(), any()) } returns Result.success(Unit)

        viewModel.setRating(8)
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.userRating).isEqualTo(8)
        assertThat(viewModel.uiState.value.isRating).isFalse()
    }

    /**
     * 测试点18：setRating 同分触发 removeRating
     */
    @Test
    fun `setRating_同分_触发removeRating`() = runTest {
        setupLoggedInState()
        setUiState { it.copy(userRating = 8) }
        coEvery { traktRepository.removeRating(any(), any()) } returns Result.success(Unit)

        viewModel.setRating(8)
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.userRating).isNull()
        assertThat(viewModel.uiState.value.isRating).isFalse()
        coVerify { traktRepository.removeRating(any(), any()) }
    }

    /**
     * 测试点19：setRating 失败 isRating 回滚
     */
    @Test
    fun `setRating_失败_isRating回滚`() = runTest {
        setupLoggedInState()
        coEvery { traktRepository.addRating(any(), any(), any()) } returns Result.failure(Exception("网络错误"))

        viewModel.setRating(8)
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.userRating).isNull()
        assertThat(viewModel.uiState.value.isRating).isFalse()
    }

    /**
     * 测试点20：setRatingWithComment 成功更新评分和短评
     */
    @Test
    fun `setRatingWithComment_成功_更新评分和短评`() = runTest {
        setupLoggedInState()
        coEvery { traktRepository.addRating(any(), any(), any()) } returns Result.success(Unit)
        coEvery { traktRepository.postComment(any(), any(), any()) } returns Result.success(mockk(relaxed = true))

        viewModel.setRatingWithComment(9, "很好看")
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.userRating).isEqualTo(9)
        assertThat(viewModel.uiState.value.userComment).isEqualTo("很好看")
        assertThat(viewModel.uiState.value.isRating).isFalse()
        assertThat(viewModel.uiState.value.showRatingDialog).isFalse()
    }

    /**
     * 测试点21：setRatingWithComment 失败 isRating 回滚
     */
    @Test
    fun `setRatingWithComment_失败_isRating回滚`() = runTest {
        setupLoggedInState()
        coEvery { traktRepository.addRating(any(), any(), any()) } returns Result.failure(Exception("网络错误"))

        viewModel.setRatingWithComment(9, "很好看")
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.userRating).isNull()
        assertThat(viewModel.uiState.value.isRating).isFalse()
    }

    /**
     * 测试点22：setRatingWithComment 空短评不调用 postComment
     */
    @Test
    fun `setRatingWithComment_空短评_不调用postComment`() = runTest {
        setupLoggedInState()
        coEvery { traktRepository.addRating(any(), any(), any()) } returns Result.success(Unit)

        viewModel.setRatingWithComment(7, "")
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.userRating).isEqualTo(7)
        assertThat(viewModel.uiState.value.userComment).isNull()
        coVerify(exactly = 0) { traktRepository.postComment(any(), any(), any()) }
    }

    /**
     * 测试点23：toggleWatched 电视剧弹出标记弹窗
     */
    @Test
    fun `toggleWatched_电视剧_弹出标记弹窗`() {
        setupLoggedInState(mediaType = MediaType.SHOW)
        // 初始 isMarkedWatched = false（默认）

        viewModel.toggleWatched()

        assertThat(viewModel.uiState.value.showMarkWatchedDialog).isTrue()
    }

    /**
     * 测试点24：toggleWatched 失败状态回滚
     */
    @Test
    fun `toggleWatched_失败_状态回滚`() = runTest {
        setupLoggedInState()
        coEvery { traktRepository.markAsWatched(any(), any()) } returns Result.failure(Exception("网络错误"))

        viewModel.toggleWatched()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.isMarkingWatched).isFalse()
        assertThat(viewModel.uiState.value.watchedChanged).isFalse()
        assertThat(viewModel.uiState.value.watchlistChanged).isFalse()
        assertThat(viewModel.uiState.value.isMarkedWatched).isFalse()
    }

    /**
     * 测试点25：toggleWatched 取消已看失败状态回滚
     */
    @Test
    fun `toggleWatched_取消已看失败_状态回滚`() = runTest {
        setupLoggedInState()
        setUiState { it.copy(isMarkedWatched = true) }
        coEvery { traktRepository.removeWatched(any(), any(), any()) } returns Result.failure(Exception("网络错误"))

        viewModel.toggleWatched()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.isMarkingWatched).isFalse()
        assertThat(viewModel.uiState.value.isMarkedWatched).isTrue()
    }

    /**
     * 测试点26：toggleWatchlist 已看→想看（简化后走通用分支，依赖 addToWatchlist 内部副操作）
     */
    @Test
    fun `toggleWatchlist_已看→想看复合分支`() = runTest {
        setupLoggedInState()
        setUiState { it.copy(isMarkedWatched = true, isMarkedWatchlist = false) }
        // addToWatchlist 内部已有 removeFromHistory 副操作，UI 不再单独调 removeWatched
        coEvery { traktRepository.addToWatchlist(any(), any(), any()) } returns Result.success(mockk(relaxed = true))

        viewModel.toggleWatchlist()
        advanceUntilIdle()

        // 不再调用 removeWatched
        coVerify(exactly = 0) { traktRepository.removeWatched(any(), any(), any()) }
        // 直接调用 addToWatchlist
        coVerify(exactly = 1) { traktRepository.addToWatchlist(any(), any(), any()) }
        // 乐观设置已看为 false（副操作失败由下次全量拉取纠正）
        assertThat(viewModel.uiState.value.isMarkedWatched).isFalse()
        assertThat(viewModel.uiState.value.isMarkedWatchlist).isTrue()
        assertThat(viewModel.uiState.value.isMarkingWatchlist).isFalse()
        assertThat(viewModel.uiState.value.isMarkingWatched).isFalse()
    }

    /**
     * 测试点27：toggleWatchlist 已看→想看 addToWatchlist 失败回滚
     */
    @Test
    fun `toggleWatchlist_已看→想看_addToWatchlist失败_回滚`() = runTest {
        setupLoggedInState()
        setUiState { it.copy(isMarkedWatched = true, isMarkedWatchlist = false) }
        coEvery { traktRepository.addToWatchlist(any(), any(), any()) } returns Result.failure(Exception("网络错误"))

        viewModel.toggleWatchlist()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.isMarkingWatchlist).isFalse()
        assertThat(viewModel.uiState.value.isMarkingWatched).isFalse()
        // 失败时不改变已标记状态
        assertThat(viewModel.uiState.value.isMarkedWatched).isTrue()
        assertThat(viewModel.uiState.value.isMarkedWatchlist).isFalse()
    }

    /**
     * 测试点29：toggleWatchlist 普通添加失败状态回滚
     */
    @Test
    fun `toggleWatchlist_添加失败_状态回滚`() = runTest {
        setupLoggedInState()
        coEvery { traktRepository.addToWatchlist(any(), any(), any()) } returns Result.failure(Exception("网络错误"))

        viewModel.toggleWatchlist()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.isMarkingWatchlist).isFalse()
        assertThat(viewModel.uiState.value.isMarkedWatchlist).isFalse()
    }

    /**
     * 测试点30：toggleWatchlist 普通取消失败状态回滚
     */
    @Test
    fun `toggleWatchlist_取消失败_状态回滚`() = runTest {
        setupLoggedInState()
        setUiState { it.copy(isMarkedWatchlist = true) }
        coEvery { traktRepository.removeFromWatchlist(any(), any(), any()) } returns Result.failure(Exception("网络错误"))

        viewModel.toggleWatchlist()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.isMarkingWatchlist).isFalse()
        assertThat(viewModel.uiState.value.isMarkedWatchlist).isTrue()
    }

    /**
     * 测试点31：submitMarkWatched 成功批量标记集为已看
     */
    @Test
    fun `submitMarkWatched_成功_批量标记集为已看`() = runTest {
        setupLoggedInState(mediaType = MediaType.SHOW)
        coEvery { traktRepository.markEpisodesWatched(any(), any(), any()) } returns Result.success(Unit)
        coEvery { traktRepository.getShowWatchedProgress(any()) } returns Result.failure(Exception("测试跳过"))

        viewModel.submitMarkWatched(listOf(1, 2, 3))
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.isMarkedWatched).isTrue()
        assertThat(viewModel.uiState.value.isMarkedWatchlist).isFalse()
        assertThat(viewModel.uiState.value.isMarkingWatched).isFalse()
        assertThat(viewModel.uiState.value.showRatingDialog).isTrue()
        assertThat(viewModel.uiState.value.pendingDoubanAction).isEqualTo(DoubanSyncAction.COLLECT)
    }

    /**
     * 测试点32：submitMarkWatched 失败状态回滚
     */
    @Test
    fun `submitMarkWatched_失败_状态回滚`() = runTest {
        setupLoggedInState(mediaType = MediaType.SHOW)
        coEvery { traktRepository.markEpisodesWatched(any(), any(), any()) } returns Result.failure(Exception("网络错误"))

        viewModel.submitMarkWatched(listOf(1, 2, 3))
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.isMarkingWatched).isFalse()
        assertThat(viewModel.uiState.value.watchedChanged).isFalse()
        assertThat(viewModel.uiState.value.isMarkedWatched).isFalse()
    }

    /**
     * 测试点33：toggleEpisodeWatched 标记单集已看
     */
    @Test
    fun `toggleEpisodeWatched_标记单集已看`() = runTest {
        setupLoggedInState(mediaType = MediaType.SHOW)
        coEvery { traktRepository.markEpisodeWatched(any()) } returns Result.success(Unit)

        viewModel.toggleEpisodeWatched(seasonNumber = 1, episodeNumber = 1, episodeTraktId = 100)
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.watchedEpisodeNumbers[1]).contains(1)
        assertThat(viewModel.uiState.value.togglingEpisode).isNull()
    }

    /**
     * 测试点34：toggleEpisodeWatched 取消单集已看
     */
    @Test
    fun `toggleEpisodeWatched_取消单集已看`() = runTest {
        setupLoggedInState(mediaType = MediaType.SHOW)
        setUiState { it.copy(watchedEpisodeNumbers = mapOf(1 to setOf(1, 2))) }
        coEvery {
            traktRepository.unmarkEpisodeWatched(any(), any(), any(), any(), any(), any())
        } returns Result.success(Unit)

        viewModel.toggleEpisodeWatched(seasonNumber = 1, episodeNumber = 1, episodeTraktId = 100)
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.watchedEpisodeNumbers[1]).doesNotContain(1)
        assertThat(viewModel.uiState.value.watchedEpisodeNumbers[1]).contains(2)
        assertThat(viewModel.uiState.value.togglingEpisode).isNull()
    }

    /**
     * 测试点35：toggleEpisodeWatched 失败回滚
     */
    @Test
    fun `toggleEpisodeWatched_失败_回滚`() = runTest {
        setupLoggedInState(mediaType = MediaType.SHOW)
        coEvery { traktRepository.markEpisodeWatched(any()) } returns Result.failure(Exception("网络错误"))

        viewModel.toggleEpisodeWatched(seasonNumber = 1, episodeNumber = 1, episodeTraktId = 100)
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.togglingEpisode).isNull()
        assertThat(viewModel.uiState.value.watchedEpisodeNumbers).isEmpty()
    }

    /**
     * 测试点36：toggleEpisodeWatched 未登录弹出登录提示
     */
    @Test
    fun `toggleEpisodeWatched_未登录_弹出登录提示`() {
        // getCachedAccessToken 默认返回 null（relaxed mock）
        viewModel.toggleEpisodeWatched(seasonNumber = 1, episodeNumber = 1, episodeTraktId = 100)

        assertThat(viewModel.uiState.value.showLoginPrompt).isTrue()
    }

    /**
     * 测试点37：loadEpisodesForMarkWatched 加载集信息
     */
    @Test
    fun `loadEpisodesForMarkWatched_加载集信息`() = runTest {
        setupLoggedInState()
        val episodes = listOf(mockk<TraktEpisode>(relaxed = true))
        coEvery { traktRepository.getSeasonEpisodes(any(), any()) } returns Result.success(episodes)

        viewModel.loadEpisodesForMarkWatched(1)
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.episodes).containsKey(1)
    }

    /**
     * 测试点38：loadEpisodesForMarkWatched 已加载则跳过
     */
    @Test
    fun `loadEpisodesForMarkWatched_已加载则跳过`() = runTest {
        setupLoggedInState()
        setUiState { it.copy(episodes = mapOf(1 to emptyList())) }
        coEvery { traktRepository.getSeasonEpisodes(any(), any()) } returns Result.success(emptyList())

        viewModel.loadEpisodesForMarkWatched(1)
        advanceUntilIdle()

        coVerify(exactly = 0) { traktRepository.getSeasonEpisodes(any(), any()) }
    }

    @Test
    fun `loadMoreComments_无效TraktId不请求Trakt评论`() = runTest {
        setupLoggedInState(traktId = 0)
        setPrivateField("currentSessionMode", SessionMode.TRAKT)
        setUiState { it.copy(hasMoreComments = true, commentPage = 1, tmdbCommentPage = 0) }

        viewModel.loadMoreComments()
        advanceUntilIdle()

        coVerify(exactly = 0) { traktRepository.getComments(any(), any(), any(), any()) }
        assertThat(viewModel.uiState.value.isLoadingMoreComments).isFalse()
    }

    @Test
    fun `invalid trakt id without douban id short circuits every trakt write entry`() = runTest {
        setupLoggedInState(traktId = 0, mediaType = MediaType.SHOW)
        setPrivateField("currentDoubanId", null)
        setPrivateField("currentSessionMode", SessionMode.TRAKT)
        setUiState {
            it.copy(
                userRating = 8,
                isMarkedWatched = true,
                isMarkedWatchlist = true,
                watchedEpisodeNumbers = mapOf(1 to setOf(1))
            )
        }

        coEvery { traktRepository.addRating(any(), any(), any()) } returns Result.success(Unit)
        coEvery { traktRepository.postComment(any(), any(), any()) } returns Result.success(mockk(relaxed = true))
        coEvery { traktRepository.removeRating(any(), any()) } returns Result.success(Unit)
        coEvery { traktRepository.removeWatched(any(), any(), any()) } returns Result.success(TraktSyncResponse())
        coEvery { traktRepository.markAsWatched(any(), any()) } returns Result.success(TraktSyncResponse())
        coEvery { traktRepository.addToWatchlist(any(), any(), any()) } returns Result.success(TraktSyncResponse())
        coEvery { traktRepository.removeFromWatchlist(any(), any(), any()) } returns Result.success(TraktSyncResponse())
        coEvery { traktRepository.markEpisodesWatched(any(), any(), any()) } returns Result.success(Unit)
        coEvery { traktRepository.markEpisodeWatched(any()) } returns Result.success(Unit)
        coEvery {
            traktRepository.unmarkEpisodeWatched(any(), any(), any(), any(), any(), any())
        } returns Result.success(Unit)
        coEvery { traktRepository.getSeasonEpisodes(any(), any()) } returns Result.success(emptyList())

        viewModel.setRating(7)
        viewModel.setRatingWithComment(7, "comment")
        viewModel.removeRating()
        setUiState { it.copy(isMarkedWatched = false) }
        viewModel.toggleWatched()
        setUiState { it.copy(isMarkedWatchlist = false) }
        viewModel.toggleWatchlist()
        setUiState { it.copy(watchedEpisodeNumbers = emptyMap()) }
        viewModel.toggleEpisodeWatched(1, 2, 102)
        setUiState { it.copy(watchedEpisodeNumbers = mapOf(1 to setOf(1))) }
        viewModel.toggleEpisodeWatched(1, 1, 101)
        viewModel.submitMarkWatched(listOf(101, 102))
        viewModel.toggleSeason(2)
        viewModel.loadEpisodesForMarkWatched(2)
        advanceUntilIdle()

        coVerify(exactly = 0) { traktRepository.addRating(any(), any(), any()) }
        coVerify(exactly = 0) { traktRepository.postComment(any(), any(), any()) }
        coVerify(exactly = 0) { traktRepository.removeRating(any(), any()) }
        coVerify(exactly = 0) { traktRepository.removeWatched(any(), any(), any()) }
        coVerify(exactly = 0) { traktRepository.markAsWatched(any(), any()) }
        coVerify(exactly = 0) { traktRepository.addToWatchlist(any(), any(), any()) }
        coVerify(exactly = 0) { traktRepository.removeFromWatchlist(any(), any(), any()) }
        coVerify(exactly = 0) { traktRepository.markEpisodesWatched(any(), any(), any()) }
        coVerify(exactly = 0) { traktRepository.markEpisodeWatched(any()) }
        coVerify(exactly = 0) {
            traktRepository.unmarkEpisodeWatched(any(), any(), any(), any(), any(), any())
        }
        coVerify(exactly = 0) { traktRepository.getSeasonEpisodes(any(), any()) }
        assertThat(viewModel.uiState.value.userRating).isEqualTo(8)
        assertThat(viewModel.uiState.value.isMarkedWatched).isFalse()
        assertThat(viewModel.uiState.value.isMarkedWatchlist).isFalse()
    }
}
