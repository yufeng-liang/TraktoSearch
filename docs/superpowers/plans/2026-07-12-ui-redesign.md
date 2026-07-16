# UI 重设计实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 在 `ui-redesign-experimental` 分支上，把 App 核心页面改成交互沉浸暗色风，统一使用 Haze 毛玻璃、应用色调、动态海报取色与列表动画。

**架构：** 先更新 `Color.kt`/`Theme.kt` 并新增一套共享 UI 组件（底部导航、评分标签、操作按钮、搜索栏、海报卡片）；再逐个改造 6 个核心 Screen；最后统一加动画与 debug 验证。所有改动只涉及 UI 层，不碰业务逻辑。

**技术栈：** Android Jetpack Compose、Material3、dev.chrisbanes.haze、Coil、Kotlin。

---

## 文件结构

| 文件 | 职责 |
|------|------|
| `app/src/main/java/com/tracktosearch/ui/theme/Color.kt` | 新增/调整暗色背景、评分色、网盘品牌色 |
| `app/src/main/java/com/tracktosearch/ui/theme/Theme.kt` | 让 primary 跟随 MonetAccent，保留暗色默认 |
| `app/src/main/java/com/tracktosearch/ui/component/AppBottomBar.kt` | 新增悬浮毛玻璃底部导航 |
| `app/src/main/java/com/tracktosearch/ui/component/RatingBadge.kt` | 新增海报右上角评分标签 |
| `app/src/main/java/com/tracktosearch/ui/component/ActionButtonRow.kt` | 新增想看/已看/评分三等分按钮组 |
| `app/src/main/java/com/tracktosearch/ui/component/GlassSearchBar.kt` | 新增毛玻璃胶囊搜索栏 |
| `app/src/main/java/com/tracktosearch/ui/component/PosterCard.kt` | 统一海报卡片（含年份标签、按压动画） |
| `app/src/main/java/com/tracktosearch/ui/component/SectionHeader.kt` | 统一「标题 + 查看全部 ›」组件 |
| `app/src/main/java/com/tracktosearch/ui/screen/main/MainScreen.kt` | 改用 AppBottomBar |
| `app/src/main/java/com/tracktosearch/ui/screen/search/SearchScreen.kt` | 重设计搜索页 |
| `app/src/main/java/com/tracktosearch/ui/screen/discover/DiscoverScreen.kt` | 重设计发现页 Hero 卡片 |
| `app/src/main/java/com/tracktosearch/ui/screen/watchlist/WatchlistScreen.kt` | 重设计想看/已看列表 |
| `app/src/main/java/com/tracktosearch/ui/screen/detail/DetailScreen.kt` | 重设计影视详情页 |
| `app/src/main/java/com/tracktosearch/ui/screen/person/PersonScreen.kt` | 重设计演员详情页 |
| `app/src/main/java/com/tracktosearch/ui/screen/settings/SettingsScreen.kt` | 重设计设置页 |
| `app/src/main/java/com/tracktosearch/ui/screen/discoverfilter/DiscoverFilterScreen.kt` | 重设计筛选页 |

---

## 任务 1：更新主题色与颜色常量

**文件：**
- 修改：`app/src/main/java/com/tracktosearch/ui/theme/Color.kt`
- 修改：`app/src/main/java/com/tracktosearch/ui/theme/Theme.kt`

- [ ] **步骤 1：新增颜色常量**

在 `Color.kt` 末尾追加：

```kotlin
// UI 重设计新增颜色
val CinemaBackground = Color(0xFF0F0F1A)
val CinemaSurface = Color(0xFF1A1A2E)
val CinemaCard = Color(0xFF242442)
val RatingGold = Color(0xFFF4A460)
val RatingGoldDim = Color(0xFFF4A460).copy(alpha = 0.7f)
```

- [ ] **步骤 2：让暗色 scheme 使用影院色**

修改 `Theme.kt` 中的 `DarkColorScheme`：

```kotlin
private val DarkColorScheme = darkColorScheme(
    primary = Red500,
    onPrimary = Color.White,
    primaryContainer = Red700,
    secondary = QuarkBlue,
    onSecondary = Color.White,
    background = CinemaBackground,
    onBackground = Color.White,
    surface = CinemaSurface,
    onSurface = Color.White,
    surfaceVariant = CinemaCard,
    onSurfaceVariant = LightGray,
)
```

- [ ] **步骤 3：MonetAccent 暗色主题也使用影院背景**

在 `monetColorScheme` 的 `dark` 分支里，把 `background`、`surface`、`surfaceVariant` 改为 `CinemaBackground`、`CinemaSurface`、`CinemaCard`。

- [ ] **步骤 4：编译验证**

运行：

```bash
./gradlew :app:compileDebugKotlin
```

预期：BUILD SUCCESSFUL。

- [ ] **步骤 5：Commit**

```bash
git add app/src/main/java/com/tracktosearch/ui/theme/Color.kt app/src/main/java/com/tracktosearch/ui/theme/Theme.kt
git commit -m "refactor: 更新暗色主题为影院沉浸配色"
```

---

## 任务 2：创建 AppBottomBar（悬浮毛玻璃底部导航）

**文件：**
- 创建：`app/src/main/java/com/tracktosearch/ui/component/AppBottomBar.kt`

- [ ] **步骤 1：实现 AppBottomBar 组件**

```kotlin
package com.tracktosearch.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials

@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun AppBottomBar(
    items: List<BottomBarItem>,
    selectedIndex: Int,
    onItemSelected: (Int) -> Unit,
    hazeState: HazeState,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .navigationBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .clip(RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp))
                .hazeEffect(state = hazeState, style = HazeMaterials.thin(MaterialTheme.colorScheme.background))
                .background(Color.White.copy(alpha = 0.08f))
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically
        ) {
            items.forEachIndexed { index, item ->
                val selected = index == selectedIndex
                val color = if (selected) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.6f)
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .clickable { onItemSelected(index) }
                        .padding(8.dp)
                ) {
                    Icon(imageVector = item.icon, contentDescription = item.label, tint = color)
                    Text(text = item.label, color = color, fontSize = 11.sp)
                }
            }
        }
    }
}

data class BottomBarItem(val icon: androidx.compose.ui.graphics.vector.ImageVector, val label: String)
```

- [ ] **步骤 2：编译验证**

运行：

```bash
./gradlew :app:compileDebugKotlin
```

- [ ] **步骤 3：Commit**

```bash
git add app/src/main/java/com/tracktosearch/ui/component/AppBottomBar.kt
git commit -m "feat: 新增悬浮毛玻璃底部导航组件"
```

---

## 任务 3：创建 RatingBadge（评分标签）

**文件：**
- 创建：`app/src/main/java/com/tracktosearch/ui/component/RatingBadge.kt`

- [ ] **步骤 1：实现 RatingBadge 组件**

```kotlin
package com.tracktosearch.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tracktosearch.ui.theme.RatingGold
import com.tracktosearch.ui.theme.RatingGoldDim

@Composable
fun RatingBadge(rating: Double, modifier: Modifier = Modifier) {
    val background = when {
        rating >= 8.0 -> RatingGold.copy(alpha = 0.9f)
        rating >= 6.0 -> RatingGoldDim
        else -> Color.White.copy(alpha = 0.2f)
    }
    val textColor = if (rating >= 6.0) Color.Black else Color.White
    Text(
        text = "★ %.1f".format(rating),
        color = textColor,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        modifier = modifier
            .background(background, RoundedCornerShape(8.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    )
}
```

- [ ] **步骤 2：编译验证**

运行：

```bash
./gradlew :app:compileDebugKotlin
```

- [ ] **步骤 3：Commit**

```bash
git add app/src/main/java/com/tracktosearch/ui/component/RatingBadge.kt
git commit -m "feat: 新增评分徽章组件"
```

---

## 任务 4：创建 ActionButtonRow（三等分操作按钮）

**文件：**
- 创建：`app/src/main/java/com/tracktosearch/ui/component/ActionButtonRow.kt`

- [ ] **步骤 1：实现 ActionButtonRow 组件**

```kotlin
package com.tracktosearch.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials

@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun ActionButtonRow(
    actions: List<ActionItem>,
    hazeState: HazeState,
    modifier: Modifier = Modifier
) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        actions.forEachIndexed { index, action ->
            val shape = when (index) {
                0 -> RoundedCornerShape(topStart = 14.dp, bottomStart = 14.dp)
                actions.lastIndex -> RoundedCornerShape(topEnd = 14.dp, bottomEnd = 14.dp)
                else -> RoundedCornerShape(0.dp)
            }
            val bg = if (action.selected) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.08f)
            val shadow = if (action.selected) 8.dp else 0.dp
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 4.dp)
                    .shadow(shadow, RoundedCornerShape(14.dp))
                    .clip(RoundedCornerShape(14.dp))
                    .hazeEffect(state = hazeState, style = HazeMaterials.thin(MaterialTheme.colorScheme.background).takeIf { !action.selected } ?: HazeMaterials.thin(MaterialTheme.colorScheme.background))
                    .background(bg)
                    .clickable(onClick = action.onClick)
                    .padding(vertical = 10.dp)
            ) {
                Icon(imageVector = action.icon, contentDescription = action.label, tint = Color.White)
                Text(text = action.label, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

data class ActionItem(
    val icon: ImageVector,
    val label: String,
    val selected: Boolean = false,
    val onClick: () -> Unit
)
```

> 说明：hazeEffect 在选中态可省略，因为背景已是不透明 primary。

- [ ] **步骤 2：编译验证**

运行：

```bash
./gradlew :app:compileDebugKotlin
```

- [ ] **步骤 3：Commit**

```bash
git add app/src/main/java/com/tracktosearch/ui/component/ActionButtonRow.kt
git commit -m "feat: 新增详情页操作按钮组组件"
```

---

## 任务 5：创建 GlassSearchBar（毛玻璃搜索栏）

**文件：**
- 创建：`app/src/main/java/com/tracktosearch/ui/component/GlassSearchBar.kt`

- [ ] **步骤 1：实现 GlassSearchBar 组件**

```kotlin
package com.tracktosearch.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials

@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun GlassSearchBar(
    placeholder: String,
    typeLabel: String,
    onClick: () -> Unit,
    onTypeClick: () -> Unit,
    hazeState: HazeState,
    modifier: Modifier = Modifier
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .hazeEffect(state = hazeState, style = HazeMaterials.thin(MaterialTheme.colorScheme.background))
            .background(Color.White.copy(alpha = 0.08f))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Icon(imageVector = Icons.Rounded.Search, contentDescription = null, tint = Color.White.copy(alpha = 0.7f))
        Text(
            text = placeholder,
            color = Color.White.copy(alpha = 0.5f),
            fontSize = 14.sp,
            modifier = Modifier.weight(1f).padding(horizontal = 10.dp)
        )
        Text(
            text = "$typeLabel ▾",
            color = Color.White.copy(alpha = 0.4f),
            fontSize = 12.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(Color.White.copy(alpha = 0.06f))
                .clickable(onClick = onTypeClick)
                .padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}
```

- [ ] **步骤 2：编译验证**

运行：

```bash
./gradlew :app:compileDebugKotlin
```

- [ ] **步骤 3：Commit**

```bash
git add app/src/main/java/com/tracktosearch/ui/component/GlassSearchBar.kt
git commit -m "feat: 新增毛玻璃搜索栏组件"
```

---

## 任务 6：创建 PosterCard（统一海报卡片）

**文件：**
- 创建：`app/src/main/java/com/tracktosearch/ui/component/PosterCard.kt`

- [ ] **步骤 1：实现 PosterCard 组件**

```kotlin
package com.tracktosearch.ui.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

@Composable
fun PosterCard(
    imageUrl: String?,
    title: String,
    year: String? = null,
    rating: Double? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(if (isPressed) 0.97f else 1f, label = "poster_scale")

    Box(modifier = modifier.scale(scale)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .shadow(8.dp, RoundedCornerShape(14.dp))
                .clip(RoundedCornerShape(14.dp))
                .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
        ) {
            AsyncImage(
                model = imageUrl,
                contentDescription = title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            if (year != null) {
                Text(
                    text = year,
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(6.dp)
                        .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
            if (rating != null) {
                RatingBadge(rating = rating, modifier = Modifier.align(Alignment.TopEnd).padding(6.dp))
            }
        }
    }
}
```

- [ ] **步骤 2：编译验证**

运行：

```bash
./gradlew :app:compileDebugKotlin
```

- [ ] **步骤 3：Commit**

```bash
git add app/src/main/java/com/tracktosearch/ui/component/PosterCard.kt
git commit -m "feat: 新增统一海报卡片组件"
```

---

## 任务 7：MainScreen 改用 AppBottomBar

**文件：**
- 修改：`app/src/main/java/com/tracktosearch/ui/screen/main/MainScreen.kt`

- [ ] **步骤 1：替换底部导航实现**

移除 `MainScreen` 内现有的底部导航实现，改为 `AppBottomBar`。保留现有的 `HazeState`、`HorizontalPager`、`page` 状态逻辑。

关键替换点：

```kotlin
import com.tracktosearch.ui.component.AppBottomBar
import com.tracktosearch.ui.component.BottomBarItem

// 在 bottomBar = { ... } 里
AppBottomBar(
    items = listOf(
        BottomBarItem(Icons.Rounded.Search, stringResource(R.string.tab_search)),
        BottomBarItem(Icons.Rounded.Explore, stringResource(R.string.tab_discover)),
        BottomBarItem(Icons.Rounded.Person, stringResource(R.string.tab_watchlist)),
        BottomBarItem(Icons.Rounded.Settings, stringResource(R.string.tab_settings))
    ),
    selectedIndex = pagerState.currentPage,
    onItemSelected = { scope.launch { pagerState.animateScrollToPage(it) } },
    hazeState = hazeState
)
```

- [ ] **步骤 2：编译验证**

运行：

```bash
./gradlew :app:compileDebugKotlin
```

- [ ] **步骤 3：Commit**

```bash
git add app/src/main/java/com/tracktosearch/ui/screen/main/MainScreen.kt
git commit -m "refactor: MainScreen 使用新的悬浮毛玻璃底部导航"
```

---

## 任务 8：重设计搜索页

**文件：**
- 修改：`app/src/main/java/com/tracktosearch/ui/screen/search/SearchScreen.kt`
- 新增字符串：`app/src/main/res/values/strings.xml` 等 4 语言

- [ ] **步骤 1：添加文案字符串**

在 `values/strings.xml` 添加：

```xml
<string name="search_subtitle">发现你的下一部观影</string>
<string name="hot_search">热门搜索</string>
```

同步到 `values-zh`、`values-ja`、`values-ko`。

- [ ] **步骤 2：改造搜索页 UI**

用 `GlassSearchBar` 替换原有搜索栏，顶部增加标题区，热门搜索使用新的半透明 chip 样式，背景改为 `MaterialTheme.colorScheme.background`。

- [ ] **步骤 3：编译验证**

运行：

```bash
./gradlew :app:compileDebugKotlin
```

- [ ] **步骤 4：Commit**

```bash
git add app/src/main/java/com/tracktosearch/ui/screen/search/SearchScreen.kt app/src/main/res/values/strings.xml app/src/main/res/values-zh/strings.xml app/src/main/res/values-ja/strings.xml app/src/main/res/values-ko/strings.xml
git commit -m "refactor: 搜索页改成交互沉浸暗色风"
```

---

## 任务 9：重设计发现页

**文件：**
- 修改：`app/src/main/java/com/tracktosearch/ui/screen/discover/DiscoverScreen.kt`

- [ ] **步骤 1：实现 Hero 分类卡片**

在文件内新增 `CategoryHeroCard` composable：

```kotlin
@Composable
private fun CategoryHeroCard(title: String, count: Int, gradient: Brush, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .width(140.dp)
            .height(90.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(gradient)
            .clickable(onClick = onClick)
            .padding(12.dp)
    ) {
        Text(text = title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.BottomStart))
    }
}
```

- [ ] **步骤 2：改造发现页结构**

顶部改为横向滑动的 Hero 分类卡片，下方分类列表使用 `SectionHeader` + `PosterCard` 横向滚动。

- [ ] **步骤 3：编译验证**

运行：

```bash
./gradlew :app:compileDebugKotlin
```

- [ ] **步骤 4：Commit**

```bash
git add app/src/main/java/com/tracktosearch/ui/screen/discover/DiscoverScreen.kt
git commit -m "refactor: 发现页新增 Hero 分类卡片与沉浸海报列表"
```

---

## 任务 10：重设计想看/已看列表

**文件：**
- 修改：`app/src/main/java/com/tracktosearch/ui/screen/watchlist/WatchlistScreen.kt`

- [ ] **步骤 1：改造顶部与 Tab**

使用 `GlassSearchBar`，想看/已看按钮改为大胶囊，电影/电视剧 Tab 改为下划线样式。

- [ ] **步骤 2：网格改用 PosterCard**

把现有的 poster item 替换为 `PosterCard`，统一显示年份与类型。

- [ ] **步骤 3：编译验证**

运行：

```bash
./gradlew :app:compileDebugKotlin
```

- [ ] **步骤 4：Commit**

```bash
git add app/src/main/java/com/tracktosearch/ui/screen/watchlist/WatchlistScreen.kt
git commit -m "refactor: 想看已看列表改用统一海报卡片与毛玻璃顶部"
```

---

## 任务 11：重设计影视详情页

**文件：**
- 修改：`app/src/main/java/com/tracktosearch/ui/screen/detail/DetailScreen.kt`

- [ ] **步骤 1：实现沉浸式顶部**

用海报主色生成 `Brush.verticalGradient` 背景，高度 260dp。返回/分享按钮用毛玻璃圆形浮层。

- [ ] **步骤 2：使用 ActionButtonRow**

把原有的想看/已看/评分按钮替换为 `ActionButtonRow`。

- [ ] **步骤 3：Sticky Tab 样式**

资源/评论/推荐 Tab 吸顶，背景使用 Haze 毛玻璃。

- [ ] **步骤 4：编译验证**

运行：

```bash
./gradlew :app:compileDebugKotlin
```

- [ ] **步骤 5：Commit**

```bash
git add app/src/main/java/com/tracktosearch/ui/screen/detail/DetailScreen.kt
git commit -m "refactor: 影视详情页改成沉浸暗色风"
```

---

## 任务 12：重设计演员详情页

**文件：**
- 修改：`app/src/main/java/com/tracktosearch/ui/screen/person/PersonScreen.kt`

- [ ] **步骤 1：改造顶部与简介卡片**

头像取色渐变背景，简介放在半透明圆角卡片内。

- [ ] **步骤 2：图片与参演电影列表**

使用统一的横向滚动样式，海报用 `PosterCard`。

- [ ] **步骤 3：编译验证**

运行：

```bash
./gradlew :app:compileDebugKotlin
```

- [ ] **步骤 4：Commit**

```bash
git add app/src/main/java/com/tracktosearch/ui/screen/person/PersonScreen.kt
git commit -m "refactor: 演员详情页改成沉浸暗色风"
```

---

## 任务 13：重设计设置页

**文件：**
- 修改：`app/src/main/java/com/tracktosearch/ui/screen/settings/SettingsScreen.kt`

- [ ] **步骤 1：分组卡片化**

把设置项按组放入半透明圆角卡片，分组标题改为 12sp 大写。

- [ ] **步骤 2：统计入口大卡片**

观看统计做成独立大卡片，左侧图标放在主题色浅色背景方块中。

- [ ] **步骤 3：编译验证**

运行：

```bash
./gradlew :app:compileDebugKotlin
```

- [ ] **步骤 4：Commit**

```bash
git add app/src/main/java/com/tracktosearch/ui/screen/settings/SettingsScreen.kt
git commit -m "refactor: 设置页改成分组卡片暗色风"
```

---

## 任务 14：重设计筛选页

**文件：**
- 修改：`app/src/main/java/com/tracktosearch/ui/screen/discoverfilter/DiscoverFilterScreen.kt`

- [ ] **步骤 1：改造筛选条件与结果列表**

筛选 chip 使用毛玻璃半透明样式，结果列表改为海报左 + 信息右。

- [ ] **步骤 2：编译验证**

运行：

```bash
./gradlew :app:compileDebugKotlin
```

- [ ] **步骤 3：Commit**

```bash
git add app/src/main/java/com/tracktosearch/ui/screen/discoverfilter/DiscoverFilterScreen.kt
git commit -m "refactor: 筛选页改成沉浸暗色风"
```

---

## 任务 15：统一列表入场动画与按压反馈

**文件：**
- 创建：`app/src/main/java/com/tracktosearch/ui/animation/Animations.kt`

- [ ] **步骤 1：创建动画工具函数**

```kotlin
package com.tracktosearch.ui.animation

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer

fun Modifier.fadeSlideIn(index: Int = 0): Modifier = composed {
    val alpha = remember { Animatable(0f) }
    val offsetY = remember { Animatable(20f) }
    LaunchedEffect(Unit) {
        alpha.animateTo(1f, animationSpec = tween(300, delayMillis = index * 30))
        offsetY.animateTo(0f, animationSpec = tween(300, delayMillis = index * 30))
    }
    graphicsLayer(
        alpha = alpha.value,
        translationY = offsetY.value
    )
}
```

- [ ] **步骤 2：应用到列表**

在 `DiscoverScreen`、`WatchlistScreen` 等列表 item 上应用 `Modifier.fadeSlideIn(index)`。

- [ ] **步骤 3：编译验证**

运行：

```bash
./gradlew :app:compileDebugKotlin
```

- [ ] **步骤 4：Commit**

```bash
git add app/src/main/java/com/tracktosearch/ui/animation/Animations.kt app/src/main/java/com/tracktosearch/ui/screen/discover/DiscoverScreen.kt app/src/main/java/com/tracktosearch/ui/screen/watchlist/WatchlistScreen.kt
git commit -m "feat: 列表增加 stagger 入场动画"
```

---

## 任务 16：最终 Debug 构建与验证

**文件：**
- 全部已修改文件

- [ ] **步骤 1：运行 Debug 构建**

```bash
./gradlew :app:assembleDebug
```

预期：BUILD SUCCESSFUL。

- [ ] **步骤 2：检查 lint 与编译错误**

```bash
./gradlew :app:lintDebug
```

预期：无新增严重错误。

- [ ] **步骤 3：最终 Commit（如无错误）**

```bash
git status
```

确认无未提交改动后：

```bash
git commit --allow-empty -m "chore: UI 重设计完成，debug 构建通过"
```

---

## 自检

- [ ] 规格中每个页面都有对应任务：搜索、发现、列表、详情、演员、设置、筛选。
- [ ] 共享组件（AppBottomBar、RatingBadge、ActionButtonRow、GlassSearchBar、PosterCard）都已单独创建。
- [ ] 动画与主题色修改已纳入计划。
- [ ] 所有新增 UI 文案都计划同步 4 语言 strings.xml。
- [ ] 计划中没有"待定"/"TODO"/"后续实现"。
