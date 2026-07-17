# 标记记录页首屏加载速度优化 — 设计规格

- 日期：2026-07-17
- 分支：`feature/mark-record-load-opt`（git worktree 隔离，位于 `F:/trae-project-mark-record-opt`）
- 范围：仅优化「标记记录页」首屏冷启动加载速度（场景 A）

## 1. 背景与瓶颈定位

标记记录页入口：设置页「标记记录」卡片 → `MarkRecordScreen`（ALL / WATCHLIST / WATCHED / REMOVED 四个 Tab）。

首屏加载链路（`MarkRecordViewModel.init → loadFirstPage()`）：

- 当前 Tab 为 ALL 或 WATCHED 时，调用 `TraktRepository.fetchWatchHistory(page)`。
- `fetchWatchHistory` 先并行拉取 Trakt 的 movie 历史 + episode 历史两个接口（已并行，非瓶颈）。
- **瓶颈**：拿到原始 entries 后，对**每一条记录串行**调用 `tmdbRepository.enrichMovie / enrichTv`（TraktRepository.kt:870-934 的 for 循环内逐个 await），单页最多 200 条。
  - 每个 enrich：内存命中（`movieDetailCache`/`tvDetailCache`）即时返回；未命中则 `awaitLoaded()` 等磁盘加载，再未命中才发 TMDB 网络请求。
  - 冷启动时内存/磁盘均未预热，且 200 条 tmdbId 各异，命中率极低 → 大量串行网络请求。
  - 必须等整页 200 条全部 enrich 完成，`watchHistoryCache.put` 后才回调 → UI 长时间停留在 `isLoading=true`。
- 次要：`updateCurrentStatusMap()` 在首屏后再查一次 `getWatchlistWatchedIds()`（内存查询，不慢）。

结论：最大瓶颈是「串行逐条 enrich + 全量完成才显示」。

## 2. 优化方案（已确认：C + B）

并行 enrich（并发上限 10）+ 渐进提交（每批 20 条先显示，其余后台补全）。保留缓存优先原则。

### 2.1 缓存优先原则（不破坏，明确声明）

- 内存命中（`movieDetailCache`/`tvDetailCache` 一级）→ 即时返回，不阻塞。
- 磁盘命中（DataStore 二级，`awaitLoaded()` 后查）→ 即时返回。
- 都未命中 → 发 TMDB 网络请求 `getMovieDetail`/`getTvDetail` → 成功后 `movieDetailCache.put(key, detail)` **写入内存 + 持久化 DataStore**（TmdbRepository.kt:250 已有此逻辑）。
- 海报 URL / 中文标题由 enrich 结果算出，随 detail 一起被缓存，下次直接命中。
- 本次**不新增任何缓存层**，仅改变 enrich 的调度方式（串行 → 并行 + 渐进）。
- 并行下同一会话内：前批 enrich 写入的持久化/内存缓存，后续批次同 tmdbId 立即命中（`movieDetailCache`/`tvDetailCache` 为进程内单例）。
- 限流：`Semaphore(10)` 防止冷启动同时发起 200 个网络请求打爆 TMDB。

## 3. 架构与改动范围

改动集中在数据层与 ViewModel 消费方式，UI（`MarkRecordScreen`/`MarkRecordComponents`）不变。

- `TraktRepository.fetchWatchHistory`：由「返回 `Result<WatchHistoryPage>`」改为「返回 `Flow<WatchHistoryEmit>`」，其中 `WatchHistoryEmit` 携带 `items: List<WatchHistoryItem>` 与 `isComplete: Boolean` 与可选 `error`。
  - 内部：并行拉 movie+episode 接口（保持）→ 合并 entries → `coroutineScope` 内 `async` 并行 enrich（受 `Semaphore(10)` 限流）→ 每累计 20 条 emit 一批 → 全部完成 emit 末批 `isComplete=true`。
  - 仍做 `watchHistoryCache.put`（末批完成时）以保证翻页/刷新缓存语义；但首屏不依赖缓存命中即可显示。
- `MarkRecordViewModel`：首屏（`loadFirstPage` / ALL Tab 合并 trakt 部分）改为消费该 Flow：
  - 每收到一批 → 更新 `uiState.items`（首屏第一批到达即 `isLoading=false`）。
  - 全部完成 → 标记 `hasMore` 等（ALL Tab 合并逻辑保持不变：local 自建表 + 渐进到达的 trakt 批次，按 `actedAt` 倒序合并，`take(pageSize)`）。
  - 中途 `error` → 已提交批次保留，错误作为末批 `error` 上报（复用现有 `error` 字段机制）。
- 翻页（WATCHED Tab 非首屏、`loadNextPage`）：同样消费 Flow，但仅末批参与追加，避免中间批抖动（或简化为 Flow 仍 emit，ViewModel 只在 `isComplete` 时提交——以「不引入 UI 抖动」为准，实现时选择「仅末批提交」）。
- 下拉刷新 `refresh()`：`clearWatchHistoryCache()` 后重新消费 Flow（强制全量重新 enrich）。

### 3.1 并发与渐进常量

```kotlin
companion object {
    const val ENRICH_CONCURRENCY = 10   // 并行 enrich 并发上限
    const val EMIT_BATCH_SIZE = 20      // 渐进提交批大小
}
```
作为可调常量，便于后续按真机表现调参。

## 4. 数据流（首屏冷启动，ALL/WATCHED Tab）

```
MarkRecordViewModel.loadFirstPage
  └─ fetchWatchHistory(1) 返回 Flow<WatchHistoryEmit>
       ├─ 并行拉 movie 历史 + episode 历史接口
       ├─ 合并成 entries（最多 200 条）
       ├─ coroutineScope + Semaphore(10): async 并行 enrich 每条
       ├─ 累计满 20 条 → emit WatchHistoryEmit(items=批, isComplete=false)
       └─ 全部完成 → emit WatchHistoryEmit(items=末批, isComplete=true)
  └─ ViewModel 每批 update uiState.items
       ├─ 首批到达 → isLoading=false（UI 立即显示）
       └─ 后续批追加 / ALL Tab 与 local 合并
  └─ 首屏一批到达即 isLoading=false → UI 立刻出内容
```

取消安全：`coroutineScope` 取消时所有 `async` 自动取消；ViewModel 切 Tab / 搜索 / `dispose` 时 `viewModelScope` 取消应传播到 Flow 收集，避免泄漏 enrich 协程。

## 5. 错误处理

- 单条 enrich 失败（catch，TraktRepository.kt:885/919 现有逻辑）→ 用 Trakt 原始 title/posterUrl 兜底，不中断整批，继续后续。
- 整页 movie/episode 接口失败 → 走现有 `Result.failure` 语义，Flow 以 `error` 末批结束。
- 渐进提交中途单批出错：已提交批次保留，错误作为末批 `error` 字段上报，与现有 `uiState.error` 机制一致（UI 显示重试）。
- 限流信号量在 `coroutineScope` 内使用 `withPermit`，确保取消时正确释放。

## 6. 测试

新增/调整（保留现有 74 个相关测试不回归）：

- `TraktRepositoryTest`：
  - 并行 enrich 单页 200 条：验证并发执行（可用 fake 计时，断言总耗时显著小于 200×单条串行耗时）。
  - enrich 单条失败 → 兜底 title/posterUrl，Flow 继续产出后续批，不崩溃。
  - 渐进 emit：断言 emit 的批次数 = ceil(总条数 / EMIT_BATCH_SIZE)，末批 `isComplete=true`。
  - 缓存优先级：内存/磁盘命中时 enrich 不发网络（复用现有 `enrichMovie_内存缓存命中_不调用API` 类测试思路）。
  - 并发未命中时写回持久化缓存：同会话内后续同 tmdbId emit 命中内存缓存。
- `MarkRecordViewModelTest`：
  - 首屏第一批到达即 `uiState.isLoading == false`（渐进显示验证）。
  - 切 Tab / 搜索时旧 Flow 收集被取消（无泄漏、无旧数据追加错乱）。
- UI 测试 `MarkRecordScreenTest`：保持现有断言仍通过（loading/empty/error/retry 行为不变）。

## 7. 验证

- worktree 内执行：
  - `./gradlew :app:testDebugUnitTest` 全绿。
  - `./gradlew :app:assembleDebug` 构建通过。
- 手动验证（真机/模拟器）：
  - 冷启动（清除 App 进程或首次安装）后进入标记记录页 → 观察首屏出内容时间显著缩短（应在前 20 条 enrich 完成即显示，而非等 200 条全完）。
  - 切到 WATCHED Tab、再切回，确认无重复加载/错乱。
  - 下拉刷新确认重新 enrich。

## 8. 风险与权衡

- 并行 enrich 增加瞬时网络请求数（限流 10 缓解）；TMDB 限频风险低（命中率随会话提升）。
- Flow 渐进提交使 `watchHistoryCache` 仅在末批写入，期间若进程被杀，下次冷启动重新 enrich（可接受，原有 1h TTL 仍生效）。
- 不改动 UI 层与分页主流程，回归面小。
