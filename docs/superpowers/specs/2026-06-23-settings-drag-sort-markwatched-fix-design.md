# 设置页拖动排序与标记已看弹窗修复设计

## 背景

当前设置页的栏目排序通过每行右侧的上下箭头按钮（`ArrowUpward` / `ArrowDownward`）逐格移动，操作不够直观。用户希望改为按住右侧拖动手柄直接拖动排序。

同时，电视剧详情页点击"标记已看"弹出的季/集勾选弹窗中，展开某季后若该季集信息尚未加载，会一直处于"加载中"状态，因为弹窗没有触发 ViewModel 加载该季数据。

另外，设置页打开时，栏目排序和自定义搜索源列表会先显示默认值/空列表，待 DataStore 异步加载完成后才跳变为真实数据，存在视觉跳变。

## 目标

1. 设置页栏目排序改为拖动移动（drag handle）。
2. 修复电视剧标记已看弹窗无法加载集信息的问题。
3. 消除设置页栏目排序与搜索源开关的加载跳变。

## 方案

### 1. 设置页栏目排序拖动

#### 依赖

引入第三方 Compose 拖拽排序库：

```kotlin
implementation("sh.calvin.reorderable:reorderable:2.4.3")
```

#### UI 改造

将 `DiscoverSectionsDialog` 中的 `Column + sections.forEachIndexed` 改为 `ReorderableLazyColumn`：

- 每行保留左侧栏目名称、右侧显示/隐藏 `Switch`。
- 在 Switch 左侧（或最右侧）添加拖动手柄图标 `Icons.Default.DragIndicator`。
- 拖动手柄使用 `ReorderableCollectionItemScope.dragHandle()` 修饰符。
- 移除现有的 `ArrowUpward` / `ArrowDownward` 上下箭头按钮。
- 拖动时 item 自动抬起并显示阴影，释放后列表整体重排。

#### ViewModel 改造

- 新增 `setSectionOrder(orderedIds: List<String>)` 方法，调用 `discoverSectionStorage.setSectionOrder(...)` 保存完整排序。
- 移除 `moveSectionUp(id)` 和 `moveSectionDown(id)` 方法。
- `DiscoverSectionRow` 的回调简化为：名称、可见性、拖动手柄、Switch 切换。

### 2. 标记已看弹窗集信息加载

- `DetailViewModel` 新增方法：

```kotlin
fun loadEpisodesForMarkWatched(seasonNumber: Int)
```

该方法在 `episodes` map 中不存在指定季时，调用 `traktRepository.getSeasonEpisodes(currentTraktId, seasonNumber)` 加载该季集信息，并更新 `_uiState.value.episodes`。

- `MarkWatchedDialog` 新增回调参数 `onLoadEpisodes: (Int) -> Unit`。
- 在弹窗内展开某季时，若 `episodes[season.number] == null`，调用 `onLoadEpisodes(season.number)` 触发加载。
- `DetailScreen` 调用弹窗时传入 `viewModel::loadEpisodesForMarkWatched`。

### 3. 消除设置页跳变

`SettingsViewModel` 中的两个 StateFlow 改为同步读取 DataStore 当前值作为初始值：

```kotlin
val discoverSections: StateFlow<List<DiscoverSectionConfig>> = discoverSectionStorage.sectionConfigs
    .stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        runBlocking { discoverSectionStorage.sectionConfigs.first() }
    )

val customSources: StateFlow<List<CustomSearchSource>> = customSearchSourceStorage.sources
    .stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        runBlocking { customSearchSourceStorage.sources.first() }
    )
```

这样设置页打开首帧即显示已保存数据，不再从默认值/空列表跳变。

## 影响文件

| 文件 | 变更 |
|------|------|
| `app/build.gradle.kts` | 添加 `sh.calvin.reorderable:reorderable:2.4.3` 依赖 |
| `SettingsScreen.kt` | `DiscoverSectionsDialog` 改用 `ReorderableLazyColumn`，`DiscoverSectionRow` 改为拖动排序行 |
| `SettingsViewModel.kt` | 新增 `setSectionOrder`、同步初始化 `discoverSections`/`customSources`、移除 `moveSectionUp/Down` |
| `DetailScreen.kt` | `MarkWatchedDialog` 增加 `onLoadEpisodes` 回调参数 |
| `DetailViewModel.kt` | 新增 `loadEpisodesForMarkWatched` 方法 |

## 验收标准

- [ ] 设置页"发现页栏目"对话框中，长按/拖动右侧 drag handle 可上下移动栏目顺序，释放后顺序保存。
- [ ] 栏目排序不再显示上下箭头按钮。
- [ ] 电视剧标记已看弹窗中，展开未加载过集信息的季时，自动加载并显示该季集列表。
- [ ] 设置页打开时，栏目排序和自定义搜索源开关首帧即显示保存状态，无跳变。
- [ ] Debug 构建通过，无编译错误。
