# TrackToSearch 项目架构与代码审查报告

> 审查日期：2026-06-28
> 审查范围：内存泄漏、Compose重组、协程/Flow、网络层、Room/DataStore/WorkManager、启动优化

---

## 总览

共发现 **38个问题**，按优先级分布：

| 优先级 | 数量 | 说明 |
|--------|------|------|
| P0 (必须修复) | 5 | 安全泄露、可用性缺陷、电量浪费 |
| P1 (强烈建议) | 8 | 性能瓶颈、架构不一致 |
| P2 (建议优化) | 15 | 可维护性、最佳实践 |
| P3 (可选改进) | 10 | 代码风格、细节优化 |

---

## P0 - 必须修复

### 1. TraktAuthManager 生产环境泄露敏感信息
- **文件**: `data/remote/trakt/TraktAuthManager.kt:28-41`
- **问题**: `HttpLoggingInterceptor.Level.BODY` 在 Release 也打印完整请求体（含 OAuth token、client_secret）
- **修复**: 使用 DI 中带 debug/release 区分的日志拦截器，或将此 OkHttpClient 注入 DI 管理

### 2. 401 Token 过期无自动刷新
- **文件**: `di/NetworkModule.kt:59-86` + `TraktAuthManager.kt`
- **问题**: Trakt 拦截器只附加 token，401 时直接失败，无 refresh + retry
- **修复**: 在 OkHttp 拦截器中实现 `401 → refreshAccessToken() → 重试` 逻辑

### 3. 47处 `collectAsState()` 应替换为 `collectAsStateWithLifecycle()`
- **影响文件**: LoginScreen, StatisticsScreen, WatchlistScreen, TraktSearchScreen, SettingsScreen, SearchScreen, MainScreen, AppNavigation, CloudEasterEgg, MainActivity 等 11+ 文件
- **问题**: Screen 不可见时 Flow 仍收集，浪费 CPU 和电量
- **修复**: 全局替换为 `collectAsStateWithLifecycle()`（需添加 `androidx.lifecycle:lifecycle-runtime-compose` 依赖）

### 4. Flow 缺少 `distinctUntilChanged()`
- **影响**: 全局性问题，DataStore 的 stateIn 流可能重复发射相同值
- **重点位置**: `SearchViewModel.kt:100`, `DetailViewModel.kt:211`, 所有 DataStore Flow 转换链
- **修复**: 在 DataStore Flow 转换链中添加 `.distinctUntilChanged()`

### 5. Room 使用 `fallbackToDestructiveMigration()` — 数据丢失风险
- **文件**: `data/local/db/DatabaseModule.kt:22`
- **问题**: 任何版本升级都会销毁重建数据库，用户离线缓存和通知记录全部丢失
- **修复**: 编写正式的 Migration，移除 `fallbackToDestructiveMigration()`

---

## P1 - 强烈建议修复

### 6. 8个 OkHttpClient 独立连接池
- **文件**: `di/NetworkModule.kt`
- **问题**: 每个 API 独立创建 OkHttpClient，连接池和线程池完全隔离
- **修复**: 共享基础 client，通过 `newBuilder()` 创建子实例

### 7. DetailViewModel 静态缓存持有完整 UI 状态
- **文件**: `ui/screen/detail/DetailViewModel.kt:161-197`
- **问题**: companion object 中的 LRU 缓存持有 `DetailUiState`（含大量列表），进程存活期间永不释放
- **修复**: 降低缓存大小，或改用 WeakReference

### 8. ReleaseCheckWorker 串行请求 TMDB API
- **文件**: `data/notification/ReleaseCheckWorker.kt:83-185`
- **问题**: 想看列表 100 部电影 = 100 个串行请求，可能需要 2-3 分钟
- **修复**: 使用 `coroutineScope + async` 并发 + `Semaphore(5)` 限流

### 9. Repository 错误处理不一致
- **TraktRepository**: 失败只返回 HTTP 状态码，`response.body()!!` 可能 NPE
- **TmdbRepository**: 异常被 `catch (_: Exception)` 吞掉，返回 null 丢失错误信息
- **ResourceRepository**: 搜索失败返回空列表，用户无法感知
- **修复**: 统一 `Result<T>` 类型，区分网络错误和业务错误

### 10-13. 缺少 @Immutable 注解的 UiState
| 文件 | 位置 |
|------|------|
| `TraktSearchViewModel.kt:37-72` | SearchTabState, DiskSearchState, TraktSearchUiState |
| `PersonViewModel.kt:24` | PersonUiState |
| `DetailViewModel.kt:46-58` | RecommendationItem |
| `DetailViewModel.kt:139-146` | DetailSectionVisibility |

---

## P2 - 建议优化

### 14. RatingsRepository 非线程安全缓存
- **文件**: `data/repository/RatingsRepository.kt:27`
- `mutableMapOf` → `ConcurrentHashMap` 或 `Mutex` 保护

### 15. 无 HTTP Cache 配置
- **文件**: `di/NetworkModule.kt`
- TMDB/Trakt 静态数据可利用 HTTP 缓存

### 16. Room Entity 缺少索引
- **文件**: `data/local/db/Entities.kt`
- `MediaItemEntity.type`、`NotificationRecordEntity.traktId+type+payload` 缺少索引

### 17. OfflineCacheManager 非原子操作
- **文件**: `data/local/db/OfflineCacheManager.kt:28-31`
- `deleteByType` + `insertAll` 之间异常会导致数据丢失

### 18. DataStore 实例过多（14个）
- 每类偏好独立 DataStore，建议合并

### 19. SettingsViewModel 13个独立 stateIn Flow
- **文件**: `ui/screen/settings/SettingsViewModel.kt:79-108`
- 打开设置页同时触发 13 次磁盘读取

### 20. PanSou/Zreso/Douban API 不使用 Response 包装
- 无法区分 HTTP 错误和解析错误

### 21. 无证书固定 (Certificate Pinning)
- OAuth token 传输缺少中间人防护

### 22-27. 其他 P2 问题
- WatchlistViewModel 频繁创建中间列表
- StatisticsViewModel 全量拉取 + 逐部查进度
- 缓存策略碎片化（TtlCache/DataStore/OkHttp/Coil 各自为政）
- User-Agent 硬编码重复 4 处
- Trakt API 缺少 User-Agent
- Gitee Token 通过 URL 参数传递（日志泄露风险）
- DNS IPv4 过滤仅应用到 TMDB

---

## P3 - 可选改进

28. Trakt API Content-Type 头多余（Retrofit 自动设置）
29. `launchIn(viewModelScope)` 旧模式 → `viewModelScope.launch { collect }`
30. DiscoverScreen lambda 回调每次重组创建新实例
31. TraktSearchViewModel SearchTabState 频繁 copy 创建新实例
32. WatchlistViewModel `toMutableList()` + `toList()` 双重临时列表
33. SettingsViewModel TestResultState 缺少 @Immutable
34. Room DAO `getByType` 和 `getByTypeList` SQL 重复
35. `exportSchema = false` 阻止 schema 校验
36. CustomSearchService 同步 execute() 无独立超时控制
37. DetailScreen 多处 `Icons.Filled.StarHalf` / `OpenInNew` 应使用 AutoMirrored 版本

---

## 实施建议

### 第一阶段（安全 + 可用性）— 优先级最高
1. 修复 TraktAuthManager 日志泄露
2. 实现 401 Token 自动刷新
3. 编写 Room Migration，移除 destructive fallback

### 第二阶段（性能 + 电量）
4. 全局替换 `collectAsState()` → `collectAsStateWithLifecycle()`
5. 添加 `distinctUntilChanged()` 到 DataStore Flow
6. 共享 OkHttpClient 连接池
7. ReleaseCheckWorker 并发请求

### 第三阶段（架构一致性）
8. 统一 Repository 错误处理模式
9. 添加 @Immutable 注解到所有 UiState
10. Room 添加索引 + @Transaction

### 第四阶段（可维护性）
11. 提取 User-Agent 常量
12. 合并 DataStore 实例
13. 配置 HTTP Cache
14. 统一 API 返回类型为 `Response<T>`

---

## 关键文件清单

| 领域 | 文件路径 |
|------|----------|
| 网络 | `di/NetworkModule.kt` |
| 网络 | `data/remote/trakt/TraktAuthManager.kt` |
| 网络 | `data/repository/TraktRepository.kt`, `TmdbRepository.kt`, `ResourceRepository.kt` |
| 数据库 | `data/local/db/Entities.kt`, `Daos.kt`, `AppDatabase.kt`, `DatabaseModule.kt`, `OfflineCacheManager.kt` |
| DataStore | `data/local/ThemeStorage.kt`, `TokenStorage.kt` 等 14 个 |
| ViewModel | `ui/screen/detail/DetailViewModel.kt`, `settings/SettingsViewModel.kt`, `traktsearch/TraktSearchViewModel.kt` |
| Compose | 所有 `*Screen.kt` 文件（collectAsState 替换） |
| Worker | `data/notification/ReleaseCheckWorker.kt` |
