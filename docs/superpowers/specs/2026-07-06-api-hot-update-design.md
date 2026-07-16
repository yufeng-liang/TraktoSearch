# API 热更新基建设计

- 日期: 2026-07-06
- 状态: 已批准,待实现
- 范围: API key/base URL 云端热更新基建(先做 key + URL,后续可扩展热更新更多数据)

## 1. 背景与目标

### 1.1 问题

当前所有 API key 与 base URL 通过 `local.properties` → `buildConfigField` 在编译期固化到 `BuildConfig`:

- `app/build.gradle.kts` line 32-44 注入 `TMDB_API_KEY` / `TRAKT_CLIENT_ID` / `TRAKT_CLIENT_SECRET` / `DOUBAN_API_KEY` / `OMDB_API_KEY` 等
- `di/NetworkModule.kt` 中 4 个服务的拦截器直接读 `BuildConfig.XXX` 注入请求头
- `Retrofit.Builder().baseUrl(...)` 也用硬编码字符串

APK 打包后这些值不可改。一旦某个 key 失效(TMDB 改政策、Trakt 限流、豆瓣 key 被封)或额度耗尽,只能重新打包发版。

### 1.2 目标

- **核心**: 把 API key 和 base URL 移到云端,App 启动时拉取,运行时动态注入
- **key 池轮换**: 多个 key 故障转移,429/401 自动切下一个,避免单 key 额度耗尽
- **降级兜底**: 任何环节失败(网络/解密/解析)都不影响 App 正常使用,回退到编译期 BuildConfig 值
- **基建扩展**: 先做 key + URL,后续可热更新更多数据(如默认 tab、功能开关等)

### 1.3 非目标

- 不做 App 自身代码热更新(仅配置数据)
- 不替换 OAuth 流程(Trakt OAuth 仍走现有 `TraktAuthenticator`)
- 不改 TMDB 图片 base URL(`image.tmdb.org` 不变,只热更新 `api.tmdb.org` 域名)

## 2. 整体架构(方案 A:三层分离)

```
┌──────────────────────────────────────────────────────┐
│  App 启动                                             │
│   ├─ RemoteConfigManager.initialize()                │
│   │    1. DataStore → 内存(毫秒级可用)              │
│   │    2. 若超 24h → 后台拉取云端加密配置             │
│   │    3. AES-256-GCM 解密 → JSON 解析 → 更新内存    │
│   │    4. 失败则继续用缓存/BuildConfig 兜底          │
│   └─ ApiKeyProvider + BaseUrlInterceptor 就绪         │
├──────────────────────────────────────────────────────┤
│  网络请求                                             │
│   Request → BaseUrlInterceptor(重写 URL)            │
│          → ApiKeyInterceptor(注入 key + 失败轮换)    │
│          → RetryInterceptor → 服务器                  │
│          ↑ 429/401 重试时 ApiKeyInterceptor 切 key    │
└──────────────────────────────────────────────────────┘
```

**三层职责分离**:

| 层 | 职责 | 不负责 |
|----|------|--------|
| RemoteConfigManager | 拉取 + 解密 + 解析 + 持久化 + 提供 `get(key, default)` 同步查询 | 不知道哪个 key 给哪个 API 用 |
| ApiKeyProvider | 管理 key 池状态(ACTIVE/COOLING/INVALID),提供 `pickKey()` 和 `markAndRotate(failedKey, code)` | 不发网络请求,不持久化 |
| BaseUrlInterceptor + ApiKeyInterceptor | OkHttp 拦截器,运行时注入 URL 和 key,失败时切 key 重试 | 不做配置管理 |

## 3. 云端配置服务(CF Pages)

### 3.1 托管位置

新建独立 CF Pages 项目 `app-config`(域名 `app-config.pages.dev`),与现有 `douban-movie-api` 项目隔离:

- 配置服务不依赖 douban-movie-api 的 Worker 逻辑
- 独立部署/更新/回滚,互不影响
- CF Pages 静态托管,免 Worker 配额

### 3.2 配置文件加密

- 明文 JSON → AES-256-GCM 加密 → 部署为 `public/config.json.enc`
- 加密 key 通过 CF Pages 环境变量注入(`wrangler secret put CONFIG_AES_KEY`),不进 git
- **客户端内置同一个解密 key**(`BuildConfig.CONFIG_AES_KEY`),用于解密下载到的密文
- 加密 key 不在网络上明文传输(密文走 HTTPS,解密在客户端本地)

> 备注:解密 key 嵌入 APK 理论上可被反编译提取。这是 YAGNI 范围内的可接受风险——目标是防中间人篡改配置 + 防爬虫直连明文,不是对抗逆向工程。若未来需要更强保护,可升级为服务端动态签发 key 的方案。

### 3.3 JSON Schema(版本 1)

```json
{
  "version": 1,
  "updatedAt": "2026-07-06T12:00:00Z",
  "values": {
    "tmdb.baseUrl": "https://api.tmdb.org/3/",
    "tmdb.imageBaseUrl": "https://image.tmdb.org/t/p/",
    "tmdb.apiKeys": ["key1", "key2", "key3"],
    "trakt.baseUrl": "https://api.trakt.tv/",
    "trakt.clientId": "xxx",
    "trakt.clientSecret": "xxx",
    "trakt.redirectUri": "urn:ietf:wg:oauth:2.0:oob",
    "douban.baseUrl": "https://douban-movie-api.pages.dev/",
    "douban.apiKey": "xxx",
    "omdb.baseUrl": "https://www.omdbapi.com/",
    "omdb.apiKey": "xxx"
  }
}
```

- `version`: schema 版本号。App 只读自己支持的版本(当前 = 1),未来版本字段可向前兼容添加,但若 major 版本号变化则老 App 忽略整份配置
- `updatedAt`: ISO-8601,用于客户端判断是否需要拉取新版本(配合 24h 缓存)
- `values`: 通用 key-value map,点号命名空间(`service.field`),便于扩展

### 3.4 部署流程

1. 本地明文 JSON → AES-256-GCM 加密脚本(可用 Node.js `crypto` 或 `openssl`)→ `config.json.enc`
2. 推送到 `app-config` 仓库 → CF Pages 自动部署
3. App 通过 `GET https://app-config.pages.dev/config.json.enc` 拉取密文

## 4. RemoteConfigManager + 持久化

### 4.1 职责

- 同步提供配置查询:`get(key, default): String` / `getKeys(prefix): List<String>` / `getInt` / `getBoolean`
- 异步初始化:DataStore 加载 → 内存就绪 → 后台拉取(可选)
- 24h 缓存策略(参考 `UpdateRepository.checkForUpdate`)

### 4.2 持久化结构

- **内存一级**:`TtlCache<String, String>`(TTL=24h,maxSize=64),启动时从 DataStore 加载
- **DataStore 二级**:`RemoteConfigStorage`,key 形如 `remote_config.tmdb.apiKeys`(序列化为 JSON 字符串)
- key 版本号后缀(`_v1`)用于数据格式变更时让旧缓存自动失效

### 4.3 初始化时序

1. App 冷启动 → `RemoteConfigManager.initialize()` 在 IO 协程异步执行(在 `MainActivity.onCreate` 或 `Application.onCreate` 触发,**不阻塞 UI 线程**)
2. 先从 DataStore 加载到内存 TtlCache → 立即可用(毫秒级)
3. 若内存缓存为空或已超 24h → 后台拉取云端 `config.json.enc`
4. 拉取成功 → AES-256-GCM 解密 → JSON 解析 → 校验 `version==1` → 更新内存 + DataStore
5. 拉取/解密/解析失败 → 继续用缓存;首次安装无缓存 → 用 BuildConfig 兜底

### 4.4 配置查询 API

```kotlin
// 同步查询(已初始化后必返回值,无缓存返回 default)
fun get(key: String, default: String = ""): String
fun getOrNull(key: String): String?
fun getInt(key: String, default: Int): Int
fun getBoolean(key: String, default: Boolean): Boolean
fun getStringList(key: String): List<String>  // 用于 tmdb.apiKeys 等数组字段

// 强制刷新(设置页"检查配置更新"按钮可用)
suspend fun refresh(): Result<Unit>
```

## 5. ApiKeyProvider + key 轮换

### 5.1 数据结构

```kotlin
enum class KeyStatus { ACTIVE, COOLING, INVALID }

data class KeyState(
    val key: String,
    val status: KeyStatus,
    val cooldownUntilMs: Long  // 仅 COOLING 状态有效
)

class ApiKeyProvider @Inject constructor(
    private val remoteConfig: RemoteConfigManager,
    private val configKey: String,          // 如 "tmdb.apiKeys"
    private val fallbackKey: String         // 如 BuildConfig.TMDB_API_KEY
) {
    private val states = AtomicReference<List<KeyState>>(emptyList())

    fun pickKey(): String
    fun markAndRotate(failedKey: String, httpCode: Int)
    fun resetAll()  // 配置刷新后重置状态
}
```

### 5.2 轮换规则

| HTTP 状态 | 含义 | 处理 |
|-----------|------|------|
| 429 | 限流 | 当前 key 冷却 5 分钟,切下一个 key **立即重试** |
| 401 | key 失效 | 当前 key 标记 INVALID(不冷却,直到下次配置刷新才恢复),切下一个重试 |
| 403 | 禁止 | 同 401 |

`pickKey()` 算法:
1. 返回第一个 `ACTIVE` key
2. 若无 ACTIVE,返回第一个 `COOLING` 且已过冷却期的(转回 ACTIVE)
3. 若全失效,返回第一个 key(**宁可重试也不要无 key**,可能服务端临时抽风)
4. key 池为空(云端没下发 key)→ 返回 fallbackKey(BuildConfig 兜底)

`markAndRotate()`:
- 429 → 标记 `COOLING`,cooldownUntilMs = now + 5min
- 401/403 → 标记 `INVALID`
- 其他错误码 → 不切 key(可能是网络问题,与 key 无关)

`resetAll()`:RemoteConfigManager 刷新配置成功后调用,从新配置重建 key 池(所有 key 重置为 ACTIVE)。

## 6. 拦截器改造

### 6.1 当前结构(改造前)

`NetworkModule.kt` 中 4 个独立 OkHttpClient,每个内联一个匿名 Interceptor 直接读 `BuildConfig.XXX`。

### 6.2 改造后

新增两个通用拦截器(可复用到所有服务):

```kotlin
class BaseUrlInterceptor @Inject constructor(
    private val remoteConfig: RemoteConfigManager,
    private val configKey: String,      // 如 "tmdb.baseUrl"
    private val fallbackUrl: String     // 如 "https://api.tmdb.org/3/"
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val dynamicUrl = remoteConfig.getOrNull(configKey) ?: fallbackUrl
        val original = chain.request()
        // 仅当 dynamicUrl 与原 URL host 不同时才重写
        if (original.url.toString().startsWith(fallbackUrl)) {
            val newUrl = original.url.toString().replace(fallbackUrl, dynamicUrl)
            return chain.proceed(original.newBuilder().url(newUrl).build())
        }
        return chain.proceed(original)
    }
}

class ApiKeyInterceptor @Inject constructor(
    private val apiKeyProvider: ApiKeyProvider,
    private val headerName: String,     // 如 "Authorization" 或 "trakt-api-key"
    private val headerValueTemplate: (String) -> String  // 如 { key -> "Bearer $key" }
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        var currentKey = apiKeyProvider.pickKey()
        var retryCount = 0
        val maxRetries = 3
        while (retryCount <= maxRetries) {
            val request = chain.request().newBuilder()
                .header(headerName, headerValueTemplate(currentKey))
                .build()
            val response = chain.proceed(request)
            if (response.code == 429 || response.code == 401 || response.code == 403) {
                response.close()
                apiKeyProvider.markAndRotate(currentKey, response.code)
                val nextKey = apiKeyProvider.pickKey()
                if (nextKey == currentKey) {
                    // 没有其他 key 可换,直接返回失败
                    return response
                }
                currentKey = nextKey
                retryCount++
            } else {
                return response
            }
        }
        // 重试上限,最后一次请求的结果(此处简化为抛异常,实际由 RetryInterceptor 处理)
        return chain.proceed(chain.request().newBuilder()
            .header(headerName, headerValueTemplate(currentKey))
            .build())
    }
}
```

### 6.3 NetworkModule 改造

每个服务保留独立 `@Named` OkHttpClient,但拦截器替换为 `BaseUrlInterceptor + ApiKeyInterceptor`:

- TMDB:`Bearer ${key}` 头,4 个 key 池
- Trakt:`trakt-api-key` 头,1 个 client_id(无池,但走动态配置)+ OAuth Bearer 仍由 `TraktAuthenticator` 处理
- 豆瓣:`X-API-Key` 头,1 个 key
- OMDB:`?apikey=` query 参数(此服务用 query 而非 header,需特殊处理 — 可用 `ApiKeyInterceptor` 的 query 变体或保留小拦截器)

### 6.4 故障转移触发点

`ApiKeyInterceptor` 放在 `RetryInterceptor` **之前**,这样 RetryInterceptor 重试时 ApiKeyInterceptor 能感知到上一次的失败状态码并切 key:

```
Request → ApiKeyInterceptor(注入 key A) → RetryInterceptor → 服务器
                                    ↑ 429/401 重试时,ApiKeyInterceptor 检测到失败切 key B
```

## 7. 错误处理与降级策略

### 7.1 RemoteConfigManager 降级链路

```
拉取配置
 ├─ 网络成功 + 解密成功 + JSON 合法
 │   ├─ schema.version == 1(当前支持) → 写内存 + DataStore,生效
 │   └─ schema.version > 1(未来版本) → 忽略,继续用本地缓存
 ├─ 网络失败 → 用 DataStore 缓存(即使已过期,有总比没有强)
 ├─ 解密失败(密钥不匹配/数据损坏) → 用 DataStore 缓存;若无缓存 → 回退 BuildConfig
 └─ JSON 解析失败 → 同上
```

### 7.2 ApiKeyProvider 兜底

- key 池为空(云端没下发 `tmdb.apiKeys`)→ 返回 `BuildConfig.TMDB_API_KEY` 兜底
- 所有 key 都失效 → 返回第一个 key(宁可重试也不要无 key)
- 单个请求最多轮换 3 次(避免死循环)

### 7.3 BaseUrlInterceptor

- 不自动回退 URL(无法探测可达性)
- RemoteConfigManager 未初始化完成时,用内存缓存;无缓存 → 不重写(用 Retrofit 编译期 baseUrl)

### 7.4 关键决策点

| 决策 | 值 | 理由 |
|------|-----|------|
| 429 冷却时间 | 5 分钟 | TMDB 限流通常 10 秒~1 分钟恢复,5 分钟保守 |
| 401 处理 | 标记 INVALID 直到下次配置刷新 | key 失效不会自动恢复,等云端更新 |
| 全失效兜底 | 返回第一个 key | 避免无 key 可用导致请求 0% 成功 |
| 单请求最大重试 | 3 次 | 防死循环,3 次足够覆盖 3-key 池 |
| 配置缓存 TTL | 24 小时 | 与 `UpdateRepository` 一致,平衡时效性和请求频率 |
| 初始化阻塞 | 否(IO 协程异步) | 不阻塞 UI,用 BuildConfig 兜底首请求 |

## 8. 测试策略

### 8.1 单元测试

| 组件 | 测试点 |
|------|--------|
| `RemoteConfigManager` | 解密成功/失败、JSON 解析失败、缓存命中、24h 过期触发拉取、schema 版本不匹配忽略 |
| `ApiKeyProvider` | key 池为空返回 fallback、429 标记 COOLING、401 标记 INVALID、COOLING 过期转回 ACTIVE、全失效返回第一个 key |
| `BaseUrlInterceptor` | URL 重写正确、host 不匹配时不重写、缓存为空时用 fallback |
| `ApiKeyInterceptor` | 429 触发切 key、401 触发切 key、200 直接返回、重试上限 3 次 |

### 8.2 集成测试

- 在 `NetworkModule` 中注入 `MockRemoteConfigManager`(返回测试配置),验证 TmdbApiService/TraktApiService 端到端能拿到动态 key 和 URL
- 模拟 429 响应,验证 ApiKeyInterceptor 切 key 后请求成功

### 8.3 手动验证

- 修改云端 `config.json.enc`(改一个 key 或 baseUrl)→ App 重启 → 验证新配置生效
- 故意把云端某个 key 改错 → 触发 401 → 验证自动切下一个 key
- 关闭网络启动 App → 验证用 DataStore 缓存的配置正常工作
- 首次安装(无缓存)启动 App → 验证用 BuildConfig 兜底

## 9. 文件清单

### 9.1 新建文件

**Android 端**(`app/src/main/java/com/tracktosearch/data/remote/config/`):

- `RemoteConfig.kt` — 接口 + 数据类(`RemoteConfig`, `ConfigValues`)
- `RemoteConfigManager.kt` — 拉取 + 缓存 + 解密 + 持久化的实现
- `RemoteConfigStorage.kt` — DataStore 持久化
- `ConfigApiService.kt` — Retrofit 接口(`GET config.json.enc`)
- `ApiKeyProvider.kt` — key 池 + 轮换
- `ApiKeyInterceptor.kt` — 统一 key 注入 + 失败轮换
- `BaseUrlInterceptor.kt` — 通用 base URL 重写拦截器

**DI 模块**(`app/src/main/java/com/tracktosearch/di/`):

- `ConfigModule.kt` — Hilt 模块,提供 `RemoteConfigManager` / `ApiKeyProvider`(每个服务一个实例,通过 `@Named` 区分)

**CF Pages 项目**(`app-config/`):

- `public/config.json.enc` — AES-256-GCM 加密的配置文件
- `scripts/encrypt-config.js` — 本地加密脚本(读取明文 JSON + AES key → 输出密文)
- `README.md` — 部署说明

### 9.2 改造文件

- `app/build.gradle.kts` — 新增 `CONFIG_AES_KEY` 和 `CONFIG_BASE_URL` buildConfigField(从 `local.properties` 读)
- `app/src/main/java/com/tracktosearch/di/NetworkModule.kt` — 4 个服务的拦截器改造为 `BaseUrlInterceptor + ApiKeyInterceptor`
- `app/src/main/java/com/tracktosearch/TrackToSearchApp.kt`(或 `MainActivity.kt`) — 在 `onCreate` 中触发 `RemoteConfigManager.initialize()`

### 9.3 测试文件

- `app/src/test/java/com/tracktosearch/data/remote/config/RemoteConfigManagerTest.kt`
- `app/src/test/java/com/tracktosearch/data/remote/config/ApiKeyProviderTest.kt`
- `app/src/test/java/com/tracktosearch/data/remote/config/ApiKeyInterceptorTest.kt`
- `app/src/test/java/com/tracktosearch/data/remote/config/BaseUrlInterceptorTest.kt`

## 10. 实施顺序

按依赖关系分阶段:

### 阶段 1:云端配置服务(可独立验证)
1. 创建 `app-config/` CF Pages 项目
2. 编写 `scripts/encrypt-config.js` 加密脚本
3. 生成 `config.json.enc` 并部署
4. 验证 `curl https://app-config.pages.dev/config.json.enc` 能拉到密文

### 阶段 2:Android 端配置层
5. `build.gradle.kts` 新增 `CONFIG_AES_KEY` / `CONFIG_BASE_URL` buildConfigField
6. 实现 `RemoteConfig.kt`(接口 + 数据类)
7. 实现 `ConfigApiService.kt`(Retrofit 接口)
8. 实现 `RemoteConfigStorage.kt`(DataStore)
9. 实现 `RemoteConfigManager.kt`(拉取 + 解密 + 持久化)
10. 实现 `ConfigModule.kt`(Hilt)
11. 在 `Application.onCreate` 触发 `initialize()`
12. 单元测试 RemoteConfigManager

### 阶段 3:拦截器层 + key 轮换
13. 实现 `ApiKeyProvider.kt`
14. 实现 `ApiKeyInterceptor.kt`
15. 实现 `BaseUrlInterceptor.kt`
16. 改造 `NetworkModule.kt` 中 TMDB 拦截器(最复杂,4-key 池,先做这个验证)
17. 改造 Trakt / 豆瓣 / OMDB 拦截器
18. 单元测试 ApiKeyProvider / ApiKeyInterceptor / BaseUrlInterceptor

### 阶段 4:端到端验证
19. 集成测试(MockRemoteConfigManager)
20. 手动验证(改云端配置 → 重启 → 验证生效)
21. 故障转移验证(故意改错 key → 验证轮换)

## 11. 风险与权衡

| 风险 | 影响 | 缓解 |
|------|------|------|
| 解密 key 嵌入 APK 可被反编译 | 攻击者可解密云端配置,拿到所有 API key | YAGNI 范围内可接受;未来可升级为服务端动态签发 |
| CF Pages 被墙 | App 无法拉取新配置 | 用 DataStore 缓存兜底;CF Pages 国内可用性较好 |
| 配置拉取失败 | App 用旧配置或 BuildConfig | 降级链路已设计,不影响核心功能 |
| key 轮换死循环 | 请求卡住 | 单请求最多 3 次重试 |
| 配置 schema 变更 | 老 App 读不懂新配置 | version 字段忽略不兼容版本 |
