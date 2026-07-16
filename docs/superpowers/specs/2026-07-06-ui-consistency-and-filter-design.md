# UI 一致性改造与筛选增强设计

**日期**: 2026-07-06
**主题**: Watchlist/豆瓣失败项页/设置页 UI 一致性改造 + 筛选功能增强

## 一、背景与目标

v2.23.0 发布后,用户反馈多项 UI 一致性问题与功能缺失:

1. 豆瓣失败项相关页面(查看页/详情页)与正常详情页的视觉风格割裂(无 haze 毛玻璃、海报大图缺保存按钮、布局差异大)
2. Watchlist 页缺少筛选功能,统计入口位置不合理
3. 设置页导出/导入入口布局松散,缓存管理卡片交互不友好
4. Trakt 搜索框缺一键清空,与项目其他搜索框约定不一致
5. 豆瓣失败项查看页缺搜索/筛选能力
6. 网盘资源缓存第二次打开仍转圈(根因:`refreshResources` 误用)
7. IMDb/JSON 导入缺格式校验和错误反馈

## 二、设计概览

本设计分为 7 个独立模块,按优先级排序:

| 模块 | 改动范围 | 复杂度 |
|------|---------|--------|
| A. Watchlist 筛选增强 | WatchlistScreen + 新建 FilterSheet | 中 |
| B. 豆瓣失败项查看页改造 | DoubanFailuresScreen | 中 |
| C. 豆瓣失败项详情页改造 | DoubanItemDetailScreen + 复用 DetailPosterOverlay | 大 |
| D. 设置页布局改造 | SettingsScreen | 中 |
| E. 搜索框统一清空 | TraktSearchScreen + 全局检查 | 小 |
| F. 导入功能完善 | SettingsViewModel + DoubanFailureExporter | 中 |
| G. 缓存与边距修复 | DoubanItemDetailViewModel + DiscoverScreen | 小 |

## 三、模块 A: Watchlist 筛选增强

### A.1 入口调整

- **移除**: `WatchlistScreen` 顶部统计按钮(`Icons.Default.BarChart`,行 681-687)
- **新增**: 同位置换为筛选按钮(`Icons.Default.Tune`)
- **激活态**: 筛选条件生效时,图标变 `MaterialTheme.colorScheme.primary`
- **统计入口**: 移到 `SettingsScreen`(在"账户"section 上方新增"观看统计"入口)
- **导航**: `SettingsScreen.onStatisticsClick` → `navController.navigate(Routes.STATISTICS)`

### A.2 筛选维度

数据来源:`MediaUiItem` 已加载字段(无需新增网络请求)

| 维度 | 控件 | 选项 |
|------|------|------|
| 类型(genres) | 多选 FilterChip | 从当前列表所有 genres 聚合(逗号分隔字符串 split) |
| 年份 | 区间选择 | RangeSlider(从已有 year 字段聚合 min..max,步长 1) |
| 标记时间 | 排序 + 区间 | 升降序切换 + "最近 7 天/30 天/全部"预设 |
| Trakt 评分 | 范围滑块 | 0-10 双滑块(参考 DiscoverFilterScreen 行 412-450) |

### A.3 筛选 UI: ModalBottomSheet

点击筛选图标 → 从底部弹出 `ModalBottomSheet`(M3 标准),内含:

```
┌─────────────────────────┐
│  筛选                   │
├─────────────────────────┤
│ 类型                    │
│ [剧情] [动作] [科幻] ...│  ← FilterChip 流式排列
├─────────────────────────┤
│ 年份                    │
│ [2020 ── 2025]          │  ← RangeSlider
├─────────────────────────┤
│ 标记时间                │
│ [最近7天][30天][全部]   │  ← SingleChoiceChip
│ [↑升序] [↓降序]         │  ← SegmentedButton
├─────────────────────────┤
│ Trakt 评分              │
│ [4.0 ── 10.0]           │  ← RangeSlider 0-10 步长 0.5
├─────────────────────────┤
│ [重置]    [应用筛选]    │
└─────────────────────────┘
```

### A.4 筛选状态管理

- `WatchlistViewModel` 新增 `FilterState` 数据类:
  ```kotlin
  data class FilterState(
      val selectedGenres: Set<String> = emptySet(),
      val yearRange: IntRange = 1900..2100,
      val markedTimePreset: MarkedTimePreset = MarkedTimePreset.ALL,
      val markedTimeOrder: SortOrder = SortOrder.DESC,
      val ratingRange: ClosedFloatingPointRange<Float> = 0f..10f
  )
  ```
- 筛选逻辑在 `filteredMovies`/`filteredShows` 链路上叠加 filter 谓词
- 筛选条件非默认时,筛选图标变 primary 色

## 四、模块 B: 豆瓣失败项查看页改造

### B.1 Haze 模糊

参考 `WatchlistScreen` 行 234, 455, 546-553:

- `hazeState = remember { HazeState() }`
- `LazyVerticalGrid` 作为 `hazeSource`
- 顶部覆盖层 `Box` + `hazeEffect(style = HazeMaterials.thin())` + `surface.copy(alpha = 0.50f)` 兜底
- 顶部覆盖层内含:状态栏 Spacer + 标题栏(返回 + 标题 + 清空 + 搜索 + 筛选)+ ModeCapsuleToggle + PrimaryTabRow
- `PrimaryTabRow` 的 `containerColor = Color.Transparent`(让 haze 透出)

### B.2 标题栏右侧入口

- **新增**: "从 JSON 导入失败项"按钮(`Icons.Default.FileUpload`)放在标题栏右侧(actions),右对齐
- **保留**: 清空全部按钮(`Icons.Default.DeleteSweep`)
- **移除**: 设置页的"从 JSON 导入失败项"入口(避免重复)

### B.3 搜索框

- 标题栏下方新增搜索框(参考 WatchlistScreen 行 613-679 的 BasicTextField 实现)
- 搜索范围: `title` + `subtitle`(模糊匹配)
- 一键清空按钮(`Icons.Filled.Close`,有输入时显示)

### B.4 筛选功能

- **筛选按钮**: 标题栏右侧新增筛选图标(`Icons.Default.Tune`)
- **筛选 UI**: ModalBottomSheet(同模块 A 风格)
- **筛选维度**:
  - 失败原因(多选 FilterChip,5 种 FailureReason 枚举)
  - 标记时间(升降序 + 区间预设:最近 7 天/30 天/全部)
- **激活态**: 筛选条件生效时图标变 primary 色

## 五、模块 C: 豆瓣失败项详情页改造

### C.1 Haze 模糊

- 整个 `LazyColumn` 作为 `hazeSource`
- 标题栏覆盖层 `HazeMaterials.thin()` + `surface.copy(alpha = 0.50f)`
- 标题栏内含:返回按钮 + 标题(单行省略)
- 状态栏沉浸:`contentWindowInsets = WindowInsets(0, 0, 0, 0)` + `statusBarsPadding`

### C.2 头部布局重构

- **顶部边距**: 从 32dp 改为 `statusBarsPadding`(海报延伸到状态栏下,与正常详情页一致)
- **失败原因栏**: 从 header 之后的独立 banner,移到海报右侧、标记时间下方
- **对齐**: 失败原因栏底部与海报图底部对齐(用 `Row` + `Column` + `Spacer(weight = 1f)` + 失败原因)

布局示意:
```
┌─────────────────────────────────┐
│ [← 标题栏(haze)]               │
├─────────────────────────────────┤
│ statusBarsPadding               │
│ ┌────────┐  影视标题           │
│ │        │  2024 · 剧情         │
│ │ 海报图  │  标记时间:2024-01-15│
│ │ 120x180│  标记:想看 ★★★★    │
│ │        │  (Spacer weight)    │
│ │        │  ┌────────────────┐│
│ │        │  │⚠️ 详情页访问失败││  ← 底部与海报底部对齐
│ │        │  │已尝试 2 次      ││
│ └────────┘  └────────────────┘│
├─────────────────────────────────┤
│ 网盘资源 / 简介 / 演职员...      │
└─────────────────────────────────┘
```

### C.3 海报大图查看

- **复用**: `DetailPosterOverlay`(正常详情页的海报大图组件)
- **功能**: 关闭按钮 + 保存到相册按钮 + 已保存状态检测 + 缩放
- **移除**: 当前 `DoubanPosterOverlay`(仅关闭按钮的简化版)
- **调用方式**: `if (showPosterFullscreen && posterUrl != null) { PosterFullscreenOverlay(posterUrl, title) { showPosterFullscreen = false } }`

### C.4 网盘缓存修复

**根因**: `DoubanItemDetailViewModel.searchResources()` 调用了 `refreshResources()`(强制清缓存),而非 `searchResources()`(先查缓存)。

**修复(方案 B)**:
- 自动触发的搜索: `refreshResources` → `searchResources`(先查缓存,命中即用)
- 新增手动刷新入口: 标题栏右侧加刷新按钮,调用 `refreshResources`(清缓存重拉)
- **效果**: 第二次打开详情页,缓存命中(5 分钟内),`isSearching=false`,不转圈

## 六、模块 D: 设置页布局改造

### D.1 缓存管理卡片

- **整卡可点击展开**: `Row` 加 `clickable { expanded = !expanded }`,保留 IconButton(同步状态)
- **状态持久化**: `remember` → `rememberSaveable`(屏幕旋转后保持展开状态)

### D.2 数据流通 2x2 卡片

4 个入口改为 2x2 圆角卡片网格(`Row` + `Card` + `weight(1f)`):

| 位置 | 入口 | 图标 |
|------|------|------|
| 左上 | 导出标记数据(原"导出数据 JSON") | `Icons.Default.FileUpload` |
| 右上 | 从 IMDb 导入 | `Icons.Default.FileDownload` |
| 左下 | 上传到云端 | `Icons.Default.CloudUpload` |
| 右下 | 从云端拉取 | `Icons.Default.CloudDownload` |

- **卡片样式**: `Surface(shape = RoundedCornerShape(12.dp), color = surfaceVariant)` + 图标 + 标题 + 副标题
- **豆瓣同步入口保持垂直**: 重新同步豆瓣 / 重试失败项 / 查看同步失败项
- **移除**: 设置页的"从 JSON 导入失败项"入口(已移到失败项查看页标题栏)

### D.3 观看统计入口

- 在"账户"section 上方新增"观看统计"入口
- 图标: `Icons.Default.BarChart`
- 点击: `onStatisticsClick` → 导航到 StatisticsScreen

## 七、模块 E: 搜索框统一清空

### E.1 TraktSearchScreen 修复

- 添加一键清空按钮(参考 WatchlistScreen 行 654-666)
- 有输入时显示 `Icons.Filled.Close`(19dp),点击清空
- 无输入时显示 `Icons.Default.Search`

### E.2 豆瓣失败项查看页搜索框

- 同上风格实现(模块 B.3)

### E.3 项目约定

- 图标: `Icons.Filled.Close`(18-19dp)
- contentDescription: `R.string.content_desc_clear`
- 触发条件: `if (searchQuery.isNotEmpty())` 才显示清空按钮

## 八、模块 F: 导入功能完善

### F.1 IMDb 导入格式校验

- **必填列检查**: 除 `Title` 外,新增 `Created` 和 `Title Type` 列检查(任一缺失报错)
- **错误反馈**: 区分 3 种错误
  - `error_not_imdb_csv`: 缺少 IMDb 必填列
  - `error_empty_csv`: CSV 为空或无数据行
  - `error_parse_failed`: CSV 解析异常
- **UI 提示**: Snackbar 显示具体错误(对应字符串资源)

### F.2 JSON 导入失败项格式校验

- **版本号校验**: `version` 字段缺失或不在 `[1, 2]` 范围 → 报错
- **必填字段检查**: `failures` 数组每项必须有 `doubanId` + `title` + `failureReason`
- **错误反馈**: 返回 `ImportResult.Error(message)` 而非 `-1`
- **UI 提示**: Snackbar 显示具体错误
- **成功提示**: "已导入 N 条失败数据"(当前静默,无反馈)

### F.3 IMDb 导入后刷新 Watchlist

- `importFromImdb` 末尾在 `traktRepository.addToWatchlist` 调用后,同步更新 `WatchlistWatchedIds` 全局缓存(若该方法已存在则调用,若不存在则在 `TraktRepository.addToWatchlist` 内部触发 `WatchlistWatchedIds.add(traktId, mediaType)`)
- **效果**: 导入后切回 Watchlist 页,新条目立即可见,无需手动下拉刷新
- **验证**: 导入 1 条 IMDb 数据后立即切到 Watchlist 页,确认新条目出现在列表顶部

## 九、模块 G: 缓存与边距修复

### G.1 网盘缓存修复(见模块 C.4)

### G.2 发现页底部边距

- `DiscoverScreen.kt` 行 194: `bottom = 80.dp` → `bottom = 112.dp`
- 原因: 底部导航栏 56dp + 安全区 inset + 呼吸空间,80dp 在某些设备上内容被遮挡

## 十、国际化要求

所有新增用户可见文字必须同步添加到 4 语言 strings.xml:
- `values/`(英文)
- `values-zh/`(中文)
- `values-ja/`(日文)
- `values-ko/`(韩文)

新增字符串清单(预估 15-20 条):
- 筛选相关: `filter_title`, `filter_genre`, `filter_year`, `filter_marked_time`, `filter_rating`, `filter_sort_order`, `filter_reset`, `filter_apply`, `filter_time_7d`, `filter_time_30d`, `filter_time_all`
- 豆瓣失败项: `douban_failure_search_hint`, `douban_failure_filter_reason`
- 设置页: `settings_view_statistics`, `settings_export_marks_data`(原 export_json 改名)
- 导入错误: `error_not_imdb_csv`, `error_empty_csv`, `error_parse_failed`, `error_invalid_json_format`
- 缓存: `settings_cache_management_expanded_desc`(可选)

## 十一、实施顺序

按依赖关系和风险最小原则排序:

1. **模块 E**(搜索框统一清空)— 独立,无依赖,风险最低
2. **模块 G**(缓存与边距修复)— 独立,修复 bug
3. **模块 D**(设置页布局改造)— 独立
4. **模块 F**(导入功能完善)— 依赖模块 D 的布局
5. **模块 A**(Watchlist 筛选增强)— 独立,新增 ViewModel 状态
6. **模块 B**(豆瓣失败项查看页改造)— 独立
7. **模块 C**(豆瓣失败项详情页改造)— 最复杂,放最后

## 十二、验收标准

- [ ] Watchlist 顶部统计按钮替换为筛选按钮,点击弹出 ModalBottomSheet
- [ ] 筛选条件生效时筛选图标变 primary 色
- [ ] TraktSearchScreen 搜索框有一键清空按钮
- [ ] 豆瓣失败项查看页有搜索框 + 筛选按钮 + JSON 导入入口(标题栏右侧)
- [ ] 豆瓣失败项查看页标题栏/切换条/tab 有 haze 毛玻璃,tab 底色透明
- [ ] 豆瓣失败项详情页标题栏有 haze 毛玻璃
- [ ] 失败原因栏在海报右侧,底部与海报底部对齐
- [ ] 海报大图有保存到相册按钮(复用 DetailPosterOverlay)
- [ ] 网盘缓存第二次打开不转圈
- [ ] 设置页缓存管理整卡可点击展开
- [ ] 设置页 4 个数据流通入口成 2x2 卡片
- [ ] 观看统计入口在设置页
- [ ] IMDb 导入有格式校验和错误 Snackbar
- [ ] JSON 导入失败项有格式校验和错误 Snackbar
- [ ] 发现页底部边距增大
- [ ] 4 语言 strings.xml 同步完整
- [ ] 构建 debug 包验证通过
