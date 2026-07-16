# 豆瓣失败数据云端同步设计

## 背景与目标

当前豆瓣同步失败项只能本地查看,支持导出 JSON 文件分享。用户希望:
- A 手机同步失败后,失败数据自动上传云端
- B 手机用同一豆瓣账号登录后,自动检测云端是否有失败数据,支持下载查看
- 比手动导出 JSON 再传给另一台手机更便捷

## 方案:Gitee 私有仓库文件 API

复用项目已有的 Gitee token(`local.properties` 的 `gitee.access.token`,打包进 App),用户零配置。

### 数据流

```
A 手机:
  豆瓣同步完成 → 本地有失败项 → 自动上传
    → JSON 序列化(含 mediaType/subtitle,修复 DTO 缺陷)
    → AES-256-CBC 加密(固定 key 打包进 App)
    → base64 编码 → Gitee Contents API PUT
    → 路径: failures/{SHA256(doubanUserId).substring(0,16)}.json

B 手机:
  豆瓣登录成功 → 自动检测云端
    → GET failures/{SHA256(doubanUserId).substring(0,16)}.json
    → 200: 弹窗"检测到云端 X 条失败数据,是否下载查看?"
    → 404: 静默跳过
  用户同意 → 下载 → AES 解密 → 反序列化 → Room REPLACE 策略合并
```

### 关键设计决策

| 决策点 | 选择 | 理由 |
|--------|------|------|
| 上传时机 | 同步完成后自动上传一次 + 设置页手动上传按钮 | 自动无感,但只在同步结束时批量上传,避免频繁 |
| 下载时机 | 豆瓣登录成功后自动检测 + 设置页手动拉取按钮 | 登录时自然检测,不打扰用户 |
| 合并策略 | REPLACE(同 doubanId 覆盖),不删除本地云端没有的项 | 避免误删,合并而非覆盖 |
| 加密 | AES-256-CBC,固定 key 打包进 App | 防止 Gitee token 泄露时数据明文;反编译能看到 key 但数据非高敏感,可接受 |
| 路径隔离 | `failures/{SHA256(doubanId)前16位}.json` | 避免直接暴露豆瓣 ID;同账号在 A/B 手机 hash 一致 |
| 仓库 | 复用 Gitee 私有仓库 `yufeng-liang/TrackToSearch` | 用户零配置,复用现有 token |
| DTO 字段 | 补全 mediaType/subtitle(修复现有缺陷) | 导入时不丢失用户标注 |
| 失败提示 | 上传/下载失败只 Toast,不阻塞流程 | 云端同步是辅助功能,不能阻塞主流程 |

### 涉及文件改动

- **新增** `data/remote/cloud/CloudFailureSyncApi.kt` — Retrofit 接口(GET/PUT Contents API)
- **新增** `data/repository/CloudFailureSyncManager.kt` — 上传/下载/合并逻辑 + AES 加解密
- **修改** `data/repository/DoubanFailureExporter.kt` — FailureDto 补 mediaType/subtitle 字段
- **修改** `data/repository/DoubanSyncManager.kt` — 同步完成后调用 `cloudFailureSyncManager.uploadIfHasFailures()`
- **修改** `ui/screen/douban/DoubanLoginViewModel.kt` — 登录成功后调用 `cloudFailureSyncManager.checkAndPromptDownload()`
- **修改** `ui/screen/settings/SettingsScreen.kt` — 失败项区块加"上传到云端"/"从云端拉取"两个按钮
- **DI** `di/CloudSyncModule.kt` — 提供 CloudFailureSyncApi + Manager

### Gitee Contents API(待用 context7 验证)

- 上传: `PUT /repos/{owner}/{repo}/contents/{path}` — body 含 `content`(base64)、`message`(commit msg)、`branch`(默认 master)、可选 `sha`(更新已有文件时必传)
- 下载: `GET /repos/{owner}/{repo}/contents/{path}` — 返回 `content`(base64)、`sha`
- 404 表示文件不存在(云端无该用户数据)

### 错误处理

- 上传失败:Toast 提示"上传云端失败",不阻塞同步流程
- 下载失败:Toast 提示"下载云端数据失败",不阻塞登录流程
- 网络不可达:静默跳过(不弹窗)
- 解密失败:Toast 提示"云端数据损坏",不合并

## 本地导入导出(降级方案)

同时修复本地导入功能:
- 修复 `DoubanFailureExporter.FailureDto` 缺 mediaType/subtitle 字段
- 连通 `onRetryFromJson` UI:点击后触发 `ACTION_OPEN_DOCUMENT` 让用户选 JSON 文件,调用 `importFromFile` 导入,导入后在查看页显示
- 导入策略:REPLACE 合并(同 doubanId 覆盖),不删除本地已有项

## 测试要点

- A 手机同步产生失败项 → 自动上传 → Gitee 仓库出现 `failures/{hash}.json`
- B 手机同豆瓣账号登录 → 弹窗提示 → 下载 → 查看页显示导入的失败项
- 导出 JSON → 在另一台手机导入 → 查看页显示
- AES 加解密往返测试
- 网络异常/云端 404/解密失败 等异常路径
