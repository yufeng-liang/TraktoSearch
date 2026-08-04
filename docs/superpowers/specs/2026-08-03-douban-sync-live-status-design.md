# 豆瓣同步实时状态与通知层级设计

日期：2026-08-03
状态：视觉方案 B 已获批准，待用户审阅规格文档

## 1. 背景与目标

本次调整覆盖豆瓣导入/同步从开始到结束的完整用户可见链路：设置页入口、同步对话框、Watchlist 横幅、Android 系统通知、Android 16+ Live Update 状态栏芯片、通知展开态和锁屏通知。

目标：

1. 未登录豆瓣时，设置页入口标题显示“同步豆瓣”，不显示“重新同步豆瓣”。已登录且已有同步记录时仍显示“重新同步豆瓣”。
2. 使用稳定的同步阶段 ID，让对话框、Watchlist、系统通知和 Live Update 使用同一套阶段语义，阶段名不重复、不因模式不同而漂移。
3. 同步对话框实时展示最近拿到的最多 5 条豆瓣列表数据：标题、想看/看过、评分、标记日期。
4. 过程通知显示阶段、子状态、进度、当前条目和预计剩余时间；系统通知不塞入完整数据列表，完整数据只在同步对话框展示。
5. Watchlist 横幅点击行为明确：同步中打开同步对话框，完成态打开结果，Cookie 过期直接进入豆瓣登录。
6. 完成态通知先发布最终状态，再结束前台 Service；结果对话框不因横幅 5 秒自动隐藏而丢失结果。

不在本次范围内：

- 改变豆瓣爬取请求、反爬延迟、并发度或同步冲突策略。
- 把最近 5 条豆瓣数据放入系统通知或锁屏通知。
- 在居中挖孔状态栏中模拟左右双栏；Android Live Update 芯片只显示一段短文本。
- 重做同步模式选择器、失败项管理页或状态一致性检查本身的视觉设计。

## 2. 当前问题

当前 `DoubanSyncProgress` 的 `phase` 和 `subPhase` 是同步管理器内直接拼接的字符串。不同同步路径会产生“爬取想看列表”“同步想看到 Trakt”“详情页”“写入 Trakt”等不同粒度的名称，UI 和通知直接显示这些字符串，导致阶段粒度不一致、同一阶段重复描述，且用户可见文字没有统一的资源映射。

当前 `DoubanSyncService` 只把 `phase/current/total` 传给通知，忽略 `subPhase`、`currentTitle`、`etaSeconds` 和 Cookie 过期状态。完成时 Collector 直接 `stopSelf()`，没有先发布最终通知状态。

当前 `DoubanSyncDialog` 已显示当前阶段、子阶段、当前条目、延迟和失败项，但没有显示最近成功拿到的豆瓣条目。列表页 `onPage` 回调已经拿到 `DoubanMarkItem` 的标题、评分、标记日期和状态，适合直接作为实时预览来源，不需要额外爬取。

当前 Watchlist 横幅整块点击都打开同步对话框。Cookie 过期时虽然显示“重新登录”，点击仍没有独立进入登录流程。`WatchlistViewModel` 在完成后 5 秒清理进度并调用 `resetProgress()`，可能导致仍打开的结果对话框收到默认空状态。

## 3. 信息层级约定

### 3.1 阶段定义

增加稳定的 `DoubanSyncStage`，生产代码不再通过可见字符串判断阶段。建议阶段如下：

| 阶段 ID | 状态栏短标签 | 对话框/通知含义 | 覆盖的内部工作 |
| --- | --- | --- | --- |
| `IDLE` | - | 没有正在进行的同步 | 初始值、重置后的空状态，不进入任何用户可见表面 |
| `PREPARING` | 准备同步 | 正在连接豆瓣或准备同步 | 会话检查、拉取云端数据、加载本地状态 |
| `FETCHING_LIST` | 获取列表 | 正在获取豆瓣列表 | 想看列表、看过列表的分页爬取 |
| `PARSING_DATA` | 解析数据 | 正在补齐豆瓣数据 | 详情页、IMDb/Trakt 查询、解析失败处理 |
| `UPDATING_LIST` | 更新清单 | 正在写入同步结果 | Trakt 批量写入、本地豆瓣表写入、状态变更 |
| `COMPLETED` | 同步完成 | 本次同步已结束 | 成功、跳过、缓存命中和失败统计 |
| `LOGIN_REQUIRED` | 需要登录 | 豆瓣未登录或 Cookie 已过期 | 未登录预检、Cookie 过期 |
| `CANCELLING` | 正在取消 | 用户请求取消，正在收尾 | 取消 checkpoint 和云端上传 |
| `FAILED` | 同步失败 | 同步异常结束 | 未预期异常，错误详情放在独立字段 |

`DoubanSyncSubStage` 只表达当前阶段内的具体工作，不重复主阶段名称。建议值包括 `CONNECTING`、`PULLING_CLOUD`、`FETCHING_WISH_LIST`、`FETCHING_COLLECT_LIST`、`FETCHING_DETAIL`、`LOOKING_UP_TRAKT`、`WRITING_TARGET`、`WRITING_LOCAL`、`STATUS_CHANGES`、`RETRYING_FAILURES`、`WAITING_DELAY` 和 `NONE`。动态数量、当前标题和异常文本分别放在数据字段中，不拼入阶段名。

### 3.2 各系统表面的固定职责

| 表面 | 主文案 | 动态信息 | 不显示 |
| --- | --- | --- | --- |
| 状态栏 Live Update 芯片 | 只显示阶段短标签，例如“获取列表” | 不超过系统芯片可读长度 | 标题、数量、当前电影名 |
| 通知收起态 | 固定标题“同步豆瓣” | 阶段、`current/total`、预计时间 | 最近 5 条数据、重复的“正在同步” |
| 通知展开态 | 固定标题“同步豆瓣” | 阶段、子状态、当前条目、成功/失败统计、取消动作 | 完整数据列表 |
| 锁屏通知 | 固定标题“同步豆瓣” | 阶段、进度、预计时间；Cookie 过期时显示需要登录 | 评分、标记日期和最近条目列表 |
| 同步对话框 | 固定标题“同步豆瓣” | 阶段徽章、子状态、当前条目、延迟倒计时、最近 5 条、失败项和最终统计 | 另一套独立阶段文案 |
| Watchlist 横幅 | 进行中显示阶段与进度；完成显示结果；过期显示重新登录 | 进度条、操作提示 | 仅有颜色或无法点击的“重新登录”文字 |

状态栏不显示“想看（15/60）”。推荐的动态组合是：状态栏“获取列表”；收起通知“同步豆瓣 / 获取列表 · 15/60”；展开通知补充“当前条目：沙丘 Dune”；对话框显示最近 5 条详细数据。

## 4. 数据与状态模型

### 4.1 实时预览数据

增加轻量内存模型，不写入新的 Room 表：

```kotlin
data class DoubanSyncPreviewItem(
    val doubanId: String,
    val title: String,
    val status: DoubanMarkStatus,
    val rating: Int?,
    val markedAt: String,
)
```

`DoubanSyncProgress` 增加：

```kotlin
val stage: DoubanSyncStage = DoubanSyncStage.IDLE
val subStage: DoubanSyncSubStage = DoubanSyncSubStage.NONE
val recentItems: List<DoubanSyncPreviewItem> = emptyList()
val errorMessage: String? = null
```

现有 `currentTitle`、`recentFailures`、`delayInfo`、统计字段和取消字段继续保留。生产 UI、Service 和阶段分支迁移到 `stage/subStage`；不再用 `phase.contains(...)` 判断 Trakt 未登录、豆瓣未登录或完成态。异常信息不再拼进阶段名，而使用 `errorMessage`，ViewModel 层日志保持英文，UI 使用本地化资源。

### 4.2 最近数据的产生规则

在每个同步流程的豆瓣列表 `onPage(items, ...)` 回调中，把当前页的 `DoubanMarkItem` 转换为 `DoubanSyncPreviewItem` 并写入有界缓冲区：

- 以 `status + doubanId` 去重，最新到达的条目排在前面。
- 最多保留 5 条，跨想看和看过列表持续更新。
- 后续详情解析、Trakt 查询、批量写入的进度更新必须通过 `copy` 保留 `recentItems`，不能因为子阶段变化清空预览。
- 列表页来自云端缓存、跳过实际爬取时，不伪造“刚刚爬取”的条目；对话框显示云端/跳过子状态即可。
- 取消、Cookie 过期和失败结束时保留已经拿到的预览，帮助用户判断本次实际取得的数据。

将列表回调缓冲和进度更新抽成一个小的内部辅助结构，供增量同步、完全重写、旧同步路径和失败重试路径复用，避免四套流程各自实现不同的最近列表规则。

## 5. 组件行为与数据流

1. `DoubanSyncManager` 在每个工作节点更新 `stage/subStage/current/currentTitle/etaSeconds/recentItems`，通过同一个 `StateFlow` 对外发布。
2. `DoubanSyncDialog` 收集该 StateFlow，使用 `stringResource` 将阶段 ID、子阶段 ID、状态和失败原因转换为文字；数据到达一条就刷新一条，最近数据行固定展示标题、状态、评分和标记日期。
3. `WatchlistViewModel` 继续转发进度和完成事件，但把“横幅可见性”和“完成结果保留”拆开：横幅可在 5 秒后隐藏，完成进度快照在结果对话框关闭前不能被 `resetProgress()` 清空。
4. `WatchlistScreen` 使用同一套阶段映射显示横幅。同步中点击打开对话框；完成态点击打开结果；`cookieExpired`/`LOGIN_REQUIRED` 点击直接调用 `onNavigateToDoubanLogin()`，不先打开一个没有有效动作的结果对话框。
5. `SettingsScreen` 的入口标题条件改为“运行中优先，其次未登录或从未同步显示 `settings_douban_sync`，其余显示 `settings_douban_resync`”。设置页的同步对话框传入豆瓣登录回调，使过期状态也能直接重新登录。
6. `DoubanSyncService` 将进度映射为通知状态：标题固定为“同步豆瓣”；收起文本使用阶段、进度和预计时间；展开内容加入子状态、当前条目和失败数；取消动作只在运行中显示。Android 16+ 的 `setShortCriticalText()` 使用本地化阶段短标签，Android 15 及以下继续使用普通进度通知。
7. 当 `isComplete`、`LOGIN_REQUIRED` 或 `FAILED` 到达时，Service 先发布最终通知，再结束前台 Service。完成通知为非进行中状态；登录过期通知携带进入豆瓣登录的内容 Intent；普通通知点击打开同步结果或进度对话框。

## 6. 本地化资源

所有用户可见的新阶段、子阶段、预览字段、横幅状态、通知摘要和错误动作都加入 `values/`、`values-zh/`、`values-ja/`、`values-ko/`。

已有资源的调整：

- `settings_douban_sync` 的中文显示改为“同步豆瓣”；英文统一为“Sync Douban”，其他语言保持对应的自然表达。
- `douban_sync_title` 与对话框/通知固定标题统一为“同步豆瓣”的本地化表达。
- 阶段资源不包含数量；数量通过已有格式资源或新的独立格式资源拼接，避免在状态栏短文本中出现数量。
- 新增最近数据标题、条目状态、无数据、评分缺失、标记日期、展开通知详情和需要登录动作的资源。

## 7. 验证方案

### 单元测试

- `DoubanSyncManager`：列表页回调产生预览；跨页面去重；最多 5 条；后续子阶段更新保留预览；想看/看过状态、评分和标记日期映射正确。
- 同步生命周期：增量、完全重写、旧路径、失败重试都能产生统一阶段；未登录、Cookie 过期、取消、异常和完成都使用稳定阶段 ID。
- `LiveUpdateNotificationBuilder`：收起标题/摘要、进度、最终状态、取消动作和登录动作的 PendingIntent 正确；API 36+ 芯片只接收阶段短标签。

### Compose/UI 测试

- `DoubanSyncDialogTest`：运行中显示阶段、子状态和最近 5 条数据；数据流更新后条目内容刷新；完成态仍显示结果；Cookie 过期显示重新登录；取消状态按钮禁用。
- `WatchlistScreenTest`：同步中横幅显示统一格式并打开对话框；完成态打开结果；Cookie 过期横幅点击登录回调；横幅隐藏不影响已打开的结果对话框。
- `SettingsScreen` 测试：未登录和从未同步显示“同步豆瓣”，已同步显示“重新同步豆瓣”。
- 更新现有依赖裸 `phase` 字符串的测试，改为验证稳定阶段和本地化展示，不再用字符串包含关系验证业务状态。

### 构建与回归

按项目约定依次执行目标单元测试、Debug 构建和 `git diff --check`。若设备在线，再用 Android 16+ 模拟器验证 Live Update 芯片、通知展开、锁屏和点击路由；Android 低版本验证普通通知回退。报告需区分源码、构建、Compose 测试和设备运行证据。

## 8. 验收标准

1. 设置页未登录豆瓣时入口明确显示“同步豆瓣”。
2. 同一阶段在状态栏、通知标题、通知摘要、对话框标题中不重复；每个表面显示的信息符合职责表。
3. 豆瓣列表页返回数据后，对话框实时出现最近最多 5 条条目，包含标题、状态、评分和标记日期。
4. 通知展开态显示子状态、进度、当前条目和统计，但不挤入完整数据列表；锁屏不泄露最近条目详情。
5. Watchlist 横幅的进行中、完成、Cookie 过期三种点击路径分别正确响应。
6. 完成态或过期态不会因 Service 立即停止或横幅自动隐藏而丢失结果；用户可以从对话框查看最终统计和失败项。
7. 四套语言资源完整，通知和 Compose UI 不再依赖同步管理器直接拼接的用户可见阶段字符串。
