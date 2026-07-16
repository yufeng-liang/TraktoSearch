# 详情页沉浸式底色设计(海报主色调)

**日期**: 2026-07-06
**主题**: 正常影视详情页 + 豆瓣失败项详情页底色取自海报主色调,实现类似网易云播放器的沉浸效果

## 一、背景与目标

当前两个详情页(`DetailScreen` 和 `DoubanItemDetailScreen`)底色均使用 Scaffold 默认 `MaterialTheme.colorScheme.background`,与海报色完全无关,沉浸感不足。

**目标**: 海报加载后提取主色调,在页面背景上盖垂直渐变(主色半透明 → 背景色),实现沉浸式视觉。TopAppBar 保持当前 haze 实现不变。

## 二、技术现状

- **Palette 库**: 未引入,需新增 `androidx.palette:palette-ktx` 依赖
- **Coil 版本**: 2.7.0,`AsyncImage` 支持 `listener(onSuccess)` 回调拿 bitmap
- **ImageLoader 配置**: 全局 `crossfade(false)`,500MB 磁盘缓存(不改)
- **取色基础设施**: 项目完全没有,需从零搭建
- **已有能力**: `Theme.kt` 的 `monetColorScheme(seed, dark)` 函数(种子色→ColorScheme),但 private 未暴露 — 本设计不使用该函数(避免侵入性大)
- **`DetailUiState`**: 无任何颜色字段,需新增
- **`DoubanItemDetailUiState`: 同上

## 三、设计方案

### 3.1 整体方案

**方案3(简化版)**: 全屏垂直渐变背景 + TopAppBar 不动

- 海报加载后用 Palette 提取 `dominantColor`
- 在 Scaffold 内 Box 上盖 `Brush.verticalGradient(dominantColor.copy(alpha=0.45f) → background)`
- TopAppBar 保持当前 `HazeMaterials.thin()` + `surface.copy(alpha=0.50f)` 实现,不修改
- 海报为 null 时 `posterDominantColor = null`,背景保持默认

### 3.2 取色流程

```
海报 URL → Coil AsyncImage 加载
         → listener(onSuccess) 拿 bitmap
         → Palette.from(bitmap).generate { palette } (异步回调,不阻塞主线程)
         → palette.getDominantColor(0) 提取主色
         → ViewModel.updatePosterColor(Color(argb))
         → UiState.posterDominantColor 变更
         → Box.background(Brush.verticalGradient(...)) 重组
```

### 3.3 渐变规格

```
顶部: dominantColor.copy(alpha = 0.45f)
       ↓
底部: MaterialTheme.colorScheme.background
```

- 渐变范围: 整个 Box 高度(从状态栏到底部)
- 透明度: 0.45f(主色存在感强但不喧宾夺主,文字可读性不受影响)
- 暗色模式: 主色叠加在深色背景上,呈现低饱和度氛围色
- 亮色模式: 主色叠加在白色背景上,呈现淡彩色氛围

## 四、实施模块

### 4.1 依赖层

**`gradle/libs.versions.toml`**:
```toml
[versions]
palette = "1.0.0"

[libraries]
androidx-palette = { group = "androidx.palette", name = "palette-ktx", version.ref = "palette" }
```

**`app/build.gradle.kts`**:
```kotlin
implementation(libs.androidx.palette)
```

### 4.2 缓存层: PosterColorCache

新建 `app/src/main/java/com/tracktosearch/data/util/PosterColorCache.kt`:

- 单例 `@Singleton`
- 持久化缓存(DataStore 存储,key = posterUrl,value = ARGB Long)
- 内存一级缓存(`TtlCache<String, Long>` 或简单 ConcurrentHashMap)
- TTL: 永久(海报颜色不会变)
- 启动时从 DataStore 加载到内存
- API:
  ```kotlin
  suspend fun getColor(posterUrl: String): Long?           // 查内存缓存
  suspend fun putColor(posterUrl: String, argb: Long)     // 写内存 + DataStore
  ```

### 4.3 取色工具: PosterColorExtractor

新建 `app/src/main/java/com/tracktosearch/data/util/PosterColorExtractor.kt`:

- 单例 `@Singleton`,注入 `PosterColorCache`
- 方法:
  ```kotlin
  suspend fun extractDominantColor(posterUrl: String, bitmap: Bitmap): Long
  ```
- 逻辑:
  1. 先查 `PosterColorCache.getColor(posterUrl)`,命中直接返回
  2. 未命中 → `Palette.from(bitmap).generate()`(在 IO 线程)
  3. 提取 `getDominantColor(0)` → 转 ARGB Long
  4. 写入缓存 → 返回

### 4.4 状态层

**`DetailViewModel.kt` 的 `DetailUiState`** 新增字段:
```kotlin
val posterDominantColor: Color? = null
```

**`DoubanItemDetailViewModel.kt` 的 `DoubanItemDetailUiState`** 同上新增字段。

ViewModel 新增方法:
```kotlin
fun updatePosterColor(color: Color) {
    _uiState.value = _uiState.value.copy(posterDominantColor = color)
}
```

### 4.5 UI 层: DetailHeaderContent.kt

修改 `AsyncImage` 的 `ImageRequest`,添加 listener:

```kotlin
AsyncImage(
    model = remember(uiState.posterUrl) {
        ImageRequest.Builder(context)
            .data(uiState.posterUrl)
            .size(264)
            .crossfade(false)
            .listener(
                onSuccess = { _, result ->
                    val bitmap = androidx.core.graphics.drawable.toBitmap(result.drawable)
                    scope.launch {
                        val argb = posterColorExtractor.extractDominantColor(uiState.posterUrl, bitmap)
                        val color = Color(argb)
                        onPosterColorExtracted(color)
                    }
                }
            )
            .build()
    },
    ...
)
```

- `scope` = `rememberCoroutineScope()`
- `posterColorExtractor` 通过 ViewModel 传入或 Hilt 注入
- `onPosterColorExtracted` 回调调用 `ViewModel.updatePosterColor()`
- `androidx.core.graphics.drawable.toBitmap` 是 `androidx.core:core-ktx` 提供的扩展(项目已引入)

### 4.6 UI 层: DetailScreen.kt

修改 Scaffold 内 Box 的 modifier:

```kotlin
Scaffold(contentWindowInsets = WindowInsets(0, 0, 0, 0)) { padding ->
    Box(modifier = Modifier
        .fillMaxSize()
        .padding(padding)
        .then(
            uiState.posterDominantColor?.let { c ->
                Modifier.background(
                    Brush.verticalGradient(
                        colors = listOf(
                            c.copy(alpha = 0.45f),
                            MaterialTheme.colorScheme.background
                        )
                    )
                )
            } ?: Modifier
        )
    ) { ... }
}
```

### 4.7 UI 层: DoubanItemDetailScreen.kt

同 4.5 + 4.6,在 `DoubanItemHeader` 的 `AsyncImage` 加 listener,在 Scaffold 内 Box 加渐变背景。

### 4.8 DI 注册

**新建 `app/src/main/java/com/tracktosearch/di/UtilModule.kt`**:
```kotlin
@Module
@InstallIn(SingletonComponent::class)
object UtilModule {
    @Provides
    @Singleton
    fun providePosterColorCache(@ApplicationContext context: Context): PosterColorCache

    @Provides
    @Singleton
    fun providePosterColorExtractor(posterColorCache: PosterColorCache): PosterColorExtractor
}
```

## 五、注意事项

### 5.1 性能

- `Palette.from(bitmap).generate()` 必须用**异步回调重载**(`generate { palette -> }`),不能阻塞主线程
- 但由于 `PosterColorExtractor.extractDominantColor` 是 suspend 函数,在 `scope.launch` 中调用,内部用 `withContext(Dispatchers.Default)` 包裹 Palette 计算
- 海报 bitmap 已在内存中(Coil 缓存),不需要重新解码

### 5.2 缓存

- **持久化**: PosterColorCache 用 DataStore 存储,key = posterUrl,value = ARGB Long
- **TTL**: 永久(海报颜色不会变,符合 AGENTS.md "基本不变的数据用持久化缓存"原则)
- **启动加载**: App 启动时在 IO 协程异步加载到内存,不阻塞 UI
- **缓存命中后跳过 Palette 计算**: 第一次进入详情页提取后,后续进入直接读缓存,无 Palette 计算开销

### 5.3 边界情况

- **海报 URL 为 null**: `posterDominantColor = null`,背景保持默认,不强制造色
- **Palette 提取失败**: `getDominantColor(0)` 返回 0(透明),此时不写入缓存,UI 保持默认背景
- **暗色/亮色模式切换**: 渐变背景的底色用 `MaterialTheme.colorScheme.background`(随主题切换),主色透明叠加,无需额外处理
- **Coil 缓存命中**(图片已加载): `onSuccess` 仍会触发,listener 正常工作
- **sharedElement 转场**: `crossfade(false)` 不改,渐变背景在转场时即时呈现,不影响动画

### 5.4 不修改的部分

- **TopAppBar**: 保持当前 `HazeMaterials.thin()` + `surface.copy(alpha=0.50f)` 实现,不改
- **ImageLoader 全局配置**: `crossfade(false)` 不改
- **`monetColorScheme` 函数**: 保持 private,不暴露
- **状态栏颜色**: 不改(状态栏跟随系统主题)

## 六、国际化

本设计不涉及用户可见文字,无需新增 strings.xml。

## 七、验收标准

- [ ] `androidx.palette:palette-ktx` 依赖已添加
- [ ] `PosterColorCache` 单例实现,DataStore 持久化,key = posterUrl
- [ ] `PosterColorExtractor` 单例实现,先查缓存再 Palette 计算
- [ ] `DetailUiState` 新增 `posterDominantColor: Color?` 字段
- [ ] `DoubanItemDetailUiState` 同上
- [ ] `DetailHeaderContent` 的 `AsyncImage` 加 listener 提取主色
- [ ] `DoubanItemHeader` 同上
- [ ] `DetailScreen` 的 Box 加垂直渐变背景
- [ ] `DoubanItemDetailScreen` 同上
- [ ] 海报为 null 时背景保持默认
- [ ] 第二次进入详情页,缓存命中,无 Palette 计算延迟
- [ ] TopAppBar 保持当前 haze 实现,未修改
- [ ] 构建 debug 包验证通过
