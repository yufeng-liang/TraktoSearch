# TrackToSearch 性能优化与用户体验设计文档

> 版本：v1.0
> 日期：2026-06-30
> 状态：待评审

---

## 1. 概述

本文档定义 TrackToSearch 应用的性能优化和用户体验改进方向，涵盖 LazyList 优化、图片加载优化、动画效果、手势操作等方面。

### 1.1 目标

- 提升应用流畅度和响应速度
- 增强视觉过渡效果
- 改善用户交互体验

### 1.2 范围

- 性能优化：LazyList、图片缓存、重组优化
- 用户体验：共享元素过渡、手势操作、深色模式

---

## 2. 性能优化

### 2.1 LazyList 性能优化

**问题描述**：
列表滚动时，未提供稳定 key 导致不必要的重组。

**解决方案**：
为所有 LazyColumn/LazyVerticalGrid 的 items 添加稳定的 `key` 参数。

**实现细节**：

```kotlin
// 优化前
LazyColumn {
    items(movies) { movie ->
        MovieCard(movie)
    }
}

// 优化后
LazyColumn {
    items(movies, key = { it.id }) { movie ->
        MovieCard(movie)
    }
}
```

**涉及文件**：
| 文件 | 说明 |
|------|------|
| `DiscoverScreen.kt` | 发现页电影/剧集列表 |
| `SearchScreen.kt` | 搜索结果列表 |
| `WatchlistScreen.kt` | 想看/已看列表 |
| `PersonScreen.kt` | 人物作品列表 |
| `TraktListDetailScreen.kt` | 列表详情 |
| `TraktSearchScreen.kt` | Trakt 搜索结果 |

**预期收益**：
- 减少 30-50% 的不必要重组
- 提升列表滚动流畅度

---

### 2.2 图片加载优化

**问题描述**：
Coil 图片缓存配置可能未最优，导致重复加载。

**解决方案**：
配置全局 ImageLoader，优化内存和磁盘缓存。

**实现细节**：

```kotlin
// NetworkModule.kt
@Provides
@Singleton
fun provideImageLoader(@ApplicationContext context: Context): ImageLoader {
    return ImageLoader.Builder(context)
        .memoryCache {
            MemoryCache.Builder()
                .maxSizePercent(context, 0.25)
                .build()
        }
        .diskCache {
            DiskCache.Builder()
                .directory(context.cacheDir.resolve("image_cache"))
                .maxSizePercent(0.02)
                .build()
        }
        .crossfade(true)
        .build()
}
```

**涉及文件**：
| 文件 | 说明 |
|------|------|
| `NetworkModule.kt` | 配置全局 ImageLoader |

**预期收益**：
- 减少网络请求
- 提升图片加载速度
- 降低内存占用

---

### 2.3 重组优化

**问题描述**：
部分 Composable 存在过度重组。

**解决方案**：
- 使用 `@Stable` 和 `@Immutable` 注解标记数据类
- 使用 `Modifier.offset { }` lambda 版本
- 使用 `key()` 稳定组合项

**实现细节**：

```kotlin
// 数据类标记
@Immutable
data class Movie(
    val id: Int,
    val title: String,
    val posterUrl: String?
)

// Modifier 优化
// 优化前
Modifier.offset(x = offsetDp)

// 优化后
Modifier.offset { IntOffset(offsetPx.toInt(), 0) }
```

**涉及文件**：
| 文件 | 说明 |
|------|------|
| 所有 dto 文件 | 数据类标记 |
| 所有 Screen 文件 | Modifier 优化 |

**预期收益**：
- 减少不必要的重组
- 提升 UI 响应速度

---

### 2.4 启动速度优化

**问题描述**：
冷启动时间可能较长。

**解决方案**：
- 完善 Baseline Profiles 配置
- 延迟初始化非必要组件

**实现细节**：

```kotlin
// MainActivity.kt
override fun onCreate(savedInstanceState: Bundle?) {
    // 延迟初始化非必要组件
    lifecycleScope.launch {
        delay(1000)
        // 初始化推送、统计等非必要组件
    }
}
```

**涉及文件**：
| 文件 | 说明 |
|------|------|
| `MainActivity.kt` | 延迟初始化 |
| `build.gradle.kts` | baseline profile 配置 |

**预期收益**：
- 减少冷启动时间 20-30%

---

## 3. 用户体验

### 3.1 共享元素过渡动画

**问题描述**：
页面跳转缺少连贯的视觉过渡。

**解决方案**：
使用 `SharedTransitionLayout` 实现共享元素过渡。

**实现细节**：

```kotlin
// 卡片组件
@Composable
fun MovieCard(
    movie: Movie,
    sharedTransitionScope: SharedTransitionScope
) {
    with(sharedTransitionScope) {
        Card(
            modifier = Modifier
                .sharedElement(
                    sharedContentState = rememberSharedContentState(key = "movie-${movie.id}"),
                    animatedVisibilityScope = animatedVisibilityScope
                )
        ) {
            // 内容
        }
    }
}

// 详情页
@Composable
fun DetailScreen(
    movieId: Int,
    sharedTransitionScope: SharedTransitionScope
) {
    with(sharedTransitionScope) {
        Box(
            modifier = Modifier
                .sharedElement(
                    sharedContentState = rememberSharedContentState(key = "movie-$movieId"),
                    animatedVisibilityScope = animatedVisibilityScope
                )
        ) {
            // 内容
        }
    }
}
```

**涉及文件**：
| 文件 | 说明 |
|------|------|
| `MovieCard.kt` | 卡片组件 |
| `DetailScreen.kt` | 详情页 |
| `PersonScreen.kt` | 人物页 |
| `AppNavigation.kt` | 导航配置 |

**预期收益**：
- 显著提升页面跳转的视觉连贯性
- 增强应用的专业感

---

### 3.2 手势操作增强

**问题描述**：
部分操作依赖按钮点击，交互不够自然。

**解决方案**：
- 长按卡片弹出快捷操作菜单
- 双击海报放大查看

**实现细节**：

```kotlin
// 长按菜单
@Composable
fun MovieCard(
    movie: Movie,
    onLongClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .combinedClickable(
                onClick = { /* 点击事件 */ },
                onLongClick = onLongClick
            )
    ) {
        // 内容
    }
}
```

**涉及文件**：
| 文件 | 说明 |
|------|------|
| `MovieCard.kt` | 卡片长按菜单 |
| `DetailScreen.kt` | 海报双击放大 |

**预期收益**：
- 提升操作效率
- 增强交互体验

---

### 3.3 下拉刷新支持

**问题描述**：
部分列表不支持下拉刷新。

**解决方案**：
使用 Material3 的 `PullToRefreshBox` 组件。

**实现细节**：

```kotlin
@Composable
fun DiscoverScreen(viewModel: DiscoverViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var refreshing by remember { mutableStateOf(false) }

    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = {
            refreshing = true
            viewModel.refresh()
            refreshing = false
        }
    ) {
        LazyColumn {
            // 内容
        }
    }
}
```

**涉及文件**：
| 文件 | 说明 |
|------|------|
| `DiscoverScreen.kt` | 发现页下拉刷新 |
| `WatchlistScreen.kt` | 想看列表下拉刷新 |
| `SearchScreen.kt` | 搜索结果下拉刷新 |

**预期收益**：
- 提升数据更新的便捷性
- 符合用户操作习惯

---

### 3.4 骨架屏优化

**问题描述**：
骨架屏动画可能不够流畅。

**解决方案**：
优化 shimmer 动画效果，使其更自然。

**实现细节**：

```kotlin
@Composable
fun ShimmerSkeleton() {
    val shimmerBrush = rememberShimmerBrush()
    
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp)
            .background(shimmerBrush)
    )
}
```

**涉及文件**：
| 文件 | 说明 |
|------|------|
| `ShimmerSkeleton.kt` | 骨架屏组件 |

**预期收益**：
- 提升加载状态的视觉体验

---

## 4. 实施计划

### 4.1 优先级

| 优先级 | 任务 | 预计工时 |
|--------|------|----------|
| P0 | LazyList key 优化 | 2h |
| P0 | 图片缓存配置 | 1h |
| P1 | 共享元素过渡 | 4h |
| P1 | 下拉刷新支持 | 3h |
| P2 | 手势操作增强 | 3h |
| P2 | 骨架屏优化 | 2h |
| P3 | 重组优化 | 4h |
| P3 | 启动速度优化 | 2h |

### 4.2 里程碑

- **M1**：完成 P0 任务（性能基础优化）
- **M2**：完成 P1 任务（核心体验提升）
- **M3**：完成 P2 任务（交互增强）
- **M4**：完成 P3 任务（深度优化）

---

## 5. 验证方法

### 5.1 性能验证

- 使用 Android Studio Profiler 检查重组次数
- 使用 Layout Inspector 查看重组原因
- 使用 Baseline Profiles 测量启动时间

### 5.2 用户体验验证

- 在真机上测试动画流畅度
- 测试不同屏幕尺寸的适配
- 测试深色模式下的可读性

---

## 6. 风险与注意事项

1. **兼容性**：确保新功能在 Android 8.0+ 设备上正常运行
2. **性能**：新功能不应导致性能下降
3. **测试**：每个优化点需要充分测试

---

## 7. 附录

### 7.1 参考资源

- [Compose Performance Best Practices](https://developer.android.com/develop/ui/compose/performance/bestpractices)
- [Coil Image Loading](https://coil-kt.coil/docs/)
- [Material3 Components](https://m3.material.io/)

### 7.2 术语表

- **Recomposition**：Compose 重新执行组合函数的过程
- **LazyLayout**：延迟加载的列表组件
- **SharedElement**：共享元素过渡动画
