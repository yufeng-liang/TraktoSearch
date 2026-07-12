# 项目代码审查报告

> 审查时间：2026-07-11
> 审查范围：数据层、豆瓣/云同步、缓存体系、UI ViewModel、网络层、导航/Service/通知
> 审查方法：6 个子代理并行审查 + 人工汇总

## 问题统计

| 严重程度 | 数量 | 说明 |
|---------|------|------|
| 严重 (P0) | 14 | 导致崩溃、数据丢失、功能失效、数据泄漏 |
| 中等 (M) | 39 | 影响性能、并发安全、规范一致性 |
| 轻微 (L) | 38 | 代码质量、可维护性 |

---

## 一、严重问题 (P0)

### P0-1 数据丢失风险：豆瓣全量重写先删后同步

- **文件**: `app/src/main/java/com/tracktosearch/data/repository/DoubanSyncManager.kt`
- **行号**: 766-780
- **问题描述**: `runSyncFullRewrite()` 先从 Trakt 删除全部标记 + 清空本地表，再调用 `runSyncLegacy` 重新同步。步骤 3 失败时 Trakt 数据已删、本地记录已空，无法恢复。
- **建议修复**: 改为两阶段——先重新同步确认成功，再删除旧标记；或失败时记录待回滚项。
- **状态**: ✅ 已修复（保持先删后同步+回滚机制：同步前保存回滚快照到 `douban_sync_rollback` 表，失败/取消时保留快照，下次启动弹对话框提示用户恢复被删除的标记）

### P0-2 登出时用户私有缓存未清除（数据泄漏）

- **文件**: `app/src/main/java/com/tracktosearch/data/repository/TraktRepository.kt`
- **行号**: 134-140
- **问题描述**: `clearWatchlistWatchedCache()` 只清了 `watchlistWatchedIds`，未清 `commentsCache`/`movieHistoryCache`/`userRationsCache`/`movieWatchlistCache` 等用户私有缓存。用户 A 登出后用户 B 在 5-10 分钟内能看到 A 的历史/评分/想看列表。
- **建议修复**: 新增 `clearUserCaches()` 统一清理所有用户私有缓存。
- **状态**: ✅ 已修复

### P0-3 `TtlCache.clear()` 不清 `inFlightRequests`，登出后旧数据被写回

- **文件**: `app/src/main/java/com/tracktosearch/data/util/TtlCache.kt`
- **行号**: 97
- **问题描述**: `clear()` 后若有飞行中的请求完成，会 `put()` 把用户 A 的旧数据写回刚清空的缓存。
- **建议修复**: `clear()` 同时取消/清除 `inFlightRequests`。
- **状态**: ✅ 已修复

### P0-4 TraktAuthManager token 刷新失败时过度清除凭证

- **文件**: `app/src/main/java/com/tracktosearch/data/remote/trakt/TraktAuthManager.kt`
- **行号**: 89-91
- **问题描述**: 任何非 2xx 响应（含 5xx/429 临时故障）都执行 `clearTokens()`。Trakt 服务器一次抖动导致全部用户被强制登出。
- **建议修复**: 仅 400/401 清除 token；5xx/429 保留 refresh_token 返回 failure。
- **状态**: ✅ 已修复

### P0-5 通知点击深链路完全失效

- **文件**: `app/src/main/java/com/tracktosearch/MainActivity.kt` (451-465)、`NotificationHelper.kt` (55-62)
- **问题描述**: 通知 Intent 注入了 `navigate_to="detail"` 等 extra，但 `MainActivity.handleIntent()` 完全未消费。
- **建议修复**: `handleIntent()` 增加分支处理。
- **状态**: ✅ 已修复（新增 `DeepLinkNavigator` 单例传递导航指令，`handleIntent` 消费通知 extra，`AppNavigation` 监听 `pendingNavigation` 跳转详情页）

### P0-6 状态判断错误：`checkWatched`/`checkInWatchlist`/`batchCheckStatus` 只查前 200 条

- **文件**: `app/src/main/java/com/tracktosearch/data/repository/TraktRepository.kt`
- **行号**: 806-892
- **问题描述**: 默认 `page=1, limit=200`，超 200 条历史/想看的条目状态被误判为 false。
- **建议修复**: 优先查 `WatchlistWatchedIds` 缓存；网络路径必须跨页全量拉取。
- **状态**: ✅ 已修复（优先用 `WatchlistWatchedIds` 全局缓存判断，覆盖全量数据）

### P0-7 `getMovieDetail` 缓存 key 不含语言后缀，与 `enrichMovie` 不一致

- **文件**: `app/src/main/java/com/tracktosearch/data/repository/TmdbRepository.kt`
- **行号**: 655 vs 177
- **问题描述**: 同一电影以 `"123"` 和 `"123_zh-CN"` 两个 key 缓存，导致重复网络请求 + 切换语言后返回旧语言数据。
- **建议修复**: `getMovieDetail` 改用 `langKey(movieId)`。
- **状态**: ✅ 已修复

### P0-8 ViewModel 转圈永久卡死（3 处）

- `PersonViewModel.kt` (333-355) `resolvingTmdbId` 异常时不清空
- `TraktSearchViewModel.kt` (635-679) `isLoading` 异常时不清空
- `DetailViewModel.kt` (499-543) `isLoadingMoreComments` 异常时不清空
- **建议修复**: 全部用 try-finally 包裹。
- **状态**: ✅ 已修复

### P0-9 `fetchRecommendations` 覆盖用户手动标记状态

- **文件**: `app/src/main/java/com/tracktosearch/ui/screen/detail/DetailViewModel.kt`
- **行号**: 866-871
- **问题描述**: 延迟 1.5 秒加载推荐时，用缓存旧值覆盖当前影视的 `isMarkedWatchlist`/`isMarkedWatched`。
- **建议修复**: 移除这两行覆盖。
- **状态**: ✅ 已修复

### P0-10 批量删除乐观更新不回滚

- **文件**: `app/src/main/java/com/tracktosearch/ui/screen/watchlist/WatchlistViewModel.kt`
- **行号**: 593-623
- **问题描述**: `allSuccess` 变量计算后未使用，无论 API 是否成功都从 UI 移除条目。
- **建议修复**: 仅 `allSuccess=true` 时更新 UI；失败时通过 SharedFlow 通知用户。
- **状态**: ✅ 已修复（仅 `allSuccess=true` 时更新 UI）

### P0-11 OkHttp 连接泄漏

- **文件**: `app/src/main/java/com/tracktosearch/data/remote/custom/CustomSearchService.kt`
- **行号**: 62-63
- **问题描述**: 非成功响应未 `response.close()`。
- **状态**: ✅ 已修复

### P0-12 `TraktAuthManager.response.body()!!` NPE 风险

- **文件**: `app/src/main/java/com/tracktosearch/data/remote/trakt/TraktAuthManager.kt`
- **行号**: 54、82
- **状态**: ✅ 已修复

### P0-13 `getOrAwait` 捕获 `CancellationException` 导致级联取消

- **文件**: `app/src/main/java/com/tracktosearch/data/util/TtlCache.kt`
- **行号**: 89-91
- **建议修复**: 单独 `catch (e: CancellationException) { throw e }`。
- **状态**: ✅ 已修复

### P0-14 `cancel()` 与同步作业并发，`dirtyDetailIds` 竞态

- **文件**: `app/src/main/java/com/tracktosearch/data/repository/DoubanSyncManager.kt`
- **行号**: 140-153、1079-1090
- **建议修复**: `cancel()` 中先 `syncJob?.join()` 再上传。
- **状态**: ✅ 已修复

---

## 二、中等问题 (M) - 已修复

### M-3 6 小时缓存 TTL 在 `loadFromDisk` 时被重置

- **文件**: `app/src/main/java/com/tracktosearch/data/util/PersistentTtlCache.kt`
- **行号**: 62
- **问题描述**: 每次 App 启动会把过期时间重置为 `启动时刻 + 6h`，只要 6 小时内重启 App，趋势/热榜/推荐缓存永不过期。
- **状态**: ✅ 已修复

### M-10 `startSync` 系列方法无 try-catch，未捕获异常导致 progress 状态不一致

- **文件**: `app/src/main/java/com/tracktosearch/data/repository/DoubanSyncManager.kt`
- **行号**: 190-199、209-218、232-241、271-283
- **状态**: ✅ 已修复

### M-19 `DiscoverViewModel.loadTraktData` catch 块遗漏 `traktShowRecommendationsError`

- **文件**: `app/src/main/java/com/tracktosearch/ui/screen/discover/DiscoverViewModel.kt`
- **行号**: 754-761
- **状态**: ✅ 已修复

### M-23 `personDetailCache` 用 1 小时 TTL，违反永久缓存规范

- **文件**: `app/src/main/java/com/tracktosearch/data/repository/TmdbRepository.kt`
- **行号**: 96-98
- **状态**: ✅ 已修复

### M-24 `getTvDetail` 完全不使用缓存

- **文件**: `app/src/main/java/com/tracktosearch/data/repository/TmdbRepository.kt`
- **行号**: 670-677
- **状态**: ✅ 已修复

### M-26 `doubanRecommendCache` 登出时未清除

- **文件**: `app/src/main/java/com/tracktosearch/ui/screen/settings/SettingsViewModel.kt`
- **行号**: 536-538
- **状态**: ✅ 已修复

### M-27 `notFoundTmdbIds`/`notFoundImdbIds` 负缓存永不清除

- **文件**: `app/src/main/java/com/tracktosearch/data/repository/TraktRepository.kt`
- **行号**: 318、321
- **状态**: ✅ 已修复

### M-30 `AppNavigation` `pendingCount` 用 `remember` 而非 `rememberSaveable`

- **文件**: `app/src/main/java/com/tracktosearch/ui/navigation/AppNavigation.kt`
- **行号**: 242
- **状态**: ✅ 已修复

### M-31 `DoubanSyncService` 多次 `start()` 启动多个进度收集协程

- **文件**: `app/src/main/java/com/tracktosearch/service/DoubanSyncService.kt`
- **行号**: 76-115
- **状态**: ✅ 已修复

---

## 三、中等问题 (M) - 待修复

### M-1 系统性 `CancellationException` 被吞掉

- **文件**: `TraktRepository.kt` 约 40 处、`PersistentTtlCache.kt` 5 处、`UpdateRepository.kt` 多处
- **建议**: 全面推广 `catch (e: CancellationException) { throw e }`。
- **状态**: ✅ 已修复（6 个文件 69 处 catch 块补 `CancellationException` 重抛）

### M-2 持久化缓存 key 普遍缺版本号

- **文件**: `TraktRepository.kt`、`TmdbRepository.kt` 多处
- **状态**: ✅ 已修复（23 个缓存 key 前缀统一加 `_v1` 后缀，数据格式变更时可通过 `_v2` 自动失效旧缓存）

### M-4 `syncBatchToTrakt` 的 `successCount` 计算错误

- **文件**: `DoubanSyncManager.kt` (1436)
- **状态**: 待修复

### M-5 `persistFailures` 空列表不清旧失败项

- **文件**: `DoubanSyncManager.kt` (1029-1041)
- **状态**: 待修复

### M-6 `fetchDetail`/`fetchMarkList` 网络异常不重试

- **文件**: `DoubanRepository.kt`
- **状态**: 待修复

### M-7 `CloudFailureSyncManager` 时间戳比较无法检测 `mediaType`/`subtitle` 更新

- **状态**: ✅ 已修复（`DoubanSyncFailureEntity` 新增 `updatedAt` 字段，DAO UPDATE 方法同步刷新 `updatedAt`，时间戳比较改用 `max(failedAt, updatedAt)`）

### M-8 `CloudPersonalSyncManager.downloadAndMerge` 无时间戳比较

- **状态**: ✅ 已修复（`synced_items`/`pending_items` 按 `syncedAt`/`crawledAt` 逐条比较，仅云端较新才覆盖本地）

### M-9 `clearAll()` + `insertAll()` 无数据库事务

- **状态**: ✅ 已修复（4 个 DAO 添加 `@Transaction replaceAll`/`replaceByStatus` 方法，3 处调用方改用事务包装）

### M-11 `processStatusChanges` 部分失败无回滚

- **状态**: 待修复（需评估业务流程）

### M-12 `ApiKeyInterceptor` × `RetryInterceptor` 重试乘法效应

- **状态**: 待修复（需策略调整）

### M-13 `RetryInterceptor` 用 `Thread.sleep` 阻塞 dispatcher 线程

- **状态**: 待修复

### M-14 Trakt 的 `ApiKeyInterceptor` 将 401 误标记 client_id INVALID

- **状态**: 待修复

### M-15 `TraktAuthManager` 创建独立 OkHttpClient

- **状态**: 待修复

### M-16 `WatchlistViewModel.refresh()` 重置状态后旧协程覆盖新数据

- **状态**: 待修复

### M-17 `StatisticsViewModel` async 未 await 时异常未处理

- **状态**: 待修复

### M-18 `SearchViewModel` 缓存检查在协程内部

- **状态**: 待修复

### M-20 `DetailViewModel.toggleSeason` 无 `onFailure` 处理

- **状态**: 待修复

### M-21 `TraktSearchViewModel` PERSON 搜索覆盖 MOVIE/SHOW tab

- **状态**: 待修复

### M-22 `DiscoverViewModel` 多个 `resolveAndNavigate` 异常静默忽略

- **状态**: 待修复

### M-25 `getOrPut` 使用全局 Mutex

- **状态**: 待修复

### M-28 `watchlistWatchedIds` 缓存更新存在竞态条件

- **状态**: 待修复

### M-29 `loadWatchlistWatchedIds` 无并发去重

- **状态**: 待修复

### M-32 Gitee/GitHub access token 嵌入 BuildConfig

- **状态**: 待修复（涉及安全策略）

### M-33 `CrashHandler` 在 `:pushcore` 子进程未初始化

- **状态**: 待修复

---

## 四、轻微问题 (L) - 摘要

完整 38 条轻微问题见各模块子代理输出，主要包括：

- `TmdbRepository.enrichMovie`/`enrichTv` 代码重复
- `getMovieImages` 硬编码 `"zh,null"` 语言参数
- `UpdateRepository.isNewerVersion` 不支持预发布版本号
- `RatingsRepository` 缓存无 TTL
- DataStore 写入/序列化失败全部静默处理无日志
- `DoubanSpider.isLoginPage()` 可能误判
- `DoubanFailureExporter` 同日多次导出文件名相同
- `FailureReason.fromString()` 未知值降级为可恢复类型
- Service `ACTION_CANCEL` 时通知立即消失
- `NotificationHelper` 通知小图标用 mipmap 而非单色 drawable
- `buildAuthorizationUrl` 未 URL 编码 `redirect_uri`
- `TraktSearchApp` 创建多个临时 CoroutineScope 未统一管理
- `OfflineCacheManager.clearDatabase()` 未清除豆瓣同步相关表
- `DoubanRepository` 使用 `GlobalScope` 上传全局池

---

## 五、修复优先级建议

### 已全部修复 ✅
- P0-1~P0-14（14 个严重问题全部修复）
- M-1/M-2/M-3/M-7/M-8/M-9/M-10/M-19/M-23/M-24/M-26/M-27/M-30/M-31

### 后续可改进
- M-4/M-5/M-6 豆瓣同步细节优化
- M-11~M-15 网络层重试策略调整
- M-16/M-17/M-18/M-20/M-21/M-22 ViewModel Job 管理
- M-25/M-28/M-29 缓存并发优化
- M-32/M-33 安全与崩溃处理
