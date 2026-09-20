<!-- caveman-begin -->
CAVEMAN MODE ACTIVE (full). Drop articles/filler/pleasantries/hedging. Fragments OK. Code/commits/security: write normal.
<!-- caveman-end -->

# 项目规则（TrackToSearch Android）

## 0. 记忆纪律（agentmemory，本仓库所有 Agent 共享同一记忆库）
服务常驻 http://localhost:3111（MCP 工具 memory_*；ZCode/Claude Code/Codex/dsh 已接线，Trae 未接）。
- 开始非平凡任务**前**：`memory_smart_search` 查本项目相关决策/教训（一次 miss 只花一个调用）
- 决策定案、问题解决**当下**：`memory_save`（content 含理由，2-5 个具体概念）；被纠正时存 `memory_lesson_save`（lesson 带置信度，会主动浮现）
- 教训组 tag：`douban-sync` / `auth-revoke` / `common-pitfalls` / `cloudflare-deploy` / `adb-verify` / `splash-accept` / `viz-companion`（历史经验已全量在库）
- 不存：代码里能读到的、瞬时状态、密钥、步骤流水账（hooks 已自动记录会话）

## 1. 安卓开发工作流（每项任务按此闭环）
1. **了解**：先读相关代码与 §2 架构约定；非平凡任务先查记忆（§0）
2. **计划**：重要修改/功能性损失先说明计划再执行；不确定/新增功能先用 grill-me 问清；UI 设计先走 Stark 定方向再实现
3. **实现**：遵循 §3 规范
   - 大任务拆可独立、范围不重叠子任务按依赖并行（子代理提示里写明「不要跑 Gradle」）；主代理统一审查/整合/验证/提交
   - 子代理并行期间**禁止一切 Gradle 任务**：本机内存撑不住多 daemon，会拖死测试 worker 并静默漏跑末尾测试类
   - 增/改/删功能后同步更新 App 内帮助与说明页
4. **验证**：改动全部完成后统一验证——纯 Kotlin/UI 改动跑 `:app:compileDebugKotlin` 即可（别改一次构建一次）；确需产物/装机时再 assembleDebug → 功能/UI 改动真机核验（adb 安装后 exec am start + UI 树 + screencap + logcat -b crash，凭安装成功不宣称功能过）
5. **提交**：验证通过即按实际改动提交（Conventional Commits 中文，§4），不合并无关功能；同文件多独立功能按 hunk 分暂存；大量删除前先本地提交一次便回滚
6. **沉淀**：有价值教训 `memory_lesson_save`（勿写回本文件）

## 2. 项目架构概览
- Android Jetpack Compose + Hilt + MVVM
- TraktRepository / TmdbRepository / ResourceRepository
- TtlCache：过期+容量上限+线程安全+飞行中去重 getOrAwait
- WatchlistWatchedIds：登录后加载一次（traktId/tmdbId 集合 + tmdb→trakt 映射）
- searchByTmdbCache 永久缓存（tmdb↔trakt 映射不变）
- 豆瓣爬取链路：内存缓存 → 磁盘缓存（awaitLoaded）→ 全局池（CloudDetailsPoolManager）→ 爬豆瓣（成功异步上传全局池）；反爬延迟爬前 3-5s、列表页 5-10s、失败重试≤1
- 授权：网关 Pages 代理（https://tracktosearch-gateway.pages.dev/gateway-api）+ auth-worker

## 3. 编码与缓存规范
**缓存**（核心：尽量减少网络请求；数据基本不变优先复用缓存）
- 内存缓存优先，命中同步返回；未命中先等磁盘缓存加载完再决定是否请求网络
- 持久化缓存（基本不变数据：TMDB/豆瓣详情、演职员、ID 映射、人物、海报 URL）：DataStore（key→JSON）+ TtlCache 双级，跨重启复用
- 写成功同步更新内存缓存并异步落盘；**缓存 key 带版本号**，结构变化用新 key 隔离旧格式
- 图片沿用现有磁盘缓存目录/容量上限/LRU；剧照等增量资源保留已缓存，仅新增 URL 时刷新合并
- TTL 只控确有刷新必要的数据；基本不变数据用长期/永久缓存 + 显式 forceRefresh

**风格**
- 依赖统一 gradle/libs.versions.toml（含开源页版本，见 §5 约束）
- AlertDialog containerColor = surfaceVariant；新建页面：标题栏 + Tab 用 Box + hazeSource/hazeEffect 毛玻璃吸顶，小白条沉浸用 Scaffold(contentWindowInsets = WindowInsets(0,0,0,0)) 或 Box
- API 代码生成/配置步骤用 context7 MCP；逆向/对接第三方接口先 GitHub + CSDN/掘金 查现成实现，多源交叉验证
- UI 设计用 PureShowWidget 内联展示；前端新页面 web-dev 技能实时预览，批准后 ai-self-loop-ui-workflow 截图闭环
- 安卓 CLI/skills：android --no-metrics run/install/emulator/screen/layout/docs/sdk；测试用 android-emulator-qa（功能/复现/截图/logcat）与 android-performance（CPU/内存/帧率剖析）

**国际化**：用户可见文字用 stringResource 不硬编码；strings.xml 同步 values/（英）、values-zh/（中）、values-ja/（日）、values-ko/（韩）；非 Composable 用 context.getString(R.string.xxx)

## 4. Git 规范
- commit 须 Conventional Commits：`<type>(<scope>): <中文描述>`；type 按实际改动选 feat/fix/refactor/style/test/docs/chore；scope 用受影响模块/功能，中文动宾短语 ≤50 字不加句号
- 不同改动拆不同提交；同文件多独立功能按 hunk 分暂存
- 提交前 `git diff --cached --check` 并查 `--cached --name-only`，勿混入未授权文件/截图/构建产物/临时目录/敏感配置
- 改动过针对性测试/构建验证立即提交；多步任务每逻辑改动验证通过分别提交，勿最后笼统 chore

## 5. 防回归硬约束（改到相关代码必须遵守，违反即出难查的 bug）
- `assets/quotes.json` 的 id **只增不删不改名**：daily_stamp 只存 (epochDay, quoteId)，改名会把历史卡片抹成空格子；换内容新增 id，旧条目留着
- Watchlist 离线快照共用 `media_items` 表，**主键必须含 (traktId, type)**（Trakt 电影/剧集 ID 分属不同命名空间，可能同号）；改主键同步加无损 Room 迁移 + 跨分类同号回归测试
- 开源相关页依赖版本禁止手工复制：从 `libs` Version Catalog 构建 `OPEN_SOURCE_VERSION_CATALOG_BASE64`；Gradle 会把 `jieba-analysis` 规范化成 `jieba.analysis`，解析统一 `-`/`_` 为 `.`
- 详情页「以 TMDB 为准」类改动须区分纯豆瓣条目（tmdbId=0）：无 TMDB 数据时不得无条件保留当前值
- 授权网关 base URL 含 /api/omdb/ 时 Retrofit 用 `@GET(".")`（空 URL 会被静默降级）；用 MockWebServer 锁最终路径
- 注释正文避免出现 `/*` 序列（KDoc 嵌套注释会把后半文件吞掉）

## 6. 用户偏好与安全
- 面向用户输出、解释用中文；代码注释中文；ViewModel error 英文（非 UI 展示文字）；命令行 bash、Python 用 py
- 网络搜索优先 anysearch（首选）或 firecrawl；涉及 api 必须用 context7 查明用法
- 不硬编码密码/密钥；配置文件敏感信息提醒用户保护
- 不确定就主动问：不要瞎猜，讲清权衡，有更简单做法直说，该反对时反对

- 子代理并行期间 Gradle 禁令见 §1.3；worktree 开发注意 lessons（common-pitfalls 组）
