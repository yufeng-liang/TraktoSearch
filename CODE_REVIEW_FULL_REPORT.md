# 全量代码审查报告

审查日期：2026-07-13
审查范围：`app/src/main/java/com/tracktosearch/` 全部 192 个 Kotlin 文件
审查角度：缓存层、Repository 层、ViewModel/UI 层、豆瓣爬虫+全局池、Trakt 认证+网络层

总计发现 **34 个 bug**（#8、#22、#34 经评估无需修复/为设计需要，已移除），按严重度分级如下。

---

## 🔴 严重（数据丢失 / 死循环 / 崩溃）

| # | 文件 | 行号 | 摘要 | 故障场景 |
|---|------|------|------|----------|
| 1 | TraktAuthenticator.kt | 30 | Token 刷新无最大重试上限，循环到 refresh 本身失败才停 | 服务端持续 401 且 refresh_token 仍有效 → 无限发送 /oauth/token，token 轮转耗尽才暴露 |
| 2 | TraktAuthenticator.kt | 42 | `runBlocking` 在 OkHttp dispatcher 线程做同步网络刷新 | 15s 超时 HTTP 请求直接卡 OkHttp 共享线程池；并发请求多时占满 dispatcher → UI ANR |
| 3 | ApiKeyProvider.kt | 84-97 | Trakt 单 client_id 一次 403 被 `markAndRotate` 标 INVALID 永久失效，之后 pickKey 仍返回该失效 key | 服务端临时策略 403 → client_id 标 INVALID → 所有 trakt-api-key 请求都用失效 key 先 403、再重试同一 key 又 403，双倍失败，等 RemoteConfig resetAll 才恢复 |
| 4 | DetailViewModel.kt | 1316 | 在搜索流 `conflate` 收集线程（Main）上直接调用 suspend IO 函数 `viewedItemStorage.getViewedUrls()` | 用户发起资源搜索 → 每次流发射同步 Room/磁盘读 → 主线程阻塞，列表滑动卡顿或 ANR |
| 5 | WatchlistViewModel.kt | 315 | 多个 async 协程并发执行 `while(size<=index) current.add(placeholder)` 修改同一 ArrayList | 想看列表 200 部 → `current.size` 被并发篡改 → 索引越界 / 占位重复 / item 错序 |
| 6 | TraktRepository.kt | 141-144 | `loadWatchlistWatchedIds` 网络失败时吞异常并返回空 `WatchlistWatchedIds()` | 首次想看/已看加载超时 → 返回空对象写入内存缓存 → `checkInWatchlist/batchCheckStatus` 全返 false → UI 把已想看/已看的条目显示为未标记，用户可重复添加 |
| 7 | DoubanTraktStatusConsistencyChecker.kt | 491,497 | `batchUpdateTrakt` 把 API 失败也计入 `traktUpdated` 成功数（`batchMarkAsWatched` 返回 `Result.failure` 不抛异常，`runCatching` 不会拦截） | Trakt 批量标记 401/500 → `traktUpdated += size` 仍执行 → UI 显示「更新 Trakt N 条」但实际全失败，用户误以为同步成功 |
| 9 | DoubanSpider.kt | 151-152 | IMDb ID 用 `nextSibling` 完整文本 `startsWith('tt')` 兜底，未正则提取 | 豆瓣节点文本 `tt39528392（主）` → `imdbId` 被解析为 `tt39528392（主）` → `searchByImdb` 用完整串查 Trakt 返回空 → 被错误记为 `TRAKT_NOT_FOUND` 不可恢复 |
| 10 | DoubanSyncManager.kt | 1292 | `markedAtToIso` 本地 12:00 转 UTC，UTC+13/+14 会跨日到前日（注释声称「正负12小时都不会跨日」错误） | UTC+13 用户标记「2026-07-13 看过」→ 解析为本地 12:00 → UTC 前日 23:00 → Trakt `watched_at` 聚合到 7/12 而非 7/13 |

## 🟠 高（内存泄漏 / 并发错误 / 数据不一致）

| 11 | ResourceRepository.kt | 99,118,126,168,235 | 非线程安全 LruCache 多协程并发 get/put，无同步保护 | 快速连续 `searchResources` / `searchResourcesFlow` 与 `searchResources` 并发 → LinkedHashMap 结构损坏 → ConcurrentModificationException 或 get 进入死循环 ANR |
| 12 | DetailViewModel.kt | 1713 | `markResourceViewed` 读-改-写非并发安全 | 用户连续快速点击 3 个资源链接 → 3 协程都读同一旧 `viewedUrls` 再分别写入 → 中间点击状态被覆盖，已点击高亮回退为未点击 |
| 13 | DetailViewModel.kt | 242 | 静态 LRU `detailCache` 持有完整 `UiState`（含 resources/recommendations/comments 等大列表），跨 ViewModel 存活 | 连续打开 4 个详情页再返回首屏 → 旧 VM 被 clear 但 static cache 仍持有前 3 个页面完整大状态 → 内存占用偏高，叠加 posterColor 等对象无法回收 |
| 14 | DetailViewModel.kt | 331 | 从静态缓存重新进入详情页时，`viewedUrls` 未与最新存储同步 | 从详情页 A 点看过资源后进 B 再返 A（命中 cacheGet）→ 恢复的 `uiState.viewedUrls` 仍是旧值 → 已看资源高亮短暂消失，直到下次 `updateSearchResults` 才刷新 |
| 15 | CloudDetailsPoolManager.kt | 62 | `shardLocks` 仅串行化 upload 路径，`downloadShard` 无锁，乐观锁 GET 与 PUT 窗口被并发 download 穿越 | `uploadDirtyDetails`（持分片锁 PUT）与 `refreshMediaTypesFromCloudPool` → `downloadShard` 无锁并发 → upload 的 sha 被交错 PUT 改变 → 409 → MAX_RETRY=3 耗尽后返回 false，该分片用户标注上传失败 |
| 16 | CloudDetailsPoolManager.kt | 365 | `uploadDetailEntry` 用 `==` 判等决定是否跳过，copy 后字段级精度差异 → == 恒假，多余 PUT | 池已存在完整条目再爬取同一 doubanId（字段值一致）→ `ratingDistribution` 顺序/精度微小差异 → == 假 → 无谓 PUT → 触发 sha 冲突重试循环 |
| 17 | BaseUrlInterceptor.kt | 34 | `String.replace(fallbackUrl, dynamicUrl)` 替换所有匹配子串 | 请求 URL 含 query `redirect=https://api.trakt.tv/...` → replace 把 query 里的 fallback 子串也替换成 dynamicUrl → 畸形 URL，请求打到错误地址 |
| 18 | ApiKeyInterceptor.kt | 43-47 | 403 重试时若所有 key 都已失效（`nextKey==currentKey`），用同 key 再发一次必然 403 | 单 key 失效 → 每次请求先 403 → `markAndRotate` → 再用同一 key 重试又 403，所有请求双倍流量且最终失败 |
| 19 | di/NetworkModule.kt | 536-557 | `RetryInterceptor` 用 `Thread.sleep` 在 OkHttp dispatcher 线程做退避，且重试复用原始 request 未重走拦截器链刷新已过期的 Authorization | 429 时 sleep 最长 10s 阻塞该线程上其他排队请求；多轮重试时多条并发请求退避叠加，用户感知明显卡顿 |
| 20 | TokenStorage.kt | 71-74 | `ensureCacheLoaded` 只要 `access_token` 非空就把 `isLoggedInState` 置 true，不校验 `expiresAt` | access_token 过期但 refresh_token 仍有效 → 启动后 `isLoggedIn=true` 显示已登录 → 发请求才 401 触发刷新；refresh 也失败则突然跳登，状态跳变无过渡 |

## 🟡 中（性能 / 功能缺陷）

| 21 | TraktRepository.kt | 1139 | `getUserRating` 调用 `getRatings(type)` 只取第一页（Trakt /ratings 默认每页 10 条，无分页参数） | 用户已评分超过 10 部电影 → 查第 11 部评分时 find 匹配不到 → 返回 null → UI 显示「未评分」而实际已评分，用户可重复提交 |
| 23 | DetailViewModel/ViewedItem | - | 静态缓存跨 VM 实例存活，viewedUrls 陈旧 | 返回旧页，高亮落后于最新点击 |
| 24 | WatchlistViewModel.kt | 337 | 离线缓存仅在 `moviePage==2` 时写入，非首页分页加载时缓存永不更新 | 用户首次加载首页后上滑触发 `loadMoreMovies` → `moviePage` 跳至 3 → 条件永不成立 → 离线缓存只有首页数据，断网刷新后首页丢失 |
| 25 | DetailViewModel.kt | 1337 | `updateSearchResults` 在主线程同步执行资源过滤，源/类型多时计算成本线性增长 | 启用 10+ 搜索源且关键词结果数千条 → 每次流发射同步 `filterItems` → 主线程短时阻塞，筛选标签切换掉帧 |
| 26 | DetailScreen.kt | 160 | `contentReady` 在 LaunchedEffect 中被重复设为 true，依赖 `posterDominantColor` 变化的淡入逻辑不可重入 | 主色由 null 变有值触发延迟淡入 → 若之后主色更新 → 再次进入 `delay(400)` 分支而非立即显示，内容不合理地再次淡入 |
| 27 | MovieCard.kt | 79 | `myClickToken` 用 `rememberSaveable` 保存，跨配置变化后可能与人详情页新鲜令牌不一致 | 横竖屏旋转后 `activeClickToken` 自增，MovieCard 内 `myClickToken` 被恢复为旧值 → 点击转场令牌不匹配 → 动画飘到错误的同 tmdbId 海报 |

## 🔵 低（代码质量 / 死代码 / 边界）

| 28 | data/util/TtlCache.kt | 40 | `get()` 对 ConcurrentHashMap 做 get-then-put 非原子 read-modify-write | 100 协程并发 get 同一热 key → 多数读到相同 entry 后各自 put 同 accessSeq 区间 → accessCounter 被消耗但 LRU 序未真实反映访问 → `trimToSize` 误把热 key 当冷 key 淘汰 |
| 29 | data/util/PersistentTtlCache.kt | 138 | `snapshotFromDisk` 未过滤过期条目 | 设备 A 的 trending 缓存已过期（6h 后）→ 仍读出上传 → 设备 B 下载合并后无此 key 写入过期数据 → B 展示过期榜单直到 TTL 再过期 |
| 30 | data/util/PersistentTtlCache.kt | 173 | `putAll` 的 `get(key)==null` 检查与 `super.put` 之间无原子保护 | 两协程并发 putAll 同 key → 都通过 get()==null 检查 → 各自 `super.put` + DataStore 写入两次 → written 返回 2 但实际只应写 1，DataStore 事务并发 edit 互相覆盖 |
| 31 | data/repository/TraktRepository.kt | 1006-1008, 1173-1175 | `batchSync` 与 `searchPaginated` 存在重复 `catch (e: CancellationException)` 块，第二个不可达 | 死代码，编译器可能告警；误导维护者以为 CancellationException 被处理两次；修改首个 catch 逻辑后第二个 catch 实际永不执行，取消语义被静默吞掉 |
| 32 | data/repository/TraktRepository.kt | 364-381 | `getCachedTraktId` 对「已查无有效 ID」返回非 null 的 0，三态（null/0/正数）违反 API 直觉 | 调用方写成 `getCachedTraktId(id)?.let { markAsWatched(it) }` → 负缓存命中返回 0 进入 let → 以 traktId=0 调后续接口，可能触发非法 ID 请求 |
| 33 | data/repository/CloudPersonalSyncManager.kt | 398-400 | `refreshMetaOnly` 在节流窗口内直接返回 true（声称成功），即使上一次实际刷新失败 | 设置页两个 LaunchedEffect 并发触发 → 首次 `doRefreshMetaOnly` 网络失败返 false → 5s 内第二次调用被节流返 true → 调用方认为云端 meta 已合并跳过重试 → 跨设备 `lastFullSyncAt` 实际未更新 |
| 35 | DetailViewModel.kt | 641 | `translateSingleComment` 复用已暴露的 `translatedComments` 可变副本，违反 `@Immutable` 约定 | 复制列表后添加条目再写回 → 若期间并发替换同一字段 → 两次更新互相覆盖，翻译结果丢失且 UI 无提示 |
| 36 | DoubanSyncManager.kt | 1157 | `runRetry` 的 `successCount` 来自 `result.success`（`withTraktId.size - writeFailedCount`），但 `allRetrySuccess = items - result.failed`，口径不同 | 阶段 4 部分失败时 writeFailedCount=withTraktId.size → success=0，但 `allRetrySuccess` 把「有 traktId 却写入失败」的项也算成功并从 failures 表删除 → 这些项实际没写进 Trakt 却永久移出重试队列 |
| 37 | data/util/TtlCache.kt | getOrAwait | fetch lambda 永久挂起时 inFlight 槽位永不释放 | 网络库 bug 导致 fetch 永远不抛异常不返回 → deferred 永远不 complete → finally 不执行 → `inFlightRequests` 中该 key 残留 → 后续所有该 key 的 getOrAwait 全部卡死，UI 无限转圈且无法取消 |

---

## 修复优先级与批次建议

**第一批（数据丢失 / 死循环，立即修）：** 1, 2, 9, 10, 11, 21
**第二批（并发错误 / 内存泄漏 / 网络层）：** 4, 5, 12, 13, 17, 19, 29
**第三批（性能 / 功能缺陷 / 日志）：** 其余

## 验证策略

- 每个 fix 独立编译（`./gradlew assembleDebug`）
- 全部修完后统一跑一次完整构建
- 逻辑复杂项（token 刷新上限、分片锁、LRU 竞态）补充单测
- 手动验证：登录登出切换、想看/已看同步、豆瓣搜索+Trakt 搜索轮替、旋转屏幕、深色模式切换
