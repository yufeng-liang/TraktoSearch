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

## 版本更新检查策略

- 启动时自动检查更新：24 小时内复用上次结果（`UpdateRepository.checkForUpdate(force=false)`），不重复请求 GitHub API
- 设置页手动检查更新：强制走网络（`UpdateRepository.checkForUpdate(force=true)`），绕过 24 小时缓存
- 缓存内容包括：最新版本号、changelog、是否有更新、下载链接，存储在 ChangelogStorage 的 DataStore 中

## 国际化规范

- 所有用户可见文字必须使用 stringResource，不能硬编码
- strings.xml 同步添加：values/(英文)、values-zh/(中文)、values-ja/(日文)、values-ko/(韩文)
- 非 Composable 中用 context.getString(R.string.xxx)

## 编码风格

- 优先使用项目已有的约定和模式，修改前先了解现有架构
- 项目依赖统一用 gradle/libs.versions.toml 管理
- 涉及 API、库/框架时，先用 Context7 查明用法，保证 API 使用正确

## 工作方式

- 重要修改和涉及功能性损失的，先说明计划，获得确认后再执行
- 修复 bug + 修改/增加功能 + 修改 UI 合计超过三个，构建验证后先本地提交一次
- 涉及大量原代码删除的改动，必须在改动前本地提交一次，方便回滚
- 多步任务在最后统一完成后构建 debug 验证一次即可
- 完成任务后简要总结改动内容
- 有不确定的地方主动用 brainstorming 技能问清楚（添加新功能时必须调用头脑风暴技能）
- 头脑风暴的可视化伴侣：涉及 UI 设计时，使用 PureShowWidget 内联展示设计效果（SVG/HTML）；前端新页面设计用 web-dev 技能生成实时预览（自动启动本地 HTTP 服务器并通过 OpenPreview 提供预览链接）
- 安装软件后自动清理临时文件
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
- 深色模式下 BasicTextField 文字不可见：textStyle 必须显式设置 color = MaterialTheme.colorScheme.onSurface
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

## 外科手术式改动

只动必须动的，只收拾自己制造的乱。不去"改进"没让你碰的内容，不翻新没坏的东西。跟着原有风格走。每一处改动都要能直接追溯到需求。
