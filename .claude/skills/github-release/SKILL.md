---
name: github-release
description: "GitHub Release 发布子步骤——在 release 总流程已完成提交/版本号/构建/日志后，用 gh 创建 GitHub Release 并上传 APK。由 release skill 调用，目标含 GitHub 仓库。"
version: "1.0.0"
license: MIT
---

# GitHub Release（子步骤）

在 `release` 总流程完成 Git 提交、版本号更新、构建 APK、生成更新日志、推送代码与 tag 之后，执行 GitHub 侧 Release 创建。

## 仓库

- GitHub: `yufeng-liang/TrackToSearch`（origin，私有，版本检测主用）

## 前置（由 release skill 保证）

- `gh` 已登录
- APK 已构建并重命名为 `TraktoSearch-v{versionName}.apk`
- 更新日志 markdown 已生成（文件 `changelog.md`）
- 代码与 tag 已 `git push origin master` + `git push origin v{versionName}`（走代理）

## 步骤

```
gh release create v{versionName} {apk_path} --title "v{versionName}" --notes-file changelog.md
```

降级（gh 未装）：GitHub REST API
1. `POST https://api.github.com/repos/yufeng-liang/TrackToSearch/releases`
   Body: `{ tag_name, name, body, target_commitish: "master" }`
2. 用返回 `upload_url` 上传 APK 二进制

## 验证

- `gh release view v{versionName}` 确认 Release 存在、body 正确、资产已附
- 可选：下载 APK 用 `Get-FileHash` 对比本地 SHA256
