# UI 一致性打磨 + 观看统计短评词云 实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 统一发现页/设置页/搜索页标题字重与样式，打磨 Watchlist 切换条与设置/统计卡片边框阴影，并新增基于本地缓存用户短评的观看统计词云图。

**架构：** 纯前端样式调整（1-4、6 节）直接改 Compose 组件；第 5 节新增 Room 本地缓存 `UserReviewEntity` 承载评分+短评，详情页优先读缓存、提交时写缓存，统计页从本地库取短评经 jieba 分词生成词云。

**技术栈：** Kotlin + Jetpack Compose + Material3 + Room + Hilt；中文分词库 jieba-analysis（纯 Java）。

---

## 文件结构

**样式（第 1-4、6 节）**
- 修改：`app/src/main/java/com/tracktosearch/ui/screen/search/SearchScreen.kt`（不改样式，仅改文案字符串）
- 修改：`app/src/main/java/com/tracktosearch/ui/screen/discover/DiscoverScreen.kt:652`（标题字重）
- 修改：`app/src/main/java/com/tracktosearch/ui/screen/settings/SettingsScreen.kt:885`（标题字重）、通知组标题
- 修改：`app/src/main/java/com/tracktosearch/ui/screen/settings/SettingsComponents.kt`（卡片边框阴影、通知标题字重）
- 修改：`app/src/main/java/com/tracktosearch/ui/screen/watchlist/WatchlistScreen.kt:636-794`（胶囊圆角/右对齐/填充）
- 修改：`app/src/main/java/com/tracktosearch/ui/component/GlassSearchBar.kt`（搜索框填充加深）
- 修改：`app/src/main/java/com/tracktosearch/ui/screen/statistics/StatisticsScreen.kt`（SectionCard 边框阴影 + 词云卡片）
- 修改：四语言 `res/values*/strings.xml`（`search_title`、`statistics_wordcloud` 等）

**词云与缓存（第 5 节）**
- 修改：`app/src/main/java/com/tracktosearch/data/local/db/AppDatabase.kt`（注册实体）
- 创建：`app/src/main/java/com/tracktosearch/data/local/db/UserReviewEntity.kt`
- 创建：`app/src/main/java/com/tracktosearch/data/local/db/UserReviewDao.kt`
- 创建/修改：`app/src/main/java/com/tracktosearch/data/repository/UserReviewRepository.kt`（封装 DAO + Hilt）
- 修改：`app/src/main/java/com/tracktosearch/ui/screen/detail/DetailViewModel.kt`（读缓存优先、写缓存）
- 修改：`app/src/main/java/com/tracktosearch/ui/screen/statistics/StatisticsViewModel.kt`（读本地短评）
- 创建：`app/src/main/java/com/tracktosearch/ui/screen/statistics/WordCloud.kt`（Canvas 词云组件）
- 创建：`app/src/main/java/com/tracktosearch/ui/screen/statistics/ReviewTokenizer.kt`（jieba 分词 + 停用词 + 词频）
- 修改：`gradle/libs.versions.toml` + `app/build.gradle`（jieba 依赖）
- 测试：`app/src/test/.../ReviewTokenizerTest.kt`、`app/src/test/.../UserReviewDaoTest.kt`

---

## 任务 1：搜索页顶部标题文案「搜索资源」→「搜索」

- [ ] **步骤 1：修改四语言字符串**

`app/src/main/res/values-zh/strings.xml`（第 19 行附近）：
```xml
<string name="search_title">搜索</string>
```
`app/src/main/res/values/strings.xml`：
```xml
<string name="search_title">Search</string>
```
`app/src/main/res/values-ja/strings.xml`：
```xml
<string name="search_title">検索</string>
```
`app/src/main/res/values-ko/strings.xml`：
```xml
<string name="search_title">검색</string>
```

- [ ] **步骤 2：构建验证**
运行：`./gradlew :app:assembleDebug`
预期：编译通过，无字符串缺失。

- [ ] **步骤 3：Commit**
```bash
git add app/src/main/res/values-zh/strings.xml app/src/main/res/values/strings.xml app/src/main/res/values-ja/strings.xml app/src/main/res/values-ko/strings.xml
git commit -m "style: 搜索页顶部标题改为「搜索」"
```

---

## 任务 2：发现页 / 设置页标题字重对齐搜索页

- [ ] **步骤 1：发现页标题栏标题**
`app/src/main/java/com/tracktosearch/ui/screen/discover/DiscoverScreen.kt:652-655`：
```kotlin
Text(
    text = stringResource(R.string.discover_trending),
    style = MaterialTheme.typography.headlineSmall,
    fontWeight = FontWeight.Bold,
    // 其余参数保持不变
)
```

- [ ] **步骤 2：设置页标题**
`app/src/main/java/com/tracktosearch/ui/screen/settings/SettingsScreen.kt:885-890`：
```kotlin
Text(
    text = stringResource(R.string.settings_title),
    style = MaterialTheme.typography.headlineSmall,
    fontWeight = FontWeight.Bold,
    color = MaterialTheme.colorScheme.onSurface
)
```

- [ ] **步骤 3：构建验证**
运行：`./gradlew :app:assembleDebug`
预期：编译通过。

- [ ] **步骤 4：Commit**
```bash
git add app/src/main/java/com/tracktosearch/ui/screen/discover/DiscoverScreen.kt app/src/main/java/com/tracktosearch/ui/screen/settings/SettingsScreen.kt
git commit -m "style: 发现页/设置页标题字重对齐搜索页"
```

---

## 任务 3：Watchlist 想看/已看切换条（圆角/右对齐/填充）

`app/src/main/java/com/tracktosearch/ui/screen/watchlist/WatchlistScreen.kt`

- [ ] **步骤 1：胶囊圆角 28→14**
`WatchlistScreen.kt:736` 与 `744`（胶囊外框与指示块）：
```kotlin
.clip(RoundedCornerShape(14.dp))
```
两处 `RoundedCornerShape(28.dp)` 均改为 `14.dp`。

- [ ] **步骤 2：未选中胶囊填充加深**
`WatchlistScreen.kt:737` 背景：
```kotlin
.background(MaterialTheme.colorScheme.surfaceVariant)   // 原为 surfaceVariant.copy(alpha = 0.3f)
```
（若白底下仍偏浅，可改为 `surfaceVariant.copy(alpha = 0.9f)`，以实机效果为准。）

- [ ] **步骤 3：切换条右对齐**
将顶部 Row（`WatchlistScreen.kt:638` 起）改为：搜索框 `Modifier.weight(1f)` 占满，胶囊与筛选按钮靠右。
删除/简化 `searchBoxWidth` 的胶囊预留计算（`WatchlistScreen.kt:671` 附近 `val searchBoxWidth = ...`），
`GlassSearchBar` 的 `modifier` 由 `Modifier.width(searchBoxWidth)` 改为 `Modifier.weight(1f)`（在 Row 内）。
Row 保持 `horizontalArrangement = Arrangement.spacedBy(8.dp)`，顺序：搜索框、胶囊、筛选按钮；胶囊与筛选按钮在右侧自然靠右。

- [ ] **步骤 4：搜索框填充加深**
`app/src/main/java/com/tracktosearch/ui/component/GlassSearchBar.kt` 背景（当前 `surfaceVariant.copy(alpha = 0.3f)`）：
```kotlin
.background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
```
（以白底实机区分度为准，可调试 0.5~0.9。）

- [ ] **步骤 5：构建 + 实机验证**
运行：`./gradlew :app:assembleDebug` 并安装到设备/模拟器。
预期：胶囊圆角变小、整体靠右；白主题下未选中胶囊与搜索框清晰可辨。

- [ ] **步骤 6：Commit**
```bash
git add app/src/main/java/com/tracktosearch/ui/screen/watchlist/WatchlistScreen.kt app/src/main/java/com/tracktosearch/ui/component/GlassSearchBar.kt
git commit -m "style: watchlist 切换条圆角缩小、右对齐、填充加深"
```

---

## 任务 4：设置页卡片边框 + 阴影

`app/src/main/java/com/tracktosearch/ui/screen/settings/SettingsComponents.kt`

- [ ] **步骤 1：为 SettingsGroupCard / SettingsCard 加边框与阴影**
定位 `SettingsGroupCard`、`SettingsCard` 的 `Card`/`Surface` 定义，改为：
```kotlin
Card(
    modifier = modifier.fillMaxWidth(),
    shape = RoundedCornerShape(16.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
) { ... }
```
（具体参数以现有组件签名为准，保持 `containerColor` 原有值。）

- [ ] **步骤 2：构建验证**
运行：`./gradlew :app:assembleDebug`
预期：编译通过，设置页卡片出现细边框与轻投影。

- [ ] **步骤 3：Commit**
```bash
git commit -m "style: 设置页卡片加边框与阴影"
```

---

## 任务 5：通知类卡片标题字重 = 数据管理类

- [ ] **步骤 1：核查两组的标题字重**
在 `SettingsComponents.kt` 与 `SettingsScreen.kt` 中找到 `NotificationItem`（通知组，约 `SettingsScreen.kt:591`）与数据管理组条目（`DataFlowGridItem` 等）的标题 `Text` 样式，记录当前 `fontWeight`/`typography`。

- [ ] **步骤 2：统一为数据管理组字重**
将通知类标题改为与数据管理类一致（预期 `titleMedium` + `FontWeight.Bold`）。若数据管理组标题也来自通用 `SettingsRowItem`/标题组件，则直接复用同一组件；否则在 `NotificationItem` 标题 `Text` 显式设置：
```kotlin
Text(
    text = ...,
    style = MaterialTheme.typography.titleMedium,
    fontWeight = FontWeight.Bold
)
```

- [ ] **步骤 3：构建 + 实机验证**
运行：`./gradlew :app:assembleDebug`
预期：设置页「通知提醒」组与「数据管理」组卡片主标题字重一致。

- [ ] **步骤 4：Commit**
```bash
git commit -m "style: 通知类卡片标题字重对齐数据管理类"
```

---

## 任务 6：新增 Room 本地缓存 UserReviewEntity

- [ ] **步骤 1：创建实体**
`app/src/main/java/com/tracktosearch/data/local/db/UserReviewEntity.kt`：
```kotlin
package com.tracktosearch.data.local.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "user_reviews")
data class UserReviewEntity(
    @PrimaryKey val id: String,            // "$mediaType:$traktId"
    val traktId: Int,
    val mediaType: String,                 // "movie" | "show"
    val rating: Int?,
    val comment: String?,
    val updatedAt: Long = System.currentTimeMillis()
)
```

- [ ] **步骤 2：创建 DAO**
`app/src/main/java/com/tracktosearch/data/local/db/UserReviewDao.kt`：
```kotlin
package com.tracktosearch.data.local.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface UserReviewDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: UserReviewEntity)

    @Query("SELECT * FROM user_reviews WHERE traktId = :traktId AND mediaType = :mediaType LIMIT 1")
    suspend fun get(traktId: Int, mediaType: String): UserReviewEntity?

    @Query("SELECT * FROM user_reviews")
    suspend fun getAll(): List<UserReviewEntity>

    @Query("DELETE FROM user_reviews WHERE traktId = :traktId AND mediaType = :mediaType")
    suspend fun delete(traktId: Int, mediaType: String)
}
```

- [ ] **步骤 3：注册到 AppDatabase**
`app/src/main/java/com/tracktosearch/data/local/db/AppDatabase.kt` 的 `@Database(entities = [ ..., UserReviewEntity::class ])` 追加 `UserReviewEntity::class`，并在 `abstract fun userReviewDao(): UserReviewDao`。

- [ ] **步骤 4：Repository 封装**
`app/src/main/java/com/tracktosearch/data/repository/UserReviewRepository.kt`：
```kotlin
package com.tracktosearch.data.repository

import com.tracktosearch.data.local.db.UserReviewDao
import com.tracktosearch.data.local.db.UserReviewEntity
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UserReviewRepository @Inject constructor(private val dao: UserReviewDao) {
    suspend fun upsert(traktId: Int, mediaType: String, rating: Int?, comment: String?) {
        dao.upsert(UserReviewEntity("$mediaType:$traktId", traktId, mediaType, rating, comment))
    }
    suspend fun get(traktId: Int, mediaType: String) = dao.get(traktId, mediaType)
    suspend fun allComments(): List<String> = dao.getAll().mapNotNull { it.comment?.takeIf { c -> c.isNotBlank() } }
}
```
在应用 Di 模块（Hilt `@Provides`/`@Binds` 或现有 DatabaseModule）暴露 `UserReviewRepository`。

- [ ] **步骤 5：DAO 单元测试**
`app/src/test/java/com/tracktosearch/data/local/db/UserReviewDaoTest.kt`（使用现有 Room 测试模板，如 in-memory DB + runTest）：
```kotlin
@Test
fun upsertThenGet_returnsSame() = runTest {
    dao.upsert(UserReviewEntity("movie:1", 1, "movie", 8, "很好看"))
    val got = dao.get(1, "movie")
    assertEquals(8, got?.rating)
    assertEquals("很好看", got?.comment)
}
@Test
fun allComments_filtersBlank() = runTest {
    dao.upsert(UserReviewEntity("movie:1", 1, "movie", 8, "好看"))
    dao.upsert(UserReviewEntity("movie:2", 2, "movie", 9, ""))
    assertEquals(listOf("好看"), dao.getAll().mapNotNull { it.comment?.takeIf { c -> c.isNotBlank() } })
}
```

- [ ] **步骤 6：运行 DAO 测试**
运行：`./gradlew :app:testDebugUnitTest --tests "*UserReviewDaoTest"`
预期：PASS。

- [ ] **步骤 7：Commit**
```bash
git add app/src/main/java/com/tracktosearch/data/local/db/UserReviewEntity.kt app/src/main/java/com/tracktosearch/data/local/db/UserReviewDao.kt app/src/main/java/com/tracktosearch/data/local/db/AppDatabase.kt app/src/main/java/com/tracktosearch/data/repository/UserReviewRepository.kt app/src/test/java/com/tracktosearch/data/local/db/UserReviewDaoTest.kt
git commit -m "feat: 新增用户评分短评本地缓存 Room 实体与 DAO"
```

---

## 任务 7：详情页优先读缓存、提交写缓存

`app/src/main/java/com/tracktosearch/ui/screen/detail/DetailViewModel.kt`

- [ ] **步骤 1：注入 UserReviewRepository**
在 `DetailViewModel` 构造函数注入 `private val userReviewRepository: UserReviewRepository`（沿用现有 `@HiltViewModel` 注入方式）。

- [ ] **步骤 2：提交时写缓存**
在 `setRatingWithComment(...)`（`DetailViewModel.kt:1011`）同步/豆瓣成功后：
```kotlin
viewModelScope.launch {
    userReviewRepository.upsert(currentTraktId, currentMediaType.name.lowercase(), rating, comment.ifBlank { null })
}
```
同样在 `setRating(...)`（`DetailViewModel.kt:970`）更新 rating 成功后 upsert（comment 保留现有 `userComment`）。

- [ ] **步骤 3：进入详情优先读缓存**
在 `fetchUserRating()`（`DetailViewModel.kt:952`）中，先读本地：
```kotlin
val cached = userReviewRepository.get(currentTraktId, currentMediaType.name.lowercase())
if (cached != null) {
    _uiState.value = _uiState.value.copy(userRating = cached.rating, userComment = cached.comment)
} else {
    // 原有 Trakt 拉取逻辑，成功后 upsert 回本地
}
```
确保 `fetchUserRating` 为 `suspend` 或包裹在 `viewModelScope.launch` 内（以现有结构为准）。

- [ ] **步骤 4：构建验证**
运行：`./gradlew :app:assembleDebug`
预期：编译通过；进入已写过短评的详情页不再触发评分/短评网络请求（可借网络日志观察）。

- [ ] **步骤 5：Commit**
```bash
git add app/src/main/java/com/tracktosearch/ui/screen/detail/DetailViewModel.kt
git commit -m "feat: 详情页评分短评优先读本地缓存、提交写缓存"
```

---

## 任务 8：引入 jieba 分词库 + ReviewTokenizer

- [ ] **步骤 1：添加依赖**
`gradle/libs.versions.toml` 的 `[versions]` 增加 `jieba-analysis = "1.0.2"`；`[libraries]` 增加：
```toml
jieba-analysis = { module = "com.huaban:jieba-analysis", version.ref = "jieba-analysis" }
```
`app/build.gradle`（或 `build.gradle.kts`）dependencies 增加：
```gradle
implementation(libs.jieba-analysis)
```
（若 1.0.2 无法解析，改为同作者的可用版本或等价的纯 Java 中文分词库，并更新 libs.versions.toml。）

- [ ] **步骤 2：停用词表**
`app/src/main/java/com/tracktosearch/ui/screen/statistics/ReviewTokenizer.kt` 内置最小中文/英文停用词集合（的、了、是、我、你、和、也、都、the、a、an、is、to、and 等）。

- [ ] **步骤 3：分词 + 词频**
`ReviewTokenizer.kt`：
```kotlin
package com.tracktosearch.ui.screen.statistics

import com.huaban.analysis.jieba.JiebaSegmenter

object ReviewTokenizer {
    private val segmenter = JiebaSegmenter()
    private val stopwords = setOf("的","了","是","我","你","他","和","也","都","就","很","个","这","那","the","a","an","is","to","and","of","in","on","i","you","it","this","that","with","for")

    fun topWords(comments: List<String>, limit: Int = 40): List<Pair<String, Int>> {
        val freq = mutableMapOf<String, Int>()
        for (c in comments) {
            segmenter.process(c, JiebaSegmenter.SegMode.SEARCH)
                .map { it.word.trim() }
                .filter { it.length >= 2 && it !in stopwords && !it.all { ch -> ch.isPunctuation() } }
                .forEach { w -> freq[w] = freq.getOrDefault(w, 0) + 1 }
        }
        return freq.entries.sortedByDescending { it.value }.take(limit).map { it.key to it.value }
    }
}
```

- [ ] **步骤 4：分词单元测试**
`app/src/test/java/com/tracktosearch/ui/screen/statistics/ReviewTokenizerTest.kt`：
```kotlin
@Test
fun segmentsChineseAndFiltersStopwords() {
    val result = ReviewTokenizer.topWords(listOf("这部电影真的很好看，演员演得也很好"), limit = 10)
    assertTrue(result.any { it.first == "好看" })
    assertTrue(result.none { it.first == "的" })
}
@Test
fun returnsEmptyForNoComments() {
    assertEquals(emptyList<Pair<String, Int>>(), ReviewTokenizer.topWords(emptyList()))
}
```

- [ ] **步骤 5：运行分词测试**
运行：`./gradlew :app:testDebugUnitTest --tests "*ReviewTokenizerTest"`
预期：PASS。

- [ ] **步骤 6：Commit**
```bash
git add gradle/libs.versions.toml app/build.gradle app/src/main/java/com/tracktosearch/ui/screen/statistics/ReviewTokenizer.kt app/src/test/java/com/tracktosearch/ui/screen/statistics/ReviewTokenizerTest.kt
git commit -m "feat: 引入 jieba 分词并新增 ReviewTokenizer"
```

---

## 任务 9：WordCloud 组件

`app/src/main/java/com/tracktosearch/ui/screen/statistics/WordCloud.kt`

- [ ] **步骤 1：Canvas 词云**
提供 `WordCloud(words: List<Pair<String, Int>>)` Composable：用 `Canvas`/`Layout` 按词频降序，字号从 `maxSp` 到 `minSp` 映射；颜色从主题 `primary`/`secondary`/`tertiary` 轮换；采用简单螺旋/网格排布避免重叠（以 `rememberTextMeasurer` 测量宽度）。
```kotlin
@Composable
fun WordCloud(words: List<Pair<String, Int>>, modifier: Modifier = Modifier) {
    if (words.isEmpty()) return
    val measurer = rememberTextMeasurer()
    val palette = listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.secondary, MaterialTheme.colorScheme.tertiary)
    val maxFreq = words.maxOf { it.second }.toFloat().coerceAtLeast(1f)
    Canvas(modifier = modifier.fillMaxWidth().height(220.dp)) {
        var x = 16.dp.toPx(); var y = 24.dp.toPx(); var rowH = 0f
        words.forEachIndexed { i, (w, f) ->
            val size = (14 + 22 * (f / maxFreq)).sp.toPx()
            val m = measurer.measure(w, TextStyle(fontSize = size.sp, fontWeight = FontWeight.Bold))
            if (x + m.size.width > size.width - 16.dp.toPx()) { x = 16.dp.toPx(); y += rowH + 10.dp.toPx(); rowH = 0f }
            drawText(m, topLeft = Offset(x, y), color = palette[i % palette.size])
            x += m.size.width + 12.dp.toPx(); rowH = maxOf(rowH, m.size.height)
        }
    }
}
```
（排布为简化行式，足够词云可读性；如需螺旋防重叠可在后续优化。）

- [ ] **步骤 2：构建验证**
运行：`./gradlew :app:assembleDebug`
预期：编译通过。

- [ ] **步骤 3：Commit**
```bash
git add app/src/main/java/com/tracktosearch/ui/screen/statistics/WordCloud.kt
git commit -m "feat: 新增 WordCloud 词云绘制组件"
```

---

## 任务 10：统计页接入词云 + 卡片边框阴影

`app/src/main/java/com/tracktosearch/ui/screen/statistics/StatisticsViewModel.kt` 与 `StatisticsScreen.kt`

- [ ] **步骤 1：ViewModel 暴露短评词频**
`StatisticsViewModel` 注入 `UserReviewRepository`，在加载完成后：
```kotlin
val comments = userReviewRepository.allComments()
val wordCloud = ReviewTokenizer.topWords(comments)
_uiState.value = _uiState.value.copy(reviewWords = wordCloud)
```
在 `UiState` 增加 `val reviewWords: List<Pair<String, Int>> = emptyList()`。
（与现有 `ratingsDeferred` 并行或在其后补充，降级为空列表时不报错。）

- [ ] **步骤 2：统计页新增词云卡片**
`StatisticsScreen.kt` 在评分统计 `SectionCard`（`statistics_ratings`）附近新增卡片：
```kotlin
item(key = "wordcloud") {
    SectionCard(title = stringResource(R.string.statistics_wordcloud)) {
        if (uiState.reviewWords.isEmpty()) {
            Text(stringResource(R.string.statistics_wordcloud_empty), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            WordCloud(words = uiState.reviewWords, modifier = Modifier.fillMaxWidth())
        }
    }
}
```
`SectionCard` 自身加边框+阴影（见步骤 3）。

- [ ] **步骤 3：统计各卡片边框 + 阴影**
定位 `StatisticsScreen.kt` 的 `SectionCard` 定义，按任务 4 同样方式加 `outlineVariant` 边框 + `cardElevation(defaultElevation = 1.dp)`。

- [ ] **步骤 4：新增字符串（四语言）**
`values-zh`：`statistics_wordcloud` = 「短评词云」；`statistics_wordcloud_empty` = 「还没有写短评」。
`values`：`statistics_wordcloud` = "Short Review Word Cloud"；`statistics_wordcloud_empty` = "No short reviews yet"。
`values-ja` / `values-ko` 相应翻译。

- [ ] **步骤 5：构建 + 实机验证**
运行：`./gradlew :app:assembleDebug`
预期：编译通过；写过短评的用户在统计页看到词云，无短评显示空态；各卡片有边框阴影。

- [ ] **步骤 6：Commit**
```bash
git add app/src/main/java/com/tracktosearch/ui/screen/statistics/StatisticsViewModel.kt app/src/main/java/com/tracktosearch/ui/screen/statistics/StatisticsScreen.kt app/src/main/res/values-zh/strings.xml app/src/main/res/values/strings.xml app/src/main/res/values-ja/strings.xml app/src/main/res/values-ko/strings.xml
git commit -m "feat: 统计页接入短评词云并统一卡片边框阴影"
```

---

## 自检记录

- **规格覆盖度**：任务 1→§1 文案；任务 2→§1 字重；任务 3→§2；任务 4→§3；任务 5→§4；任务 6/7/8/9/10→§5；任务 4/10→§3&§6。全覆盖。
- **占位符扫描**：无「待定/TODO/补充细节」。jieba 版本标注了回退方案。
- **类型一致性**：`UserReviewEntity(id,traktId,mediaType,rating,comment,updatedAt)` 与 DAO/Repository 签名一致；`ReviewTokenizer.topWords` 返回 `List<Pair<String,Int>>` 与 `WordCloud(words)`、`UiState.reviewWords` 一致；`UserReviewRepository.allComments()` 返回 `List<String>` 与 ViewModel 调用一致。
