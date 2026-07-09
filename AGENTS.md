# 全局指令

## 语言偏好

- 面向用户的输出、解释说明、注释均使用中文
- 代码注释使用中文，Git commit message 使用中文
- ViewModel 中的 error 信息用英文（非 UI 展示文字）

## 项目架构概览

- Android Jetpack Compose + Hilt + MVVM 架构
- 仓库层：TraktRepository（Trakt API）、TmdbRepository（TMDB API）、ResourceRepository（资源搜索）
- 全局缓存：TtlCache（带过期时间和容量上限的线程安全内存缓存，支持飞行中去重 getOrAwait）
- WatchlistWatchedIds 全局缓存：登录后加载一次，包含 traktId/tmdbId 集合和 tmdb→trakt 映射
- ID 转换缓存（searchByTmdbCache）：永不过期，tmdb↔trakt 映射不会变

## 缓存使用规范

- 缓存优先：先查内存缓存，命中则同步返回（不转圈），未命中再走网络
- WatchlistWatchedIds.traktIdByTmdb() → 想看/已看中已有的影视秒进
- TraktRepository.getCachedTraktId() → ID 转换缓存秒进（之前转换过的）
- 缓存同步查询必须在协程启动之前，避免不必要的转圈 UI
- 详情页/发现页/社区列表的 MovieCard 必须传递真实的 isInWatchlist/isWatched 状态，不能硬编码 false
- 影视卡片想看/已看标记必须从 WatchlistWatchedIds 缓存计算，不能依赖路由参数中的 inWatchlist/isWatched（这些只是初始值）

### 持久化缓存原则（重要）

**核心原则：基本不变的数据用持久化缓存，省去不必要的请求。**

以下数据视为"基本不变"，必须使用持久化缓存（跨 App 重启保留，TTL = 永久）：

- **TMDB 详情**（`movieDetailCache`/`tvDetailCache`）：海报路径（`poster_path`）、`tmdbId`、`imdbId`、标题、概述等字段不会变
- **演职员信息**（`movieCreditsCache`/`tvCreditsCache`）：演员头像、姓名、角色不会变
- **TMDB↔Trakt ID 映射**（`searchByTmdbCache`/`searchByImdbCache`）：映射关系静态永久
- **人物信息**（`personCache`）：演员/导演的基本信息、头像不会变

以下数据使用短期缓存（6 小时，App 进程内有效即可）：

- 豆瓣热榜（榜单排名会变）
- Trakt 趋势/最受期待/社区列表（热度会变）
- TMDB 热门/即将上映列表（随市场变化）
- 评论、统计数据（频繁更新）
- 想看/已看 ID 集合（6 小时 TTL，跨 App 重启复用，避免每次启动都发 4 个 /sync/* 请求；增删想看/已看时同步更新持久化缓存，退出登录时清除）

持久化缓存实现要求：

- 使用 DataStore 存储（key → JSON 字符串），启动时加载到内存 TtlCache
- 写入内存缓存时同步写入 DataStore（异步，不阻塞返回）
- 内存缓存作为一级缓存（秒进），DataStore 作为二级缓存（重启后恢复）
- 数据格式变更时通过 key 版本号（如 `_v2` 后缀）让旧缓存自动失效
- 缓存未命中时调用 `awaitLoaded()` 等待磁盘加载完成再查一次，避免 loadFromDisk 未完成时误判为缓存未命中导致重复网络请求

## 豆瓣爬取原则（重要）

**核心原则：尽量减少对豆瓣网站的爬取次数，避免触发反爬机制和 IP 封禁。**

措施（按优先级递减）：

1. **本地永久缓存**（一级 + 二级）：豆瓣详情（评分/简介/集数/演职员/ID 映射等）不会变，永久持久化缓存，跨 App 重启复用
2. **全局池共享**：用户 A 爬过的豆瓣条目详情，上传到全局池（`CloudDetailsPoolManager`），用户 B 直接从全局池拉取填充本地缓存，不再爬豆瓣
3. **fetchDetail 查询链路**：内存缓存 → 磁盘缓存（`awaitLoaded`）→ 全局池 → 爬豆瓣（爬取成功后异步上传全局池）
4. **反爬延迟**：爬取前 3-5 秒随机延迟，列表页 5-10 秒；失败重试不超过 1 次

全局池字段级合并规则：
- 上传完整条目时：非 null 字段覆盖现有值，null 字段保留现有值（避免覆盖其他用户已标注的字段）
- 上传单字段（如 mediaType）时：只覆盖该字段

## 国际化规范

- 所有用户可见文字必须使用 stringResource，不能硬编码
- strings.xml 同步添加：values/(英文)、values-zh/(中文)、values-ja/(日文)、values-ko/(韩文)
- 非 Composable 中用 context.getString(R.string.xxx)

## 编码风格

- 优先使用项目已有的约定和模式，修改前先了解现有架构
- 项目依赖统一用 gradle/libs.versions.toml 管理
- 当我需要库/API文档、代码生成、设置或配置步骤时，始终使用Context7 MCP，而无需我明确要求。

## 工作方式

- 重要修改和涉及功能性损失的，先说明计划，获得确认后再执行
- 修复 bug + 修改/增加功能 + 修改 UI 合计超过三个，构建验证后先本地提交一次
- 涉及大量原代码删除的改动，必须在改动前本地提交一次，方便回滚
- 多步任务在最后统一完成后构建 debug 验证一次即可
- 完成任务后简要总结改动内容
- 有不确定的地方主动用 brainstorming 技能问清楚（添加新功能时必须调用头脑风暴技能）
- 头脑风暴的可视化伴侣：涉及 UI 设计时，使用 PureShowWidget 内联展示设计效果（SVG/HTML）；前端新页面设计用 web-dev 技能生成实时预览（自动启动本地 HTTP 服务器并通过 OpenPreview 提供预览链接）
- 安装软件，程序后自动清理临时文件
- 增加/修改/删除功能后，及时更新 App 内帮助与说明页，确保帮助与说明与实际情况一致
- 发布流程参考 `.trae/rules/project_rules.md`

## Bug 修复工作流

1. **复现**：确认 bug 可复现，记录复现步骤
2. **定位**：从 UI 层反向追踪到数据层，找到根因
3. **修复**：最小改动，不引入新问题
4. **验证**：构建 debug 包验证修复效果
5. **总结经验**：有价值的教训写入本文件

## 常见陷阱与经验

- TtlCache 缓存命中但 UI 仍转圈：缓存查询必须在 viewModelScope.launch 之前，否则协程启动后才设 resolving 状态
- 回调签名与实际状态不同步：新增状态字段后必须同步更新所有回调签名和调用点，不能在中间层硬编码默认值
- 数据类缓存路径遗漏字段：所有缓存路径必须与网络请求路径字段一致（如 TvEnrichment 缓存漏 status 字段导致二次打开无状态信息）
- Gitee Release 上传 APK 必须用 curl.exe（PowerShell multipart 会 UTF-8 重编码导致 APK 损坏）
- Gitee Release body 不能含 markdown 格式符号（#/-），创建时用纯文本，创建后 PATCH 补回
- Gitee Release body PATCH 同步 markdown 更新日志时，PowerShell 的 Invoke-RestMethod 对 PATCH 方法在本环境会卡住（GET/POST 正常），改用 curl.exe --data-binary @file 从 UTF-8 无 BOM 的 JSON 文件读取 body 可成功；JSON 文件用 Write 工具直接生成避免 PowerShell 编码问题

## Git 规范

- commit 格式：`<type>: <描述>`
  - feat: 新功能 / fix: 修复 / refactor: 重构 / docs: 文档 / style: 格式 / test: 测试 / chore: 构建/工具

## 安全意识

- 不硬编码密码、密钥等敏感信息
- 配置文件中的敏感信息需提醒用户注意保护

## 想清楚再写

不要瞎猜，把权衡讲出来。假设要明说，不确定就问。多种理解都摆出来，别悄悄选一个。有更简单的做法直说，该反对时反对。
