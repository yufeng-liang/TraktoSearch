# 设计规格：UI 一致性打磨 + 观看统计短评词云

日期：2026-07-13
状态：已批准（待实现计划）

## 背景与目标

对多个页面的标题字号字重、卡片视觉样式做统一打磨，并在观看统计新增「短评词云图」。
词云数据来自用户自己的评分短评——当前这些短评只同步到 Trakt/豆瓣、**不落盘**，因此本规格包含
新增本地磁盘缓存（Room），并让详情页优先读缓存、避免重复网络请求。

---

## 1. 标题字号字重统一（发现页 / 设置页 → 搜索页顶部）

**基准**：搜索页顶部标题 `search_title` 使用 `MaterialTheme.typography.headlineSmall` + `FontWeight.Bold`
（见 `SearchScreen.kt:313-318`）。

改动：
- 发现页标题栏标题（`DiscoverScreen.kt:652-655`，当前 `titleLarge`+Bold）→ 改为 `headlineSmall`+Bold。
- 设置页标题（`SettingsScreen.kt:885-890`，当前 `titleLarge`+Bold）→ 改为 `headlineSmall`+Bold。
- 搜索页顶部文案：字符串 `search_title`「搜索资源」→「搜索」，四语言同步
  （`values/values-zh/values-ja/values-ko` 的 `search_title`）。

验收：三处标题视觉字号字重一致；搜索页顶部显示「搜索」。

---

## 2. Watchlist 想看/已看切换条

当前实现：`WatchlistScreen.kt` 顶部 Row（搜索框 + 胶囊 + 筛选按钮），
胶囊 `RoundedCornerShape(28.dp)`，背景 `surfaceVariant.copy(alpha = 0.3f)`（`WatchlistScreen.kt:736`、`PersonScreen` 同源模式）。
搜索框宽度经计算预留胶囊空间（`searchBoxWidth`，`WatchlistScreen.kt:671`）。

改动：
- 胶囊圆角 `28.dp` → `14.dp`（`WatchlistScreen.kt:736` 及指示块 `744`）。
- **右对齐**：Row 改为搜索框 `Modifier.weight(1f)` 占满左侧，胶囊与筛选按钮保持靠右；
  移除 `searchBoxWidth` 中对胶囊宽度的预留计算（或改为 `fillMaxWidth` 由 weight 决定）。
- 未选中胶囊填充加深：`surfaceVariant.copy(alpha = 0.3f)` → 更明显的填充
  （建议 `surfaceVariant` 不透明，或 `@0.6`；白底下与背景区分即可）。
- 搜索框底部填充色加深：GlassSearchBar 背景 alpha 提高（当前 `surfaceVariant.copy(alpha = 0.3f)`，
  见 `GlassSearchBar.kt`），白底下与背景区分。

验收：胶囊圆角明显变小；切换条整体靠右；白主题下未选中胶囊与搜索框清晰可辨、不融背景。

---

## 3. 设置页卡片边框 + 阴影

当前：`SettingsGroupCard` / `SettingsCard`（见 `SettingsComponents.kt`）多为 `Surface`/`Card` 无边框无阴影。

改动：为设置页分组卡片与子卡片加
- `outlineVariant` 细边框（`BorderStroke(1.dp, outlineVariant.copy(alpha = …))`）
- 轻量阴影（Material3 `Card` 的 `elevation` 或 `Modifier.shadow(...)`）

样式与第 6 节统计卡片保持一致。

验收：设置页各卡片有明显边框与轻微投影，暗/亮主题均协调。

---

## 4. 通知类卡片标题字重 = 数据管理类卡片标题字重

当前：通知组用 `NotificationItem`（`SettingsScreen.kt:591`），数据管理组用 `DataFlowGridItem` 等
（`SettingsScreen.kt:627` 起）。两类卡片标题当前字重不一致（需实现时核查具体组件与 `fontWeight`）。

改动：将通知类卡片标题字重对齐到数据管理类卡片标题字重（预期 `titleMedium`+Bold）。
统一使用同一标题样式组件，避免两处各自硬编码。

验收：设置页「通知提醒」组与「数据管理」组卡片主标题字重一致。

---

## 5. 观看统计：短评词云图（含本地磁盘缓存）

### 5.1 新增本地磁盘缓存（Room）

- 新实体 `UserReviewEntity`：`traktId: Int`、`mediaType: String`（movie/show）、
  `rating: Int?`、`comment: String?`、`updatedAt: Long`。
- 注册到 `AppDatabase.kt` 的 `entities`（与现有 `MediaItemEntity` 等并列）。
- 新增 DAO `UserReviewDao`：`upsert`、`getAll()`、`get(traktId, mediaType)`、`delete`。
- 通过 Hilt 暴露 Repository/DataSource 访问（沿用现有 `OfflineCacheManager` 或新建轻量 DAO 封装）。

### 5.2 详情页：优先读缓存、更新时写缓存

- `DetailViewModel.setRatingWithComment(...)`（`DetailViewModel.kt:1011`）：
  提交成功后**同时写入本地 `UserReviewEntity`**（rating + comment），再同步 Trakt/豆瓣（现有逻辑不变）。
- `DetailViewModel.setRating(...)`（`DetailViewModel.kt:970`）：更新 rating 时同步更新本地缓存。
- 进入详情页加载评分/短评（`fetchUserRating`，`DetailViewModel.kt:952`）：
  **优先读本地 `UserReviewEntity`**；仅当本地未命中时才回源 Trakt（`getUserRating` + 短评），
  并将结果写回本地缓存，供回显与统计复用。
- 这避免了每次进入详情页都重复请求评分/短评。

### 5.3 统计页：词云数据来源

- `StatisticsViewModel`：从本地 `UserReviewEntity.getAll()` 收集全部 `comment` 文本
  （不再额外请求网络），与现有 ratings 数据并列。
- 若本地无短评，词云区域显示空态提示（如「还没有写短评」）。

### 5.4 中文分词库

- 引入 **jieba-analysis**（`com.huaban:jieba-analysis`，纯 Java、Android 友好、零 native），
  经 `libs.versions.toml` 统一管理依赖（实现时确认可用版本，若不可用则回退到等价纯 Java 分词库）。
- 分词：对每条短评做分词 + 去停用词，统计词频。
- 词云组件 `WordCloud`（Canvas 绘制）：字号随词频缩放，颜色取主题 `primary`/`secondary` 等，
  避免重叠的简易布局（按词频降序、螺旋/网格排布）。

### 5.5 新增统计卡片

- 在 `StatisticsScreen.kt` 现有 `SectionCard` 体系中新增「短评词云」卡片
  （`stringResource(R.string.statistics_wordcloud)`，四语言补充），置于评分统计附近。

验收：用户在某影视写短评并提交 → 进入详情页不再重复请求 → 观看统计页出现该短评分词词云；
离线也可显示（数据来自本地库）。

---

## 6. 观看统计各卡片边框 + 阴影

- `StatisticsScreen.kt` 的 `SectionCard` 等卡片加与第 3 节一致的 `outlineVariant` 边框 + 轻量阴影。
- 含新增的词云卡片。

验收：统计页各卡片视觉与设置页统一（边框 + 轻微投影）。

---

## 实现顺序建议

1. 第 1 节（标题字重）+ 第 4 节（通知/数据管理标题字重）——纯样式，风险低。
2. 第 2 节（Watchlist 胶囊）——样式 + 布局。
3. 第 3、6 节（卡片边框阴影）——通用样式，可抽公共。
4. 第 5 节（磁盘缓存 + 词云）——核心功能，依赖 Room 实体、jieba 依赖、DetailViewModel/StatisticsViewModel 改动。

## 风险与注意

- jieba-analysis 依赖需验证可解析、包体积可接受；否则回退等效库。
- 本地评分缓存可能与 Trakt 远端在其他设备产生的变更不一致——按本 App 既有缓存哲学（进入优先缓存、
  必要时回源/手动刷新），可接受。
- 所有用户可见字符串需在四语言同步（zh/en/ja/ko）。
