# 豆瓣详情页爬取进度与标注提示设计

## 背景与目标

当前 `DoubanItemDetailScreen` 进入失败项详情页时，`loadDetailInfo` 静默调用 `fetchDetail`，UI 完全无感知爬取进度（3-5秒反爬延迟+网络请求）。用户手动标注媒体类型（`setMediaType`）时也无任何 Toast 提示。

本设计目标：
1. 详情Tab 内联展示豆瓣详情爬取进度（加载中/失败/成功）
2. 爬取成功后乐观 Toast 提示「已更新，已共享全局池」
3. 手动标注类型时 Toast 提示「已标注为XX，已同步全局池」
4. 爬取失败时提供重试按钮

## 不在范围内

- 缓存命中（本地或全局池）时不提示数据来源（静默秒回）
- 自动推断媒体类型成功时静默（不弹Toast，仅刷新failure状态）
- 清除标注不上传全局池（现有行为保持）

## 架构

### ViewModel 状态扩展

`DoubanItemDetailUiState` 新增字段：

```kotlin
val detailLoadPhase: DetailLoadPhase = DetailLoadPhase.IDLE
```

新增私有枚举（文件内）：

```kotlin
enum class DetailLoadPhase {
    IDLE,       // 初始/未开始
    FETCHING,   // 正在爬取豆瓣详情页
    DONE,       // 完成（含缓存命中；detailInfo 为 null 表示爬取失败但已尝试）
    FAILED      // 爬取失败
}
```

### 一次性 Toast 事件

ViewModel 不能直接 showToast，通过 SharedFlow 传递 R.string 资源 ID：

```kotlin
private val _toastEvent = MutableSharedFlow<Int>(extraBufferCapacity = 4)
val toastEvent: SharedFlow<Int> = _toastEvent.asSharedFlow()
```

Composable 顶层收集：
```kotlin
LaunchedEffect(Unit) {
    viewModel.toastEvent.collect { resId ->
        context.showToast(context.getString(resId))
    }
}
```

### loadDetailInfo 改造

传递 `onProgress` 回调，映射到 `detailLoadPhase`：

| onProgress phase | detailLoadPhase | 内联UI | Toast |
|---|---|---|---|
| `cache_hit` | DONE | 显示字段卡片 | 无（秒回不提示） |
| `fetching` | FETCHING | 「正在爬取豆瓣详情...」+ 转圈 | 无 |
| `done` | DONE | 显示字段卡片 | `douban_detail_updated_and_synced` |
| `failed` | FAILED | 「爬取失败」+ 重试按钮 | 无（内联已提示） |

关键点：
- `done` 时弹乐观 Toast（上传全局池是 `GlobalScope.launch` 异步，成功率高，乐观提示可接受）
- Cookie 过期（`fetchDetail` 返回 null）走 `FAILED` 分支
- 异常 catch 时 `detailLoadPhase` 设为 `FAILED`

### setMediaType 改造

```kotlin
fun setMediaType(mediaType: String?) {
    val failure = _uiState.value.failure ?: return
    viewModelScope.launch {
        try {
            doubanRetryManager.updateMediaType(failure.doubanId, mediaType)
            _uiState.value = _uiState.value.copy(failure = failure.copy(mediaType = mediaType))
            _toastEvent.emit(
                if (mediaType != null) when (mediaType) {
                    "movie" -> R.string.douban_detail_marked_and_synced_movie
                    "show" -> R.string.douban_detail_marked_and_synced_show
                    "variety" -> R.string.douban_detail_marked_and_synced_variety
                    "documentary" -> R.string.douban_detail_marked_and_synced_documentary
                    else -> R.string.douban_detail_mark_cleared
                } else R.string.douban_detail_mark_cleared
            )
        } catch (_: Exception) {
            _toastEvent.emit(R.string.douban_detail_mark_failed)
        }
    }
}
```

`updateMediaType` 内部已完成上传全局池（`uploadUserMarkedMediaType`），Toast 在 await 完成后发出，语义准确。

### 重试按钮

ViewModel 新增：
```kotlin
fun retryLoadDetailInfo() {
    val failure = _uiState.value.failure ?: return
    loadDetailInfo(failure)
}
```

`loadDetailInfo` 入口重置 `detailLoadPhase = FETCHING`（跳过 IDLE），失败时设为 FAILED。重试自然走完整链路（缓存未命中才重新爬取，全局池命中条件是 imdbId != null，不会误命中残缺数据）。

### 详情Tab 内联状态 UI

在 `DoubanDetailInfoTab` 中，`detailInfo == null` 时根据 `detailLoadPhase` 显示：

- `FETCHING`：小转圈 + 「正在爬取豆瓣详情...」（独立卡片或内联行）
- `FAILED`：「爬取失败」灰色文字 + 「重试」TextButton
- `IDLE`/`DONE` 且 `detailInfo == null`：不显示该区域（静默）

`detailInfo != null` 时正常显示字段卡片（评分/简介/集数等），内联状态区域隐藏。

## 国际化新增字符串（4语言：values/zh/ja/ko）

- `douban_detail_fetching`：正在爬取豆瓣详情...
- `douban_detail_fetch_failed`：爬取失败
- `douban_detail_retry`：重试
- `douban_detail_updated_and_synced`：豆瓣详情已更新，已共享全局池
- `douban_detail_marked_and_synced_movie`：已标注为电影，已同步全局池
- `douban_detail_marked_and_synced_show`：已标注为电视剧，已同步全局池
- `douban_detail_marked_and_synced_variety`：已标注为综艺，已同步全局池
- `douban_detail_marked_and_synced_documentary`：已标注为纪录片，已同步全局池
- `douban_detail_mark_cleared`：已清除标注
- `douban_detail_mark_failed`：标注失败

## 数据流

```
进入详情页
  ↓
loadDetailInfo(failure)
  ↓
detailLoadPhase = FETCHING
  ↓
fetchDetail(onProgress)
  ├─ cache_hit → detailLoadPhase = DONE, 显示字段卡片
  ├─ fetching → (已在FETCHING状态, 转圈显示)
  ├─ done → detailLoadPhase = DONE, 显示字段卡片, Toast「已更新已共享」
  └─ failed → detailLoadPhase = FAILED, 显示「失败+重试」
  ↓
成功且集数>0 → inferMediaTypeFromDetail (静默, 不覆盖用户标注)
  ↓
刷新 failure 状态 (UI mediaType 按钮同步)

手动标注类型
  ↓
setMediaType(mediaType)
  ↓
updateMediaType (内部 uploadUserMarkedMediaType)
  ↓
更新 failure.mediaType
  ↓
Toast「已标注为XX，已同步全局池」/「已清除标注」
```

## 错误处理

- `loadDetailInfo` 异常 → `detailLoadPhase = FAILED`，显示重试按钮
- `fetchDetail` Cookie 过期 → 返回 null → `detailLoadPhase = FAILED`
- `setMediaType` 失败 → catch 异常，弹 Toast「标注失败」（新增字符串 `douban_detail_mark_failed`）
- `toastEvent.emit` 用 `extraBufferCapacity` 避免背压丢消息

## 影响文件

1. `app/src/main/java/com/tracktosearch/ui/screen/douban/DoubanItemDetailScreen.kt`
   - 新增 `DetailLoadPhase` 枚举
   - `DoubanItemDetailUiState` 新增 `detailLoadPhase` 字段
   - ViewModel 新增 `_toastEvent`/`toastEvent`、`retryLoadDetailInfo`，改造 `loadDetailInfo` 和 `setMediaType`
   - Composable 顶层收集 `toastEvent`
   - `DoubanDetailInfoTab` 新增内联状态UI和重试按钮
2. `app/src/main/res/values/strings.xml`（+zh/ja/ko）新增10个字符串

## 验证

构建 debug 包验证：
1. 进入旧失败项详情页 → 详情Tab显示「正在爬取...」→ 完成后显示字段卡片 + Toast
2. Cookie 过期场景 → 显示「爬取失败」+ 重试按钮
3. 手动标注4种类型 → Toast 提示对应文案
4. 清除标注 → Toast 提示「已清除标注」
