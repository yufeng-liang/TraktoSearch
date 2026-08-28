package com.tracktosearch.ui.screen.listdetail

import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import com.tracktosearch.data.remote.trakt.dto.TraktIds
import com.tracktosearch.data.remote.trakt.dto.TraktListItemResponse
import com.tracktosearch.data.remote.trakt.dto.TraktMovie
import com.tracktosearch.data.remote.trakt.dto.TraktShow
import com.tracktosearch.data.repository.MediaType
import com.tracktosearch.data.repository.TmdbRepository
import com.tracktosearch.data.repository.TraktRepository
import com.tracktosearch.data.repository.TraktRepository.WatchlistWatchedIds
import com.tracktosearch.data.session.SessionModeManager
import com.tracktosearch.test.MainDispatcherRule
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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
 * - 磁盘快照先上屏 + 远端刷新的 isRefreshing 提示
 *
 * 测试策略：多数测试数据使用 tmdbId=0，避免触发增强流程
 * （enhanceMovieItem 在 tmdbId<=0 时直接 return base.copy(isEnhanced=true)，
 * 不调用 tmdbRepository）。
 *
 * 注意 peek 桩：relaxed mock 对可空返回值也会造出一个 mock 实例，
 * 会让 buildBaseXxxItem 误以为命中内存缓存并把 year 覆盖成 0，
 * 所以 setup 里统一桩成 null，需要命中的测试自己重新桩。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TraktListDetailViewModelTest {

    // 与 Main 同一个 TestDispatcher：advanceUntilIdle 也能确定性推进 withContext(ioDispatcher) 里的读写盘
    private val testDispatcher = StandardTestDispatcher()

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(testDispatcher)

    private val traktRepository = mockk<TraktRepository>(relaxed = true)
    private val tmdbRepository = mockk<TmdbRepository>(relaxed = true)
    private val sessionModeManager = mockk<SessionModeManager>(relaxed = true)
    private lateinit var viewModel: TraktListDetailViewModel

    @Test
    fun `仅登录豆瓣时榜单详情不加载Trakt私有想看已看数据`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        coVerify(exactly = 0) { traktRepository.loadWatchlistWatchedIds() }
        assertThat(viewModel.watchlistWatchedIds.value?.movieWatchlistTraktIds).isEmpty()
    }

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

        // 清理磁盘快照，确保 init 走远端而不是先上屏旧数据
        cacheFile().delete()

        // init 块会调用这些方法（init 触发 loadItems(1)，返回空列表 → hasMore=false）
        coEvery { traktRepository.getWatchlistWatchedIds() } returns null
        coEvery { traktRepository.loadWatchlistWatchedIds() } returns WatchlistWatchedIds()
        every { sessionModeManager.traktConnected } returns MutableStateFlow(false)
        coEvery { traktRepository.getListItems(testListId, 20, 1) } returns Result.success(emptyList())
        // 默认内存缓存未命中，走正常的异步富化路径
        every { tmdbRepository.peekMovieEnrichment(any(), any(), any()) } returns null
        every { tmdbRepository.peekTvEnrichment(any(), any(), any()) } returns null
    }

    /**
     * 各测试自己建 ViewModel。
     *
     * 不在 setup 里建：init 的读盘与远端请求都在 pending 队列里，
     * 多一个实例就会多一份协程抢在被测实例前面跑，快照/刷新这类顺序敏感的断言会飘。
     */
    private fun createViewModel(): TraktListDetailViewModel = TraktListDetailViewModel(
        SavedStateHandle(mapOf("listId" to testListId, "listName" to testListName)),
        traktRepository,
        tmdbRepository,
        sessionModeManager,
        testDispatcher,
        RuntimeEnvironment.getApplication()
    )

    private val json = Json { ignoreUnknownKeys = true }

    private fun cacheFile(): File =
        File(RuntimeEnvironment.getApplication().cacheDir, "list_detail_$testListId.json")

    /** 预写磁盘快照，模拟「上次进过这个榜单」。字段名需与 CachedListItem 一致。 */
    private fun writeSnapshot(count: Int) {
        val items = (1..count).map { rank ->
            CachedListItem(
                rank = rank,
                type = MediaType.MOVIE.name,
                traktId = 1000 + rank,
                tmdbId = 0,
                title = "快照$rank",
                year = 2020,
                posterUrl = "https://img/$rank.jpg",
                imdbId = "tt${1000 + rank}"
            )
        }
        cacheFile().writeText(
            json.encodeToString(CachedListDetail(listId = testListId, items = items))
        )
    }

    // ==================== 测试 ====================

    /**
     * 测试点1：构造时从 SavedStateHandle 读取 listId/listName
     * listId/listName 在构造函数中同步赋值，无需 advanceUntilIdle
     */
    @Test
    fun `构造_SavedStateHandle携带listId和listName_uiState正确填充`() = runTest {
        viewModel = createViewModel()
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
        viewModel = createViewModel()
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
        viewModel = createViewModel()
        advanceUntilIdle()

        coEvery { traktRepository.getListItems(testListId, 20, 1) } returns Result.failure(IOException("网络错误"))

        viewModel.retry()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertThat(state.error).contains("Network error")
        assertThat(state.isLoading).isFalse()
        assertThat(state.items).isEmpty()
    }

    /**
     * 测试点4：loadMore() 成功 → 追加更多条目，currentPage 递增
     */
    @Test
    fun `loadMore_有更多数据_追加条目且currentPage递增`() = runTest {
        viewModel = createViewModel()
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
        viewModel = createViewModel()
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
        viewModel = createViewModel()
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
    // tmdbId=0 的测试都走短路逻辑（base.copy(isEnhanced=true)），验证不到字段传递。
    //
    // 两个分支都走 enrich*，posterUrl 一律是完整 URL（已含尺寸前缀）：
    // 页面直接把它交给 Coil，不再二次拼接，否则拼出 ".../w342https://..." 这种取不到的地址。

    /**
     * 测试：电影列表 tmdbId>0 → enhanceMovieItem 调用 enrichMovie，
     * 返回的 chineseTitle、year、posterUrl 必须正确映射到 ListDetailItem。
     */
    @Test
    fun `retry_电影tmdbId大于0_enrichMovie返回详情_title和posterUrl被覆盖`() = runTest {
        viewModel = createViewModel()
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
        coEvery { tmdbRepository.enrichMovie(27205, "Inception", 2010) } returns
            TmdbRepository.MovieEnrichment(
                posterUrl = "https://image.tmdb.org/t/p/w342/abc.jpg",
                chineseTitle = "盗梦空间（中文名）",
                overview = "梦境层层",
                genres = "科幻,悬疑",
                year = 2010,
                rating = 8.8
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
        // enhance 后字段（注意 title 直接覆盖，无 displayTitle 字段）
        assertThat(item.title).isEqualTo("盗梦空间（中文名）")
        assertThat(item.year).isEqualTo(2010)
        // 完整 URL：页面不再二次拼接，这里必须已经带尺寸前缀
        assertThat(item.posterUrl).isEqualTo("https://image.tmdb.org/t/p/w342/abc.jpg")
        assertThat(item.isEnhanced).isTrue()
        coVerify(exactly = 1) { tmdbRepository.enrichMovie(27205, "Inception", 2010) }
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
        viewModel = createViewModel()
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
     * 测试：enrichMovie 拿不到详情（TMDB 不可用，返回无海报的兜底）时，
     * 不能把已有标题、年份、海报覆盖成空。
     */
    @Test
    fun `retry_电影enrichMovie返回兜底_保留base原值仅标记isEnhanced`() = runTest {
        viewModel = createViewModel()
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
        // TmdbRepository.fallbackMovie 的形态：无海报，中文名退回原名
        coEvery { tmdbRepository.enrichMovie(27205, "Inception", 2010) } returns
            TmdbRepository.MovieEnrichment(
                posterUrl = null,
                chineseTitle = "",
                overview = "",
                genres = "",
                year = null,
                rating = 0.0
            )

        viewModel.retry()
        advanceUntilIdle()

        val item = viewModel.uiState.value.items[0]
        // base 原值保留
        assertThat(item.title).isEqualTo("Inception")
        assertThat(item.year).isEqualTo(2010)
        assertThat(item.posterUrl).isNull()
        assertThat(item.isEnhanced).isTrue()
    }

    // ==================== 内存缓存同步命中（peek） ====================

    /**
     * 测试：TMDB 内存缓存已有详情时，buildBaseMovieItem 用同步 peek 直接填好
     * 中文名与海报，首帧就有内容，且不再发异步富化请求。
     */
    @Test
    fun `retry_peek命中_首帧即填中文名与海报且不再调用enrichMovie`() = runTest {
        viewModel = createViewModel()
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
        every { tmdbRepository.peekMovieEnrichment(27205, "Inception", 2010) } returns
            TmdbRepository.MovieEnrichment(
                posterUrl = "https://image.tmdb.org/t/p/w342/peek.jpg",
                chineseTitle = "盗梦空间",
                overview = "",
                genres = "",
                year = 2010,
                rating = 8.8
            )

        viewModel.retry()
        advanceUntilIdle()

        val item = viewModel.uiState.value.items[0]
        assertThat(item.title).isEqualTo("盗梦空间")
        assertThat(item.posterUrl).isEqualTo("https://image.tmdb.org/t/p/w342/peek.jpg")
        assertThat(item.isEnhanced).isTrue()
        coVerify(exactly = 0) { tmdbRepository.enrichMovie(any(), any(), any()) }
    }

    // ==================== 磁盘快照 + 静默刷新 ====================

    /**
     * 测试：有磁盘快照时先把快照上屏，远端第一页还在飞就置 isRefreshing，
     * 让页面在标题栏下方显示细进度条；远端回来后复位。
     *
     * 用闸门挂住远端请求，而不是在 coAnswers 里直接读状态：读盘与远端请求是并行发出的，
     * 远端请求先发出、读盘后落地，进 coAnswers 那一刻快照还没上屏。
     */
    @Test
    fun `构造_存在磁盘快照_先上屏快照并置isRefreshing_远端返回后复位`() = runTest {
        writeSnapshot(count = 5)
        val remoteGate = CompletableDeferred<Unit>()
        coEvery { traktRepository.getListItems(testListId, 20, 1) } coAnswers {
            remoteGate.await()
            Result.success(generateMovieItems(3))
        }

        viewModel = createViewModel()
        // 读盘已落地，远端仍挂在闸门上：此时快照已上屏，进度条应该亮着
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.items).hasSize(5)
        assertThat(viewModel.uiState.value.isRefreshing).isTrue()

        remoteGate.complete(Unit)
        advanceUntilIdle()

        // 远端结果替换快照，进度条收起
        val state = viewModel.uiState.value
        assertThat(state.items).hasSize(3)
        assertThat(state.isRefreshing).isFalse()
        assertThat(state.isLoading).isFalse()
    }

    /**
     * 回归测试：快照恢复后 currentPage 必须仍是 1，且列表不能出现重复条目。
     *
     * 快照存全部翻页结果时，下次进页 items 已有几十条而 currentPage=1，
     * loadMore 会把第二页重新追加一遍，撞上 LazyGrid 的 key 直接崩。
     */
    @Test
    fun `快照恢复后翻页_不产生重复条目`() = runTest {
        // 快照 20 条（rank 1..20，traktId 1001..1020），与第一页远端结果同源
        writeSnapshot(count = 20)
        coEvery { traktRepository.getListItems(testListId, 20, 1) } returns
            Result.success(generateMovieItems(20))
        // 第二页故意与第一页重叠（榜单在翻页间隙变动会这样返回）
        coEvery { traktRepository.getListItems(testListId, 20, 2) } returns
            Result.success(generateMovieItems(5, startRank = 18))

        viewModel = createViewModel()
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.currentPage).isEqualTo(1)

        viewModel.loadMore()
        advanceUntilIdle()

        val items = viewModel.uiState.value.items
        val keys = items.map { "${it.type}-${it.traktId}" }
        assertThat(keys).containsNoDuplicates()
        // 20 + 重叠去重后新增的 2 条（rank 21、22）
        assertThat(items).hasSize(22)
    }

    /** 测试：远端刷新失败时保留快照内容，只收起进度条。 */
    @Test
    fun `存在磁盘快照_远端刷新失败_保留快照并复位isRefreshing`() = runTest {
        writeSnapshot(count = 5)
        coEvery { traktRepository.getListItems(testListId, 20, 1) } returns
            Result.failure(IOException("网络错误"))

        viewModel = createViewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertThat(state.items).hasSize(5)
        assertThat(state.isRefreshing).isFalse()
        assertThat(state.error).isNotNull()
    }

    /** 测试：无快照时不置 isRefreshing，交给骨架屏，避免同时出现两种加载提示。 */
    @Test
    fun `无磁盘快照_首次加载不置isRefreshing`() = runTest {
        var refreshingWhileFetching: Boolean? = null
        coEvery { traktRepository.getListItems(testListId, 20, 1) } coAnswers {
            refreshingWhileFetching = viewModel.uiState.value.isRefreshing
            Result.success(generateMovieItems(3))
        }

        viewModel = createViewModel()
        advanceUntilIdle()

        assertThat(refreshingWhileFetching).isFalse()
        assertThat(viewModel.uiState.value.isRefreshing).isFalse()
    }

    /** 回归测试：富化完成后写入的快照只有第一页，不能把翻页结果一起存进去。 */
    @Test
    fun `富化完成_磁盘快照只存第一页`() = runTest {
        coEvery { traktRepository.getListItems(testListId, 20, 1) } returns
            Result.success(generateMovieItems(20))
        coEvery { traktRepository.getListItems(testListId, 20, 2) } returns
            Result.success(generateMovieItems(5, startRank = 21))

        viewModel = createViewModel()
        advanceUntilIdle()
        viewModel.loadMore()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.items).hasSize(25)
        val saved = json.decodeFromString<CachedListDetail>(cacheFile().readText())
        assertThat(saved.items).hasSize(20)
    }
}
