# 设计文档：帮助页面 + 新手引导 + 状态栏回顶 + 设置页精简

## 1. 帮助页面（设置 → 帮助）

### 位置
设置页"关于"分组中新增"帮助与说明"项，点击进入独立帮助页面。

### 布局
分组列表式（ExpandableColumn），默认展开第一组，其余折叠。

### 分组内容
1. **搜索功能** — 输入关键词搜索网盘资源；支持夸克/百度/阿里/迅雷/UC/115；点击网盘图标跳转对应App；搜索历史点击快速搜索，长按删除
2. **想看与已看** — 详情页标记想看/已看；我的页面管理列表；标记已看支持按季勾选集数
3. **观看统计** — 查看观影数据统计和热力图；电视剧部数需看完整季才计入
4. **通知提醒** — 开启后自动提醒新上映/新季；需开启通知权限
5. **数据管理** — 导出JSON/CSV；导入Letterboxd/IMDb评分
6. **其他技巧** — 点击状态栏快速回顶；评分支持半星；详情页展开全部季信息；发现页栏目可自定义排序

### 技术实现
- 新增 `HelpScreen.kt`，使用 LazyColumn + AnimatedVisibility 实现展开/折叠
- 帮助文本硬编码在 string resources 中（不依赖网络）
- 导航路由：`help`

## 2. 新手引导（逐步气泡 Coach Marks）

### 触发条件
首次启动 app 且未完成引导时自动触发。DataStore 存储布尔值 `onboarding_completed`。

### 引导步骤（5步）
1. 高亮搜索Tab → 气泡："搜索影视资源，支持多网盘源"
2. 高亮发现Tab → 气泡："浏览豆瓣/TMDB热门榜单"
3. 高亮我的Tab → 气泡："登录后管理想看/已看列表"
4. 高亮设置Tab → 气泡："自定义主题、搜索源等"
5. 高亮搜索框 → 气泡："试试搜索一个影视名称吧！"

### UI 设计
- 半透明黑色遮罩覆盖全屏（alpha 0.6）
- 目标元素通过 `Modifier.offset` 或布局计算高亮区域，遮罩挖洞（Compose `Canvas` + `drawPath` XOR）
- 气泡卡片：圆角12dp，主题色背景，白色文字，底部小三角指向目标
- 底部按钮："下一步" / "跳过引导"
- 步骤指示器："2/5"

### 技术实现
- 新增 `OnboardingOverlay.kt` Composable
- 在 `MainScreen.kt` 中根据 `onboardingCompleted` 状态条件显示
- 使用 `Layout` 或 `SubcomposeLayout` 获取目标元素位置
- `DataStore` 存储完成状态

## 3. 点击状态栏快速回顶

### 实现方式
在 `MainActivity` 中重写 `onWindowFocusChanged` 或使用 `View.OnClickListener` 监听状态栏点击，通过共享的回调接口通知当前活跃页面滚动到顶部。

### 技术方案
- `MainActivity` 中获取 `decorView`，给 `statusBarBackground` 设置点击监听
- 使用 `CompositionLocal` 提供 `scrollToTop` 回调
- 各页面在 `LaunchedEffect` 中注册/注销自己的 `scrollToTop` 实现
- 当前活跃页面的 `scrollToTop` 被调用时，执行 `lazyListState.animateScrollToItem(0)`

### 适用页面
搜索、发现、我的、设置、详情、Trakt搜索、统计、搜索结果页等所有包含可滚动列表的页面

## 4. 设置页精简

### 变更
- 删除"GitHub"仓库链接项（`settings_source_github`）
- "Gitee"项改名为"源代码仓库"（`settings_source_repo`），保留 Gitee 公开仓库链接
- 在"关于"分组中新增"帮助与说明"项（`settings_help`），点击导航到帮助页面

### 修改文件
- `SettingsScreen.kt`：删除 GitHub item，Gitee item 改标题，新增帮助 item
- `strings.xml`：新增 `settings_help`、`settings_source_repo` 字符串，可删除 `settings_source_github`
