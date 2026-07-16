# 豆瓣失败项媒体类型全局共享设计

## 背景与目标

用户在豆瓣失败项详情页/列表页手动标注的媒体类型（movie/show），目前仅保存在本地 Room 数据库。
目标：将用户标注的类型上传到全局共享池（复用 `CloudDetailsPoolManager`），让其他用户在豆瓣同步完成后自动获取类型标注，减少手动标注成本。

## 架构

复用现有 `CloudDetailsPoolManager`（`details_pool/{shard}.json`），不新建池。

`DoubanDetailCacheEntry` 已有 `isTvShow: Boolean` 字段（目前从豆瓣详情页爬取的类型信息）。
用户标注映射：
- `"movie"` → `isTvShow = false`
- `"show"` → `isTvShow = true`
- `null` → 不上传（避免清除池中其他用户的有效数据）

## 数据流

### 上传（标记后立即）

1. 用户点击「标记为电影/电视剧」
2. ViewModel 调用 `DoubanRetryManager.updateMediaType()` 更新本地 Room（已有逻辑）
3. 新增：同时调用 `CloudDetailsPoolManager.uploadUserMarkedType(doubanId, isTvShow)` 异步上传
4. 上传逻辑：GET 对应分片 → 合并（用户标注覆盖 isTvShow，其他字段保留池中原值）→ PUT 回去
5. 失败不阻塞 UI，仅记日志

### 下载（同步后批量）

1. 豆瓣同步完成后，`DoubanSyncManager` 调用 `fillMediaTypeFromCloudPool()`
2. 查询本地 `mediaType IS NULL` 的失败项 doubanId 列表
3. 批量调用 `CloudDetailsPoolManager.downloadDetails()` 拉取对应详情
4. 命中的 `isTvShow` 映射回 `mediaType`（`true` → "show"，`false` → "movie"）
5. 批量更新本地 Room（仅填充 null 的，不覆盖用户已手动标注的）
6. 失败不阻塞主流程

## 冲突处理

- **上传**：用户标注始终覆盖池中 `isTvShow`（用户手动确认 > 爬取数据）
- **下载**：仅填充本地 `mediaType == null` 的失败项，不覆盖用户已标注的

## 改动范围

### 新增

- `CloudDetailsPoolManager.uploadUserMarkedType(doubanId, isTvShow)`：单条上传，复用分片上传逻辑
- `DoubanSyncFailureDao.getFailuresWithNullMediaType()`：查询未标注类型的失败项 doubanId
- `DoubanSyncFailureDao.batchUpdateMediaType(updates)`：批量更新 mediaType
- `DoubanSyncManager.fillMediaTypeFromCloudPool()`：同步完成后填充未标注失败项的类型

### 修改

- `DoubanRetryManager`：注入 `CloudDetailsPoolManager`，`updateMediaType()` 中新增异步上传
- `DoubanItemDetailViewModel.setMediaType()`：上传逻辑已在 `DoubanRetryManager` 中处理，无需改动
- `DoubanFailuresViewModel.setMediaType()`：同上
- `DoubanSyncManager`：同步完成后调用 `fillMediaTypeFromCloudPool()`

## 边界情况

- 用户清除标注（设为 null）：不上传，不删除池中数据
- 池中条目无 `isTvShow` 字段（旧数据）：跳过，不填充
- 网络失败：静默失败，不影响主流程
