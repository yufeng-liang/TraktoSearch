# UI 一致性改造 + 筛选增强 + 沉浸式底色 实现计划

> **面向 AI 代理的工作者:** 必需子技能:使用 superpowers:subagent-driven-development(推荐)或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框(`- [ ]`)语法来跟踪进度。

**目标:** 完成 v2.23.0 后的 UI 一致性改造、Watchlist/豆瓣失败项页筛选增强、详情页沉浸式底色(海报主色调)等 8 大类需求。

**架构:** 按风险递增分 3 阶段:① 低风险修复(搜索框清空 / 网盘缓存 / 边距)→ ② 中风险改造(设置页布局 / 导入完善 / 沉浸式底色基础设施)→ ③ 高风险 UI 重构(Watchlist 筛选 / 豆瓣失败项页 haze / 豆瓣详情页布局重构)。

**技术栈:** Jetpack Compose + Hilt + Palette + Coil 2.7.0 + DataStore

**规格文档:**
- `docs/superpowers/specs/2026-07-06-ui-consistency-and-filter-design.md`(UI 一致性 + 筛选)
- `docs/superpowers/specs/2026-07-06-immersive-poster-bg-design.md`(沉浸式底色)

---

## 阶段 1:低风险修复

### 任务 1:TraktSearchScreen 搜索框一键清空

**文件:**
- 修改:`app/src/main/java/com/tracktosearch/ui/screen/traktsearch/TraktSearchScreen.kt:429-486`

- [ ] **步骤 1:修改 trailingIcon 加一键清空**

在 `TraktSearchScreen.kt` 行 465-473 的 `trailingIcon`,改为参考 `WatchlistScreen.kt:654-666` 的模式:

```kotlin
trailingIcon = {
    if (searchQuery.isNotEmpty()) {
        IconButton(onClick = { searchQuery = "" }, modifier = Modifier.size(32.dp)) {
            Icon(
                Icons.Filled.Close,
                contentDescription = stringResource(R.string.content_desc_clear),
                modifier = Modifier.size(19.dp)
            )
        }
    } else {
        IconButton(onClick = {
            if (searchQuery.isNotBlank()) viewModel.search(searchQuery)
        }) {
            Icon(Icons.Default.Search, contentDescription = stringResource(R.string.watchlist_search))
        }
    }
},
```

- [ ] **步骤 2:确认 import 已有 `Icons.Filled.Close`**

若 import 区缺,补:
```kotlin
import androidx.compose.material.icons.filled.Close
```

- [ ] **步骤 3:构建 debug 包验证**

运行:`.\gradlew assembleDebug`
预期:BUILD SUCCESSFUL

- [ ] **步骤 4:Commit**

```bash
git add app/src/main/java/com/tracktosearch/ui/screen/traktsearch/TraktSearchScreen.kt
git commit -m "feat: TraktSearchScreen 搜索框添加一键清空按钮"
```

---

### 任务 2:豆瓣详情页网盘缓存修复

**文件:**
- 修改:`app/src/main/java/com/tracktosearch/ui/screen/douban/DoubanItemDetailScreen.kt:248-262`(DoubanItemDetailViewModel.searchResources)

- [ ] **步骤 1:把 refreshResources 改为 searchResources**

在 `DoubanItemDetailViewModel.searchResources()` 方法中(行 248-262),把所有 `resourceRepository.refreshResources(...)` 改为 `resourceRepository.searchResources(...)`:

```kotlin
val results = coroutineScope {
    val deferreds = keywords.map { kw ->
        async {
            resourceRepository.searchResources(
                keyword = kw,
                enabledSources = storageEnabledSources,
                enabledDiskTypes = _uiState.value.enabledDiskTypes,
                isShow = isShow
            ).getOrDefault(emptyList())
        }
    }
    deferreds.awaitAll()
}
```

注意:`searchResources` 的签名与 `refreshResources` 一致(都是 suspend,返回 `Result<List<ResourceItem>>`),仅行为不同(先查缓存命中即用 vs 强制清缓存重拉)。

- [ ] **步骤 2:新增手动刷新入口**

在 `DoubanItemDetailScreen.kt` 的 TopAppBar actions 中新增刷新按钮(参考 DetailScreen 的刷新按钮风格):

```kotlin
TopAppBar(
    title = { Text(uiState.failure?.title ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis) },
    navigationIcon = { IconButton(onClick = onBack) { Icon(ArrowBack, ...) } },
    actions = {
        IconButton(onClick = { viewModel.refreshResources() }) {
            Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.content_desc_refresh))
        }
    }
)
```

ViewModel 新增方法:
```kotlin
fun refreshResources() {
    viewModelScope.launch {
        // 手动刷新:清缓存重拉
        val failure = _uiState.value.failure ?: return@launch
        // 复用 searchResources 逻辑但调用 resourceRepository.refreshResources
        // ... (清缓存版本)
    }
}
```

- [ ] **步骤 3:构建 debug 包验证**

运行:`.\gradlew assembleDebug`
预期:BUILD SUCCESSFUL

- [ ] **步骤 4:Commit**

```bash
git add app/src/main/java/com/tracktosearch/ui/screen/douban/DoubanItemDetailScreen.kt
git commit -m "fix: 豆瓣详情页网盘缓存第二次打开转圈问题"
```

---

### 任务 3:发现页底部边距增大

**文件:**
- 修改:`app/src/main/java/com/tracktosearch/ui/screen/discover/DiscoverScreen.kt:194`

- [ ] **步骤 1:改 bottom = 80.dp 为 112.dp**

```kotlin
contentPadding = PaddingValues(
    start = 16.dp,
    end = 16.dp,
    top = 80.dp + statusBarHeight,
    bottom = 112.dp
)
```

- [ ] **步骤 2:Commit**

```bash
git add app/src/main/java/com/tracktosearch/ui/screen/discover/DiscoverScreen.kt
git commit -m "style: 发现页底部边距 80dp → 112dp"
```

---

## 阶段 2:中风险改造

### 任务 4:沉浸式底色 — 依赖与缓存基础设施

**文件:**
- 修改:`gradle/libs.versions.toml`
- 修改:`app/build.gradle.kts`
- 创建:`app/src/main/java/com/tracktosearch/data/util/PosterColorCache.kt`
- 创建:`app/src/main/java/com/tracktosearch/data/util/PosterColorExtractor.kt`
- 创建:`app/src/main/java/com/tracktosearch/di/UtilModule.kt`

- [ ] **步骤 1:添加 Palette 依赖**

`gradle/libs.versions.toml` 在 `[versions]` 区添加:
```toml
palette = "1.0.0"
```

在 `[libraries]` 区添加:
```toml
androidx-palette = { group = "androidx.palette", name = "palette-ktx", version.ref = "palette" }
```

`app/build.gradle.kts` 在 dependencies 块添加:
```kotlin
implementation(libs.androidx.palette)
```

- [ ] **步骤 2:实现 PosterColorCache**

创建 `app/src/main/java/com/tracktosearch/data/util/PosterColorCache.kt`:

```kotlin
package com.tracktosearch.data.util

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

private val Context.posterColorDataStore by preferencesDataStore(name = "poster_color_cache")

/**
 * 海报主色调持久化缓存。
 * - key: posterUrl(海报图 URL)
 * - value: ARGB Long(海报主色调)
 * - TTL: 永久(海报颜色不会变,符合 AGENTS.md 持久化缓存原则)
 *
 * 内存一级 + DataStore 二级结构,启动时异步加载到内存。
 */
@Singleton
class PosterColorCache @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val memoryCache = mutableMapOf<String, Long>()

    suspend fun getColor(posterUrl: String): Long? = withContext(Dispatchers.IO) {
        memoryCache[posterUrl] ?: run {
            val ds = context.posterColorDataStore.data.first()
            val v = ds[longPreferencesKey(posterUrl)]
            if (v != null) {
                memoryCache[posterUrl] = v
                v
            } else null
        }
    }

    suspend fun putColor(posterUrl: String, argb: Long) = withContext(Dispatchers.IO) {
        memoryCache[posterUrl] = argb
        val key = longPreferencesKey(posterUrl)
        context.posterColorDataStore.edit { it[key] = argb }
    }
}
```

注意:DataStore 的 key 用 `longPreferencesKey(posterUrl)`,其中 posterUrl 作为 key name 的一部分。对于 URL 较长的情况,DataStore 支持任意字符串 key name。

- [ ] **步骤 3:实现 PosterColorExtractor**

创建 `app/src/main/java/com/tracktosearch/data/util/PosterColorExtractor.kt`:

```kotlin
package com.tracktosearch.data.util

import android.graphics.Bitmap
import androidx.palette.graphics.Palette
import dagger.Lazy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 海报主色调提取器。
 * 先查 PosterColorCache,命中直接返回;未命中用 Palette 异步提取 dominantColor,写缓存后返回。
 */
@Singleton
class PosterColorExtractor @Inject constructor(
    private val cache: PosterColorCache
) {
    suspend fun extractDominantColor(posterUrl: String, bitmap: Bitmap): Long = withContext(Dispatchers.Default) {
        cache.getColor(posterUrl)?.let { return@withContext it }

        val palette = Palette.from(bitmap).generate()
        val argb = palette.getDominantColor(0).toLong()

        if (argb != 0L) {
            cache.putColor(posterUrl, argb)
        }
        argb
    }
}
```

- [ ] **步骤 4:实现 UtilModule**

创建 `app/src/main/java/com/tracktosearch/di/UtilModule.kt`:

```kotlin
package com.tracktosearch.di

import android.content.Context
import com.tracktosearch.data.util.PosterColorCache
import com.tracktosearch.data.util.PosterColorExtractor
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object UtilModule {
    @Provides
    @Singleton
    fun providePosterColorCache(@ApplicationContext context: Context): PosterColorCache =
        PosterColorCache(context)

    @Provides
    @Singleton
    fun providePosterColorExtractor(cache: PosterColorCache): PosterColorExtractor =
        PosterColorExtractor(cache)
}
```

- [ ] **步骤 5:构建 debug 包验证**

运行:`.\gradlew assembleDebug`
预期:BUILD SUCCESSFUL

- [ ] **步骤 6:Commit**

```bash
git add gradle/libs.versions.toml app/build.gradle.kts app/src/main/java/com/tracktosearch/data/util/PosterColorCache.kt app/src/main/java/com/tracktosearch/data/util/PosterColorExtractor.kt app/src/main/java/com/tracktosearch/di/UtilModule.kt
git commit -m "feat: 添加 Palette 依赖和海报主色调缓存基础设施"
```

---

### 任务 5:沉浸式底色 — 正常详情页

**文件:**
- 修改:`app/src/main/java/com/tracktosearch/ui/screen/detail/DetailViewModel.kt`(DetailUiState + updatePosterColor)
- 修改:`app/src/main/java/com/tracktosearch/ui/screen/detail/DetailHeaderContent.kt:109-151`(AsyncImage listener)
- 修改:`app/src/main/java/com/tracktosearch/ui/screen/detail/DetailScreen.kt:181-185`(Box 渐变背景)

- [ ] **步骤 1:DetailUiState 新增 posterDominantColor 字段**

在 `DetailViewModel.kt` 的 `DetailUiState` data class 中(行 61-143),在 `posterUrl` 字段附近添加:

```kotlin
val posterDominantColor: Color? = null,
```

确保 import:
```kotlin
import androidx.compose.ui.graphics.Color
```

- [ ] **步骤 2:DetailViewModel 注入 PosterColorExtractor + 新增 updatePosterColor**

```kotlin
@HiltViewModel
class DetailViewModel @Inject constructor(
    // ... 现有依赖
    private val posterColorExtractor: PosterColorExtractor
) : ViewModel() {
    // ...

    fun updatePosterColor(color: Color) {
        _uiState.value = _uiState.value.copy(posterDominantColor = color)
    }
}
```

- [ ] **步骤 3:DetailHeaderContent 加 listener 提取主色**

修改 `DetailHeaderContent.kt:109-151`,在 `AsyncImage` 的 `ImageRequest.Builder` 链上加 `listener`:

```kotlin
val scope = rememberCoroutineScope()
// posterColorExtractor 通过参数传入(从 DetailScreen 传)
// onPosterColorExtracted 通过参数传入

AsyncImage(
    model = remember(uiState.posterUrl) {
        ImageRequest.Builder(context)
            .data(uiState.posterUrl)
            .size(264)
            .crossfade(false)
            .listener(
                onSuccess = { _, result ->
                    val bitmap = androidx.core.graphics.drawable.toBitmap(result.drawable)
                    scope.launch {
                        val argb = posterColorExtractor.extractDominantColor(uiState.posterUrl!!, bitmap)
                        val color = Color(argb)
                        onPosterColorExtracted(color)
                    }
                }
            )
            .build()
    },
    // ...
)
```

需要给 `DetailHeaderContent` Composable 增加参数:
- `posterColorExtractor: PosterColorExtractor`
- `onPosterColorExtracted: (Color) -> Unit`

并在 `DetailScreen` 调用处传入 `viewModel.posterColorExtractor` 和 `viewModel::updatePosterColor`。

- [ ] **步骤 4:DetailScreen Box 加垂直渐变背景**

修改 `DetailScreen.kt:181-185`:

```kotlin
Scaffold(contentWindowInsets = WindowInsets(0, 0, 0, 0)) { padding ->
    Box(modifier = Modifier
        .fillMaxSize()
        .padding(padding)
        .then(
            uiState.posterDominantColor?.let { c ->
                Modifier.background(
                    Brush.verticalGradient(
                        colors = listOf(
                            c.copy(alpha = 0.45f),
                            MaterialTheme.colorScheme.background
                        )
                    )
                )
            } ?: Modifier
        )
    ) {
        // ... 现有 LazyColumn
    }
}
```

确保 import:
```kotlin
import androidx.compose.foundation.Brush
import androidx.compose.foundation.background
```

- [ ] **步骤 5:构建 debug 包验证**

运行:`.\gradlew assembleDebug`
预期:BUILD SUCCESSFUL

- [ ] **步骤 6:Commit**

```bash
git add app/src/main/java/com/tracktosearch/ui/screen/detail/
git commit -m "feat: 正常详情页沉浸式底色(海报主色调垂直渐变)"
```

---

### 任务 6:沉浸式底色 — 豆瓣失败项详情页

**文件:**
- 修改:`app/src/main/java/com/tracktosearch/ui/screen/douban/DoubanItemDetailScreen.kt`(DoubanItemDetailUiState + DoubanItemHeader + Scaffold)

- [ ] **步骤 1:DoubanItemDetailUiState 新增 posterDominantColor 字段**

在 `DoubanItemDetailUiState` data class 中添加:
```kotlin
val posterDominantColor: Color? = null,
```

- [ ] **步骤 2:DoubanItemDetailViewModel 注入 PosterColorExtractor + updatePosterColor**

```kotlin
@HiltViewModel
class DoubanItemDetailViewModel @Inject constructor(
    // ... 现有依赖
    private val posterColorExtractor: PosterColorExtractor
) : ViewModel() {
    fun updatePosterColor(color: Color) {
        _uiState.value = _uiState.value.copy(posterDominantColor = color)
    }
}
```

- [ ] **步骤 3:DoubanItemHeader 的 AsyncImage 加 listener**

参考任务 5 步骤 3,在 `DoubanItemHeader` 的 `AsyncImage`(行 744-754)加同样的 listener 和参数。

- [ ] **步骤 4:DoubanItemDetailScreen 的 Scaffold Box 加渐变背景**

参考任务 5 步骤 4,修改行 443-472 的 Scaffold 内 Box。

- [ ] **步骤 5:构建验证**

运行:`.\gradlew assembleDebug`
预期:BUILD SUCCESSFUL

- [ ] **步骤 6:Commit**

```bash
git add app/src/main/java/com/tracktosearch/ui/screen/douban/DoubanItemDetailScreen.kt
git commit -m "feat: 豆瓣失败项详情页沉浸式底色"
```

---

### 任务 7:设置页布局改造

**文件:**
- 修改:`app/src/main/java/com/tracktosearch/ui/screen/settings/SettingsScreen.kt`(缓存管理卡片 + 2x2 数据流通卡片 + 观看统计入口)
- 修改:`app/src/main/java/com/tracktosearch/ui/navigation/AppNavigation.kt`(onStatisticsClick)
- 修改:4 语言 strings.xml

- [ ] **步骤 1:缓存管理卡片整卡可点击 + rememberSaveable**

修改 `CacheManagementItem`(行 1550-1640):
- `var expanded by remember { mutableStateOf(false) }` → `var expanded by rememberSaveable { mutableStateOf(false) }`
- Row 加 `clickable { expanded = !expanded }`,保留 IconButton(同步状态)

```kotlin
Row(
    modifier = Modifier
        .fillMaxWidth()
        .clickable { expanded = !expanded }
        .padding(horizontal = 16.dp, vertical = 16.dp),
    verticalAlignment = Alignment.CenterVertically
) {
    // ... Icon + Column(title + subtitle)
    IconButton(onClick = { expanded = !expanded }) { ... }
}
```

- [ ] **步骤 2:4 个数据流通入口改为 2x2 卡片**

在「数据管理」section,把 4 个 `SettingsItem`(导出 JSON / 从 IMDb 导入 / 上传云端 / 从云端拉取)替换为:

```kotlin
item {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        DataFlowCard(
            modifier = Modifier.weight(1f),
            icon = Icons.Default.FileUpload,
            title = stringResource(R.string.settings_export_marks_data),
            onClick = { exportLauncher.launch("TraktToSearch-Export.json") }
        )
        DataFlowCard(
            modifier = Modifier.weight(1f),
            icon = Icons.Default.FileDownload,
            title = stringResource(R.string.import_imdb),
            onClick = { importImdbLauncher.launch(arrayOf("text/*", "text/csv", "application/vnd.ms-excel")) }
        )
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        DataFlowCard(
            modifier = Modifier.weight(1f),
            icon = Icons.Default.CloudUpload,
            title = stringResource(R.string.settings_douban_upload_cloud),
            onClick = { if (!cloudSyncLoading) doubanRetryViewModel.uploadToCloud() }
        )
        DataFlowCard(
            modifier = Modifier.weight(1f),
            icon = Icons.Default.CloudDownload,
            title = stringResource(R.string.settings_douban_download_cloud),
            onClick = { if (!cloudSyncLoading) doubanRetryViewModel.downloadFromCloud() }
        )
    }
}
```

新增 `DataFlowCard` Composable:
```kotlin
@Composable
private fun DataFlowCard(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    title: String,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text(title, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
        }
    }
}
```

- [ ] **步骤 3:移除设置页的"从 JSON 导入失败项"入口**

删除 SettingsScreen.kt 中行 572-583 的 `SettingsItem`(icons = FileUpload, title = settings_douban_import_failures)。该入口已移到失败项查看页标题栏(任务 9)。

- [ ] **步骤 4:新增观看统计入口**

在「账户」section 上方新增:
```kotlin
item {
    SettingsItem(
        icon = Icons.Default.BarChart,
        title = stringResource(R.string.settings_view_statistics),
        onClick = onStatisticsClick
    )
}
```

SettingsScreen 函数签名新增 `onStatisticsClick: () -> Unit = {}` 参数。

- [ ] **步骤 5:AppNavigation 传 onStatisticsClick**

在 `AppNavigation.kt` 中 `SettingsScreen(...)` 调用处添加:
```kotlin
onStatisticsClick = { navController.navigate(Routes.STATISTICS) }
```

- [ ] **步骤 6:4 语言 strings.xml 新增字符串**

`values/strings.xml`:
```xml
<string name="settings_export_marks_data">Export marks</string>
<string name="settings_view_statistics">View statistics</string>
```

`values-zh/strings.xml`:
```xml
<string name="settings_export_marks_data">导出标记数据</string>
<string name="settings_view_statistics">观看统计</string>
```

`values-ja/strings.xml`:
```xml
<string name="settings_export_marks_data">マークをエクスポート</string>
<string name="settings_view_statistics">視聴統計</string>
```

`values-ko/strings.xml`:
```xml
<string name="settings_export_marks_data">마크 내보내기</string>
<string name="settings_view_statistics">시청 통계</string>
```

- [ ] **步骤 7:构建验证 + Commit**

运行:`.\gradlew assembleDebug`

```bash
git add app/src/main/java/com/tracktosearch/ui/screen/settings/SettingsScreen.kt app/src/main/java/com/tracktosearch/ui/navigation/AppNavigation.kt app/src/main/res/values*/strings.xml
git commit -m "feat: 设置页缓存管理整卡可点击 + 2x2 数据流通卡片 + 观看统计入口"
```

---

### 任务 8:导入功能完善(IMDb 格式校验 + JSON 格式校验 + Watchlist 刷新)

**文件:**
- 修改:`app/src/main/java/com/tracktosearch/data/util/DataExportImport.kt`(parseImdbCsv 严格校验)
- 修改:`app/src/main/java/com/tracktosearch/data/repository/DoubanFailureExporter.kt`(importToRoom 返回 ImportResult)
- 修改:`app/src/main/java/com/tracktosearch/ui/screen/settings/SettingsViewModel.kt`(importFromImdb 错误反馈 + Watchlist 刷新)
- 修改:`app/src/main/java/com/tracktosearch/ui/screen/settings/SettingsScreen.kt`(JSON 导入错误 Snackbar)
- 修改:4 语言 strings.xml

- [ ] **步骤 1:DataExportImport.parseImdbCsv 严格校验**

```kotlin
object DataExportImport {
    sealed class ParseResult {
        data class Success(val items: List<ImportItem>) : ParseResult()
        object MissingRequiredColumns : ParseResult()
        object Empty : ParseResult()
        data class Error(val message: String) : ParseResult()
    }

    fun parseImdbCsv(csvContent: String): ParseResult {
        return try {
            val rows = parseCsv(csvContent)
            if (rows.isEmpty()) return ParseResult.Empty
            val header = rows.first().map { it.trim().lowercase() }
            val requiredCols = listOf("title", "created", "title type")
            if (!requiredCols.all { col -> header.any { it == col } }) {
                return ParseResult.MissingRequiredColumns
            }
            // ... 现有解析逻辑,包装为 Success
            ParseResult.Success(items)
        } catch (e: Exception) {
            ParseResult.Error(e.message ?: "Unknown error")
        }
    }
}
```

- [ ] **步骤 2:DoubanFailureExporter.importToRoom 返回 ImportResult**

```kotlin
sealed class ImportResult {
    data class Success(val count: Int) : ImportResult()
    object InvalidFormat : ImportResult()
    object Empty : ImportResult()
    data class Error(val message: String) : ImportResult()
}

suspend fun importToRoom(context: Context, uri: Uri): ImportResult = withContext(Dispatchers.IO) {
    try {
        val failures = importFromFile(context, uri)
            ?: return@withContext ImportResult.InvalidFormat
        if (failures.isEmpty()) return@withContext ImportResult.Empty
        val entities = failures.map { it.toEntity() }
        doubanSyncFailureDao.insertAll(entities)
        ImportResult.Success(entities.size)
    } catch (e: Exception) {
        ImportResult.Error(e.message ?: "Unknown error")
    }
}
```

- [ ] **步骤 3:SettingsViewModel.importFromImdb 使用新 ParseResult + Watchlist 刷新**

```kotlin
fun importFromImdb(uri: Uri) {
    viewModelScope.launch {
        _exportImportState.value = _exportImportState.value.copy(isImporting = true, message = null)
        try {
            val csvContent = readUriContent(uri)
            when (val result = DataExportImport.parseImdbCsv(csvContent)) {
                is DataExportImport.ParseResult.Success -> {
                    // ... 现有同步逻辑
                    // 末尾刷新 WatchlistWatchedIds
                    // 若无该方法,在 traktRepository.addToWatchlist 内部已触发
                }
                is DataExportImport.ParseResult.MissingRequiredColumns ->
                    _exportImportState.value = _exportImportState.value.copy(
                        isImporting = false,
                        message = context.getString(R.string.error_not_imdb_csv)
                    )
                is DataExportImport.ParseResult.Empty ->
                    _exportImportState.value = _exportImportState.value.copy(
                        isImporting = false,
                        message = context.getString(R.string.error_empty_csv)
                    )
                is DataExportImport.ParseResult.Error ->
                    _exportImportState.value = _exportImportState.value.copy(
                        isImporting = false,
                        message = context.getString(R.string.error_parse_failed)
                    )
            }
        } catch (e: Exception) {
            // ...
        }
    }
}
```

- [ ] **步骤 4:SettingsScreen JSON 导入失败 Snackbar**

修改 `importFailuresLauncher` 回调(行 186-194):
```kotlin
scope.launch {
    when (val result = doubanRetryViewModel.doubanFailureExporter.importToRoom(context, uri)) {
        is ImportResult.Success -> {
            doubanRetryViewModel.refreshRetryState()
            snackbarHostState.showSnackbar(context.getString(R.string.snackbar_import_done_json, result.count))
            onDoubanFailures()
        }
        is ImportResult.InvalidFormat ->
            snackbarHostState.showSnackbar(context.getString(R.string.error_invalid_json_format))
        is ImportResult.Empty ->
            snackbarHostState.showSnackbar(context.getString(R.string.error_empty_csv))
        is ImportResult.Error ->
            snackbarHostState.showSnackbar(context.getString(R.string.error_parse_failed))
    }
}
```

- [ ] **步骤 5:4 语言 strings.xml 新增错误字符串**

```xml
<string name="error_not_imdb_csv">Not a valid IMDb CSV (missing required columns: Title, Created, Title Type)</string>
<string name="error_empty_csv">File is empty or has no data rows</string>
<string name="error_parse_failed">Failed to parse file: %1$s</string>
<string name="error_invalid_json_format">Invalid JSON format for failures file</string>
<string name="snackbar_import_done_json">Imported %1$d failure items</string>
```

同步 zh/ja/ko 翻译。

- [ ] **步骤 6:构建验证 + Commit**

```bash
git add ...
git commit -m "feat: IMDb/JSON 导入格式校验 + 错误 Snackbar + Watchlist 刷新"
```

---

## 阶段 3:高风险 UI 重构

### 任务 9:豆瓣失败项查看页改造(haze + 搜索 + 筛选 + JSON 导入入口)

**文件:**
- 修改:`app/src/main/java/com/tracktosearch/ui/screen/douban/DoubanFailuresScreen.kt`
- 修改:`app/src/main/java/com/tracktosearch/ui/screen/douban/DoubanFailuresViewModel.kt`(若存在)或在同文件
- 修改:4 语言 strings.xml

- [ ] **步骤 1:引入 haze 模糊(参考 WatchlistScreen 行 234, 455, 546-553)**

在 `DoubanFailuresScreen.kt`:
- 添加 `hazeState = remember { HazeState() }`
- `LazyVerticalGrid` 加 `.hazeSource(state = hazeState)`
- 顶部用 `Box` 包裹 `hazeEffect(style = HazeMaterials.thin())` + `surface.copy(alpha = 0.50f)` 兜底
- 顶部 Box 内含:状态栏 Spacer + 标题栏 + 搜索框 + ModeCapsuleToggle + PrimaryTabRow
- `PrimaryTabRow` 的 `containerColor = Color.Transparent`

- [ ] **步骤 2:标题栏右侧加 JSON 导入入口 + 搜索框 + 筛选按钮**

标题栏 actions:
```kotlin
actions = {
    IconButton(onClick = { importFailuresLauncher.launch(arrayOf("application/json")) }) {
        Icon(Icons.Default.FileUpload, contentDescription = stringResource(R.string.douban_retry_option_json))
    }
    IconButton(onClick = { showFilterSheet = true }) {
        Icon(
            Icons.Default.Tune,
            contentDescription = stringResource(R.string.filter_title),
            tint = if (hasActiveFilters) MaterialTheme.colorScheme.primary else LocalContentColor.current
        )
    }
}
```

标题栏下方新增搜索框(参考 WatchlistScreen 行 613-679)。

- [ ] **步骤 3:实现筛选 ModalBottomSheet**

筛选维度:失败原因(多选 FilterChip)+ 标记时间(升降序 + 区间预设)。

ViewModel 新增 `FilterState`:
```kotlin
data class FilterState(
    val selectedReasons: Set<FailureReason> = emptySet(),
    val markedTimePreset: MarkedTimePreset = MarkedTimePreset.ALL,
    val markedTimeOrder: SortOrder = SortOrder.DESC
)
```

- [ ] **步骤 4:筛选逻辑叠加到 filtered 链路**

在现有 `filtered`(行 187-198)基础上叠加 filter 谓词:
- `selectedReasons.isEmpty() || it.failureReason in selectedReasons`
- markedTimePreset 过滤
- markedTimeOrder 排序

- [ ] **步骤 5:4 语言 strings.xml 新增筛选相关字符串**

```xml
<string name="filter_title">Filter</string>
<string name="filter_marked_time">Marked time</string>
<string name="filter_time_7d">Last 7 days</string>
<string name="filter_time_30d">Last 30 days</string>
<string name="filter_time_all">All</string>
<string name="filter_sort_order">Sort order</string>
<string name="filter_reset">Reset</string>
<string name="filter_apply">Apply</string>
<string name="douban_failure_search_hint">Search title or subtitle</string>
```

同步 zh/ja/ko 翻译。

- [ ] **步骤 6:构建验证 + Commit**

```bash
git add ...
git commit -m "feat: 豆瓣失败项查看页 haze 模糊 + 搜索 + 筛选 + JSON 导入入口"
```

---

### 任务 10:豆瓣失败项详情页布局重构(haze + 失败原因位置 + 海报大图)

**文件:**
- 修改:`app/src/main/java/com/tracktosearch/ui/screen/douban/DoubanItemDetailScreen.kt`

- [ ] **步骤 1:引入 haze 模糊(参考任务 9 步骤 1)**

- [ ] **步骤 2:头部布局重构 — 失败原因栏移到海报右侧**

修改 `DoubanItemHeader`(行 721-846):
- 顶部边距从 32dp 改为 `statusBarsPadding`
- Row 内海报右侧的 Column 用 `Spacer(modifier = Modifier.weight(1f))` 把失败原因栏推到底部
- 失败原因栏底部与海报底部对齐

```kotlin
Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp).statusBarsPadding()) {
    Row(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.Top) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.size(width = 120.dp, height = 180.dp)
                .then(if (failure.posterUrl != null) Modifier.clickable { onPosterClick() } else Modifier)
        ) { AsyncImage(...) }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp)) {
            Text(failure.title, style = titleLarge, fontWeight = Bold)
            Text("${failure.year} · ${failure.genres}", style = bodySmall, color = onSurfaceVariant)
            Text("标记时间: ${failure.markedAt}", style = bodySmall, color = onSurfaceVariant)
            Text("标记: ${failure.status.localizedName}", style = bodySmall, color = onSurfaceVariant)
            Spacer(modifier = Modifier.weight(1f))
            // 失败原因栏,底部与海报底部对齐
            DoubanFailureBanner(failure = failure, modifier = Modifier.fillMaxWidth())
        }
    }
}
```

- [ ] **步骤 3:复用 DetailPosterOverlay 替换 DoubanPosterOverlay**

移除 `DoubanPosterOverlay`(行 1233-1307),改为复用 `DetailPosterOverlay`:

```kotlin
if (showPosterFullscreen && posterUrl != null) {
    PosterFullscreenOverlay(
        posterUrl = posterUrl,
        title = failure.title,
        onDismiss = { showPosterFullscreen = false }
    )
}
```

确保 import:
```kotlin
import com.tracktosearch.ui.screen.detail.PosterFullscreenOverlay
```

- [ ] **步骤 4:构建验证 + Commit**

```bash
git add ...
git commit -m "feat: 豆瓣失败项详情页 haze + 失败原因栏位置 + 复用 DetailPosterOverlay"
```

---

### 任务 11:Watchlist 筛选增强(统计按钮换筛选按钮 + ModalBottomSheet)

**文件:**
- 修改:`app/src/main/java/com/tracktosearch/ui/screen/watchlist/WatchlistScreen.kt`
- 修改:`app/src/main/java/com/tracktosearch/ui/screen/watchlist/WatchlistViewModel.kt`
- 修改:4 语言 strings.xml

- [ ] **步骤 1:WatchlistViewModel 新增 FilterState**

```kotlin
data class FilterState(
    val selectedGenres: Set<String> = emptySet(),
    val yearRange: IntRange = 1900..2100,
    val markedTimePreset: MarkedTimePreset = MarkedTimePreset.ALL,
    val markedTimeOrder: SortOrder = SortOrder.DESC,
    val ratingRange: ClosedFloatingPointRange<Float> = 0f..10f
)

enum class MarkedTimePreset { SEVEN_DAYS, THIRTY_DAYS, ALL }
enum class SortOrder { ASC, DESC }

private val _filterState = MutableStateFlow(FilterState())
val filterState: StateFlow<FilterState> = _filterState.asStateFlow()

val hasActiveFilters: StateFlow<Boolean> = _filterState.map { state ->
    state.selectedGenres.isNotEmpty() ||
    state.yearRange != 1900..2100 ||
    state.markedTimePreset != MarkedTimePreset.ALL ||
    state.ratingRange != 0f..10f
}.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

// 从当前已加载列表聚合可选 genres
val availableGenres: StateFlow<List<String>> = // ...
```

- [ ] **步骤 2:filteredMovies/filteredShows 链路叠加 filter 谓词**

在现有 `filteredMovies`(行 287-314)基础上:
```kotlin
val filteredMovies = movies.filter { item ->
    item.displayTitle.contains(searchQuery, ignoreCase = true) &&
    (filterState.selectedGenres.isEmpty() || filterState.selectedGenres.any { it in item.genres.split(",") }) &&
    item.year?.let { it in filterState.yearRange } ?: false &&
    // markedTimePreset 过滤
    item.traktRating in filterState.ratingRange
}.let { list ->
    if (filterState.markedTimeOrder == SortOrder.DESC) list.sortedByDescending { it.listedAt }
    else list.sortedBy { it.listedAt }
}
```

- [ ] **步骤 3:WatchlistScreen 统计按钮换筛选按钮**

行 681-687:
```kotlin
val hasActiveFilters by viewModel.hasActiveFilters.collectAsStateWithLifecycle()
var showFilterSheet by remember { mutableStateOf(false) }

IconButton(onClick = { showFilterSheet = true }) {
    Icon(
        Icons.Default.Tune,
        contentDescription = stringResource(R.string.filter_title),
        tint = if (hasActiveFilters) MaterialTheme.colorScheme.primary else LocalContentColor.current,
        modifier = Modifier.size(32.dp)
    )
}
```

移除 `onStatisticsClick` 参数(统计入口已移到设置页,任务 7)。

- [ ] **步骤 4:实现筛选 ModalBottomSheet**

```kotlin
if (showFilterSheet) {
    ModalBottomSheet(onDismissRequest = { showFilterSheet = false }) {
        Column(modifier = Modifier.padding(16.dp)) {
            // 类型 FilterChip 流式排列(FlowRow)
            // 年份 RangeSlider
            // 标记时间 SingleChoiceChip + SegmentedButton
            // Trakt 评分 RangeSlider 0-10 步长 0.5
            Row {
                TextButton(onClick = { viewModel.resetFilters() }) { Text(stringResource(R.string.filter_reset)) }
                Spacer(Modifier.weight(1f))
                Button(onClick = { showFilterSheet = false }) { Text(stringResource(R.string.filter_apply)) }
            }
        }
    }
}
```

- [ ] **步骤 5:4 语言 strings.xml 新增筛选字符串**

```xml
<string name="filter_genre">Genre</string>
<string name="filter_year">Year</string>
<string name="filter_rating">Trakt rating</string>
```

同步 zh/ja/ko 翻译。

- [ ] **步骤 6:AppNavigation 移除 WatchlistScreen 的 onStatisticsClick**

(已在任务 7 步骤 5 完成,此处仅确认)

- [ ] **步骤 7:构建验证 + Commit**

```bash
git add ...
git commit -m "feat: Watchlist 筛选增强(统计按钮换筛选按钮 + ModalBottomSheet + 类型/年份/标记时间/评分)"
```

---

### 任务 12:最终构建验证

- [ ] **步骤 1:完整构建 debug 包**

运行:`.\gradlew assembleDebug`
预期:BUILD SUCCESSFUL

- [ ] **步骤 2:Commit(如有遗漏修复)**

```bash
git add ...
git commit -m "chore: 最终构建验证修复"
```

---

### 任务 13:海报大图查看双击放大

**文件:**
- 修改:`app/src/main/java/com/tracktosearch/ui/screen/detail/DetailPosterOverlay.kt`

**背景**: 现有 `PosterFullscreenOverlay` 用 `net.engawapg.lib.zoomable` 库支持双指缩放,但不支持双击放大/还原。需手动添加双击手势。

- [ ] **步骤 1:在 AsyncImage 上添加双击手势检测**

修改 `DetailPosterOverlay.kt:107-126` 的 `AsyncImage`:

```kotlin
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput

// ...

AsyncImage(
    model = remember(posterUrl) {
        ImageRequest.Builder(context)
            .data(posterUrl)
            .crossfade(false)
            .size(1080)
            .build()
    },
    contentDescription = title,
    contentScale = ContentScale.Fit,
    modifier = Modifier
        .fillMaxWidth(0.85f)
        .aspectRatio(2f / 3f)
        .pointerInput(zoomState) {
            detectTapGestures(
                onDoubleTap = { tapOffset ->
                    // 双击切换放大/还原
                    if (zoomState.scale > 1f) {
                        // 已放大 → 还原
                        scope.launch { zoomState.changeScale(1f, Offset.Zero) }
                    } else {
                        // 未放大 → 放大到 2.5x,以双击位置为中心
                        scope.launch { zoomState.changeScale(2.5f, tapOffset) }
                    }
                }
            )
        }
        .zoomable(zoomState)
        .clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = {}
        )
)
```

注意:
- `pointerInput` 必须在 `zoomable` 之前,确保双击手势先被检测(否则 `zoomable` 会消费掉所有手势)
- `detectTapGestures` 的 `onDoubleTap` 与 `zoomable` 的双指缩放不冲突,各自处理不同手势
- `scope` 已在行 65 定义(`rememberCoroutineScope()`)
- 放大倍数 2.5x 是常见的"双击放大"倍数(参考相册 App)

- [ ] **步骤 2:确认 DoubanItemDetailScreen 复用 DetailPosterOverlay 后自动获得双击放大**

任务 10 步骤 3 已把 `DoubanPosterOverlay` 替换为 `PosterFullscreenOverlay`,所以豆瓣失败项详情页的海报大图自动获得双击放大能力。无需额外改动。

- [ ] **步骤 3:构建 debug 包验证**

运行:`.\gradlew assembleDebug`
预期:BUILD SUCCESSFUL

- [ ] **步骤 4:Commit**

```bash
git add app/src/main/java/com/tracktosearch/ui/screen/detail/DetailPosterOverlay.kt
git commit -m "feat: 海报大图查看支持双击放大/还原"
```

---

## 自检

### 规格覆盖度

**规格1(UI 一致性 + 筛选)**:
- ✅ 模块 A(Watchlist 筛选):任务 11
- ✅ 模块 B(豆瓣失败项查看页):任务 9
- ✅ 模块 C(豆瓣失败项详情页):任务 10
- ✅ 模块 D(设置页布局):任务 7
- ✅ 模块 E(搜索框统一清空):任务 1
- ✅ 模块 F(导入完善):任务 8
- ✅ 模块 G(缓存与边距):任务 2 + 任务 3

**规格2(沉浸式底色)**:
- ✅ 依赖与缓存:任务 4
- ✅ 正常详情页:任务 5
- ✅ 豆瓣详情页:任务 6

### 占位符扫描

无 TODO/待定。任务 8 步骤 3 中"末尾刷新 WatchlistWatchedIds"标记了"若无该方法则在 traktRepository.addToWatchlist 内部已触发",需在实施时确认。

### 类型一致性

- `FilterState` 在任务 9 和任务 11 中都有,但字段不同(豆瓣只有 reasons + markedTime,Watchlist 有 genres + year + markedTime + rating)— 两处独立定义,不共享
- `MarkedTimePreset` / `SortOrder` 枚举在任务 9 和任务 11 都用,可提取到公共文件或各自定义(推荐各自定义,避免跨模块耦合)
- `ImportResult` 在任务 8 中定义,`DataExportImport.ParseResult` 也在任务 8 中定义

### 模糊性检查

- 任务 9 步骤 4 的 markedTimePreset 过滤逻辑需要明确:用 `markedAt` 字段(ISO 字符串)解析为时间戳再比较
- 任务 11 步骤 2 的 markedTimePreset 过滤逻辑同上
- 两处都需 `parseIsoTime(markedAt)` 辅助函数,可在各自 ViewModel 内部实现

计划已完成并保存到 `docs/superpowers/plans/2026-07-06-ui-consistency-filter-immersive.md`。
