# 全局指令

## 用户偏好

- 面向用户输出、解释说明、注释用中文
- 代码注释用中文，Git commit message 用中文
- ViewModel error 用英文（非 UI 展示文字）
- 命令行用 bash，Python 用 `py`
- 善用 anysearch/firecrawl 查信息，API 用法用 context7 查明

## 项目架构概览

- Android Jetpack Compose + Hilt + MVVM
- TraktRepository / TmdbRepository / ResourceRepository
- TtlCache：过期时间 + 容量上限 + 线程安全 + 飞行中去重 `getOrAwait`
- WatchlistWatchedIds 缓存：登录后加载一次（traktId/tmdbId 集合 + tmdb→trakt 映射）
- searchByTmdbCache 永久缓存（tmdb↔trakt 映射不变）

## 缓存使用规范

- 内存缓存优先，命中同步返回（不转圈），未命中再走网络
- MovieCard 用 WatchlistWatchedIds 算真实 isInWatchlist/isWatched，不能硬编码 false
- **持久化缓存（基本不变数据）**：
  - TMDB 详情 / 演职员 / tmdb↔trakt ID 映射 / 人物信息
  - DataStore（key→JSON）+ TtlCache 双级缓存
  - 写内存同步写 DataStore（异步不阻塞）
  - key 版本号（`_v2` 后缀）自动失效旧缓存
  - 未命中时 `awaitLoaded()` 等磁盘加载完再查，防重复请求

## 豆瓣爬取原则

**尽量减少爬取，避免触发反爬。**

链路：内存缓存 → 磁盘缓存（`awaitLoaded`）→ 全局池（CloudDetailsPoolManager）→ 爬豆瓣（成功异步上传全局池）
- 全局池字段合并：整条目非 null 覆盖，null 保留；单字段只覆盖该字段
- 反爬延迟：爬前 3-5 秒，列表页 5-10 秒，失败重试≤1 次

## 国际化规范

- 用户可见文字用 stringResource，不硬编码
- strings.xml 同步：values/（英）、values-zh/（中）、values-ja/（日）、values-ko/（韩）
- 非 Composable 用 `context.getString(R.string.xxx)`

## 编码风格

- 优先沿用项目约定，修改前先了解现有架构
- 依赖统一用 `gradle/libs.versions.toml`
- AlertDialog containerColor = surfaceVariant
- **新建页面标准模板**：标题栏 + Tab 用 `Box` + `hazeSource`/`hazeEffect` 毛玻璃吸顶，小白条沉浸用 `Scaffold(contentWindowInsets = WindowInsets(0,0,0,0))` 或 `Box`，不加 `navigationBarsPadding`，内容延展到导航栏背后
- API 代码生成/配置步骤用 context7 MCP
- **逆向/对接第三方接口**：先 GitHub + 博客（CSDN/掘金）查现成实现，多来源交叉验证

## 工作方式

- 重要修改/功能性损失先说明计划再执行
- 修复/增功能/改 UI 构建通过后及时提交
- 大量删除前先本地提交一次方便回滚
- 多步任务最后统一构建 debug 验证
- 完成任务后简要总结
- 有不确定先用 brainstorming（新增功能必须用）
- UI 设计先用 PureShowWidget/web-dev 原型预览，批准后用 `ai-self-loop-ui-workflow` 截图闭环
- 发布用 `release` skill 编排，说"发布"即触发
- 安卓测试用 OpenAI 官方 skill（`~/.agents/skills/`），无需点名即匹配：
  - `android-emulator-qa`：模拟器功能验证/UI bug 复现/截图/logcat
  - `android-performance`：CPU/内存/帧率/卡顿剖析（Simpleperf/Perfetto/gfxinfo/meminfo/heap dump）
  - 前提：`adb devices` 确认在线 → installDebug，可组合（先 qa 驱动再 performance 采样）
- UI/UX 设计用 Stark 插件（`~/.agents/skills/`，github.com/f0d010c/stark）：
  - `stark`：总入口，先定产品流/平台/原创性/动效/Token 再实现
  - `android-design`：Compose/Material 3 Expressive 设计（动态色彩/edge-to-edge/自适应）
  - 设计需求先走 Stark 定方向，再交给 ai-self-loop-ui-workflow 落地

## Bug 修复工作流

1. 复现，记录步骤
2. 从 UI 层反向追踪到数据层定位根因
3. 最小改动修复
4. 验证
5. 有价值教训写入本文件

## 授权撤销同步经验

- 后台撤销设备后，App 不能只依赖进程启动时的 `AuthManager.initialize()`；前台恢复应立即调用 `check()`，并通过网络约束的 15 分钟周期 Worker 兜底。
- Worker 返回 403 时沿用 `AuthManager.check()` 的清理逻辑，清除本地令牌并由导航状态切回激活页；撤销设备仍保留记录，重新绑定应使用迁移邀请码。

## 常见陷阱

- Retrofit 的 `@GET("")` 会在进入 OkHttp 前因空 URL 失败，并被评分层的宽泛异常捕获静默降级；网关 base URL 已含 `/api/omdb/` 时应使用 `@GET(".")`，并用 MockWebServer 锁定最终路径。
- `AuthManager.initialize()` 可能同时由 `MainActivity` 和 `AuthCheckWorker` 进入；刷新锁内必须按调用方看到的旧 access token 二次检查，避免串行等待后的第二次刷新再次轮换 refresh token。
- Git worktree 建新分支后 `local.properties` 不在版本库，需手动从 `F:\trae-project\local.properties` 复制到 worktree 目录
- 授权网关调试时，App 默认应使用可直连的 Pages 代理 `https://tracktosearch-gateway.pages.dev/gateway-api`，由其转发到 `auth-worker`；不要把 `workers.dev` 直连地址写入面向普通用户的构建，否则部分网络环境会超时。
- 若激活后短暂进入主界面又回到激活页，先核对 worktree 的 `gateway.base.url` 和构建产物中的 `GATEWAY_BASE_URL`，再检查 auth `check` 请求是否带 Bearer；不能只根据页面现象判断是邀请码失效。

## Git 规范

- commit 格式：`<type>: <描述>`
  - feat / fix / refactor / docs / style / test / chore

## Cloudflare 生产部署经验

- Pages 生产部署必须显式使用 `--branch master`：`npx wrangler pages deploy public --project-name app-config --branch master`。
- 部署后不要只看 Wrangler 的成功输出；用 `npx wrangler pages deployment list --project-name app-config` 确认最新记录的 `Environment=Production`、`Branch=master` 和提交 SHA，并打开该部署 URL 检查实际静态资源版本。
- Pages 受 Cloudflare Access 保护时，未带登录 Cookie 的 `curl`/`Invoke-WebRequest` 可能只拿到 302 或登录 HTML，不能据此判断页面代码未更新。应在已登录浏览器中强制刷新，或直接检查部署哈希 URL 的资源内容。
- Worker 部署后记录版本 ID，并至少验证健康检查和本次变更涉及的 API；Pages 与 Worker 的部署成功不等于业务流程已验证。
- Wrangler 部署若超时或长时间无输出，不能直接判定成功或失败：先检查并结束本次残留的 `wrangler`/`node` 子进程，再分别用直连和 Clash 代理重试。Windows Clash 常见 HTTP 代理为 `http://127.0.0.1:7890`，只在当前命令临时设置 `HTTP_PROXY`、`HTTPS_PROXY`、`ALL_PROXY`，不要写入全局配置；重试后仍须记录 Worker 版本 ID、Pages 的 Production/master 记录和健康检查结果。
- Pages Functions 必须从 `app-config` 项目根目录部署（或显式指定该目录的配置），确保读取 `app-config/wrangler.toml` 并上传 `functions/`；从其他目录直接上传 `public` 可能只发布静态文件，导致 `/admin-api/*` 返回 HTML/Access 页面并触发前端 `Invalid server response`。部署后应在部署哈希域名请求 `/admin-api/admin/health`，未登录时应返回 JSON `401`，而不是 HTML。

## Android ADB 设备核验经验

- Gradle 的 `installDebug` 设备筛选可能因 ADB 返回的 API 属性异常而跳过设备；先用 `adb -s <serial> shell getprop ro.build.version.release` 和 `ro.build.version.sdk` 记录设备实际报告值。
- 若 Gradle 提示 `minSdkVersion` 不兼容，仍应直接用 `adb -s <serial> install -r <apk>` 做一次事实核验；本项目曾出现 Gradle 报 API 21、但 ADB 直接安装返回 `Success` 的不一致情况。
- 安装成功后必须继续执行 `am start`、UI 树检查、`screencap` 后 `adb pull` 截图和 `logcat -b crash`，不能仅凭安装成功宣称功能通过。若系统属性与用户描述不一致，保留命令输出并报告差异。

## 安全意识

- 不硬编码密码、密钥
- 配置文件敏感信息提醒用户注意保护

## 不确定就主动问

不要瞎猜，讲清楚权衡。有问题明说，有更简单做法直说，该反对时反对。
