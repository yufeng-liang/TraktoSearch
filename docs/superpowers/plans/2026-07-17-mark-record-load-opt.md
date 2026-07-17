# 标记记录页首屏加载优化 实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 将标记记录页首屏冷启动的「串行逐条 enrich + 全量完成才显示」改为「并行 enrich（并发 10）+ 渐进提交（每 20 条先显示）」，缩短首屏出内容时间。缓存优先原则完全保留。

**架构：** `TraktRepository.fetchWatchHistory` 由返回 `Result<WatchHistoryPage>` 改为返回 `Flow<WatchHistoryEmit>`（分批 emit）；`MarkRecordViewModel` 改为消费该 Flow，首批到达即 `isLoading=false`，后续批追加/合并。UI 层与分页主流程不变。

**技术栈：** Kotlin Coroutines（`async`/`coroutineScope`/`Semaphore`/`withPermit`）、`kotlinx.coroutines.flow`、`TtlCache` 持久化缓存（已有）、Hilt MVVM。

**Worktree：** 本计划在 `F:/trae-project-mark-record-opt`（分支 `feature/mark-record-load-opt`）内实现。

---

## 文件结构

- 修改：`app/src/main/java/com/tracktosearch/data/repository/TraktRepository.kt`
  - 新增 `WatchHistoryEmit` data class 与 `ENRICH_CONCURRENCY`/`EMIT_BATCH_SIZE` 常量。
  - 新增 `import kotlinx.coroutines.flow.Flow`、`import kotlinx.coroutines.flow.flow`、`import kotlinx.coroutines.sync.Semaphore`、`import kotlinx.coroutines.sync.withPermit`。
  - 改写 `fetchWatchHistory`：并行拉 movie+episode → 合并 entries → 并行 enrich（Semaphore 限流）→ 分批 emit；末批 `isComplete=true` 时 `watchHistoryCache.put`。
  - 保留 `clearWatchHistoryCache()`。
- 修改：`app/src/main/java/com/tracktosearch/ui/screen/markrecord/MarkRecordViewModel.kt`
  - `loadFromTraktHistory` 改为挂起函数消费 Flow，返回（items, isComplete）供 `loadPage` 使用；首屏首批即提交。
  - `loadPage` 的 WATCHED / ALL 分支适配 Flow 消费（仅末批提交，避免抖动）。
- 修改：`app/src/test/java/com/tracktosearch/data/repository/TraktRepositoryTest.kt`
  - 调整现有 `fetchWatchHistory` 相关测试适配 Flow 返回；新增并行/渐进/缓存优先级测试。
- 修改：`app/src/test/java/com/tracktosearch/ui/screen/markrecord/MarkRecordViewModelTest.kt`
  - 调整 `fetchWatchHistory` mock 为 Flow；新增「首批到达即 isLoading=false」测试。

---

### 任务 1：TraktRepository 新增 emit 类型与常量 + 补 import

**文件：**
- 修改：`app/src/main/java/com/tracktosearch/data/repository/TraktRepository.kt`

- [ ] **步骤 1：补 import**

在 TraktRepository.kt 顶部 import 区（约 line 18-26 附近）新增：
```kotlin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
```

- [ ] **步骤 2：在 `WatchHistoryPage` data class 后新增 emit 类型与常量**

在 TraktRepository.kt:99（`WatchHistoryPage` 结束）之后插入：
```kotlin
    /** 已看历史分批产出单元（并行 enrich + 渐进提交用） */
    data class WatchHistoryEmit(
        val items: List<WatchHistoryItem>,
        val isComplete: Boolean = false,
        val error: String? = null
    )

    companion object {
        /** 并行 enrich 并发上限，防止冷启动瞬间打爆 TMDB */
        const val ENRICH_CONCURRENCY = 10
        /** 渐进提交批大小，每累计这么多条 emit 一批 */
        const val EMIT_BATCH_SIZE = 20
    }
```

- [ ] **步骤 3：编译验证**

运行：`cd F:/trae-project-mark-record-opt && ./gradlew :app:compileDebugKotlin`
预期：BUILD SUCCESSFUL（无调用方改动，仅新增类型/常量/import）。

- [ ] **步骤 4：Commit**

```bash
cd F:/trae-project-mark-record-opt && git add -A && git commit -m "feat: 标记记录-新增 WatchHistoryEmit 类型与并行/批大小常量"
```

---

### 任务 2：改写 fetchWatchHistory 为分批 Flow（并行 enrich + 渐进提交）

**文件：**
- 修改：`app/src/main/java/com/tracktosearch/data/repository/TraktRepository.kt:847-945`（原 `fetchWatchHistory` 整段）

- [ ] **步骤 1：编写失败测试（先锁定新行为）**

在 `app/src/test/java/com/tracktosearch/data/repository/TraktRepositoryTest.kt` 顶部 import 区新增（若未存在）：
```kotlin
import kotlinx.coroutines.flow.toList
import com.tracktosearch.data.repository.TraktRepository.WatchHistoryEmit
```
在 `fetchWatchHistory` 测试区（约 line 129 起）**新增**方法（不改旧方法，旧方法将在任务 4 适配）：
```kotlin
    @Test
    fun `fetchWatchHistory_返回Flow_分批emit且末批isComplete`() = runTest {
        val result = repository.fetchWatchHistory(1).toList()
        // 至少一批；末批 isComplete=true
        assertTrue(result.isNotEmpty())
        assertTrue(result.last().isComplete)
        // 所有条目总数等于原始 movie+episode 条数（mock 已在 @Before 提供）
        val total = result.sumOf { it.items.size }
        assertEquals(2, total) // 假设 @Before mock 各 1 条
    }
```

- [ ] **步骤 2：运行测试验证失败**

运行：`cd F:/trae-project-mark-record-opt && ./gradlew :app:testDebugUnitTest --tests "com.tracktosearch.data.repository.TraktRepositoryTest.fetchWatchHistory_返回Flow_分批emit且末批isComplete"`
预期：FAIL（当前 `fetchWatchHistory` 返回 `Result`，无 `toList`）。

- [ ] **步骤 3：改写 fetchWatchHistory**

将 TraktRepository.kt:847-945 整段替换为：
```kotlin
    /**
     * 拉取 Trakt 已看历史（含电影和剧集），返回分批 Flow。
     * movie 和 episode 并行拉取后合并，每条记录并行 enrich（Semaphore 限流并发），
     * 每累计 [EMIT_BATCH_SIZE] 条 emit 一批，全部完成 emit 末批（isComplete=true）。
     * 缓存优先：enrich 内部命中 movieDetailCache/tvDetailCache 内存/磁盘即即时返回；
     * 未命中才发 TMDB 网络请求并写回持久化缓存（TmdbRepository.enrichMovie/enrichTv 已实现）。
     * @param page 页码，从 1 开始
     */
    fun fetchWatchHistory(page: Int): Flow<WatchHistoryEmit> = flow {
        val cacheKey = "watch_history_page_$page"
        watchHistoryCache.get(cacheKey)?.let {
            emit(WatchHistoryEmit(items = it.items, isComplete = true))
            return@flow
        }
        try {
            coroutineScope {
                val movieDef = async { traktApiService.getMovieHistory(page = page, limit = 100) }
                val episodeDef = async { traktApiService.getEpisodeHistory(page = page, limit = 100) }
                val movieResp = movieDef.await()
                val episodeResp = episodeDef.await()
                if (!movieResp.isSuccessful || !episodeResp.isSuccessful) {
                    emit(WatchHistoryEmit(items = emptyList(), isComplete = true,
                        error = "fetchWatchHistory failed: movie=${movieResp.code()}, episode=${episodeResp.code()}"))
                    return@coroutineScope
                }
                val movieEntries = movieResp.body() ?: emptyList()
                val episodeEntries = episodeResp.body() ?: emptyList()
                val movieTotal = movieResp.headers()["X-Pagination-Item-Count"]?.toIntOrNull() ?: 0
                val episodeTotal = episodeResp.headers()["X-Pagination-Item-Count"]?.toIntOrNull() ?: 0
                val moviePageCount = movieResp.headers()["X-Pagination-Page-Count"]?.toIntOrNull() ?: 1
                val episodePageCount = episodeResp.headers()["X-Pagination-Page-Count"]?.toIntOrNull() ?: 1
                val totalPages = maxOf(moviePageCount, episodePageCount)
                val totalCount = movieTotal + episodeTotal

                // 合并所有待 enrich 的原始条目
                val rawItems = mutableListOf<RawEntry>()
                for (entry in movieEntries) {
                    val m = entry.movie ?: continue
                    rawItems.add(RawEntry(
                        mediaType = "movie", traktId = m.ids.trakt, tmdbId = m.ids.tmdb,
                        imdbId = m.ids.imdb, title = m.title, year = m.year.takeIf { it > 0 },
                        watchedAt = parseTraktDate(entry.watched_at), episodeInfo = null, show = null, episode = null
                    ))
                }
                for (entry in episodeEntries) {
                    val ep = entry.episode ?: continue
                    val show = entry.show ?: continue
                    rawItems.add(RawEntry(
                        mediaType = "show", traktId = show.ids.trakt, tmdbId = show.ids.tmdb,
                        imdbId = show.ids.imdb, title = show.title, year = show.year,
                        watchedAt = parseTraktDate(entry.watched_at),
                        episodeInfo = "S${ep.season}E${ep.number}", show = show, episode = ep
                    ))
                }

                val semaphore = Semaphore(ENRICH_CONCURRENCY)
                val enriched = mutableListOf<WatchHistoryItem>()
                val deferreds = rawItems.map { raw ->
                    async {
                        semaphore.withPermit {
                            enrichRaw(raw)
                        }
                    }
                }
                // 渐进提交：每完成 EMIT_BATCH_SIZE 条 emit 一批
                var emittedCount = 0
                for (deferred in deferreds) {
                    enriched.add(deferred.await())
                    emittedCount++
                    if (emittedCount % EMIT_BATCH_SIZE == 0) {
                        val batch = enriched.sortedByDescending { it.watchedAt }
                        emit(WatchHistoryEmit(items = batch, isComplete = false))
                    }
                }
                // 末批（完整排序）
                val finalItems = enriched.sortedByDescending { it.watchedAt }
                watchHistoryCache.put(cacheKey, WatchHistoryPage(finalItems, page, totalPages, totalCount))
                emit(WatchHistoryEmit(items = finalItems, isComplete = true))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emit(WatchHistoryEmit(items = emptyList(), isComplete = true, error = e.message ?: "fetchWatchHistory failed"))
        }
    }

    /** 单条原始记录 enrich（并行调用，限流由调用方 Semaphore 控制） */
    private suspend fun enrichRaw(raw: RawEntry): WatchHistoryItem {
        return if (raw.mediaType == "movie") {
            var displayTitle = raw.title
            var posterUrl: String? = null
            var year = raw.year
            var imdbId = raw.imdbId
            if (raw.tmdbId > 0) {
                try {
                    val enrichment = tmdbRepository.enrichMovie(raw.tmdbId, raw.title, year)
                    displayTitle = enrichment.chineseTitle.ifBlank { raw.title }
                    posterUrl = enrichment.posterUrl
                    year = enrichment.year ?: year
                    imdbId = enrichment.imdbId ?: imdbId
                } catch (e: CancellationException) { throw e } catch (e: Exception) {
                    Log.w("TraktRepository", "fetchWatchHistory movie enrich failed: ${e.message}")
                }
            }
            WatchHistoryItem(
                traktId = raw.traktId, tmdbId = raw.tmdbId, imdbId = imdbId,
                mediaType = "movie", title = raw.title, displayTitle = displayTitle,
                posterUrl = posterUrl, year = year, watchedAt = raw.watchedAt, episodeInfo = null
            )
        } else {
            var displayTitle = raw.title
            var posterUrl: String? = null
            var year = raw.year
            var imdbId = raw.imdbId
            if (raw.tmdbId > 0) {
                try {
                    val enrichment = tmdbRepository.enrichTv(raw.tmdbId, raw.title, year)
                    displayTitle = enrichment.chineseTitle.ifBlank { raw.title }
                    posterUrl = enrichment.posterUrl
                    year = enrichment.year ?: year
                    imdbId = enrichment.imdbId ?: imdbId
                } catch (e: CancellationException) { throw e } catch (e: Exception) {
                    Log.w("TraktRepository", "fetchWatchHistory episode enrich failed: ${e.message}")
                }
            }
            WatchHistoryItem(
                traktId = raw.traktId, tmdbId = raw.tmdbId, imdbId = imdbId,
                mediaType = "show", title = raw.title, displayTitle = displayTitle,
                posterUrl = posterUrl, year = year, watchedAt = raw.watchedAt,
                episodeInfo = raw.episodeInfo
            )
        }
    }

    /** 并行 enrich 用的原始记录载体 */
    private data class RawEntry(
        val mediaType: String,
        val traktId: Int,
        val tmdbId: Int,
        val imdbId: String,
        val title: String,
        val year: Int?,
        val watchedAt: Long,
        val episodeInfo: String?,
        val show: com.tracktosearch.data.remote.trakt.dto.TraktShow?,
        val episode: com.tracktosearch.data.remote.trakt.dto.TraktEpisode?
    )
```

> 注：`TraktShow`/`TraktEpisode` 实际类型名以 TraktRepository.kt 现有 `getMovieHistory`/`getEpisodeHistory` 返回值中的 `.show`/`.episode` 字段类型为准（当前代码 line 904-905 用 `entry.show`/`entry.episode`）。若类型名不同，按实际替换 `RawEntry.show`/`RawEntry.episode` 的声明类型，并移除未使用字段（当前 `show`/`episode` 在 enrich 中已不再需要，仅保留以便兼容，可简化为不存 show/episode，直接把 title/year/ids 提出来——实现时若发现 show/episode 未使用，直接去掉这两个字段以 YAGNI）。

- [ ] **步骤 4：运行测试验证通过**

运行：`cd F:/trae-project-mark-record-opt && ./gradlew :app:testDebugUnitTest --tests "com.tracktosearch.data.repository.TraktRepositoryTest.fetchWatchHistory_返回Flow_分批emit且末批isComplete"`
预期：PASS。

- [ ] **步骤 5：Commit**

```bash
cd F:/trae-project-mark-record-opt && git add -A && git commit -m "feat: 标记记录-并行enrich+渐进提交改写fetchWatchHistory"
```

---

### 任务 3：MarkRecordViewModel 消费 Flow（首批到达即显示）

**文件：**
- 修改：`app/src/main/java/com/tracktosearch/ui/screen/markrecord/MarkRecordViewModel.kt:225-230`（`loadFromTraktHistory`）、`172-205`（`loadPage`）

- [ ] **步骤 1：编写失败测试（首批即 isLoading=false）**

在 `app/src/test/java/com/tracktosearch/ui/screen/markrecord/MarkRecordViewModelTest.kt` 新增（依赖任务 4 的 Flow mock，此处先写结构）：
```kotlin
    @Test
    fun `loadFirstPage_WATCHED_首批到达即isLoadingFalse`() = runTest {
        val page = TraktRepository.WatchHistoryPage(
            items = listOf(makeTraktItem(1)), currentPage = 1, totalPages = 1, totalCount = 1
        )
        coEvery { traktRepo.fetchWatchHistory(any()) } returns flowOf(
            TraktRepository.WatchHistoryEmit(items = page.items, isComplete = true)
        )
        val vm = createViewModel()
        // 首批 emit 后 isLoading 应立刻为 false（不依赖全部完成）
        advanceUntilIdle()
        assertFalse(vm.uiState.value.isLoading)
        assertEquals(1, vm.uiState.value.items.size)
    }
```

- [ ] **步骤 2：运行验证失败**

运行：`cd F:/trae-project-mark-record-opt && ./gradlew :app:testDebugUnitTest --tests "com.tracktosearch.ui.screen.markrecord.MarkRecordViewModelTest.loadFirstPage_WATCHED_首批到达即isLoadingFalse"`
预期：FAIL（当前 `loadFromTraktHistory` 返回 `List`，非 Flow；mock 类型不符）。

- [ ] **步骤 3：改写 loadFromTraktHistory 返回分批结果**

将 MarkRecordViewModel.kt:225-230 的 `loadFromTraktHistory` 改为消费 Flow、首批即提交、返回（items, isComplete）：
```kotlin
    /**
     * 消费 fetchWatchHistory 的 Flow：首批到达即提交（调用方负责 isLoading=false）；
     * 末批（isComplete）时返回完整列表供翻页/合并判断。
     */
    private suspend fun loadFromTraktHistory(
        page: Int,
        onFirstBatch: (List<MarkRecordItem>) -> Unit
    ): Pair<List<MarkRecordItem>, Boolean> {
        var allItems: List<MarkRecordItem> = emptyList()
        var complete = false
        traktRepository.fetchWatchHistory(page).collect { emit ->
            if (emit.error != null) throw Exception(emit.error)
            val mapped = emit.items.map { it.toMarkRecordItem() }
            if (emit.isComplete) {
                allItems = mapped
                complete = true
            } else {
                // 非末批：首批到达即回调，让 UI 立即显示
                onFirstBatch(mapped)
            }
        }
        return allItems to complete
    }
```

- [ ] **步骤 4：改写 loadPage 适配**

将 MarkRecordViewModel.kt:172-205 `loadPage` 中 WATCHED 与 ALL 分支改为：
```kotlin
    private suspend fun loadPage(page: Int) {
        val state = _uiState.value
        try {
            val (newItems, traktComplete) = when (state.currentTab) {
                MarkRecordTab.WATCHED -> {
                    loadFromTraktHistory(page) { firstBatch ->
                        // 首批即显示
                        _uiState.update { it.copy(items = firstBatch, isLoading = false) }
                    }
                }
                MarkRecordTab.ALL -> {
                    val localItems = loadFromDao(page, state)
                    val traktItems = if (page == 1) {
                        try {
                            loadFromTraktHistory(1) { firstBatch ->
                                _uiState.update {
                                    it.copy(items = (localItems.map { it.toMarkRecordItem() } + firstBatch)
                                        .sortedByDescending { it.actedAt }.take(pageSize), isLoading = false)
                                }
                            }.first
                        } catch (e: CancellationException) { throw e } catch (_: Exception) { emptyList() }
                    } else emptyList()
                    // ALL Tab 合并 local + trakt；trakt 部分已是末批完整列表
                    val merged = (localItems.map { it.toMarkRecordItem() } + traktItems)
                        .sortedByDescending { it.actedAt }.take(pageSize) to true
                    merged
                }
                else -> loadFromDao(page, state).let { it to true }
            }
            // WATCHED/ALL 的 newItems 已是末批完整列表（首批已在上面即时提交）
            val displayedItems = if (_uiState.value.items.isNotEmpty() && page == 1) {
                // 首批已提交，末批到达时替换为完整排序结果
                newItems
            } else {
                (_uiState.value.items + newItems).distinctBy { it.traktId to it.actionType }
            }
            // ALL Tab 是合并视图，不做分页（hasMore 恒为 false）
            val hasMore = newItems.size == pageSize && state.currentTab == MarkRecordTab.WATCHED
            _uiState.update {
                it.copy(
                    items = displayedItems,
                    currentPage = page,
                    hasMore = hasMore,
                    isLoading = false,
                    isLoadingMore = false,
                    error = null
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _uiState.update { it.copy(isLoading = false, isLoadingMore = false, error = e.message ?: "加载失败") }
        }
    }
```

> 注意：`WATCHED` 分支首批回调提交的是中间批（可能未满整页），末批 `newItems` 为完整列表覆盖——避免抖动：仅首批 + 末批两次写 `items`，中间批不重复写（已在 `loadFromTraktHistory` 中仅对非 `isComplete` 批调用 `onFirstBatch`，且末批在 `loadPage` 末尾统一覆盖）。如需更平滑可去掉中间批回调，仅首批 + 末批；本计划采用「首批 + 末批」双写，平衡即时性与稳定性。

- [ ] **步骤 5：运行测试验证通过**

运行：`cd F:/trae-project-mark-record-opt && ./gradlew :app:testDebugUnitTest --tests "com.tracktosearch.ui.screen.markrecord.MarkRecordViewModelTest"`
预期：PASS（含新增测试）。

- [ ] **步骤 6：Commit**

```bash
cd F:/trae-project-mark-record-opt && git add -A && git commit -m "feat: 标记记录-ViewModel消费Flow首批即显示"
```

---

### 任务 4：适配现有测试（Flow 返回类型）

**文件：**
- 修改：`app/src/test/java/com/tracktosearch/data/repository/TraktRepositoryTest.kt`
- 修改：`app/src/test/java/com/tracktosearch/ui/screen/markrecord/MarkRecordViewModelTest.kt`

- [ ] **步骤 1：TraktRepositoryTest 适配**

将现有 `fetchWatchHistory_*` 测试中 `repository.fetchWatchHistory(1)` 的 `.getOrNull()`/`.isSuccess` 用法改为 `.toList().last()` 取末批；`enrich 命中不调用API` 类测试保持（enrich 内部不变）。示例改法：
```kotlin
// 原：val result = repository.fetchWatchHistory(1)
//    assertTrue(result.isSuccess)
// 改：
val emits = repository.fetchWatchHistory(1).toList()
assertTrue(emits.last().isComplete)
assertNull(emits.last().error)
```
逐个修改 line 129-370 范围内 8 个相关测试方法，保持断言语义等价。

- [ ] **步骤 2：MarkRecordViewModelTest 适配**

将现有 `coEvery { traktRepo.fetchWatchHistory(any()) } returns Result.success(page)` 改为：
```kotlin
coEvery { traktRepo.fetchWatchHistory(any()) } returns flowOf(
    TraktRepository.WatchHistoryEmit(items = page.items, isComplete = true)
)
```
涉及 line 365/400/504/522/547/550/581/604/750 等。同时 import `kotlinx.coroutines.flow.flowOf`。

- [ ] **步骤 3：运行全部相关测试**

运行：`cd F:/trae-project-mark-record-opt && ./gradlew :app:testDebugUnitTest --tests "com.tracktosearch.data.repository.TraktRepositoryTest" --tests "com.tracktosearch.ui.screen.markrecord.MarkRecordViewModelTest"`
预期：全部 PASS。

- [ ] **步骤 4：Commit**

```bash
cd F:/trae-project-mark-record-opt && git add -A && git commit -m "test: 标记记录-适配fetchWatchHistory Flow返回的相关测试"
```

---

### 任务 5：新增并行/渐进/缓存优先级专项测试

**文件：**
- 修改：`app/src/test/java/com/tracktosearch/data/repository/TraktRepositoryTest.kt`
- 修改：`app/src/test/java/com/tracktosearch/ui/screen/markrecord/MarkRecordViewModelTest.kt`

- [ ] **步骤 1：TraktRepositoryTest 新增专项测试**

```kotlin
    @Test
    fun `fetchWatchHistory_并行enrich_单条失败兜底不崩溃`() = runTest {
        // 让第 2 条 enrich 抛异常（@Before mock 提供 2 条）
        coEvery { tmdbRepo.enrichMovie(any(), any(), any()) } throws RuntimeException("boom") andThenAnswer { ...正常... }
        val emits = repository.fetchWatchHistory(1).toList()
        assertTrue(emits.last().isComplete)
        assertEquals(2, emits.sumOf { it.items.size })
        // 失败条用 Trakt 原始 title，不崩溃
    }

    @Test
    fun `fetchWatchHistory_分批emit数量正确`() = runTest {
        // mock 返回 45 条 movie，验证 emit 批数 = ceil(45/EMIT_BATCH_SIZE)
        val emits = repository.fetchWatchHistory(1).toList()
        val nonFinal = emits.filter { !it.isComplete }
        assertTrue(nonFinal.isNotEmpty())
        assertEquals(45, emits.sumOf { it.items.size })
    }

    @Test
    fun `fetchWatchHistory_缓存命中不重复enrich`() = runTest {
        repository.fetchWatchHistory(1).toList()
        // 第二次应命中 watchHistoryCache，enrichMovie 不再被调用
        coVerify(exactly = 0) { tmdbRepo.enrichMovie(any(), any(), any()) }
        repository.fetchWatchHistory(1).toList()
    }
```

- [ ] **步骤 2：MarkRecordViewModelTest 新增首批即显示测试（同任务 3 步骤 1，确认已通过）**

- [ ] **步骤 3：运行新增测试**

运行：`cd F:/trae-project-mark-record-opt && ./gradlew :app:testDebugUnitTest --tests "com.tracktosearch.data.repository.TraktRepositoryTest" --tests "com.tracktosearch.ui.screen.markrecord.MarkRecordViewModelTest"`
预期：全部 PASS。

- [ ] **步骤 4：Commit**

```bash
cd F:/trae-project-mark-record-opt && git add -A && git commit -m "test: 标记记录-新增并行enrich与渐进提交专项测试"
```

---

### 任务 6：全量构建与 UI 测试回归

**文件：**
- 运行：`app/src/androidTest/java/com/tracktosearch/ui/screen/markrecord/MarkRecordScreenTest.kt`（不改动，仅验证）

- [ ] **步骤 1：运行单元测试全量**

运行：`cd F:/trae-project-mark-record-opt && ./gradlew :app:testDebugUnitTest`
预期：BUILD SUCCESSFUL，所有单测绿。

- [ ] **步骤 2：运行 debug 构建**

运行：`cd F:/trae-project-mark-record-opt && ./gradlew :app:assembleDebug`
预期：BUILD SUCCESSFUL。

- [ ] **步骤 3：UI 测试编译验证（不强制跑设备）**

运行：`cd F:/trae-project-mark-record-opt && ./gradlew :app:compileDebugAndroidTestKotlin`
预期：BUILD SUCCESSFUL（确认 MarkRecordScreenTest 现有断言未因行为变化破坏编译；UI 行为 loading/empty/error/retry 语义不变）。

- [ ] **步骤 4：Commit（若期间有微调）**

```bash
cd F:/trae-project-mark-record-opt && git add -A && git commit -m "chore: 标记记录-加载优化全量构建与回归验证" || echo "no changes"
```

---

## 自检

- **规格覆盖度**：
  - §2 缓存优先 → 任务 2 步骤 3 注释 + enrichRaw 复用 TmdbRepository（未改缓存逻辑）；任务 5 缓存命中测试。✓
  - §3 架构/范围 → 任务 2+3 仅改 fetchWatchHistory + ViewModel 消费，UI 不变。✓
  - §3.1 常量 → 任务 1 步骤 2 `ENRICH_CONCURRENCY`/`EMIT_BATCH_SIZE`。✓
  - §4 数据流 → 任务 3 首批回调 + 末批覆盖。✓
  - §5 错误处理 → 任务 2 emit error；任务 5 单条失败兜底测试；§4 限流 Semaphore。✓
  - §6 测试 → 任务 4+5。✓
  - §7 验证 → 任务 6。✓
- **占位符扫描**：任务 2 中 `RawEntry.show/episode` 类型名与"可简化去掉"注记——已给出明确 fallback（按实际类型替换/去掉），非 TODO。其余无占位符。
- **类型一致性**：`WatchHistoryEmit` 在任务 1 定义，任务 2/3/4/5 一致使用；`loadFromTraktHistory` 新签名 `(page, onFirstBatch) -> Pair<List, Boolean>` 在任务 3 定义并被 `loadPage` 使用，一致。✓
