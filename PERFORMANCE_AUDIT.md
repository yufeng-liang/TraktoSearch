# TraktToSearch Android 应用性能审查报告

## 一、内存泄漏风险

### [Critical] OAuthCallback 全局单例持有数据
**文件**: `MainActivity.kt:73-78`
```kotlin
object OAuthCallback {
    @Volatile var pendingCode: String? = null
    @Volatile var authDenied: Boolean = false
}
```
**问题**: 全局单例在 Activity 销毁后仍持有 OAuth 数据，虽然 @Volatile 保证可见性，但 `pendingCode` 是 String，Activity 重建时不会自动清理。不过风险较低，因为及时消费了。
**建议**: 登录成功后立即 `OAuthCallback.pendingCode = null`，当前未在所有路径清理。

### [Important] CrashHandler 持有 Context
**文件**: `CrashHandler.kt:13-14`
```kotlin
class CrashHandler private constructor(
    private val context: Context
) : Thread.UncaughtExceptionHandler {
```
**问题**: 构造时传入 `context.applicationContext`（第32行），所以**不泄漏 Activity**。但 `CrashHandler.init(this)` 在 `Application.onCreate` 调用是正确的。
**结论**: 无泄漏风险，已正确使用 applicationContext。

### [Important] ReleaseCheckWorker 的 SimpleDateFormat 实例
**文件**: `ReleaseCheckWorker.kt:37-39`
```kotlin
private val dateParser = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
    timeZone = TimeZone.getTimeZone("UTC")
}
```
**问题**: `SimpleDateFormat` 不是线程安全的。虽然 Worker 通常串行执行，但理论上 `doWork()` 可能被并发调用。
**建议**: 在 `doWork()` 内创建局部变量，或使用 `ThreadLocal`。

### [Minor] TtlCache 无大小限制
**文件**: `TtlCache.kt`
```kotlin
private val cache = ConcurrentHashMap<String, Pair<T, Long>>()
```
**问题**: 无最大条目数限制，长期运行可能累积大量缓存条目。但 TTL 会清理过期条目，且实际使用中条目数量有限。
**建议**: 可选添加 `maxSize` 参数。

---

## 二、Compose 重组（Recomposition）性能

### [Important] MainActivity.kt 中 `collectAsState` 位置
**文件**: `MainActivity.kt:172`
```kotlin
val themeMode by themeStorage.themeMode.collectAsState(initial = "system")
```
**问题**: 在 `setContent` 的 Composable 中使用 `collectAsState` 是正确的。但 `themeMode` 的变化会导致整个 `TraktToSearchTheme` 及其所有子 Composable 重组。
**建议**: 如果 themeMode 不常变化，可接受。

### [Important] MainScreen 中重复 import
**文件**: `MainScreen.kt:46` 和 `MainScreen.kt:56`
```kotlin
import androidx.compose.runtime.collectAsState  // 重复导入
```
**问题**: 不影响运行，但代码不整洁。

### [Minor] SplashScreen 中 BitmapFactory.decodeResource
**文件**: `MainActivity.kt:238-241`
```kotlin
val launcherBitmap = remember {
    android.graphics.BitmapFactory.decodeResource(
        context.resources, R.drawable.ic_search_cloud
    )?.asImageBitmap()
}
```
**问题**: 在 `remember` 中解码 Bitmap 是正确的，只执行一次。但如果 `ic_search_cloud` 是大图，可能占用较多内存。
**建议**: 确保图片尺寸合适（56dp 显示，不需要过大资源）。

### [Minor] `var isReady by mutableStateOf(false)` 在 onCreate
**文件**: `MainActivity.kt:132`
**问题**: 这是 Activity 级别的 State，不是 Compose 的 State，但在 `setContent` 中被读取。这是正确用法。

---

## 三、Room / DataStore / WorkManager

### [Critical] Room 使用 fallbackToDestructiveMigration
**文件**: `DatabaseModule.kt:22`
```kotlin
).fallbackToDestructiveMigration().build()
```
**问题**: 数据库版本升级时会**销毁所有数据**。当前 version=2，未来升级会丢失用户缓存。
**建议**: 添加 `addMigrations()` 定义迁移策略。当前数据是缓存可重建，影响有限，但应尽早补上。

### [Important] Room Entity 缺少索引
**文件**: `Entities.kt:10-25`
```kotlin
@Entity(tableName = "media_items")
data class MediaItemEntity(
    @PrimaryKey val traktId: Int,
    ...
    val type: String,
    val listedAt: String,
```
**问题**: `getByType(type)` 查询按 `listedAt DESC` 排序，但 `type` 和 `listedAt` 上没有索引。数据量大时查询会变慢。
**建议**: 添加 `@Entity(indices = [Index("type"), Index("type", "listedAt")])`。

### [Important] NotificationRecordEntity 缺少索引
**文件**: `Entities.kt:53-64`
**问题**: `find(traktId, type, payload)` 和 `findByTraktId(traktId, type)` 查询需要复合索引。
**建议**: 添加 `@Entity(indices = [Index("traktId"), Index("traktId", "type")])`。

### [Important] DataStore 使用 preferencesDataStore 委托
**文件**: `TokenStorage.kt:17`
```kotlin
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "auth")
```
**问题**: 每个 Storage 类创建独立的 DataStore 实例（通过 `preferencesDataStore` 委托）。项目有 15+ 个 Storage 类，意味着 15+ 个独立 DataStore 文件。
**影响**: 每个 DataStore 都有自己的单例和协程作用域，可能导致文件过多。
**建议**: 考虑合并为一个 DataStore 或少量几个（如 auth、settings、cache），减少文件碎片。

### [Good] DataStore 使用正确
TokenStorage 的 `@Volatile` 内存缓存 + DataStore 持久化模式是正确的，避免了 OkHttp 拦截器中 `runBlocking`。

### [Good] WorkManager 使用正确
- `@HiltWorker` 正确注入依赖
- `ExistingPeriodicWorkPolicy.KEEP` 防止重复调度
- `Constraints` 配置了网络要求
- `CoroutineWorker` 使用协程执行

### [Minor] WorkManager isScheduled 使用阻塞调用
**文件**: `NotificationScheduler.kt:47-54`
```kotlin
fun isScheduled(callback: (Boolean) -> Unit) {
    WorkManager.getInstance(context)
        .getWorkInfosForUniqueWork(WORK_NAME)
        .get()  // 阻塞调用
```
**问题**: `.get()` 是阻塞调用，可能阻塞主线程。
**建议**: 改用 `ListenableFuture` 或 `Flow` 版本。

---

## 四、Flow / StateFlow / 协程性能

### [Important] TraktRepository 大量重复的分页拉取模式
**文件**: `TraktRepository.kt:120-214`
```kotlin
suspend fun getAllMovieWatchlist(): Result<List<TraktWatchlistMovieItem>> {
    val allItems = mutableListOf<TraktWatchlistMovieItem>()
    var page = 1
    var totalPages = 1
    while (page <= totalPages) { ... }
}
```
**问题**: `getAllMovieWatchlist`、`getAllShowWatchlist`、`getAllMovieHistory`、`getAllShowHistory` 都是相同的分页拉取模式。在 `ReleaseCheckWorker.doWork()` 中被调用时，如果用户想看列表很大，会同步拉取全部页面。
**建议**: 考虑并行拉取（`coroutineScope { async {} }`），或增加缓存/增量同步。

### [Important] ReleaseCheckWorker 串行 API 调用
**文件**: `ReleaseCheckWorker.kt:83-131`
```kotlin
for (item in watchlist) {
    val response = tmdbApiService.getMovieDetail(tmdbId)
    ...
}
```
**问题**: 对每个想看列表中的影视，串行调用 TMDB API 获取详情。如果列表有 100 部电影，会发起 100+ 个请求。
**建议**: 使用 `coroutineScope` + `async` 并行处理，或使用批量 API（如果有）。

### [Good] SearchViewModel 正确使用 searchJob?.cancel()
**文件**: `SearchViewModel.kt:250-251`
```kotlin
searchJob?.cancel()
searchJob = viewModelScope.launch { ... }
```
**问题**: 搜索取消旧任务，避免过时结果覆盖新结果。正确做法。

### [Good] Flow 使用 stateIn + WhileSubscribed
**文件**: `SettingsViewModel.kt:80`
```kotlin
stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ...)
```
**正确使用**: `WhileSubscribed(5000)` 在最后一个订阅者取消后 5 秒停止上游 Flow，节省资源。

### [Minor] TraktSearchViewModel 中 async/awaitAll 未设超时
**文件**: `TraktSearchViewModel.kt:168-169`
```kotlin
val traktItems = searchResults.map { item ->
    async { enrichSearchResult(item, searchType) }
}.awaitAll()
```
**问题**: 没有对单个 enrichSearchResult 设置超时。如果某个 TMDB 请求挂起，整个搜索会卡住。
**建议**: 添加 `withTimeoutOrNull` 或在 OkHttp 层设置合理超时。

### [Minor] SettingsViewModel 导入功能使用 delay(500) 做限流
**文件**: `SettingsViewModel.kt:323, 397, 495`
```kotlin
if (index < total - 1) delay(500)
```
**问题**: 500ms 固定延迟简单但低效。如果导入 100 条数据，需要 50 秒。
**建议**: 考虑使用令牌桶或滑动窗口限流器。

---

## 五、网络层（Retrofit/OkHttp）优化

### [Critical] 缺少 HTTP 缓存
**文件**: `NetworkModule.kt` 所有 OkHttpClient
**问题**: 没有配置 `OkHttpClient.cache()`。对于 TMDB/Trakt 这类 API，适当的 HTTP 缓存可以减少重复请求。
**建议**: 配置缓存目录和大小（如 10MB），并为 GET 请求添加 `Cache-Control` 拦截器。

### [Important] 缺少统一的重试拦截器
**文件**: `NetworkModule.kt`
**问题**: 虽然部分 client 设置了 `retryOnConnectionFailure(true)`，但没有自定义重试拦截器（如指数退退、429 限流重试）。
**建议**: 添加 OkHttp `Interceptor` 实现带退避的重试，特别是 Trakt API 有速率限制（约 1000 req/5min）。

### [Important] TMDB 客户端缺少 connectTimeout 以外的超时
**文件**: `NetworkModule.kt:104-125`
```kotlin
fun provideTmdbOkHttpClient(...): OkHttpClient {
    return OkHttpClient.Builder()
        ...
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        // 没有 writeTimeout
```
**问题**: 没有设置 `writeTimeout`（默认 10s）。虽然 TMDB 以读为主，但不一致的超时配置可能引发问题。
**建议**: 统一设置 connect/read/write timeout。

### [Important] 缺少 DNS 优化
**文件**: `NetworkModule.kt`
**问题**: 只有 TMDB 客户端有自定义 DNS（过滤 IPv4），其他客户端使用系统 DNS。
**建议**: 考虑为所有客户端统一使用 IPv4-only DNS（如果目标市场主要是中国大陆），或使用 OkHttp 的 `Dns` 接口实现自定义 DNS 解析（如 DoH）。

### [Important] Gitee access_token 暴露在 URL 中
**文件**: `NetworkModule.kt:330-338`
```kotlin
val newUrl = originalUrl.newBuilder()
    .addQueryParameter("access_token", token)
    .build()
```
**问题**: Token 作为 URL 参数传输，可能出现在日志、服务器日志中。
**建议**: 改用 `Authorization: token xxx` Header 方式。

### [Good] 拦截器设计合理
- 认证拦截器正确使用内存缓存避免阻塞
- 日志拦截器在 Release 模式下关闭
- 各服务有独立的超时配置

### [Minor] 多个 OkHttpClient 实例未共享连接池
**文件**: `NetworkModule.kt`
**问题**: 创建了 10+ 个独立的 OkHttpClient 实例（trakt、tmdb、pansou、panhub、zreso、omdb、github、gitee、custom_search、open-meteo、imageLoader）。
**影响**: 每个实例有独立的连接池和线程池，可能导致连接数过多。
**建议**: 复用共享的 OkHttpClient 作为基础，只覆盖需要不同的配置。或至少共享连接池：`OkHttpClient.connectionPool`。

### [Minor] 缺少证书固定（Certificate Pinning）
**问题**: 对于金融级应用可能需要，但对于媒体搜索应用，OkHttp 默认的系统信任库足够。
**建议**: 可选实现。

---

## 六、启动速度 / 冷启动 / Baseline Profile

### [Important] Application.onCreate 初始化较轻
**文件**: `TraktSearchApp.kt:30-34`
```kotlin
override fun onCreate() {
    super.onCreate()
    CrashHandler.init(this)
    JPushHelper.init(this)
}
```
**评估**: 初始化很轻量，只做了崩溃处理和推送初始化。**良好**。

### [Important] JPush 初始化在主线程
**文件**: `TraktSearchApp.kt:33`
```kotlin
JPushHelper.init(this)
```
**问题**: JPush SDK 初始化可能涉及网络请求和数据库操作，如果 SDK 内部阻塞主线程会影响启动速度。
**建议**: 移到后台线程初始化，或使用 App Startup 库延迟初始化。

### [Important] Splash 强制 1500ms 最小显示时间
**文件**: `MainActivity.kt:84, 155-158`
```kotlin
private const val MIN_SPLASH_DURATION_MS = 1500L
...
if (elapsed < MIN_SPLASH_DURATION_MS) {
    delay(MIN_SPLASH_DURATION_MS - elapsed)
}
```
**问题**: 强制 1.5 秒 Splash 可能影响用户体验（尤其在快设备上）。但这是品牌展示需求，可接受。

### [Critical] 缺少 Baseline Profile
**问题**: 项目中没有发现 Baseline Profile 配置。对于 Compose 应用，Baseline Profile 可以显著减少首次启动时间（通过 AOT 编译关键路径）。
**建议**:
1. 创建 `baseline-prof.txt` 文件
2. 在 `build.gradle.kts` 中添加 `baselineProfile` 插件
3. 使用 Jetpack Macrobenchmark 生成 profile
4. 可提升首次启动速度 10-30%

### [Important] Compose 未配置编译器 Metrics
**问题**: 没有启用 Compose 编译器 metrics 来分析重组情况。
**建议**: 在 `gradle.properties` 中添加：
```
kotlin.compose.compiler.metrics=true
kotlin.compose.compiler.reports=true
```
生成 HTML 报告分析哪些 Composable 有频繁重组。

### [Minor] 缺少 App Startup 库
**问题**: 当前的初始化在 `Application.onCreate` 中直接执行，没有使用 `androidx.startup` 库来管理初始化顺序和延迟。
**建议**: 对非关键初始化（如 JPush）使用 App Startup，实现按需初始化。

### [Minor] ProGuard/R8 配置
**文件**: `app/build.gradle.kts:69-70`
```kotlin
isMinifyEnabled = true
isShrinkResources = true
```
**正确**: Release 构建已启用混淆和资源压缩。

---

## 总结

| 维度 | Critical | Important | Minor |
|------|----------|-----------|-------|
| 内存泄漏 | 0 | 1 | 1 |
| Compose 重组 | 0 | 1 | 2 |
| Room/DataStore/WorkManager | 1 | 3 | 1 |
| Flow/协程 | 0 | 2 | 2 |
| 网络层 | 1 | 4 | 2 |
| 启动/性能 | 1 | 3 | 2 |
| **合计** | **3** | **14** | **10** |

### 最高优先级修复建议

1. **添加 Baseline Profile** — 启动性能提升 10-30%
2. **配置 HTTP 缓存** — 减少重复网络请求
3. **添加 Room 索引** — 查询性能提升
4. **为 OkHttpClient 添加重试拦截器** — 提升网络稳定性
5. **ReleaseCheckWorker 并行化 API 调用** — 减少后台任务耗时

整体代码质量良好，架构清晰。主要优化点在启动性能和网络层配置上。
