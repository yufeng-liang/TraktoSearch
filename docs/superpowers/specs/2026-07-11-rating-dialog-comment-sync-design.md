# 打分弹窗增加短评 + 评分/短评同步豆瓣+Trakt 设计

## 背景

当前详情页标记已看后立即同步豆瓣（仅标记 collect，无评分无短评），随后弹出打分弹窗。
用户希望：标记已看 → 弹打分弹窗 → 用户打分（可选附带短评）→ 确认 → 一次性把「看过+评分+短评」同步到豆瓣和 Trakt。
不打分直接关闭弹窗时，仅同步「看过」到豆瓣。

同时修复：`checkAndUnify` 在上传云端之后执行，导致云端 `synced_items.json` 中的 status 未统一。

## Part 1: 冲突处理策略调整（checkAndUnify 前移 + 回写本地表）

### 问题

`uploadToCloudAfterSync` 当前顺序：
1. `recordLocalSync`
2. `cloudPersonalSyncManager.uploadAll` — 上传 synced_items（含原始 status）
3. `uploadIfHasFailures`
4. `uploadDirtyDetails`
5. `fillMediaTypeFromCloudPool`
6. `checkAndUnify` — 状态统一（最后一步，不回写本地表）

云端数据未经统一。

### 方案

**调整顺序**：`checkAndUnify` 移到 `uploadAll` 之前，且 `checkAndUnify` 回写本地表 status。

```
新顺序：
1. recordLocalSync
2. checkAndUnify（回写本地表 status）  ← 前移
3. cloudPersonalSyncManager.uploadAll（上传统一后数据）
4. uploadIfHasFailures
5. uploadDirtyDetails
6. fillMediaTypeFromCloudPool
```

### checkAndUnify 回写逻辑

在 `DoubanTraktStatusConsistencyChecker` 中，豆瓣侧 `markInterest` 成功后回写本地表：

- 豆瓣 wish → collect（升级）：`doubanSyncedItemDao.updateStatus(doubanId, "collect")`
- 豆瓣未标记 → wish/collect：`doubanSyncedItemDao.updateStatus(doubanId, action)`

Trakt 侧批量标记不涉及本地表回写（Trakt 状态不在 synced_items 表中，由 WatchlistWatchedIds 缓存管理）。

### DoubanSyncedItemDao 新增方法

```kotlin
@Query("UPDATE douban_synced_items SET status = :status WHERE doubanId = :doubanId")
suspend fun updateStatus(doubanId: String, status: String)
```

## Part 2: 打分弹窗增加短评 + 评分/短评同步

### 时序调整

```
原：标记已看 → 立即 syncDoubanMark(COLLECT) → 弹打分弹窗
新：标记已看 → 不立即同步豆瓣 → 弹打分弹窗 →
    用户确认(打分+短评) → 一次性同步「看过+评分+短评」到豆瓣 + Trakt评分+Trakt评论
    用户关闭弹窗(不打分) → 关闭时仅同步「看过」到豆瓣
```

### 评分映射

Trakt 1-10 分（半星步长）→ 豆瓣 1-5 整星：`Math.round(traktRating / 2.0)`

| Trakt | 豆瓣 |
|-------|------|
| 1-2   | 1    |
| 3-4   | 2    |
| 5-6   | 3    |
| 7-8   | 4    |
| 9-10  | 5    |

### UI 改动（RatingDialog）

`RatingDialog` 签名变更：

```kotlin
@Composable
internal fun RatingDialog(
    initialRating: Int?,
    initialComment: String?,        // 新增：已有短评回显
    isSubmitting: Boolean,
    onDismiss: () -> Unit,          // 关闭时回调（不打分）
    onConfirm: (Int?, String) -> Unit  // 评分 + 短评
)
```

UI 新增：
- 星级选择器下方增加 `OutlinedTextField`（短评输入框，可选）
- 提示文字 `detail_rating_comment_hint`（如"短评（可选）"）
- 最大字符数 350（豆瓣短评限制）

### DoubanRepository 新增正式方法

基于已验证的 `markWatchedWithRatingForTest`，新增正式方法：

```kotlin
suspend fun markWatchedWithRating(
    doubanId: String,
    cookie: String,
    ck: String,
    rating: Int,           // 1..5 豆瓣五星制
    comment: String = ""
): MarkWriteResult
```

端点：`POST /j/subject/{id}/interest`，表单含 `interest=collect` + `rating` + `comment`。
成功判断：HTTP 2xx + 响应体含 `"r":0`。

### TraktApiService 新增评论端点

```kotlin
@POST("comments")
suspend fun postComment(@Body body: TraktCommentRequest): Response<TraktCommentResponse>
```

DTO：

```kotlin
@Serializable
data class TraktCommentRequest(
    val item: TraktCommentItem,
    val comment: String,
    val spoiler: Boolean = false
)

@Serializable
data class TraktCommentItem(
    val type: String,           // "movie" 或 "show"
    val ids: TraktCommentItemId
)

@Serializable
data class TraktCommentItemId(
    val trakt: Int
)
```

成功返回 201。

### TraktRepository 新增方法

```kotlin
suspend fun postComment(traktId: Int, type: MediaType, comment: String, spoiler: Boolean = false): Result<TraktComment>
```

### DetailViewModel 改动

#### 1. setRating 改为 setRatingWithComment

```kotlin
fun setRatingWithComment(rating: Int, comment: String)
```

- Trakt 评分走 `traktRepository.addRating`（已有）
- Trakt 评论走 `traktRepository.postComment`（新增），comment 非空时才发
- 豆瓣评分+短评走 `doubanRepository.markWatchedWithRating`（新增）
- 评分映射：`val doubanRating = Math.round(rating / 2.0)`

#### 2. toggleWatched 时序调整

标记已看成功后：
- **不再立即** 调用 `syncDoubanMark(DoubanSyncAction.COLLECT)`
- 设置 `pendingDoubanAction = DoubanSyncAction.COLLECT`
- 弹出打分弹窗 `showRatingDialog = true`

#### 3. dismissRatingDialog 增加豆瓣同步

用户关闭打分弹窗（不打分）时：
- 若 `pendingDoubanAction == COLLECT` → 调用 `syncDoubanMark(COLLECT)`（仅标记看过，无评分无短评）
- 清除 `pendingDoubanAction`

#### 4. onConfirm 回调处理

打分弹窗确认时：
- 若 rating != null → 调用 `setRatingWithComment(rating, comment)`
- `setRatingWithComment` 内部同步豆瓣（若 pendingDoubanAction == COLLECT，用 `markWatchedWithRating` 一次性传看过+评分+短评；否则仅更新评分+短评）
- 清除 `pendingDoubanAction`

#### 5. removeRating 调整

取消评分时也同步豆瓣（传 rating=0 仅清除豆瓣评分，但豆瓣 `/interest` 端点无清除评分的独立操作，只能通过重新标记 collect 不传 rating 来覆盖）。
简化处理：removeRating 仅移除 Trakt 评分，不同步豆瓣（豆瓣评分保留，避免复杂度）。

### 4 语言字符串

| key | 中文 | 英文 | 日文 | 韩文 |
|-----|------|------|------|------|
| `detail_rating_comment_hint` | 短评（可选） | Comment (optional) | コメント（任意） | 코멘트 (선택) |

## 涉及文件

| 文件 | 改动 |
|------|------|
| `DoubanEntities.kt` | 新增 `DoubanSyncedItemDao.updateStatus` |
| `DoubanTraktStatusConsistencyChecker.kt` | 豆瓣标记成功后回写本地表 |
| `DoubanSyncManager.kt` | `uploadToCloudAfterSync` 中 checkAndUnify 前移 |
| `DoubanRepository.kt` | 新增 `markWatchedWithRating` 正式方法 |
| `TraktApiService.kt` | 新增 `postComment` 端点 |
| `TraktDtos.kt` | 新增 `TraktCommentRequest` 等 DTO |
| `TraktRepository.kt` | 新增 `postComment` 方法 |
| `DetailRatingsDialog.kt` | `RatingDialog` 增加短评输入框 |
| `DetailViewModel.kt` | 时序调整 + `setRatingWithComment` |
| `DetailScreen.kt` | `RatingDialog` 调用签名调整 |
| `strings.xml` × 4 | 新增 `detail_rating_comment_hint` |
