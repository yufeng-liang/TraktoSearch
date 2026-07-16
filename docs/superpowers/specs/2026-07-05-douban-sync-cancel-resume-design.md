# 豆瓣同步取消续传优化 + 通知栏图标 设计规格

## 背景

豆瓣同步功能存在以下问题:
1. 通知栏图标用系统下载箭头 `stat_sys_download`,语义偏"下载"非"同步",国产 ROM 经常魔改显示异常
2. 用户取消同步时,`persistFailures` 在 `allFailed` 为空时调用 `clearAll()`,会清空之前所有状态的失败项记录
3. `fetchMarkList` 内部不检查 `cancelled`,取消后还要爬完所有列表页(1000 条 ≈ 10 分钟)
4. `syncBatchToTrakt` 阶段 4(批量 POST Trakt)无取消点,取消后最多要等 60 秒才停止
5. 列表爬取进度不持久化,取消后已爬到的 pageItems 全部丢失,下次必须从头爬

## 决策

| # | 决策 | 选择 |
|---|------|------|
| 1 | 通知栏图标 | 新建 `ic_sync.xml` VectorDrawable(Material Sync 图标,白色单色) |
| 2 | persistFailures 清空 bug | 修复:取消时不清空表,按 status 精细化覆盖 |
| 3 | fetchMarkList 可中断 | 加 `isCancelled` 参数,每页爬完检查 |
| 4 | 阶段 4 加取消点 | `withTimeout` 之前加 `if (cancelled) return` |
| 5 | 列表进度持久化策略 | 持久化 pageItems + 续传选项 |
| 6 | 续传入口时机 | App 启动时主动检测并提示 |
| 7 | pending 与 failures 关系 | 优先处理 pending,处理完后再走失败重试 |

## 数据结构

### `DoubanSyncPendingItemEntity`(新增 Room Entity)

```kotlin
@Entity(tableName = "douban_sync_pending_items")
data class DoubanSyncPendingItemEntity(
    @PrimaryKey val doubanId: String,
    val title: String,
    val posterUrl: String?,
    val rating: Int?,
    val comment: String?,
    val markedAt: String,
    val doubanUrl: String,
    val status: String,        // "wish" / "collect"
    val crawledAt: Long        // 爬到该条目的时间戳
)
```

- **写入时机**:`runSync` 每爬完一页 `fetchMarkList` 的 `onPage` 回调中,批量 insertAll
- **清理时机**:`syncBatchToTrakt` 处理完一批后删除已处理的 doubanId(无论成功还是失败)
- **续传检测**:App 启动时查询该表是否有数据,有则提示用户

### DAO

```kotlin
@Dao
interface DoubanSyncPendingItemDao {
    @Query("SELECT * FROM douban_sync_pending_items")
    suspend fun getAll(): List<DoubanSyncPendingItemEntity>

    @Query("SELECT COUNT(*) FROM douban_sync_pending_items")
    suspend fun count(): Int

    @Query("SELECT * FROM douban_sync_pending_items WHERE status = :status")
    suspend fun getByStatus(status: String): List<DoubanSyncPendingItemEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<DoubanSyncPendingItemEntity>)

    @Query("DELETE FROM douban_sync_pending_items WHERE doubanId IN (:ids)")
    suspend fun deleteByDoubanIds(ids: List<String>)

    @Query("DELETE FROM douban_sync_pending_items")
    suspend fun clearAll()
}
```

## 改动详情

### 1. 通知栏图标

新建 `app/src/main/res/drawable/ic_sync.xml`(VectorDrawable,Material Sync 图标路径,白色单色)。
`DoubanSyncService.buildNotification` 的 `setSmallIcon` 改用 `R.drawable.ic_sync`。

### 2. 修复 persistFailures 清空 bug

**根因**:`persistFailures` 在 `failures.isEmpty()` 时调用 `clearAll()`,清空之前所有状态的失败项记录。

**修复**:
- 同步正常完成且 `allFailed` 为空 → 不调用 `clearAll()`,保留之前的失败项记录
- 改为按 status 精细化覆盖:只删除当前 status 的旧失败项,再插入新的

### 3. fetchMarkList 加 cancelled 检查

`DoubanRepository.fetchMarkList` 增加 `isCancelled: () -> Boolean` 参数,内部 `while` 循环每爬完一页检查一次,已取消则 break。取消后最多再爬完当前页(5-10 秒)即停止。

### 4. syncBatchToTrakt 阶段 4 加取消点

`syncBatchToTrakt` 阶段 4(`withTimeout` 之前)加 `if (cancelled) return BatchSyncResult(0, failed, skippedCount, detailCacheHit)`。

被跳过的 `batchToInsert` 不会丢失 — 下次重试时:
- 已成功同步的:通过 `douban_synced_items` 表跳过
- 未同步的:详情页缓存命中(省 3-5 秒/条),重新走 syncBatchToTrakt

### 5. 列表进度持久化

`runSync` 每爬完一页 `fetchMarkList` 的 `onPage` 回调中,把 pageItems 写入 `douban_sync_pending_items` 表(批量 insertAll)。这样取消时已爬到的列表数据全部持久化。

### 6. App 启动检测 + 续传对话框

App 启动时(在 MainActivity 或 Application 初始化后)查询 `douban_sync_pending_items` 表是否有数据。有数据则弹对话框:

> 上次同步有 N 条已爬到的列表未处理完,是否继续?
> - 继续同步(跳过列表爬取,直接处理已爬到的数据)
> - 完整同步(清空未处理数据,从头开始)

- **继续**:跳过列表爬取,直接从该表加载 pageItems 走 syncBatchToTrakt;处理成功的从该表删除,处理完所有条目后清空该表
- **完整同步**:清空该表,从头开始爬列表

### 7. pending 与 failures 关系

优先处理 pending items,处理完后再走失败重试流程。
- App 启动时同时检测 pending items 和 failures
- 有 pending items → 弹续传对话框(优先处理)
- 无 pending items 但有 failures → 弹失败重试对话框
- 都没有 → 正常流程

## 改动文件清单

### 新增(3 个)
- `res/drawable/ic_sync.xml` — 同步图标 VectorDrawable
- `data/local/db/DoubanSyncPendingItemEntity.kt` — Room Entity + DAO(可合并到 DoubanEntities.kt)
- `ui/screen/douban/DoubanPendingItemsDialog.kt` — 续传选项对话框

### 改造(5 个)
- `service/DoubanSyncService.kt` — `setSmallIcon` 改用 `R.drawable.ic_sync`
- `data/repository/DoubanSyncManager.kt` — 修复 persistFailures、加阶段 4 取消点、列表爬取持久化、续传入口
- `data/remote/douban/DoubanRepository.kt` — `fetchMarkList` 增加 `isCancelled` 参数
- `data/local/db/AppDatabase.kt` + `DatabaseModule.kt` — version 5→6,新增表 + MIGRATION
- `ui/MainActivity.kt` 或 `ui/screen/...` — App 启动检测 pending items

## 验证

构建 debug 包验证:
```
.\gradlew assembleDebug
```

确保构建成功且无功能影响。
