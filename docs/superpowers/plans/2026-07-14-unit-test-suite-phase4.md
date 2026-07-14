# 阶段4：ViewModel 层单元测试 实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 为 10 个 ViewModel 编写单元测试，覆盖状态管理、业务逻辑、协程流程和错误处理，发现潜在 bug。

**架构：** 复用阶段1-3已建立的测试基础设施（mockk + turbine + truth + coroutines-test + Robolectric + MainDispatcherRule）。每个 ViewModel 一个测试类，用 mock 替换所有依赖，用 turbine 验证 StateFlow 状态变化序列，用 runTest 控制协程时序。

**技术栈：** JUnit 4 + mockk 1.13.13 + turbine 1.2.0 + truth 1.4.4 + Robolectric 4.13 + kotlinx-coroutines-test 1.11.0

---

## 测试范围

### ViewModel 清单与优先级

| 任务 | ViewModel | 依赖数 | 公开方法数 | 复杂度 | 测试价值 |
|------|-----------|--------|-----------|--------|---------|
| 1 | StatisticsViewModel | 3 | 1 | 低 | 高（统计逻辑） |
| 2 | TraktListDetailViewModel | 4 | 2 | 低 | 中 |
| 3 | PersonViewModel | 4 | 4 | 低 | 中 |
| 4 | DiscoverFilterViewModel | 4 | 14 | 中 | 中（筛选逻辑） |
| 5 | TraktSearchViewModel | 5 | 7 | 中 | 高（搜索竞态） |
| 6 | SearchViewModel | 7 | 13 | 中 | 高（核心流程） |
| 7 | WatchlistViewModel | 9 | 20+ | 高 | 高（核心流程） |
| 8 | DiscoverViewModel | 12 | 20+ | 高 | 中 |
| 9 | DetailViewModel | 14 | 25 | 极高 | 高（核心流程） |
| 10 | SettingsViewModel | 20+ | 精选 | 极高 | 中（精选核心方法） |

> **UpdateCheckViewModel 跳过**：空类（1 依赖，0 方法），无可测试逻辑。

### 不测试的范围

- **UI 渲染和交互**：阶段5 Compose UI 测试覆盖
- **startSync 完整同步流程**：涉及豆瓣爬取+Trakt API+云端上传，属集成测试范畴
- **纯委托方法**：如 `SettingsViewModel.setThemeMode` → `themeStorage.setThemeMode`，无业务逻辑
- **Android 框架交互**：如 WakeLock、Notification，需插桩测试

---

## 文件结构

### 创建的文件

```
app/src/test/java/com/tracktosearch/ui/screen/
├── statistics/StatisticsViewModelTest.kt          # 任务1
├── listdetail/TraktListDetailViewModelTest.kt     # 任务2
├── person/PersonViewModelTest.kt                  # 任务3
├── discoverfilter/DiscoverFilterViewModelTest.kt  # 任务4
├── traktsearch/TraktSearchViewModelTest.kt        # 任务5
├── search/SearchViewModelTest.kt                  # 任务6
├── watchlist/WatchlistViewModelTest.kt            # 任务7
├── discover/DiscoverViewModelTest.kt              # 任务8
├── detail/DetailViewModelTest.kt                  # 任务9
└── settings/SettingsViewModelTest.kt              # 任务10
```

### 已有基础设施（复用，不修改）

- `app/src/test/java/com/tracktosearch/test/MainDispatcherRule.kt` — 替换 Dispatchers.Main 为 StandardTestDispatcher
- `app/src/test/java/com/tracktosearch/test/TestFixture.kt` — 公共测试数据工厂（按需扩充）
- `app/src/test/resources/robolectric.properties` — Robolectric SDK=33 配置
- `app/build.gradle.kts` — 测试依赖已配置（mockk/turbine/truth/coroutines-test/robolectric）

---

## 通用测试模式

### ViewModel 测试基本结构

```kotlin
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class XxxViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    // 所有依赖用 relaxed mock
    private val dependency1 = mockk<Dependency1>(relaxed = true)
    private val dependency2 = mockk<Dependency2>(relaxed = true)

    private lateinit var viewModel: XxxViewModel

    @Before
    fun setup() {
        clearMocks(dependency1, dependency2)
        viewModel = XxxViewModel(dependency1, dependency2)
    }

    @Test
    fun `被测行为_条件_预期结果`() = runTest {
        // 1. Arrange：配置 mock 返回值
        coEvery { dependency1.getData(any()) } returns expectedData

        // 2. Act：调用 ViewModel 方法
        viewModel.loadData()

        // 3. Assert：用 turbine 验证 StateFlow 状态
        viewModel.uiState.test {
            val state = awaitItem()
            assertThat(state.data).isEqualTo(expectedData)
        }
    }
}
```

### 关键模式说明

1. **MainDispatcherRule**：ViewModel 内部的 `viewModelScope` 默认用 `Dispatchers.Main`，MainDispatcherRule 替换为 StandardTestDispatcher，使协程在 `runTest` 中可控
2. **runTest + advanceUntilIdle**：`runTest` 内协程挂起后需调用 `advanceUntilIdle()` 让待执行的协程任务跑完
3. **turbine 的 test { }**：用于验证 StateFlow 状态序列。注意 StateFlow 是 distinct 的，相同值不会重复发射
4. **clearMocks**：在 @Before 中清除前序测试的 mock 调用记录，确保 `coVerify(exactly = N)` 正确工作
5. **relaxed mock 陷阱**：对返回 nullable 类型的 suspend 方法，relaxed mock 返回 null；对返回 List 的方法返回 emptyList。需显式 stub 需要非空返回值的方法
6. **SavedStateHandle 构造**：`SavedStateHandle(mapOf("key" to value))` 可直接构造，无需 mock
7. **Context 参数**：用 `RuntimeEnvironment.getApplication()`（Robolectric 真实 Context）

### Bug 分级处理

- **P0（立即修复）**：测试发现的源码逻辑错误，立即修复 + 独立 commit
- **P1（标记待修复）**：用 `@Ignore("待修复: 描述")` 标注测试，commit message 注明
- **P2（特征测试）**：对可疑行为写 characterization test + `// FIXME` 注释

---

### 任务 1：StatisticsViewModelTest

**文件：**
- 创建：`app/src/test/java/com/tracktosearch/ui/screen/statistics/StatisticsViewModelTest.kt`
- 源码：`app/src/main/java/com/tracktosearch/ui/screen/statistics/StatisticsViewModel.kt`

**被测 ViewModel 结构：**
```kotlin
class StatisticsViewModel @Inject constructor(
    private val traktRepository: TraktRepository,
    private val userReviewRepository: UserReviewRepository,
    @ApplicationContext private val context: Context
) : ViewModel()
```

**公开方法：**
- `fun loadStatistics()` — 加载统计数据（观看数、评分分布、类型分布、热力图、词云）
- `val uiState: StateFlow<StatisticsUiState>` — 统计数据状态

**StatisticsUiState 关键字段：**
- `initialLoading: Boolean` — 初始加载中
- `error: String?` — 错误信息
- `totalMovieCount`, `totalShowCount`, `totalEpisodeCount` — 计数
- `genreDistribution: Map<String, Int>` — 类型分布
- `ratingDistribution: Map<Int, Int>` — 评分分布
- `overviewReady`, `watchTimeReady`, `heatmapReady`, `ratingsReady`, `genreReady`, `wordCloudReady` — 各部分加载完成标志

**测试点清单（8个）：**

1. `loadStatistics()` 初始加载 → uiState.initialLoading 从 true 变为 false
2. `loadStatistics()` 成功 → totalMovieCount/totalShowCount 正确填充
3. `loadStatistics()` 成功 → genreDistribution 正确填充
4. `loadStatistics()` 成功 → ratingDistribution 正确填充
5. `loadStatistics()` 成功 → overviewReady 变为 true
6. `loadStatistics()` TraktRepository 抛异常 → error 非空，initialLoading 为 false
7. `loadStatistics()` 成功后再次调用 → 重新加载（不缓存上次结果）
8. `loadStatistics()` 词云数据正确填充（从 userReviewRepository 获取）

**Mock 配置要点：**
- `traktRepository`: mock `getWatchedMovies()`, `getWatchedShows()`, `getRatings()` 等方法返回测试数据
- `userReviewRepository`: mock 返回评论列表用于词云生成
- `context`: 用 `RuntimeEnvironment.getApplication()`

**实现步骤：**

- [ ] **步骤 1：读取源码确认方法签名**

读取 `StatisticsViewModel.kt` 的 `loadStatistics()` 方法实现，确认它调用了 traktRepository 的哪些方法、如何处理错误、如何分阶段更新 uiState。

- [ ] **步骤 2：编写全部8个测试**

编写 `StatisticsViewModelTest.kt`，按通用测试模式结构。注意：
- `loadStatistics()` 是非 suspend 函数，内部用 `viewModelScope.launch`，调用后需 `advanceUntilIdle()` 等待协程完成
- 用 turbine 的 `test { }` 块验证 uiState 状态变化
- 错误测试：mock traktRepository 方法抛 IOException，验证 uiState.error 非空

- [ ] **步骤 3：运行测试验证通过**

运行：`.\gradlew testDebugUnitTest --tests "com.tracktosearch.ui.screen.statistics.StatisticsViewModelTest"`
预期：8/8 通过

- [ ] **步骤 4：Commit**

```bash
git add app/src/test/java/com/tracktosearch/ui/screen/statistics/StatisticsViewModelTest.kt
git commit -m "test: 添加 StatisticsViewModel 统计数据加载测试"
```

---

### 任务 2：TraktListDetailViewModelTest

**文件：**
- 创建：`app/src/test/java/com/tracktosearch/ui/screen/listdetail/TraktListDetailViewModelTest.kt`
- 源码：`app/src/main/java/com/tracktosearch/ui/screen/listdetail/TraktListDetailViewModel.kt`

**被测 ViewModel 结构：**
```kotlin
class TraktListDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val traktRepository: TraktRepository,
    private val tmdbRepository: TmdbRepository,
    @ApplicationContext private val context: Context
) : ViewModel()
```

**公开方法：**
- `fun retry()` — 重试加载
- `fun loadMore()` — 加载更多
- `val uiState: StateFlow<ListDetailUiState>`
- `val watchlistWatchedIds: StateFlow<WatchlistWatchedIds?>`

**测试点清单（6个）：**

1. 构造时从 SavedStateHandle 读取 listId/listName → uiState 正确初始化
2. `retry()` 成功 → uiState.isLoading 从 true 变 false，listItems 填充
3. `retry()` 网络失败 → uiState.error 非空
4. `loadMore()` 成功 → 追加更多条目（不分页则验证不重复加载）
5. `retry()` 成功后 watchlistWatchedIds 被加载
6. `retry()` 空列表 → uiState 显示空状态

**Mock 配置要点：**
- `SavedStateHandle(mapOf("listId" to 123, "listName" to "测试列表"))` 直接构造
- `traktRepository`: mock `getTraktListItems()` 返回测试数据

- [ ] **步骤 1-4：同任务1模式（读源码→编写6个测试→运行验证→commit）**

运行：`.\gradlew testDebugUnitTest --tests "com.tracktosearch.ui.screen.listdetail.TraktListDetailViewModelTest"`
预期：6/6 通过
Commit：`test: 添加 TraktListDetailViewModel 列表详情测试`

---

### 任务 3：PersonViewModelTest

**文件：**
- 创建：`app/src/test/java/com/tracktosearch/ui/screen/person/PersonViewModelTest.kt`
- 源码：`app/src/main/java/com/tracktosearch/ui/screen/person/PersonViewModel.kt`

**被测 ViewModel 结构：**
```kotlin
class PersonViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val tmdbRepository: TmdbRepository,
    private val traktRepository: TraktRepository,
    private val posterColorExtractor: PosterColorExtractor
) : ViewModel()
```

**公开方法：**
- `fun loadPerson(personId: Int, profilePath: String? = null, avatarColor: Color? = null)` — 加载人物详情
- `fun loadMoreMovies()` — 加载更多参演电影
- `fun loadMoreTvShows()` — 加载更多参演电视剧
- `fun resolveAndNavigate(...)` — 解析 TMDB ID 并导航

**测试点清单（7个）：**

1. `loadPerson()` 成功 → uiState.personInfo 填充，isLoading 变 false
2. `loadPerson()` TMDB 失败 → uiState.error 非空
3. `loadPerson()` 传入 profilePath → uiState.profilePath 正确设置
4. `loadMoreMovies()` 成功 → movieCredits 追加更多条目
5. `loadMoreTvShows()` 成功 → tvCredits 追加更多条目
6. `loadPerson()` 成功后 Trakt 人物详情被加载（社媒、维基等）
7. `loadPerson()` 两次调用同一 personId → 第二次从缓存加载（不重复请求）

**注意事项：**
- PersonViewModel 有 companion object 静态缓存（LruCache），测试间需清理。在 @Before 中通过反射清理静态缓存，或确保每个测试用不同 personId

- [ ] **步骤 1-4：同任务1模式**

运行：`.\gradlew testDebugUnitTest --tests "com.tracktosearch.ui.screen.person.PersonViewModelTest"`
预期：7/7 通过
Commit：`test: 添加 PersonViewModel 人物详情测试`

---

### 任务 4：DiscoverFilterViewModelTest

**文件：**
- 创建：`app/src/test/java/com/tracktosearch/ui/screen/discoverfilter/DiscoverFilterViewModelTest.kt`
- 源码：`app/src/main/java/com/tracktosearch/ui/screen/discoverfilter/DiscoverFilterViewModel.kt`

**被测 ViewModel 结构：**
```kotlin
class DiscoverFilterViewModel @Inject constructor(
    private val tmdbRepository: TmdbRepository,
    private val traktRepository: TraktRepository,
    private val tokenStorage: TokenStorage,
    val posterColorExtractor: PosterColorExtractor
) : ViewModel()
```

**公开方法（14个，大多是简单 toggle）：**
- `switchType(type)`, `toggleGenre(genreId)`, `toggleCountry(countryCode)`, `toggleKeyword(keywordId)`
- `toggleDecade(key)`, `setVoteRange(min, max)`, `setSortBy(sort)`, `toggleHideWatched()`
- `toggleAdvanced()`, `collapseAdvanced()`, `resetFilters()`
- `search()`, `loadMore()`

**测试点清单（10个）：**

1. `switchType(MOVIE)` → uiState.selectedType 变为 MOVIE，触发新搜索
2. `toggleGenre(18)` → genreIds 包含 18；再次调用 → 移除 18
3. `toggleDecade("2020s")` → 选中的年代包含 2020s；再次调用 → 移除
4. `setVoteRange(5f, 10f)` → voteRange 更新
5. `setSortBy(POPULARITY)` → sortBy 更新
6. `toggleHideWatched()` → hideWatched 取反
7. `resetFilters()` → 所有筛选条件回到默认值
8. `search()` 成功 → uiState.results 填充，isLoading 变 false
9. `search()` 网络失败 → uiState.error 非空
10. `toggleAdvanced()` → isAdvancedExpanded 取反

**注意事项：**
- `tokenStorage.accessToken` 是 `StateFlow<String?>`，需 mock 返回有效 flow。用 `every { tokenStorage.accessToken } returns MutableStateFlow("valid_token")`
- `search()` 内部调用 `tmdbRepository.discover()`，需 mock 返回结果

- [ ] **步骤 1-4：同任务1模式**

运行：`.\gradlew testDebugUnitTest --tests "com.tracktosearch.ui.screen.discoverfilter.DiscoverFilterViewModelTest"`
预期：10/10 通过
Commit：`test: 添加 DiscoverFilterViewModel 筛选逻辑测试`

---

### 任务 5：TraktSearchViewModelTest

**文件：**
- 创建：`app/src/test/java/com/tracktosearch/ui/screen/traktsearch/TraktSearchViewModelTest.kt`
- 源码：`app/src/main/java/com/tracktosearch/ui/screen/traktsearch/TraktSearchViewModel.kt`

**被测 ViewModel 结构：**
```kotlin
class TraktSearchViewModel @Inject constructor(
    private val traktRepository: TraktRepository,
    private val tmdbRepository: TmdbRepository,
    private val resourceRepository: ResourceRepository,
    savedStateHandle: SavedStateHandle,
    @ApplicationContext private val context: Context
) : ViewModel()
```

**公开方法：**
- `initSearch(query, type)` — 初始化搜索（从导航参数）
- `switchTab(type)` — 切换搜索类型（MOVIE/SHOW/PERSON/DISK）
- `search(query, type?)` — 执行搜索
- `loadMore()` — 加载更多结果
- `toggleDiskSource(source)`, `toggleDiskType(type)` — 网盘筛选

**测试点清单（9个）：**

1. 构造时从 SavedStateHandle 读取 type/query → uiState.selectedTab 正确初始化
2. `search("盗梦空间", MOVIE)` 成功 → uiState.results 填充，isLoading 变 false
3. `search()` 网络失败 → uiState.error 非空
4. `search()` 空查询 → 不触发搜索
5. `switchTab(SHOW)` → uiState.selectedTab 变为 SHOW，清空旧结果
6. 快速连续搜索两次 → 只保留最后一次结果（searchJob 取消旧任务）
7. `loadMore()` 成功 → 追加下一页结果
8. `loadMore()` 已到最后一页 → 不重复加载
9. `initSearch()` 从导航参数初始化 → 自动执行首次搜索

**注意事项：**
- 搜索竞态测试（测试点6）：快速调用两次 `search()`，验证只调用了第二次的 API。用 `coVerify(exactly = 1)` 确认
- `enrichSemaphore = Semaphore(5)` 限制 enrich 并发，测试中 mock enrich 返回固定结果即可

- [ ] **步骤 1-4：同任务1模式**

运行：`.\gradlew testDebugUnitTest --tests "com.tracktosearch.ui.screen.traktsearch.TraktSearchViewModelTest"`
预期：9/9 通过
Commit：`test: 添加 TraktSearchViewModel 搜索与竞态测试`

---

### 任务 6：SearchViewModelTest

**文件：**
- 创建：`app/src/test/java/com/tracktosearch/ui/screen/search/SearchViewModelTest.kt`
- 源码：`app/src/main/java/com/tracktosearch/ui/screen/search/SearchViewModel.kt`

**被测 ViewModel 结构：**
```kotlin
class SearchViewModel @Inject constructor(
    private val resourceRepository: ResourceRepository,
    private val searchHistoryStorage: SearchHistoryStorage,
    private val viewedItemStorage: ViewedItemStorage,
    private val doubanHotApi: DoubanHotApiService,
    private val tmdbRepository: TmdbRepository,
    private val traktRepository: TraktRepository,
    private val sharedDoubanHotCache: PersistentTtlCache<DoubanHotData>
) : ViewModel()
```

**公开方法（13个）：**
- `loadHotSearches()` — 加载豆瓣热词
- `search(keyword)` — 执行资源搜索
- `retryDoubanCategory(categoryId)`, `retryAllDoubanHot()`, `loadDoubanHotAll(categoryId, page, limit)`
- `removeHistory(keyword)`, `addTraktHistory(keyword, type)`, `getSuggestions(query)`, `clearHistory()`, `clearResults()`
- `setTypeFilter(filter)`, `resolveAndNavigate(...)`, `markViewed(url)`

**测试点清单（12个）：**

1. `loadHotSearches()` 成功 → hotSearches StateFlow 填充热词列表
2. `loadHotSearches()` 缓存命中 → 不调用 doubanHotApi
3. `search("盗梦空间")` 成功 → uiState 搜索结果填充
4. `search()` 空关键词 → 不触发搜索
5. `search()` 快速连续两次 → 只保留最后一次结果（searchJob 取消旧任务）
6. `setTypeFilter(MOVIE)` → uiState.typeFilter 更新
7. `clearResults()` → 搜索结果清空
8. `clearHistory()` → searchHistoryStorage.clearHistory() 被调用
9. `removeHistory("测试")` → searchHistoryStorage.removeHistory("测试") 被调用
10. `getSuggestions("测")` → 返回匹配的搜索历史
11. `search()` 网络失败 → uiState.error 非空
12. `markViewed(url)` → viewedItemStorage.markViewed(url) 被调用

**注意事项：**
- `sharedDoubanHotCache` 是 `PersistentTtlCache<DoubanHotData>`，需 mock `get()`/`put()` 方法。缓存命中测试需 mock `get()` 返回非 null 值
- `search()` 内部有 debounce 逻辑（可能用 delay），测试中需 `advanceUntilIdle()` 等待
- `searchHistoryStorage.getSuggestions()` 需 mock 返回列表

- [ ] **步骤 1-4：同任务1模式**

运行：`.\gradlew testDebugUnitTest --tests "com.tracktosearch.ui.screen.search.SearchViewModelTest"`
预期：12/12 通过
Commit：`test: 添加 SearchViewModel 搜索与热词测试`

---

### 任务 7：WatchlistViewModelTest

**文件：**
- 创建：`app/src/test/java/com/tracktosearch/ui/screen/watchlist/WatchlistViewModelTest.kt`
- 源码：`app/src/main/java/com/tracktosearch/ui/screen/watchlist/WatchlistViewModel.kt`

**被测 ViewModel 结构：**
```kotlin
class WatchlistViewModel @Inject constructor(
    private val traktRepository: TraktRepository,
    private val tmdbRepository: TmdbRepository,
    private val offlineCacheManager: OfflineCacheManager,
    private val doubanSyncManager: DoubanSyncManager,
    private val doubanAuthStorage: DoubanAuthStorage,
    private val doubanSyncMetaStorage: DoubanSyncMetaStorage,
    private val statusConsistencyChecker: DoubanTraktStatusConsistencyChecker,
    private val doubanBatchRemovalManager: DoubanBatchRemovalManager,
    @ApplicationContext private val context: Context
) : ViewModel()
```

**公开方法（20+个，聚焦核心）：**
- `loadMovies(forceReload, silent)`, `loadShows(...)`, `loadHistoryMovies(...)`, `loadHistoryShows(...)`
- `refresh()`, `refreshIfLoaded(silent)`, `refreshWatchlist()`, `refreshWatched()`
- `batchRemoveFromWatchlist(traktIds, type)`, `batchRemoveFromHistory(traktIds, type)`
- `startDoubanSync(mode)`, `isDoubanLoggedIn()`, `isBatchRemovalRunning()`, `cancelBatchRemoval()`
- 筛选：`updateSelectedGenres`, `toggleDecade`, `updateMarkedTimePreset`, `updateRatingRange`, `resetFilters`

**测试点清单（14个）：**

1. `loadMovies()` 成功 → uiState.movies 填充，isLoading 变 false
2. `loadMovies()` 网络失败 → uiState.error 非空
3. `loadMovies(silent=true)` → 不显示 loading 状态
4. `loadShows()` 成功 → uiState.shows 填充
5. `loadHistoryMovies()` 成功 → uiState.historyMovies 填充
6. `refresh()` → 取消旧的加载协程，重新加载所有数据
7. `batchRemoveFromWatchlist(traktIds, MOVIE)` → traktRepository.removeFromWatchlist 被调用，列表更新
8. `batchRemoveFromHistory(traktIds, SHOW)` → traktRepository.removeFromHistory 被调用
9. `startDoubanSync(INCREMENTAL)` → doubanSyncManager.startSync 被调用
10. `isDoubanLoggedIn()` 豆瓣已登录 → 返回 true
11. `isDoubanLoggedIn()` 豆瓣未登录 → 返回 false
12. `updateSelectedGenres(setOf("动作"))` → filterState.genres 更新
13. `resetFilters()` → filterState 回到默认值
14. `cancelBatchRemoval()` → doubanBatchRemovalManager.cancel() 被调用

**注意事项：**
- `loadMovies` 等方法内部用 `viewModelScope.launch`，调用后需 `advanceUntilIdle()`
- `batchRemoveFromWatchlist` 是 suspend 函数，直接在 runTest 中调用
- `doubanAuthStorage.getCredentials()` 非 suspend，返回 `DoubanCredentials?`
- `doubanSyncManager.progress` 是 `StateFlow<DoubanSyncProgress>`，构造前需 mock（init 块可能 collect）

- [ ] **步骤 1-4：同任务1模式**

运行：`.\gradlew testDebugUnitTest --tests "com.tracktosearch.ui.screen.watchlist.WatchlistViewModelTest"`
预期：14/14 通过
Commit：`test: 添加 WatchlistViewModel 想看列表管理测试`

---

### 任务 8：DiscoverViewModelTest

**文件：**
- 创建：`app/src/test/java/com/tracktosearch/ui/screen/discover/DiscoverViewModelTest.kt`
- 源码：`app/src/main/java/com/tracktosearch/ui/screen/discover/DiscoverViewModel.kt`

**被测 ViewModel 结构：**
```kotlin
class DiscoverViewModel @Inject constructor(
    private val doubanHotApi: DoubanHotApiService,
    private val tmdbRepository: TmdbRepository,
    private val traktRepository: TraktRepository,
    private val searchHistoryStorage: SearchHistoryStorage,
    private val viewedItemStorage: ViewedItemStorage,
    private val discoverSectionStorage: DiscoverSectionStorage,
    private val tokenStorage: TokenStorage,
    private val sharedDoubanHotCache: PersistentTtlCache<DoubanHotData>,
    private val doubanRepository: DoubanRepository,
    private val doubanAuthStorage: DoubanAuthStorage,
    private val doubanRecommendCache: PersistentTtlCache<List<DoubanRecommendItem>>,
    @ApplicationContext private val context: Context
) : ViewModel()
```

**公开方法（20+个，聚焦核心）：**
- `loadDoubanHot()`, `loadDoubanRecommend()`, `loadTmdbPopular()`, `loadTmdbUpcoming()`
- `loadTraktRecommendations()`, `loadTraktData()`, `loadTraktLists()`
- `retryDoubanCategory(categoryId)`, `retryAll()`, `forceRefreshAll()`
- `switchRecommendTab(tab)`, `switchTrendingTimeWindow(timeWindow)`
- `loadRemainingSections(force)`, `refreshWatchlistWatchedIds()`
- `resolveAndNavigate(...)`, `markViewed(url)`

**测试点清单（12个）：**

1. `loadDoubanHot()` 成功 → uiState.doubanHotData 填充
2. `loadDoubanHot()` 缓存命中 → 不调用 doubanHotApi
3. `loadDoubanRecommend()` 成功 → uiState.recommendItems 填充
4. `loadTmdbPopular()` 成功 → uiState.tmdbPopular 填充
5. `loadTmdbUpcoming()` 成功 → uiState.tmdbUpcoming 填充
6. `loadTraktRecommendations()` 未登录 → 跳过，不调用 traktRepository
7. `loadTraktRecommendations()` 已登录 → uiState.traktRecommendations 填充
8. `switchRecommendTab(tab)` → uiState.currentRecommendTab 更新
9. `switchTrendingTimeWindow("week")` → uiState.trendingTimeWindow 更新，重新加载
10. `retryDoubanCategory(categoryId)` → 重新加载指定分类
11. `forceRefreshAll()` → 所有 section 重新加载
12. `refreshWatchlistWatchedIds()` → traktRepository.loadWatchlistWatchedIds 被调用

**注意事项：**
- `discoverSectionStorage.sectionConfigs` 是 `StateFlow<List<DiscoverSectionConfig>>`，需 mock 返回有效 flow
- `tokenStorage.accessToken` 需 mock 返回 `MutableStateFlow("token")` 或 `MutableStateFlow(null)`
- `sharedDoubanHotCache` 和 `doubanRecommendCache` 需 mock `get()`/`put()` 方法

- [ ] **步骤 1-4：同任务1模式**

运行：`.\gradlew testDebugUnitTest --tests "com.tracktosearch.ui.screen.discover.DiscoverViewModelTest"`
预期：12/12 通过
Commit：`test: 添加 DiscoverViewModel 发现页数据加载测试`

---

### 任务 9：DetailViewModelTest

**文件：**
- 创建：`app/src/test/java/com/tracktosearch/ui/screen/detail/DetailViewModelTest.kt`
- 源码：`app/src/main/java/com/tracktosearch/ui/screen/detail/DetailViewModel.kt`

**被测 ViewModel 结构（14依赖，最复杂）：**
```kotlin
class DetailViewModel @Inject constructor(
    private val tmdbRepository: TmdbRepository,
    private val traktRepository: TraktRepository,
    private val resourceRepository: ResourceRepository,
    private val ratingsRepository: RatingsRepository,
    private val viewedItemStorage: ViewedItemStorage,
    private val commentTranslator: CommentTranslator,
    private val tokenStorage: TokenStorage,
    private val detailSectionStorage: DetailSectionStorage,
    private val languageStorage: LanguageStorage,
    private val doubanRepository: DoubanRepository,
    private val doubanAuthStorage: DoubanAuthStorage,
    private val doubanSyncedItemDao: DoubanSyncedItemDao,
    val posterColorExtractor: PosterColorExtractor,
    private val userReviewRepository: UserReviewRepository
) : ViewModel()
```

**公开方法（25个，聚焦核心）：**
- `loadDetail(traktId, tmdbId, title, mediaType, ...)` — 加载详情（核心入口）
- `toggleWatchlist()` — 切换想看状态
- `toggleWatched()` — 切换已看状态
- `setRating(rating)` / `setRatingWithComment(rating, comment)` / `removeRating()` — 评分管理
- `searchResources()` / `toggleSource(source)` — 资源搜索
- `translateComments()` / `translateSingleComment(commentId)` — 评论翻译
- `loadMoreComments()` — 加载更多评论
- `toggleSeason(seasonNumber)` / `toggleEpisodeWatched(...)` — 季集管理（TV）

**测试点清单（16个）：**

1. `loadDetail()` 成功 → uiState.detail 填充，isLoading 变 false
2. `loadDetail()` TMDB 失败 → uiState.error 非空
3. `loadDetail()` 传入 inWatchlist=true → uiState.isInWatchlist 初始为 true
4. `toggleWatchlist()` 未登录 → 触发登录提示（uiState.showLoginPrompt 变 true）
5. `toggleWatchlist()` 已登录 + 当前在想看 → 移除想看，isInWatchlist 变 false
6. `toggleWatchlist()` 已登录 + 当前不在想看 → 添加想看，isInWatchlist 变 true
7. `toggleWatched()` 已登录 + 当前未看 → 标记已看，isWatched 变 true
8. `setRating(8)` 已登录 → uiState.userRating 变为 8，ratingsRepository.setRating 被调用
9. `removeRating()` 已登录 → uiState.userRating 变为 0，ratingsRepository.removeRating 被调用
10. `setRatingWithComment(8, "好看")` → 评分和评论同时更新
11. `searchResources()` 成功 → uiState.resourceResults 填充
12. `toggleSource(source)` → uiState.selectedSource 更新
13. `translateComments()` 成功 → uiState.comments 中的评论被翻译
14. `loadMoreComments()` 成功 → 追加更多评论
15. `toggleSeason(1)` → uiState.expandedSeasons 包含 1
16. `loadDetail()` 重复调用同一 traktId → 第二次从缓存加载

**注意事项：**
- DetailViewModel 有 companion object 静态缓存（detailCache LinkedHashMap），测试间需清理。在 @Before 中通过反射清理
- `loadDetail()` 是 suspend 函数，参数多，用 `traktId=1, tmdbId=100, title="测试", mediaType=MediaType.MOVIE` 作为基本测试参数
- `toggleWatchlist/toggleWatched` 是非 suspend 函数，内部用 `viewModelScope.launch`
- `commentTranslator` 需 mock `translate()` 方法
- `doubanSyncedItemDao.getByImdbId()` 可能被调用（同步状态检查），需 mock

- [ ] **步骤 1-4：同任务1模式**

运行：`.\gradlew testDebugUnitTest --tests "com.tracktosearch.ui.screen.detail.DetailViewModelTest"`
预期：16/16 通过
Commit：`test: 添加 DetailViewModel 详情页核心功能测试`

---

### 任务 10：SettingsViewModelTest

**文件：**
- 创建：`app/src/test/java/com/tracktosearch/ui/screen/settings/SettingsViewModelTest.kt`
- 源码：`app/src/main/java/com/tracktosearch/ui/screen/settings/SettingsViewModel.kt`

**被测 ViewModel 结构（20+依赖，最复杂）：**
```kotlin
class SettingsViewModel @Inject constructor(
    private val themeStorage: ThemeStorage,
    private val searchSourceStorage: SearchSourceStorage,
    private val notificationStorage: NotificationStorage,
    private val notificationScheduler: NotificationScheduler,
    private val languageStorage: LanguageStorage,
    private val traktRepository: TraktRepository,
    private val tmdbRepository: TmdbRepository,
    private val updateRepository: UpdateRepository,
    private val offlineCacheManager: OfflineCacheManager,
    private val doubanHotCache: PersistentTtlCache<DoubanHotData>,
    private val doubanDetailCache: PersistentTtlCache<DoubanDetailCacheEntry>,
    private val discoverSectionStorage: DiscoverSectionStorage,
    private val detailSectionStorage: DetailSectionStorage,
    private val customSearchSourceStorage: CustomSearchSourceStorage,
    private val customSearchService: CustomSearchService,
    private val panHubConfigStorage: PanHubConfigStorage,
    private val defaultTabStorage: DefaultTabStorage,
    private val doubanAuthStorage: DoubanAuthStorage,
    private val doubanRepository: DoubanRepository,
    private val cloudPersonalSyncManager: CloudPersonalSyncManager,
    // ... 可能还有更多依赖
) : ViewModel()
```

**测试策略：** SettingsViewModel 有 80+ 方法，但大部分是纯委托（`setThemeMode` → `themeStorage.setThemeMode`）。**只测试有业务逻辑的方法**，跳过纯委托。

**精选测试方法：**
- `exportData(uri)` — 导出数据（涉及序列化、文件写入）
- `importFromImdb(uri)` — 导入 IMDb 数据（涉及解析、同步）
- `clearCache()` — 清除缓存（涉及多个缓存管理）
- `checkUpdate()` — 检查更新（涉及 UpdateRepository）
- `loadChangelog()` — 加载更新日志
- `refreshCacheInfo()` — 刷新缓存信息
- `loadUserProfile()` — 加载用户资料
- `startManualConsistencyCheck()` — 启动一致性检查
- `testCustomSource(source)` — 测试自定义搜索源

**测试点清单（10个）：**

1. `checkUpdate()` 成功有更新 → uiState.updateInfo 填充，showUpdateDialog 变 true
2. `checkUpdate()` 成功无更新 → showUpdateDialog 保持 false
3. `checkUpdate()` 网络失败 → uiState.error 非空
4. `loadChangelog()` 成功 → uiState.changelog 填充
5. `clearCache()` → offlineCacheManager.clearAll() 被调用，缓存相关方法被调用
6. `refreshCacheInfo()` → uiState.cacheBreakdown 填充缓存大小信息
7. `loadUserProfile()` 成功 → uiState.userProfile 填充
8. `loadUserProfile()` 未登录 → userProfile 保持 null
9. `startManualConsistencyCheck()` → statusConsistencyChecker.checkAndUnifyWithCrawl() 被调用
10. `testCustomSource(source)` 成功 → uiState.testResults 更新为成功状态

**注意事项：**
- 20+ 依赖全部用 relaxed mock。大部分 storage 类有 `StateFlow` 属性（如 `themeStorage.themeMode`），需 mock 返回有效 flow
- `updateRepository.checkForUpdate()` 返回 `UpdateInfo?`，需 mock 返回值
- `exportData/importFromImdb` 涉及 Uri 操作，可能需要 `mockkStatic(Uri::class)` 或用 Robolectric 真实 Context
- `statusConsistencyChecker.checkProgress` 是 `StateFlow<ConsistencyCheckResult>`，需 mock 返回有效 flow
- `doubanAuthStorage.isLoggedIn` 是 `StateFlow<Boolean>`，需 mock 返回 `MutableStateFlow(false)`

- [ ] **步骤 1-4：同任务1模式**

运行：`.\gradlew testDebugUnitTest --tests "com.tracktosearch.ui.screen.settings.SettingsViewModelTest"`
预期：10/10 通过
Commit：`test: 添加 SettingsViewModel 设置页核心功能测试`

---

## 阶段4结束验证

- [ ] **运行全部 ViewModel 测试**

```bash
.\gradlew testDebugUnitTest --tests "com.tracktosearch.ui.screen.*ViewModelTest"
```
预期：全部通过，0 failures, 0 errors

- [ ] **运行全部已有测试确认无回归**

```bash
.\gradlew testDebugUnitTest
```
预期：全部通过（阶段1-3 的 247 个测试 + 阶段4 新增测试）

- [ ] **构建 Debug 包验证**

```bash
.\gradlew assembleDebug
```
预期：BUILD SUCCESSFUL

- [ ] **Commit 最终验证记录**

```bash
git commit --allow-empty -m "chore: 阶段4 ViewModel 测试全部通过"
```

---

## 自检

### 1. 规格覆盖度

| ViewModel | 任务 | 测试点数 | 覆盖核心方法 |
|-----------|------|---------|------------|
| StatisticsViewModel | 任务1 | 8 | loadStatistics |
| TraktListDetailViewModel | 任务2 | 6 | retry, loadMore |
| PersonViewModel | 任务3 | 7 | loadPerson, loadMoreMovies/TvShows |
| DiscoverFilterViewModel | 任务4 | 10 | 筛选 toggle, search |
| TraktSearchViewModel | 任务5 | 9 | search, switchTab, loadMore, 竞态 |
| SearchViewModel | 任务6 | 12 | search, loadHotSearches, history |
| WatchlistViewModel | 任务7 | 14 | load*, batchRemove*, sync, filter |
| DiscoverViewModel | 任务8 | 12 | load* 各数据源 |
| DetailViewModel | 任务9 | 16 | loadDetail, toggle*, rating*, translate |
| SettingsViewModel | 任务10 | 10 | checkUpdate, clearCache, export |
| **总计** | **10任务** | **114** | **UpdateCheckViewModel 跳过（空类）** |

### 2. 占位符扫描

- 无"待定"/"TODO"内容
- 每个测试点都有明确的测试意图、输入条件和预期输出
- 每个任务都有精确的运行命令和预期通过数
- Mock 配置要点说明了每个依赖的 stub 策略

### 3. 类型一致性

- 所有 ViewModel 构造参数与源码一致（已通过 Grep 确认）
- MainDispatcherRule 在阶段1已创建，路径 `app/src/test/java/com/tracktosearch/test/MainDispatcherRule.kt`
- TestFixture 在阶段1已创建，可按需扩充
- 测试运行命令统一用 `.\gradlew testDebugUnitTest --tests "全限定类名"`

### 4. 风险与注意事项

- **静态缓存清理**：PersonViewModel 和 DetailViewModel 有 companion object 静态缓存，测试间需通过反射清理，否则测试顺序影响结果
- **StateFlow 的 distinct 行为**：相同值不会重复发射，turbine 验证时需注意不要 `awaitItem()` 预期不存在的状态变化
- **relaxed mock 陷阱**：nullable 返回类型返回 null，List 返回 emptyList。需显式 stub 所有需要非空返回值的方法
- **runTest 与 viewModelScope**：MainDispatcherRule 用 StandardTestDispatcher，`runTest` 内调用 `advanceUntilIdle()` 让 viewModelScope 的协程执行完成
- **SavedStateHandle**：直接用 `SavedStateHandle(mapOf(...))` 构造，无需 mock
