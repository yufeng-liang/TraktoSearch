# Android CLI 与官方 Skills 使用参考

Android 官方提供的命令行工具与 AI skills（来自 [android/skills](https://github.com/android/skills)），用于让 Agent 更规范地完成 Android 开发任务。本文档记录本机已安装的内容与日常用法。

## 安装位置

- **Android CLI 可执行文件**：`C:\Users\15778\AppData\AndroidCLI\android.exe`
  - 已写入用户 PATH（注册表 `HKCU\Environment`）。新开终端可直接用 `android`；当前 shell 需先 `export PATH="$PATH:C:/Users/15778/AppData/AndroidCLI"` 或写全路径。
  - 首次运行会下载并解包 CLI 本体（约需联网）。
- **Android skills 目录**：`C:\Users\15778\.config\opencode\skills\`
  - 安装目标 agent 为 `opencode`，由 `android skills add --all --agent=opencode` 写入。
- 官方文档：[Android CLI](https://developer.android.com/tools/agents/android-cli) · [Android skills](https://developer.android.com/tools/agents/android-skills)

> 注意：本机 Windows 使用 schannel 时访问 `dl.google.com` 可能因证书吊销检查失败（`CRYPT_E_REVOCATION_OFFLINE`）。下载二进制请加 `--ssl-no-revoke`（`curl --ssl-no-revoke ...`）。CLI 自身上报（analytics）会超时但不影响功能，命令统一加 `--no-metrics` 避免卡顿。

## Android CLI 常用命令

| 命令 | 作用 | 本项目用法示例 |
|------|------|------|
| `android --no-metrics run` | 构建并部署启动 App（增量，比 `adb install` 快） | 装到已连接设备/模拟器验证 |
| `android --no-metrics install <apk...>` | 仅安装 APK，不激活组件 | 配合 Gradle 产物做增量部署 |
| `android --no-metrics emulator` | 管理 AVD：启动/停止/列出/查看 | `android emulator list`、`android emulator start <avd>` |
| `android --no-metrics screen` | 截图、录屏 | 配合 `android-emulator-qa` 做 UI 验收 |
| `android --no-metrics layout` | 导出当前 App 的 UI 树（Compose 层级） | 查过度嵌套/遮挡、对齐问题 |
| `android --no-metrics docs` | 搜索/拉取官方开发者文档 | 替代切浏览器查 API 用法 |
| `android --no-metrics sdk` | 安装/更新/列出 SDK 包 | `android sdk list --all`、`android sdk install platforms/android-34` |
| `android --no-metrics skills` | 管理 skills（add/remove/list/find） | 见下方「更新」 |
| `android --no-metrics update` | 升级 Android CLI 自身 | 定期检查新版 |
| `android --no-metrics create` | 从模板新建项目/模块 | 新模块脚手架 |
| `android --no-metrics info` | 打印环境信息（SDK 路径、设备、变量） | 排查环境 |

## 已安装 Android skills（22 个）

Agent 会根据你的指令**自动**加载匹配 skill，无需手动点名。分组按与本项目（Compose + Hilt + MVVM 电影 App）的相关度：

### 推荐关注（与当前开发强相关）

- **edge-to-edge** — 沉浸式/全面屏标准做法（状态栏、导航条、手势区、`WindowInsets`）。新建页面模板已在用。
- **testing-setup** — UI 测试 / 单元测试脚手架搭建，配合 `android-emulator-qa`。
- **r8-analyzer** — release 混淆/收缩分析，配合 `release` 流程排查误删。
- **migrate-xml-views-to-jetpack-compose** — 历史 XML 布局批量迁到 Compose。
- **navigation-3** — 新的 Compose 声明式导航（Navigation 3）。
- **adaptive** — 平板/折叠屏等大屏自适应布局。
- **agp-9-upgrade** — AGP 升级到 9 的迁移指引。
- **styles** — Material 主题与样式/Token 规范。
- **android-intent-security** — Intent/深链安全（本项目有 deep link 跳转详情页）。

### 按需使用

- **perfetto-sql** / **perfetto-trace-analysis** — 性能剖析（卡顿/帧率），配合 `android-performance`。
- **camerax** — 相机集成（若加扫码/拍照反馈）。
- **media3-cast-integration** — 投屏（Cast）集成。
- **appfunctions** — Google Play 应用功能（App Functions）接入。
- **play-billing-library-version-upgrade** — 内购库版本升级。
- **play-policy-insights** — Play 政策合规洞察。
- **engage-sdk-integration** — Google 互动 SDK 集成。
- **verified-email** — 邮箱验证流程。

### 备用（与当前 App 关联弱）

- **leanback-to-compose-tv-migration** — TV（Leanback）迁移到 Compose TV。
- **wear-compose-m3** — Wear OS Compose M3。
- **display-glasses-with-jetpack-compose-glimmer** — 眼镜显示（Glimmer）。

## 怎么触发

直接对 Agent 下符合场景的指令即可，无需记 skill 名：

- “把详情页改成 edge-to-edge 沉浸” → `edge-to-edge`
- “给搜索页加 UI 测试” → `testing-setup`
- “release 打包后分析 R8 有没有误删” → `r8-analyzer`
- “用 `android run` 装到模拟器并截图” → 调用 `android` CLI + `emulator`/`screen`
- “查一下 Navigation 3 怎么用” → `navigation-3` + `android docs`

## 更新

- 升级 CLI：`android --no-metrics update`
- 更新全部 skills 到最新：`android --no-metrics skills add --all --agent=opencode`
- 查看可用/已装 skills：`android --no-metrics skills list`
- 按关键词查找：`android --no-metrics skills find <keyword>`
