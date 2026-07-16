# UI 连续打磨实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 修复 7 类 UI 细节问题，统一详情页/发现页/设置页/演职员页/网盘/豆瓣相关卡片与按钮风格。

**架构：** 在既有 Composable 组件上做最小化修改；新增/复用 `PosterCard`、`ActionButtonRow`、`ResourceItemCard` 等通用组件，避免重复实现；所有字符串资源必须同步 4 语言。

**技术栈：** Android Jetpack Compose、Haze、Coil、Material3

---

## 文件结构

| 文件 | 职责 |
|------|------|
| `app/src/main/java/com/tracktosearch/ui/screen/detail/DetailScreen.kt` | 详情页根布局、Haze 源、返回/分享按钮 |
| `app/src/main/java/com/tracktosearch/ui/screen/discover/DiscoverSheets.kt` | 各查看全部 Sheet 的卡片网格 |
| `app/src/main/java/com/tracktosearch/ui/screen/discover/DiscoverScreen.kt` | 各 Sheet 调用点 |
| `app/src/main/java/com/tracktosearch/ui/screen/settings/SettingsComponents.kt` | 设置页 `StatisticsCard` |
| `app/src/main/res/values-zh/strings.xml` | 中文文案 |
| `app/src/main/res/values/strings.xml` | 英文文案 |
| `app/src/main/res/values-ja/strings.xml` | 日文文案 |
| `app/src/main/res/values-ko/strings.xml` | 韩文文案 |
| `app/src/main/java/com/tracktosearch/ui/screen/person/PersonScreen.kt` | 演职员详情页布局与渐变 |
| `app/src/main/java/com/tracktosearch/ui/screen/person/PersonViewModel.kt` | 演职员头像主色提取 |
| `app/src/main/java/com/tracktosearch/ui/component/ResourceItemCard.kt` | 网盘资源结果卡片 |
| `app/src/main/java/com/tracktosearch/ui/screen/douban/DoubanFailuresScreen.kt` | 豆瓣失败项影视卡片 |
| `app/src/main/java/com/tracktosearch/ui/screen/douban/DoubanItemDetailScreen.kt` | 豆瓣条目详情按钮与资源卡片 |

---

### 任务 1：详情页返回/分享按钮始终 Haze 模糊

**文件：**
- 修改：`app/src/main/java/com/tracktosearch/ui/screen/detail/DetailScreen.kt:223-240`

**目标：** 页面顶部时返回/分享按钮也能正确 Haze 模糊。

- [ ] **步骤 1：把根 Box 设为 Haze 源**

当前根 `Box` 负责沉浸渐变背景，但不是 `hazeSource`。在 `LazyColumn` 已经设置 `hazeSource` 的基础上，把根 `Box` 也设为 `hazeSource`，让按钮在页面顶部时模糊背后的渐变背景。

```kotlin
Box(modifier = Modifier
    .fillMaxSize()
    .padding(padding)
    .hazeSource(state = detailHazeState)   // 新增
    .then(
        uiState.posterDominantColor?.let { c ->
            Modifier.background(
                Brush.verticalGradient(
                    colors = listOf(
                        c.copy(alpha = 0.70f),
                        MaterialTheme.colorScheme.background
                    )
                )
            )
        } ?: Modifier
    )
)
```

- [ ] **步骤 2：确认返回/分享按钮已使用 hazeEffect**

在 `DetailScreen` 中搜索返回/分享按钮，确保它们已经应用 `hazeEffect`。如果当前只是 `.background(...)`，则改为：

```kotlin
IconButton(
    onClick = onBack,
    modifier = Modifier
        .hazeEffect(
            state = detailHazeState,
            style = HazeMaterials.thin(MaterialTheme.colorScheme.background)
        )
        .background(
            MaterialTheme.colorScheme.background.copy(alpha = 0.35f),
            CircleShape
        )
) { /* 返回图标 */ }
```

- [ ] **步骤 3：运行 lint / 编译检查**

运行：
```bash
.\gradlew :app:compileDebugKotlin
```
预期：`BUILD SUCCESSFUL` 或仅有既有警告。

- [ ] **步骤 4：Commit**

```bash
git add app/src/main/java/com/tracktosearch/ui/screen/detail/DetailScreen.kt
git commit -m "ui: 详情页根布局设为 Haze 源，顶部按钮始终模糊"
```

---

### 任务 2：发现页“查看全部”弹窗卡片显示标题和副标题

**文件：**
- 修改：`app/src/main/java/com/tracktosearch/ui/screen/discover/DiscoverSheets.kt`
- 修改：`app/src/main/java/com/tracktosearch/ui/screen/discover/DiscoverScreen.kt`（如有必要传入副标题）

**目标：** 弹窗网格中的每张卡片都像发现页横向列表一样，海报下方显示标题 + 副标题。

- [ ] **步骤 1：创建 Sheet 专用卡片组件 `SheetMediaCard`**

在 `DiscoverSheets.kt` 文件内新增（或复用 `PosterCard`）：

```kotlin
@Composable
private fun SheetMediaCard(
    imageUrl: String?,
    title: String,
    subtitle: String?,
    year: String?,
    rating: Double?,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.Start
    ) {
        PosterCard(
            imageUrl = imageUrl,
            title = title,
            year = year,
            rating = rating,
            onClick = null,
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            text = title,
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp, start = 2.dp, end = 2.dp)
        )
        if (!subtitle.isNullOrBlank()) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp, start = 2.dp, end = 2.dp)
            )
        }
    }
}
```

- [ ] **步骤 2：在 `TmdbAllSheet` 中替换 `PosterCard`**

把 `itemsIndexed` 中的 `PosterCard(...)` 替换为：

```kotlin
SheetMediaCard(
    imageUrl = movie.poster_path?.let { TmdbImageUrls.build(it) },
    title = movie.title,
    subtitle = null, // TMDB 趋势/热门/即将上映暂无 watchingCount，保持为空
    year = movie.release_date.take(4).takeIf { it != "0" },
    rating = if (movie.rating > 0) movie.rating else null,
    onClick = { onItemClick(movie) }
)
```

- [ ] **步骤 3：在 `TraktMovieAllSheet` 中替换卡片并添加副标题**

`TraktMovie` 类型通常没有 `watchers`；如果有，显示 `watchers` 数量。否则副标题为空。

```kotlin
SheetMediaCard(
    imageUrl = movie.posterPath?.let { TmdbImageUrls.build(it) },
    title = movie.title,
    subtitle = movie.watchers?.let { stringResource(R.string.people_watching, it) },
    year = if (movie.year > 0) movie.year.toString() else null,
    rating = if (movie.rating > 0) movie.rating else null,
    onClick = { onItemClick(movie) }
)
```

若 `TraktMovie` 没有 `watchers` 字段，则 subtitle 传 `null`。

- [ ] **步骤 4：在 `TraktShowAllSheet` 中替换卡片并添加副标题**

```kotlin
SheetMediaCard(
    imageUrl = show.posterPath?.let { TmdbImageUrls.build(it) },
    title = show.title,
    subtitle = show.watchers?.let { stringResource(R.string.people_watching, it) },
    year = if (show.year > 0) show.year.toString() else null,
    rating = if (show.rating > 0) show.rating else null,
    onClick = { onItemClick(show) }
)
```

若 `TraktShow` 没有 `watchers` 字段，则 subtitle 传 `null`。

- [ ] **步骤 5：在 `TraktAnticipatedAllSheet` 中替换卡片并添加副标题**

电影：

```kotlin
SheetMediaCard(
    imageUrl = item.movie.posterPath?.let { TmdbImageUrls.build(it) },
    title = item.movie.title,
    subtitle = stringResource(R.string.lists_count, item.list_count),
    year = if (item.movie.year > 0) item.movie.year.toString() else null,
    rating = if (item.movie.rating > 0) item.movie.rating else null,
    onClick = { onMovieClick(item.movie) }
)
```

剧集：

```kotlin
SheetMediaCard(
    imageUrl = item.show.posterPath?.let { TmdbImageUrls.build(it) },
    title = item.show.title,
    subtitle = stringResource(R.string.lists_count, item.list_count),
    year = if (item.show.year > 0) item.show.year.toString() else null,
    rating = if (item.show.rating > 0) item.show.rating else null,
    onClick = { onShowClick(item.show) }
)
```

- [ ] **步骤 6：在 `TrendingListsAllSheet` 中替换卡片并添加副标题**

列表项通常显示列表名称 + like 数量：

```kotlin
SheetMediaCard(
    imageUrl = list.posterPath?.let { TmdbImageUrls.build(it) },
    title = list.name,
    subtitle = stringResource(R.string.likes_count, list.likes),
    year = null,
    rating = null,
    onClick = { onListClick(list.id, list.name) }
)
```

字段名以实际 `TraktTrendingListResponse` 定义为准（可能为 `list.like_count` 等）。

- [ ] **步骤 7：添加/确认字符串资源**

确认 `values-zh/strings.xml` 中已有：

```xml
<string name="people_watching">%1$d 人在看</string>
<string name="lists_count">%1$d 个列表</string>
<string name="likes_count">%1$d 喜欢</string>
```

如不存在，在 4 个语言文件中都添加对应翻译。

- [ ] **步骤 8：编译检查**

运行：
```bash
.\gradlew :app:compileDebugKotlin
```
预期：`BUILD SUCCESSFUL`。

- [ ] **步骤 9：Commit**

```bash
git add app/src/main/java/com/tracktosearch/ui/screen/discover/DiscoverSheets.kt
# 如有新增字符串资源一并添加
# git add app/src/main/res/values/strings.xml app/src/main/res/values-zh/strings.xml ...
git commit -m "ui: 查看全部弹窗卡片显示标题和副标题"
```

---

### 任务 3：设置页统计卡片去掉数字

**文件：**
- 修改：`app/src/main/java/com/tracktosearch/ui/screen/settings/SettingsComponents.kt:622-686`

**目标：** 移除 `StatisticsCard` 右侧的已看数量数字。

- [ ] **步骤 1：删除右侧数字 Column**

当前代码：

```kotlin
Column(horizontalAlignment = Alignment.End) {
    Text(
        text = count.toString(),
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary
    )
}
Spacer(modifier = Modifier.width(8.dp))
```

删除上述 `Column` 和后面的 `Spacer`，保留箭头 `Icon`。

修改后该部分应为：

```kotlin
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
```

- [ ] **步骤 2：编译检查**

运行：
```bash
.\gradlew :app:compileDebugKotlin
```

- [ ] **步骤 3：Commit**

```bash
git add app/src/main/java/com/tracktosearch/ui/screen/settings/SettingsComponents.kt
git commit -m "ui: 设置页统计卡片移除数量数字"
```

---

### 任务 4：详情页已看按钮文案改为“已看 / 已看过”

**文件：**
- 修改：`app/src/main/res/values-zh/strings.xml`
- 修改：`app/src/main/res/values/strings.xml`
- 修改：`app/src/main/res/values-ja/strings.xml`
- 修改：`app/src/main/res/values-ko/strings.xml`

**目标：** 未看时显示“已看”，已看时显示“已看过”。

- [ ] **步骤 1：修改中文字符串**

```xml
<string name="detail_mark_watched">已看</string>
<string name="detail_marked_watched">已看过</string>
```

- [ ] **步骤 2：修改英文字符串**

```xml
<string name="detail_mark_watched">Mark as Watched</string>
<string name="detail_marked_watched">Watched</string>
```

- [ ] **步骤 3：修改日文字符串**

```xml
<string name="detail_mark_watched">視聴済みにする</string>
<string name="detail_marked_watched">視聴済み</string>
```

- [ ] **步骤 4：修改韩文字符串**

```xml
<string name="detail_mark_watched">시청한 것으로 표시</string>
<string name="detail_marked_watched">시청함</string>
```

- [ ] **步骤 5：编译检查**

运行：
```bash
.\gradlew :app:compileDebugKotlin
```

- [ ] **步骤 6：Commit**

```bash
git add app/src/main/res/values-zh/strings.xml app/src/main/res/values/strings.xml app/src/main/res/values-ja/strings.xml app/src/main/res/values-ko/strings.xml
git commit -m "ui: 详情页已看按钮文案改为已看/已看过"
```

---

### 任务 5：演职员详情页沉浸色方案改为 master 同款并立即渲染

**文件：**
- 修改：`app/src/main/java/com/tracktosearch/ui/screen/person/PersonScreen.kt`
- 修改：`app/src/main/java/com/tracktosearch/ui/screen/person/PersonViewModel.kt`

**目标：** 演职员详情页使用整页渐变，进入页面时立即用传入头像 URL 提取/渲染沉浸色。

- [ ] **步骤 1：修改 `PersonScreen` 顶部渐变**

把固定高度 220dp 的渐变 Box 替换为覆盖全页的渐变。

当前代码（约 L117-130）：

```kotlin
Box(
    modifier = Modifier
        .fillMaxWidth()
        .height(220.dp)
        .align(Alignment.TopCenter)
        .background(
            Brush.verticalGradient(
                colors = listOf(
                    uiState.avatarDominantColor ?: MaterialTheme.colorScheme.surface,
                    Color.Transparent
                )
            )
        )
)
```

替换为：

```kotlin
Box(
    modifier = Modifier
        .fillMaxSize()
        .background(
            Brush.verticalGradient(
                colors = listOf(
                    uiState.avatarDominantColor?.copy(alpha = 0.70f)
                        ?: MaterialTheme.colorScheme.background,
                    MaterialTheme.colorScheme.background
                )
            )
        )
)
```

- [ ] **步骤 2：在 `PersonViewModel` 中新增立即提取方法**

当前 `prefetchAvatarColor` 依赖 API 返回的 `profilePath`。新增公开方法，允许用传入的 `profileUrl` 直接提取：

```kotlin
fun prefetchAvatarColorFromUrl(profileUrl: String?) {
    if (profileUrl.isNullOrBlank()) return
    if (_uiState.value.avatarDominantColor != null) return

    viewModelScope.launch {
        // 先查缓存
        posterColorExtractor.getCachedColor(profileUrl)?.let { argb ->
            if (argb != 0L) {
                _uiState.value = _uiState.value.copy(avatarDominantColor = Color(argb))
                return@launch
            }
        }

        // 缓存未命中，用 Coil 加载并提取
        val request = ImageRequest.Builder(context)
            .data(profileUrl)
            .size(200)
            .crossfade(false)
            .listener(
                onSuccess = { _, result ->
                    val bitmap = result.drawable.toBitmap()
                    viewModelScope.launch {
                        val argb = posterColorExtractor.extractDominantColor(profileUrl, bitmap)
                        if (argb != 0L) {
                            _uiState.value = _uiState.value.copy(avatarDominantColor = Color(argb))
                        }
                    }
                }
            )
            .build()
        context.imageLoader.enqueue(request)
    }
}
```

- [ ] **步骤 3：在 `PersonScreen` 进入时调用新方法**

在 `LaunchedEffect(personId)` 中新增：

```kotlin
LaunchedEffect(personId, profileUrl) {
    viewModel.loadPerson(personId)
    viewModel.prefetchAvatarColorFromUrl(profileUrl)
}
```

- [ ] **步骤 4：编译检查**

运行：
```bash
.\gradlew :app:compileDebugKotlin
```

- [ ] **步骤 5：Commit**

```bash
git add app/src/main/java/com/tracktosearch/ui/screen/person/PersonScreen.kt app/src/main/java/com/tracktosearch/ui/screen/person/PersonViewModel.kt
git commit -m "ui: 演职员详情页沉浸色改为整页渐变并立即渲染"
```

---

### 任务 6：网盘搜索资源结果卡片重新设计

**文件：**
- 修改：`app/src/main/java/com/tracktosearch/ui/component/ResourceItemCard.kt`

**目标：** 使用品牌色微弱渐变背景的沉浸风格卡片。

- [ ] **步骤 1：修改 `Card` 背景为品牌色渐变**

当前 `Card`：

```kotlin
Card(
    modifier = modifier
        .fillMaxWidth()
        .padding(horizontal = 4.dp, vertical = 2.dp)
        .combinedClickable(
            onClick = onClick,
            onLongClick = onLongClick
        ),
    shape = RoundedCornerShape(12.dp),
    colors = CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.surfaceVariant
    ),
    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
)
```

替换为：

```kotlin
val style = diskStyleOf(item.diskType)
val cardBackground = Brush.linearGradient(
    colors = listOf(
        style.backgroundColor.copy(alpha = 0.12f),
        style.backgroundColor.copy(alpha = 0.02f)
    )
)

Card(
    modifier = modifier
        .fillMaxWidth()
        .padding(horizontal = 4.dp, vertical = 2.dp)
        .combinedClickable(
            onClick = onClick,
            onLongClick = onLongClick
        ),
    shape = RoundedCornerShape(16.dp),
    colors = CardDefaults.cardColors(
        containerColor = Color.Transparent
    ),
    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(cardBackground)
            .padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 10.dp)
            .alpha(contentAlpha)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // 原内容平移到这里
        }
    }
}
```

注意：原 `Column` 的 `Modifier.alpha(contentAlpha)` 需要移到外层 `Box`，并把 `Column` 作为 `Box` 的子元素。

- [ ] **步骤 2：调整资源名称字号和加粗**

当前：

```kotlin
Text(
    text = item.name,
    style = MaterialTheme.typography.bodyMedium,
    maxLines = 2,
    overflow = TextOverflow.Ellipsis,
    color = MaterialTheme.colorScheme.onSurface,
    modifier = Modifier.padding(top = 2.dp)
)
```

改为：

```kotlin
Text(
    text = item.name,
    style = MaterialTheme.typography.bodyMedium.copy(
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp
    ),
    maxLines = 2,
    overflow = TextOverflow.Ellipsis,
    color = MaterialTheme.colorScheme.onSurface,
    modifier = Modifier.padding(top = 4.dp)
)
```

- [ ] **步骤 3：编译检查**

运行：
```bash
.\gradlew :app:compileDebugKotlin
```

- [ ] **步骤 4：Commit**

```bash
git add app/src/main/java/com/tracktosearch/ui/component/ResourceItemCard.kt
git commit -m "ui: 网盘资源结果卡片改为品牌色沉浸风格"
```

---

### 任务 7：豆瓣失败项和豆瓣条目详情页统一新设计风格

**文件：**
- 修改：`app/src/main/java/com/tracktosearch/ui/screen/douban/DoubanFailuresScreen.kt`
- 修改：`app/src/main/java/com/tracktosearch/ui/screen/douban/DoubanItemDetailScreen.kt`

**目标：** 豆瓣失败项影视卡片改用 PosterCard + 标题/副标题；豆瓣条目详情按钮改用 ActionButtonRow；资源卡片自动使用新风格。

- [ ] **步骤 1：定位 `DoubanFailuresScreen` 中的影视卡片**

搜索文件中渲染失败项卡片的 `Card`/`AsyncImage` 区域（通常在 grid items 中）。

- [ ] **步骤 2：替换为 `SheetMediaCard` 风格（或内联 PosterCard + 标题/副标题）**

假设原卡片大致为：

```kotlin
Card(
    modifier = Modifier.combinedClickable(...),
    // ...
) {
    AsyncImage(...)
    Text(title)
}
```

改为：

```kotlin
Column(
    modifier = Modifier
        .fillMaxWidth()
        .combinedClickable(onClick = ..., onLongClick = ...)
) {
    PosterCard(
        imageUrl = failure.posterUrl,
        title = failure.title,
        year = failure.year?.toString(),
        onClick = null,
        modifier = Modifier.fillMaxWidth()
    )
    Text(
        text = failure.title,
        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
        color = MaterialTheme.colorScheme.onBackground,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(top = 6.dp, start = 2.dp, end = 2.dp)
    )
    Text(
        text = failure.subtitle,
        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(top = 2.dp, start = 2.dp, end = 2.dp)
    )
}
```

字段名以 `DoubanSyncFailure` 实际定义为准。若需要保留选中状态，在 Column 外层加选中边框/遮罩。

- [ ] **步骤 3：定位 `DoubanItemDetailScreen` 中的操作按钮**

搜索想看/已看/取消/重试等按钮（通常在页面头部附近）。

- [ ] **步骤 4：把按钮改为 `ActionButtonRow` + `ActionItem`**

例如原代码：

```kotlin
Button(onClick = { viewModel.toggleWish() }) { Text("想看") }
Button(onClick = { viewModel.toggleCollect() }) { Text("已看") }
```

改为：

```kotlin
val actions = listOf(
    ActionItem(
        icon = if (uiState.isWanted) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder,
        label = if (uiState.isWanted) stringResource(R.string.cd_watchlist_badge) else stringResource(R.string.detail_mark_watchlist),
        selected = uiState.isWanted,
        onClick = { viewModel.toggleWish() }
    ),
    ActionItem(
        icon = if (uiState.isWatched) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff,
        label = if (uiState.isWatched) stringResource(R.string.detail_marked_watched) else stringResource(R.string.detail_mark_watched),
        selected = uiState.isWatched,
        onClick = { viewModel.toggleCollect() }
    )
)
ActionButtonRow(actions = actions, hazeState = hazeState)
```

字段名以 `DoubanItemDetailUiState` 实际定义为准。

- [ ] **步骤 5：确认 `DoubanItemDetailScreen` 中的资源卡片使用 `ResourceItemCard`**

由于 `ResourceItemCard` 已在任务 6 中修改，调用处无需改动，只需确保它仍然被使用：

```kotlin
ResourceItemCard(
    item = item,
    isViewed = ...,
    onClick = { ... },
    onLongClick = { ... },
    index = index
)
```

- [ ] **步骤 6：编译检查**

运行：
```bash
.\gradlew :app:compileDebugKotlin
```

- [ ] **步骤 7：Commit**

```bash
git add app/src/main/java/com/tracktosearch/ui/screen/douban/DoubanFailuresScreen.kt app/src/main/java/com/tracktosearch/ui/screen/douban/DoubanItemDetailScreen.kt
git commit -m "ui: 豆瓣失败项/条目详情页统一新卡片和按钮风格"
```

---

### 任务 8：最终 Debug 构建验证

**文件：** 所有已修改文件

**目标：** 确保所有改动无编译错误、无新增严重警告。

- [ ] **步骤 1：运行完整 Debug 构建**

```bash
.\gradlew :app:assembleDebug
```

预期：`BUILD SUCCESSFUL`。

- [ ] **步骤 2：检查新增警告**

编译输出中搜索 `w:`，确认没有由本次改动引入的警告（如未使用导入、不安全调用等）。

- [ ] **步骤 3：Commit（如有需要）或结束**

如果构建过程中没有需要单独提交的修复，则无需额外 commit。

---

## 自检

- [ ] 所有 7 类改动都有对应的任务。
- [ ] 没有使用“待定”、“TODO”等占位符。
- [ ] 文件中引用的类型名、字段名与实际代码一致。
- [ ] 每个任务都有明确的验证步骤。
