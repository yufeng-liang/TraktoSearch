# 全局指令

## 用户信息

- **邮箱**：1577865546@qq.com，SMTP 授权码可从memory mcp中获取
- **Cloudflare 账号 ID**：9fe1b3ef7e9891ea34b4d8f6d1210ff1
- **团队域名**：douban-movie-api-peak.cloudflareaccess.com（Cloudflare Access）

## 用户偏好

- 面向用户输出、解释说明用中文
- 代码注释用中文，Git commit message 用中文
- ViewModel error 用英文（非 UI 展示文字）
- 命令行用 bash，Python 用 `py`
- 网络搜索**优先**用 anysearch（首选）或 firecrawl；涉及 api 的必须用 context7 查明用法，避免瞎猜用法

## 项目架构概览

- Android Jetpack Compose + Hilt + MVVM
- TraktRepository / TmdbRepository / ResourceRepository
- TtlCache：过期时间 + 容量上限 + 线程安全 + 飞行中去重 `getOrAwait`
- WatchlistWatchedIds 缓存：登录后加载一次（traktId/tmdbId 集合 + tmdb→trakt 映射）
- searchByTmdbCache 永久缓存（tmdb↔trakt 映射不变）

## 缓存使用规范

- **核心原则：一切缓存策略都以尽量减少网络请求为第一目标。**只要数据基本不变，就应优先复用缓存；只有缓存不存在、明确过期、发现新增资源或用户主动要求刷新时才访问网络。
- 内存缓存优先，命中同步返回（不转圈）；未命中时先等待磁盘缓存加载完成，再决定是否请求网络，避免启动竞态造成重复请求。
- **持久化缓存（基本不变数据）**：TMDB/豆瓣详情、演职员、ID 映射、人物信息、海报 URL 等使用 DataStore（key→JSON）+ TtlCache 双级缓存，跨应用重启复用。
- 写入成功数据时同步更新内存缓存，并异步落盘；缓存 key 必须带版本号，数据结构变化时用新 key 隔离旧格式，避免旧数据污染新模型。
- 图片文件沿用现有磁盘缓存目录、容量上限和 LRU/清理边界，不另建私有 `filesDir`；在容量允许时跨重启复用，避免重复下载。
- 剧照等可增量资源保留已缓存结果；只有需要发现新增 URL 时刷新接口，并将新结果与旧集合合并。
- TTL 只用于控制确有刷新必要的数据；对基本不变数据使用长期 TTL/永久缓存，并保留显式 `forceRefresh` 作为例外入口。

## 豆瓣爬取原则

**尽量减少爬取，避免触发反爬。**

链路：内存缓存 → 磁盘缓存（`awaitLoaded`）→ 全局池（CloudDetailsPoolManager）→ 爬豆瓣（成功异步上传全局池）
- 反爬延迟：爬前 3-5 秒，列表页 5-10 秒，失败重试≤1 次

## 国际化规范

- 用户可见文字用 stringResource，不硬编码
- strings.xml 同步：values/（英）、values-zh/（中）、values-ja/（日）、values-ko/（韩）
- 非 Composable 用 `context.getString(R.string.xxx)`

## 编码风格

- 优先沿用项目约定，修改前先了解现有架构
- 依赖统一用 `gradle/libs.versions.toml`
- AlertDialog containerColor = surfaceVariant
- **新建页面标准模板**：标题栏 + Tab 用 `Box` + `hazeSource`/`hazeEffect` 毛玻璃吸顶，小白条沉浸用 `Scaffold(contentWindowInsets = WindowInsets(0,0,0,0))` 或 `Box`，内容延展到导航栏背后
- API 代码生成/配置步骤用 context7 MCP
- **逆向/对接第三方接口**：先 GitHub + 博客（CSDN/掘金）查现成实现，多来源交叉验证

## 工作方式

- 重要修改/功能性损失先说明计划再执行
- 大型任务先拆分为可独立处理、写入范围不重叠的子任务，按依赖关系使用子智能体并行推进；主智能体负责统一审查、整合、验证和提交
- 修复/增功能/改 UI 的验证通过后立即按实际改动内容提交，不要把多个无关功能合并成一个提交
- 大量删除前先本地提交一次方便回滚
- 多步任务按依赖关系推进：无共享写集的子任务并行处理，主智能体统一集成后进行最终 debug 验证
- 增加/修改/删除功能后，及时更新 App 内帮助与说明页，确保帮助与说明与实际情况一致
- 有不确定先用 grill-me问清楚（新增功能时必须用）
- 涉及 UI 设计时，使用 PureShowWidget 内联展示设计效果（SVG/HTML）；前端新页面设计用 web-dev 技能生成实时预览（自动启动本地 HTTP 服务器并通过 OpenPreview 提供预览链接），批准后用 `ai-self-loop-ui-workflow` 截图闭环。
- 发布用 `release` skill 编排，说"发布"即触发
- Android 官方 CLI 与 skills（命令、已装清单、触发方式、更新）见 `android-cli-skills.md`；Agent 会按指令自动加载对应 skill，无需手动点名。常用：`android --no-metrics run/install/emulator/screen/layout/docs/sdk`，记得加 `--no-metrics` 避免上报超时。
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

## 豆瓣同步前置条件

- 豆瓣独立模式没有 Trakt 状态；同步完成后应跳过自动 Trakt 一致性检查，但仍需继续云端上传本地同步数据，否则会把本地成功误报为失败并跳过上传。

## 授权撤销同步经验

- 后台撤销设备后，App 不能只依赖进程启动时的 `AuthManager.initialize()`；前台恢复应立即调用 `check()`，并通过网络约束的 15 分钟周期 Worker 兜底。
- Worker 返回 403 时沿用 `AuthManager.check()` 的清理逻辑，清除本地令牌并由导航状态切回激活页；撤销设备仍保留记录，重新绑定应使用迁移邀请码。

## 常见陷阱

- Haze 2.0 的 `HazeMaterials.*` 默认从 `MaterialTheme.colorScheme.surface` 读取填充色；主题新增专属 `surface` 时会同步改变全站 haze，应先确认是否需要保持与其他主题一致。

- Retrofit 的 `@GET("")` 会在进入 OkHttp 前因空 URL 失败，并被评分层的宽泛异常捕获静默降级；网关 base URL 已含 `/api/omdb/` 时应使用 `@GET(".")`，并用 MockWebServer 锁定最终路径。
- Brainstorm Companion 预览依赖 URL 查询参数 `?key=`；部分应用内浏览器会把链接重写为不带 key 的裸地址，导致 Companion 显示等待页或返回 403。先用 `Invoke-WebRequest` 验证带 key 地址；若服务端正常而浏览器丢 key，应改用独立的本地静态预览服务器，不要反复重启同一个 Companion 会话。
- Android/Gradle 测试和构建可能超过默认工具超时但仍在运行；使用较长的单次超时，超时后先检查 Gradle 进程、`app/build/test-results`、`app/build/reports` 和 APK 输出，再判断成功或失败。不要把工具层 timeout 直接等同于 Gradle 失败。
- `AuthManager.initialize()` 可能同时由 `MainActivity` 和 `AuthCheckWorker` 进入；刷新锁内必须按调用方看到的旧 access token 二次检查，避免串行等待后的第二次刷新再次轮换 refresh token。
- Git worktree 建新分支后 `local.properties` 不在版本库，需手动从 `F:\trae-project\local.properties` 复制到 worktree 目录
- `dsh plugin` 或 dshmarket 更新报 `ERR_PNPM_UNEXPECTED_STORE` 是 pnpm 11 store 位置漂移：**pnpm 11 配置键是驼峰 `storeDir`，且只认 `C:\Users\15778\AppData\Local\pnpm\config\config.yaml`**（profile `.npmrc` 的连字符 `store-dir` 无效）；已全局写入 `storeDir: C:\Users\15778\.pnpm\store\v11` 修复。git 源插件首次安装会被 pnpm 拦 prepare 脚本，需在 `~/.dsh/profiles/web/pnpm-workspace.yaml` 的 `allowBuilds` 把对应包置 true 后重跑。
- 授权网关调试时，App 默认应使用可直连的 Pages 代理 `https://tracktosearch-gateway.pages.dev/gateway-api`，由其转发到 `auth-worker`；不要把 `workers.dev` 直连地址写入面向普通用户的构建，否则部分网络环境会超时。
- 若激活后短暂进入主界面又回到激活页，先核对 worktree 的 `gateway.base.url` 和构建产物中的 `GATEWAY_BASE_URL`，再检查 auth `check` 请求是否带 Bearer；不能只根据页面现象判断是邀请码失效。
- Trakt `users/me?extended=full` 可能只返回用户名而没有头像；补拉头像时优先请求 `users/{username}/profile`，若网关或上游返回 405，再回退到 `users/{username}`，并持久化成功返回的 `images.avatar.full`。

## Git 规范

- commit 必须使用 Conventional Commits 格式：`<type>(<scope>): <中文描述>`
- `type` 必须根据实际改动内容选择：
  - `feat`：新增用户可见功能
  - `fix`：修复错误或异常行为
  - `refactor`：不改变功能意图的结构/API 重构
  - `style`：仅 UI 视觉、布局或样式调整
  - `test`：仅测试新增或修改
  - `docs`：文档或说明更新
  - `chore`：构建、依赖、工具等杂项维护
- `scope` 使用受影响的模块或功能，描述使用中文动宾短语，不超过 50 个字符，不加句号
- 不同改动内容必须拆分为不同提交；同一文件包含多个独立功能时按 hunk 分开暂存
- 提交前使用 `git diff --cached --check`，并检查 `git diff --cached --name-only`，不得混入未授权文件、截图、构建产物、临时目录或敏感配置
- 改动通过针对性测试/构建验证后立即提交；多步任务也应在每个逻辑改动验证通过后分别提交，不要拖到最后使用笼统的 `chore` 提交

## Cloudflare 生产部署经验

- AI TTS 的 IP 限流不能使用 KV `get → put` 读改写：并发请求会覆盖计数。需要用 D1 单条条件 UPSERT 原子完成窗口重置、递增和上限判断，并定期清理过期窗口行。
- `X-Real-IP` 只能在已确认来自 `gateway.internal` service binding 的请求中信任；公开 `workers.dev` 请求必须使用 Cloudflare 注入的 `CF-Connecting-IP`，避免客户端伪造转发头绕过限流。

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

## Android 开屏截图验收经验

- 开屏视觉验收必须记录设备实际分辨率、密度、API、安装结果、主 Activity、启动进程、UI 树和 `logcat -b crash`；构建成功不等于真机视觉通过。
- Windows PowerShell 直接用 `>` 接收 `adb exec-out screencap -p` 可能破坏 PNG 二进制；截图应通过 `cmd.exe /c` 或其他二进制安全方式保存，保存后用图片解析确认尺寸和格式。
- 系统 Splash 的底部 `branding_image` 虚线框是原型中的安全区域标记，复刻时必须保留；系统 branding 区域有尺寸限制，不能靠盲目放大资源实现网页全宽，必要时应改用自定义 Compose 启动内容。
- 对照动画时至少抓取初始、延迟后、闭合后和进入首屏四个时间点；拍板图标需要同时核对 stick 的棕色上沿、白色斜纹、旋转轴和场记板主体的嵌入关系。

## 安全意识

- 不硬编码密码、密钥
- 配置文件敏感信息提醒用户注意保护

## 不确定就主动问

不要瞎猜，讲清楚权衡。有问题明说，有更简单做法直说，该反对时反对。

## 可视化伴侣踩坑经验

- 头脑风暴可视化伴侣重启后可能创建新的 session 目录，同时复用原端口和 key；不要只根据旧的 `server-info` 判断当前服务状态。
- 排查时要确认当前端口对应的监听 Node 进程，以及最新 session 的 `server-info` 中的 `screen_dir`。如果 HTML 写在旧 session 目录，带 key 的请求可能成功但页面仍只显示 waiting。
- 发现 session 不一致时，将 HTML 同步到实际运行 session 的 `screen_dir`，并用带 key 的会话验证 `/files/<filename>` 与首页内容都能返回。
- key URL 首次访问会写入同源 cookie 后跳转到裸地址，这是伴侣的正常 bootstrap 流程；若浏览器没有保留 cookie，应继续提供完整 key URL。
- 授权刷新 challenge 不应使用 Cloudflare KV 做一次性凭证：KV 不保证原子读写一致性。应使用 D1 条件更新或 Durable Object 原子消费，并为重复消费保留重放检测。
