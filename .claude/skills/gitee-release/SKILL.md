---
name: gitee-release
description: "Gitee Release 发布子步骤——在 release 总流程已完成提交/版本号/构建/日志后，用 Gitee MCP 创建两个 Gitee 仓库 Release 并写入 markdown 更新日志，再用 curl.exe 上传 APK。由 release skill 调用，目标含 Gitee 仓库。"
version: "1.0.0"
license: MIT
---

# Gitee Release（子步骤）

在 `release` 总流程完成 Git 提交、版本号更新、构建 APK、生成更新日志、推送代码与 tag 之后，执行 Gitee 侧 Release 创建与 APK 上传。

## 仓库

- Gitee 主仓库：`yufeng-liang/TrackToSearch`（私有，版本检测降级用）
- Gitee 公开仓库：`yufeng-liang/TrackToSearch-release`（公开，APK 下载）

## 前置（由 release skill 保证）

- Gitee MCP 已加载（`create_release`、`list_releases`）
- APK 已构建并重命名为 `TraktToSearch-v{versionName}.apk`
- 更新日志 markdown 已生成（文件 `changelog.md`）
- 代码与 tag 已 `git -c http.proxy="" push gitee master` + `git -c http.proxy="" push gitee v{versionName}`

## 步骤

### 1. 创建 Gitee Release（MCP）

对两个 Gitee 仓库各调用一次 `create_release`：

- 参数：`owner`/`repo`、`tag_name=v{versionName}`、`name=v{versionName}`、`target_commitish=master`
- **body 传简化纯文本**（如 `v{versionName}: 性能优化与新功能。`）—— Gitee API 拒绝含 markdown 符号的 body
- 记录返回 `release_id`

### 2. 同步 markdown 更新日志（PATCH）

- 将 `changelog.md` 内容包成 JSON（UTF-8 无 BOM，用 Write 工具生成 `changelog.json`，结构 `{ "body": "..." }`）
- 用 `curl.exe --data-binary @file` PATCH 两个仓库（PowerShell Invoke-RestMethod 对 PATCH 会卡住）：
  ```
  curl.exe -X PATCH "https://gitee.com/api/v5/repos/{owner}/{repo}/releases/{release_id}?access_token={token}" -H "Content-Type: application/json" --data-binary @changelog.json
  ```
- 验证：用 `curl.exe` 查询两个 release 的 body，确认中文无乱码、长度与源一致

### 3. 上传 APK（curl.exe，必须用 curl.exe）

- 主仓库：
  ```
  curl.exe -X POST "https://gitee.com/api/v5/repos/yufeng-liang/TrackToSearch/releases/{release_id}/attach_files?access_token={token}" -F "file=@{apk_path};filename=TraktToSearch-v{versionName}.apk"
  ```
- 公开仓库：
  ```
  curl.exe -X POST "https://gitee.com/api/v5/repos/yufeng-liang/TrackToSearch-release/releases/{release_id}/attach_files?access_token={token}" -F "file=@{apk_path};filename=TraktToSearch-v{versionName}.apk"
  ```
- **绝不用 PowerShell multipart 拼接**，否则 APK 被 UTF-8 重编码损坏

### 4. 验证 APK 完整性

- 下载两个仓库上传的 APK，用 `Get-FileHash` 对比本地 SHA256，必须完全一致
- 若体积约为本地 1.5~2 倍，说明上传方式错误，APK 已损坏，需重新上传正确文件

## 注意事项

- Gitee 附件不支持删除，上传错误只能再传正确文件（App 端取最后一个 apk 规避）
- App 端获取链路：`UpdateRepository.checkForUpdate()` 优先 GitHub，降级 Gitee 主仓库；`sanitizeChangelog()` 检测乱码（`?` 占比 > 30% 返回空）
