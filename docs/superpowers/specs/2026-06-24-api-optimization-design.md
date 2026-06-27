# API 优化与功能增强设计文档

> 日期: 2026-06-24
> 状态: 设计中

---

## 概述

基于对项目当前 Trakt/TMDB API 使用情况的全面审计，识别出 5 项功能增强，按优先级排列：

1. A1 — Trakt 搜索页增加人物搜索
2. A2 — 详情页展示预告片
3. A3 — 发现页增加 Trakt 热门数据源
4. B1 — 人物页用 Trakt API 增强数据
5. B5 — 发现页增加 Trakt 剧集推荐

---

## A1: Trakt 搜索页增加人物搜索

### 现状
- TraktSearchScreen 仅有「电影」和「电视剧」两个 Tab
- 搜索 API 只调用 `searchMovies()` 和 `searchShows()`
- 点击演职员头像进入 PersonScreen（数据来自 TMDB）

### 设计

**UI 变更**:
- 在现有电影/电视剧 Tab 旁新增「人物」Tab
- HorizontalPager 从 2 页扩展为 3 页
- 人物搜索结果卡片：头像 + 名字 + 代表作部门（如 Acting, Directing）

**API 变更**:
- `TraktApiService` 新增: `GET /search/person?query={q}&page={p}&limit={l}`
- `TraktRepository` 新增: `searchPeople(query: String, page: Int, limit: Int): Result<Pair<List<TraktSearchResult>, Int>>`
- 搜索结果中 `person` 对象含: `name`, `ids` (trakt, slug, imdb, tmdb), `headshot`

**点击行为**:
- 点击人物卡片 → 导航到现有 PersonScreen（传 tmdbId）
- 若 Trakt 结果无 tmdbId，用 `searchByTmdb` 或 IMDB ID 查找

**数据模型**:
- 复用现有 `TraktSearchResult`，其 `person` 字段已有定义
- `TraktPerson` 对象: `name`, `ids`, `headshot`, `biography`(extended)

---

## A2: 详情页展示预告片

### 现状
- DetailScreen 展示海报、评分、演职员、季信息、评论等
- 无视频/预告片展示
- TMDB API 已有 `/movie/{id}/videos` 和 `/tv/{id}/videos` 端点

### 设计

**UI 变更**:
- 在海报区域叠加一个半透明播放按钮（YouTube 风格三角形）
- 点击后弹出 BottomSheet 内嵌 YouTube Player 或跳转 YouTube App
- 优先使用 Trailer 类型视频，无 Trailer 时显示 Teaser

**API 变更**:
- `TmdbApiService` 新增:
  - `GET /movie/{id}/videos?language={lang}`
  - `GET /tv/{id}/videos?language={lang}`
- `TmdbRepository` 新增: `getMovieVideos(id: Int, lang: String)` / `getShowVideos(id: Int, lang: String)`
- 响应: `[{ id, key (YouTube ID), name, site, type, size, official }]`

**播放策略**:
1. 优先: 跳转 YouTube App（`Intent(ACTION_VIEW, youtube_uri)`）
2. 降级: 跳转浏览器播放

**DetailViewModel 变更**:
- 加载详情时同时请求 videos
- `uiState` 新增 `trailerKey: String?` 字段

---

## A3: 发现页增加 Trakt 热门数据源

### 现状
- DiscoverScreen 仅有 TMDB 数据源：热门电影、即将上映、高分电影
- 缺少 Trakt 社区热度数据（trending, popular, anticipated）
- 缺少剧集发现内容

### 设计

**UI 变更**:
- 在现有 TMDB 栏目下方追加 Trakt 栏目：
  - 「Trakt 热门电影」— `GET /movies/trending`
  - 「Trakt 热门剧集」— `GET /shows/trending`
  - 「最受期待」— `GET /movies/anticipated` + `GET /shows/anticipated`

**API 变更**:
- `TraktApiService` 新增:
  - `GET /movies/trending?extended=full&page={p}&limit={l}`
  - `GET /shows/trending?extended=full&page={p}&limit={l}`
  - `GET /movies/anticipated?extended=full&page={p}&limit={l}`
  - `GET /shows/anticipated?extended=full&page={p}&limit={l}`
- `TraktRepository` 新增:
  - `getTrendingMovies(page, limit): Result<Pair<List<TraktTrendingItem>, Int>>`
  - `getTrendingShows(page, limit): Result<Pair<List<TraktTrendingItem>, Int>>`
  - `getAnticipatedMovies(page, limit): Result<Pair<List<TraktAnticipatedItem>, Int>>`
  - `getAnticipatedShows(page, limit): Result<Pair<List<TraktAnticipatedItem>, Int>>`

**数据模型**:
- `TraktTrendingItem`: `watchers: Int`, `movie/show: TraktMovie/TraktShow`
- `TraktAnticipatedItem`: `list_count: Int`, `movie/show: TraktMovie/TraktShow`

**DiscoverViewModel 变更**:
- 新增加载 Trakt 数据的协程
- `uiState` 新增 Trakt 栏目数据字段
- 卡片复用现有 MovieCard/ShowCard 组件

**栏目排序**:
1. TMDB 热门电影
2. TMDB 即将上映
3. TMDB 高分电影
4. Trakt 热门电影
5. Trakt 热门剧集
6. Trakt 最受期待

---

## B1: 人物页用 Trakt API 增强数据

### 现状
- PersonScreen 仅使用 TMDB API
- 缺少 Trakt 特有数据：`series_regular`（常驻演员标记）、`episode_count`（出演集数）、`known_for_department`

### 设计

**数据增强**:
- 加载 PersonScreen 时，同时请求 Trakt `GET /people/{id}` 和 `GET /people/{id}/movies` + `GET /people/{id}/shows`
- 用 Trakt slug 或 tmdbId 查找人物

**UI 增强**:
- 演员标签：常驻演员显示「常驻」徽章
- 出演集数：在剧集作品卡片上显示「出演 X 集」
- 人物简介：优先显示 Trakt biography（更完整），TMDB 作为补充
- known_for_department：显示在人物名字下方（如「Acting」「Directing」）

**API 变更**:
- `TraktApiService` 新增:
  - `GET /people/{id}?extended=full`
  - `GET /people/{id}/movies?extended=full`
  - `GET /people/{id}/shows?extended=full`
- `TraktRepository` 新增:
  - `getPersonSummary(id: String): Result<TraktPerson>`
  - `getPersonMovieCredits(id: String): Result<TraktPersonCredits>`
  - `getPersonShowCredits(id: String): Result<TraktPersonCredits>`

**数据模型**:
- `TraktPersonCredits`:
  ```kotlin
  data class TraktPersonCredits(
      val cast: List<TraktCreditItem>,
      val crew: Map<String, List<TraktCreditItem>>
  )
  data class TraktCreditItem(
      val characters: List<String>,
      val seriesRegular: Boolean = false,  // 仅 shows
      val episodeCount: Int = 0,            // 仅 shows
      val movie: TraktMovie? = null,
      val show: TraktShow? = null
  )
  ```

---

## B5: 发现页增加 Trakt 剧集推荐

### 现状
- DiscoverScreen 无剧集推荐
- TraktRepository 已有 `getMovieRecommendations()` 但无剧集推荐

### 设计

**API 变更**:
- `TraktApiService` 新增: `GET /recommendations/shows?limit={l}&extended=full`
- `TraktRepository` 新增: `getShowRecommendations(limit: Int): Result<List<TraktRecommendation>>`

**UI 变更**:
- 在发现页 Trakt 栏目区域增加「为你推荐剧集」栏目
- 仅登录用户可见（需要 OAuth）
- 卡片复用现有组件

---

## 实施顺序

1. **A1 人物搜索** — 独立功能，不影响现有页面
2. **A2 预告片** — DetailScreen 增强，独立模块
3. **A3 Trakt 发现** — DiscoverScreen 增强，需要多个新 API
4. **B5 剧集推荐** — 依赖 A3 的 API 基础设施
5. **B1 人物页增强** — 依赖 A1 的人物搜索 API 基础设施

---

## 风险与注意事项

- **Trakt API 速率限制**: 新增多个栏目会并发请求，需控制请求频率
- **YouTube 跳转**: 部分设备可能未安装 YouTube App，需降级到浏览器
- **人物 ID 映射**: Trakt person slug 与 TMDB person id 的映射可能不完整
- **发现页性能**: 新增 Trakt 栏目会增加初始加载时间，考虑懒加载
