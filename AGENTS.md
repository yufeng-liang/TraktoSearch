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
- 善用 anysearch/firecrawl 查信息，涉及api的必须用context7查明用法，避免瞎猜用法

## 项目架构概览

- Android Jetpack Compose + Hilt + MVVM
- TraktRepository / TmdbRepository / ResourceRepository
- TtlCache：过期时间 + 容量上限 + 线程安全 + 飞行中去重 `getOrAwait`
- WatchlistWatchedIds 缓存：登录后加载一次（traktId/tmdbId 集合 + tmdb→trakt 映射）
- searchByTmdbCache 永久缓存（tmdb↔trakt 映射不变）

## 缓存使用规范

- 内存缓存优先，命中同步返回（不转圈），未命中再走网络
- **持久化缓存（基本不变数据）**：
  - TMDB 详情 / 演职员 / tmdb↔trakt ID 映射 / 人物信息
  - DataStore（key→JSON）+ TtlCache 双级缓存
  - 写内存同步写 DataStore（异步不阻塞）
  - key 版本号（`_v2` 后缀）自动失效旧缓存
  - 未命中时 `awaitLoaded()` 等磁盘加载完再查，防重复请求

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
- 修复/增功能/改 UI 构建通过后及时提交
- 大量删除前先本地提交一次方便回滚
- 多步任务最后统一构建 debug 验证
- 增加/修改/删除功能后，及时更新 App 内帮助与说明页，确保帮助与说明与实际情况一致
- 有不确定先用 grill-me问清楚（新增功能时必须用）
- 涉及 UI 设计时，使用 PureShowWidget 内联展示设计效果（SVG/HTML）；前端新页面设计用 web-dev 技能生成实时预览（自动启动本地 HTTP 服务器并通过 OpenPreview 提供预览链接），批准后用 `ai-self-loop-ui-workflow` 截图闭环。
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

## 常见陷阱

- Git worktree 建新分支后 `local.properties` 不在版本库，需手动从 `F:\trae-project\local.properties` 复制到 worktree 目录

## Git 规范

- commit 格式：`<type>: <描述>`
  - feat / fix / refactor / docs / style / test / chore

## 安全意识

- 不硬编码密码、密钥
- 配置文件敏感信息提醒用户注意保护

## 不确定就主动问

不要瞎猜，讲清楚权衡。有问题明说，有更简单做法直说，该反对时反对。
