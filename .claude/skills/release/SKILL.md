---
name: release
description: "Android 发布总流程编排——当用户说发布/release/打包发布时，统一执行 Git 提交、版本号更新、构建 Release APK、生成用户友好更新日志、推送 GitHub 与 Gitee，再调用 github-release 与 gitee-release 两个子 skill 完成两侧 Release。单一入口，无需再参考其他文档。"
version: "1.0.0"
license: MIT
---

# Android Release 发布总流程

当用户触发发布（"发布"、"release"、"打包发布"等），按本流程执行完整发布。GitHub 与 Gitee 两侧 Release 分别由 `github-release` 与 `gitee-release` 子 skill 完成。

## 仓库信息

- GitHub: `yufeng-liang/TrackToSearch`（origin，私有，版本检测主用）
- Gitee 主仓库: `yufeng-liang/TrackToSearch`（gitee，私有，版本检测降级）
- Gitee 公开仓库: `yufeng-liang/TrackToSearch-release`（公开，APK 下载）

## 前置条件

- `gh` CLI 已登录
- Gitee MCP（`mcp-gitee`）已加载
- 构建 Release APK 需先配置签名（`release.jks` + `local.properties` 的 `release.*`）
- Gitee token：opencode.json（remote）+ `local.properties` 的 `gitee.access.token`

## 流程

### 1. Git 提交

- 暂存所有变更（排除 `.env`、密钥、`local.properties`、`release.jks` 等敏感文件）
- 提交信息格式：`v{versionName}: 描述主要变更`（中文）

### 2. 版本号更新

- 读取 `app/build.gradle.kts` 的 `versionCode` / `versionName`
- 对比上次发布 tag 到 HEAD 的 `git diff`，按改动大小定版本号：
  - **major**：架构重构、不兼容 API 变更
  - **minor**：新增功能、较大改进
  - **patch**：Bug 修复、小优化、文字调整
- `versionCode` +1，`versionName` 按规则递增
- 创建 git tag：`v{versionName}`

### 3. 构建 Release APK

- `.\gradlew assembleRelease`
- 产物：`app/build/outputs/apk/release/app-release.apk`
- 重命名为 `TraktToSearch-v{versionName}.apk`

### 4. 生成用户友好的更新日志

- `git log {last_tag}..HEAD --pretty=format:"%s"`
- 分类 `### 新功能` / `### 改进` / `### 修复`（对应 feat/fix/refactor 等）
- 用户友好：技术细节省略，适当用 emoji（✨ 新功能、🔧 改进、🐛 修复）
- 格式：
  ```
  ## v{version} 更新内容

  ### 新功能

  - xxx

  ### 改进

  - xxx

  ### 修复

  - xxx
  ```
- 写入 `changelog.md`（GitHub 直接用；Gitee 侧由子 skill 转 JSON PATCH）

### 5. 推送代码与 tag

- GitHub：`git push origin master`（走代理）+ `git push origin v{versionName}`
- Gitee：`git -c http.proxy="" push gitee master` + `git -c http.proxy="" push gitee v{versionName}`（直连绕过代理）

### 6. 调用子 skill 创建 Release

- 调用 `github-release` skill：用 `gh` 创建 GitHub Release 并上传 APK
- 调用 `gitee-release` skill：用 Gitee MCP 创建两个 Gitee 仓库 Release、PATCH markdown 日志、curl.exe 上传 APK、校验 SHA256

## 验证

- GitHub：`gh release view v{versionName}`
- Gitee：curl 查两个 release body 无乱码；下载 APK 与本地 SHA256 一致

## 注意事项

- 敏感文件绝不提交（local.properties / release.jks）
- Gitee 附件不可删除，APK 上传必须用 curl.exe，PowerShell multipart 会损坏包
- Gitee Release body 创建时只能纯文本，markdown 日志靠 PATCH 补回（PowerShell PATCH 卡死，用 curl.exe --data-binary）
