# Trakt API v2 完整参考文档

> 基础 URL: `https://api.trakt.tv`
> 通用请求头:
> - `Content-Type: application/json`
> - `trakt-api-version: 2`
> - `trakt-api-key: {client_id}`
>
> 认证标记: 🔒 = OAuth 必需 | 🔓 = OAuth 可选 | 无标记 = 公开
> 特性标记: 📄 = 分页 | ✨ = 扩展信息 | 🎚 = 过滤器 | 😁 = Emoji 支持

---

## 目录

1. [认证 (Authentication)](#1-认证-authentication)
2. [日历 (Calendars)](#2-日历-calendars)
3. [签到 (Checkin)](#3-签到-checkin)
4. [认证分级 (Certifications)](#4-认证分级-certifications)
5. [评论 (Comments)](#5-评论-comments)
6. [国家 (Countries)](#6-国家-countries)
7. [类型 (Genres)](#7-类型-genres)
8. [语言 (Languages)](#8-语言-languages)
9. [列表 (Lists)](#9-列表-lists)
10. [电影 (Movies)](#10-电影-movies)
11. [网络 (Networks)](#11-网络-networks)
12. [人物 (People)](#12-人物-people)
13. [推荐 (Recommendations)](#13-推荐-recommendations)
14. [搜索 (Search)](#14-搜索-search)
15. [季 (Seasons)](#15-季-seasons)
16. [集 (Episodes)](#16-集-episodes)
17. [剧集 (Shows)](#17-剧集-shows)
18. [记录播放 (Scrobble)](#18-记录播放-scrobble)
19. [同步 (Sync)](#19-同步-sync)
20. [用户 (Users)](#20-用户-users)
21. [通用数据结构](#21-通用数据结构)
22. [通用参数说明](#22-通用参数说明)

---

## 1. 认证 (Authentication)

### 1.1 生成设备码

| 项目 | 详情 |
|------|------|
| **方法** | `POST` |
| **路径** | `/oauth/device/code` |
| **认证** | 无 |
| **描述** | 启动设备认证流程。`user_code` 和 `verification_url` 展示给用户，可生成二维码 `https://trakt.tv/activate/{user_code}` |

**请求体**:

```json
{
  "client_id": "string (必填) — 应用 Client ID"
}
```

**响应 200**:

```json
{
  "device_code": "string — 设备码，用于后续轮询",
  "user_code": "string — 用户输入码（通常8字符）",
  "verification_url": "string — 验证 URL",
  "expires_in": "integer — 过期时间（秒）",
  "interval": "integer — 轮询间隔（秒）"
}
```

---

### 1.2 轮询获取访问令牌

| 项目 | 详情 |
|------|------|
| **方法** | `POST` |
| **路径** | `/oauth/device/token` |
| **认证** | 无 |
| **描述** | 使用 `device_code` 按 `interval` 轮询。**必须按正确间隔轮询，过期后停止。** |

**请求体**:

```json
{
  "code": "string (必填) — 设备码",
  "client_id": "string (必填) — 应用 Client ID",
  "client_secret": "string (必填) — 应用 Client Secret"
}
```

**状态码说明**:

| 状态码 | 含义 |
|--------|------|
| 200 | 成功 — 保存 `access_token` |
| 400 | 等待中 — 用户尚未授权 |
| 404 | 未找到 — 无效的 `device_code` |
| 409 | 已使用 — 用户已批准此码 |
| 410 | 已过期 — 令牌已过期，需重新开始 |
| 418 | 被拒绝 — 用户明确拒绝 |
| 429 | 请求过快 — 轮询间隔太短 |

**响应 200**:

```json
{
  "access_token": "string — 访问令牌（有效期7天）",
  "token_type": "string — 令牌类型",
  "expires_in": "integer — 有效期（秒）",
  "refresh_token": "string — 刷新令牌",
  "scope": "string — 权限范围",
  "created_at": "integer — 创建时间戳"
}
```

---

### 1.3 交换令牌

| 项目 | 详情 |
|------|------|
| **方法** | `POST` |
| **路径** | `/oauth/token` |
| **认证** | 无 |
| **描述** | 使用授权码或刷新令牌交换访问令牌 |

**请求体 — 授权码模式**:

```json
{
  "client_id": "string (必填)",
  "client_secret": "string (必填)",
  "redirect_uri": "string (必填) — 应用设置中指定的 URI",
  "code": "string (必填) — 授权码",
  "grant_type": "string (必填) — authorization_code"
}
```

**请求体 — 刷新令牌模式**:

```json
{
  "client_id": "string (必填)",
  "client_secret": "string (必填)",
  "redirect_uri": "string (必填)",
  "refresh_token": "string (必填) — 之前获得的刷新令牌",
  "grant_type": "string (必填) — refresh_token"
}
```

**响应 200**: 同 1.2 的令牌响应格式

---

### 1.4 撤销令牌

| 项目 | 详情 |
|------|------|
| **方法** | `POST` |
| **路径** | `/oauth/revoke` |
| **认证** | 无 |
| **描述** | 使访问令牌和刷新令牌失效 |

**请求体**:

```json
{
  "access_token": "string (必填) — 要撤销的令牌",
  "client_id": "string (必填)"
}
```

---

## 2. 日历 (Calendars)

> 所有日历端点共享路径参数。`target` 可选 `my`（个性化，需 OAuth）或 `all`（全局）。

### 2.1 获取节目日历

| 项目 | 详情 |
|------|------|
| **方法** | `GET` |
| **路径** | `/calendars/{target}/shows/{start_date}/{days}` |
| **特性** | ✨ 🎚 |

**路径参数**:

| 参数 | 类型 | 必填 | 描述 |
|------|------|------|------|
| target | string | ✅ | `my` 或 `all` |
| start_date | string | ✅ | 起始日期 `YYYY-MM-DD` |
| days | string | ✅ | 获取天数 |

**查询参数**: [通用过滤器参数](#221-通用过滤器参数)

**响应 200**: `[{ first_aired, episode, show }]`

---

### 2.2 获取新节目日历

| 项目 | 详情 |
|------|------|
| **方法** | `GET` |
| **路径** | `/calendars/{target}/shows/new/{start_date}/{days}` |
| **描述** | 返回指定日期范围内首季播出的新节目 |

参数和响应同 2.1

---

### 2.3 获取季首播日历

| 项目 | 详情 |
|------|------|
| **方法** | `GET` |
| **路径** | `/calendars/{target}/shows/premieres/{start_date}/{days}` |
| **描述** | 返回指定日期范围内的季首播 |

参数和响应同 2.1

---

### 2.4 获取季终日历

| 项目 | 详情 |
|------|------|
| **方法** | `GET` |
| **路径** | `/calendars/{target}/shows/finales/{start_date}/{days}` |
| **描述** | 返回指定日期范围内的季终集 |

参数和响应同 2.1

---

### 2.5 获取电影日历

| 项目 | 详情 |
|------|------|
| **方法** | `GET` |
| **路径** | `/calendars/{target}/movies/{start_date}/{days}` |
| **特性** | ✨ 🎚 |

**响应 200**: `[{ released, movie }]`

---

### 2.6 获取 DVD 发行日历

| 项目 | 详情 |
|------|------|
| **方法** | `GET` |
| **路径** | `/calendars/{target}/dvd/{start_date}/{days}` |
| **特性** | ✨ 🎚 |

**响应 200**: `[{ released, movie }]`

---

## 3. 签到 (Checkin)

### 3.1 签到项目

| 项目 | 详情 |
|------|------|
| **方法** | `POST` |
| **路径** | `/checkin` |
| **认证** | 🔒 |
| **描述** | 签到一部电影或剧集，显示为"正在观看"。持续结束后自动切换为"已看"。已有签到进行中返回 409 |

**请求体**:

```json
{
  "movie": {
    "ids": { "trakt": 0, "slug": "", "imdb": "", "tmdb": 0 }
  },
  "episode": {
    "ids": { "trakt": 0, "slug": "", "imdb": "", "tmdb": 0, "tvdb": 0 }
  },
  "sharing": { "twitter": false, "mastodon": false, "tumblr": false },
  "message": "string (可选) — 分享消息"
}
```

**响应**: 200 成功 | 409 已有签到（含 `expires_at`）

---

### 3.2 删除当前签到

| 项目 | 详情 |
|------|------|
| **方法** | `DELETE` |
| **路径** | `/checkin` |
| **认证** | 🔒 |
| **描述** | 移除任何活跃的签到 |

**响应**: 204

---

## 4. 认证分级 (Certifications)

### 4.1 获取电影分级

| 项目 | 详情 |
|------|------|
| **方法** | `GET` |
| **路径** | `/certifications/movies` |
| **描述** | 返回按国家分组的电影分级 |

**响应 200**: `{ "us": [{ name, slug, description }], ... }`

---

### 4.2 获取剧集分级

| 项目 | 详情 |
|------|------|
| **方法** | `GET` |
| **路径** | `/certifications/shows` |
| **描述** | 返回按国家分组的剧集分级 |

**响应 200**: 同 4.1

---

## 5. 评论 (Comments)

### 5.1 发布评论

| 项目 | 详情 |
|------|------|
| **方法** | `POST` |
| **路径** | `/comments` |
| **认证** | 🔒 😁 |
| **描述** | 向电影、剧集、季、集或列表添加新评论 |

**请求体**:

```json
{
  "movie": { "ids": {} },
  "show": { "ids": {} },
  "season": { "ids": {} },
  "episode": { "ids": {} },
  "list": { "ids": {} },
  "comment": "string (必填) — 评论文本",
  "spoiler": "boolean (必填) — 是否剧透",
  "sharing": { "twitter": false, "tumblr": false, "medium": false }
}
```

**响应**: 201

---

### 5.2 更新评论

| 项目 | 详情 |
|------|------|
| **方法** | `PUT` |
| **路径** | `/comments/{id}` |
| **认证** | 🔒 😁 |
| **描述** | 更新评论，OAuth 用户必须是评论作者 |

**请求体**:

```json
{
  "comment": "string (必填)",
  "spoiler": "boolean (必填)"
}
```

**响应**: 200

---

### 5.3 删除评论

| 项目 | 详情 |
|------|------|
| **方法** | `DELETE` |
| **路径** | `/comments/{id}` |
| **认证** | 🔒 |
| **描述** | 删除评论。评论必须少于2周且0回复，否则返回 409 |

**响应**: 204

---

### 5.4 获取评论回复

| 项目 | 详情 |
|------|------|
| **方法** | `GET` |
| **路径** | `/comments/{id}/replies` |
| **认证** | 🔓 📄 😁 |
| **描述** | 返回评论的所有回复，可递归调用 |

**查询参数**: `extended`, `page`, `limit`

---

### 5.5 发布回复

| 项目 | 详情 |
|------|------|
| **方法** | `POST` |
| **路径** | `/comments/{id}/replies` |
| **认证** | 🔒 😁 |
| **描述** | 向顶级评论添加回复，回复回复会返回 404 |

**请求体**:

```json
{
  "comment": "string (必填)",
  "spoiler": "boolean (必填)"
}
```

**响应**: 201

---

### 5.6 点赞评论

| 项目 | 详情 |
|------|------|
| **方法** | `POST` |
| **路径** | `/comments/{id}/like` |
| **认证** | 🔒 |
| **描述** | 每个用户每条评论只能点赞一次 |

**响应**: 204

---

### 5.7 取消点赞评论

| 项目 | 详情 |
|------|------|
| **方法** | `DELETE` |
| **路径** | `/comments/{id}/like` |
| **认证** | 🔒 |

**响应**: 204

---

### 5.8 获取评论点赞用户

| 项目 | 详情 |
|------|------|
| **方法** | `GET` |
| **路径** | `/comments/{id}/likes` |
| **特性** | 📄 |

**响应 200**: `[{ liked_at, user }]`

---

### 5.9 获取反应摘要

| 项目 | 详情 |
|------|------|
| **方法** | `GET` |
| **路径** | `/comments/{id}/reactions/summary` |
| **描述** | 返回评论的反应总计，按反应类型分组 |

---

### 5.10 获取评论反应列表

| 项目 | 详情 |
|------|------|
| **方法** | `GET` |
| **路径** | `/comments/{id}/reactions` |
| **特性** | 📄 ✨ |

---

### 5.11 添加评论反应

| 项目 | 详情 |
|------|------|
| **方法** | `POST` |
| **路径** | `/comments/{id}/reactions/{reaction_type}` |
| **认证** | 🔒 |

**路径参数**: `reaction_type` 枚举: `like` | `dislike` | `love` | `laugh` | `shocked` | `bravo` | `spoiler`

**响应**: 201

---

### 5.12 移除评论反应

| 项目 | 详情 |
|------|------|
| **方法** | `DELETE` |
| **路径** | `/comments/{id}/reactions/{reaction_type}` |
| **认证** | 🔒 |

**响应**: 204

---

### 5.13 举报评论

| 项目 | 详情 |
|------|------|
| **方法** | `POST` |
| **路径** | `/comments/{id}/report` |
| **认证** | 🔒 |

**请求体**:

```json
{
  "reason": "string (必填) — spoilers|language|abusive|spam|bigotry|political|offtopic|support|duplicate|too_short|other",
  "message": "string (可选) — 附加说明"
}
```

**响应**: 201 | 409 已存在举报

---

## 6. 国家 (Countries)

### 6.1 获取电影国家列表

| 项目 | 详情 |
|------|------|
| **方法** | `GET` |
| **路径** | `/countries/movies` |

**响应 200**: `[{ name, code }]`

---

### 6.2 获取剧集国家列表

| 项目 | 详情 |
|------|------|
| **方法** | `GET` |
| **路径** | `/countries/shows` |

**响应 200**: 同 6.1

---

## 7. 类型 (Genres)

### 7.1 获取电影类型列表

| 项目 | 详情 |
|------|------|
| **方法** | `GET` |
| **路径** | `/genres/movies` |

**响应 200**: `[{ name, slug }]`

---

### 7.2 获取剧集类型列表

| 项目 | 详情 |
|------|------|
| **方法** | `GET` |
| **路径** | `/genres/shows` |

**响应 200**: 同 7.1

---

## 8. 语言 (Languages)

### 8.1 获取电影语言列表

| 项目 | 详情 |
|------|------|
| **方法** | `GET` |
| **路径** | `/languages/movies` |

**响应 200**: `[{ name, code }]`

---

### 8.2 获取剧集语言列表

| 项目 | 详情 |
|------|------|
| **方法** | `GET` |
| **路径** | `/languages/shows` |

**响应 200**: 同 8.1

---

## 9. 列表 (Lists)

### 9.1 获取热门列表

| 项目 | 详情 |
|------|------|
| **方法** | `GET` |
| **路径** | `/lists/trending` |
| **特性** | 📄 ✨ 🎚 😁 |

**查询参数**: [通用过滤器参数](#221-通用过滤器参数)

**响应 200**: `[{ like_count, comment_count, list }]`

---

### 9.2 获取流行列表

| 项目 | 详情 |
|------|------|
| **方法** | `GET` |
| **路径** | `/lists/popular` |
| **特性** | 📄 ✨ 🎚 😁 |

参数和响应同 9.1

---

### 9.3 获取列表详情

| 项目 | 详情 |
|------|------|
| **方法** | `GET` |
| **路径** | `/lists/{id}` |
| **特性** | 😁 |

**路径参数**: `id` — 列表 slug（整数 ID）

**响应 200**:

```json
{
  "name": "string",
  "description": "string",
  "privacy": "string — private|friends|public",
  "share_link": "string",
  "type": "string — movies|shows|seasons|episodes|persons|lists",
  "display_numbers": "boolean",
  "allow_comments": "boolean",
  "sort_by": "string — rank|added|title|released|runtime|popularity|percentage|votes|my_rating|random",
  "sort_how": "string — asc|desc",
  "created_at": "string",
  "updated_at": "string",
  "item_count": "integer",
  "comment_count": "integer",
  "likes": "integer",
  "ids": { "trakt": 0, "slug": "" },
  "user": { "username": "", "name": "", "vip": false },
  "images": {}
}
```

---

### 9.4 获取列表点赞用户

| 项目 | 详情 |
|------|------|
| **方法** | `GET` |
| **路径** | `/lists/{id}/likes` |
| **特性** | 📄 |

**响应 200**: `[{ liked_at, user }]`

---

### 9.5 点赞列表

| 项目 | 详情 |
|------|------|
| **方法** | `POST` |
| **路径** | `/lists/{id}/like` |
| **认证** | 🔒 |

**响应**: 204

---

### 9.6 取消点赞列表

| 项目 | 详情 |
|------|------|
| **方法** | `DELETE` |
| **路径** | `/lists/{id}/like` |
| **认证** | 🔒 |

**响应**: 204

---

### 9.7 获取列表项目

| 项目 | 详情 |
|------|------|
| **方法** | `GET` |
| **路径** | `/lists/{id}/items/{type}` |
| **特性** | 📄 ✨ 🎚 😁 |

**路径参数**: `type` — `movie` | `show` | `episode` | `season` | `movie,show` | `movie,show,episode,season`

**查询参数**: `extended`, `sort_by`, `sort_how`, `page`, `limit`（数字或 `all`）, [通用过滤器参数](#221-通用过滤器参数)

**响应 200**: `[{ rank, id, listed_at, notes, type, movie/show/episode/season }]`

---

### 9.8 举报列表

| 项目 | 详情 |
|------|------|
| **方法** | `POST` |
| **路径** | `/lists/{id}/report` |
| **认证** | 🔒 |

**请求体**:

```json
{
  "reason": "string (必填)",
  "message": "string (可选)"
}
```

**响应**: 201 | 409

---

## 10. 电影 (Movies)

### 10.1 获取电影详情

| 项目 | 详情 |
|------|------|
| **方法** | `GET` |
| **路径** | `/movies/{id}` |
| **特性** | ✨ |

**路径参数**: `id` — Trakt ID / slug / IMDb ID

**查询参数**: `extended` — `full` | `images` | `full,images` | `metadata`

**响应 200**:

```json
{
  "title": "string",
  "year": 2023,
  "ids": { "trakt": 0, "slug": "", "imdb": "", "tmdb": 0 },
  "tagline": "string",
  "overview": "string",
  "released": "string — YYYY-MM-DD",
  "runtime": 120,
  "country": "string — 2字符代码",
  "trailer": "string — URL",
  "homepage": "string — URL",
  "status": "string — released|in production|post production|planned|rumored|canceled",
  "rating": 8.5,
  "votes": 10000,
  "comment_count": 500,
  "updated_at": "string",
  "language": "string — 2字符代码",
  "available_translations": ["en", "zh"],
  "genres": ["action", "drama"],
  "certification": "string — 如 PG-13"
}
```

---

### 10.2 获取电影别名

| 项目 | 详情 |
|------|------|
| **方法** | `GET` |
| **路径** | `/movies/{id}/aliases` |

**响应 200**: `[{ title, country }]`

---

### 10.3 获取电影发行信息

| 项目 | 详情 |
|------|------|
| **方法** | `GET` |
| **路径** | `/movies/{id}/releases/{country}` |

**路径参数**: `country` — 2字符国家代码

**响应 200**: `[{ country, certification, release_date, release_type, note }]`

---

### 10.4 获取电影翻译

| 项目 | 详情 |
|------|------|
| **方法** | `GET` |
| **路径** | `/movies/{id}/translations/{language}` |

**路径参数**: `language` — 2字符语言代码

**响应 200**: `[{ title, overview, tagline, language, country }]`

---

### 10.5 获取电影评分

| 项目 | 详情 |
|------|------|
| **方法** | `GET` |
| **路径** | `/movies/{id}/ratings` |

**响应 200**:

```json
{
  "rating": 8.5,
  "votes": 10000,
  "distribution": { "1": 10, "2": 5, ..., "10": 3000 }
}
```

---

### 10.6 获取电影统计

| 项目 | 详情 |
|------|------|
| **方法** | `GET` |
| **路径** | `/movies/{id}/stats` |

**响应 200**:

```json
{
  "watchers": 50000,
  "plays": 120000,
  "collectors": 30000,
  "comments": 500,
  "lists": 2000,
  "votes": 10000
}
```

---

### 10.7 获取电影演职员

| 项目 | 详情 |
|------|------|
| **方法** | `GET` |
| **路径** | `/movies/{id}/people` |
| **特性** | ✨ |

**查询参数**: `extended` — `guest_stars` 可获取客串演员

**响应 200**:

```json
{
  "cast": [
    { "characters": ["角色名"], "person": { "name", "ids": {}, "headshot" } }
  ],
  "crew": {
    "production": [{ "jobs": ["Producer"], "person": {} }],
    "directing": [{ "jobs": ["Director"], "person": {} }],
    "writing": [...],
    "art": [...],
    "crew": [...],
    "costume & make-up": [...],
    "sound": [...],
    "camera": [...],
    "visual effects": [...],
    "lighting": [...],
    "editing": [...]
  }
}
```

---

### 10.8 获取电影评论

| 项目 | 详情 |
|------|------|
| **方法** | `GET` |
| **路径** | `/movies/{id}/comments/{sort}` |
| **认证** | 🔓 📄 😁 |

**路径参数**: `sort` — `newest` | `oldest` | `likes` | `replies` | `highest` | `lowest` | `plays`

**查询参数**: `extended`, `page`, `limit`

---

### 10.9 获取电影相关列表

| 项目 | 详情 |
|------|------|
| **方法** | `GET` |
| **路径** | `/movies/{id}/lists/{type}/{sort}` |
| **特性** | 📄 |

**路径参数**:
- `type` — `all` | `personal` | `official` | `watchlist` | `favorites`
- `sort` — `popular` | `likes` | `comments` | `items` | `added` | `updated`

---

### 10.10 获取相关电影

| 项目 | 详情 |
|------|------|
| **方法** | `GET` |
| **路径** | `/movies/{id}/related` |
| **特性** | 📄 ✨ |

**查询参数**: `extended`, `page`, `limit`

---

### 10.11 获取正在观看的用户

| 项目 | 详情 |
|------|------|
| **方法** | `GET` |
| **路径** | `/movies/{id}/watching` |
| **特性** | ✨ |

---

### 10.12 获取电影视频

| 项目 | 详情 |
|------|------|
| **方法** | `GET` |
| **路径** | `/movies/{id}/videos` |

**响应 200**: `[{ title, url, site, type, size, uploaded_at }]`

---

### 10.13 获取电影制片厂

| 项目 | 详情 |
|------|------|
| **方法** | `GET` |
| **路径** | `/movies/{id}/studios` |

---

### 10.14 获取热门电影

| 项目 | 详情 |
|------|------|
| **方法** | `GET` |
| **路径** | `/movies/trending` |
| **特性** | 📄 ✨ 🎚 |

**查询参数**: [通用过滤器参数](#221-通用过滤器参数)

**响应 200**: `[{ watchers, movie }]`

---

### 10.15 获取最多观看电影

| 项目 | 详情 |
|------|------|
| **方法** | `GET` |
| **路径** | `/movies/watched/{period}` |
| **特性** | 📄 ✨ 🎚 |

**路径参数**: `period` — `daily` | `weekly` | `monthly` | `yearly` | `all`

**响应 200**: `[{ watcher_count, play_count, movie }]`

---

### 10.16 获取最多播放电影

| 项目 | 详情 |
|------|------|
| **方法** | `GET` |
| **路径** | `/movies/played/{period}` |
| **特性** | 📄 ✨ 🎚 |

参数同 10.15

---

### 10.17 获取最多收藏电影

| 项目 | 详情 |
|------|------|
| **方法** | `GET` |
| **路径** | `/movies/collected/{period}` |
| **特性** | 📄 ✨ 🎚 |

参数同 10.15

---

### 10.18 获取最受期待电影

| 项目 | 详情 |
|------|------|
| **方法** | `GET` |
| **路径** | `/movies/anticipated` |
| **特性** | 📄 ✨ 🎚 |

**响应 200**: `[{ list_count, movie }]`

---

### 10.19 获取票房榜

| 项目 | 详情 |
|------|------|
| **方法** | `GET` |
| **路径** | `/movies/box_office` |

**响应 200**: `[{ revenue, movie }]`

---

### 10.20 获取流行电影

| 项目 | 详情 |
|------|------|
| **方法** | `GET` |
| **路径** | `/movies/popular` |
| **特性** | 📄 ✨ 🎚 |

---

### 10.21 获取流媒体电影

| 项目 | 详情 |
|------|------|
| **方法** | `GET` |
| **路径** | `/movies/streaming/{period}` |
| **特性** | 📄 ✨ 🎚 |

**路径参数**: `period` — `daily` | `weekly` | `monthly`

---

### 10.22 获取热门电影 (Hot)

| 项目 | 详情 |
|------|------|
| **方法** | `GET` |
| **路径** | `/movies/hot` |
| **特性** | 📄 ✨ 🎚 |

---

### 10.23 获取电影更新

| 项目 | 详情 |
|------|------|
| **方法** | `GET` |
| **路径** | `/movies/updates/{start_date}` |
| **特性** | 📄 ✨ |

---

### 10.24 刷新电影元数据

| 项目 | 详情 |
|------|------|
| **方法** | `POST` |
| **路径** | `/movies/{id}/refresh` |
| **认证** | 🔒 |

---

### 10.25 举报电影

| 项目 | 详情 |
|------|------|
| **方法** | `POST` |
| **路径** | `/movies/{id}/report` |
| **认证** | 🔒 |

---

## 11. 网络 (Networks)

### 11.1 获取网络列表

| 项目 | 详情 |
|------|------|
| **方法** | `GET` |
| **路径** | `/networks` |

**响应 200**: `[{ name, country }]`

---

## 12. 人物 (People)

### 12.1 获取人物详情

| 项目 | 详情 |
|------|------|
| **方法** | `GET` |
| **路径** | `/people/{id}` |
| **特性** | ✨ |

**路径参数**: `id` — 人物 slug

**响应 200**:

```json
{
  "name": "string",
  "ids": { "trakt": 0, "slug": "", "imdb": "", "tmdb": 0, "tvrage": 0 },
  "gender": "string — male|female|non_binary",
  "known_for_department": "string — production|art|crew|costume & make-up|directing|writing|sound|camera|visual effects|lighting|editing",
  "biography": "string",
  "birthday": "string — YYYY-MM-DD",
  "death": "string — YYYY-MM-DD",
  "homepage": "string — URL",
  "headshot": "string — URL"
}
```

---

### 12.2 获取电影演职员信息

| 项目 | 详情 |
|------|------|
| **方法** | `GET` |
| **路径** | `/people/{id}/movies` |
| **特性** | ✨ |
| **描述** | 返回此人参演/参与的所有电影。cast 含 characters 数组和 movie 对象。crew 按部门分组 |

**响应 200**:

```json
{
  "cast": [
    { "characters": ["角色名"], "movie": { "title", "year", "ids": {} } }
  ],
  "crew": {
    "production": [{ "jobs": ["Producer"], "movie": {} }],
    "directing": [...],
    "writing": [...]
  }
}
```

---

### 12.3 获取剧集演职员信息

| 项目 | 详情 |
|------|------|
| **方法** | `GET` |
| **路径** | `/people/{id}/shows` |
| **特性** | ✨ |
| **描述** | 返回此人参演/参与的所有剧集，含 `episode_count` 和 `series_regular` |

**响应 200**:

```json
{
  "cast": [
    {
      "characters": ["角色名"],
      "series_regular": true,
      "episode_count": 62,
      "show": { "title", "year", "ids": {} }
    }
  ],
  "crew": {
    "production": [...],
    "created by": [{ "jobs": ["Creator"], "show": {} }],
    "directing": [...]
  }
}
```

---

### 12.4 刷新人物元数据

| 项目 | 详情 |
|------|------|
| **方法** | `POST` |
| **路径** | `/people/{id}/refresh` |
| **认证** | 🔒 |

---

### 12.5 举报人物

| 项目 | 详情 |
|------|------|
| **方法** | `POST` |
| **路径** | `/people/{id}/report` |
| **认证** | 🔒 |

---

## 13. 推荐 (Recommendations)

### 13.1 获取电影推荐

| 项目 | 详情 |
|------|------|
| **方法** | `GET` |
| **路径** | `/recommendations/movies` |
| **认证** | 🔒 |

**查询参数**:

| 参数 | 类型 | 描述 |
|------|------|------|
| limit | integer | 每页数量（默认10，最大100） |
| ignore_collected | boolean | 过滤已收藏 |
| ignore_watchlisted | boolean | 过滤已在想看列表 |
| extended | string | 扩展信息 |

**响应 200**: `[{ movie, favorited_by: [{ user, notes }] }]`

---

### 13.2 隐藏电影推荐

| 项目 | 详情 |
|------|------|
| **方法** | `DELETE` |
| **路径** | `/recommendations/movies/{id}` |
| **认证** | 🔒 |

**响应**: 204

---

### 13.3 获取剧集推荐

| 项目 | 详情 |
|------|------|
| **方法** | `GET` |
| **路径** | `/recommendations/shows` |
| **认证** | 🔒 |

参数同 13.1

---

### 13.4 隐藏剧集推荐

| 项目 | 详情 |
|------|------|
| **方法** | `DELETE` |
| **路径** | `/recommendations/shows/{id}` |
| **认证** | 🔒 |

**响应**: 204

---

## 14. 搜索 (Search)

### 14.1 文本搜索

| 项目 | 详情 |
|------|------|
| **方法** | `GET` |
| **路径** | `/search/{type}` |
| **特性** | 📄 ✨ |
| **描述** | 搜索所有文本字段，结果按相关度排序。特殊字符需用 `\` 转义：`+ - && || ! ( ) { } [ ] ^ " ~ * ? : /` |

**路径参数**: `type` — `movie` | `show` | `episode` | `person` | `list`（可逗号分隔多个）

**查询参数**:

| 参数 | 类型 | 描述 |
|------|------|------|
| query | string | 搜索查询 |
| page | integer | 页码 |
| limit | integer | 每页数量 |
| extended | string | 扩展信息 |

**搜索字段对照**:

| 类型 | 搜索字段 |
|------|----------|
| movie | title, original_title, translations, aliases, tagline, overview |
| show | title, original_title, translations, aliases, overview, people |
| episode | title, show_title, overview |
| person | name, biography |
| list | name, description |

---

### 14.2 ID 搜索

| 项目 | 详情 |
|------|------|
| **方法** | `GET` |
| **路径** | `/search/id/{id_type}/{id}` |
| **描述** | 通过特定 ID 类型搜索 |

**路径参数**:
- `id_type` — `trakt` | `imdb` | `tmdb` | `tvdb`
- `id` — ID 值

**查询参数**: `type`（过滤结果类型）, `extended`

---

### 14.3 精确文本搜索

| 项目 | 详情 |
|------|------|
| **方法** | `GET` |
| **路径** | `/search/{type}/exact` |
| **特性** | 📄 ✨ |
| **描述** | 精确匹配搜索 |

参数同 14.1

---

### 14.4 获取热门搜索结果

| 项目 | 详情 |
|------|------|
| **方法** | `GET` |
| **路径** | `/search/recent_by_id/global/{type}` |
| **特性** | 📄 ✨ |

**路径参数**: `type` — `movies` | `shows` | `people`

---

### 14.5 添加最近搜索

| 项目 | 详情 |
|------|------|
| **方法** | `POST` |
| **路径** | `/search/recent` |
| **认证** | 🔒 |

**请求体**:

```json
{
  "query": "string (必填)",
  "id": "integer (必填) — 媒体 ID",
  "type": "string (必填) — movies|shows|people|lists"
}
```

**响应**: 201

---

### 14.6 移除最近搜索

| 项目 | 详情 |
|------|------|
| **方法** | `POST` |
| **路径** | `/search/recent/remove` |
| **认证** | 🔒 |

请求体同 14.5

**响应**: 204

---

## 15. 季 (Seasons)

### 15.1 获取剧集所有季

| 项目 | 详情 |
|------|------|
| **方法** | `GET` |
| **路径** | `/shows/{id}/seasons` |
| **特性** | ✨ |

**查询参数**: `extended` — `episodes` 可返回所有季的所有集

**响应 200**: `[{ number, ids: { trakt, tvdb, tmdb }, rating, votes, episode_count, episodes }]`

---

### 15.2 获取单季详情

| 项目 | 详情 |
|------|------|
| **方法** | `GET` |
| **路径** | `/shows/{id}/seasons/{season}` |
| **特性** | ✨ |

**路径参数**: `season` — 季号（≥0）

---

### 15.3 获取季评论

| 项目 | 详情 |
|------|------|
| **方法** | `GET` |
| **路径** | `/shows/{id}/seasons/{season}/comments/{sort}` |
| **认证** | 🔓 📄 |

---

### 15.4 获取季演职员

| 项目 | 详情 |
|------|------|
| **方法** | `GET` |
| **路径** | `/shows/{id}/seasons/{season}/people` |
| **特性** | ✨ |

---

### 15.5 获取季评分

| 项目 | 详情 |
|------|------|
| **方法** | `GET` |
| **路径** | `/shows/{id}/seasons/{season}/ratings` |

---

### 15.6 获取季统计

| 项目 | 详情 |
|------|------|
| **方法** | `GET` |
| **路径** | `/shows/{id}/seasons/{season}/stats` |

---

### 15.7 获取季相关列表

| 项目 | 详情 |
|------|------|
| **方法** | `GET` |
| **路径** | `/shows/{id}/seasons/{season}/lists/{type}/{sort}` |
| **特性** | 📄 |

---

### 15.8 获取正在观看季的用户

| 项目 | 详情 |
|------|------|
| **方法** | `GET` |
| **路径** | `/shows/{id}/seasons/{season}/watching` |

---

### 15.9 获取季翻译

| 项目 | 详情 |
|------|------|
| **方法** | `GET` |
| **路径** | `/shows/{id}/seasons/{season}/translations/{language}` |

---

### 15.10 获取季视频

| 项目 | 详情 |
|------|------|
| **方法** | `GET` |
| **路径** | `/shows/{id}/seasons/{season}/videos` |

---

### 15.11 举报季

| 项目 | 详情 |
|------|------|
| **方法** | `POST` |
| **路径** | `/shows/{id}/seasons/{season}/report` |
| **认证** | 🔒 |

---

## 16. 集 (Episodes)

### 16.1 获取单集详情

| 项目 | 详情 |
|------|------|
| **方法** | `GET` |
| **路径** | `/shows/{id}/seasons/{season}/episodes/{episode}` |
| **特性** | ✨ |

**路径参数**: `season` (≥0), `episode` (≥0)

**查询参数**: `extended` — `full`

**响应 200**:

```json
{
  "season": 1,
  "number": 1,
  "title": "string",
  "ids": { "trakt": 0, "slug": "", "imdb": "", "tmdb": 0, "tvdb": 0 },
  "number_abs": 0,
  "overview": "string",
  "first_aired": "string — UTC",
  "runtime": 45,
  "rating": 8.5,
  "votes": 5000,
  "comment_count": 100,
  "episode_type": "string — standard|series_premiere|season_premiere|mid_season_finale|mid_season_premiere|season_finale|series_finale"
}
```

---

### 16.2 获取单集评论

| 项目 | 详情 |
|------|------|
| **方法** | `GET` |
| **路径** | `/shows/{id}/seasons/{season}/episodes/{episode}/comments/{sort}` |
| **认证** | 🔓 📄 😁 |

---

### 16.3 获取单集演职员

| 项目 | 详情 |
|------|------|
| **方法** | `GET` |
| **路径** | `/shows/{id}/seasons/{season}/episodes/{episode}/people` |
| **特性** | ✨ |

**查询参数**: `extended` — `guest_stars`

---

### 16.4 获取单集评分

| 项目 | 详情 |
|------|------|
| **方法** | `GET` |
| **路径** | `/shows/{id}/seasons/{season}/episodes/{episode}/ratings` |

---

### 16.5 获取单集统计

| 项目 | 详情 |
|------|------|
| **方法** | `GET` |
| **路径** | `/shows/{id}/seasons/{season}/episodes/{episode}/stats` |

---

### 16.6 获取正在观看集的用户

| 项目 | 详情 |
|------|------|
| **方法** | `GET` |
| **路径** | `/shows/{id}/seasons/{season}/episodes/{episode}/watching` |

---

### 16.7 获取集翻译

| 项目 | 详情 |
|------|------|
| **方法** | `GET` |
| **路径** | `/shows/{id}/seasons/{season}/episodes/{episode}/translations/{language}` |

---

### 16.8 举报集

| 项目 | 详情 |
|------|------|
| **方法** | `POST` |
| **路径** | `/shows/{id}/seasons/{season}/episodes/{episode}/report` |
| **认证** | 🔒 |

---

## 17. 剧集 (Shows)

### 17.1 获取剧集详情

| 项目 | 详情 |
|------|------|
| **方法** | `GET` |
| **路径** | `/shows/{id}` |
| **特性** | ✨ |

**查询参数**: `extended` — `full`

**响应 200**:

```json
{
  "title": "string",
  "year": 2023,
  "ids": { "trakt": 0, "slug": "", "imdb": "", "tmdb": 0, "tvdb": 0 },
  "overview": "string",
  "first_aired": "string — UTC",
  "airs": { "day": "Monday", "time": "21:00", "timezone": "America/New_York" },
  "runtime": 45,
  "country": "string — 2字符代码",
  "trailer": "string — URL",
  "homepage": "string — URL",
  "status": "string — returning series|continuing|in production|planned|upcoming|pilot|canceled|ended",
  "rating": 8.5,
  "votes": 10000,
  "comment_count": 500,
  "language": "string — 2字符代码",
  "genres": ["drama", "crime"],
  "certification": "string",
  "network": "string"
}
```

---

### 17.2 获取剧集别名

| 项目 | 详情 |
|------|------|
| **方法** | `GET` |
| **路径** | `/shows/{id}/aliases` |

---

### 17.3 获取剧集认证分级

| 项目 | 详情 |
|------|------|
| **方法** | `GET` |
| **路径** | `/shows/{id}/certifications` |

---

### 17.4 获取剧集翻译

| 项目 | 详情 |
|------|------|
| **方法** | `GET` |
| **路径** | `/shows/{id}/translations/{language}` |

---

### 17.5 获取剧集评分

| 项目 | 详情 |
|------|------|
| **方法** | `GET` |
| **路径** | `/shows/{id}/ratings` |

---

### 17.6 获取剧集统计

| 项目 | 详情 |
|------|------|
| **方法** | `GET` |
| **路径** | `/shows/{id}/stats` |

---

### 17.7 获取剧集观看进度

| 项目 | 详情 |
|------|------|
| **方法** | `GET` |
| **路径** | `/shows/{id}/progress/watched` |
| **认证** | 🔒 |
| **描述** | 返回剧集的观看进度，含 `next_episode` 和 `reset_at` |

**查询参数**:

| 参数 | 类型 | 描述 |
|------|------|------|
| hidden | boolean | 是否包含隐藏的季 |
| specials | boolean | 是否包含特别篇（第0季） |
| count_specials | boolean | 是否在统计中计入特别篇 |
| include_stats | boolean | 是否包含统计 |

---

### 17.8 获取剧集收藏进度

| 项目 | 详情 |
|------|------|
| **方法** | `GET` |
| **路径** | `/shows/{id}/progress/collection` |
| **认证** | 🔒 |

参数同 17.7

---

### 17.9 获取剧集演职员

| 项目 | 详情 |
|------|------|
| **方法** | `GET` |
| **路径** | `/shows/{id}/people` |
| **特性** | ✨ |

**查询参数**: `extended` — `guest_stars`

---

### 17.10 获取剧集评论

| 项目 | 详情 |
|------|------|
| **方法** | `GET` |
| **路径** | `/shows/{id}/comments/{sort}` |
| **认证** | 🔓 📄 😁 |

---

### 17.11 获取剧集相关列表

| 项目 | 详情 |
|------|------|
| **方法** | `GET` |
| **路径** | `/shows/{id}/lists/{type}/{sort}` |
| **特性** | 📄 |

---

### 17.12 获取相关剧集

| 项目 | 详情 |
|------|------|
| **方法** | `GET` |
| **路径** | `/shows/{id}/related` |
| **特性** | 📄 ✨ |

---

### 17.13 获取正在观看的用户

| 项目 | 详情 |
|------|------|
| **方法** | `GET` |
| **路径** | `/shows/{id}/watching` |

---

### 17.14 获取下一集

| 项目 | 详情 |
|------|------|
| **方法** | `GET` |
| **路径** | `/shows/{id}/next` |
| **认证** | 🔒 |

---

### 17.15 获取最后一集

| 项目 | 详情 |
|------|------|
| **方法** | `GET` |
| **路径** | `/shows/{id}/last` |

---

### 17.16 获取剧集视频

| 项目 | 详情 |
|------|------|
| **方法** | `GET` |
| **路径** | `/shows/{id}/videos` |

---

### 17.17 获取剧集制片厂

| 项目 | 详情 |
|------|------|
| **方法** | `GET` |
| **路径** | `/shows/{id}/studios` |

---

### 17.18 获取热门剧集

| 项目 | 详情 |
|------|------|
| **方法** | `GET` |
| **路径** | `/shows/trending` |
| **特性** | 📄 ✨ 🎚 |

**响应 200**: `[{ watchers, show }]`

---

### 17.19 获取最多观看剧集

| 项目 | 详情 |
|------|------|
| **方法** | `GET` |
| **路径** | `/shows/watched/{period}` |
| **特性** | 📄 ✨ 🎚 |

**路径参数**: `period` — `daily` | `weekly` | `monthly` | `yearly` | `all`

---

### 17.20 获取最多播放剧集

| 项目 | 详情 |
|------|------|
| **方法** | `GET` |
| **路径** | `/shows/played/{period}` |
| **特性** | 📄 ✨ 🎚 |

---

### 17.21 获取最多收藏剧集

| 项目 | 详情 |
|------|------|
| **方法** | `GET` |
| **路径** | `/shows/collected/{period}` |
| **特性** | 📄 ✨ 🎚 |

---

### 17.22 获取最受期待剧集

| 项目 | 详情 |
|------|------|
| **方法** | `GET` |
| **路径** | `/shows/anticipated` |
| **特性** | 📄 ✨ 🎚 |

**响应 200**: `[{ list_count, show }]`

---

### 17.23 获取流行剧集

| 项目 | 详情 |
|------|------|
| **方法** | `GET` |
| **路径** | `/shows/popular` |
| **特性** | 📄 ✨ 🎚 |

---

### 17.24 获取流媒体剧集

| 项目 | 详情 |
|------|------|
| **方法** | `GET` |
| **路径** | `/shows/streaming/{period}` |
| **特性** | 📄 ✨ 🎚 |

---

### 17.25 获取热门剧集 (Hot)

| 项目 | 详情 |
|------|------|
| **方法** | `GET` |
| **路径** | `/shows/hot` |
| **特性** | 📄 ✨ 🎚 |

---

### 17.26 获取剧集更新

| 项目 | 详情 |
|------|------|
| **方法** | `GET` |
| **路径** | `/shows/updates/{start_date}` |
| **特性** | 📄 ✨ |

---

### 17.27 刷新剧集元数据

| 项目 | 详情 |
|------|------|
| **方法** | `POST` |
| **路径** | `/shows/{id}/refresh` |
| **认证** | 🔒 |

---

### 17.28 举报剧集

| 项目 | 详情 |
|------|------|
| **方法** | `POST` |
| **路径** | `/shows/{id}/report` |
| **认证** | 🔒 |

---

## 18. 记录播放 (Scrobble)

### 18.1 开始观看

| 项目 | 详情 |
|------|------|
| **方法** | `POST` |
| **路径** | `/scrobble/start` |
| **认证** | 🔒 |
| **描述** | 视频开始播放或取消暂停时使用。将移除任何已有的播放进度 |

**请求体**:

```json
{
  "movie": { "ids": { "trakt": 0 } },
  "episode": { "ids": { "trakt": 0 } },
  "progress": 25.5
}
```

**响应 201**:

```json
{
  "id": 12345,
  "action": "start",
  "progress": 25.5,
  "sharing": {},
  "movie": {}
}
```

---

### 18.2 暂停观看

| 项目 | 详情 |
|------|------|
| **方法** | `POST` |
| **路径** | `/scrobble/pause` |
| **认证** | 🔒 |

请求体同 18.1

---

### 18.3 停止/完成观看

| 项目 | 详情 |
|------|------|
| **方法** | `POST` |
| **路径** | `/scrobble/stop` |
| **认证** | 🔒 |
| **描述** | 进度 ≥80% 将被 scrobble（记录观看历史），返回唯一 history id。进度 1%-79% 视为暂停。进度 <1% 返回 422。同一项目刚被 scrobbled 返回 409 |

请求体同 18.1

**响应 201**:

```json
{
  "id": 12345,
  "action": "scrobble",
  "progress": 100,
  "watched_at": "2024-01-01T00:00:00.000Z",
  "expires_at": "2024-01-01T02:00:00.000Z",
  "movie": {}
}
```

---

## 19. 同步 (Sync)

> 所有 Sync 端点需要 🔒 OAuth 认证

### 19.1 获取最后活动时间

| 项目 | 详情 |
|------|------|
| **方法** | `GET` |
| **路径** | `/sync/last_activities` |
| **描述** | 返回各类操作的最新时间戳，建议缓存以避免获取未变更数据 |

**响应 200**:

```json
{
  "all": "2024-01-01T00:00:00.000Z",
  "movies": { "watched_at": "", "collected_at": "", "rated_at": "", "watchlisted_at": "", "commented_at": "", "paused_at": "", "hidden_at": "" },
  "episodes": { ... },
  "shows": { ... },
  "seasons": { ... },
  "comments": { "liked_at": "" },
  "lists": { "liked_at": "", "updated_at": "", "commented_at": "" }
}
```

---

### 19.2 添加到观看历史

| 项目 | 详情 |
|------|------|
| **方法** | `POST` |
| **路径** | `/sync/history` |
| **描述** | 将项目添加到观看历史。如果只传 show，则添加该剧所有集。`watched_at` 可设为 `released`（使用首映日期）或 `unknown` |

**请求体**:

```json
{
  "movies": [
    {
      "ids": { "trakt": 0, "imdb": "", "tmdb": 0 },
      "watched_at": "2024-01-01T00:00:00.000Z"
    }
  ],
  "shows": [
    {
      "ids": { "trakt": 0 },
      "watched_at": "2024-01-01T00:00:00.000Z",
      "seasons": [
        {
          "number": 1,
          "episodes": [
            { "number": 1, "watched_at": "2024-01-01T00:00:00.000Z" }
          ]
        }
      ]
    }
  ],
  "episodes": [
    {
      "ids": { "trakt": 0 },
      "watched_at": "2024-01-01T00:00:00.000Z"
    }
  ]
}
```

**响应 200**:

```json
{
  "added": { "movies": 1, "episodes": 0 },
  "not_found": { "movies": [], "shows": [], "episodes": [] },
  "existing": { "movies": [], "episodes": [] }
}
```

---

### 19.3 从观看历史移除

| 项目 | 详情 |
|------|------|
| **方法** | `POST` |
| **路径** | `/sync/history/remove` |
| **描述** | 也可发送 `ids` 数组（64位整数 history id）删除单条播放记录 |

**请求体**: 同 19.2 格式，额外支持 `ids` 数组

**响应 200**:

```json
{
  "deleted": { "movies": 1, "episodes": 0 },
  "not_found": { "movies": [], "episodes": [], "ids": [] }
}
```

---

### 19.4 添加到想看列表

| 项目 | 详情 |
|------|------|
| **方法** | `POST` |
| **路径** | `/sync/watchlist` |
| **描述** | 每个项目可选 `notes`（最多500字符，需 VIP）。超出限制返回 420 |

**请求体**: 同 19.2 格式，`watched_at` 替换为可选的 `notes`

**响应 200**: 同 19.2 格式

---

### 19.5 从想看列表移除

| 项目 | 详情 |
|------|------|
| **方法** | `POST` |
| **路径** | `/sync/watchlist/remove` |

**请求体**: 同 19.2 格式

---

### 19.6 添加评分

| 项目 | 详情 |
|------|------|
| **方法** | `POST` |
| **路径** | `/sync/ratings` |

**请求体**:

```json
{
  "movies": [
    {
      "ids": { "trakt": 0 },
      "rating": 8,
      "rated_at": "2024-01-01T00:00:00.000Z"
    }
  ],
  "shows": [
    {
      "ids": { "trakt": 0 },
      "rating": 9,
      "rated_at": "2024-01-01T00:00:00.000Z",
      "seasons": [
        { "number": 1, "rating": 8 }
      ]
    }
  ],
  "seasons": [{ "ids": { "trakt": 0 }, "rating": 7 }],
  "episodes": [{ "ids": { "trakt": 0 }, "rating": 9 }]
}
```

**响应 200**:

```json
{
  "added": { "movies": 1, "shows": 0, "seasons": 0, "episodes": 0 },
  "not_found": { "movies": [], "shows": [], "seasons": [], "episodes": [] }
}
```

---

### 19.7 移除评分

| 项目 | 详情 |
|------|------|
| **方法** | `POST` |
| **路径** | `/sync/ratings/remove` |

**请求体**: 同 19.6 格式（不需要 `rating` 和 `rated_at`）

---

### 19.8 添加到收藏

| 项目 | 详情 |
|------|------|
| **方法** | `POST` |
| **路径** | `/sync/collection` |
| **描述** | 每个项目可选 `collected_at`（UTC 时间戳）和 `metadata`（媒体元数据） |

**请求体**:

```json
{
  "movies": [
    {
      "ids": { "trakt": 0 },
      "collected_at": "2024-01-01T00:00:00.000Z",
      "metadata": {
        "media_type": "digital",
        "resolution": "hd_1080p",
        "hdr": "dolby_vision",
        "audio": "dolby_atmos",
        "audio_channels": "7.1"
      }
    }
  ],
  "shows": [
    {
      "ids": { "trakt": 0 },
      "seasons": [
        {
          "number": 1,
          "episodes": [{ "number": 1, "collected_at": "" }]
        }
      ]
    }
  ]
}
```

---

### 19.9 从收藏移除

| 项目 | 详情 |
|------|------|
| **方法** | `POST` |
| **路径** | `/sync/collection/remove` |

---

### 19.10 添加到收藏夹 (Favorites)

| 项目 | 详情 |
|------|------|
| **方法** | `POST` |
| **路径** | `/sync/favorites` |
| **描述** | 最多50个 TV shows 和 movies。每个可选 `notes`（最多500字符） |

---

### 19.11 从收藏夹移除

| 项目 | 详情 |
|------|------|
| **方法** | `POST` |
| **路径** | `/sync/favorites/remove` |

---

### 19.12 获取电影收藏

| 项目 | 详情 |
|------|------|
| **方法** | `GET` |
| **路径** | `/sync/collection/movies` |
| **特性** | 📄 ✨ |

**查询参数**: `extended`, `available_on`, `page`, `limit`

---

### 19.13 获取剧集收藏

| 项目 | 详情 |
|------|------|
| **方法** | `GET` |
| **路径** | `/sync/collection/shows` |
| **特性** | ✨ |

**查询参数**: `extended`, `available_on`

---

### 19.14 获取集收藏

| 项目 | 详情 |
|------|------|
| **方法** | `GET` |
| **路径** | `/sync/collection/episodes` |

---

### 19.15 获取最小化收藏

| 端点 | 路径 | 描述 |
|------|------|------|
| 电影 | `/sync/collection/minimal/movies` | 最小格式电影收藏 |
| 剧集 | `/sync/collection/minimal/shows` | 最小格式剧集收藏 |
| 集 | `/sync/collection/minimal/episodes` | 最小格式集收藏 |

**查询参数**: `available_on`

---

### 19.16 获取所有评分

| 项目 | 详情 |
|------|------|
| **方法** | `GET` |
| **路径** | `/sync/ratings` |
| **特性** | ✨ |

**查询参数**: `extended`

**响应 200**: `[{ rating: 8, rated_at: "", movie/show/season/episode }]`

---

### 19.17 获取观看进度

| 项目 | 详情 |
|------|------|
| **方法** | `GET` |
| **路径** | `/sync/watched` |
| **描述** | 返回已认证用户的观看进度 |

---

### 19.18 获取媒体收藏

| 项目 | 详情 |
|------|------|
| **方法** | `GET` |
| **路径** | `/sync/collection/media` |
| **特性** | 📄 ✨ |

**查询参数**: `extended`, `available_on`, `page`, `limit`

---

### 19.19 获取媒体想看列表

| 项目 | 详情 |
|------|------|
| **方法** | `GET` |
| **路径** | `/sync/watchlist/media` |
| **特性** | 📄 ✨ |

**查询参数**: `extended`, `page`, `limit`

---

### 19.20 获取电影播放进度

| 项目 | 详情 |
|------|------|
| **方法** | `GET` |
| **路径** | `/sync/playback/movies` |

---

### 19.21 移除播放记录

| 项目 | 详情 |
|------|------|
| **方法** | `DELETE` |
| **路径** | `/sync/playback/{id}` |

**路径参数**: `id` — 播放条目 ID

**响应**: 204 | 404

---

## 20. 用户 (Users)

### 20.1 获取用户设置

| 项目 | 详情 |
|------|------|
| **方法** | `GET` |
| **路径** | `/users/settings` |
| **认证** | 🔒 |

---

### 20.2 更新用户设置

| 项目 | 详情 |
|------|------|
| **方法** | `PUT` |
| **路径** | `/users/settings` |
| **认证** | 🔒 |

---

### 20.3 获取用户资料

| 项目 | 详情 |
|------|------|
| **方法** | `GET` |
| **路径** | `/users/{username}/profile` |
| **特性** | ✨ |

---

### 20.4 获取用户统计

| 项目 | 详情 |
|------|------|
| **方法** | `GET` |
| **路径** | `/users/{username}/stats` |

**响应 200**:

```json
{
  "movies": { "watched": 100, "collected": 80, "ratings": 50, "comments": 10 },
  "shows": { "watched": 50, "collected": 30, "ratings": 20, "comments": 5 },
  "seasons": { "ratings": 10, "comments": 2 },
  "episodes": { "watched": 1000, "collected": 800, "ratings": 100, "comments": 20 },
  "network": { "friends": 5, "followers": 100, "following": 50 },
  "ratings": 180
}
```

---

### 20.5 获取正在观看

| 项目 | 详情 |
|------|------|
| **方法** | `GET` |
| **路径** | `/users/{username}/watching` |

---

### 20.6 获取观看历史

| 项目 | 详情 |
|------|------|
| **方法** | `GET` |
| **路径** | `/users/{username}/history` |
| **特性** | 📄 ✨ |

**查询参数**:

| 参数 | 类型 | 描述 |
|------|------|------|
| type | string | 过滤类型: movie|show|season|episode |
| item_id | integer | 过滤特定项目 ID |
| start_at | string | 起始时间 ISO 8601 |
| end_at | string | 结束时间 ISO 8601 |
| page | integer | 页码 |
| limit | integer | 每页数量 |
| extended | string | 扩展信息 |

---

### 20.7 获取电影观看历史

| 项目 | 详情 |
|------|------|
| **方法** | `GET` |
| **路径** | `/users/{username}/watched/movies` |
| **特性** | ✨ |

---

### 20.8 获取剧集观看历史

| 项目 | 详情 |
|------|------|
| **方法** | `GET` |
| **路径** | `/users/{username}/watched/shows` |
| **特性** | ✨ |

---

### 20.9 获取电影想看列表

| 项目 | 详情 |
|------|------|
| **方法** | `GET` |
| **路径** | `/users/{username}/watchlist/movies` |
| **特性** | 📄 ✨ |

**查询参数**: `sort_by`, `sort_how`, `page`, `limit`, `extended`

**sort_by 选项**: `rank` | `added` | `title` | `released` | `runtime` | `rating` | `votes` | `imdb_rating`(VIP) | `tmdb_rating`(VIP) | `metascore`(VIP)

**sort_how**: `asc` | `desc`

---

### 20.10 获取剧集想看列表

| 项目 | 详情 |
|------|------|
| **方法** | `GET` |
| **路径** | `/users/{username}/watchlist/shows` |
| **特性** | 📄 ✨ |

参数同 20.9

---

### 20.11 获取想看列表（按类型）

| 项目 | 详情 |
|------|------|
| **方法** | `GET` |
| **路径** | `/users/{username}/watchlist/{type}` |
| **特性** | 📄 ✨ |

**路径参数**: `type` — `movies` | `shows` | `seasons` | `episodes` | `all`

---

### 20.12 获取电影评分

| 项目 | 详情 |
|------|------|
| **方法** | `GET` |
| **路径** | `/users/{username}/ratings/movies` |
| **特性** | 📄 ✨ |

**查询参数**: `rating`（过滤特定评分 1-10）, `page`, `limit`

---

### 20.13 获取剧集评分

| 项目 | 详情 |
|------|------|
| **方法** | `GET` |
| **路径** | `/users/{username}/ratings/shows` |
| **特性** | 📄 ✨ |

---

### 20.14 获取所有评分

| 项目 | 详情 |
|------|------|
| **方法** | `GET` |
| **路径** | `/users/{username}/ratings/{type}` |

**路径参数**: `type` — `movies` | `shows` | `seasons` | `episodes` | `all`

---

### 20.15 获取收藏电影

| 项目 | 详情 |
|------|------|
| **方法** | `GET` |
| **路径** | `/users/{username}/favorites/movies` |
| **特性** | 📄 ✨ |

---

### 20.16 获取收藏剧集

| 项目 | 详情 |
|------|------|
| **方法** | `GET` |
| **路径** | `/users/{username}/favorites/shows` |
| **特性** | 📄 ✨ |

---

### 20.17 获取隐藏项目

| 项目 | 详情 |
|------|------|
| **方法** | `GET` |
| **路径** | `/users/{username}/hidden/{section}` |
| **认证** | 🔒 |

**路径参数**: `section` — `calendar` | `watched` | `collected` | `recommendations`

---

### 20.18 添加隐藏项目

| 项目 | 详情 |
|------|------|
| **方法** | `POST` |
| **路径** | `/users/{username}/hidden/{section}` |
| **认证** | 🔒 |

---

### 20.19 移除隐藏项目

| 项目 | 详情 |
|------|------|
| **方法** | `POST` |
| **路径** | `/users/{username}/hidden/remove/{section}` |
| **认证** | 🔒 |

---

### 20.20 获取关注者

| 项目 | 详情 |
|------|------|
| **方法** | `GET` |
| **路径** | `/users/{username}/followers` |

---

### 20.21 获取正在关注

| 项目 | 详情 |
|------|------|
| **方法** | `GET` |
| **路径** | `/users/{username}/following` |

---

### 20.22 获取好友

| 项目 | 详情 |
|------|------|
| **方法** | `GET` |
| **路径** | `/users/{username}/friends` |

---

### 20.23 关注用户

| 项目 | 详情 |
|------|------|
| **方法** | `POST` |
| **路径** | `/users/{username}/follow` |
| **认证** | 🔒 |

---

### 20.24 取消关注用户

| 项目 | 详情 |
|------|------|
| **方法** | `DELETE` |
| **路径** | `/users/{username}/follow` |
| **认证** | 🔒 |

---

### 20.25 获取关注请求

| 项目 | 详情 |
|------|------|
| **方法** | `GET` |
| **路径** | `/users/requests` |
| **认证** | 🔒 |

---

### 20.26 批准关注请求

| 项目 | 详情 |
|------|------|
| **方法** | `POST` |
| **路径** | `/users/requests/{id}` |
| **认证** | 🔒 |

---

### 20.27 拒绝关注请求

| 项目 | 详情 |
|------|------|
| **方法** | `DELETE` |
| **路径** | `/users/requests/{id}` |
| **认证** | 🔒 |

---

### 20.28 屏蔽用户

| 项目 | 详情 |
|------|------|
| **方法** | `POST` |
| **路径** | `/users/{username}/block` |
| **认证** | 🔒 |

---

### 20.29 取消屏蔽用户

| 项目 | 详情 |
|------|------|
| **方法** | `DELETE` |
| **路径** | `/users/{username}/block` |
| **认证** | 🔒 |

---

### 20.30 获取用户个人列表

| 项目 | 详情 |
|------|------|
| **方法** | `GET` |
| **路径** | `/users/{username}/lists` |

---

### 20.31 获取单个个人列表

| 项目 | 详情 |
|------|------|
| **方法** | `GET` |
| **路径** | `/users/{username}/lists/{id}` |

---

### 20.32 创建个人列表

| 项目 | 详情 |
|------|------|
| **方法** | `POST` |
| **路径** | `/users/{username}/lists` |
| **认证** | 🔒 |

**请求体**:

```json
{
  "name": "string (必填)",
  "description": "string (可选)",
  "privacy": "string — private|friends|public",
  "display_numbers": "boolean",
  "allow_comments": "boolean",
  "sort_by": "string",
  "sort_how": "string"
}
```

---

### 20.33 更新个人列表

| 项目 | 详情 |
|------|------|
| **方法** | `PUT` |
| **路径** | `/users/{username}/lists/{id}` |
| **认证** | 🔒 |

请求体同 20.32

---

### 20.34 删除个人列表

| 项目 | 详情 |
|------|------|
| **方法** | `DELETE` |
| **路径** | `/users/{username}/lists/{id}` |
| **认证** | 🔒 |

---

### 20.35 添加列表项目

| 项目 | 详情 |
|------|------|
| **方法** | `POST` |
| **路径** | `/users/{username}/lists/{id}/items` |
| **认证** | 🔒 |

**请求体**:

```json
{
  "movies": [{ "ids": { "trakt": 0 } }],
  "shows": [{ "ids": { "trakt": 0 } }],
  "seasons": [{ "ids": { "trakt": 0 } }],
  "episodes": [{ "ids": { "trakt": 0 } }]
}
```

---

### 20.36 移除列表项目

| 项目 | 详情 |
|------|------|
| **方法** | `POST` |
| **路径** | `/users/{username}/lists/{id}/items/remove` |
| **认证** | 🔒 |

---

### 20.37 重新排序列表项目

| 项目 | 详情 |
|------|------|
| **方法** | `POST` |
| **路径** | `/users/{username}/lists/{id}/items/reorder` |
| **认证** | 🔒 |

**请求体**: `{ "rank": [item_id1, item_id2, ...] }`

---

### 20.38 获取用户评论

| 项目 | 详情 |
|------|------|
| **方法** | `GET` |
| **路径** | `/users/{username}/comments/{type}` |
| **特性** | 📄 |

**路径参数**: `type` — `all` | `movie` | `show` | `season` | `episode` | `list`

---

### 20.39 获取用户点赞的评论

| 项目 | 详情 |
|------|------|
| **方法** | `GET` |
| **路径** | `/users/{username}/likes/{type}` |
| **特性** | 📄 |

**路径参数**: `type` — `comments` | `lists`

---

### 20.40 获取用户收藏

| 项目 | 详情 |
|------|------|
| **方法** | `GET` |
| **路径** | `/users/{username}/collection/{type}` |
| **特性** | ✨ |

**路径参数**: `type` — `movies` | `shows` | `all`

---

### 20.41 获取社交活动

| 项目 | 详情 |
|------|------|
| **方法** | `GET` |
| **路径** | `/users/{username}/social` |

---

### 20.42 举报用户

| 项目 | 详情 |
|------|------|
| **方法** | `POST` |
| **路径** | `/users/{username}/report` |
| **认证** | 🔒 |

---

## 21. 通用数据结构

### 21.1 IDs 对象

**Movie IDs**:

```json
{
  "trakt": 0,
  "slug": "movie-slug-year",
  "imdb": "tt0000000",
  "tmdb": 0
}
```

**Show IDs**:

```json
{
  "trakt": 0,
  "slug": "show-slug",
  "imdb": "tt0000000",
  "tmdb": 0,
  "tvdb": 0
}
```

**Episode IDs**:

```json
{
  "trakt": 0,
  "slug": "show-slug-s1-e1",
  "imdb": "tt0000000",
  "tmdb": 0,
  "tvdb": 0
}
```

**Person IDs**:

```json
{
  "trakt": 0,
  "slug": "person-name",
  "imdb": "nm0000000",
  "tmdb": 0,
  "tvrage": 0
}
```

### 21.2 Sync 响应格式

```json
{
  "added": { "movies": 0, "shows": 0, "seasons": 0, "episodes": 0 },
  "existing": { "movies": [], "shows": [], "seasons": [], "episodes": [] },
  "not_found": { "movies": [], "shows": [], "seasons": [], "episodes": [] }
}
```

### 21.3 分页响应头

| Header | 描述 |
|--------|------|
| `X-Pagination-Page` | 当前页码 |
| `X-Pagination-Limit` | 每页数量 |
| `X-Pagination-Page-Count` | 总页数 |
| `X-Pagination-Item-Count` | 总项目数 |

---

## 22. 通用参数说明

### 22.1 通用过滤器参数

以下参数适用于 trending/watched/played/collected/anticipated/popular/streaming/hot 等列表端点：

| 参数 | 类型 | 描述 |
|------|------|------|
| extended | string | 扩展信息级别：`full`、`images`、`full,images`、`metadata` |
| watchnow | string | 流媒体筛选：`favorites`/`any`/`any_all`/`free`/`free_all`/`subscriptions`/`subscriptions_all` |
| genres | string | 类型 slug，逗号分隔（OR 逻辑） |
| subgenres | string | 子类型 slug |
| years | string | 4位年份或范围（如 `2016` 或 `2016-2020`） |
| ratings | string | Trakt 评分范围 0-100 |
| start_date | string | 起始日期 |
| end_date | string | 结束日期 |
| runtimes | string | 时长范围（分钟，如 `30-90`） |
| countries | string | 2字符国家代码 |
| certifications | string | 内容分级 |
| page | integer | 页码（默认1） |
| limit | integer | 每页数量（默认10，最大100） |
| ignore_watched | boolean | 忽略已看项目 |
| ignore_collected | boolean | 忽略已收藏项目 |
| ignore_watchlisted | boolean | 忽略已在想看列表的项目 |

### 22.2 Extended Info 参数

| 值 | 描述 |
|------|------|
| (空) | 最小信息 |
| `full` | 完整对象（含所有详情） |
| `images` | 包含图片 URL |
| `full,images` | 完整对象 + 图片 |
| `metadata` | 元数据 |
| `guest_stars` | 客串演员（仅 people 端点） |
| `episodes` | 所有集（仅 seasons 端点） |

### 22.3 速率限制

| Header | 描述 |
|--------|------|
| `X-RateLimit-Limit` | 速率限制上限 |
| `X-RateLimit-Remaining` | 剩余请求数 |
| `X-RateLimit-Reset` | 限制重置时间（UTC 时间戳） |

**建议**: 每秒不超过1次请求，批量操作添加 500ms-1000ms 延迟

---

> 文档基于 Trakt API v2 官方文档 (https://docs.trakt.tv, https://trakt.docs.apiary.io) 整理
> 最后更新: 2026-06-24
