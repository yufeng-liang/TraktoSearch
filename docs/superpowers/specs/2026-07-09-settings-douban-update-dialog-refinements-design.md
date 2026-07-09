# 设置页 / 豆瓣同步 / 更新日志 / Watchlist / Sheet 多项优化设计

日期：2026-07-09

## 背景

用户提出 8 项相关需求，覆盖设置页交互、豆瓣同步冷却期、更新日志显示、登录后失败数据拉取、共享元素转场动画开关、Watchlist 筛选弹窗评分滑动条手势、Sheet 视觉统一、失败项资源搜索核验。其中需求 6、8 经核验现状已基本实现，需求 6 需针对"斜向拖动"场景增强拦截。

## 需求与设计

### 需求 1：「重新同步豆瓣」检查登录状态

**现状**：[SettingsScreen.kt:586-596](file:///f:/trae-project/app/src/main/java/com/tracktosearch/ui/screen/settings/SettingsScreen.kt#L586-L596) 点击直接弹 `DoubanSyncModePickerDialog`，UI 层不预检登录态；同步层兜底检查 phase="未登录豆瓣"。

**设计**：
- 点击「重新同步豆瓣」时读 `doubanAuthStorage.isLoggedIn`
- 未登录 → 弹 `AlertDialog`「未登录豆瓣，是否前往登录？」→ 确认跳转豆瓣登录页，取消留在设置页
- 已登录 → 正常弹模式选择对话框
- 新增 string 资源（4 语言）：标题、正文、确认按钮、取消按钮

### 需求 2：增量同步冷却期显示（跨设备同步）

**现状**：`DoubanSyncMetaStorage.canSkipListCrawl()` 基于 `lastFullSyncAt` 判断 7 天冷却，但仅本地；CloudPersonalSyncManager 已同步 `sync_meta.json` 但未合并云端 `lastFullSyncAt`。

**设计**：
- **跨设备冷却期**：`canSkipListCrawl()` 取 `max(本地 lastFullSyncAt, 云端 lastFullSyncAt)`。CloudPersonalSyncManager 读写 `sync_meta.json` 时包含 `lastFullSyncAt` 字段；判断前先拉云端 meta 合并取最新
- **设置页卡片**：标题右侧右对齐显示「冷却中·剩X天」/「可同步」。新增 ViewModel 方法 `getCooldownStatus(): Flow<CooldownStatus>`
- **模式选择对话框**：「增量同步」选项标题右侧也显示冷却状态（传入参数）
- **冷却期内点击增量同步**：弹 `AlertDialog` 提示「7天内已同步，建议跳过」，提供「跳过」「强制同步」。强制同步给 `startSync` 传 `forceCrawl=true` 跳过 `canSkipListCrawl`
- 新增 string 资源（4 语言）：冷却中·剩X天、可同步、冷却提示标题/正文/跳过/强制同步

### 需求 3：更新日志移除重复标题 + 补全日期注入

**现状**：[UpdateDialog.kt:346](file:///f:/trae-project/app/src/main/java/com/tracktosearch/ui/component/UpdateDialog.kt#L346) AlertDialog 标题「发现新版本 vX.X.X」与滚动区 stickyHeader「vX.X.X 更新内容」重复；`checkForUpdate()` 路径未注入日期导致日期不显示。

**设计**：
- `UpdateDialog.kt:346` 的 `title` 改为 `null`，只保留 stickyHeader 作为唯一标题
- `UpdateRepository.checkForUpdate()` 路径复用 `formatAllChangelogs` 的日期注入逻辑，把 release 的 `created_at` 解析为 `yyyy-MM-dd` 追加到 section 标题末尾「（日期）」
- `appendToFullChangelog()` 追加最新版时也补上日期

### 需求 4：登录后拉取失败数据 + 入口刷新

**现状**：`onLoginSuccess` → `checkCloudFailures` → 弹窗 → 下载，逻辑已自动触发。

**设计**：
- 现状逻辑保留不动
- **入口刷新**：`SettingsScreen` 监听 `doubanAuthStorage.isLoggedIn` 变化，登录态从 false→true 时调用 `doubanRetryViewModel.refreshRetryState()`，确保云端失败数据下载完成后 `hasFailures` 立即更新，「查看同步失败项」入口卡片及时显示
- 下载完成后触发一次 `refreshRetryState()`（在 `downloadCloudFailures` 完成回调里）

### 需求 5：共享元素转场动画开关（默认关闭）

**现状**：转场动画默认开启，`SharedTransitionLayout` 在 AppNavigation 全局包裹；设置页 scrollGate 机制专为 SharedTransition 幽灵 scroll 设计。

**设计**：
- **新增 Local**：`SharedTransitionLocals.kt` 加 `LocalSharedTransitionEnabled`（默认 false）
- **存储**：`SettingsViewModel.sharedTransitionEnabled` StateFlow + DataStore，默认 false
- **AppNavigation**：`SharedTransitionLayout` 外层 `CompositionLocalProvider(LocalSharedTransitionEnabled provides viewModel.sharedTransitionEnabled)`
- **各使用点**：所有 `sharedElement`/`sharedBounds` 判空条件加 `&& LocalSharedTransitionEnabled.current`，关闭时不附加修饰符
- **scrollGate 联动**：`sharedTransitionEnabled == false` 时移除 scrollGate 延迟开闸和 `rememberSaveable` 持久化，`settingsListState` 用普通 `rememberLazyListState()`，返回时回顶部；开启时恢复完整保存机制
- **开关卡片**：外观分组 2×2 网格下方新增独立 item，用 `NotificationItem` 样式（Surface+Row+Switch）
- 新增 string 资源（4 语言）：标题、副标题

### 需求 6：Watchlist 评分滑动条斜向拖动拦截

**现状**：[WatchlistScreen.kt:1191-1216](file:///f:/trae-project/app/src/main/java/com/tracktosearch/ui/screen/watchlist/WatchlistScreen.kt#L1191-L1216) 已用 `NestedScrollConnection` 消费竖直分量，但斜向拖动时 sheet 仍移动。

**根因分析**：`NestedScrollConnection` 只拦截嵌套滚动事件，但 RangeSlider 的滑块拖动是 `pointerInput` 级别的拖拽手势，斜向拖动时父级 ModalBottomSheet 的 `anchoredDraggable` 可能先消费了竖直分量。

**设计**：
- 在包裹 RangeSlider 的 Box 上增加 `Modifier.pointerInput` 拦截：检测拖动事件，若竖直位移分量 > 水平分量的一定比例（如 0.5），消费竖直位移，阻止向 sheet 冒泡
- 或更简单方案：给 RangeSlider 的父 Box 加 `Modifier.scrollBlocking()`
- 实施时优先尝试在 Box 上加 `pointerInput` 消费竖直 drag 事件

### 需求 7：Sheet 背景色统一

**现状**：发现页 5 个 sheet 用 `surfaceVariant`；WatchlistFilterSheet、FailureFilterSheet 用默认 `surface`。

**设计**：
- 给 `WatchlistFilterSheet`（[WatchlistScreen.kt:1088](file:///f:/trae-project/app/src/main/java/com/tracktosearch/ui/screen/watchlist/WatchlistScreen.kt#L1088)）和 `FailureFilterSheet`（[DoubanFailuresScreen.kt:737](file:///f:/trae-project/app/src/main/java/com/tracktosearch/ui/screen/douban/DoubanFailuresScreen.kt#L737)）的 `ModalBottomSheet` 加 `containerColor = MaterialTheme.colorScheme.surfaceVariant`
- 不动 `contentWindowInsets` 和 `navBarHeight` padding

### 需求 8：失败项四次搜索核验

**现状**：[DoubanItemDetailScreen.kt:303-318](file:///f:/trae-project/app/src/main/java/com/tracktosearch/ui/screen/douban/DoubanItemDetailScreen.kt#L303-L318) 已实现 4 关键词搜索。

**核验点**：
- `searchWithSubtitle` 默认值：确保 subtitle 非空时默认 true
- `removeSeasonInfo` 正则覆盖：中文「第X季」、英文「Season N」、是否覆盖「S2」「第2部」等变体
- 必要时补强正则

## 实现顺序

1. 需求 7（最简单，2 行）
2. 需求 3（更新日志标题+日期）
3. 需求 1（重新同步豆瓣检查登录）
4. 需求 4（登录后入口刷新）
5. 需求 8（核验四次搜索）
6. 需求 6（评分滑动条斜向拦截）
7. 需求 2（冷却期跨设备同步，较复杂）
8. 需求 5（转场动画开关，影响面最大）

## 验证

- 每个 UI 改动构建 debug 包验证
- 需求 2 跨设备冷却期需构造测试场景（两设备/清本地数据后拉云端）
- 需求 5 关闭/开启转场动画分别验证设置页 scrollGate 行为
