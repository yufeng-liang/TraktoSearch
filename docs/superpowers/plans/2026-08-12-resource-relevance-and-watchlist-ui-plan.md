# 资源高相关与详情/Watchlist UI 优化实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 `subagent-driven-development`（推荐）或 `executing-plans` 逐任务实现此计划。每个任务按复选框跟踪，完成一个逻辑改动并通过针对性验证后立即提交。

**目标：** 修复详情页高相关模式误隐藏真实视频资源的问题，并完成详情评分、底部导航和 Watchlist Tab 的已确认视觉交互优化。

**架构：** 资源评分器继续负责排序分数，但新增结构化的高相关资格结果，将标题身份匹配与音频/书籍/剧集/年份冲突分开。Repository 为每个资源保存分数和高相关资格，两个详情 ViewModel 从全量列表本地过滤。UI 采用现有 Compose/Haze/拟态组件，Watchlist 模式切换新建页面专用组件，不改全局分段器。

**技术栈：** Kotlin、Jetpack Compose、Material 3、Hilt/MVVM、现有 `ResourceRepository`、JUnit/Truth、Compose UI Test、Gradle Debug。

---

## 文件清单与职责

### 资源相关度与过滤

- 修改：`app/src/main/java/com/tracktosearch/data/repository/ResourceRelevance.kt`
  新增发布名规范化、结构化相关度结果和高相关资格判定；保留 `score()` 兼容调用。
- 修改：`app/src/main/java/com/tracktosearch/data/repository/ResourceRepository.kt`
  在 `RankedResources` 中携带每个 URL 的高相关资格，排序仍使用分数。
- 修改：`app/src/main/java/com/tracktosearch/ui/screen/detail/DetailViewModel.kt`
  使用高相关资格 map 过滤普通详情页资源。
- 修改：`app/src/main/java/com/tracktosearch/ui/screen/douban/DoubanItemDetailScreen.kt`
  使用同一高相关资格 map 过滤豆瓣失败项详情页资源。
- 修改：`app/src/test/java/com/tracktosearch/data/repository/RuleBasedRelevanceScorerTest.kt`
  覆盖截图样本、发布名元数据、误杀和冲突规则。
- 修改：`app/src/test/java/com/tracktosearch/data/repository/ResourceRepositoryTest.kt`
  覆盖 `highRelevanceMap` 与排序/无 query 行为。
- 修改：`app/src/test/java/com/tracktosearch/ui/screen/detail/DetailViewModelSupplementTest.kt`
  覆盖过滤开关读取全量结果和高相关资格。

### 详情评分与底部导航

- 修改：`app/src/main/java/com/tracktosearch/ui/component/RatingBadge.kt`
  放大海报右上角共享评分徽章并增强对比描边/阴影。
- 修改：`app/src/main/java/com/tracktosearch/ui/screen/detail/DetailRatingsDialog.kt`
  适度放大详情头部平台图标、品牌文字和评分数字，保持固定槽位与高度。
- 修改：`app/src/main/java/com/tracktosearch/ui/screen/main/MainScreen.kt`
  放大底部导航文字，保留图标、触控区与选中动画。
- 修改：`app/src/test/java/com/tracktosearch/ui/component/RatingBadgeTest.kt`
  保留评分格式回归，并增加语义/布局节点检查。
- 修改：`app/src/test/java/com/tracktosearch/ui/screen/detail/DetailRatingsDialogTest.kt`
  锁定评分卡固定高度、平台槽位和评分文案。

### Watchlist Tab

- 创建：`app/src/main/java/com/tracktosearch/ui/screen/watchlist/WatchlistModeSelector.kt`
  Watchlist 专用“想看/已看”内容驱动宽度切换组件。
- 修改：`app/src/main/java/com/tracktosearch/ui/screen/watchlist/WatchlistCategoryTabs.kt`
  缩小分类 Tab 字号与垂直尺寸，保留选中缩放和无障碍语义。
- 修改：`app/src/main/java/com/tracktosearch/ui/screen/watchlist/WatchlistScreen.kt`
  使用专用模式切换组件，缩小分类 Tab 外部垂直 padding，不改变 selectedMode 和业务回调。
- 修改：`app/src/androidTest/java/com/tracktosearch/ui/screen/watchlist/WatchlistScreenTest.kt`
  覆盖模式切换、分类 Tab 语义和新增 testTag。

---

## 任务 1：建立结构化高相关结果的失败测试

**文件：**

- 修改：`app/src/test/java/com/tracktosearch/data/repository/RuleBasedRelevanceScorerTest.kt`
- 修改：`app/src/test/java/com/tracktosearch/data/repository/ResourceRepositoryTest.kt`

- [ ] **步骤 1：扩展评分器测试数据和断言，先锁定截图行为**

在 `RuleBasedRelevanceScorerTest` 中新增目标影视和测试辅助方法。测试调用新 API `evaluate(item, query)`，而保留现有 `score()` 测试作为排序分数回归。

```kotlin
private val querySpiderMan = ResourceQuery(
    title = "蜘蛛侠：崭新之日",
    year = 2026,
    country = "美国",
    mediaType = MediaType.MOVIE
)

@Test
fun spiderMan_latestVideo_isHighRelevanceWithoutQualityToken() {
    val result = scorer.evaluate(item("蜘蛛侠：崭新之日 最新"), querySpiderMan)

    assertThat(result.titleMatch).isEqualTo(TitleMatch.DELIMITED)
    assertThat(result.contentType).isEqualTo(ResourceContentType.UNKNOWN)
    assertThat(result.isHighRelevance).isTrue()
}

@Test
fun spiderMan_tcVideo_isHighRelevance() {
    val result = scorer.evaluate(item("蜘蛛侠：崭新之日（TC画质增强版）"), querySpiderMan)

    assertThat(result.isHighRelevance).isTrue()
    assertThat(result.hasExplicitConflict).isFalse()
}

@Test
fun spiderMan_flacPromotionSong_isNotHighRelevance() {
    val result = scorer.evaluate(
        item("蜘蛛侠：崭新之日(2026) 电影中文推广曲 胡彦斌 破晓以后 FLAC 24bit 48khz"),
        querySpiderMan
    )

    assertThat(result.titleMatch.isStrong).isTrue()
    assertThat(result.contentType).isEqualTo(ResourceContentType.AUDIO)
    assertThat(result.isHighRelevance).isFalse()
}

@Test
fun spiderMan_wrongYear_isNotHighRelevance() {
    val result = scorer.evaluate(item("蜘蛛侠：崭新之日 2012 1080p"), querySpiderMan)

    assertThat(result.hasYearConflict).isTrue()
    assertThat(result.isHighRelevance).isFalse()
}

@Test
fun electronicLoveLetter_remainsWeakMatch() {
    val result = scorer.evaluate(item("电子情书 1080p"), queryLoveLetter)

    assertThat(result.titleMatch).isEqualTo(TitleMatch.NONE)
    assertThat(result.isHighRelevance).isFalse()
}
```

在已有 `ostAlbum_stillDetectedAsNonMovie`、`flacAlbum_stillDetectedAsNonMovie` 测试中，把“必须低于 45 分”的资格断言改为同时断言 `evaluate(...).isHighRelevance == false`；分数仍可低于阈值用于排序，但高相关资格不再只依赖分数。

- [ ] **步骤 2：运行新增测试，确认因 API/结果类型尚未实现而失败**

运行：

```bash
./gradlew.bat :app:testDebugUnitTest --tests "com.tracktosearch.data.repository.RuleBasedRelevanceScorerTest" --no-daemon
```

预期：`FAIL`，编译阶段提示 `evaluate`、`TitleMatch` 或 `ResourceContentType` 尚不存在。此失败用于确认测试先于实现进入代码库。

- [ ] **步骤 3：补充 Repository 结果结构的失败断言**

在 `ResourceRepositoryTest` 的 `mergeAndCacheResources_有query时返回scoreMap` 中追加：

```kotlin
assertThat(ranked.highRelevanceMap).containsKey("https://a.com")
assertThat(ranked.highRelevanceMap["https://a.com"]).isTrue()
assertThat(ranked.highRelevanceMap["https://b.com"]).isFalse()
```

在无 query 测试中追加：

```kotlin
assertThat(ranked.highRelevanceMap).isEmpty()
```

- [ ] **步骤 4：Commit 测试基线**

```bash
git add app/src/test/java/com/tracktosearch/data/repository/RuleBasedRelevanceScorerTest.kt app/src/test/java/com/tracktosearch/data/repository/ResourceRepositoryTest.kt
git diff --cached --check
git diff --cached --name-only
git commit -m "test(资源相关度): 覆盖视频误杀与内容冲突"
```

---

## 任务 2：实现发布名解析与高相关资格判定

**文件：**

- 修改：`app/src/main/java/com/tracktosearch/data/repository/ResourceRelevance.kt`

- [ ] **步骤 1：添加结构化结果类型和接口默认分数方法**

在 `ResourceQuery` 后、评分器接口前加入以下类型；名称必须与任务 1 测试一致：

```kotlin
enum class TitleMatch {
    NONE,
    PARTIAL,
    DELIMITED,
    EXACT;

    val isStrong: Boolean
        get() = this == DELIMITED || this == EXACT
}

enum class ResourceContentType {
    UNKNOWN,
    VIDEO,
    AUDIO,
    BOOK
}

data class ResourceRelevance(
    val score: Int,
    val titleMatch: TitleMatch,
    val contentType: ResourceContentType,
    val hasYearConflict: Boolean,
    val hasMediaTypeConflict: Boolean,
    val hasRegionConflict: Boolean
) {
    val hasExplicitConflict: Boolean
        get() = hasYearConflict || hasMediaTypeConflict || hasRegionConflict

    val isHighRelevance: Boolean
        get() = when {
            contentType == ResourceContentType.AUDIO -> false
            contentType == ResourceContentType.BOOK -> false
            titleMatch.isStrong -> !hasExplicitConflict
            else -> score >= RelevanceScorerProvider.HIGH_RELEVANCE_THRESHOLD && !hasExplicitConflict
        }
}
```

将接口改为一次评估、兼容现有分数调用：

```kotlin
interface ResourceRelevanceScorer {
    fun evaluate(item: ResourceItem, query: ResourceQuery): ResourceRelevance

    fun score(item: ResourceItem, query: ResourceQuery): Int =
        evaluate(item, query).score
}
```

删除未使用且语义过窄的 `ScoredResource`，或将其替换为 `ResourceRelevance` 的使用，不保留两个表达相同结果的类型。

- [ ] **步骤 2：新增规范化与元数据识别辅助函数**

在 `RuleBasedRelevanceScorer` 中将 `score()` 改为 `evaluate()`，先计算规范化文本和独立信号，再组合分数：

```kotlin
override fun evaluate(item: ResourceItem, query: ResourceQuery): ResourceRelevance {
    val rawName = item.name
    if (rawName.isBlank()) {
        return ResourceRelevance(
            score = 0,
            titleMatch = TitleMatch.NONE,
            contentType = ResourceContentType.UNKNOWN,
            hasYearConflict = false,
            hasMediaTypeConflict = false,
            hasRegionConflict = false
        )
    }

    val normalizedName = normalizeReleaseName(rawName)
    val titleMatch = titleMatch(normalizedName, query.title, query.originalTitle)
    val year = yearSignal(normalizedName, query.year)
    val mediaTypeConflict = hasMediaTypeConflict(normalizedName, query.mediaType)
    val regionConflict = hasRegionConflict(normalizedName, query.country)
    val contentType = detectContentType(normalizedName)

    val score = titleScore(titleMatch) + year.score +
        if (mediaTypeConflict) -20 else 0 +
        if (regionConflict) -15 else 0 +
        contentPenalty(contentType) + qualityScore(normalizedName) +
        peopleScore(normalizedName, query.directors, query.cast)

    return ResourceRelevance(
        score = score,
        titleMatch = titleMatch,
        contentType = contentType,
        hasYearConflict = year.hasConflict,
        hasMediaTypeConflict = mediaTypeConflict,
        hasRegionConflict = regionConflict
    )
}
```

实现 `normalizeReleaseName` 时使用 `java.text.Normalizer` 的 NFKC 形式，统一全半角符号、大小写和连续空白；不要删除中文标题字符。技术元数据识别采用边界正则，至少覆盖：

```kotlin
private val YEAR_REGEX = Regex("""(?:19|20)\\d{2}""")
private val AUDIO_MARKER_REGEX = Regex(
    """(?i)(?:^|[^a-z0-9])(flac|mp3|wav|ape|alac|24bit|24-bit|48khz|96khz|推广曲|主题曲|插曲|原声带|原声大碟|专辑|单曲|音频|soundtrack|ost)(?:[^a-z0-9]|$)"""
)
private val BOOK_MARKER_REGEX = Regex(
    """(?i)(?:^|[^a-z0-9])(epub|kindle|pdf|有声书|实体书|绘本)(?:[^a-z0-9]|$)"""
)
private val VIDEO_MARKER_REGEX = Regex(
    """(?i)(?:^|[^a-z0-9])(2160p|1080p|720p|4k|remux|bluray|bdrip|web-dl|hdtv|tc|cam|mkv|mp4|avi|h264|h265|hevc|高清|画质增强版|正片)(?:[^a-z0-9]|$)"""
)
```

`AUDIO` 优先于 `VIDEO`，因为音频资源可能同时带有片名和年份；`BOOK` 次之；两者都没有明确标记时返回 `UNKNOWN`。未知标记不得直接隐藏。

- [ ] **步骤 3：将标题匹配改为强/弱分层，但保留误召回保护**

保留现有 `isDelimitedSegment` 的中文边界原则：中文/英文字母相邻不是定界符，数字、空格、标点、首尾是定界符。匹配函数返回 `TitleMatch` 而不再只返回整数：

```kotlin
private fun titleMatch(name: String, title: String, originalTitle: String?): TitleMatch {
    return sequenceOf(title, originalTitle.orEmpty())
        .filter { it.isNotBlank() }
        .map { segmentMatch(name, it) }
        .maxByOrNull { it.ordinal }
        ?: TitleMatch.NONE
}

private fun segmentMatch(name: String, target: String): TitleMatch {
    val n = normalizeReleaseName(name)
    val t = normalizeTitle(target)
    if (t.isEmpty()) return TitleMatch.NONE
    if (n == t) return TitleMatch.EXACT
    if (isDelimitedSegment(n, t)) return TitleMatch.DELIMITED

    val cosine = cosineBigram(stripTechnicalMetadata(n), stripTechnicalMetadata(t))
    return if (cosine >= 0.7) TitleMatch.PARTIAL else TitleMatch.NONE
}
```

`titleScore` 继续使用原来的 `50/40/25/12/0` 分档，改为接受 `TitleMatch`；这样排序行为尽量稳定，而高相关资格由 `ResourceRelevance.isHighRelevance` 决定。

- [ ] **步骤 4：把年份、类型、地区和内容信号拆成资格字段**

保留已有地区组合词和季集正则，改为返回 Boolean 冲突；不要恢复单字“韩/日/美/港/台”匹配。年份逻辑必须区分“任意年份文本”和“影片年份边界”：目标年份命中不冲突，明确边界年份不等于目标年份才是冲突。

```kotlin
private data class YearSignal(val score: Int, val hasConflict: Boolean)

private fun yearSignal(name: String, year: Int?): YearSignal {
    if (year == null) return YearSignal(0, false)
    val years = YEAR_REGEX.findAll(name).map { it.value.toInt() }.toSet()
    if (year in years) return YearSignal(15, false)
    val movieYears = MOVIE_YEAR_REGEX.findAll(name)
        .map { it.groupValues[1].toInt() }
        .toSet()
    return YearSignal(
        score = if (movieYears.isNotEmpty()) -15 else 0,
        hasConflict = movieYears.isNotEmpty()
    )
}
```

注意：如果资源名同时出现目标年份和其它日期年份，不能因为任意日期就判冲突；只有影片年份边界中存在非目标年份且没有目标年份时才冲突。补充测试覆盖 `2025.05.11` 不应被当成影片年份。

- [ ] **步骤 5：运行评分器和 Repository 测试确认实现通过**

运行：

```bash
./gradlew.bat :app:testDebugUnitTest --tests "com.tracktosearch.data.repository.RuleBasedRelevanceScorerTest" --tests "com.tracktosearch.data.repository.ResourceRepositoryTest" --no-daemon
```

预期：`BUILD SUCCESSFUL`，评分器测试覆盖截图样本并通过；`scoreMap` 排序和 `highRelevanceMap` 断言通过。

- [ ] **步骤 6：Commit 评分器实现**

```bash
git add app/src/main/java/com/tracktosearch/data/repository/ResourceRelevance.kt
git diff --cached --check
git diff --cached --name-only
git commit -m "fix(资源相关度): 分离标题匹配与内容资格"
```

---

## 任务 3：把高相关资格接入 Repository 和两个详情页面

**文件：**

- 修改：`app/src/main/java/com/tracktosearch/data/repository/ResourceRepository.kt`
- 修改：`app/src/main/java/com/tracktosearch/ui/screen/detail/DetailViewModel.kt`
- 修改：`app/src/main/java/com/tracktosearch/ui/screen/douban/DoubanItemDetailScreen.kt`
- 修改：`app/src/test/java/com/tracktosearch/data/repository/ResourceRepositoryTest.kt`
- 修改：`app/src/test/java/com/tracktosearch/ui/screen/detail/DetailViewModelSupplementTest.kt`

- [ ] **步骤 1：扩展 `RankedResources` 和 Repository 测试**

将 `RankedResources` 从两个字段扩展为三个字段，保留原有 `scoreMap`：

```kotlin
data class RankedResources(
    val items: List<ResourceItem>,
    val scoreMap: Map<String, Int>,
    val highRelevanceMap: Map<String, Boolean>
)
```

在 `rank()` 中对每个资源只调用一次 `scorer.evaluate`：

```kotlin
val relevanceMap = if (query != null) {
    distinct.associate { it.url to RelevanceScorerProvider.get().evaluate(it, query) }
} else {
    emptyMap()
}
val scoreMap = relevanceMap.mapValues { it.value.score }
val highRelevanceMap = relevanceMap.mapValues { it.value.isHighRelevance }
val sorted = distinct.sortedWith(resourceComparator(isShow, scoreMap))
return RankedResources(sorted, scoreMap, highRelevanceMap)
```

无 query 时两个 map 都必须为空，避免通用搜索错误进入高相关过滤。

- [ ] **步骤 2：让普通详情 ViewModel 保存并使用资格 map**

在 `DetailViewModel` 当前 `currentScoreMap` 附近新增：

```kotlin
private var currentHighRelevanceMap: Map<String, Boolean> = emptyMap()
```

所有 `ranked` 赋值点同时更新 `currentHighRelevanceMap = ranked.highRelevanceMap`；缓存回退、空结果和重新初始化时清空它。

把 `applyHighRelevanceFilter` 改为：

```kotlin
private fun applyHighRelevanceFilter(
    items: List<ResourceItem>,
    onlyHigh: Boolean
): Pair<List<ResourceItem>, Int> {
    if (!onlyHigh || currentResourceQuery == null || currentHighRelevanceMap.isEmpty()) {
        return items to 0
    }
    val kept = items.filter { currentHighRelevanceMap[it.url] == true }
    return kept to (items.size - kept.size)
}
```

仍然从 `allResources` 应用源筛选、网盘类型筛选和高相关筛选，不能从当前已过滤列表再次过滤。

- [ ] **步骤 3：让豆瓣失败项详情页使用同一 map**

在 `DoubanItemDetailScreen` 的 ViewModel/状态持有位置增加对应的 `currentHighRelevanceMap`，所有 `ranked` 和缓存清空点同步赋值；把其 `applyHighRelevanceFilter` 从 `currentScoreMap[url] >= threshold` 改成 `currentHighRelevanceMap[url] == true`。

不得在该页面复制评分规则；规则唯一来源仍是 `ResourceRelevance.kt` 与 Repository 的 `rank()`。

- [ ] **步骤 4：补充 ViewModel 过滤回归测试**

在 `DetailViewModelSupplementTest` 添加一组资源，至少包含：一个强标题视频、一个强标题音频、一个弱匹配资源。通过已有私有字段测试辅助注入 `allResources/currentHighRelevanceMap/currentResourceQuery` 后调用 `toggleShowHighRelevanceOnly()`，断言：

```kotlin
viewModel.toggleShowHighRelevanceOnly()

assertThat(viewModel.uiState.value.resources.map { it.name })
    .containsExactly("蜘蛛侠：崭新之日 最新")
viewModel.toggleShowHighRelevanceOnly()
assertThat(viewModel.uiState.value.resources).hasSize(3)
```

如果当前测试夹具字段名称不同，沿用测试中已有的反射/`setUiState` 辅助，不新增第二套注入机制。同步检查 `lowRelevanceHiddenCount` 为 2，再切回全部为 0。

- [ ] **步骤 5：运行资源与详情 ViewModel 测试**

运行：

```bash
./gradlew.bat :app:testDebugUnitTest --tests "com.tracktosearch.data.repository.ResourceRepositoryTest" --tests "com.tracktosearch.ui.screen.detail.DetailViewModelSupplementTest" --no-daemon
```

预期：`BUILD SUCCESSFUL`，高相关开关可来回恢复全量资源，两个页面的过滤判定来源一致。

- [ ] **步骤 6：Commit 过滤链路**

```bash
git add app/src/main/java/com/tracktosearch/data/repository/ResourceRepository.kt app/src/main/java/com/tracktosearch/ui/screen/detail/DetailViewModel.kt app/src/main/java/com/tracktosearch/ui/screen/douban/DoubanItemDetailScreen.kt app/src/test/java/com/tracktosearch/data/repository/ResourceRepositoryTest.kt app/src/test/java/com/tracktosearch/ui/screen/detail/DetailViewModelSupplementTest.kt
git diff --cached --check
git diff --cached --name-only
git commit -m "fix(资源筛选): 复用高相关资格过滤详情资源"
```

---

## 任务 4：锁定详情评分视觉回归并实现放大

**文件：**

- 修改：`app/src/test/java/com/tracktosearch/ui/component/RatingBadgeTest.kt`
- 修改：`app/src/test/java/com/tracktosearch/ui/screen/detail/DetailRatingsDialogTest.kt`
- 修改：`app/src/main/java/com/tracktosearch/ui/component/RatingBadge.kt`
- 修改：`app/src/main/java/com/tracktosearch/ui/screen/detail/DetailRatingsDialog.kt`

- [ ] **步骤 1：为共享海报评分徽章增加稳定 testTag**

在 `RatingBadge` 根 Row 上增加稳定 tag，数值继续由现有格式化逻辑生成：

```kotlin
const val RATING_BADGE_TEST_TAG = "rating_badge"

Row(
    modifier = modifier
        .testTag(RATING_BADGE_TEST_TAG)
        .offset(y = (-2).dp)
        .padding(horizontal = 5.dp, vertical = 2.dp),
    ...
)
```

先在 `RatingBadgeTest` 增加：

```kotlin
composeRule.onNodeWithTag(RATING_BADGE_TEST_TAG).assertIsDisplayed()
```

同时保留 `8.5`、`7.0`、`0.0` 一位小数断言。

- [ ] **步骤 2：运行评分徽章测试确认 testTag 变更前失败**

运行：

```bash
./gradlew.bat :app:testDebugUnitTest --tests "com.tracktosearch.ui.component.RatingBadgeTest" --no-daemon
```

预期：在实现 tag 前编译失败或找不到节点；这一步只记录测试先行结果，不改动无关组件。

- [ ] **步骤 3：实现共享评分徽章视觉调整**

在 `RatingBadge.kt` 中使用以下目标规格：

```kotlin
private val RatingFontSize = 13.sp
private val RatingIconSize = 14.dp

horizontalArrangement = Arrangement.spacedBy(3.dp)
```

保留星标金色和数字白色；将星标底层黑色副本偏移增至约 `0.9.dp`，数字 `Shadow` 使用近黑色、高 alpha、约 `0.8f` 偏移和 `1.8f` blur，确保白色海报上的外轮廓清晰。不要增加不透明黑色 chip，避免改变海报视觉。

- [ ] **步骤 4：为详情 `RatingsRow` 增加尺寸回归并实现放大**

保持 `RATING_CARD_TEST_TAG` 和 `64.dp` 高度测试不变，只调整内部单项：

```kotlin
// 平台图标：14.dp -> 16.dp
modifier = Modifier.size(16.dp)

// 平台名：12.sp -> 13.sp
style = MaterialTheme.typography.titleSmall.copy(fontSize = 13.sp)

// 评分值：14.sp -> 15.sp
style = MaterialTheme.typography.titleSmall.copy(fontSize = 15.sp)
```

IMDb 标签可从 `11.sp` 调到 `12.sp`；RT/MTC emoji 由 `14.sp` 调到 `16.sp`。若固定槽位在测试设备上出现截断，优先缩小 badge 间距/label slot，而不是恢复原字号或改变卡片高度。

- [ ] **步骤 5：运行详情评分测试**

运行：

```bash
./gradlew.bat :app:testDebugUnitTest --tests "com.tracktosearch.ui.component.RatingBadgeTest" --tests "com.tracktosearch.ui.screen.detail.DetailRatingsDialogTest" --no-daemon
```

预期：`BUILD SUCCESSFUL`，评分格式、四平台槽位、固定卡片高度和来源语义全部通过。

- [ ] **步骤 6：Commit 评分视觉改动**

```bash
git add app/src/main/java/com/tracktosearch/ui/component/RatingBadge.kt app/src/main/java/com/tracktosearch/ui/screen/detail/DetailRatingsDialog.kt app/src/test/java/com/tracktosearch/ui/component/RatingBadgeTest.kt app/src/test/java/com/tracktosearch/ui/screen/detail/DetailRatingsDialogTest.kt
git diff --cached --check
git diff --cached --name-only
git commit -m "style(详情评分): 放大评分徽章与平台评分区"
```

---

## 任务 5：创建 Watchlist 内容驱动模式切换组件并先写测试

**文件：**

- 创建：`app/src/main/java/com/tracktosearch/ui/screen/watchlist/WatchlistModeSelector.kt`
- 修改：`app/src/androidTest/java/com/tracktosearch/ui/screen/watchlist/WatchlistScreenTest.kt`

- [ ] **步骤 1：定义专用组件接口和稳定语义**

创建组件，使用现有主题颜色和 Compose 语义，不复用全局 `CapsuleTabSelector`：

```kotlin
@Composable
internal fun WatchlistModeSelector(
    tabs: List<String>,
    selectedIndex: Int,
    onTabSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    require(tabs.size == 2) { "WatchlistModeSelector requires exactly two tabs" }
    // BoxWithConstraints + animateDpAsState 计算两个动态宽度，正文实现见任务 6。
}
```

每个选项必须提供：

```kotlin
.testTag("watchlist_mode_tab_$index")
.selectable(
    selected = selected,
    role = Role.Tab,
    onClick = { onTabSelected(index) }
)
```

文本仍直接使用传入的 `tabs`，不在组件内硬编码“想看/已看”。

- [ ] **步骤 2：扩展 Watchlist Instrumented UI 测试**

在现有默认状态测试中增加 testTag 和语义检查；在已看切换测试中记录回调和 selected 状态：

```kotlin
composeRule.onNodeWithTag("watchlist_mode_tab_0")
    .assertIsDisplayed()
    .assertIsSelected()
composeRule.onNodeWithTag("watchlist_mode_tab_1")
    .performClick()
composeRule.waitForIdle()
composeRule.onNodeWithTag("watchlist_mode_tab_1").assertIsSelected()
composeRule.onNodeWithTag("watchlist_mode_tab_0").assertIsNotSelected()
```

分类 Tab 继续使用已有 `watchlist_category_tab_0` tag，并增加 `assertIsSelected()`/`assertIsNotSelected()` 检查，锁定现有语义。

- [ ] **步骤 3：运行 Watchlist 定向测试确认新组件接入前失败**

在组件尚未接入 `WatchlistScreen` 时运行：

```bash
./gradlew.bat :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.tracktosearch.ui.screen.watchlist.WatchlistScreenTest --no-daemon
```

预期：测试因 `watchlist_mode_tab_*` 节点不存在而失败；若当前环境无在线设备，记录为环境阻塞，不把安装/设备缺失误判为代码通过。

---

## 任务 6：实现 Watchlist Tab 视觉调整并接入页面

**文件：**

- 修改：`app/src/main/java/com/tracktosearch/ui/screen/watchlist/WatchlistModeSelector.kt`
- 修改：`app/src/main/java/com/tracktosearch/ui/screen/watchlist/WatchlistCategoryTabs.kt`
- 修改：`app/src/main/java/com/tracktosearch/ui/screen/watchlist/WatchlistScreen.kt`

- [ ] **步骤 1：实现内容驱动宽度模式切换**

使用稳定的外层宽度和动画后的两个 `Dp` 宽度，不使用权重直接重测造成跳动：

```kotlin
val selected = selectedIndex == index
val selectedWidthTarget = availableWidth * (1.45f / (1.45f + 0.80f))
val unselectedWidthTarget = availableWidth * (0.80f / (1.45f + 0.80f))
val itemWidth by animateDpAsState(
    targetValue = if (selected) selectedWidthTarget else unselectedWidthTarget,
    animationSpec = tween(durationMillis = 240),
    label = "watchlist_mode_width_$index"
)
val textSize by animateFloatAsState(
    targetValue = if (selected) 18f else 14f,
    animationSpec = tween(durationMillis = 240),
    label = "watchlist_mode_text_$index"
)
```

完整布局要求：

- 外层 `BoxWithConstraints` 高度约 `46dp`，内层背景圆角胶囊；
- 两个 item 的动态宽度总和始终等于内容宽度；
- 选中项使用 primary 玻璃胶囊，未选中项无实色填充；
- 选中项文字 `18.sp`、`FontWeight.Bold`，未选中项 `14.sp`、`FontWeight.Medium`；
- 每个 item 最小高度 `44dp`，文本单行、必要时 `maxLines = 1`；
- 选中项宽度和胶囊位置使用同一 `240ms` tween，不使用 spring/回弹；
- `selectedIndex` 通过 `coerceIn(0, 1)` 防止状态异常；
- 外层 `Modifier.semantics` 不吞掉 item 点击，保留 `Role.Tab` 和 selected 信息。

- [ ] **步骤 2：替换 WatchlistScreen 中的全局分段器**

将 `WatchlistScreen.kt:1064` 附近的调用替换为：

```kotlin
WatchlistModeSelector(
    tabs = listOf(
        stringResource(R.string.watchlist_mode_watchlist),
        stringResource(R.string.watchlist_mode_watched)
    ),
    selectedIndex = selectedMode,
    onTabSelected = {
        collapseSearch()
        selectedMode = it
    }
)
```

删除该调用的 `sizeMultiplier = 1.25f`，保留搜索收起和 `selectedMode` 更新逻辑。不要修改 Discover/其他页面对 `CapsuleTabSelector` 的调用。

- [ ] **步骤 3：缩小 Watchlist 分类 Tab**

在 `WatchlistCategoryTabs.kt`：

```kotlin
.height(44.dp)
...
fontSize = 14.sp
...
modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
```

保留 `1.05f` 选中缩放、数量徽标、`Role.Tab`、testTag 和等宽布局。

在 `WatchlistScreen.kt` 将外部 modifier 从：

```kotlin
Modifier.padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 4.dp)
```

调整为：

```kotlin
Modifier.padding(start = 12.dp, end = 12.dp, top = 2.dp, bottom = 2.dp)
```

- [ ] **步骤 4：运行 Watchlist 测试和 Kotlin 编译**

有在线设备时运行：

```bash
./gradlew.bat :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.tracktosearch.ui.screen.watchlist.WatchlistScreenTest --no-daemon
```

无在线设备或 instrumentation 不可用时至少运行：

```bash
./gradlew.bat :app:compileDebugKotlin --no-daemon
```

预期：编译成功；设备测试中默认状态、已看点击和分类 Tab 语义通过。若设备不可用，必须记录未完成设备证据。

- [ ] **步骤 5：Commit Watchlist Tab 改动**

```bash
git add app/src/main/java/com/tracktosearch/ui/screen/watchlist/WatchlistModeSelector.kt app/src/main/java/com/tracktosearch/ui/screen/watchlist/WatchlistCategoryTabs.kt app/src/main/java/com/tracktosearch/ui/screen/watchlist/WatchlistScreen.kt app/src/androidTest/java/com/tracktosearch/ui/screen/watchlist/WatchlistScreenTest.kt
git diff --cached --check
git diff --cached --name-only
git commit -m "style(watchlist): 优化分类与模式切换"
```

---

## 任务 7：放大底部导航文字并补充回归验证

**文件：**

- 修改：`app/src/main/java/com/tracktosearch/ui/screen/main/MainScreen.kt`
- 修改：`app/src/test/java/com/tracktosearch/ui/screen/main/MainScreenTest.kt`（如果现有测试文件不存在，则在已有导航 Compose 测试文件中增加，不新建重复测试入口）

- [ ] **步骤 1：确认导航测试入口**

运行以下搜索并根据结果选择现有测试文件，不创建重复的 `MainScreenTest`：

```bash
rg -n "MainScreen|tab_me|tab_search|tab_discover|onboarding_step" app/src/test app/src/androidTest --glob '*.kt'
```

测试必须至少验证主导航标签文案存在；字号属于视觉验收，不能仅用文案存在代替截图检查。

- [ ] **步骤 2：先添加导航标签稳定 testTag**

在 `NavTabItem` 的 `Text` 上增加：

```kotlin
Modifier.testTag("bottom_nav_label_$labelRes")
```

如果现有测试不适合依赖资源 ID 字符串，使用 `Modifier.semantics { contentDescription = stringResource(labelRes) }` 保持可定位性，但不要改变可见文案。

- [ ] **步骤 3：将导航标签字号从 10sp 调整为 12sp**

保留现有 `selectedScale`、`fontWeight`、`maxLines = 1` 和四等分 `weight`；只把：

```kotlin
fontSize = 10.sp
```

改为：

```kotlin
fontSize = 12.sp
```

如设备截图发现选中态过大，不修改触控区和图标尺寸，只把选中缩放上限从现有 `1.06f` 降到 `1.04f`，并用截图验证中文/英文标签不裁切。

- [ ] **步骤 4：运行导航相关测试和编译**

```bash
./gradlew.bat :app:testDebugUnitTest --no-daemon
./gradlew.bat :app:compileDebugKotlin --no-daemon
```

预期：相关单测与 Kotlin 编译成功；不因底部文字放大改变导航状态或 onboarding 目标矩形。

- [ ] **步骤 5：Commit 底部导航改动**

```bash
git add app/src/main/java/com/tracktosearch/ui/screen/main/MainScreen.kt <实际存在且由本任务修改的导航测试文件>
git diff --cached --check
git diff --cached --name-only
git commit -m "style(底部导航): 放大导航文字"
```

暂存前必须检查 `git diff --cached --name-only`，只保留本任务实际修改的导航源文件和测试文件，不要把其他测试或构建产物带入。

---

## 任务 8：完整验证、设备截图与收尾审查

**文件：** 无计划外源代码变更；如验证发现缺陷，先回到对应任务补测试和最小修复。

- [ ] **步骤 1：运行资源与详情完整 JVM 测试**

```bash
./gradlew.bat :app:testDebugUnitTest --tests "com.tracktosearch.data.repository.RuleBasedRelevanceScorerTest" --tests "com.tracktosearch.data.repository.ResourceRepositoryTest" --tests "com.tracktosearch.ui.screen.detail.DetailViewModelSupplementTest" --tests "com.tracktosearch.ui.screen.detail.DetailRatingsDialogTest" --tests "com.tracktosearch.ui.component.RatingBadgeTest" --no-daemon
```

预期：`BUILD SUCCESSFUL`，所有定向测试通过。不要用工具层 timeout 直接判定 Gradle 失败；超时后检查 Gradle 进程、`app/build/test-results`、`app/build/reports` 和 APK 状态。

- [ ] **步骤 2：运行 Debug 构建**

```bash
./gradlew.bat :app:assembleDebug --no-daemon
```

预期：`app/build/outputs/apk/debug/app-debug.apk` 存在，构建成功；若本 worktree 没有 `local.properties`，按 AGENTS.md 使用临时 `ANDROID_HOME=H:/android/Sdk` 与 `ANDROID_SDK_ROOT=H:/android/Sdk`，不修改版本控制文件。

- [ ] **步骤 3：使用 Android QA skill 验证设备状态和安装**

先按 `android-emulator-qa` skill 读取并执行设备前置检查：

```bash
adb devices
adb -s <serial> shell getprop ro.build.version.release
adb -s <serial> shell getprop ro.build.version.sdk
adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk
```

记录真实设备 API、分辨率、密度和安装结果。不要仅以 Gradle `installDebug` 的设备筛选结果代替 ADB 事实核验。

- [ ] **步骤 4：执行详情页资源过滤验收**

在详情页打开资源区域，分别验证：

1. 关闭高相关时 6 条截图资源均可见。
2. 开启高相关时第 1、2 条视频资源保留。
3. 第 3～6 条带 `FLAC/24bit/48khz` 的音频资源隐藏。
4. 切回全部时 6 条资源恢复，隐藏计数归零。
5. 同名歌曲/原声带、错年份和电影中的季集结果仍被隐藏。

保存开启前后截图和 UI 树；必要时通过日志记录资源名与判定结果，但不得输出账号、token 或其它敏感信息。

- [ ] **步骤 5：执行视觉验收**

检查白色/浅色海报右上角评分：星标、数字和深色描边清晰，未覆盖状态标签。检查详情右侧评分卡仍为两行四槽且不挤压操作按钮。检查底部导航文字在当前语言和窄屏下不裁切。

在 Watchlist 检查：

- 分类 Tab 文字和上下留白比之前紧凑；
- 想看选中时占比超过一半、文字明显放大；
- 切换到已看时旧项缩小、新项放大，宽度过渡约 240ms；
- 搜索展开、筛选按钮、模式切换和分类 Tab 点击仍可用；
- 深色主题下选中/未选中对比度足够。

- [ ] **步骤 6：检查崩溃日志并审查 Git 状态**

```bash
adb -s <serial> logcat -c
# 完成关键页面操作后
adb -s <serial> logcat -d -b crash
git status --short --branch
git diff --check
```

预期：`logcat -b crash` 无本次改动相关崩溃；工作区只包含已提交改动或明确的用户既有文件，不得混入截图、APK、`.superpowers`、临时日志或敏感配置。

- [ ] **步骤 7：按实际结果补充修复并提交**

若验证失败：先为失败行为添加最小回归测试，再修改对应源文件，重新运行定向测试并使用对应中文 Conventional Commit。若全部通过，不创建空提交；最终汇总静态、测试、构建、设备和部署证据边界。

---

## 计划自检记录

- 规格中的资源高相关解析、截图六条资源、误杀保护、排序/缓存和两个详情页面均由任务 1～3 覆盖。
- 规格中的共享海报评分、详情头部评分区和固定高度由任务 4 覆盖。
- 规格中的 Watchlist 分类 Tab、内容驱动模式切换、动画、语义和现有回调由任务 5～6 覆盖。
- 规格中的底部导航字号和窄屏验收由任务 7～8 覆盖。
- 规格中的 JVM、Compose、Debug、设备截图、UI 树和 crash log 证据由任务 8 覆盖。
- 计划未引入新的网络请求、缓存协议、资源字段或全局 Tab 行为。
- 计划中没有 `TODO`、`TBD`、待定项或未定义的后续接口；`TitleMatch`、`ResourceContentType`、`ResourceRelevance`、`highRelevanceMap` 和 `WatchlistModeSelector` 均在前序任务中定义后再被引用。
