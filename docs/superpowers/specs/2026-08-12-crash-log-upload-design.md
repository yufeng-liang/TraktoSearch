# 崩溃日志上报增强设计

> **日期**：2026-08-12
> **状态**：已批准，待实现
> **范围**：App 端（Android）崩溃上报交互改造 + 上传记录本地化 + 反馈页展示

## 背景

现有崩溃上报链路：
- `CrashHandler`（非 Hilt 静态单例）捕获崩溃 → 写日志到 `filesDir/crash_logs/crash_时间戳.log`（上限 5 份）+ SharedPreferences 计数
- `CrashLogStorage`（Hilt 单例，DataStore）保存 `enabled`（默认 false）+ `prompted`（默认 false）
- `TraktSearchApp.onCreate` 启动时后台调 `CrashLogUploader.uploadPendingLogs()`（仅授权后上传，结果放 `CompletableDeferred`）
- `MainActivity.checkCrashAndPrompt()` 按崩溃计数弹原生 AlertDialog：
  - 未弹过授权 → 授权询问（同意 → 开开关 + 立即上传；拒绝 → 清日志）
  - 已弹过未授权 → 清日志
  - 已授权 → 等上传结果（8s 超时），失败弹「邮件兜底」对话框（发邮件/取消）
- 设置页已有「崩溃日志上报」开关（仅改状态，不触发上传）

## 目标

1. 崩溃后下次打开提示上报，用户同意并上传后：成功 toast 提示；失败对话框不关闭 + 重试按钮
2. 已授权老用户启动自动上传失败 → 统一改为重试对话框（删除邮件兜底）
3. 设置页开关改名「自动上报崩溃日志」；开启开关后崩溃，下次打开**自动上报不再弹窗**（修复现有 prompted 判断缺陷）
4. 反馈与建议列表页新增「崩溃日志」区块展示上传记录，可点进详情查看日志全文与上传信息

## 设计决策汇总

| 决策项 | 选择 |
|---|---|
| 失败对话框 | 统一重试对话框（首次授权与老用户场景一致），删除邮件兜底 |
| 记录存储 | 本地 DataStore（key→JSON 列表），不上云 |
| 列表展示 | 反馈页「我的反馈」上方独立区块 |
| 记录粒度 | 每份日志文件一条记录，状态可更新（PENDING/UPLOADING/SUCCESS/FAILED） |
| 失败提示 | 对话框内错误文案 + toast + 重试按钮 |
| toast 策略 | 所有上传流程结束均有 toast（成功/失败），含老用户自动上传 |
| 授权判断 | `enabled=true` 即自动上报，不再弹授权/上报弹窗（仅失败弹重试） |
| 上传互斥 | in-flight 锁，App 启动 / 对话框同意 / 设置页开关并发触发只执行一次 |
| 记录上限 | 20 条，超出删最旧 |
| 日志文件上限 | 5 份维持不变 |
| 对话框实现 | Compose 顶层状态驱动（替换原生 AlertDialog） |
| 上传中不可取消 | 防止中途状态丢失 |

## 组件清单

| 组件 | 类型 | 职责 |
|---|---|---|
| `CrashLogRecord` | 新增 | 数据模型：`id(文件名)、crashTime、status、uploadTime、error、logContent(全文快照)、appVersion、device` |
| `CrashLogRecordStore` | 新增（data/local） | DataStore 持久化记录列表，上限 20 条裁剪，暴露 `StateFlow<List<CrashLogRecord>>` |
| `CrashLogUploader` | 改造 | 状态机（`StateFlow<UploadState>`）+ in-flight 互斥；上传前扫描目录为无记录文件补 PENDING 记录（含全文快照） |
| `MainActivity` | 改造 | 删原生 AlertDialog 逻辑，Compose 顶层状态驱动对话框；修复授权判断 |
| `CrashLogStorage` | 小改 | `setEnabled(true)` 同步置 `prompted=true` |
| `FeedbackScreen` | 改造 | 新增「崩溃日志」区块（本地数据源，无记录隐藏） |
| `CrashLogDetailScreen` | 新增（ui/screen/crashlog） | 详情页：上传信息卡片 + 日志全文卡片 + 失败/待上传可立即上传 |
| `FeedbackViewModel` | 改造 | 注入 `CrashLogRecordStore`，暴露记录列表 |
| 设置页开关 | 小改 | 改名「自动上报崩溃日志」+ 副标题；打开时若有待传文件立即触发上传 |
| `AppNavigation` | 小改 | 新增路由 `crashLogDetail/{recordId}` |

## 上传状态机（CrashLogUploader）

```
Idle → Uploading（互斥锁，重复触发直接返回当前状态）
  ├─ scan 目录：为无记录的文件建 PENDING 记录（读全文快照）
  ├─ 逐个文件上传
  │   ├─ 成功 → 记录置 SUCCESS（快照已在记录中）→ 删除文件
  │   └─ 失败 → 记录置 FAILED(error) → 文件保留供重试
  └─ 全部完成 → Success / Failed(error)
```

- `UploadState`：`Idle / Uploading / Success / Failed(error)`
- 记录中 UPLOADING/PENDING 状态在启动扫描时视为 PENDING 重试（覆盖上传中进程被杀）

## 数据流

```
崩溃 → CrashHandler 写文件
启动(enabled=true) → TraktSearchApp → uploadPendingLogs()（scan 建记录 → 逐个上传）
MainActivity 观察 uploadState → 授权/上传中/失败重试对话框 + toast
设置页开关打开 → 触发上传（有待传文件时）
反馈页「崩溃日志」区块 ← CrashLogRecordStore（本地，离线可见）
详情页 ← 记录数据
```

## 时序

### 首次崩溃（未授权）

1. 启动 → TraktSearchApp 上传（enabled=false 直接跳过，无副作用）
2. MainActivity：崩溃计数 > 0 且 `enabled=false && !prompted` → 弹授权对话框
3. 用户点「同意并上传」→ 置 enabled+prompted → 触发上传 → 对话框切换上传中（转圈，不可取消）
4. 成功 → toast「崩溃日志上报成功」+ 对话框关闭；失败 → 对话框内错误文案 + toast「上报失败，可重试」+ 重试按钮（重试失败仍停留，可取消关闭，日志保留下次再试）
5. 用户点「拒绝」→ 置 prompted → 清日志文件（未授权不留记录）

### 已授权老用户

1. 启动 → TraktSearchApp 自动上传（scan 建记录）
2. MainActivity 观察：失败 → 弹重试对话框 + toast；成功 → toast 提示（自动上传成功也提示）
3. 重试对话框取消 → 保留文件与 FAILED 记录，下次启动自动再试

### 设置页开关

- 开启 → `setEnabled(true)` + `setPrompted(true)`；若有待传文件（如之前失败保留）立即触发上传
- 关闭 → 仅改状态；已保留的本地文件下次启动不再上传，直到重新开启

## 错误处理与边界情况

| 场景 | 处理 |
|---|---|
| 上传中进程被杀 | 文件保留、记录停在 PENDING/UPLOADING → 下次启动扫描视为 PENDING 重试 |
| 并发触发上传 | in-flight 互斥锁，仅一次真正执行 |
| 取消重试对话框 | 保留文件与 FAILED 记录，下次启动自动再试 |
| 授权拒绝 | 置 prompted + 清日志文件 |
| 记录超限 | 删最旧记录 |
| 上传部分成功 | 逐文件独立处理，成功删文件，失败保留并记 error |

## UI 设计

### 崩溃上报对话框（Compose 顶层状态驱动）

| 形态 | 触发 | 内容 | 按钮 |
|---|---|---|---|
| 授权询问 | `enabled=false && !prompted` 且有新崩溃 | 现有文案（说明上报内容 + 可去设置关闭） | 同意并上传 / 拒绝 |
| 上传中 | 同意后 / 开关打开后 | 转圈 + 「正在上传崩溃日志…」 | 无（不可取消） |
| 失败+重试 | 上传失败（首次或老用户） | 错误文案 + 失败原因 | 重试 / 取消（取消→保留日志下次再试） |
| 成功 | 上传完成 | toast「崩溃日志上报成功」 | 自动关闭 |

### 反馈页「崩溃日志」区块

位置：`写新反馈` 按钮下方、`我的反馈` 标题上方；无记录时整块隐藏。

```
崩溃日志                        ← 区块标题
───────────────────────────
🛑 08-12 10:32  上传失败 · 网络错误     ← 记录卡片（状态图标 + 相对时间 + 状态文案）
✅ 08-11 21:05  上传成功
```

- 记录卡片点击进入详情页；全部记录倒序平铺（上限 20 条）
- 数据源本地 RecordStore，与云端反馈互不影响

### 记录详情页（新路由 `crashLogDetail/{recordId}`）

```
标题栏「崩溃日志详情」+ 返回
┌─ 上传信息卡片 ──────────────┐
│ 状态：上传失败              │
│ 上传时间：08-12 10:32       │
│ 失败原因：网络连接超时      │
│ 崩溃时间：08-12 09:15       │
│ 应用版本：3.6.0 / 设备型号  │
└────────────────────────────┘
┌─ 日志内容卡片 ──────────────┐
│ （等宽字体，全文展示）       │
└────────────────────────────┘
```

- 状态为「待上传/上传失败」的记录提供「立即上传」按钮（复用状态机，成功后刷新）
- 遵循新建页面模板：Box + 毛玻璃吸顶标题栏，`Scaffold(contentWindowInsets = WindowInsets(0,0,0,0))`

### 设置页开关

- 标题改「自动上报崩溃日志」，副标题「开启后崩溃日志将在下次启动时自动上传」

## 文案

新增 string 资源 × 4 语言（values/ 英、values-zh/ 中、values-ja/ 日、values-ko/ 韩）：
上报成功 toast、上报失败 toast、上传中、失败原因、记录状态（成功/失败/待上传）、详情页各字段、区块标题、设置页开关新文案；删除邮件兜底相关文案。

## 测试策略

### 单元测试（app/src/test，JVM + MockWebServer）

1. `CrashLogRecordStore`：记录增改查、上限 20 条裁剪、全文快照存取
2. `CrashLogUploader` 状态机（MockWebServer 锁定网关路径）：
   - 成功 → 记录 SUCCESS + 文件删除 + 状态流 Success
   - 失败（网络/500）→ 记录 FAILED + error + 文件保留 + 状态流 Failed(error)
   - 并发去重：两次调用只执行一次真正上传
   - 多文件部分成功部分失败
   - UPLOADING 残留 → 重启 scan 视为 PENDING 重试
   - scan 建记录：已有记录的文件不重复建
3. 授权判断逻辑（提取为可测函数）：`enabled/prompted/崩溃数` 组合 → 弹授权 / 清日志 / 自动上传

### 手动验证清单（模拟器 + logcat -b crash）

1. 首次崩溃（无授权）→ 打开弹授权 → 同意 → 上传中转圈 → toast 成功 → 对话框关闭；反馈页可见成功记录
2. 首次崩溃 → 拒绝 → 清日志，下次打开不再弹
3. 设置页开启开关（不弹授权）→ 崩溃 → 下次打开自动上传，无弹窗，toast 提示成功
4. 断网触发上传失败 → 对话框停留 + toast + 重试 → 恢复网络点重试 → 成功
5. 反馈页崩溃日志区块：点击记录 → 详情页展示全文与上传信息；失败记录点「立即上传」
6. 崩溃日志记录与设置开关状态联动

## 收尾

- strings.xml × 4 语言全量同步（新增 + 改名 + 删除邮件兜底文案）
- 帮助与说明页更新（自动上报说明、反馈页可查看上传记录）
- 实现阶段使用 git worktree 隔离；多步任务按依赖关系用子代理并行，主代理统一验证提交
- commit 使用 Conventional Commits 中文格式，功能改动按内容拆分提交
