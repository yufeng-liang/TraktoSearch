<!-- caveman-begin -->
CAVEMAN MODE ACTIVE (full). Drop articles/filler/pleasantries/hedging. Fragments OK. Code/commits/security: write normal.
<!-- caveman-end -->

# 全局指令

## 用户信息
- 邮箱：1577865546@qq.com，SMTP 授权码从 memory mcp 获取
- Cloudflare 账号 ID：9fe1b3ef7e9891ea34b4d8f6d1210ff1
- 团队域名：douban-movie-api-peak.cloudflareaccess.com（Cloudflare Access）

## 用户偏好
- 面向用户输出、解释用中文
- 代码注释中文，Git commit 中文
- ViewModel error 英文（非 UI 展示文字）
- 命令行用 bash，Python 用 py
- 网络搜索优先 anysearch（首选）或 firecrawl；涉及 api 必须用 context7 查明用法

## 项目架构概览
- Android Jetpack Compose + Hilt + MVVM
- TraktRepository / TmdbRepository / ResourceRepository
- TtlCache：过期+容量上限+线程安全+飞行中去重 getOrAwait
- WatchlistWatchedIds：登录后加载一次（traktId/tmdbId 集合 + tmdb→trakt 映射）
- searchByTmdbCache 永久缓存（tmdb↔trakt 映射不变）

## 缓存使用规范
- 核心：一切缓存策略以尽量减少网络请求为第一目标；数据基本不变优先复用缓存
- 内存缓存优先，命中同步返回（不转圈）；未命中先等磁盘缓存加载完再决定是否请求网络
- 持久化缓存（基本不变数据）：TMDB/豆瓣详情、演职员、ID 映射、人物信息、海报 URL 用 DataStore（key→JSON）+ TtlCache 双级，跨重启复用
- 写成功数据时同步更新内存缓存并异步落盘；缓存 key 带版本号，结构变化时用新 key 隔离旧格式
- 图片沿用现有磁盘缓存目录/容量上限/LRU，不另建私有 filesDir，容量允许跨重启复用
- 剧照等增量资源保留已缓存，仅发现新增 URL 时刷新并合并
- TTL 只控确有刷新必要的数据；基本不变数据用长期/永久缓存 + 显式 forceRefresh

## 豆瓣爬取原则
核心：尽量减爬，避反爬。
链路：内存缓存 → 磁盘缓存（awaitLoaded）→ 全局池（CloudDetailsPoolManager）→ 爬豆瓣（成功异步上传全局池）
- 反爬延迟：爬前 3-5s，列表页 5-10s，失败重试≤1

## 国际化规范
- 用户可见文字用 stringResource，不硬编码
- strings.xml 同步：values/(英)、values-zh/(中)、values-ja/(日)、values-ko/(韩)
- 非 Composable 用 context.getString(R.string.xxx)

## 编码风格
- 优先沿用项目约定，改前先了解架构
- 依赖统一 gradle/libs.versions.toml
- AlertDialog containerColor = surfaceVariant
- 新建页面标准模板：标题栏 + Tab 用 Box + hazeSource/hazeEffect 毛玻璃吸顶，小白条沉浸用 Scaffold(contentWindowInsets = WindowInsets(0,0,0,0)) 或 Box，内容延展到导航栏背后
- API 代码生成/配置步骤用 context7 MCP
- 逆向/对接第三方接口：先 GitHub + CSDN/掘金 查现成实现，多源交叉验证

## 工作方式
- 重要修改/功能性损失先说明计划再执行
- 大任务拆可独立、范围不重叠子任务，按依赖用子智能体并行；主智能体统一审查/整合/验证/提交
- 修复/增功能/改 UI 验证通过后立即按实际改动提交，不合并无关功能
- 大量删除前先本地提交一次便回滚
- 多步任务按依赖推进：无共享写集并行，主智能体集成后最终 debug 验证
- 增/改/删功能后及时更新 App 内帮助与说明页
- 不确定先用 grill-me 问清（新增功能必用）
- UI 设计用 PureShowWidget 内联展示（SVG/HTML）；前端新页面用 web-dev 技能生成实时预览（启本地 HTTP 服务 + OpenPreview 链接），批准后用 ai-self-loop-ui-workflow 截图闭环
- 发布用 release skill 编排，说"发布"即触发
- Android 官方 CLI/skills（命令、清单、触发、更新）见 android-cli-skills.md；Agent 按指令自动加载。常用：android --no-metrics run/install/emulator/screen/layout/docs/sdk（加 --no-metrics 避上报超时）
- 安卓测试用 OpenAI 官方 skill（~/.agents/skills/），无需点名：
  - android-emulator-qa：模拟器功能验证/UI bug 复现/截图/logcat
  - android-performance：CPU/内存/帧率/卡顿剖析（Simpleperf/Perfetto/gfxinfo/meminfo/heap dump）
  - 前提：adb devices 确认在线 → installDebug，可组合（先 qa 再 performance 采样）
- UI/UX 设计用 Stark 插件（~/.agents/skills/，github.com/f0d010c/stark）：
  - stark：总入口，先定产品流/平台/原创性/动效/Token 再实现
  - android-design：Compose/Material 3 Expressive 设计（动态色彩/edge-to-edge/自适应）
  - 设计需求先走 Stark 定方向，再交 ai-self-loop-ui-workflow 落地

## Bug 修复工作流
1. 复现，记步骤
2. UI 层反向追数据层定位根因
3. 最小改动修复
4. 验证
5. 有价值教训写入本文件

## 豆瓣同步前置条件
豆瓣独立模式无 Trakt 状态；同步完跳 Trakt 一致检查，但仍须继续云端上传本地同步数据，否则本地成功误报失败并跳上传

## 授权撤销同步经验
- 后台撤销设备后 App 不能只依赖进程启动 AuthManager.initialize()；前台恢复立即调 check()，并用网络约束 15 分钟周期 Worker 兜底
- Worker 返 403 沿用 AuthManager.check() 清理逻辑，清本地令牌并切回激活页；撤销设备仍留记录，重绑用迁移邀请码

## 常见陷阱
- Haze 2.0 HazeMaterials.* 默认从 MaterialTheme.colorScheme.surface 读填充色；主题新增专属 surface 会同步改全站 haze，先确认是否须与其他主题一致
- Retrofit @GET("") 进 OkHttp 前因空 URL 失败，被评分层宽泛异常静默降级；网关 base URL 含 /api/omdb/ 时用 @GET(".")，用 MockWebServer 锁最终路径
- Brainstorm Companion 预览依赖 URL 查询参数 ?key=；部分应用内浏览器重写链接丢 key 显等待页或 403。先 Invoke-WebRequest 验证带 key 地址；服务端正常而浏览器丢 key 改独立本地静态预览服务器，不反复重启同 Companion 会话
- Android/Gradle 测试构建可能超默认工具超时但仍在跑；用较长单次超时，超时后先查 Gradle 进程、app/build/test-results、app/build/reports、APK 输出，再判成败；勿把工具 timeout 等同 Gradle 失败
- AuthManager.initialize() 可能同时由 MainActivity 和 AuthCheckWorker 进入；刷新锁内须按调用方看到的旧 access token 二次检查，避串行等待后第二次刷新再轮换 refresh token
- Git worktree 建新分支后 local.properties 不在版本库，须手动从 F:\trae-project\local.properties 复制到 worktree 目录
- 单测跨用例污染优先查 preferencesDataStore：委托是进程单例，各用例新建 Repository 仍读同一份磁盘数据；PersistentTtlCache 落盘还有 400ms 攒批且跑在 Repository 私有 scope 上，用例结束后仍会补写。setup 清 persistentCaches、teardown 取消落盘 scope 再清一次
- 详情页「以 TMDB 为准」类优先级改动须区分纯豆瓣条目（tmdbId=0）：无 TMDB 数据时无条件保留当前值会把豆瓣/Rexxar 结果永久挡在 UI 外
- dsh plugin/dshmarket 更新报 ERR_PNPM_UNEXPECTED_STORE 是 pnpm 11 store 漂移：pnpm 11 配置键是驼峰 storeDir，只认 C:\Users\15778\AppData\Local\pnpm\config\config.yaml（profile .npmrc 连字符 store-dir 无效）；已全局写 storeDir: C:\Users\15778\.pnpm\store\v11 修复。git 源插件首次安装被 pnpm 拦 prepare 脚本，须 ~\.dsh\profiles\web\pnpm-workspace.yaml 的 allowBuilds 置 true 后重跑
- 授权网关调试 App 默认用可直连 Pages 代理 https://tracktosearch-gateway.pages.dev/gateway-api 转发 auth-worker；勿把 workers.dev 直连写面向普通用户构建，否则部分网络超时
- 激活后短暂进主界面又回激活页，先核 worktree gateway.base.url 和构建产物 GATEWAY_BASE_URL，再查 auth check 请求是否带 Bearer；不能只凭页面现象判邀请码失效
- Trakt users/me?extended=full 可能只返用户名无头像；补拉优先 users/{username}/profile，网关或上游返 405 再回退 users/{username}，持久化成功返 images.avatar.full

## Git 规范
- commit 须 Conventional Commits：<type>(<scope>): <中文描述>
- type 按实际改动选：feat/fix/refactor/style/test/docs/chore
- scope 用受影响模块/功能，中文动宾短语，≤50 字，不加句号
- 不同改动拆不同提交；同文件多独立功能按 hunk 分暂存
- 提交前 git diff --cached --check 并查 git diff --cached --name-only，勿混入未授权文件/截图/构建产物/临时目录/敏感配置
- 改动过针对性测试/构建验证立即提交；多步任务每逻辑改动验证通过分别提交，勿最后笼统 chore 提交

## Cloudflare 生产部署经验
- AI TTS IP 限流不能用 KV get→put 读改写（并发覆盖计数）；须 D1 单条条件 UPSERT 原子完成窗口重置/递增/上限判断，定期清过期窗口行
- X-Real-IP 只信来自 gateway.internal service binding 请求；公开 workers.dev 须用 Cloudflare 注入 CF-Connecting-IP，避客户端伪造转发头绕过限流
- Pages 生产部署显式 --branch master：npx wrangler pages deploy public --project-name app-config --branch master
- 部署后不只看 Wrangler 成功输出；用 npx wrangler pages deployment list --project-name app-config 确认最新 Environment=Production、Branch=master、提交 SHA，开部署 URL 查实际静态资源版本
- Pages 受 Cloudflare Access 保护时，未带登录 Cookie 的 curl/Invoke-WebRequest 可能只拿 302 或登录 HTML，不能据此判页面代码未更新；应已登录浏览器强制刷新，或直查部署哈希 URL 资源内容
- Worker 部署后记录版本 ID，至少验健康检查和本次变更 API；Pages+Worker 部署成功≠业务流程已验证
- Wrangler 部署超时/无输出不能直接判成败：先查并结束残留 wrangler/node 子进程，再分别用直连和 Clash 代理重试。Windows Clash 常见 HTTP 代理 http://127.0.0.1:7890，只当前命令临时设 HTTP_PROXY/HTTPS_PROXY/ALL_PROXY，勿写全局；重试仍记 Worker 版本 ID、Pages Production/master 记录和健康检查结果
- Pages Functions 须从 app-config 项目根部署（或显式指定该目录配置），确保读 app-config/wrangler.toml 并传 functions/；其他目录直传 public 只发静态，致 /admin-api/* 返 HTML/Access 页并触发前端 Invalid server response。部署后于部署哈希域名请 /admin-api/admin/health，未登录应返 JSON 401 而非 HTML

## Android ADB 设备核验经验
- Gradle installDebug 设备筛选可能因 ADB 返 API 属性异常跳过；先用 adb -s <serial> shell getprop ro.build.version.release 和 ro.build.version.sdk 记实际值
- Gradle 提示 minSdkVersion 不兼容仍用 adb -s <serial> install -r <apk> 事实核验；曾 Gradle 报 API 21 但 ADB 直装返 Success 不一致
- 安装成功须续 exec am start、UI 树检查、screencap 后 adb pull 截图、logcat -b crash，不能凭安装成功宣称功能过。系统属性与用户描述不一致保留命令输出报差异

## Android 开屏截图验收经验
- 开屏视觉验收须记设备实际分辨率/密度/API/安装结果/主 Activity/启动进程/UI 树/logcat -b crash；构建成功≠真机视觉过
- Windows PowerShell 用 > 收 adb exec-out screencap -p 破 PNG 二进制；截图应 cmd.exe /c 或其他二进制安全方式存，存后图片解析确认尺寸格式
- 系统 Splash 底部 branding_image 虚线框是原型安全区标记，复刻须保留；系统 branding 区有尺寸限制，不能盲目放大资源实现网页全宽，必要改自定义 Compose 启动内容
- 对照动画至少抓初始/延迟后/闭合后/进首屏四时间点；拍板图标须同时核 stick 棕上沿/白斜纹/旋转轴/场记板主体嵌入关系

## 安全意识
- 不硬编码密码、密钥
- 配置文件敏感信息提醒用户注意保护

## 不确定就主动问
不要瞎猜，讲清权衡。有问题明说，有更简单做法直说，该反对时反对

## 可视化伴侣踩坑经验
- 头脑风暴可视化伴侣重启可能建新 session 目录，复用原端口和 key；勿只凭旧 server-info 判当前服务状态
- 排查须确认当前端口对应监听 Node 进程，及最新 session server-info 的 screen_dir。HTML 写旧 session 目录时带 key 请求可能成功但页面仍显 waiting
- session 不一致将 HTML 同步到实际运行 session 的 screen_dir，用带 key 会话验 /files/<filename> 与首页都返
- key URL 首访写同源 cookie 后跳裸地址，是伴侣正常 bootstrap；浏览器未留 cookie 续供完整 key URL
- 授权刷新 challenge 勿用 Cloudflare KV 做一次性凭证（KV 不保原子读写一致）；用 D1 条件更新或 Durable Object 原子消费，并为重复消费留重放检测
