# 筛选页/搜索页/演职员沉浸色/云端数据仓库分离 设计规格

> 目标：修复筛选页视觉问题、为筛选列表卡片加入沉浸色、统一卡片动画、重构搜索页交互、预加载演职员沉浸色、将豆瓣云端数据从源码仓库分离到独立仓库。

---

## A. 筛选页

### A1. GlassFilterChip 修复

**问题**：浅色模式下 chip 出现白色矩形，选中后文字看不清。

**根因**：`GlassFilterChip` 在 `hazeEffect` 之上又叠加了半透明背景，浅色模式下 haze 与背景混合产生白色块；选中状态的文字色被 haze 色调干扰。

**方案**：
- 移除 `GlassFilterChip` 的 `hazeEffect`（保留给吸顶栏等真正需要毛玻璃的区域）。
- 未选中背景：`MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)`。
- 选中背景：`MaterialTheme.colorScheme.primary`。
- 文字色始终使用 `onPrimary`/`onSurface`，确保对比度。
- 圆角保持 `16.dp`，padding 保持 `horizontal=12.dp, vertical=6.dp`。

### A2. 列表卡片沉浸风格

**问题**：`DiscoverFilterListItem` 当前为浅灰横条，视觉平淡。

**方案**：
- 卡片背景改为以海报主色为主导的纵向渐变：
  - 顶部：`dominantColor.copy(alpha = 0.65f)`
  - 底部：`MaterialTheme.colorScheme.surface`
- 海报继续放在左侧，宽度保持 80dp，圆角 8dp。
- 文字颜色根据 `dominantColor` 亮度自动选择：
  - 亮色主色 → 深色文字（`onSurface` 或近黑）
  - 暗色主色 → 白色文字
- 主色提取走 `PosterColorExtractor`，在 Coil 海报加载成功后的 `listener` 中异步提取并缓存；列表项复用时优先读缓存。
- 列表进入动画统一使用 `fadeSlideIn`。

---

## B. 卡片/列表动画一致性

**检查范围**：
- `MovieCard`（首页/发现页/Watchlist）
- `PosterCard`
- `WatchlistPosterCard`
- `FailureCard`（豆瓣失败项）
- `DiscoverFilterListItem`（筛选列表）
- `CastCard` / `FullCastItem`（演职员）
- `ResourceItemCard`（网盘资源）
- `PersonCreditCard`（人物作品）

**统一标准**：
- 可点击卡片统一使用 `combinedClickable` + `interactionSource.collectIsPressedAsState()` + `animateFloatAsState(scale=0.96f)`。
- 列表项统一使用 `fadeSlideIn` 进入动画，参数一致（duration 250ms, offset Y 24dp）。
- 状态切换（已看/想看角标）使用 `Crossfade` 或 `AnimatedVisibility`。
- 同类卡片动画参数必须相同，避免有的有动画有的没有。

---

## C. 搜索页重构

### C1. 搜索框位置与焦点行为

**行为**：
- 页面刚打开时：搜索框位于屏幕垂直中心偏上 30dp 处；顶部标题/副标题/云朵彩蛋隐藏。
- 搜索框获得焦点后：搜索框上移到顶部（标题下方固定位置）；同时顶部标题/副标题/云朵彩蛋淡入显示。
- 失去焦点且搜索框为空时：搜索框回到中心偏上位置，顶部区域淡出。
- 标题/副标题/云朵在搜索框上移过程中**不跟随移动**，仅在搜索框到达顶部后淡入。

**实现**：
- 使用 `animateDpAsState` 控制搜索框 `Modifier.offset`。
- 使用 `AnimatedVisibility` 控制顶部区域显隐。
- 监听 `BasicTextField.onFocusChanged` 驱动状态变化。

### C2. 搜索历史 + 热门搜索 UI 重设计

**搜索历史**：
- 标题行：左侧「搜索历史」，右侧「清空全部」文字按钮。
- 内容：横向可滚动 `LazyRow`，每个历史项为圆角 chip。
- chip 内布局：左侧时钟图标 + 类型标签（电影/剧集/人物/网盘）+ 关键词 + 右侧删除 X 按钮。
- chip 背景使用 `surfaceVariant`，文字 `onSurface`，类型标签使用 `primary`。

**热门搜索**：
- 标题行：左侧「热门搜索」。
- 内容：`FlowRow` 胶囊网格，每行根据宽度自动换行。
- 每个热门项：搜索图标 + 关键词，圆角胶囊，背景 `surfaceVariant`。

---

## D. 演职员沉浸色预加载

**当前问题**：进入 `PersonScreen` 后，`PersonViewModel.prefetchAvatarColor()` 才从缓存/网络取色，即使头像在上一级页面已经加载过，也需要重新等待。

**优化方案**：
- 复用已有的 `PosterColorCache`（持久化缓存）。
- 在头像已经加载的前置页面，Coil 加载成功后立即提取主色并写入缓存：
  - `DetailCrewSection.CastCard`
  - Trakt 搜索人物结果列表的 Avatar 组件
- `PersonScreen` 进入时：
  1. 先读缓存，命中则立即设置 `avatarDominantColor`。
  2. 未命中再走 `prefetchAvatarColor` 的 Coil 加载流程。
- 这样从影视详情页或搜索人物页点击进入时，沉浸色大概率已预加载。

---

## E. 豆瓣云端数据仓库分离

**目标**：将豆瓣云端数据从 `yufeng-liang/TrackToSearch` 源码仓库迁移到 `yufeng-liang/meta-data`，避免源码推送时被数据变更要求先 pull。

### E1. 代码层变更

- `CloudDetailsPoolManager`：
  - `OWNER` 不变：`yufeng-liang`
  - `REPO` 从 `TrackToSearch` 改为 `meta-data`
  - `PATH_PREFIX` 保持 `details_pool/`
- `CloudFailureSyncManager`：
  - `OWNER` 不变：`yufeng-liang`
  - `REPO` 从 `TrackToSearch` 改为 `meta-data`
  - `PATH_PREFIX` 保持 `failures/`

### E2. 已有数据迁移

- 需要把 `yufeng-liang/TrackToSearch` 仓库中的 `details_pool/` 和 `failures/` 目录下文件复制到 `yufeng-liang/meta-data` 仓库的相同路径。
- 迁移可通过 Gitee API + access token 完成，也可以手动在 Gitee 网页操作。
- 迁移完成后，旧仓库中的对应文件可删除（避免未来再次冲突）。

### E3. 验证

- 打开 App 触发一次详情池上传/下载或失败项同步，确认请求指向 `yufeng-liang/meta-data`。
- 检查 Gitee `meta-data` 仓库是否有新提交。
- 源码仓库 `TrackToSearch` 后续推送不再因数据文件变更要求 pull。

---

## 验收标准

- [ ] 筛选页 chip 无白色矩形，选中/未选中文字均清晰可读。
- [ ] 筛选页列表卡片使用海报主色渐变背景，文字自适应深浅。
- [ ] 所有卡片/列表项动画一致（按压缩放、进入动画）。
- [ ] 搜索框初始位于屏幕中心偏上 30dp，获取焦点后上移到顶部；顶部标题/副标题/彩蛋在搜索框到达后淡入。
- [ ] 搜索历史改为横向可滚动 chip，热门搜索改为胶囊网格。
- [ ] 从影视详情页演职员头像或 Trakt 搜索人物头像进入 PersonScreen 时，沉浸色优先从缓存立即生效。
- [ ] 豆瓣云端数据（`details_pool/`、`failures/`）指向 `yufeng-liang/meta-data`。
- [ ] Debug 构建成功，无新增编译错误。
