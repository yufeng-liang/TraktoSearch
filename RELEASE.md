# 发版流程（唯一真相）

版本号、签名、构建、分发全部由本文件约束。`.claude/skills/release` 只是入口，内容冲突时以本文件为准。

一句话：**本地只负责「决定发什么」，CI 负责「构建与分发」。** 本机不再 `assembleRelease`，也不再手工上传 APK。
**更新日志的唯一真相源是仓库内的 `CHANGELOG.md`**——GitHub Release 正文、R2 的 `manifest/history.json` 与 App 设置页的「更新日志」都由它派生。

## 产物与数据源

| 位置 | 内容 | 谁写 |
| --- | --- | --- |
| GitHub Release 附件 | `TraktoSearch-v<name>.apk` | CI（镜像，官网次按钮与降级链指向这里） |
| R2 `apk/<name>.apk` | 同上，不可变 | CI |
| R2 `mapping/<name>/mapping.txt.gz` | R8 mapping，公网取不到 | CI |
| R2 `manifest/v<name>.json` | 该版本清单快照 | CI |
| R2 `manifest/history.json` | 全量更新日志 | CI（每次发版从 CHANGELOG.md 重算） |
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
3. **更新日志**：在 `CHANGELOG.md` **顶部**（`# 更新日志` 之后、上一版本段之前）插入新段。
   段头格式 `## v<version> 更新内容（<yyyy-MM-dd>）`，日期填**今天的东八区日期**，它就是这个版本的 `releaseDate`。
   格式规范见 `CHANGELOG_STYLE.md`；生成时用 changelog-generator 技能，注意先过滤自动化噪音。

   **写完先给用户过目，确认无误再往下走。** 这是流程里唯一的人工关卡：日志随 Release 发出去之后，
   要改它就得同时改 GitHub Release 正文与线上 `manifest/history.json`（App 设置页读的是后者）。
   **日志过目在构建之前**，所以整个发版只构建一次——不需要「先构建一遍再正式构建一遍」。
4. **提交并打 tag**：把版本号与日志一起提交，然后 `git tag v<version>`。
5. **推送**：`git push origin master && git push origin v<version>`；代码镜像 `git -c http.proxy="" push gitee master`（Gitee 只留代码镜像，不再发 APK）。
6. **建 Release 即触发发布**：

   ```bash
   # 输出必须落在 .tmp/：changelog.md 与 CHANGELOG.md 在大小写不敏感的文件系统上是同一个文件，
   # section 是覆盖写，写到仓库根的 changelog.md 会把全量日志截断成只剩当前一版。
   node .ci/make-manifest.mjs section --version v<version> --out .tmp/changelog-body.md
   gh release create v<version> --title "v<version>" --notes-file .tmp/changelog-body.md
   ```

   `published` 事件触发 `.github/workflows/release-apk.yml`：构建签名 APK → 断言证书指纹 → 挂 GitHub 附件 →
   传 R2 的 APK / mapping / `manifest/v<name>.json` → 重算 `history.json` → **最后**移 `latest.json` 指针 → 回读校验。

7. **盯第一次**：`gh run watch <run-id>`（别接 `| tail` 之类管道，会看不到进度）。
   全绿即发布完成；任何一步红，线上仍是上一个版本，按下面「回滚与补救」处理。

## 影子构建（可选，仅当想先验证能出包）

**正常发版不需要这一步**——日志过目在第 3 步、构建在第 6 步，构建只跑一次。

只有想「先确认这个版本能打出包」时才用它（比如改过构建配置、依赖、签名相关的东西）：

```bash
gh workflow run release-apk.yml -f tag=v<version> -f publish=false
gh run watch            # 11–19 分钟
```

- `publish=false`（默认）只出 artifact，不碰任何线上对象，产物在 Actions 页面保留 30 天。
- 它按 tag 检出代码，所以要先提交版本号并打 tag 推上去。
- 它**是额外的一次构建**：真正发版时第 6 步仍会重新构建。私有仓 Actions 有额度，用它就多花一次。
- 它**替代不了**第 6 步。也**不要**用它来绕过「日志没写就不能发布」——那个闸门只在正式发布
  （`PUBLISH=true`）时才生效，影子构建压根不检查 `CHANGELOG.md`，拿它当「日志以后再说」的借口
  会一路顺畅到 `gh release create` 才失败。

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
