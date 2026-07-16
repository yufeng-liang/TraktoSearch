package com.tracktosearch.ui.screen.listdetail

import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import com.tracktosearch.data.remote.tmdb.dto.TmdbMovieDetail
import com.tracktosearch.data.remote.trakt.dto.TraktIds
import com.tracktosearch.data.remote.trakt.dto.TraktListItemResponse
import com.tracktosearch.data.remote.trakt.dto.TraktMovie
import com.tracktosearch.data.remote.trakt.dto.TraktShow
import com.tracktosearch.data.repository.MediaType
import com.tracktosearch.data.repository.TmdbRepository
import com.tracktosearch.data.repository.TraktRepository
import com.tracktosearch.data.repository.TraktRepository.WatchlistWatchedIds
import com.tracktosearch.test.MainDispatcherRule
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

/**
 * TraktListDetailViewModel 单元测试。
 *
 * 验证列表详情页的核心行为：
 * - 从 SavedStateHandle 读取 listId/listName
 * - retry() 成功/失败/空列表
 * - loadMore() 成功追加 / hasMore=false 时不重复加载
 *
 * 测试策略：所有测试数据使用 tmdbId=0，避免触发增强流程
 * （enhanceMovieItem 在 tmdbId<=0 时直接 return base.copy(isEnhanced=true)，
 * 不调用 tmdbRepository）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TraktListDetailViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val traktRepository = mockk<TraktRepository>(relaxed = true)
    private val tmdbRepository = mockk<TmdbRepository>(relaxed = true)
    private lateinit var viewModel: TraktListDetailViewModel

    private val testListId = 123
    private val testListName = "测试列表"

    // ==================== 测试数据 ====================

    /**
     * tmdbId=0 避免触发增强流程
     * （enhanceMovieItem 在 tmdbId<=0 时直接 return base.copy(isEnhanced=true)）
     */
    private val testMovieItems = listOf(
        TraktListItemResponse(
            rank = 1, id = 1, type = "movie",
            movie = TraktMovie(
                title = "电影A", year = 2024,
                ids = TraktIds(trakt = 101, tmdb = 0, imdb = "tt101")
            )
        ),
        TraktListItemResponse(
            rank = 2, id = 2, type = "movie",
            movie = TraktMovie(
                title = "电影B", year = 2023,
                ids = TraktIds(trakt = 102, tmdb = 0, imdb = "tt102")
            )
        )
    )

    /** 生成指定数量的 movie items（tmdbId=0 避免增强） */
    private fun generateMovieItems(count: Int, startRank: Int = 1): List<TraktListItemResponse> =
        (1..count).map { i ->
            val rank = startRank + i - 1
            TraktListItemResponse(
                rank = rank,
                id = rank,
                type = "movie",
                movie = TraktMovie(
                    title = "电影$rank",
                    year = 2024,
                    ids = TraktIds(trakt = 1000 + rank, tmdb = 0, imdb = "tt${1000 + rank}")
                )
            )
        }

    // ==================== 生命周期 ====================

    @Before
    fun setup() {
        clearMocks(traktRepository, tmdbRepository)

        // 清理磁盘缓存文件，确保 init 不走缓存路径而走 loadItems
        val cacheFile = File(RuntimeEnvironment.getApplication().cacheDir, "list_detail_${testListId}.json")
        if (cacheFile.exists()) cacheFile.delete()

        // init 块会调用这些方法（init 触发 loadItems(1)，返回空列表 → hasMore=false）
        coEvery { traktRepository.getWatchlistWatchedIds() } returns null
        coEvery { traktRepository.loadWatchlistWatchedIds() } returns WatchlistWatchedIds()
        coEvery { traktRepository.getListItems(testListId, 20, 1) } returns Result.success(emptyList())

        val savedStateHandle = SavedStateHandle(mapOf("listId" to testListId, "listName" to testListName))
        viewModel = TraktListDetailViewModel(
            savedStateHandle,
            traktRepository,
            tmdbRepository,
            RuntimeEnvironment.getApplication()
        )
        // 不在此处 advanceUntilIdle：StandardTestDispatcher 下 init 协程处于 pending
        // 由各测试在 runTest 内 advanceUntilIdle 推进
    }

    // ==================== 测试 ====================

    /**
     * 测试点1：构造时从 SavedStateHandle 读取 listId/listName
     * listId/listName 在构造函数中同步赋值，无需 advanceUntilIdle
     */
    @Test
    fun `构造_SavedStateHandle携带listId和listName_uiState正确填充`() = runTest {
        val state = viewModel.uiState.value
        assertThat(state.listId).isEqualTo(testListId)
        assertThat(state.listName).isEqualTo(testListName)
    }

    /**
     * 测试点2：retry() 成功 → isLoading 从 true 变 false，items 填充
     */
    @Test
    fun `retry_获取列表成功_isLoading为false且items填充`() = runTest {
        // 推进 init 协程（init 用空列表桩完成）
        advanceUntilIdle()

        // 重新桩：retry 时返回测试数据
        coEvery { traktRepository.getListItems(testListId, 20, 1) } returns Result.success(testMovieItems)

        viewModel.retry()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertThat(state.isLoading).isFalse()
        assertThat(state.items).hasSize(2)
        assertThat(state.items[0].title).isEqualTo("电影A")
        assertThat(state.items[1].title).isEqualTo("电影B")
        assertThat(state.error).isNull()
    }

    /**
     * 测试点3：retry() 网络失败 → error 非空，isLoading 为 false
     */
    @Test
    fun `retry_网络失败_error非空且isLoading为false`() = runTest {
        advanceUntilIdle()

        coEvery { traktRepository.getListItems(testListId, 20, 1) } returns Result.failure(IOException("网络错误"))

        viewModel.retry()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertThat(state.error).isEqualTo("网络错误")
        assertThat(state.isLoading).isFalse()
        assertThat(state.items).isEmpty()
    }

    /**
     * 测试点4：loadMore() 成功 → 追加更多条目，currentPage 递增
     */
    @Test
    fun `loadMore_有更多数据_追加条目且currentPage递增`() = runTest {
        advanceUntilIdle()

        // 第一页：20 条 → hasMore=true（rawItems.size >= 20）
        val page1Items = generateMovieItems(20)
        coEvery { traktRepository.getListItems(testListId, 20, 1) } returns Result.success(page1Items)
        viewModel.retry()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.items).hasSize(20)
        assertThat(viewModel.uiState.value.hasMore).isTrue()
        assertThat(viewModel.uiState.value.currentPage).isEqualTo(1)

        // 第二页：5 条 → 追加到 25 条
        val page2Items = generateMovieItems(5, startRank = 21)
        coEvery { traktRepository.getListItems(testListId, 20, 2) } returns Result.success(page2Items)
        viewModel.loadMore()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertThat(state.items).hasSize(25) // 20 + 5
        assertThat(state.currentPage).isEqualTo(2)
        assertThat(state.isLoadingMore).isFalse()
    }

    /**
     * 测试点5：loadMore() hasMore=false → 不重复加载（直接 return，不调用 getListItems）
     */
    @Test
    fun `loadMore_hasMore为false_不调用getListItems`() = runTest {
        // init 后 hasMore=false（空列表），currentPage=1
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.hasMore).isFalse()

        viewModel.loadMore()
        advanceUntilIdle()

        // init 调用过一次 getListItems，loadMore 不应再调用
        coVerify(exactly = 1) { traktRepository.getListItems(any(), any(), any()) }
    }

    /**
     * 测试点6：retry() 空列表 → items 为空，hasMore=false（rawItems.size < 20）
     */
    @Test
    fun `retry_返回空列表_items为空且hasMore为false`() = runTest {
        advanceUntilIdle()

        coEvery { traktRepository.getListItems(testListId, 20, 1) } returns Result.success(emptyList())

        viewModel.retry()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertThat(state.items).isEmpty()
        assertThat(state.hasMore).isFalse() // 0 < 20
        assertThat(state.isLoading).isFalse()
        assertThat(state.error).isNull()
    }

    // ==================== enhancement 字段传递测试 ====================
    // 回归测试：tmdbId>0 时 enhanceMovieItem/enhanceShowItem 调用 TMDB，
    // 必须把返回的中文名和海报 URL 正确映射到 ListDetailItem。
    // 之前所有测试用 tmdbId=0 走短路逻辑（base.copy(isEnhanced=true)），从未验证 enhance 字段传递。
    //
    // 注意：电影分支和剧集分支走不同 TMDB 方法，字段映射规则也不同：
    // - 电影：getMovieDetail → TmdbMovieDetail，title 直接覆盖（ListDetailItem 无 displayTitle 字段），
    //   posterUrl = detail.poster_path（相对路径，未拼 IMAGE_BASE_URL）
    // - 剧集：enrichTv → TvEnrichment，title = enrichment.chineseTitle，
    //   posterUrl = enrichment.posterUrl（完整 URL）

    /**
     * 测试：电影列表 tmdbId>0 → enhanceMovieItem 调用 getMovieDetail，
     * 返回的 title、release_date、poster_path 必须正确映射到 ListDetailItem。
     *
     * 注意：电影分支 posterUrl 存的是 TMDB 相对路径（detail.poster_path），
     * 而非完整 URL（与剧集分支不同）。
     */
    @Test
    fun `retry_电影tmdbId大于0_getMovieDetail返回详情_title和posterUrl被覆盖`() = runTest {
        advanceUntilIdle()

        val movieWithTmdb = listOf(
            TraktListItemResponse(
                rank = 1, id = 1, type = "movie",
                movie = TraktMovie(
                    title = "Inception", year = 2010,
                    ids = TraktIds(trakt = 101, tmdb = 27205, imdb = "tt1375666")
                )
            )
        )
        coEvery { traktRepository.getListItems(testListId, 20, 1) } returns Result.success(movieWithTmdb)
        coEvery { tmdbRepository.getMovieDetail(27205) } returns TmdbMovieDetail(
            id = 27205,
            title = "盗梦空间（中文名）",
            original_title = "Inception",
            poster_path = "/abc.jpg", // 相对路径
            overview = "梦境层层",
            release_date = "2010-07-16",
            vote_average = 8.8
        )

        viewModel.retry()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertThat(state.items).hasSize(1)
        val item = state.items[0]
        // Trakt 原始字段保留
        assertThat(item.traktId).isEqualTo(101)
        assertThat(item.tmdbId).isEqualTo(27205)
        assertThat(item.imdbId).isEqualTo("tt1375666")
        // enhance 后字段（注意电影分支 title 直接覆盖，无 displayTitle 字段）
        assertThat(item.title).isEqualTo("盗梦空间（中文名）") // 来自 detail.title
        assertThat(item.year).isEqualTo(2010) // 来自 release_date.take(4)
        assertThat(item.posterUrl).isEqualTo("/abc.jpg") // 相对路径（detail.poster_path）
        assertThat(item.isEnhanced).isTrue()
        coVerify(exactly = 1) { tmdbRepository.getMovieDetail(27205) }
    }

    /**
     * 测试：剧集列表 tmdbId>0 → enhanceShowItem 调用 enrichTv，
     * 返回的 chineseTitle、year、posterUrl 必须正确映射到 ListDetailItem。
     *
     * 注意：剧集分支 posterUrl 是完整 URL（enrichment.posterUrl 已拼 IMAGE_BASE_URL），
     * 与电影分支（相对路径）不同。
     */
    @Test
    fun `retry_剧集tmdbId大于0_enrichTv返回中文名_title和posterUrl被覆盖`() = runTest {
        advanceUntilIdle()

        val showWithTmdb = listOf(
            TraktListItemResponse(
                rank = 1, id = 1, type = "show",
                show = TraktShow(
                    title = "Breaking Bad", year = 2008,
                    ids = TraktIds(trakt = 201, tmdb = 1396, imdb = "tt0903747")
                )
            )
        )
        coEvery { traktRepository.getListItems(testListId, 20, 1) } returns Result.success(showWithTmdb)
        coEvery { tmdbRepository.enrichTv(1396, "Breaking Bad", 2008) } returns
            TmdbRepository.TvEnrichment(
                posterUrl = "https://image.tmdb.org/t/p/w500/breakingbad.jpg",
                chineseTitle = "绝命毒师",
                overview = "高中化学老师制毒",
                genres = "犯罪,剧情",
                year = 2008,
                rating = 9.5
            )

        viewModel.retry()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertThat(state.items).hasSize(1)
        val item = state.items[0]
        // Trakt 原始字段保留
        assertThat(item.traktId).isEqualTo(201)
        assertThat(item.tmdbId).isEqualTo(1396)
        assertThat(item.imdbId).isEqualTo("tt0903747")
        // enhance 后字段
        assertThat(item.title).isEqualTo("绝命毒师") // 来自 enrichment.chineseTitle
        assertThat(item.year).isEqualTo(2008)
        assertThat(item.posterUrl).isEqualTo("https://image.tmdb.org/t/p/w500/breakingbad.jpg") // 完整 URL
        assertThat(item.isEnhanced).isTrue()
        coVerify(exactly = 1) { tmdbRepository.enrichTv(1396, "Breaking Bad", 2008) }
    }

    /**
     * 测试：getMovieDetail 返回 null（TMDB 不可用）时，保留 base 原值，
     * 仅标记 isEnhanced=true，不覆盖任何字段。
     */
    @Test
    fun `retry_电影getMovieDetail返回null_保留base原值仅标记isEnhanced`() = runTest {
        advanceUntilIdle()

        val movieWithTmdb = listOf(
            TraktListItemResponse(
                rank = 1, id = 1, type = "movie",
                movie = TraktMovie(
                    title = "Inception", year = 2010,
                    ids = TraktIds(trakt = 101, tmdb = 27205, imdb = "tt1375666")
                )
            )
        )
        coEvery { traktRepository.getListItems(testListId, 20, 1) } returns Result.success(movieWithTmdb)
        coEvery { tmdbRepository.getMovieDetail(27205) } returns null

        viewModel.retry()
        advanceUntilIdle()

        val item = viewModel.uiState.value.items[0]
        // base 原值保留
        assertThat(item.title).isEqualTo("Inception")
        assertThat(item.year).isEqualTo(2010)
        assertThat(item.posterUrl).isNull()
        assertThat(item.isEnhanced).isTrue()
    }
}
