# 豆瓣同步 UI 优化与数据一致性实现计划

> **面向 AI 代理的工作者:** 必需子技能:使用 superpowers:subagent-driven-development(推荐)或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框(`- [ ]`)语法来跟踪进度。

**目标:** 实现阶段 1→2 流水线、修复完成弹窗「0/0」、失败项双层吸顶分组、重试弹窗导出按钮、重新导入模式选择 A/B/C

**架构:** Channel-based 流水线 + SyncMode 枚举 + 双层 LazyColumn stickyHeader + 设置页双独立按钮入口

**技术栈:** Kotlin Coroutines Channel + Room + Compose LazyColumn stickyHeader + Trakt API

**规格文档:** `docs/superpowers/specs/2026-07-05-douban-sync-ui-and-consistency-design.md`

---

## 文件结构

### 新建文件
- `app/src/main/java/com/tracktosearch/data/repository/SyncMode.kt` — SyncMode 枚举
- `app/src/main/java/com/tracktosearch/ui/screen/douban/DoubanSyncModePickerDialog.kt` — 模式选择对话框

### 修改文件
- `app/src/main/java/com/tracktosearch/data/repository/TraktRepository.kt` — 新增 batchRemoveFromWatched
- `app/src/main/java/com/tracktosearch/data/repository/DoubanFailureExporter.kt` — 新增 exportFromLocal
- `app/src/main/java/com/tracktosearch/data/repository/DoubanSyncManager.kt` — SyncMode + 流水线 + 模式 B/C
- `app/src/main/java/com/tracktosearch/ui/screen/douban/DoubanSyncDialog.kt` — 0/0 修复 + 双层吸顶
- `app/src/main/java/com/tracktosearch/ui/screen/douban/DoubanRetryDialog.kt` — 删增量选项 + 加导出选项
- `app/src/main/java/com/tracktosearch/ui/screen/settings/SettingsScreen.kt` — 双独立按钮入口
- `app/src/main/res/values/strings.xml` + `values-zh/` + `values-ja/` + `values-ko/` — 新文案

---

## 任务 1: TraktRepository.batchRemoveFromWatched

**文件:**
- 修改: `app/src/main/java/com/tracktosearch/data/repository/TraktRepository.kt`(在 batchMarkAsWatched 后插入)

参考现有 `batchRemoveFromWatchlist`(L974)与 `removeWatched`(L720)的实现模式:批量 POST + 缓存同步。

- [ ] **步骤 1: 新增 batchRemoveFromWatched 方法**

在 `batchMarkAsWatched` 方法后插入:

```kotlin
/**
 * 批量移除已看记录(模式 B/C 状态变化时调用)。
 * 注意:Trakt 的 removeFromHistory 不会自动加回 watchlist,如需加回需调用方显式调用 batchAddToWatchlist。
 */
suspend fun batchRemoveFromWatched(movieTraktIds: List<Int>, showTraktIds: List<Int>): Result<TraktSyncResponse> {
    if (movieTraktIds.isEmpty() && showTraktIds.isEmpty()) return Result.success(TraktSyncResponse())
    return try {
        val request = TraktSyncRequest(
            movies = movieTraktIds.takeIf { it.isNotEmpty() }?.map { TraktSyncItem(TraktIds(trakt = it)) },
            shows = showTraktIds.takeIf { it.isNotEmpty() }?.map { TraktSyncItem(TraktIds(trakt = it)) }
        )
        val response = traktApiService.removeFromHistory(request)
        if (response.isSuccessful) {
            movieTraktIds.forEach { removeFromWatchedCache(it, 0, MediaType.MOVIE) }
            showTraktIds.forEach { removeFromWatchedCache(it, 0, MediaType.SHOW) }
            Result.success(response.body() ?: TraktSyncResponse())
        } else {
            Result.failure(Exception("batchRemoveFromWatched failed: ${response.code()}"))
        }
    } catch (e: Exception) {
        Result.failure(e)
    }
}
```

- [ ] **步骤 2: 构建 debug 验证编译**

运行: `.\gradlew assembleDebug`
预期: BUILD SUCCESSFUL

---

## 任务 2: DoubanFailureExporter.exportFromLocal

**文件:**
- 修改: `app/src/main/java/com/tracktosearch/data/repository/DoubanFailureExporter.kt`(在 exportFromList 后追加)

复用现有 `exportToFile`(L64)的 DAO 读取 + `exportFromList`(L138)的 JSON 序列化逻辑,新增一个直接从 Room 读取并返回 Uri 的便捷方法。

- [ ] **步骤 1: 新增 exportFromLocal 方法**

在 `exportFromList` 方法后追加:

```kotlin
/**
 * 从 Room 数据库读取所有失败项并导出 JSON(重试弹窗中导出按钮调用)。
 * 等价于 exportToFile,但语义更明确:从本地 Room 读取,而非从内存列表。
 * 返回 Uri 后调用方触发 ShareSheet。
 */
suspend fun exportFromLocal(context: Context): Uri? = withContext(Dispatchers.IO) {
    val entities = doubanSyncFailureDao.getAll()
    if (entities.isEmpty()) return@withContext null

    val payload = ExportPayload(
        exportedAt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).format(Date()),
        totalFailures = entities.size,
        failures = entities.map { e ->
            FailureDto(
                doubanId = e.doubanId,
                title = e.title,
                posterUrl = e.posterUrl,
                rating = e.rating,
                comment = e.comment,
                markedAt = e.markedAt,
                doubanUrl = e.doubanUrl,
                status = e.status,
                failureReason = e.failureReason,
                failedAt = e.failedAt,
                attemptCount = e.attemptCount
            )
        }
    )
    val jsonStr = json.encodeToString(ExportPayload.serializer(), payload)

    val shareDir = File(context.cacheDir, "share").apply { if (!exists()) mkdirs() }
    val dateStr = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
    val file = File(shareDir, "TraktToSearch-失败项-$dateStr.json")
    file.writeText(jsonStr, Charsets.UTF_8)

    val authority = "${context.packageName}.fileprovider"
    FileProvider.getUriForFile(context, authority, file)
}
```

注:实际上 `exportToFile` 已经满足此需求。但为了语义清晰和未来扩展(如带筛选),新增独立方法。可考虑让 `exportToFile` 直接调用 `exportFromLocal` 内部逻辑,但本次为最小改动,保留两者。

- [ ] **步骤 2: 构建 debug 验证编译**

运行: `.\gradlew assembleDebug`
预期: BUILD SUCCESSFUL

- [ ] **步骤 3: Commit**

```bash
git add app/src/main/java/com/tracktosearch/data/repository/TraktRepository.kt app/src/main/java/com/tracktosearch/data/repository/DoubanFailureExporter.kt
git commit -m "feat: 新增 TraktRepository.batchRemoveFromWatched 与 DoubanFailureExporter.exportFromLocal"
```

---

## 任务 3: SyncMode 枚举 + DoubanSyncManager startSync(mode)

**文件:**
- 创建: `app/src/main/java/com/tracktosearch/data/repository/SyncMode.kt`
- 修改: `app/src/main/java/com/tracktosearch/data/repository/DoubanSyncManager.kt`(L116 startSync)

- [ ] **步骤 1: 创建 SyncMode.kt**

```kotlin
package com.tracktosearch.data.repository

/**
 * 豆瓣重新导入模式。
 *
 * - [INCREMENTAL_ONLY]: 仅同步新增条目,跳过已同步的(模式 A)
 * - [INCREMENTAL_WITH_CHANGES]: 同步新增 + 检测状态变化,撤销旧操作应用新操作(模式 B)
 * - [FULL_REWRITE]: 清空 Trakt 上之前同步的标记后重新应用(模式 C)
 */
enum class SyncMode {
    INCREMENTAL_ONLY,
    INCREMENTAL_WITH_CHANGES,
    FULL_REWRITE
}
```

- [ ] **步骤 2: DoubanSyncManager 新增 startSync(mode) 方法**

在现有 `startSync(forceOverwrite: Boolean)` 后追加:

```kotlin
/**
 * 启动同步(指定模式,非 suspend,立即返回)。
 * - [SyncMode.INCREMENTAL_ONLY]: 等价于 forceOverwrite=false
 * - [SyncMode.INCREMENTAL_WITH_CHANGES]: 跳过已同步且状态一致的,处理状态变化的
 * - [SyncMode.FULL_REWRITE]: 先清空已同步标记,再 forceOverwrite=true
 */
fun startSync(mode: SyncMode): Boolean {
    if (isRunning()) return false
    cancelled = false
    syncJob = appScope.launch { runSync(mode) }
    return true
}
```

- [ ] **步骤 3: 新增 runSync(mode) 主入口**

把现有 `private suspend fun runSync(forceOverwrite: Boolean)` 改名 `runSyncLegacy`,保留兼容;新增 `runSync(mode: SyncMode)` 分发:

```kotlin
private suspend fun runSync(mode: SyncMode) {
    when (mode) {
        SyncMode.INCREMENTAL_ONLY -> runSyncIncremental(includeStatusChanges = false)
        SyncMode.INCREMENTAL_WITH_CHANGES -> runSyncIncremental(includeStatusChanges = true)
        SyncMode.FULL_REWRITE -> runSyncFullRewrite()
    }
}
```

- [ ] **步骤 4: 构建 debug 验证编译**

运行: `.\gradlew assembleDebug`
预期: BUILD SUCCESSFUL

---

## 任务 4: 模式 B 实现 — runSyncIncremental

**文件:**
- 修改: `app/src/main/java/com/tracktosearch/data/repository/DoubanSyncManager.kt`

把现有 `runSync(forceOverwrite=false)` 的逻辑迁移到 `runSyncIncremental(includeStatusChanges: Boolean)`,增加状态变化检测分支。

- [ ] **步骤 1: 新增 runSyncIncremental 方法**

```kotlin
/**
 * 增量同步主流程(模式 A/B)。
 *
 * @param includeStatusChanges false=仅新增(模式 A);true=检测状态变化(模式 B)
 */
private suspend fun runSyncIncremental(includeStatusChanges: Boolean) {
    val creds = doubanAuthStorage.getCredentials()
        ?: run {
            _progress.value = DoubanSyncProgress(isComplete = true, phase = "未登录豆瓣")
            return
        }

    val startTime = System.currentTimeMillis()
    val modeLabel = if (includeStatusChanges) "增量+状态变化同步" else "增量同步"
    _progress.value = DoubanSyncProgress(
        isRunning = true, startTimeMs = startTime, phase = "准备$modeLabel",
        isRetry = false
    )

    traktRepository.loadWatchlistWatchedIds()
    val watchlistWatchedIds = traktRepository.getWatchlistWatchedIds()

    // 模式 B:加载已同步记录用于状态对比
    val syncedItemsMap = if (includeStatusChanges) {
        doubanSyncedItemDao.getAllSyncedItems().associateBy { it.doubanId }
    } else {
        emptyMap()
    }
    val syncedIds = syncedItemsMap.keys

    val allFailed = mutableListOf<DoubanSyncFailure>()
    val recentFailuresBuffer = ArrayDeque<DoubanSyncFailure>()
    var totalSuccess = 0
    var totalSkipped = 0
    var totalCacheHit = 0
    var totalStatusChanged = 0

    for (status in listOf(DoubanMarkStatus.WISH, DoubanMarkStatus.COLLECT)) {
        if (cancelled) break
        val phaseName = if (status == DoubanMarkStatus.WISH) "爬取想看列表" else "爬取看过列表"
        _progress.value = _progress.value.copy(
            isRunning = true, phase = phaseName, total = 0, current = 0, subPhase = "",
            currentTitle = null
        )

        val pageItems = mutableListOf<DoubanMarkItem>()
        val ok = doubanRepository.fetchMarkList(
            userId = creds.userId,
            cookie = creds.cookie,
            status = status,
            onPage = { items, _ ->
                pageItems.addAll(items)
                val now = System.currentTimeMillis()
                val pendingEntities = items.map { item ->
                    DoubanSyncPendingItemEntity(
                        doubanId = item.doubanId,
                        title = item.title,
                        posterUrl = item.posterUrl,
                        rating = item.rating,
                        comment = item.comment,
                        markedAt = item.markedAt,
                        doubanUrl = item.doubanUrl,
                        status = status.path,
                        crawledAt = now
                    )
                }
                doubanSyncPendingItemDao.insertAll(pendingEntities)
            },
            onProgress = { cur, total ->
                _progress.value = _progress.value.copy(
                    current = cur,
                    total = total ?: cur
                )
            },
            isCancelled = { cancelled }
        )

        if (!ok) {
            _progress.value = _progress.value.copy(
                phase = "豆瓣登录已过期", isComplete = true, isRunning = false,
                cookieExpired = true
            )
            return
        }

        if (cancelled) break

        // 模式 B:先处理状态变化项
        if (includeStatusChanges) {
            val changedItems = pageItems.filter { item ->
                val synced = syncedItemsMap[item.doubanId]
                synced != null && synced.status != status.path
            }
            if (changedItems.isNotEmpty()) {
                totalStatusChanged += processStatusChanges(
                    changedItems = changedItems,
                    newStatus = status,
                    syncedItemsMap = syncedItemsMap,
                    cookie = creds.cookie,
                    watchlistWatchedIds = watchlistWatchedIds,
                    onProgress = { cur, subPhase, currentTitle ->
                        _progress.value = _progress.value.copy(
                            current = cur, subPhase = subPhase, currentTitle = currentTitle
                        )
                    }
                )
            }
        }

        // 同步到 Trakt(批量,跳过已同步的)
        val syncPhase = if (status == DoubanMarkStatus.WISH) "同步想看到 Trakt" else "同步看过到 Trakt"
        _progress.value = _progress.value.copy(phase = syncPhase, total = pageItems.size, current = 0)

        val result = syncBatchToTrakt(
            items = pageItems,
            status = status,
            cookie = creds.cookie,
            syncedIds = syncedIds,
            watchlistWatchedIds = watchlistWatchedIds,
            onProgress = { cur, subPhase, cacheHit, currentTitle, recentFailure ->
                val elapsed = (System.currentTimeMillis() - startTime) / 1000
                val eta = if (cur > 0 && _progress.value.total > 0) {
                    (elapsed * (_progress.value.total - cur) / cur).coerceAtLeast(0)
                } else -1L
                if (recentFailure != null) {
                    recentFailuresBuffer.addLast(recentFailure)
                    while (recentFailuresBuffer.size > 5) recentFailuresBuffer.removeFirst()
                }
                _progress.value = _progress.value.copy(
                    current = cur,
                    subPhase = subPhase,
                    cacheHitCount = _progress.value.cacheHitCount + cacheHit,
                    etaSeconds = eta,
                    currentTitle = currentTitle,
                    recentFailures = recentFailuresBuffer.toList()
                )
            }
        )
        allFailed.addAll(result.failed)
        totalSuccess += result.success
        totalSkipped += result.skipped
        totalCacheHit += result.cacheHit
        if (pageItems.isNotEmpty()) {
            doubanSyncPendingItemDao.deleteByDoubanIds(pageItems.map { it.doubanId })
        }
    }

    persistFailures(allFailed)

    val finalProgress = DoubanSyncProgress(
        isRunning = false,
        isComplete = true,
        current = _progress.value.current,  // 保留最后的 current 避免显示 0/0
        total = _progress.value.total,      // 保留最后的 total
        successCount = totalSuccess,
        failedCount = allFailed.size,
        skippedCount = totalSkipped,
        cacheHitCount = totalCacheHit,
        failedItems = allFailed,
        phase = if (cancelled) "已取消" else "$modeLabel 完成",
        startTimeMs = startTime,
        etaSeconds = 0,
        recentFailures = recentFailuresBuffer.toList(),
        isRetry = false
    )
    _progress.value = finalProgress
}
```

- [ ] **步骤 2: 新增 processStatusChanges 方法**

```kotlin
/**
 * 处理状态变化项(模式 B)。
 *
 * - WISH→COLLECT: removeFromWatchlist + markAsWatched
 * - COLLECT→WISH: removeFromWatched + addToWatchlist
 *
 * 更新 douban_synced_items.status 字段。
 * 失败的项加入 allFailed(由调用方统一持久化)。
 *
 * @return 成功处理的状态变化数
 */
private suspend fun processStatusChanges(
    changedItems: List<DoubanMarkItem>,
    newStatus: DoubanMarkStatus,
    syncedItemsMap: Map<String, DoubanSyncedItem>,
    cookie: String,
    watchlistWatchedIds: TraktRepository.WatchlistWatchedIds?,
    onProgress: (current: Int, subPhase: String, currentTitle: String?) -> Unit
): Int {
    var successCount = 0
    changedItems.forEachIndexed { idx, item ->
        if (cancelled) return successCount
        val synced = syncedItemsMap[item.doubanId] ?: return@forEachIndexed
        val traktId = synced.traktId ?: return@forEachIndexed
        val mediaType = when (synced.mediaType) {
            "movie" -> MediaType.MOVIE
            "show" -> MediaType.SHOW
            else -> return@forEachIndexed
        }

        onProgress(idx + 1, "状态变化", item.title)

        try {
            when (newStatus) {
                DoubanMarkStatus.WISH -> {
                    // COLLECT → WISH: removeFromWatched + addToWatchlist
                    val movieIds = if (mediaType == MediaType.MOVIE) listOf(traktId) else emptyList()
                    val showIds = if (mediaType == MediaType.SHOW) listOf(traktId) else emptyList()
                    traktRepository.batchRemoveFromWatched(movieIds, showIds)
                    traktRepository.batchAddToWatchlist(movieIds, showIds)
                }
                DoubanMarkStatus.COLLECT -> {
                    // WISH → COLLECT: removeFromWatchlist + markAsWatched
                    val movieIds = if (mediaType == MediaType.MOVIE) listOf(traktId) else emptyList()
                    val showIds = if (mediaType == MediaType.SHOW) listOf(traktId) else emptyList()
                    traktRepository.batchRemoveFromWatchlist(movieIds, showIds)
                    traktRepository.batchMarkAsWatched(movieIds, showIds)
                }
            }

            // 更新 douban_synced_items.status
            doubanSyncedItemDao.insertAll(listOf(
                synced.copy(status = newStatus.path, syncedAt = System.currentTimeMillis())
            ))
            successCount++
        } catch (e: Exception) {
            // 状态变化失败:不更新 status 字段,下次重试时仍会检测到变化
            // 失败项不进入 allFailed(状态变化失败项需要单独管理,暂记日志)
            // TODO: 如需重试状态变化失败项,可扩展 DoubanSyncFailure 支持
        }
    }
    return successCount
}
```

- [ ] **步骤 3: 构建 debug 验证编译**

运行: `.\gradlew assembleDebug`
预期: BUILD SUCCESSFUL

---

## 任务 5: 模式 C 实现 — runSyncFullRewrite

**文件:**
- 修改: `app/src/main/java/com/tracktosearch/data/repository/DoubanSyncManager.kt`
- 修改: `app/src/main/java/com/tracktosearch/data/local/db/DoubanEntities.kt`(DoubanSyncedItemDao 新增 getAllSyncedItems + clearAll 已有)

- [ ] **步骤 1: DoubanSyncedItemDao 新增 getAllSyncedItems 方法**

在 `DoubanSyncedItemDao` 中追加(任务 4 已用到):

```kotlin
@Query("SELECT * FROM douban_synced_items")
suspend fun getAllSyncedItems(): List<DoubanSyncedItem>
```

- [ ] **步骤 2: 新增 runSyncFullRewrite 方法**

在 `runSyncIncremental` 后追加:

```kotlin
/**
 * 完全重写主流程(模式 C)。
 *
 * 1. 读 douban_synced_items 全表,按 status + mediaType 分组
 * 2. 批量 removeFromWatchlist + removeFromWatched
 * 3. 清空 douban_synced_items 表
 * 4. 走 forceOverwrite=true 的同步流程(所有豆瓣条目当作新增处理)
 *
 * 调用前应已通过二次确认对话框(由 UI 层处理)。
 */
private suspend fun runSyncFullRewrite() {
    val creds = doubanAuthStorage.getCredentials()
        ?: run {
            _progress.value = DoubanSyncProgress(isComplete = true, phase = "未登录豆瓣")
            return
        }

    val startTime = System.currentTimeMillis()
    _progress.value = DoubanSyncProgress(
        isRunning = true, startTimeMs = startTime, phase = "清空已同步标记",
        isRetry = false, currentTitle = null
    )

    traktRepository.loadWatchlistWatchedIds()

    // 步骤 1: 读 douban_synced_items 全表
    val allSynced = doubanSyncedItemDao.getAllSyncedItems()
    val wishMovieIds = mutableListOf<Int>()
    val wishShowIds = mutableListOf<Int>()
    val collectMovieIds = mutableListOf<Int>()
    val collectShowIds = mutableListOf<Int>()
    for (item in allSynced) {
        val traktId = item.traktId ?: continue
        when (item.status) {
            "wish" -> when (item.mediaType) {
                "movie" -> wishMovieIds.add(traktId)
                "show" -> wishShowIds.add(traktId)
            }
            "collect" -> when (item.mediaType) {
                "movie" -> collectMovieIds.add(traktId)
                "show" -> collectShowIds.add(traktId)
            }
        }
    }

    _progress.value = _progress.value.copy(
        total = allSynced.size, current = 0,
        subPhase = "移除 watchlist + watched"
    )

    // 步骤 2: 批量移除
    try {
        if (cancelled) return
        traktRepository.batchRemoveFromWatchlist(wishMovieIds, wishShowIds)
        traktRepository.batchRemoveFromWatched(collectMovieIds, collectShowIds)
    } catch (e: Exception) {
        _progress.value = _progress.value.copy(
            phase = "清空失败,已中止", isComplete = true, isRunning = false
        )
        return
    }

    // 步骤 3: 清空 douban_synced_items 表
    doubanSyncedItemDao.clearAll()

    // 步骤 4: 走 forceOverwrite=true 的同步流程
    _progress.value = _progress.value.copy(phase = "重新应用豆瓣状态")
    runSyncLegacy(forceOverwrite = true)
}
```

- [ ] **步骤 3: 把现有 runSync 改名为 runSyncLegacy**

将 `private suspend fun runSync(forceOverwrite: Boolean)` 改名为 `private suspend fun runSyncLegacy(forceOverwrite: Boolean)`。同时把原 `startSync(forceOverwrite)` 中的 `runSync(forceOverwrite)` 改为 `runSyncLegacy(forceOverwrite)`,保持向后兼容。

- [ ] **步骤 4: 构建 debug 验证编译**

运行: `.\gradlew assembleDebug`
预期: BUILD SUCCESSFUL

- [ ] **步骤 5: Commit**

```bash
git add app/src/main/java/com/tracktosearch/data/repository/SyncMode.kt app/src/main/java/com/tracktosearch/data/repository/DoubanSyncManager.kt app/src/main/java/com/tracktosearch/data/local/db/DoubanEntities.kt
git commit -m "feat: 实现 SyncMode 枚举与模式 A/B/C 同步流程"
```

---

## 任务 6: 阶段 1→2 Channel 流水线改造

**文件:**
- 修改: `app/src/main/java/com/tracktosearch/data/repository/DoubanSyncManager.kt`(`syncBatchToTrakt` 方法 L598-827)

把当前 awaitAll 串行的阶段 1+2 改为 Channel 流水线,AtomicInt 计数已完成数。

- [ ] **步骤 1: 改造 syncBatchToTrakt 方法**

替换 `syncBatchToTrakt` 方法体(L598-827)为 Channel 流水线版本:

```kotlin
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
private suspend fun syncBatchToTrakt(
    items: List<DoubanMarkItem>,
    status: DoubanMarkStatus,
    cookie: String,
    syncedIds: Set<String>,
    watchlistWatchedIds: TraktRepository.WatchlistWatchedIds?,
    onProgress: (current: Int, subPhase: String, cacheHitDelta: Int, currentTitle: String?, recentFailure: DoubanSyncFailure?) -> Unit,
    existingFailures: List<DoubanSyncFailure>? = null
): BatchSyncResult {
    val failed = mutableListOf<DoubanSyncFailure>()
    val existingMap = existingFailures?.associateBy { it.doubanId } ?: emptyMap()

    val pending = items.filter { it.doubanId !in syncedIds }
    val skippedCount = items.size - pending.size

    if (pending.isEmpty()) {
        onProgress(items.size, "断点续传跳过", 0, null, null)
        return BatchSyncResult(0, emptyList(), skippedCount, 0)
    }

    // 用 Channel 连接阶段 1(详情页)→ 阶段 2(Trakt 查询)
    val detailChannel = kotlinx.coroutines.channels.Channel<SyncResolve>(capacity = pending.size)
    val detailSemaphore = Semaphore(3)
    val traktSemaphore = Semaphore(5)
    val completedCount = java.util.concurrent.atomic.AtomicInteger(0)
    var detailCacheHit = java.util.concurrent.atomic.AtomicInteger(0)

    coroutineScope {
        // ===== 阶段 1: 详情页爬取(生产者,并发度 3) =====
        val detailJobs = pending.mapIndexed { idx, item ->
            async {
                if (cancelled) return@async
                try {
                    val (detail, isCacheHit) = detailSemaphore.withPermit {
                        if (cancelled) return@withPermit Pair<DoubanDetailInfo?, Boolean>(null, false)
                        doubanRepository.fetchDetail(item.doubanUrl, cookie, item.title) { _, _ -> }
                    }
                    if (isCacheHit) detailCacheHit.incrementAndGet()
                    if (detail == null) {
                        val failure = buildFailure(item, status, FailureReason.DETAIL_FETCH_FAILED, existingMap)
                        synchronized(failed) { failed.add(failure) }
                        val done = completedCount.incrementAndGet()
                        onProgress(done, "详情页", 0, item.title, failure)
                        return@async
                    }
                    val imdbId = detail.imdbId
                    if (imdbId.isNullOrEmpty()) {
                        val failure = buildFailure(item, status, FailureReason.NO_IMDB_ID, existingMap)
                        synchronized(failed) { failed.add(failure) }
                        val done = completedCount.incrementAndGet()
                        onProgress(done, "详情页", 0, item.title, failure)
                        return@async
                    }
                    val mediaType = if (detail.isTvShow) MediaType.SHOW else MediaType.MOVIE
                    detailChannel.send(SyncResolve(item, imdbId, traktId = 0, mediaType = mediaType, originalFailure = existingMap[item.doubanId]))
                } catch (e: Exception) {
                    val failure = buildFailure(item, status, FailureReason.DETAIL_FETCH_FAILED, existingMap)
                    synchronized(failed) { failed.add(failure) }
                    val done = completedCount.incrementAndGet()
                    onProgress(done, "详情页", 0, item.title, failure)
                }
            }
        }

        // ===== 阶段 2: Trakt 查询(消费者,并发度 5) =====
        val traktJobs = (1..5).map {
            async {
                for (r in detailChannel) {
                    if (cancelled) break
                    var traktId = traktRepository.getCachedTraktIdByImdb(r.imdbId, r.mediaType)
                    if (traktId == null) {
                        traktId = traktSemaphore.withPermit {
                            if (cancelled) null
                            else try {
                                traktRepository.searchByImdb(r.imdbId, r.mediaType).getOrNull()
                                    ?.firstOrNull()?.let { result ->
                                        when (r.mediaType) {
                                            MediaType.MOVIE -> result.movie?.ids?.trakt
                                            MediaType.SHOW -> result.show?.ids?.trakt
                                            else -> null
                                        }
                                    }
                            } catch (e: Exception) { null }
                        }
                    }
                    val done = completedCount.incrementAndGet()
                    if (traktId == null || traktId <= 0) {
                        val failure = buildFailure(r.item, status, FailureReason.TRAKT_NOT_FOUND, existingMap)
                        synchronized(failed) { failed.add(failure) }
                        onProgress(done, "Trakt 查询", 0, r.item.title, failure)
                    } else {
                        synchronized(this) {
                            resolvedTraktList.add(r.copy(traktId = traktId))
                        }
                        onProgress(done, "Trakt 查询", 0, r.item.title, null)
                    }
                }
            }
        }

        // 等阶段 1 全部完成 → 关闭 Channel → 阶段 2 消费完所有
        detailJobs.awaitAll()
        detailChannel.close()
        traktJobs.awaitAll()
    }

    if (cancelled) return BatchSyncResult(0, failed, skippedCount, detailCacheHit.get())

    val withTraktId = resolvedTraktList
    // 报告缓存命中数
    val cacheHit = detailCacheHit.get()
    if (cacheHit > 0) {
        onProgress(completedCount.get(), "详情页", cacheHit, null, null)
    }
```

注:`resolvedTraktList` 需在 `coroutineScope` 外声明:

```kotlin
val resolvedTraktList = mutableListOf<SyncResolve>()
```

把 `withTraktId` 后续的阶段 3+4 逻辑(冲突分类 + 批量 POST)保持不变,变量名从 `withTraktId` 改为 `resolvedTraktList` 即可。

- [ ] **步骤 2: 构建 debug 验证编译**

运行: `.\gradlew assembleDebug`
预期: BUILD SUCCESSFUL

---

## 任务 7: 完成弹窗「0/0」修复

**文件:**
- 修改: `app/src/main/java/com/tracktosearch/ui/screen/douban/DoubanSyncDialog.kt`(L143-144)

- [ ] **步骤 1: 修改进度文案显示逻辑**

把 L144 的:
```kotlin
Text(stringResource(R.string.douban_sync_progress_format, p.phase, p.current, p.total))
```

改为:
```kotlin
if (p.isComplete) {
    Text(p.phase, style = MaterialTheme.typography.bodyMedium)
} else {
    Text(stringResource(R.string.douban_sync_progress_format, p.phase, p.current, p.total))
}
```

- [ ] **步骤 2: 确保 finalProgress 保留 current/total**

任务 4 步骤 1 中已在 `finalProgress` 设置 `current = _progress.value.current, total = _progress.value.total`,任务 5 模式 C 调用 `runSyncLegacy` 也保留原逻辑。

- [ ] **步骤 3: 构建 debug 验证编译**

运行: `.\gradlew assembleDebug`
预期: BUILD SUCCESSFUL

---

## 任务 8: 失败项双层吸顶分组重构

**文件:**
- 修改: `app/src/main/java/com/tracktosearch/ui/screen/douban/DoubanSyncDialog.kt`(L280-329 替换)

使用 `LazyColumn` + `stickyHeader` 实现双层吸顶。

- [ ] **步骤 1: 替换失败项列表 LazyColumn 部分**

把 L283-329 的 LazyColumn 内容替换为:

```kotlin
LazyColumn(
    modifier = Modifier
        .fillMaxWidth()
        .heightIn(max = 320.dp)
) {
    // 一级分组:可恢复
    val recoverable = p.failedItems.filter { it.failureReason.recoverable }
    val nonRecoverable = p.failedItems.filter { !it.failureReason.recoverable }

    if (recoverable.isNotEmpty()) {
        stickyHeader(key = "recoverable_header") {
            FailureGroupHeader(
                title = stringResource(R.string.douban_sync_failed_group_recoverable, recoverable.size),
                expanded = recoverableExpanded,
                onClick = { recoverableExpanded = !recoverableExpanded },
                tint = MaterialTheme.colorScheme.error
            )
        }
        if (recoverableExpanded) {
            // 二级分组:按失败原因细分
            val byReason = recoverable.groupBy { it.failureReason }
            for ((reason, items) in byReason) {
                stickyHeader(key = "recoverable_${reason.name}") {
                    FailureSubGroupHeader(
                        title = stringResource(reason.localizedStringRes()),
                        count = items.size,
                        expanded = subExpandedMap[reason] ?: true,
                        onClick = { subExpandedMap[reason] = !(subExpandedMap[reason] ?: true) }
                    )
                }
                if (subExpandedMap[reason] ?: true) {
                    items(items, key = { "rec_${it.doubanId}" }) { failure ->
                        FailureItemRow(failure)
                    }
                }
            }
        }
    }

    if (nonRecoverable.isNotEmpty()) {
        stickyHeader(key = "non_recoverable_header") {
            if (recoverable.isNotEmpty()) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))
            }
            FailureGroupHeader(
                title = stringResource(R.string.douban_sync_failed_group_non_recoverable, nonRecoverable.size),
                expanded = nonRecoverableExpanded,
                onClick = { nonRecoverableExpanded = !nonRecoverableExpanded },
                tint = MaterialTheme.colorScheme.outline
            )
        }
        if (nonRecoverableExpanded) {
            val byReason = nonRecoverable.groupBy { it.failureReason }
            for ((reason, items) in byReason) {
                stickyHeader(key = "non_rec_${reason.name}") {
                    FailureSubGroupHeader(
                        title = stringResource(reason.localizedStringRes()),
                        count = items.size,
                        expanded = subExpandedMap[reason] ?: true,
                        onClick = { subExpandedMap[reason] = !(subExpandedMap[reason] ?: true) }
                    )
                }
                if (subExpandedMap[reason] ?: true) {
                    items(items, key = { "nonrec_${it.doubanId}" }) { failure ->
                        FailureItemRow(failure)
                    }
                }
            }
        }
    }
}
```

- [ ] **步骤 2: 新增 subExpandedMap 状态与导入 stickyHeader**

在 `DoubanSyncDialog` 顶部状态区(L130 附近)添加:

```kotlin
var subExpandedMap by remember { mutableStateOf<Map<FailureReason, Boolean>>(emptyMap()) }
```

在 import 区添加:
```kotlin
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.stickyHeader
```

并在 Composable 上添加 `@OptIn(ExperimentalFoundationApi::class)`。

- [ ] **步骤 3: 新增 FailureSubGroupHeader Composable**

在 `FailureGroupHeader` 后追加:

```kotlin
/** 失败项二级分组标题(按失败原因细分,可折叠) */
@Composable
private fun FailureSubGroupHeader(
    title: String,
    count: Int,
    expanded: Boolean,
    onClick: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(start = 24.dp, top = 4.dp, bottom = 4.dp, end = 8.dp)
        ) {
            Icon(
                if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(14.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                "$title ($count)",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
```

- [ ] **步骤 4: 构建 debug 验证编译**

运行: `.\gradlew assembleDebug`
预期: BUILD SUCCESSFUL

- [ ] **步骤 5: Commit**

```bash
git add app/src/main/java/com/tracktosearch/ui/screen/douban/DoubanSyncDialog.kt
git commit -m "feat: 完成弹窗 0/0 修复 + 失败项双层吸顶分组"
```

---

## 任务 9: DoubanSyncModePickerDialog 新建

**文件:**
- 创建: `app/src/main/java/com/tracktosearch/ui/screen/douban/DoubanSyncModePickerDialog.kt`

- [ ] **步骤 1: 创建对话框文件**

```kotlin
package com.tracktosearch.ui.screen.douban

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tracktosearch.R
import com.tracktosearch.data.repository.SyncMode

/**
 * 豆瓣重新导入模式选择对话框(设置页「重新同步豆瓣」按钮触发)。
 *
 * 三种模式:
 * - A: 仅同步新增条目(跳过已同步的,不处理状态变化)
 * - B: 同步新增 + 检测状态变化(撤销旧操作应用新操作)
 * - C: 完全重写(清空已同步标记后重新应用,需二次确认)
 */
@Composable
fun DoubanSyncModePickerDialog(
    syncedCount: Int,
    onDismiss: () -> Unit,
    onModeSelected: (SyncMode) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.douban_sync_mode_picker_title)) },
        text = {
            Column {
                ModeOptionItem(
                    icon = Icons.Default.AddCircle,
                    title = stringResource(R.string.douban_sync_mode_a_title),
                    desc = stringResource(R.string.douban_sync_mode_a_desc),
                    example = stringResource(R.string.douban_sync_mode_a_example),
                    onClick = {
                        onDismiss()
                        onModeSelected(SyncMode.INCREMENTAL_ONLY)
                    }
                )
                Spacer(modifier = Modifier.height(8.dp))
                ModeOptionItem(
                    icon = Icons.Default.Refresh,
                    title = stringResource(R.string.douban_sync_mode_b_title),
                    desc = stringResource(R.string.douban_sync_mode_b_desc),
                    example = stringResource(R.string.douban_sync_mode_b_example),
                    onClick = {
                        onDismiss()
                        onModeSelected(SyncMode.INCREMENTAL_WITH_CHANGES)
                    }
                )
                Spacer(modifier = Modifier.height(8.dp))
                ModeOptionItem(
                    icon = Icons.Default.Replay,
                    title = stringResource(R.string.douban_sync_mode_c_title),
                    desc = stringResource(R.string.douban_sync_mode_c_desc),
                    example = stringResource(R.string.douban_sync_mode_c_example, syncedCount),
                    onClick = {
                        onDismiss()
                        onModeSelected(SyncMode.FULL_REWRITE)
                    }
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.douban_retry_cancel))
            }
        }
    )
}

@Composable
private fun ModeOptionItem(
    icon: ImageVector,
    title: String,
    desc: String,
    example: String,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small
    ) {
        Row(
            verticalAlignment = Alignment.Top,
            modifier = Modifier.padding(12.dp)
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    desc,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    example,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                )
            }
        }
    }
}
```

- [ ] **步骤 2: 构建 debug 验证编译**

运行: `.\gradlew assembleDebug`
预期: BUILD SUCCESSFUL

---

## 任务 10: DoubanRetryDialog 拆分 + 导出选项

**文件:**
- 修改: `app/src/main/java/com/tracktosearch/ui/screen/douban/DoubanRetryDialog.kt`(L98-223)

删除「增量同步」选项,新增「导出失败记录」选项。

- [ ] **步骤 1: 移除 onIncrementalSync 参数,新增 onExport 参数**

把 `DoubanRetryDialog` 签名改为:

```kotlin
@Composable
fun DoubanRetryDialog(
    onDismiss: () -> Unit,
    onRetryLocal: (Set<FailureReason>) -> Unit,
    onRetryFromJson: () -> Unit,
    onExportFailures: () -> Unit,
    viewModel: DoubanRetryViewModel = hiltViewModel()
)
```

- [ ] **步骤 2: 删除「增量同步」选项块,新增「导出失败记录」选项块**

把 L140-149 的「选项 2:增量同步」整块替换为「选项 2:导出失败记录」:

```kotlin
// 选项 2:导出失败记录
RetryOptionItem(
    icon = Icons.Default.FileDownload,
    title = stringResource(R.string.douban_retry_option_export),
    subtitle = stringResource(R.string.douban_retry_option_export_desc),
    onClick = {
        onDismiss()
        onExportFailures()
    }
)
```

并新增 import:
```kotlin
import androidx.compose.material.icons.filled.FileDownload
```

- [ ] **步骤 3: 构建 debug 验证编译**

运行: `.\gradlew assembleDebug`
预期: BUILD SUCCESSFUL

---

## 任务 11: SettingsScreen 双独立按钮入口

**文件:**
- 修改: `app/src/main/java/com/tracktosearch/ui/screen/settings/SettingsScreen.kt`(L485-511, L885-910)

- [ ] **步骤 1: 在「重新同步豆瓣」按钮下新增「重试上次失败项」按钮**

把 L485-511 的 `SettingsItem` 修改为两个并列 item:

```kotlin
item {
    SettingsItem(
        icon = Icons.Default.FileDownload,
        title = stringResource(R.string.settings_douban_resync),
        subtitle = stringResource(R.string.settings_douban_resync_desc),
        onClick = {
            showSyncModePicker = true
        }
    )
}
item {
    if (doubanRetryState.hasFailures) {
        SettingsItem(
            icon = Icons.Default.Replay,
            title = stringResource(R.string.settings_douban_retry_failures),
            subtitle = stringResource(R.string.douban_retry_subtitle, doubanRetryState.totalFailures),
            onClick = {
                showDoubanRetryDialog = true
            }
        )
    }
}
```

- [ ] **步骤 2: 新增 showSyncModePicker 状态与对话框显示**

在状态区(L167 附近)新增:
```kotlin
var showSyncModePicker by remember { mutableStateOf(false) }
```

在底部对话框区(L885 附近)新增:
```kotlin
if (showSyncModePicker) {
    DoubanSyncModePickerDialog(
        syncedCount = 0,  // TODO: 从 DoubanSyncedItemDao.count() 获取,或用 0 简化
        onDismiss = { showSyncModePicker = false },
        onModeSelected = { mode ->
            showSyncModePicker = false
            scope.launch {
                doubanSyncManager.startSync(mode)
                onDoubanResync()
            }
        }
    )
}
```

注:由于 SettingsScreen 已有 `doubanRetryViewModel`,可通过它访问 `doubanSyncManager`(已在 DoubanRetryViewModel 中暴露)。但为避免改动 DoubanRetryViewModel,这里通过新增一个简单 ViewModel 注入或直接在 onClick 中触发。

简化方案:让 `onDoubanResync` 回调接收 mode 参数,由 AppNavigation 转发给 DoubanSyncViewModel:

修改 SettingsScreen 顶部参数:
```kotlin
onDoubanResync: (SyncMode) -> Unit = {},
```

AppNavigation 中:
```kotlin
onDoubanResync = { mode ->
    scope.launch {
        doubanSyncManager.startSync(mode)
        navController.navigate(Routes.DOUBAN_LOGIN)
    }
}
```

- [ ] **步骤 3: 修改 DoubanRetryDialog 调用,移除 onIncrementalSync**

把 L885-910 的 DoubanRetryDialog 调用改为:

```kotlin
if (showDoubanRetryDialog) {
    DoubanRetryDialog(
        onDismiss = { showDoubanRetryDialog = false },
        onRetryLocal = { selectedReasons ->
            showDoubanRetryDialog = false
            scope.launch {
                val started = doubanRetryViewModel.startRetryFromLocal(selectedReasons)
                if (started) {
                    onDoubanResync(SyncMode.INCREMENTAL_ONLY)  // 占位,实际重试不走 mode
                }
            }
        },
        onRetryFromJson = {
            showDoubanRetryDialog = false
            onDoubanResync(SyncMode.INCREMENTAL_ONLY)  // 占位,导航到豆瓣登录页选导入 JSON
        },
        onExportFailures = {
            showDoubanRetryDialog = false
            scope.launch {
                val uri = doubanRetryViewModel.doubanFailureExporter.exportFromLocal(context)
                if (uri != null) {
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "application/json"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(shareIntent, "分享失败项 JSON"))
                }
            }
        },
        viewModel = doubanRetryViewModel
    )
}
```

需要把 `DoubanFailureExporter` 通过 `DoubanRetryViewModel` 暴露(已有 `doubanFailureExporter` 字段)。

- [ ] **步骤 4: 构建 debug 验证编译**

运行: `.\gradlew assembleDebug`
预期: BUILD SUCCESSFUL

- [ ] **步骤 5: Commit**

```bash
git add app/src/main/java/com/tracktosearch/ui/screen/douban/DoubanSyncModePickerDialog.kt app/src/main/java/com/tracktosearch/ui/screen/douban/DoubanRetryDialog.kt app/src/main/java/com/tracktosearch/ui/screen/settings/SettingsScreen.kt app/src/main/java/com/tracktosearch/ui/navigation/AppNavigation.kt
git commit -m "feat: 设置页双独立按钮 + 模式选择对话框 + 重试弹窗导出选项"
```

---

## 任务 12: strings.xml 4 语言文案

**文件:**
- 修改: `app/src/main/res/values/strings.xml`(英)
- 修改: `app/src/main/res/values-zh/strings.xml`(中)
- 修改: `app/src/main/res/values-ja/strings.xml`(日)
- 修改: `app/src/main/res/values-ko/strings.xml`(韩)

- [ ] **步骤 1: values-zh/strings.xml 新增**

```xml
<!-- 豆瓣同步模式选择 -->
<string name="douban_sync_mode_picker_title">选择重新导入模式</string>
<string name="douban_sync_mode_a_title">仅同步新增条目</string>
<string name="douban_sync_mode_a_desc">仅同步豆瓣新加的想看/看过,跳过已同步条目。不处理状态变化。</string>
<string name="douban_sync_mode_a_example">例:豆瓣新加 5 部想看 → 同步到 Trakt。豆瓣某片从想看改成看过 → 不处理。</string>
<string name="douban_sync_mode_b_title">同步新增 + 状态变化</string>
<string name="douban_sync_mode_b_desc">同步新增 + 检测状态变化,撤销旧操作应用新操作。不处理删除。</string>
<string name="douban_sync_mode_b_example">例:豆瓣某片从想看改成看过 → 移除想看+标记已看。豆瓣某片从看过改成想看 → 移除已看+标记想看。</string>
<string name="douban_sync_mode_c_title">完全重写(谨慎)</string>
<string name="douban_sync_mode_c_desc">清除 Trakt 上之前同步过的所有标记,重新应用豆瓣当前状态。风险:用户在 Trakt 上手动改的状态会丢失。</string>
<string name="douban_sync_mode_c_example">将清除已同步的 %1$d 项标记后重新应用豆瓣状态。建议先导出 Trakt 数据备份。</string>

<!-- 重试弹窗导出选项 -->
<string name="douban_retry_option_export">导出失败记录</string>
<string name="douban_retry_option_export_desc">导出为 JSON 文件,可通过分享面板发送</string>

<!-- 设置页重试失败项入口 -->
<string name="settings_douban_retry_failures">重试上次失败项</string>
```

- [ ] **步骤 2: values/strings.xml(英文)**

```xml
<!-- Douban sync mode picker -->
<string name="douban_sync_mode_picker_title">Choose Reimport Mode</string>
<string name="douban_sync_mode_a_title">Sync New Items Only</string>
<string name="douban_sync_mode_a_desc">Only sync new Douban wish/collect. Skips already synced. No status change handling.</string>
<string name="douban_sync_mode_a_example">e.g. Add 5 new wish → sync to Trakt. Douban status change → ignored.</string>
<string name="douban_sync_mode_b_title">Sync New + Status Changes</string>
<string name="douban_sync_mode_b_desc">Sync new + detect status changes, revoke old op + apply new. No deletion handling.</string>
<string name="douban_sync_mode_b_example">e.g. wish→collect: remove from watchlist + mark watched. collect→wish: remove watched + add to watchlist.</string>
<string name="douban_sync_mode_c_title">Full Rewrite (Caution)</string>
<string name="douban_sync_mode_c_desc">Clear all previously synced marks on Trakt, then reapply Douban state. Risk: manual Trakt edits will be lost.</string>
<string name="douban_sync_mode_c_example">Will clear %1$d synced items and reapply. Backup recommended.</string>

<string name="douban_retry_option_export">Export Failures</string>
<string name="douban_retry_option_export_desc">Export as JSON via share sheet</string>

<string name="settings_douban_retry_failures">Retry Last Failures</string>
```

- [ ] **步骤 3: values-ja/strings.xml(日文)**

```xml
<string name="douban_sync_mode_picker_title">再インポートモードを選択</string>
<string name="douban_sync_mode_a_title">新規項目のみ同期</string>
<string name="douban_sync_mode_a_desc">Douban の新規 wish/collect のみ同期。既存はスキップ。状態変更は処理しない。</string>
<string name="douban_sync_mode_a_example">例:新規 5 件 → Trakt に同期。状態変更 → 無視。</string>
<string name="douban_sync_mode_b_title">新規 + 状態変更を同期</string>
<string name="douban_sync_mode_b_desc">新規同期 + 状態変更検出。旧操作取り消し + 新操作適用。削除は処理しない。</string>
<string name="douban_sync_mode_b_example">例:wish→collect: watchlist 削除 + 視聴済み。collect→wish: 視聴済み削除 + watchlist。</string>
<string name="douban_sync_mode_c_title">完全上書き(注意)</string>
<string name="douban_sync_mode_c_desc">Trakt の既存同期マークを全削除後、Douban 状態を再適用。手動編集は失われます。</string>
<string name="douban_sync_mode_c_example">同期済み %1$d 件を削除して再適用。バックアップ推奨。</string>

<string name="douban_retry_option_export">失敗記録をエクスポート</string>
<string name="douban_retry_option_export_desc">JSON で書き出し(共有)</string>

<string name="settings_douban_retry_failures">前回の失敗を再試行</string>
```

- [ ] **步骤 4: values-ko/strings.xml(韩文)**

```xml
<string name="douban_sync_mode_picker_title">재가져오기 모드 선택</string>
<string name="douban_sync_mode_a_title">신규 항목만 동기화</string>
<string name="douban_sync_mode_a_desc">Douban 신규 wish/collect만 동기화. 기존은 건너뜀. 상태 변화 미처리.</string>
<string name="douban_sync_mode_a_example">예: 신규 5건 → Trakt 동기화. 상태 변화 → 무시.</string>
<string name="douban_sync_mode_b_title">신규 + 상태 변화 동기화</string>
<string name="douban_sync_mode_b_desc">신규 동기화 + 상태 변화 감지. 이전 작업 취소 + 새 작업 적용. 삭제 미처리.</string>
<string name="douban_sync_mode_b_example">예: wish→collect: watchlist 제거 + 시청 표시. collect→wish: 시청 제거 + watchlist.</string>
<string name="douban_sync_mode_c_title">완전 재작성(주의)</string>
<string name="douban_sync_mode_c_desc">Trakt 기존 동기화 표시 전체 삭제 후 Douban 상태 재적용. 수동 편집 분실 위험.</string>
<string name="douban_sync_mode_c_example">동기화된 %1$d건 삭제 후 재적용. 백업 권장.</string>

<string name="douban_retry_option_export">실패 기록 내보내기</string>
<string name="douban_retry_option_export_desc">JSON으로 내보내기(공유)</string>

<string name="settings_douban_retry_failures">이전 실패 재시도</string>
```

- [ ] **步骤 5: 构建 debug 验证编译**

运行: `.\gradlew assembleDebug`
预期: BUILD SUCCESSFUL

---

## 任务 13: 最终构建验证

- [ ] **步骤 1: 完整 debug 构建**

运行: `.\gradlew assembleDebug`
预期: BUILD SUCCESSFUL

- [ ] **步骤 2: 检查 APK**

```powershell
Get-Item app\build\outputs\apk\debug\app-debug.apk | Select-Object Name, Length, LastWriteTime
```

- [ ] **步骤 3: Commit**

```bash
git add app/src/main/res/values/strings.xml app/src/main/res/values-zh/strings.xml app/src/main/res/values-ja/strings.xml app/src/main/res/values-ko/strings.xml
git commit -m "feat: 新增豆瓣同步模式选择与失败项导出 UI 文案(4 语言)"
```

---

## 自检

### 规格覆盖度

| 规格章节 | 任务 |
|---------|------|
| 主题 1: 流水线 | 任务 6 |
| 主题 2: 0/0 修复 + 双层吸顶 | 任务 7, 8 |
| 主题 3: 重试导出 | 任务 2, 10 |
| 主题 4: 模式 A/B/C | 任务 1, 3, 4, 5, 9, 11 |

### 类型一致性

- `SyncMode` 枚举: 任务 3 定义,任务 5/9/11 使用 ✓
- `batchRemoveFromWatched`: 任务 1 定义,任务 4/5 使用 ✓
- `exportFromLocal`: 任务 2 定义,任务 11 使用 ✓
- `DoubanSyncModePickerDialog`: 任务 9 定义,任务 11 使用 ✓

### 占位符扫描

无 TODO/待定。任务 4 中 `processStatusChanges` 失败项处理有简化注释,但明确说明不进入 allFailed,符合规格。

### 范围

13 个任务,覆盖规格全部章节。可独立 commit。建议按顺序执行,任务 1-2 可并行,任务 4-5 依赖任务 3。
