# 豆瓣电影标记 → Trakt 同步器 设计规格

## 背景与目标

让用户在 App 内登录豆瓣，把豆瓣的「想看 / 看过」标记记录同步写入用户的 Trakt 账号，使 watchlist 页（已从 Trakt 拉数据）能展示合并后的完整列表。参考项目 [Geetheshe/DoubanMovieListBackUpToNotion](https://github.com/Geetheshe/DoubanMovieListBackUpToNotion) 的爬虫思路。

## 核心决策汇总（已与用户确认）

| 决策项 | 选择 |
|--------|------|
| 豆瓣登录方式 | App 内 WebView 登录，自动抓 Cookie + userId |
| 合并粒度 | 同步写入 Trakt 服务器，watchlist 页不改 UI |
| 「在看」状态 | 丢弃，只同步「想看」「看过」 |
| 同步时机 | 首次登录后自动全量 + 设置页「重新导入」按钮 |
| 冲突处理 | 豆瓣优先，覆盖 Trakt 已有状态 |
| 进度显示 | 前台对话框起步，可「转后台」→ Foreground Service + 通知栏 |

## 技术约束

- 豆瓣**无 OAuth API**（2014 年关闭），只能靠 Cookie 模拟登录爬取 HTML
- 豆瓣反爬严格：需随机延迟、桌面 UA、带 Cookie
- 豆瓣 `movie.douban.com/people/{id}/collect|wish` 混含电影和电视剧，无法分开爬
- Trakt 已确认支持：`POST /sync/watchlist`（想看）、`POST /sync/history`（看过，可带 `watched_at`）、`DELETE /sync/watchlist`（移除，用于覆盖冲突）、`POST /sync/ratings`（评分）

## 整体架构与数据流

```
[豆瓣 WebView 登录] → Cookie + userId
        ↓
[豆瓣爬虫] 爬 /people/{id}/wish|collect HTML，每页 15 条
        ↓ 每条：标题/评分/短评/标记时间/豆瓣链接/封面
[详情页爬取] 访问条目详情页拿 imdbId（全球唯一标识）
        ↓
[Trakt 匹配] 项目已有 search by imdbId → traktId 能力
        ↓
[Trakt 同步] 豆瓣优先覆盖：必要时先 DELETE 再 POST
        ↓   想看 → POST /sync/watchlist
        ↓   看过 → POST /sync/history（带 watched_at）
        ↓   评分 → POST /sync/ratings
[同步状态库] Room 记录已同步条目，重启不重复
        ↓
[进度通知] 前台对话框 → 可转 Foreground Service + 通知栏
```

## 组件设计

### 4.1 登录页改造（LoginScreen）

现有 [LoginScreen.kt](file:///f:/trae-project/app/src/main/java/com/tracktosearch/ui/screen/login/LoginScreen.kt) 只有一个 Trakt 登录按钮。改造为：
- Trakt 登录按钮（保留原样，主按钮样式）
- 分隔线 + 「或从豆瓣导入标记」次按钮（TextButton 次要样式，不抢主入口）
- 点击次按钮 → 导航到 `DoubanLoginScreen`

### 4.2 豆瓣 WebView 登录（DoubanLoginScreen + DoubanAuthManager）

- `DoubanLoginScreen`：内嵌 Android `WebView`，初始加载 `https://www.douban.com/`
- 用户在 WebView 内输入账号密码登录（含验证码/手机验证由 WebView 原生处理）
- 监听 URL 变化，每次页面加载完成后检查 `CookieManager` 中是否出现 `dbcl2` cookie（豆瓣登录态的核心 cookie）：
  - 出现 `dbcl2` 即判定登录成功
  - 从 `CookieManager.getInstance().getCookie("https://movie.douban.com")` 取完整 Cookie 字符串
  - 从 `dbcl2` cookie 解析 userId（格式 `"userid:xxxxx"`，前半段即 userId）
- 拿到 Cookie + userId 后回调，触发首次全量同步

`DoubanAuthManager`（Hilt 单例）：
- 持有 DataStore 存储的 userId + Cookie（**加密存储**，用 EncryptedSharedPreferences 或 DataStore + AES）
- 提供 `getCredentials()` / `saveCredentials()` / `clearCredentials()`
- 提供 `isLoggedIn` StateFlow

### 4.3 豆瓣爬虫（DoubanRepository / DoubanSpider）

- 用 OkHttp（项目已用）+ Jsoup（新增依赖）解析 HTML
- URL：
  - 想看：`https://movie.douban.com/people/{userId}/wish?start={n*15}&sort=time&mode=grid`
  - 看过：`https://movie.douban.com/people/{userId}/collect?start={n*15}&sort=time&mode=grid`
- 每页 15 条，`start` 从 0 递增，直到页面无 `div.item` 即结束
- 请求头：桌面 UA + Cookie
- **反爬延迟**：每页间随机 5-10 秒，每个详情页访问前随机 3-5 秒（参考项目做法）

每页解析字段（参考 DoubanSpider.py）：
- 标题：`em` 标签文本
- 评分：`span.rating\d-t` 的 class 解析为 1-5
- 短评：`span.comment` 文本
- 标记时间：`span.date` 文本（格式 `yyyy-MM-dd`）
- 豆瓣链接：`a[href]`
- 封面：详情页 `img[rel=v:image]` 的 src，`s_ratio_poster` → `l`，`webp` → `jpg`

详情页爬取（访问 movie_link）：
- imdbId：`span.pl:contains(IMDb)` 的下一兄弟节点文本
- 类型：`span[property=v:genre]`（用于判断 movie/show，有「电视剧」类型即视为 show）

### 4.4 Trakt 同步（DoubanSyncManager）

复用项目已有能力：
- `TraktRepository.getCachedTraktId(imdbId)` → traktId（项目已有 search by imdbId 缓存）
- `TraktAuthManager` 的 OAuth token

同步逻辑（豆瓣优先覆盖）：
1. 对每条豆瓣条目，先用 imdbId 查 traktId（缓存优先，未命中走 search API）
2. 查 Trakt 当前对该条目的状态（watchlistWatchedIds 全局缓存）
3. 豆瓣优先覆盖（已确认）：
   - 豆瓣「看过」+ Trakt「想看」→ DELETE /sync/watchlist + POST /sync/history
   - 豆瓣「想看」+ Trakt「看过」→ 不覆盖（看过是更强状态，保留 Trakt 已看）
   - 状态相同 → 跳过
   - Trakt 无标记 → 直接 POST
4. 豆瓣评分 → POST /sync/ratings（1-5 分对应 Trakt 1-5 分）
5. 批量推送：每批最多 50 条（Trakt 单次请求限制）

**短评不同步**：Trakt comments 需逐条单独 POST 且非 sync 方法，工作量与价值不匹配，默认不同步。

### 4.5 同步状态库（Room）

新增 `DoubanSyncedItem` 实体：
```
@Entity(tableName = "douban_synced_items")
data class DoubanSyncedItem(
    @PrimaryKey val doubanId: String,   // 豆瓣条目 ID（从链接解析）
    val imdbId: String?,
    val traktId: Int?,
    val title: String,
    val status: String,                 // "wish" | "collect"
    val rating: Int?,                   // 1-5
    val syncedAt: Long,                 // 时间戳
    val mediaType: String               // "movie" | "show"
)
```
- 「重新导入」时默认跳过已同步条目（除非用户在同步对话框勾选「强制覆盖」）
- 提供失败列表记录：`failedItems`（标题 + 失败原因），同步结束在对话框展示

### 4.6 进度通知（DoubanSyncService）

前台对话框（`DoubanSyncDialog`）：
- 显示「正在同步豆瓣标记 (12/348)」+ 进度条
- 「转后台运行」按钮 → 启动 Foreground Service，关闭对话框
- 「取消」按钮 → 取消同步协程

Foreground Service（`DoubanSyncService`）：
- `notification` 显示进度（带进度条，可更新）
- 点击通知可回到 App 的同步对话框
- 通知带「取消」Action
- 同步完成或取消后自动停止 Service
- 持有协程作用域，Service 销毁时取消同步

## 数据存储

- **DataStore（加密）**：豆瓣 userId + Cookie
- **Room**：`DoubanSyncedItem` 同步记录表
- **不存豆瓣条目完整数据**：同步完即丢，只在 Trakt 端保留（避免本地数据膨胀）

## 错误处理

| 错误场景 | 处理 |
|---------|------|
| Cookie 过期（爬取返回登录页） | 提示「豆瓣登录已过期，请重新登录」 |
| 豆瓣 IP 反爬（连续 403/空页） | 提示「豆瓣限制访问，请稍后再试或换网络」 |
| 详情页访问失败 | 记录失败项，跳过继续 |
| imdbId 缺失 | 无法匹配 Trakt，记录失败项跳过 |
| Trakt search 无结果 | 记录失败项（Trakt 库无此条目） |
| Trakt API 写入失败 | 重试 2 次，仍失败则记录 |

所有失败项在同步结束后于对话框汇总展示（「成功 320 条，失败 28 条，点击查看详情」）。

## 测试策略

- 豆瓣爬虫：用固定 HTML 样本（保存真实页面快照）写单元测试，验证字段解析
- Trakt 同步：mock Trakt API 响应，验证覆盖逻辑
- 端到端：小规模真实账号（10-20 条标记）手动验证

## 范围边界（YAGNI）

**不做**：
- 双向同步（Trakt → 豆瓣）
- 定时自动同步
- 「在看」状态
- 豆瓣短评 → Trakt 评论
- 豆瓣书单/音乐标记（仅电影/电视剧）
- 多账号管理（同时只支持一个豆瓣账号）

## 已确认决策

1. **冲突细分**：豆瓣「想看」+ Trakt「看过」→ **不覆盖**（看过是更强状态，保留 Trakt 已看）。豆瓣「看过」+ Trakt「想看」→ 覆盖（DELETE watchlist + POST history）。
2. **评分同步**：**同步**豆瓣评分到 Trakt，以豆瓣为准（POST /sync/ratings，1-5 分对应 Trakt 1-5 分）。——同步，以豆瓣为准
