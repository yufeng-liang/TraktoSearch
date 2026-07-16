# UI 连续打磨设计文档

## 背景

在上一轮 UI 打磨（`2026-07-13-ui-polish-design.md`）完成后，用户在实际测试中又发现多处细节不一致：详情页顶部按钮的 Haze 模糊状态不统一、查看全部弹窗卡片信息不完整、设置页统计卡片数字冗余、部分按钮文案不自然、演职员详情沉浸色加载滞后、以及网盘/豆瓣相关卡片按钮风格未统一。

## 目标

基于用户反馈和已确认的设计方向，完成 7 类 UI 细节打磨，提升全局一致性和沉浸感。

## 已确认的设计方向

- 影视卡片统一使用 **Watchlist 页 PosterCard 风格**（海报 + 标题 + 副标题）。
- 按钮统一使用 **详情页 ActionButtonRow 风格**（圆角底块 + 图标 + 文字）。
- 资源结果卡片使用 **品牌色微弱渐变沉浸风格**。

---

## 改动清单

### 1. 详情页返回/分享按钮始终正确 Haze 模糊

**问题**：当前返回按钮和分享按钮只在列表内容滚动到 Tab 栏下方时才正确 Haze 模糊；在页面顶部时，按钮背后是整页沉浸渐变，没有可模糊的内容，只能看到半透明兜底。

**方案**：
- 把详情页根 `Box` 也设为 `hazeSource`（当前 `LazyColumn` 已是 Haze 源）。
- 返回/分享按钮继续用 `hazeEffect`。
- 这样在页面顶部，按钮会模糊背后的沉浸渐变背景本身，模糊效果始终存在。

**涉及文件**：
- `DetailScreen.kt`

---

### 2. 发现页“查看全部”弹窗里的卡片显示标题和副标题

**问题**：发现页横向列表中的影视卡片下方有标题和副标题（如“10570 人在看”），但点击“查看全部”后弹窗里的卡片网格只显示海报，缺少标题和副标题。

**方案**：
- `TmdbAllSheet`、`TraktMovieAllSheet`、`TraktShowAllSheet`、`TraktAnticipatedAllSheet`、`TrendingListsAllSheet` 中的卡片网格项改为显示：
  - 海报
  - 标题（最多 2 行省略）
  - 副标题（如 watchingCount / listCount / 年份等，一行省略）
- 复用 Watchlist 页 `WatchlistPosterCard` 或 `PosterCard` + 标题/副标题结构，保持风格一致。

**涉及文件**：
- `DiscoverSheets.kt`
- `DiscoverScreen.kt`（传入副标题数据）
- `DiscoverSections.kt`（复用已有卡片样式）

---

### 3. 设置页观看统计卡片去掉数字

**问题**：设置页顶部的“观看统计”入口卡片右侧显示了已看影片/剧集数量，用户希望去掉这个数字，只保留标题和描述。

**方案**：
- `StatisticsCard` 组件中移除右侧 `count.toString()` 的 `headlineMedium` 数字。
- 保留左侧图标、标题、描述和右侧箭头。
- `SettingsScreen` 调用处可继续传 `count`，组件内部不再显示。

**涉及文件**：
- `SettingsComponents.kt`
- `SettingsScreen.kt`（可选，检查是否还需要 count）

---

### 4. 详情页已看按钮文案改为“已看 / 已看过”

**问题**：当前详情页已看按钮文案是“标记已看”，已看状态是“已看过”；用户希望未看时显示“已看”，已看时显示“已看过”。

**方案**：
- 修改中文字符串资源：
  - `detail_mark_watched` → `已看`
  - `detail_marked_watched` → `已看过`
- 其他语言保持自然对应：
  - 英文：`Mark as Watched` / `Watched`
  - 日文：`視聴済みにする` / `視聴済み`
  - 韩文：`시청한 것으로 표시` / `시청함`

**涉及文件**：
- `values-zh/strings.xml`
- `values/strings.xml`
- `values-ja/strings.xml`
- `values-ko/strings.xml`

---

### 5. 演职员详情页沉浸色方案改为 master 影视详情页同款

**问题**：`PersonScreen` 当前使用固定高度 220dp 的“头像主色 → 透明”局部渐变，且进入页面时头像主色尚未提取，沉浸色要等图片加载后才出现。

**方案**：
- 把 `PersonScreen` 的沉浸渐变改为和 `DetailScreen` master 设计一致：
  - 整页背景渐变：`avatarDominantColor.copy(alpha = 0.70f)` → `MaterialTheme.colorScheme.background`
  - 渐变覆盖整个 `Box`，不再固定高度。
- 进入页面时，如果 `profileUrl` 不为空，立即用 Coil 加载该头像并提取主色调（利用详情页已有的内存/磁盘缓存，通常能瞬间命中）。
- 主色提取完成后更新 `uiState.avatarDominantColor`，渐变立即渲染。
- 如果 `profileUrl` 为空或加载失败，暂时不渲染渐变（fallback 为背景色），等 API 返回后再提取。

**涉及文件**：
- `PersonScreen.kt`
- `PersonViewModel.kt`（新增立即提取主色的逻辑）

---

### 6. 网盘搜索资源结果卡片重新设计

**问题**：`ResourceItemCard` 当前使用普通 surfaceVariant 卡片，风格偏旧，未体现“现代影视 App 沉浸风格”。

**方案**：
- 卡片背景使用网盘品牌色做微弱渐变：
  - 起点：`style.backgroundColor.copy(alpha = 0.12f)`
  - 终点：`style.backgroundColor.copy(alpha = 0.02f)`
- 保留圆角（16dp）和轻微阴影。
- 第一行继续显示：序号、网盘类型标签、来源标签、已查看标签、日期。
- 第二行显示文件数（>1 时）。
- 第三行显示资源名称（最多 2 行省略）。
- 调整文字层级：资源名称字号略大、加粗；日期和元信息保持 caption 大小。

**涉及文件**：
- `ResourceItemCard.kt`

---

### 7. 豆瓣失败项和豆瓣条目详情页统一新设计风格

**问题**：
- `DoubanFailuresScreen.kt` 中的影视卡片风格与全局不一致。
- `DoubanItemDetailScreen.kt` 中的各种按钮未使用详情页 ActionButtonRow 风格。
- `DoubanItemDetailScreen.kt` 中的资源结果卡片未使用新的沉浸风格。

**方案**：
- `DoubanFailuresScreen.kt`：
  - 影视卡片改用 Watchlist 页 `WatchlistPosterCard` / `PosterCard` 风格。
  - 保留原有的长按多选、选中状态等功能。
- `DoubanItemDetailScreen.kt`：
  - 想看/已看/取消等按钮改用 `ActionButtonRow` + `ActionItem` 风格。
  - 资源结果卡片改用新的 `ResourceItemCard` 沉浸风格。

**涉及文件**：
- `DoubanFailuresScreen.kt`
- `DoubanItemDetailScreen.kt`

---

## 设计原则

- **一致性**：所有影视卡片、按钮、资源卡片在视觉语言上保持统一。
- **沉浸感**：详情页和演职员详情页使用整页海报/头像主色渐变。
- **可读性**：卡片标题、副标题、状态标签层级清晰。
- **渐进增强**：品牌色渐变在暗色/浅色模式下自动适配，保持低饱和度不刺眼。

---

---

## 新增调整（用户二次反馈）

### 8. 豆瓣失败项卡片与 Watchlist 卡片完全统一

**问题**：`DoubanFailuresScreen.kt` 的 `FailureCard` 虽已参考 `MovieCard` 风格，但海报实现、角标样式与 Watchlist 页仍存在差异，且标题/副标题间距偏大。

**方案**：
- `FailureCard` 完全复用 `MovieCard`/`WatchlistPosterCard` 的卡片结构：
  - 海报区域改用 `PosterCard` 组件。
  - 用户评分/豆瓣评分角标、标题、副标题全部与 Watchlist 卡片风格统一。
  - 底部标题与副标题间距从当前 `2.dp` 收紧。
- 保留长按多选、点击跳转等功能。

**涉及文件**：
- `DoubanFailuresScreen.kt`

### 9. Watchlist 已看列表卡片图标改为眼睛

**问题**：Watchlist 已看列表中，卡片左上角已看角标当前使用 `CheckCircle`，与详情页/发现页统一使用的眼睛图标不一致。

**方案**：
- `WatchlistPosterCard` 的已看角标图标从 `Icons.Rounded.CheckCircle` 改为 `Icons.Rounded.Visibility`。

**涉及文件**：
- `WatchlistScreen.kt`

### 10. Watchlist 搜索框占位文字与光标垂直对齐

**问题**：`GlassSearchBar` 中 `BasicTextField` 的 placeholder 和实际输入光标不在同一垂直高度。

**方案**：
- 将 placeholder 与 `innerTextField()` 共同包裹在 `Box(Alignment.CenterStart)` 中，确保 placeholder 和输入文本/光标在同一基线。

**涉及文件**：
- `GlassSearchBar.kt`

### 11. 详情页 Tab 栏底色方案与豆瓣失败详情页一致

**问题**：`DetailScreen` 的 Tab 栏和状态栏区域在吸顶时使用 Haze 透明效果，而 `DoubanItemDetailScreen` 使用海报主色与背景混合的实色，并能改变状态栏底色。

**方案**：
- `DetailScreen` 吸顶时状态栏背景和 Tab 栏背景改用与 `DoubanItemDetailScreen` 相同的实色方案：
  - 状态栏区域：`tabContainerColor` 实色背景。
  - Tab 栏：`PrimaryTabRow(containerColor = tabContainerColor)`。
  - 文字颜色根据 `tabContainerColor` 亮度自适应。
- 移除吸顶时状态栏/Tab 栏的 Haze 效果，改为统一实色。

**涉及文件**：
- `DetailScreen.kt`

### 12. 资源 Tab 文字统一并显示数量

**问题**：详情页和豆瓣失败详情页的资源 Tab 文字不统一，且资源列表顶部单独显示“找到 xx 个资源”，造成信息重复。

**方案**：
- `DetailScreen` 和 `DoubanItemDetailScreen` 的资源 Tab 文字统一为 `资源(N)`（N 为当前资源数量）。
- 删除资源列表顶部的“找到 xx 个资源”提示及其字符串资源引用。

**涉及文件**：
- `DetailScreen.kt`
- `DoubanItemDetailScreen.kt`

---

## 验收标准

- [ ] 详情页返回/分享按钮在页面顶部和滚动后都有可见的 Haze 模糊。
- [ ] 发现页所有“查看全部”弹窗的卡片下方都显示标题 + 副标题。
- [ ] 设置页统计卡片不再显示右侧数字。
- [ ] 详情页已看按钮未看时显示“已看”，已看时显示“已看过”。
- [ ] 演职员详情页进入时即渲染头像主色沉浸渐变（缓存命中情况下无闪烁）。
- [ ] 网盘资源卡片显示品牌色微弱渐变背景。
- [ ] 豆瓣失败项影视卡片和豆瓣条目详情按钮/资源卡片与全局新风格一致。
- [ ] 豆瓣失败项卡片所有元素与 Watchlist 卡片统一，且标题/副标题间距更紧凑。
- [ ] Watchlist 已看列表卡片已看角标使用眼睛图标。
- [ ] Watchlist 搜索框占位文字与光标垂直对齐。
- [ ] 详情页 Tab 栏吸顶时状态栏和 Tab 栏使用海报主色混合实色。
- [ ] 资源 Tab 显示 `资源(N)` 且列表顶部不再显示“找到 xx 个资源”。
- [ ] Debug 构建成功，无新增编译错误。
