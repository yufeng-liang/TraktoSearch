# UI 细节打磨设计文档

## 背景
当前 UI 改造测试分支在浅色模式下及整体一致性上仍存在细节问题，需要基于用户反馈进行一轮精准打磨。

## 目标
在不改变整体风格方向的前提下，修复 9 类 UI 细节问题，统一图标、文字、位置与交互体验。

## 改动清单

### 1. 搜索页：只保留带类型切换的搜索框
- 移除顶部「输入片名搜索网盘资源」的 `GlassSearchBar`。
- 保留带「电影/剧集/人物/网盘」类型切换的 `SearchBarTop`。
- 为 `SearchBarTop` 增加边框，使其在浅色背景下不融入白色。

### 2. 底部导航：恢复 master 旧设计
- 恢复 master 分支的悬浮药丸式底部导航。
- 特征：宽度约 80%、圆角 28dp、带边框和阴影、滑动指示器、头像「我的」Tab。
- 移除当前全宽圆角大导航条 `AppBottomBar`。

### 3. 影视卡片年份统一在右下角
- `MovieCard` 与 `PosterCard` 的年份文字统一放在海报右下角。
- 年份为 `0`、`"0"`、`""` 或 `null` 时完全不显示。

### 4. 想看/已看标记：图标与文字统一
- 文字：「已想看」→「想看」，「已看过」→「已看」。
- 图标：
  - 想看：`Bookmark`（已标记）/ `BookmarkBorder`（未标记）。
  - 已看：`Visibility`（已标记）/ `VisibilityOff`（未标记）。
- 发现页卡片只保留图标，去掉文字。
- 详情页已看按钮图标也统一为眼睛，与卡片一致。

### 5. 发现页社区热门列表下方「查看全部」删除
- 社区热门列表标题右侧已有 SectionHeader 的「查看全部」。
- 删除列表底部多余的「查看全部」文字按钮。

### 6. 发现页趋势电影 Sheet 增加今日/本周切换
- Sheet 标题由「热门电影」改为「趋势电影」。
- Sheet 顶部增加「今日 / 本周」切换胶囊。
- 切换时重新加载对应时间窗口的电影列表。

### 7. 所有 Sheet 关闭按钮改为叉号图标
- `TmdbAllSheet`、`TraktMovieAllSheet`、`TraktShowAllSheet`、`TraktAnticipatedAllSheet`、`TrendingListsAllSheet`、`DoubanHotAllSheet` 的右上角关闭文字统一改为 `Icons.Rounded.Close` 图标按钮。

### 8. 设置页标题栏使用 Haze 毛玻璃
- 参考发现页标题栏，为设置页顶部标题栏添加 Haze 毛玻璃背景效果。
- 保持标题文字和返回/操作按钮可见。

### 9. 详情页沉浸渐变恢复 master 设计
- 将当前「主色 → 透明」的局部渐变恢复为 master 的「海报主色 0.70 alpha → 背景色」的整页背景渐变。
- 渐变覆盖整个页面背景，营造沉浸式海报色调。

## 影响文件
- `SearchScreen.kt`
- `MainScreen.kt`
- `AppBottomBar.kt`（删除）
- `MovieCard.kt`
- `PosterCard.kt`
- `DiscoverScreen.kt`
- `DiscoverSections.kt`
- `DiscoverSheets.kt`
- `DetailScreen.kt`
- `DetailHeaderContent.kt`
- `DetailPosterOverlay.kt`
- `SettingsScreen.kt`
- `strings.xml`（多语言）

## 验收标准
- Debug 构建通过。
- 浅色模式下底部导航、搜索框、详情按钮、设置标题栏均清晰可见。
- 搜索页只有一个搜索框且带边框。
- 所有影视卡片年份右下角对齐，无年份不显示。
- 卡片与详情页想看/已看图标一致。
- 发现页 Sheet 关闭按钮为叉号，趋势电影 Sheet 可切换今日/本周。
