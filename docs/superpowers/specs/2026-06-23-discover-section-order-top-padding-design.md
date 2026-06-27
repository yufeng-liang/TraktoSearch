# 发现页栏目初始顺序与顶部留白设计

## 背景

发现页栏目支持在设置页自定义排序与显示/隐藏。当前实现中，`DiscoverViewModel` 的 `sectionConfigs` 使用 `defaultSectionConfigs` 作为 `stateIn` 的初始值，DataStore 中保存的自定义顺序需要异步加载完成后才会更新。这导致用户初次进入发现页时，栏目会先按默认顺序渲染，随后“跳动”到保存的自定义顺序。

同时，上一轮把 Haze 标题栏页面的内容顶部留白从 `56.dp` 增加到了 `72.dp`，用户反馈空白偏多，希望回调到 `65.dp`。

## 目标

1. 修复发现页初次进入时栏目顺序跳动的问题。
2. 将 5 个使用 Haze 标题栏的页面内容顶部留白统一调整为 `65.dp + statusBarHeight`。

## 方案

### 1. 发现页栏目顺序

在 `DiscoverViewModel` 初始化时，同步读取 `DiscoverSectionStorage.sectionConfigs` 的当前值，作为 `sectionConfigs` StateFlow 的初始值。

```kotlin
val sectionConfigs: StateFlow<List<DiscoverSectionConfig>> = discoverSectionStorage.sectionConfigs
    .stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        runBlocking { discoverSectionStorage.sectionConfigs.first() }
    )
```

这样首帧即可使用用户保存的顺序，避免从默认顺序切换到保存顺序的视觉跳动。

### 2. 顶部留白调整

涉及的 5 个页面：

- `DiscoverScreen`
- `WatchlistScreen`
- `StatisticsScreen`
- `SettingsScreen`
- `WebViewScreen`

将各自的 `contentPadding.top` / `padding(top = ...)` / `Spacer(height = ...)` 从 `72.dp + statusBarHeight` 改回 `65.dp + statusBarHeight`。

## 影响范围

- `DiscoverViewModel.kt`：同步初始化 `sectionConfigs` 初始值。
- `DiscoverScreen.kt`、`WatchlistScreen.kt`、`StatisticsScreen.kt`、`SettingsScreen.kt`、`WebViewScreen.kt`：调整顶部留白。

## 验收标准

- [ ] 初次安装/清除数据后，设置自定义栏目顺序并返回，重新冷启动应用后首次进入发现页，栏目按自定义顺序显示，无明显跳动。
- [ ] 5 个页面的标题栏不再遮挡内容顶部。
- [ ] 顶部留白比 `72.dp` 版本减少，视觉上更接近用户预期。
