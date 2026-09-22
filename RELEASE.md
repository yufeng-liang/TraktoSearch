# 发版流程（唯一真相）

版本号、签名、构建、分发全部由本文件约束。`.claude/skills/release` 只是入口，内容冲突时以本文件为准。

一句话：**本地只负责「决定发什么」，CI 负责「构建与分发」。** 本机不再 `assembleRelease`，也不再手工上传 APK。

## 产物与数据源

| 位置 | 内容 | 谁写 |
| --- | --- | --- |
| GitHub Release 附件 | `TraktoSearch-v<name>.apk` | CI（镜像，官网次按钮与降级链指向这里） |
| R2 `apk/<name>.apk` | 同上，不可变 | CI |
| R2 `mapping/<name>/mapping.txt.gz` | R8 mapping，公网取不到 | CI |
| R2 `manifest/v<name>.json` | 该版本清单快照 | CI |
| R2 `manifest/history.json` | 全量更新日志 | CI（每次发版重算） |
| R2 `manifest/latest.json` | **指针**，唯一决定 App 认为的最新版 | CI，最后写 |

应用内检查更新与官网下载按钮读的都是 `https://tracktosearch.pages.dev/manifest/latest.json`，
下载走 `/dl/<fileName>`（Pages Function 回源 R2）。**发新版不需要重新部署官网。**

## 前置

- `gh` 已登录，本地 `release.jks` 与 `local.properties` 的 `release.*` / `config.aes.key` 齐备（本机只在需要复现构建时才用得到）
- GitHub Actions Secrets：`RELEASE_KEYSTORE_B64`、`RELEASE_STORE_PASSWORD`、`RELEASE_KEY_ALIAS`、`RELEASE_KEY_PASSWORD`、`CONFIG_AES_KEY`、`CF_API_TOKEN`、`CF_ACCOUNT_ID`
- 版本号是**手工**维护的：`app/build.gradle.kts` 的 `versionCode` / `versionName`，`README.md` 顶部那行版本号要一起改

## 步骤

1. **提交**：按 Conventional Commits 中文分逻辑提交，别把无关改动混进来。
2. **版本号**：`versionCode` +1，`versionName` 按改动大小递增。CI 的 `verifyReleaseVersion` 会拿 tag 与 `versionName` 对账，不一致直接构建失败。
3. **更新日志**：写 `changelog.md`，标题 `## v<version> 更新内容`，正文分类列条目。
   **不要在标题里塞日期**——App 侧 `injectDateIntoChangelog` 会按清单的 `releaseDate` 自动追加，多写一层就是重复括号。
4. **提交版本号改动并打 tag**：`git tag v<version>`。
5. **推送**：`git push origin master && git push origin v<version>`；代码镜像 `git -c http.proxy="" push gitee master`（Gitee 只留代码镜像，不再发 APK）。
6. **建 Release 即触发发布**：

   ```bash
   gh release create v<version> --title "v<version>" --notes-file changelog.md
   ```

   `published` 事件触发 `.github/workflows/release-apk.yml`：构建签名 APK → 断言证书指纹 → 挂 GitHub 附件 →
   传 R2 的 APK / mapping / `manifest/v<name>.json` → 重算 `history.json` → **最后**移 `latest.json` 指针 → 回读校验。

7. **盯第一次**：`gh run watch <run-id>`（别接 `| tail` 之类管道，会看不到进度）。
   全绿即发布完成；任何一步红，线上仍是上一个版本，按下面「回滚与补救」处理。

## 护栏（都在代码里，别绕）

- `VerifyReleaseSigningTask`：证书指纹必须是 `5ece267c…`，挂在 `preReleaseBuild` / `assembleRelease` / `bundleRelease`
- `verifyReleaseVersion`：tag 与 `versionName` 不符即失败；`config.aes.key` 为空即失败（空 key 会产出解不开远程配置的坏包）
- CI `apksigner verify`：查「发出去的包真是这枚证书签的吗」，与 Gradle 侧那处常量互为交叉验证
- **versionCode 单调断言在任何写入之前**：挡住 dry-run 产物顶掉线上、以及同 versionCode 出两个不同包
- 每次 `wrangler r2 object put` 都断言日志出现 `Resource location: remote`：不加 `--remote` 时 wrangler 会静默读写本机
  `.wrangler/state` 并照样打印 `Upload complete.`，退出码看不出来
- `check-site.mjs` 钉住了官网下载直链、GitHub 兜底按钮与版本信息数据源，改这些字面量必须同步改断言

## 回滚与补救

指针可覆写就是回滚手段，APK 对象一个都不用删：

```bash
# 把 latest.json 退回上一个版本（v<旧> 的快照内容与当时的 latest.json 完全相同）
npx wrangler r2 object put tracktosearch-releases/manifest/latest.json \
  --file manifest-v<旧版本>.json --content-type "application/json" --remote
```

先取回快照再覆写：

```bash
npx wrangler r2 object get tracktosearch-releases/manifest/v<旧版本>.json --file manifest-v<旧版本>.json --remote
```

发版中途失败：不可变对象（APK / mapping / `v<name>.json`）可能已经写上去了，但指针没动，用户侧无感。
修好后重跑该 workflow run 即可（`release` 事件重跑仍是 publish）。**注意**：拿历史 tag 重放会死在
`.ci/gradle-ci.init.gradle.kts 不在这棵树里`——旧 tag 上没有 `.ci/`，只有从当前 master 切的新 tag 才带得全。

## 已退役

Gitee 侧的 APK/Release 分发已停：`gitee-release` remote 与 `.claude/skills/gitee-release`、`github-release` 两个子 skill 已删，
`auth-worker` 的公开 release 代理路由与 `GiteeUpdateApiService` 也一并移除。Gitee 只保留 `gitee` 这个代码镜像 remote。
豆瓣失败项/全局池/个人数据同步走的是另外两个仓（`meta-data`、`meta-data-public`），与发版无关，别动。
