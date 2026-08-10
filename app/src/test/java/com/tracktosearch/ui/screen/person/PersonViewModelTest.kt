package com.tracktosearch.ui.screen.person

import com.google.common.truth.Truth.assertThat
import com.tracktosearch.data.remote.tmdb.dto.TmdbPerson
import com.tracktosearch.data.remote.tmdb.dto.TmdbPersonMovieCredit
import com.tracktosearch.data.remote.tmdb.dto.TmdbPersonTvCredit
import com.tracktosearch.data.repository.MediaType
import com.tracktosearch.data.repository.TmdbRepository
import com.tracktosearch.data.repository.TraktRepository
import com.tracktosearch.data.util.PersonAvatarColorStore
import com.tracktosearch.data.util.PosterColorExtractor
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

/**
 * PersonViewModel 单元测试。
 *
 * 验证人物详情页的核心行为：
 * - loadPerson() 成功/失败/幂等
 * - profilePath=null 时不触发 Coil 头像预取（posterColorExtractor 不被调用）
 * - loadMoreMovies()/loadMoreTvShows() 成功追加条目
 * - loadPerson 成功后触发 loadTraktPerson（searchByTmdb 被调用）
 *
 * 测试策略：
 * 1. profilePath=null + mock TmdbPerson.profile_path=null，避免 prefetchAvatarColor 触发 Coil 加载
 *    （loadPerson 第 110 行检查入参 profilePath，第 138 行检查 person.profile_path，二者均需为 null）
 * 2. @Before 中清理 PersonViewModel.Companion 的 4 个 LruCache 和 PersonAvatarColorStore.cache，
 *    避免测试间静态状态泄漏（特别是 traktPersonFetchTime 会触发 TTL 跳过逻辑）
 * 3. traktRepository.searchByTmdb 桩为 Result.success(emptyList())，避免 loadTraktPerson 的 5 个并行
 *    async 分支（results 为空时 personResult=null，不进入 slug 分支）
 * 4. loadTmdbPersonImages 调用 tmdbRepository.getPersonImages/getPersonTaggedImages，relaxed mock
 *    默认返回空 List，无需显式桩
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PersonViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val tmdbRepository = mockk<TmdbRepository>(relaxed = true)
    private val traktRepository = mockk<TraktRepository>(relaxed = true)
    private val posterColorExtractor = mockk<PosterColorExtractor>(relaxed = true)
    private lateinit var viewModel: PersonViewModel

    // ==================== 测试数据 ====================

    /**
     * profile_path=null 避免触发 prefetchAvatarColor → Coil 加载。
     * also_known_as=emptyList() 避免 originalName 提取逻辑干扰断言。
     */
    private val testPerson = TmdbPerson(
        id = 1,
        name = "测试演员",
        also_known_as = emptyList(),
        profile_path = null
    )

    private val testMovieCredit1 = TmdbPersonMovieCredit(id = 101, title = "电影A")
    private val testMovieCredit2 = TmdbPersonMovieCredit(id = 102, title = "电影B")

    private val testTvCredit1 = TmdbPersonTvCredit(id = 201, name = "剧集A")
    private val testTvCredit2 = TmdbPersonTvCredit(id = 202, name = "剧集B")

    // ==================== 生命周期 ====================

    @Before
    fun setup() {
        clearMocks(tmdbRepository, traktRepository, posterColorExtractor)

        // 清理 PersonViewModel 的 4 个静态 LruCache，避免测试间状态泄漏
        // （traktPersonFetchTime 残留会触发 TTL 跳过逻辑，导致 searchByTmdb 不被调用）
        // 注意：Kotlin 编译器把 companion object 的 private val 提升为外部类的 private static final 字段，
        // 而非 Companion 类的实例字段（javap 确认 PersonViewModel$Companion 无字段），所以反射目标应为 PersonViewModel
        listOf("personImagesCache", "traktPersonCache", "originalNameCache", "traktPersonFetchTime")
            .forEach { fieldName ->
                val field = PersonViewModel::class.java.getDeclaredField(fieldName)
                field.isAccessible = true
                val cache = field.get(null) as android.util.LruCache<*, *>  // 静态字段，传 null
                cache.evictAll()
            }

        // 清理 PersonAvatarColorStore 的静态 LruCache（避免首帧主色被前序测试残留影响）
        val storeClass = PersonAvatarColorStore::class.java
        val storeField = storeClass.getDeclaredField("cache")
        storeField.isAccessible = true
        val storeCache = storeField.get(PersonAvatarColorStore) as android.util.LruCache<*, *>
        storeCache.evictAll()

        viewModel = PersonViewModel(
            RuntimeEnvironment.getApplication(),
            tmdbRepository,
            traktRepository,
            posterColorExtractor
        )
    }

    // ==================== 桩辅助 ====================

    /**
     * 配置所有 Repository 方法返回成功结果，覆盖 loadPerson + loadTraktPerson + loadTmdbPersonImages 全链路。
     * 各测试可覆盖特定方法模拟失败或追加分页数据。
     */
    private fun stubAllSuccess(
        person: TmdbPerson? = testPerson,
        movieCreditsPage1: TmdbRepository.PersonCreditsPage<TmdbPersonMovieCredit> =
            TmdbRepository.PersonCreditsPage(emptyList(), hasMore = false),
        tvCreditsPage1: TmdbRepository.PersonCreditsPage<TmdbPersonTvCredit> =
            TmdbRepository.PersonCreditsPage(emptyList(), hasMore = false)
    ) {
        coEvery { tmdbRepository.getPersonDetail(any()) } returns person
        coEvery { tmdbRepository.getPersonMovieCredits(any(), any()) } returns movieCreditsPage1
        coEvery { tmdbRepository.getPersonTvCredits(any(), any()) } returns tvCreditsPage1
        // loadTmdbPersonImages 调用 getPersonImages/getPersonTaggedImages：relaxed mock 默认返回空 List
        // loadTraktPerson 调用 searchByTmdb：返回空结果，避免 5 async 分支
        coEvery { traktRepository.searchByTmdb(any(), any()) } returns Result.success(emptyList())
    }

    // ==================== 测试 ====================

    /**
     * 测试点1：loadPerson() 成功 → uiState.person 填充，isLoading 变 false
     */
    @Test
    fun `loadPerson_TMDB成功_person填充且isLoading为false`() = runTest {
        stubAllSuccess()

        viewModel.loadPerson(1, profilePath = null)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertThat(state.person).isNotNull()
        assertThat(state.person?.name).isEqualTo("测试演员")
        assertThat(state.isLoading).isFalse()
        assertThat(state.error).isNull()
    }

    /**
     * 测试点2：loadPerson() TMDB getPersonDetail 返回 null → uiState.error 非空
     */
    @Test
    fun `loadPerson_TMDB返回null_error非空`() = runTest {
        stubAllSuccess(person = null)

        viewModel.loadPerson(1, profilePath = null)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertThat(state.error).isNotNull()
        assertThat(state.error).isEqualTo("Load failed")
        assertThat(state.person).isNull()
    }

    /**
     * 测试点3：loadPerson() 传入 profilePath=null → 不触发 Coil（posterColorExtractor 不被调用）
     *
     * prefetchAvatarColor 内部会调用 posterColorExtractor.getCachedColor 和 context.imageLoader.enqueue，
     * profilePath=null 时整个方法在第 171 行直接 return，不会执行到这些调用。
     * 同时 mock TmdbPerson.profile_path=null，避免第 138 行 prefetchAvatarColor(person.profile_path) 触发。
     */
    @Test
    fun `loadPerson_profilePath为null_不调用posterColorExtractor`() = runTest {
        stubAllSuccess()

        viewModel.loadPerson(1, profilePath = null)
        advanceUntilIdle()

        // prefetchAvatarColor 未执行到 getCachedColor/extractDominantColor
        coVerify(exactly = 0) { posterColorExtractor.getCachedColor(any()) }
        coVerify(exactly = 0) { posterColorExtractor.extractDominantColor(any(), any()) }
    }

    /**
     * 测试点4：loadMoreMovies() 成功 → movieCredits 追加更多条目
     */
    @Test
    fun `loadMoreMovies_有更多数据_追加电影作品`() = runTest {
        stubAllSuccess(
            movieCreditsPage1 = TmdbRepository.PersonCreditsPage(
                listOf(testMovieCredit1), hasMore = true
            )
        )

        viewModel.loadPerson(1, profilePath = null)
        advanceUntilIdle()

        // 第一页加载完成，hasMoreMovies=true
        assertThat(viewModel.uiState.value.movieCredits).hasSize(1)
        assertThat(viewModel.uiState.value.hasMoreMovies).isTrue()

        // 重新桩第二页（精确匹配优先于 any() 通配）
        coEvery {
            tmdbRepository.getPersonMovieCredits(1, 2)
        } returns TmdbRepository.PersonCreditsPage(
            listOf(testMovieCredit2), hasMore = false
        )

        viewModel.loadMoreMovies()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertThat(state.movieCredits).hasSize(2)
        assertThat(state.movieCredits[0].title).isEqualTo("电影A")
        assertThat(state.movieCredits[1].title).isEqualTo("电影B")
        assertThat(state.hasMoreMovies).isFalse()
        assertThat(state.isLoadingMoreMovies).isFalse()
    }

    /**
     * 测试点5：loadMoreTvShows() 成功 → tvCredits 追加更多条目
     */
    @Test
    fun `loadMoreTvShows_有更多数据_追加剧集作品`() = runTest {
        stubAllSuccess(
            tvCreditsPage1 = TmdbRepository.PersonCreditsPage(
                listOf(testTvCredit1), hasMore = true
            )
        )

        viewModel.loadPerson(1, profilePath = null)
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.tvCredits).hasSize(1)
        assertThat(viewModel.uiState.value.hasMoreTvShows).isTrue()

        coEvery {
            tmdbRepository.getPersonTvCredits(1, 2)
        } returns TmdbRepository.PersonCreditsPage(
            listOf(testTvCredit2), hasMore = false
        )

        viewModel.loadMoreTvShows()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertThat(state.tvCredits).hasSize(2)
        assertThat(state.tvCredits[0].name).isEqualTo("剧集A")
        assertThat(state.tvCredits[1].name).isEqualTo("剧集B")
        assertThat(state.hasMoreTvShows).isFalse()
        assertThat(state.isLoadingMoreTvShows).isFalse()
    }

    /**
     * 测试点6：loadPerson() 成功后 Trakt 人物详情被加载（loadTraktPerson 调用 searchByTmdb）
     *
     * loadTraktPerson 在 person 加载成功后被调用，内部调用 traktRepository.searchByTmdb(tmdbId, MediaType.PERSON)。
     * @Before 已清理 traktPersonFetchTime 缓存，确保 cacheFresh=false，不跳过 searchByTmdb 调用。
     */
    @Test
    fun `loadPerson_TMDB成功后_触发Trakt人物搜索`() = runTest {
        stubAllSuccess()

        viewModel.loadPerson(1, profilePath = null)
        advanceUntilIdle()

        // loadTraktPerson 内部调用 searchByTmdb(personId, MediaType.PERSON)
        coVerify(atLeast = 1) { traktRepository.searchByTmdb(1, MediaType.PERSON) }
    }

    /**
     * 测试点7：loadPerson() 两次调用同一 personId → 第二次直接 return（幂等，不重复请求）
     *
     * 第一次 loadPerson(1) + advanceUntilIdle → loaded=true
     * 第二次 loadPerson(1) → currentPersonId==personId && loaded → 直接 return，不进入协程
     */
    @Test
    fun `loadPerson_两次调用同一personId_第二次直接跳过`() = runTest {
        stubAllSuccess()

        // 第一次加载
        viewModel.loadPerson(1, profilePath = null)
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.person).isNotNull()

        // 第二次调用同一 personId：loaded=true → 直接 return
        viewModel.loadPerson(1, profilePath = null)
        advanceUntilIdle()

        // getPersonDetail 只被调用一次（第一次 loadPerson 触发，第二次跳过）
        coVerify(exactly = 1) { tmdbRepository.getPersonDetail(1) }
    }
}
