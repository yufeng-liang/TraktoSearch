# 单元测试套件设计

**日期**: 2026-07-14
**范围**: 纯函数工具类 + Repository 层 + ViewModel 层单元测试
**测试目的**: 验证现有实现 + 发现 bug
**测试深度**: 正常路径 + 主要错误/异常路径 + 关键边界值

---

## 1. 测试基础设施

### 1.1 新增依赖

`gradle/libs.versions.toml`：

```toml
# [versions]
mockk = "1.13.13"
turbine = "1.2.0"
coroutinesTest = "1.11.0"      # 与现有 coroutines 同版本
truth = "1.4.4"
robolectric = "4.13"
coreTesting = "2.2.0"

# [libraries]
mockk = { group = "io.mockk", name = "mockk", version.ref = "mockk" }
turbine = { group = "app.cash.turbine", name = "turbine", version.ref = "turbine" }
coroutines-test = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-test", version.ref = "coroutinesTest" }
truth = { group = "com.google.truth", name = "truth", version.ref = "truth" }
robolectric = { group = "org.robolectric", name = "robolectric", version.ref = "robolectric" }
core-testing = { group = "androidx.arch.core", name = "core-testing", version.ref = "coreTesting" }
```

`app/build.gradle.kts`：

```kotlin
testImplementation(libs.junit)
testImplementation(libs.mockk)
testImplementation(libs.turbine)
testImplementation(libs.coroutines.test)
testImplementation(libs.truth)
testImplementation(libs.robolectric)
testImplementation(libs.core.testing)

android {
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}
```

### 1.2 Robolectric 配置

`app/src/test/resources/robolectric.properties`：

```properties
sdk=33
```

SDK 锁定 33：minSdk 26 兼容，Robolectric 对 33 支持最稳定。

### 1.3 通用测试工具

`app/src/test/java/com/tracktosearch/test/`：

- `MainDispatcherRule.kt` — JUnit Rule，替换 `Dispatchers.Main` 为 `StandardTestDispatcher`
- `TestFixture.kt` — 公共测试数据工厂（如 `sampleDoubanDetailHtml()`、`sampleTraktMovie()`），按需扩充

### 1.4 运行策略

- **默认 runner**：`org.junit.runners.JUnit4`（纯 JVM 测试）
- **Robolectric 测试**：类级 `@RunWith(RobolectricTestRunner::class)` + `@Config(sdk = [33])`
- **协程测试**：`@ExperimentalCoroutinesApi` 加在类上，`runTest { }` 包裹
- **Flow 测试**：`turbine { }` 收集 StateFlow 断言状态序列
- **断言库**：统一用 `truth` 的 `assertThat()`，比 JUnit `assertEquals` 更易读

---

## 2. A 层 - 纯函数工具类测试（13 个测试类）

| # | 被测类 | 测试类名 | 关键测试点 | Robolectric |
|---|---|---|---|---|
| 1 | `TtlCache` | `TtlCacheTest` | TTL 过期、LRU 淘汰、maxSize=0 无上限、getOrAwait 并发 single-flight、CancellationException 清理 inFlight、clear 后旧请求不写回 | 否 |
| 2 | `PersistentTtlCache` | `PersistentTtlCacheTest` | loadFromDisk 过期跳过、putAll 并发互斥、snapshotFromDisk 过期过滤、二级缓存命中链路、key 版本号失效 | 是 (DataStore) |
| 3 | `HolidayDetector` | `HolidayDetectorTest` | 固定日期节日（国庆/劳动/元旦）、农历节日（春节/端午/中秋）、非节日返回 null、闰年边界 | 否 |
| 4 | `ReviewTokenizer` | `ReviewTokenizerTest`（扩充已有） | 中文分词、英文小写、停用词过滤、空列表、单条评论、超长文本、混合中英文 | 否 |
| 5 | `DataExportImport` | `DataExportImportTest` | JSON 往返一致性、IMDb CSV 引号转义、跨行字段、必填列缺失、空列表导出、parseCsv 错误格式 | 否 |
| 6 | `DoubanSpider` | `DoubanSpiderTest` | 登录页识别、CSRF token 解析、标记列表解析（含分页）、详情页解析（评分/集数/演职员）、IMDB 搜索解析、残缺 HTML | 否 |
| 7 | `JsonPathParser` | `JsonPathParserTest` | 简单路径 `a.b.c`、数组索引 `a[0]`、数组遍历 `a[*]`、嵌套数组、路径不存在返回 null、空路径 | 否 |
| 8 | `AesCrypto` | `AesCryptoTest` | 加解密往返、空字符串、特殊字符（中文/emoji）、错误密文返回 null、hashUserId 一致性 | 是 (Base64) |
| 9 | `GiteeContentsApi` | `GiteeContentsApiTest` | 用 MockWebServer 测 getFileContent/createFileContent/putFileContent，含 404/401/500 错误响应 | 否 |
| 10 | `DoubanSyncFailure` | `DoubanSyncFailureTest` | fromEntity/toEntity 往返、fromMarkItem、FailureReason.fromString 容错（null/未知值）、mediaTypeCleared 默认值 | 否 |
| 11 | `SyncMode` / `PanHubPlugin` | `EnumsTest` | 枚举值完整性、id/displayName 唯一性 | 否 |
| 12 | `ScrollToTopProvider` | `ScrollToTopProviderTest` | register/unregister/scrollToTop 调用链、未 register 时 scrollToTop 无副作用 | 否 |
| 13 | `PosterColorExtractor` | `PosterColorExtractorTest` | getCachedColor 命中/未命中、extractDominantColor 写缓存、空 bitmap 处理 | 是 (Bitmap/Palette) |

### 跳过的工具类（ROI 低或需 instrumented test）

- `PosterColorCache` / `PersonAvatarColorStore` — 纯 Android LruCache/DataStore 封装，逻辑薄
- `CommentTranslator` — 依赖百度 API 网络，单元测试价值低
- `ApkInstaller` / `ApkDownloader` / `InstallResultReceiver` — 重度 Android Framework，需 instrumented test
- `ToastExt` / `HapticExt` / `ResourceLinkHelper` — UI 扩展函数，需 Compose/Activity 上下文

### 测试数据 fixture

`src/test/resources/fixtures/`：

- `douban/login_page.html`
- `douban/mark_list.html`
- `douban/detail_movie.html`
- `douban/detail_tv.html`
- `douban/search_by_imdb.html`
- `imdb/ratings.csv`（含引号转义、跨行字段样例）

fixture HTML 缺失时先用最小合成 HTML 测试解析逻辑，标注 `// TODO: 用真实 HTML 替换`，后续替换。

### 命名规范

```
方法名：被测行为_条件_预期结果
例如：
  get_expiredEntry_returnsNull
  getOrAwait_concurrentCalls_invokesFetchOnce
  parseImdbCsv_quotedFieldWithComma_parsesCorrectly
```

---

## 3. B 层 - Repository 层测试（12 个测试类）

| # | 被测类 | 测试类名 | Mock 策略 | 关键测试点 | Robolectric |
|---|---|---|---|---|---|
| 1 | `UpdateRepository` | `UpdateRepositoryTest` | mock GitHub/Gitee API + ChangelogStorage | checkForUpdate 三链路（GitHub→Gitee→缓存）、24h 缓存命中、force 跳过缓存、isNewerVersion 矩阵、sanitizeChangelog 乱码阈值、injectDateIntoChangelog 多格式、fetchDownloadUrl 多 APK 选最小 | 是 (BuildConfig.VERSION_NAME) |
| 2 | `UserReviewRepository` | `UserReviewRepositoryTest` | mock UserReviewDao | CRUD 转发、getReviewsByType 过滤、delete 不存在的 id、空表 | 否 |
| 3 | `RatingsRepository` | `RatingsRepositoryTest` | mock OmdbApiService + RemoteConfigProvider | LRU 缓存命中、OMDB 失败降级用 tmdbRating、Flow 多次收集去重、imdbId 为空 | 是 (BuildConfig.OMDB_API_KEY) |
| 4 | `CloudDetailsPoolManager` | `CloudDetailsPoolManagerTest` | mock GiteeContentsApi | uploadDetails 批量、downloadDetail 单条、fetchAndMergeToLocal 合并去重、AesCrypto 加密往返 | 否 |
| 5 | `CloudFailureSyncManager` | `CloudFailureSyncManagerTest` | mock GiteeContentsApi + DoubanSyncFailureDao + DoubanAuthStorage | isLoggedIn、uploadIfHasFailures 空列表跳过、downloadAndMerge 三结果（CloudEmpty/LocalNewer/Success）、AesCrypto 加密 | 否 |
| 6 | `CloudPersonalSyncManager` | `CloudPersonalSyncManagerTest` | mock GiteeContentsApi + 3 个 DAO/Storage + TraktRepository | uploadAll、refreshMetaOnly、downloadAndMerge PullResult、metaRefreshMutex 并发 | 否 |
| 7 | `DoubanRetryManager` | `DoubanRetryManagerTest` | mock DoubanSyncFailureDao + DoubanSyncManager + CloudDetailsPoolManager | refreshRetryState、batchUpdateMediaType、inferMediaTypeFromDetail、batchDeleteFailures、RetryState 转换 | 否 |
| 8 | `DoubanFailureExporter` | `DoubanFailureExporterTest` | mock DoubanSyncFailureDao + Json | exportFromFile JSON 序列化、importFromFile 往返、importToRoom ImportResult、损坏 JSON 处理；FileProvider I/O 部分留待 instrumented test | 是 (Context/Uri) |
| 9 | `DoubanBatchRemovalManager` | `DoubanBatchRemovalManagerTest` | mock DoubanRepository + DoubanAuthStorage + DoubanSyncedItemDao | startRemoval 批量、Semaphore(2) 并发控制、单条失败不影响其他、isRunning/resetProgress、WakeLock | 是 (Context/PowerManager) |
| 10 | `DoubanTraktStatusConsistencyChecker` | `DoubanTraktStatusConsistencyCheckerTest` | mock 5 个依赖 | checkAndUnify 一致性比对、cancel 中断、checkProgress StateFlow、lastConsistencyCheckStorage 时间戳 | 是 (Context) |
| 11 | `DoubanSyncManager` | `DoubanSyncManagerTest` | mock 13 个依赖 | startSync(mode)、isRunning/cancel、resetProgress、clearPendingItems、restoreRollback、WakeLock、Progress StateFlow | 是 (Context) |
| 12 | `WeatherRepository` | `WeatherRepositoryTest` | mock OpenMeteoApi | getCurrentWeather 缓存命中（3h TTL）、location 为 null、网络失败返回缓存、cache 过期重新请求 | 是 (Context/DataStore) |

### 跳过的 Repository（体量过大，单独子项目处理）

- **`TraktRepository`**（70+ 方法，重度 PersistentTtlCache）
- **`TmdbRepository`**（30+ 方法，10 个 PersistentTtlCache）
- **`ResourceRepository`**（依赖 8 个服务，逻辑偏编排，留待集成测试）
- **`DoubanRepository`**（含 Spider + 反爬延迟，网络爬虫类）

### 私有方法测试策略

`UpdateRepository` 的 `isNewerVersion` / `sanitizeChangelog` / `injectDateIntoChangelog` 是纯函数但私有，**通过 public 方法间接覆盖**，不修改生产代码可见性：

- `isNewerVersion`：构造不同 `BuildConfig.VERSION_NAME`（Robolectric 反射设置）+ mock API 返回的 `tag_name`
- `sanitizeChangelog`：mock API 返回含 30%+ 问号的 body，验证 `fetchChangelog()` 返回空
- `injectDateIntoChangelog`：mock API 返回不同 `created_at` 格式，验证最终 changelog 含日期

### 共通测试模式

**缓存命中验证**（UpdateRepository/RatingsRepository/WeatherRepository）：

```kotlin
@Test
fun checkForUpdate_within24h_returnsCachedResult() = runTest {
    every { changelogStorage.getLastCheckTimestamp() } returns now - 23h
    every { changelogStorage.getCachedUpdateInfo() } returns cachedInfo

    val result = repo.checkForUpdate(force = false)

    verify(exactly = 0) { gitHubApi.getLatestRelease(any(), any()) }
    assertThat(result).isEqualTo(cachedWithRecomputedHasUpdate)
}
```

**降级链路验证**（UpdateRepository/RatingsRepository）：

```kotlin
@Test
fun checkForUpdate_githubFails_fallsBackToGitee() = runTest {
    coEvery { gitHubApi.getLatestRelease(any(), any()) } throws IOException()
    coEvery { giteeApi.getLatestRelease(any(), any()) } returns giteeRelease

    val result = repo.checkForUpdate(force = true)

    assertThat(result?.latestVersion).isEqualTo("3.1.0")
}
```

**StateFlow 进度验证**（DoubanSyncManager/ConsistencyChecker/BatchRemovalManager）：

```kotlin
@Test
fun startSync_emitsProgressStates() = runTest {
    val progressStates = mutableListOf<DoubanSyncProgress>()
    val job = launch { doubanSyncManager.progress.toList(progressStates) }

    doubanSyncManager.startSync(mode = SyncMode.FULL_REWRITE)

    job.cancel()
    assertThat(progressStates.first().isRunning).isTrue()
    assertThat(progressStates.last().isRunning).isFalse()
}
```

**WakeLock 验证**：Robolectric 提供 `ShadowPowerManager`，断言 acquire/release 调用次数。

---

## 4. C 层 - ViewModel 层测试（8 个测试类）

| # | 被测 ViewModel | 测试类名 | Fake 策略 | 关键测试点 | Robolectric |
|---|---|---|---|---|---|
| 1 | `SearchViewModel` | `SearchViewModelTest` | mockk relaxed（7 依赖） | loadHotSearches 缓存命中/未命中、search 关键词触发流程、retryDoubanCategory、hotSearches StateFlow 序列 | 否 |
| 2 | `DiscoverFilterViewModel` | `DiscoverFilterViewModelTest` | 手写 Fake TmdbRepo + Fake TraktRepo（4 依赖） | toggleGenre/toggleCountry/toggleDecade 去重、setVoteRange 边界、toggleHideWatched、resetFilters 清空、search 触发、isLoggedIn StateFlow | 否 |
| 3 | `TraktListDetailViewModel` | `TraktListDetailViewModelTest` | 手写 Fake TraktRepository（1 依赖） | retry 重试流程、loadMore 分页、uiState 状态转换（Loading→Success/Error）、watchlistWatchedIds 同步 | 否 |
| 4 | `DiscoverViewModel` | `DiscoverViewModelTest` | mockk relaxed（12 依赖） | loadDoubanHot 缓存、switchRecommendTab、loadTmdbPopular、loadTraktRecommendations、retryAll、forceRefreshAll、toastEvent SharedFlow、resolveAndNavigate 系列 | 是 (Context/R.string) |
| 5 | `DetailViewModel` | `DetailViewModelTest` | mockk relaxed（14 依赖） | loadDetail 状态转换、toggleWatchlist/toggleWatched、setRating/removeRating、toggleSeason/loadEpisodesForMarkWatched、searchResources、translateComments、detailCache LRU（companion 静态，需 reset）、retryDoubanSync | 否 |
| 6 | `SettingsViewModel` | `SettingsViewModelTest` | mockk relaxed（26 依赖） | setThemeMode/setAccentColor/setDefaultTab/setLanguage 持久化、addCustomSource/testCustomSource、exportData/importFromImdb（Uri 用 Robolectric）、clearCache/clearCategory、refreshCacheInfo、checkUpdate/loadChangelog、startManualConsistencyCheck、setSectionVisible/setSectionOrder | 是 (Context/Uri) |
| 7 | `WatchlistViewModel` | `WatchlistViewModelTest` | mockk relaxed（9 依赖） | 批量移除触发豆瓣同步移除（Semaphore(2)）、isDoubanLoggedIn、startDoubanSync(mode)、filterState、needFirstSyncGuide、syncCompleteEvent/consistencyCheckCompleteEvent SharedFlow | 是 (Context) |
| 8 | `StatisticsViewModel` | `StatisticsViewModelTest` | 手写 Fake（3 依赖） | loadStatistics 数据聚合、空数据、R.string 取值（Robolectric） | 是 (Context/R.string) |

### 跳过的 ViewModel

- **`TraktSearchViewModel`** — 搜索/导航逻辑与 SearchViewModel 重叠，enrich 限流逻辑偏实现细节
- **`PersonViewModel`** — 4 个 `android.util.LruCache` 静态持有，Robolectric 测试不稳定，核心逻辑是 TMDB API 转发
- **`UpdateCheckViewModel`** — 仅转发 UpdateRepository，已被 B 层覆盖

### Fake vs Mockk 混合策略

| ViewModel | 策略 | 理由 |
|---|---|---|
| SearchViewModel (7 依赖) | mockk relaxed | 依赖多，仅需少量方法被调用 |
| DiscoverFilterViewModel (4 依赖) | 手写 Fake TmdbRepo + Fake TraktRepo | 依赖少，需复杂状态记录 |
| TraktListDetailViewModel (1 依赖) | 手写 Fake TraktRepository | 仅 1 依赖，Fake 最简洁 |
| DiscoverViewModel (12 依赖) | mockk relaxed | 依赖多 |
| DetailViewModel (14 依赖) | mockk relaxed | 依赖多 |
| SettingsViewModel (26 依赖) | mockk relaxed | 依赖最多 |
| WatchlistViewModel (9 依赖) | mockk relaxed | 依赖多 |
| StatisticsViewModel (3 依赖) | 手写 Fake | 依赖少 |

**TraktRepository 是 `open class`（非 interface）**，手写 Fake 时继承重写；mockk 时用 `mockk<TraktRepository>(relaxed = true)` 配合 `coEvery` 精确桩。

### 共通测试模式

**MainDispatcherRule**（所有 ViewModel 测试统一用）：

```kotlin
class MainDispatcherRule(
    private val dispatcher: TestDispatcher = StandardTestDispatcher()
) : TestWatcher() {
    override fun starting(description: Description) {
        Dispatchers.setMain(dispatcher)
    }
    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}
```

**StateFlow 测试**（turbine）：

```kotlin
@Test
fun loadDetail_emitsLoadingThenSuccess() = runTest {
    coEvery { tmdbRepository.getMovieDetail(any(), any()) } returns sampleMovieDetail

    viewModel.loadDetail(traktId = 1, tmdbId = 100, ...)

    viewModel.uiState.test {
        assertThat(awaitItem().isLoading).isTrue()
        val success = awaitItem()
        assertThat(success.isLoading).isFalse()
        assertThat(success.movieDetail).isNotNull()
        cancelAndIgnoreRemainingEvents()
    }
}
```

**SharedFlow 事件测试**（turbine）：

```kotlin
@Test
fun startDoubanSync_emitsSyncCompleteEvent() = runTest {
    coEvery { doubanSyncManager.startSync(any()) } returns true

    viewModel.syncCompleteEvent.test {
        viewModel.startDoubanSync(SyncMode.FULL_REWRITE)
        assertThat(awaitItem()).isEqualTo(Unit)
    }
}
```

### 静态状态隔离

- **`DetailViewModel.detailCache`**（companion object 静态 LRU）— 每个测试 `@After` 调用 `DetailViewModel.detailCache.clear()`
- **`PersistentTtlCache`** — 每个测试新建实例，不共享

### ViewModel 测试边界

**验证**：
1. StateFlow 状态转换正确（Loading→Success/Error/空）
2. 对 Repository 的调用参数正确（`coVerify` 断言）
3. 用户操作触发正确的 Repository 调用

**不验证**：
- Repository 内部逻辑（已在 B 层覆盖）
- UI 渲染（需 Compose 插桩测试）
- 网络请求细节（已在 Repository 层 mock）

---

## 5. Bug 发现处理流程

测试目的为「验证现有实现 + 发现 bug」，写测试过程中会发现 bug，需明确处理流程。

### Bug 分级

**P0 - 阻断测试（必须立即修复）**
- 现有实现对正常输入崩溃（NPE、IndexOutOfBounds、除零等）
- 现有实现逻辑明显错误（如缓存永不命中、降级链路反了）
- 处理：**立即修复生产代码**，再继续写测试锁定。修复遵循最小改动原则，单独 commit。

**P1 - 不阻断但需修复（修复后再锁定）**
- 边界场景行为不符合预期（如 TTL=0 时立即过期而非永不过期）
- 错误路径返回值不友好（如网络失败返回 null 而非空列表）
- 处理：**在测试中用 `@Ignore("待修复: 简述问题")` 标注**，记录期望行为。继续推进其他测试，不阻塞整体进度。

**P2 - 可疑行为（记录但不修复）**
- 实现可能有问题但不确定是否是 bug
- 行为与注释不一致但可能是有意为之
- 处理：**在测试注释中记录疑点**，测试按当前实际行为写（characterization test），注释标 `// FIXME: 当前行为可能不符合预期，待确认`。不修改生产代码。

### 决策树

```
发现疑似 bug
    │
    ├─ 是否阻断测试编写？
    │   ├─ 是 → P0：立即修复生产代码
    │   └─ 否 ↓
    │
    ├─ 行为是否明显错误？
    │   ├─ 是 → P1：@Ignore + 记录，继续其他测试
    │   └─ 否 → P2：characterization test + 注释疑点
```

### 标注规范

```kotlin
// P1 示例
@Ignore("待修复: TtlCache 在 maxSize=1 时 LRU 淘汰异常")
@Test
fun put_maxSizeOne_evictsLeastRecentlyUsed() { ... }

// P2 示例
@Test
fun get_expiredEntry_returnsNull() {
    // FIXME: 当前实现 Long.MAX_VALUE 跳过过期检查,文档未说明此行为是否预期
    val cache = TtlCache<String>(ttlMillis = Long.MAX_VALUE)
    cache.put("k", "v")
    assertThat(cache.get("k")).isEqualTo("v")
}
```

### 任务完成标准

- 所有非 `@Ignore` 测试通过
- 所有 `@Ignore` 测试有明确的待修复说明
- P0 bug 已修复并 commit
- P1/P2 bug 已记录在测试标注中

**不要求所有 `@Ignore` 都修复**。P1 的修复可以在测试任务之后单独进行。

### P0 修复 commit 规范

遵循 [AGENTS.md](file:///f:/trae-project/AGENTS.md) Git 规范：`fix: <类名> <简述问题>`

---

## 6. 实现顺序与验证策略

### 阶段顺序

**阶段 1：基础设施（先行）**
1. `gradle/libs.versions.toml` 加 6 个测试依赖版本
2. `app/build.gradle.kts` 加 testImplementation + testOptions
3. `app/src/test/resources/robolectric.properties`
4. `app/src/test/java/com/tracktosearch/test/MainDispatcherRule.kt`
5. `app/src/test/java/com/tracktosearch/test/TestFixture.kt`（按需扩充）
6. 运行 `.\gradlew test` 验证现有 ReviewTokenizerTest 仍通过（回归保护）

**阶段 2：A 层 - 纯函数工具类（13 个，按风险递增）**
1. `EnumsTest`（SyncMode/PanHubPlugin）— 最简单，验证基础设施
2. `HolidayDetectorTest`
3. `ScrollToTopProviderTest`
4. `ReviewTokenizerTest`（扩充已有）
5. `DataExportImportTest`
6. `JsonPathParserTest`
7. `DoubanSyncFailureTest`
8. `TtlCacheTest`
9. `AesCryptoTest`（Robolectric 首次启用）
10. `DoubanSpiderTest`（需 fixture HTML）
11. `GiteeContentsApiTest`（MockWebServer）
12. `PersistentTtlCacheTest`
13. `PosterColorExtractorTest`

每个测试类写完后立即运行，确保通过。

**阶段 3：B 层 - Repository（12 个，依赖少优先）**
1. `UserReviewRepositoryTest`（1 依赖）
2. `RatingsRepositoryTest`
3. `CloudDetailsPoolManagerTest`
4. `CloudFailureSyncManagerTest`
5. `CloudPersonalSyncManagerTest`
6. `DoubanRetryManagerTest`
7. `UpdateRepositoryTest`
8. `DoubanFailureExporterTest`
9. `WeatherRepositoryTest`
10. `DoubanBatchRemovalManagerTest`
11. `DoubanTraktStatusConsistencyCheckerTest`
12. `DoubanSyncManagerTest`（依赖最多，放最后）

**阶段 4：C 层 - ViewModel（8 个，构造参数少 + Robolectric 依赖少优先）**
1. `TraktListDetailViewModelTest`（1 依赖，无 Robolectric）
2. `SearchViewModelTest`（7 依赖，无 Robolectric）
3. `DiscoverFilterViewModelTest`（4 依赖，无 Robolectric）
4. `StatisticsViewModelTest`（3 依赖，Robolectric）
5. `DetailViewModelTest`（14 依赖，静态缓存需 reset）
6. `DiscoverViewModelTest`（12 依赖，Robolectric）
7. `WatchlistViewModelTest`（9 依赖，Robolectric）
8. `SettingsViewModelTest`（26 依赖，最复杂，放最后）

### 验证策略

**单测试类验证**（每写完一个）：

```bash
.\gradlew test --tests "com.tracktosearch.data.util.TtlCacheTest"
```

仅运行该测试类，快速反馈。失败立即修复，不累积。

**阶段验证**（每阶段结束）：

```bash
.\gradlew test
```

运行全部单元测试，确保无回归。按 AGENTS.md 用户规则：单元测试不需要构建 APK。

**最终验证**（全部完成）：

```bash
.\gradlew test
.\gradlew assembleDebug
```

单元测试全绿 + debug 包构建成功。

### 阶段内 commit 策略

按 AGENTS.md「修复 bug + 修改/增加功能 + 修改 UI 合计超过三个，构建验证后先本地提交一次」：

- **阶段 1 完成后**：`test: 添加单元测试基础设施(依赖+Robolectric+工具类)`
- **阶段 2 每 3-4 个测试类**：`test: 添加 A 层纯函数单元测试(<类名列表>)`
- **阶段 3 每 3-4 个测试类**：`test: 添加 Repository 层单元测试(<类名列表>)`
- **阶段 4 每 2-3 个测试类**：`test: 添加 ViewModel 层单元测试(<类名列表>)`
- **P0 bug 修复**：独立 commit `fix: <类名> <简述>`

### 失败处理

- 单个测试类编译失败：修复后继续，不跳过
- Robolectric 配置问题（如 SDK 不兼容）：降级该测试为 `@Ignore`，记录原因，不阻塞其他测试
- mockk 桩冲突（relaxed mock 与精确桩冲突）：切换该测试为手写 Fake
- fixture HTML 缺失（DoubanSpider）：先写最小合成 HTML 测试解析逻辑，标注 `// TODO: 用真实 HTML 替换`

### 预估规模

- A 层 13 个测试类，每类 5-15 个用例 ≈ 130 个用例
- B 层 12 个测试类，每类 8-20 个用例 ≈ 180 个用例
- C 层 8 个测试类，每类 10-25 个用例 ≈ 150 个用例
- 合计 ≈ 460 个测试用例，33 个测试类
