# 想看列表 Trakt 搜索功能设计

## 概述

在想看列表搜索无匹配项时，提供 Trakt API 在线搜索功能。用户点击按钮跳转到独立搜索页面，查看搜索结果卡片，点击进入详情页标记想看，返回后自动刷新列表。

## 数据层

### 新增 Trakt API 端点（TraktApiService.kt）

```kotlin
@GET("search/movie")
suspend fun searchMovies(@Query("query") query: String): List<TraktSearchResult>

@GET("search/show")
suspend fun searchShows(@Query("query") query: String): List<TraktSearchResult>
```

### 复用现有数据模型

`TraktSearchResult`（type, score, movie, show）已存在，无需新建。

### 新增 Repository 方法

- `searchMovies(query: String): List<TraktSearchResult>`
- `searchShows(query: String): List<TraktSearchResult>`

### 新增 ViewModel

`TraktSearchViewModel`：
- 持有搜索状态（query, results, loading, error）
- 调用 Repository 执行搜索
- 支持重新搜索（修改关键词）

## UI 与导航

### 新增 TraktSearchScreen 页面

- 顶部：搜索栏（预填关键词，可修改，带搜索按钮）
- 中部：搜索结果网格（2列，复用 MovieCard）
- 状态：加载中（CircularProgressIndicator）、空结果提示、错误提示
- 卡片点击 → 导航到详情页（复用现有 DetailScreen）

### 导航路由

- 新增路由 `traktSearch/{type}/{query}`，type 为 movie 或 show
- 从 WatchlistScreen 空状态点击"在 Trakt 上搜索"按钮 → 导航到此页面

### WatchlistScreen 修改

- `WatchlistEmptyState` 增加"在 Trakt 上搜索"按钮
- 按钮传递当前 Tab 类型（movie/show）和搜索关键词
- 点击后 `navController.navigate("traktSearch/movie/$query")`

### 自动刷新想看列表

- 在 WatchlistScreen 中使用 `LifecycleResumeEffect` 监听页面恢复
- 从详情页返回时自动重新拉取想看列表数据
- 标记想看后返回，新项立即可见，无需手动刷新

## 搜索范围

- 电影 Tab 搜索电影，电视剧 Tab 搜索电视剧，与当前 Tab 一致
