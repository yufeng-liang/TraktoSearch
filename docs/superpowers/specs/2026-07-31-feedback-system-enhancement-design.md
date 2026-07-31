# 反馈系统增强设计

> **日期**：2026-07-31
> **状态**：已批准，待实现
> **范围**：App 端（Android）+ 后台（feedback-worker）+ 管理页（app-config）

## 背景

现有反馈系统：
- `feedbacks` 表（UUID 主键，无显示 ID，无 `last_read_at`）
- `feedback_replies` 表（无 `author_role`，默认都是开发者回复）
- App 详情页：开发者回复固定标题「Developer Reply」，无追问能力
- 设置页标题栏：仅标题，无图标
- 底部导航：4 个 Tab，无角标机制
- 新建反馈页：4 个 `FilterChip` 用 `weight(1f)` 平分宽度

## 目标

1. 反馈 ID：按类型前缀+序号生成（如 BUG001），便于沟通
2. 完善回复功能：用户和开发者都能附 5 张截图，对话流呈现
3. 消息中心：设置页标题栏消息图标，底部导航角标，消息列表页
4. 持久化缓存：已提交反馈（列表+详情）持久缓存，时效性数据（未读计数、消息列表）仅内存
5. chip 样式调整：wrap content 靠左排列，水平 padding 6dp

## 设计决策汇总

| 决策项 | 选择 |
|---|---|
| 反馈 ID 实现 | 新增 `display_id` 字段（UUID 保留为主键） |
| 序号范围 | 按类型独立递增（BUG/FEAT/UX/OTH） |
| 前缀格式 | 全大写三字母（BUG/FEAT/UX/OTH） |
| 序号位数 | 3 位补零，超出自然扩展（BUG001 ~ BUG999 → BUG1000） |
| 消息列表项 | 每条回复 = 一条消息（开发者+用户扁平列表） |
| 新消息定义 | 仅开发者回复（用户未读） |
| 未读追踪粒度 | 反馈级 `last_read_at` |
| 角标清零时机 | 进详情页即清 |
| 角标计数 | 未读回复总数 |
| 用户追问截图 | 都能附 5 张 |
| 用户追问后状态 | REPLIED → PENDING |
| 对话流布局 | 聊天气泡（左右分边） |
| 新消息拉取时机 | App 启动 + 进设置页 |
| 未读计数缓存 | 仅内存 |
| 已提交反馈缓存 | 列表 + 详情都持久缓存 |
| chip 布局 | wrap content 靠左排列 |
| chip 内水平 padding | 6dp |
| chip 实现 | Surface 自定义 |
| 消息列表项内容 | ID+时间 / 回复预览 |
| 未读标识 | 列表项左侧红点 + 黄色背景；已读项整体 opacity 0.6 |
| 闪烁高亮 | 背景色 pulse 动画 3 次（1.5 秒/次） |
| 消息列表入口 | 设置页标题栏 Email 图标 |
| 后台改造范围 | API + 管理页都改 |
| 开发者身份标识 | `author_role` 默认 `developer` |
| 后台截图上传接口 | 新增 `admin/upload-screenshot`（Access JWT，R2 key 前缀 `admin/`） |
| 后台列表 display_id | 返回 |
| 已读上报接口 | `POST /feedback-api/{id}/read` |
| 未读计数接口 | `GET /feedback-api/unread-count`，返回 count + items 预览 |
| 消息列表接口 | `GET /feedback-api/messages`（仅回复，50 条分页） |
| 消息列表缓存 | 仅内存 |
| 「全部已读」 | 服务器端 `POST /feedback-api/read-all` |
| 数据模型方案 | 独立表名 `feedback_conversations` + 预留 `parent_reply_id` 字段（NULL） |
| 旧反馈 last_read_at | 全部视为已读（= created_at） |
| 旧 feedback_replies 处理 | 一次性迁移到 conversations 后 DROP |
| unread-count 服务器端缓存 | 不缓存 |
| 设置页消息图标 | Email（Icons.Rounded.Email） |
| ViewModel 共享 | 绑定 MAIN 路由（`hiltViewModel(navController.getBackStackEntry(Routes.MAIN))`） |
| 头像实现 | 开发者字母 D + 用户真实头像（AuthManager 头像，无头像 fallback 字母「我」） |
| 追问栏附加功能 | 仅截图 + 文本 |
| 关闭的反馈 | 不允许重开（隐藏追问栏，提示「该反馈已关闭」） |
| 消息列表筛选 chip | 4 个（全部 / 未读 / 开发者 / 我） |
| 设置页消息图标样式 | 拟态玻璃按钮样式（NeumorphicFrostedSurface 风格）+ 右对齐 |

## 设计详情

### 1. 数据模型（D1 Schema 迁移）

#### migration `0002_conversations.sql`

```sql
-- feedbacks 表加 display_id 和 last_read_at
ALTER TABLE feedbacks ADD COLUMN display_id TEXT;
ALTER TABLE feedbacks ADD COLUMN last_read_at INTEGER NOT NULL DEFAULT 0;

-- 为已有数据回填 display_id（按类型独立递增）
CREATE UNIQUE INDEX IF NOT EXISTS idx_feedbacks_display_id 
  ON feedbacks(display_id) WHERE display_id IS NOT NULL;

-- 序列表：按类型独立递增 display_id
CREATE TABLE IF NOT EXISTS feedback_seq (
    type TEXT PRIMARY KEY,
    seq INTEGER NOT NULL DEFAULT 0
);
INSERT INTO feedback_seq (type, seq) VALUES ('FEATURE', 0), ('BUG', 0), ('UX', 0), ('OTHER', 0);

-- 新建 feedback_conversations（语义升级，预留嵌套字段）
CREATE TABLE IF NOT EXISTS feedback_conversations (
    id TEXT PRIMARY KEY,
    feedback_id TEXT NOT NULL REFERENCES feedbacks(id),
    author_role TEXT NOT NULL DEFAULT 'developer' CHECK(author_role IN ('developer','user')),
    content TEXT NOT NULL,
    screenshots TEXT,           -- JSON 数组字符串，R2 key 列表
    parent_reply_id TEXT,       -- 预留：嵌套回复父级 ID，现阶段始终 NULL
    created_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_conversations_feedback 
  ON feedback_conversations(feedback_id, created_at ASC);
```

#### display_id 生成逻辑（feedback-worker 端）

提交反馈时，事务内：
1. `UPDATE feedback_seq SET seq = seq + 1 WHERE type = ?` 返回新 seq
2. 拼接 `prefix + seq.toString().padStart(3, '0')`（BUG001 / BUG999 / BUG1000）
3. INSERT feedbacks 时填入 display_id

类型→前缀映射：`FEATURE → FEAT`、`BUG → BUG`、`UX → UX`、`OTHER → OTH`

### 2. 后端 API（feedback-worker）

#### 新增接口

| 方法 | 路径 | 鉴权 | 用途 |
|---|---|---|---|
| POST | `/feedback-api/{id}/read` | App JWT | 用户进详情页上报已读，更新 `last_read_at=now` |
| POST | `/feedback-api/read-all` | App JWT | 一次性更新所有有未读的反馈 `last_read_at=now` |
| GET | `/feedback-api/unread-count` | App JWT | 返回 `{count, items:[{feedback_id, display_id, type, last_reply:{id, content, created_at, has_screenshot}}]}` |
| GET | `/feedback-api/messages?limit=50&offset=0` | App JWT | 扁平化所有回复列表，按 `created_at DESC`，每项含 `{id, feedback_id, display_id, type, author_role, content, screenshots, created_at, is_unread}` |
| POST | `/feedback-api/{id}/reply` | App JWT | 用户追问，body: `{content, screenshots?:[]}`，`author_role='user'`，事务内 REPLIED→PENDING |
| POST | `/admin/upload-screenshot` | Access JWT | 后台开发者上传截图，R2 key = `admin/{timestamp}-{8位token}.{ext}` |

#### 修改接口

| 方法 | 路径 | 改动 |
|---|---|---|
| POST | `/feedback-api/submit` | 事务内调 `feedback_seq` 生成 display_id 并写入 |
| GET | `/feedback-api/{id}` | replies 改为查 `feedback_conversations`，返回 `author_role` + `screenshots` |
| GET | `/feedback-api/mine` | 返回字段加 `display_id` |
| POST | `/admin/reply` | body 加 `screenshots?:string[]`，写入 `conversations.screenshots`，校验每个 key 必须以 `admin/` 开头 |
| GET | `/admin/list` | SELECT 加 `display_id`，返回字段加 `displayId` |
| GET | `/admin/detail/{id}` | replies 改查 `feedback_conversations`，返回 `authorRole` + `screenshots` |

#### 限流

- `POST /feedback-api/{id}/read`：每分钟 10 次
- `POST /feedback-api/read-all`：每分钟 2 次
- `POST /feedback-api/{id}/reply`：复用 submit 限流（每分钟 1 条 + 每天 10 条，与反馈提交共享配额）
- `POST /admin/upload-screenshot`：每分钟 5 张
- `GET /feedback-api/unread-count` + `GET /feedback-api/messages`：每分钟 30 次
- `unread-count` 不在服务器端缓存（每次都查 D1）

#### unread-count 查询逻辑

```sql
-- 未读开发者回复总数
SELECT COUNT(*) FROM feedback_conversations c
JOIN feedbacks f ON c.feedback_id = f.id
WHERE f.friend_id = ? 
  AND c.author_role = 'developer'
  AND c.created_at > f.last_read_at;

-- items：每个有未读开发者回复的反馈，取最新一条未读开发者回复
SELECT f.id AS feedback_id, f.display_id, f.type,
       c.id AS last_reply_id, c.content AS last_reply_content, 
       c.created_at AS last_reply_created_at,
       (c.screenshots IS NOT NULL) AS has_screenshot
FROM feedbacks f
JOIN feedback_conversations c ON c.feedback_id = f.id
WHERE f.friend_id = ?
  AND c.author_role = 'developer'
  AND c.created_at > f.last_read_at
  AND c.id = (
    SELECT id FROM feedback_conversations 
    WHERE feedback_id = f.id AND author_role = 'developer' AND created_at > f.last_read_at
    ORDER BY created_at DESC LIMIT 1
  )
ORDER BY c.created_at DESC;
```

#### 鉴权与网关

- 新增的 `/feedback-api/*` 复用现有 `verifyAccessToken`（App JWT）
- 新增的 `/admin/upload-screenshot` 复用现有 `verifyAccessJWT`（Access JWT + 邮箱白名单）
- `gateway-pages/functions/gateway-api/[[path]].js` 无需改动
- `app-config/functions/admin-api/[[path]].js` 无需改动

### 3. 前端导航与角标机制

#### 3.1 新增路由

```kotlin
// AppNavigation.kt
const val FEEDBACK_DETAIL = "feedbackDetail/{feedbackId}?replyId={replyId}"
const val MESSAGES = "messages"
fun feedbackDetailRoute(feedbackId: String, replyId: String? = null): String =
    "feedbackDetail/$feedbackId?replyId=${replyId ?: ""}"
```

#### 3.2 角标组件（复用 Material3 BadgedBox）

```kotlin
// ui/component/CountBadge.kt（新增）
@Composable
fun CountBadge(count: Int, modifier: Modifier = Modifier) {
    if (count <= 0) return
    Box(
        modifier = modifier
            .size(if (count < 10) 16.dp else 20.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary)
            .border(1.5.dp, MaterialTheme.colorScheme.surface, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = if (count > 99) "99+" else count.toString(),
            color = MaterialTheme.colorScheme.onPrimary,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
    }
}
```

#### 3.3 底部导航「设置」Tab 角标

扩展 `NavTabItem` 入参加 `badgeCount: Int = 0`，用 `BadgedBox` 包裹 `Icon`：

```kotlin
val unreadCount by feedbackViewModel.unreadCount.collectAsState()
NavTabItem(
    // ...
    badgeCount = if (tab == Tab.SETTINGS) unreadCount else 0
)
```

#### 3.4 设置页标题栏消息图标（拟态玻璃按钮 + 右对齐）

```kotlin
// SettingsScreen.kt 标题栏 Row
Row(
    modifier = Modifier.fillMaxWidth().padding(start=16.dp, end=12.dp, top=12.dp, bottom=12.dp),
    verticalAlignment = Alignment.CenterVertically
) {
    Text(text = stringResource(R.string.settings_title), fontSize = 28.sp, ...)
    Spacer(Modifier.weight(1f))  // 推到右侧
    
    // 拟态玻璃消息图标按钮
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f))
            // 复用 NeumorphicFrostedSurface 的拟态阴影逻辑
            .shadow(elevation = 4.dp, shape = CircleShape, ...)
            .clickable { onMessagesClick() },
        contentAlignment = Alignment.Center
    ) {
        BadgedBox(
            badge = { 
                if (unreadCount > 0) {
                    Badge { Text(if (unreadCount > 99) "99+" else unreadCount.toString()) }
                }
            }
        ) {
            Icon(
                imageVector = Icons.Rounded.Email,
                contentDescription = stringResource(R.string.feedback_messages),
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}
```

**关键**：40dp 圆形 Box 容器，复用 `NeumorphicFrostedSurface` 风格（半透明 surface 背景 + 拟态阴影），`BadgedBox` 包裹 Email 图标，角标在右上。

#### 3.5 拉取时机与状态流

`FeedbackViewModel.unreadCount: StateFlow<Int>`（默认 0）：
- **App 进入主界面**：`MainScreen` 的 `LaunchedEffect(Unit)` 调 `feedbackViewModel.fetchUnreadCount()`
- **进入设置页**：`SettingsScreen` 的 `LaunchedEffect(Unit)` 调 `feedbackViewModel.fetchUnreadCount()`

#### 3.6 ViewModel 共享

用 `hiltViewModel(navController.getBackStackEntry(Routes.MAIN))` 在子页面（SettingsScreen / MessagesScreen / FeedbackDetailScreen / FeedbackScreen）共享同一 `FeedbackViewModel` 实例。

### 4. 反馈详情页改造

#### 4.1 页面结构

```
[标题栏：返回 + ID(BUG001) + 状态标签 + 相对时间]
[原反馈卡（置顶，独立样式）]
  - 头像 + 我的反馈标签 + 设备型号 · App 版本
  - 正文
  - 截图网格
  - 应用信息行（Trakt / 豆瓣 / 联系方式）
[对话流标题「对话」]
[对话气泡列表]
  - 开发者：左侧 + surfaceVariant 气泡 + 绿色 D 头像 + borderRadius(16,16,16,4)
  - 用户：右侧 + primary 气泡 + AuthManager 头像 + borderRadius(16,16,4,16)
  - 头像 28dp 圆形，气泡外
  - 昵称/时间在气泡外上方
  - 截图缩略图 48dp 圆角 8dp 嵌入气泡内，点击进 ScreenshotFullscreenOverlay
[底部追问栏（CLOSED 状态隐藏，提示「该反馈已关闭」）]
  - 「+」按钮打开截图选择器（最多 5 张）
  - 文本框 maxLines=4
  - 发送按钮带上传/提交状态
```

#### 4.2 闪烁定位

从消息列表跳转 `feedbackDetail/{id}?replyId={replyId}`：
- 加载完成后 `lazyListState.scrollToItem(targetIndex)` 该回复
- 该回复气泡背景色 `animateColorAsState` pulse 动画 3 次（1.5 秒/次），从 `surfaceVariant` 到 `primary.copy(alpha=0.3)` 再回 `surfaceVariant`

#### 4.3 已读上报

`LaunchedEffect(feedbackId)` 加载成功后调 `viewModel.markAsRead(feedbackId)`：
- 本地 `lastReadAt = now`
- `unreadCount` 重新计算（减少对应数量）

#### 4.4 关闭的反馈

状态为 CLOSED 时隐藏底部追问栏，显示居中提示「该反馈已关闭」。

### 5. 消息列表页

#### 5.1 页面结构

```
[标题栏：返回 + 标题「消息」 + 「全部已读」文字按钮]
[筛选 chip 行：全部 / 未读 N / 开发者 / 我]（Surface 自定义 chip，wrap content 靠左）
[列表 LazyColumn]
  - 未读项：左侧 8dp 红点 + 黄色背景 primary.copy(alpha=0.08)
  - 已读项：透明背景 + 整体 opacity 0.6
  - 项内：左侧 36dp 圆形头像（开发者绿 D / 用户蓝「我」）
       右侧主行：display_id（按类型染色：BUG=红/FEAT=绿/UX=黄/OTH=灰） + 「 · 」 + 角色（开发者/我） + 截图标志 📷
       右侧时间
       下方回复预览（单行 ellipsis）
[上滑加载更多（limit=50, offset）]
[下拉刷新]
```

#### 5.2 跳转逻辑

点击列表项 → `feedbackDetail/{feedbackId}?replyId={replyId}` → 详情页闪烁定位

#### 5.3 状态流

- `MessagesState(Loading/Success/Error)` + `filter: MessageFilter`（ALL/UNREAD/DEVELOPER/USER）
- `loadMessages(refresh, filter)`、`markAllRead()`
- `markAllRead` 后本地 `unreadCount = 0`，触发底部导航 + 设置页标题栏角标消失

### 6. Chip 样式调整

#### 6.1 NewFeedbackScreen 4 个类型 chip

```kotlin
// 移除 weight(1f)，改 Row wrap
Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.Start)
) {
    FeedbackTypeChip(..., modifier = Modifier)  // 不再 weight(1f)
    // ... 4 个
}

// FeedbackTypeChip 用 Surface 自定义
private fun FeedbackTypeChip(
    selected: Boolean, onClick: () -> Unit, label: String, 
    icon: ImageVector, color: Color, modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.clickable { onClick() },
        shape = RoundedCornerShape(8.dp),
        color = if (selected) color.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surface,
        tonalElevation = if (selected) 0.dp else 1.dp,
        shadowElevation = if (selected) 0.dp else 1.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(icon, Modifier.size(14.dp), tint = if (selected) color else MaterialTheme.colorScheme.onSurface)
            Text(label, fontSize = 12.sp, maxLines = 1, color = if (selected) color else MaterialTheme.colorScheme.onSurface)
        }
    }
}
```

**关键**：水平 padding 6dp，垂直 8dp，圆角 8dp，间距 4dp，wrap content 靠左排列。

#### 6.2 持久化缓存策略

| 数据 | 缓存层级 | key 格式 | 失效策略 |
|---|---|---|---|
| 反馈列表（我的反馈） | 内存 TtlCache + DataStore | `feedback_list_v1` | 永久持久（启动加载），进页拉新覆盖 |
| 反馈详情（含回复） | 内存 TtlCache + DataStore | `feedback_detail_{id}_v1` | 永久持久，进页拉新覆盖（合并最新回复，按 reply id 去重） |
| 未读计数 | 仅内存 StateFlow | - | 启动 + 进设置页拉取 |
| 消息列表 | 仅内存 | - | 进页拉取，下拉刷新 |

新增 `FeedbackCacheStore`（参考现有 `TmdbCacheStore` 模式），DataStore key 加 `_v1` 后缀，启动时在 IO 协程中异步加载。

### 7. 后台管理页改造（app-config/public/admin/）

#### 7.1 管理页 UI 改造

**`app.js` `renderFeedback` 函数（第 1920-2128 行）改造**：
1. 列表项加 `display_id` 显示（在类型标签后，如 `[BUG001]`），可读 ID 替代 UUID 展示
2. 详情页对话流改为左右气泡（与 App 端一致）：开发者左侧绿色，用户右侧蓝色
3. 回复输入区加截图上传：
   - 文件选择器（多选，最多 5 张）
   - 上传按钮调用 `POST /admin-api/fb/admin/upload-screenshot`
   - 上传后显示缩略图网格，可删除
   - 提交回复时把 `screenshots: [key1, key2, ...]` 一起 POST
4. 列表筛选加「有未读」选项（可选，便于开发者查看待回复的）

#### 7.2 后台回复截图上传流程

```js
// 1. 用户选文件
const file = fileInput.files[0];
// 2. 上传
const formData = new FormData();
formData.append('file', file);
const res = await fetch('/admin-api/fb/admin/upload-screenshot', {
    method: 'POST',
    headers: { 'Authorization': `Bearer ${token}` },
    body: formData
});
const { key } = await res.json();
// 3. 加入 screenshots 数组，显示缩略图
// 4. 提交回复时附带
```

### 8. 数据回填与上线流程

#### 8.1 回填脚本（一次性运行）

```bash
# feedback-db ID: 62047475-0eb8-4b24-8e18-a49acebf2dd5

# Step 1: 应用 migration 0002
npx wrangler d1 execute feedback-db --remote \
  --file=feedback-worker/migrations/0002_conversations.sql

# Step 2: 回填 display_id（按 type 分组、created_at ASC）
# 用 CASE 把 type 映射为 prefix
npx wrangler d1 execute feedback-db --remote --command="
UPDATE feedbacks SET display_id = 
  CASE type
    WHEN 'FEATURE' THEN 'FEAT'
    WHEN 'BUG' THEN 'BUG'
    WHEN 'UX' THEN 'UX'
    WHEN 'OTHER' THEN 'OTH'
  END || printf('%03d', (
    SELECT COUNT(*) + 1 FROM feedbacks f2 
    WHERE f2.type = feedbacks.type 
      AND (f2.created_at < feedbacks.created_at 
           OR (f2.created_at = feedbacks.created_at AND f2.id < feedbacks.id))
  ))
WHERE display_id IS NULL;"

# Step 3: 同步 feedback_seq 表
npx wrangler d1 execute feedback-db --remote --command="
INSERT OR REPLACE INTO feedback_seq (type, seq)
SELECT type, COUNT(*) FROM feedbacks GROUP BY type;"

# Step 4: 设置 last_read_at = created_at（旧反馈视为已读）
npx wrangler d1 execute feedback-db --remote --command="
UPDATE feedbacks SET last_read_at = created_at WHERE last_read_at = 0;"

# Step 5: 迁移 feedback_replies → feedback_conversations
npx wrangler d1 execute feedback-db --remote --command="
INSERT INTO feedback_conversations (id, feedback_id, author_role, content, screenshots, parent_reply_id, created_at)
SELECT id, feedback_id, 'developer', content, NULL, NULL, created_at FROM feedback_replies;"

# Step 6: 验证后 DROP 旧表
npx wrangler d1 execute feedback-db --remote --command="DROP TABLE feedback_replies;"
```

#### 8.2 上线顺序

1. **部署 feedback-worker**（新接口 + migration）
   - `cd feedback-worker && npx wrangler deploy`
   - 应用 migration + 运行回填脚本（Step 1-6）
   - 验证：`curl /health`、用现有 App 拉一次 `/mine` 看是否带 `display_id`
2. **部署 app-config**（管理页改造）
   - `cd app-config && npx wrangler pages deploy public --project-name app-config --branch master`
3. **发布 App 新版本**
   - 改造后的 App 上架
   - 启动时拉取 `unread-count`，底部导航出现角标

#### 8.3 灰度与回滚

- 后端先上线，旧 App 仍能正常工作（新字段不影响旧接口返回，`display_id` 是新增字段）
- 回滚：`feedback-worker` 用 `wrangler rollback`；migration 不回滚（D1 不支持 DROP COLUMN，但新增字段可保留）

## 字符串资源（4 语言同步）

新增 key（values/ 英、values-zh/ 中、values-ja/ 日、values-ko/ 韩）：

| key | 英文值 |
|---|---|
| `feedback_messages` | Messages |
| `feedback_messages_title` | Messages |
| `feedback_messages_empty` | No messages yet |
| `feedback_messages_all_read` | Mark all as read |
| `feedback_filter_all` | All |
| `feedback_filter_unread` | Unread |
| `feedback_filter_developer` | Developer |
| `feedback_filter_mine` | Mine |
| `feedback_role_developer` | Developer |
| `feedback_role_me` | Me |
| `feedback_conversation` | Conversation |
| `feedback_reply_placeholder` | Add more details... |
| `feedback_reply_send` | Send |
| `feedback_reply_uploading` | Uploading screenshots (%1$d/%2$d) |
| `feedback_reply_failed` | Reply failed, please retry |
| `feedback_closed_hint` | This feedback is closed |
| `feedback_load_more` | Load more |
| `feedback_unread_count_format` | Unread %1$d |

## 涉及文件清单

### App 端（Android Kotlin + Compose）

**新增**：
- `app/src/main/java/com/tracktosearch/ui/component/CountBadge.kt`
- `app/src/main/java/com/tracktosearch/ui/screen/messages/MessagesScreen.kt`
- `app/src/main/java/com/tracktosearch/ui/screen/messages/MessagesViewModel.kt`（或合并到 FeedbackViewModel）
- `app/src/main/java/com/tracktosearch/data/repository/FeedbackCacheStore.kt`

**修改**：
- `app/src/main/java/com/tracktosearch/ui/screen/feedback/NewFeedbackScreen.kt`（chip 样式）
- `app/src/main/java/com/tracktosearch/ui/screen/feedback/FeedbackDetailScreen.kt`（对话流 + 追问栏 + 闪烁定位）
- `app/src/main/java/com/tracktosearch/ui/screen/feedback/FeedbackViewModel.kt`（unreadCount + markAsRead + reply + 共享）
- `app/src/main/java/com/tracktosearch/ui/screen/feedback/FeedbackScreen.kt`（display_id 显示）
- `app/src/main/java/com/tracktosearch/ui/screen/settings/SettingsScreen.kt`（消息图标 + 角标 + 拉取）
- `app/src/main/java/com/tracktosearch/ui/screen/main/MainScreen.kt`（底部导航角标 + 拉取）
- `app/src/main/java/com/tracktosearch/ui/navigation/AppNavigation.kt`（新路由 + ViewModel 共享）
- `app/src/main/java/com/tracktosearch/data/repository/FeedbackRepository.kt`（新方法 + 持久缓存）
- `app/src/main/java/com/tracktosearch/data/remote/feedback/FeedbackApiService.kt`（新接口 + 数据模型扩展）
- `app/src/main/res/values/strings.xml` + `values-zh/` + `values-ja/` + `values-ko/`（新增 key）

### 后台（feedback-worker）

**新增**：
- `feedback-worker/src/api/read.ts`（POST /feedback-api/{id}/read + POST /feedback-api/read-all）
- `feedback-worker/src/api/unread-count.ts`（GET /feedback-api/unread-count）
- `feedback-worker/src/api/messages.ts`（GET /feedback-api/messages）
- `feedback-worker/src/api/reply.ts`（POST /feedback-api/{id}/reply）
- `feedback-worker/src/admin/upload-screenshot.ts`（POST /admin/upload-screenshot）
- `feedback-worker/migrations/0002_conversations.sql`

**修改**：
- `feedback-worker/src/index.ts`（路由分发）
- `feedback-worker/src/api/submit.ts`（生成 display_id）
- `feedback-worker/src/api/detail.ts`（查 conversations）
- `feedback-worker/src/api/mine.ts`（返回 display_id）
- `feedback-worker/src/admin/reply.ts`（接收 screenshots）
- `feedback-worker/src/admin/list.ts`（返回 display_id）
- `feedback-worker/src/admin/detail.ts`（查 conversations）
- `feedback-worker/src/util/rate-limit.ts`（新接口限流配置）

### 管理页（app-config/public/admin/）

**修改**：
- `app-config/public/admin/app.js`（`renderFeedback` + `showFeedbackDetail` + 回复输入区）
- `app-config/public/admin/styles.css`（对话流气泡样式）

## 测试策略

### 后端测试（feedback-worker/tests/feedback.test.mjs）

新增测试用例：
- `POST /feedback-api/submit` 生成的 display_id 符合格式（BUG001）
- `POST /feedback-api/{id}/read` 更新 last_read_at
- `GET /feedback-api/unread-count` 返回正确计数和 items
- `GET /feedback-api/messages` 分页正确
- `POST /feedback-api/{id}/reply` 用户追问 + REPLIED→PENDING
- `POST /admin/upload-screenshot` 鉴权 + R2 key 前缀 `admin/`
- `POST /admin/reply` 接收 screenshots + 校验 key 前缀

### App 端测试

- `FeedbackViewModelTest`：unreadCount 流转、markAsRead、reply、缓存命中/未命中
- `FeedbackRepositoryTest`：新方法 + 持久缓存读写
- 手动验证：底部导航角标、设置页标题栏图标、消息列表筛选、闪烁定位、追问流程

### 回填脚本验证

- 回填后 `SELECT COUNT(*) FROM feedbacks WHERE display_id IS NULL` = 0
- `SELECT type, COUNT(*), MAX(seq) FROM feedback_seq` 与 `feedbacks` 各类型计数一致
- `SELECT COUNT(*) FROM feedback_conversations` = 回填前 `feedback_replies` 行数
- `feedback_replies` 表已 DROP

## 未覆盖范围（YAGNI）

- 推送通知（FCM/推送通道）：本期不做，未读仅靠 App 主动拉取
- 嵌套回复（parent_reply_id）：表字段已预留，UI 不启用
- 多开发者区分（author_name）：默认 `author_role='developer'`
- 消息列表持久缓存：仅内存
- 服务器端 unread-count 缓存：不缓存
