# 豆瓣登录与 Trakt 登录逻辑修复设计

## 背景与问题

用户点 LoginScreen 的"从豆瓣导入"按钮后,会跳转到 DoubanLoginScreen 完成豆瓣登录。豆瓣登录成功后 `DoubanLoginViewModel.onLoginSuccess` 会自动触发 `doubanSyncManager.startSync()` 把豆瓣标记同步到 Trakt。

**问题**:如果用户此时未登录 Trakt,同步流程不会检查 Trakt 登录态,直接调用 Trakt API 导致全部 401 失败。失败项被记录到 Room 表,但 UI 不提示根因,用户看到"同步完成 X 条,失败 Y 条"却不知道为何全失败。

## 根因

`DoubanSyncManager` 的 5 个公开入口全部只检查豆瓣登录,不检查 Trakt 登录:

- `startSync(forceOverwrite: Boolean)` (line 118)
- `startSync(mode: SyncMode)` (line 131)
- `startResume()` (line 150)
- `startRetry(...)` (line 185)
- `runSyncLegacy/runSyncIncremental/runSyncFullRewrite/runResume/runRetry` 内部只调用 `doubanAuthStorage.getCredentials()`,完全不引用 `tokenStorage`/`TraktAuthManager`/`isLoggedIn`

Grep 确认:`DoubanSyncManager.kt` 中没有任何 `tokenStorage`、`accessToken`、`isLoggedIn`、`TraktAuthManager` 的引用。

## 方案:双重拦截

### 1. UI 层前置引导(LoginScreen)

用户点"从豆瓣导入"按钮时,先检查 Trakt 登录态:
- 已登录 Trakt → 正常跳转 DoubanLoginScreen
- 未登录 Trakt → 弹 AlertDialog:
  - 标题:"需要先登录 Trakt 账号"
  - 内容:"从豆瓣导入标记需要先登录 Trakt 账号,标记会同步到你的 Trakt 想看/已看列表。是否先登录 Trakt?"
  - 确认 → 启动 Trakt OAuth 流程
  - Trakt OAuth 成功 → 自动导航到 DoubanLoginScreen 继续豆瓣登录流程

### 2. 同步层兜底(DoubanSyncManager)

在 DoubanSyncManager 注入 `TokenStorage`,5 个公开入口统一加 Trakt 登录预检:
- `tokenStorage.getCachedAccessToken()` 为空 或 `!tokenStorage.isTokenValid()` →
  - `_progress.value = DoubanSyncProgress(isComplete = true, phase = "未登录 Trakt,请先登录")`
  - 直接 return,不启动同步流程
- 已登录 → 正常继续

### 3. UI 层监听 phase 显示引导

- `DoubanSyncDialog` 监听 `progress.phase`,phase 包含"未登录 Trakt" → 显示引导登录按钮
- 点击引导按钮 → 跳转到 LoginScreen

### 4. DoubanLoginScreen 自动触发同步前置检查

`DoubanLoginScreen.kt:152` 的 `LaunchedEffect(isLoggedIn)` 自动触发同步前,先检查 Trakt 登录态:
- 未登录 Trakt → 不触发同步(避免无效同步产生失败项)
- 已登录 → 正常触发

## 涉及文件改动

- **修改** `ui/screen/login/LoginScreen.kt` — "从豆瓣导入"按钮加 Trakt 登录检查 + 引导对话框
- **修改** `data/repository/DoubanSyncManager.kt` — 注入 `TokenStorage`,5 个入口加预检
- **修改** `ui/screen/douban/DoubanSyncDialog.kt` — 监听 phase 显示引导登录按钮
- **修改** `ui/screen/douban/DoubanLoginScreen.kt` — 自动触发同步前检查 Trakt 登录态

## 测试要点

- 未登录 Trakt + 点"从豆瓣导入" → 弹引导对话框 → 确认 → 跳 Trakt OAuth
- 未登录 Trakt + 直接调 `doubanSyncManager.startSync()` → phase="未登录 Trakt" → 不启动同步
- 已登录 Trakt + 点"从豆瓣导入" → 正常跳豆瓣登录页
- DoubanLoginScreen 自动触发同步前检查 Trakt 登录态
