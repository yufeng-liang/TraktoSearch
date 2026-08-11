# TraktoSearch

从观影清单到网盘资源，一步之遥。

一款 Android 影视搜索与管理工具。基于 Trakt 观影清单，也可独立使用豆瓣账号，快速查找网盘资源、管理想看/已看清单，并提供天气/节日动态主题等个性化体验。

- 包名：`com.tracktosearch`
- 当前版本：`3.6.0`（versionCode 64）
- 最低支持：Android 8.0（API 26）/ 目标与编译版本：API 37（compileSdk 37 / targetSdk 37）
- 多语言：中文（简）、英文、日文、韩文

## 核心功能

### 影视搜索与发现
- **多源资源搜索** — 内置判搜（pansou）、PanHub、Z-RESO 等搜索源，支持按网盘类型过滤与多线路展示；支持自定义搜索源（兼容 PanSou/ZReso 模板或自定义 JSONPath）
- **Trakt 集成** — 登录 Trakt 账号，同步想看/已看/评分，支持 Trakt 搜索（电影/电视剧/人物/资源四类）
- **发现页** — 豆瓣猜你喜欢、新片榜/口碑榜/Top250/正在热映，Trakt 热门电影/剧集、最受期待、为你推荐、社区热门列表等栏目，全部支持拖拽排序与显示/隐藏；顶部 Hero 大卡片快捷入口；导航底部 Tab：搜索 / 发现 / 我的 / 设置
- **影视搜索** — 在线搜索支持中文人名匹配（基于 TMDB），人物搜索自动填充相关影视作品
- **影视筛选** — 按类型、地区、标签、评分区间、年代多维度筛选电影/电视剧，支持排序与「仅展示未标看过」，电影与剧集筛选条件互相独立保留
- **影视详情** — 评分（IMDb/TMDB/OMDb/豆瓣）、简介、演职员、预告片与剧照、季集信息、资源/评论/推荐三个 Tab；支持双击放大海报
- **评论翻译** — 一键翻译 Trakt 评论与豆瓣短评，支持批量翻译（百度翻译：大模型 AI 优先，失败降级通用翻译）
- **人物详情** — 演职员详细档案与作品列表
- **影视库列表** — 浏览 Trakt 社区/热门影视库列表及其内容

### 观影管理
- **想看列表** — 管理想看的电影与电视剧，支持搜索与筛选
- **已看列表** — 记录已观看内容
- **观影标记记录** — 记录每一次标记行为（想看 / 已看 / 评分的变化历史），支持四种筛选（全部/想看/已看/取消标记）
- **观影统计** — 总时长、每日热力图、评分分布、短评词云（jieba 分词）、类型分布与常看类型排行
- **我的评分** — 标记观看后可打分（Trakt 0-10 分制 / 豆瓣 1-5 星），支持短评
- **数据导入** — 从 IMDb 导出的 CSV 导入想看列表、连接 Trakt 后从豆瓣一键导入标记

### 登录与账号
- **设备激活** — 通过 12 位激活码/邀请码激活设备，支持新设备激活与授权撤销后的迁移重新绑定（离线授权宽限期）
- **Trakt 登录** — OAuth 授权 Trakt 账号，同步想看 / 已看 / 评分与历史
- **豆瓣独立模式** — 不依赖 Trakt，用豆瓣账号登录并同步观影数据（想看 / 已看 / 历史）；支持失败项续传重试、完整重写可回滚、批量移除与豆瓣↔Trakt 状态一致性检查
- **游客模式** — 无需登录即可体验浏览与搜索（跨重启保留）

### 消息与反馈
- **消息中心** — 开发者对反馈的回复消息（未读角标提示，设置页入口）
- **反馈与建议** — 应用内提交反馈（Bug 报告 / 功能建议 / 其他），可附最多 5 张截图并预览、查看处理进度与开发者回复
- **崩溃日志** — 全局崩溃捕获并记录（最近操作上下文），可开关自动上报，失败时提供邮件兜底

### 个性化设置
- **主题定制** — 浅色 / 深色 / 跟随系统，Material You 强调色动态配色，语言选择（中文 / 英文 / 日文 / 韩文 / 跟随系统）
- **动态主题** — 授权定位后可随城市实时天气与节日切换主题（假日 Lottie 彩蛋、天气相关配色）
- **启动偏好** — 默认启动 Tab、共享元素转场开关
- **栏目管理** — 自定义发现页与详情页栏目的显示 / 隐藏和排序（拖拽）
- **搜索源配置** — 启用 / 禁用内置搜索源，添加 / 编辑 / 测试自定义搜索源（兼容 PanSou/ZReso 模板或自定义 JSONPath）
- **通知设置** — 上映提醒、新季提醒
- **数据管理** — 导出 JSON、从 IMDb 导入、豆瓣同步、观看状态一致性检查、缓存分级清理
- **桌面组件** — Glance 快捷入口（点击直达应用）

### 体验优化
- **缓存策略** — 多级缓存（想看/已看缓存 → ID 转换缓存 → 网络请求），尽量复用缓存、减少网络请求，秒进详情页
- **骨架屏** — 优雅的加载状态
- **快速回顶** — 长列表一键返回顶部
- **新手引导** — 首次使用引导
- **自动更新** — 检查新版本（GitHub 优先、Gitee 备用），支持应用内下载更新（SHA-256 校验）
- **启动优化** — Baseline Profile，系统启动画面
- **状态栏沉浸** — 边缘到边 (edge-to-edge)，毛玻璃吸顶导航

> 更完整的缓存原则、豆瓣爬取与授权同步经验见 [`AGENTS.md`](AGENTS.md)。

## 技术栈

- **语言**: Kotlin 2.4.0
- **构建**: Android Gradle Plugin 9.3.0 + Gradle Wrapper（KSP 2.3.9，JDK 17）
- **UI**: Jetpack Compose（Material 3，BOM 2026.06.01）+ Navigation Compose
- **架构**: MVVM + 仓库层 + Hilt 依赖注入
- **网络**: Retrofit + OkHttp + Kotlin Serialization
- **图片**: Coil
- **存储**: DataStore + Room（SQLCipher 加密）
- **后台**: WorkManager（定时同步 / 通知 / 授权巡检）
- **富文本**: compose-richtext（CommonMark）
- **桌面组件**: Glance（App Widget）
- **特效**: Haze 毛玻璃、Lottie 动画、Zoomable 缩放
- **其他**: jsoup（HTML 解析）、Security Crypto（密钥加密）、jieba（中文分词）、Baseline Profile

## 下载

前往公开的 [Gitee Releases](https://gitee.com/yufeng-liang/TraktoSearch-release/releases) 下载最新版本 APK。

GitHub 仓库为私有仓库，拥有访问权限的协作者也可从 [GitHub Releases](https://github.com/yufeng-liang/TraktoSearch/releases) 查看发布记录并下载 APK。

## 构建与开发

本章节面向**开发者**，介绍如何在本机拉取项目、配置密钥并构建运行。

### 环境要求

| 工具 | 版本要求 | 说明 |
| --- | --- | --- |
| Android Studio | 支持 AGP 9.3.0 与 Kotlin 2.4.0 的版本 | 建议使用较新稳定版 |
| JDK | 17 | `compileOptions` 与 Kotlin `jvmTarget` 均为 17 |
| Android SDK | compileSdk 37 / minSdk 26 | 需安装对应平台与 Build Tools |
| Gradle | 无需单独安装 | 直接使用仓库内置的 `gradlew` / `gradlew.bat` |
| 设备/模拟器 | Android 8.0+ | 真机调试需开启「USB 调试」 |

> ⚠️ `gradle.properties` 中可能带有作者本地的代理配置（`127.0.0.1:7890`）。**该配置仅适用于作者本机**，克隆后若你不在该代理环境下，请删除这几行或改为你自己的代理，否则会导致依赖下载失败。

### 1. 获取源码

```bash
# GitHub（主仓库）
git clone https://github.com/yufeng-liang/TraktoSearch.git
cd TraktoSearch

# 或 Gitee（国内镜像）
git clone https://gitee.com/yufeng-liang/TraktoSearch.git
```

### 2. 配置构建密钥

**注意：** 过去编译进 APK 的 Gitee/GitHub 更新令牌、百度翻译（Baidu）等密钥，目前已迁移到云端组件的 Secrets（`auth-worker` / `app-config`），**不再编译进 APK**。因此本地一般只需要几个构建必须的密钥。

项目通过 `local.properties`（位于仓库根目录，**已被 `.gitignore` 忽略，不会入库**）注入构建密钥。将占位符替换为你自己申请的真实密钥：

```properties
# Trakt API（登录 / 同步想看已看必需）
trakt.client.id=your_trakt_client_id
trakt.client.secret=your_trakt_client_secret
trakt.redirect.uri=tracktosearch://oauth/callback

# TMDB API（影视详情、发现页必需）
tmdb.api.key=your_tmdb_api_key

# 豆瓣 API（可选，豆瓣热门/详情增强）
douban.api.key=your_douban_api_key

# OMDb API（可选，用于评分信息）
omdb.api.key=your_omdb_api_key

# 云端配置服务（可选；不填则使用默认 baseUrl）
config.aes.key=your_config_aes_key
config.base.url=https://app-config-1qe.pages.dev/

# 授权网关根域名（可选；不填使用默认 Pages 代理）
gateway.base.url=https://tracktosearch-gateway.pages.dev/gateway-api

# Release 签名（仅构建正式包时需要）
release.store.file=../release.jks
release.store.password=your_store_password
release.key.alias=release
release.key.password=your_key_password
```

> 🔒 **安全提示**：`local.properties` 与 `release.jks` 均包含敏感信息，切勿提交到版本库。
> 密钥可迁移到 Cloudflare 组件（`auth-worker` / `app-config` / `feedback-worker`）的 Secrets 中运行时下发。未配置构建期密钥时，依赖该密钥的功能（如登录、资源搜索）在运行时可能不可用，但应用仍可编译。

### 3. 打开并同步项目

1. 用 Android Studio 打开项目根目录（即包含 `settings.gradle.kts` 的目录）。
2. 首次打开会触发 Gradle 同步，等待 `BUILD SUCCESSFUL` 完成。
3. 若同步失败，请优先检查：JDK 版本是否为 17、Android SDK 路径（`sdk.dir`，可在 `local.properties` 中设置）是否正确。

### 4. 运行与构建

**调试运行（连接设备/模拟器后）**

- Android Studio：选择 `app` 模块 → 点击 ▶ Run。
- 命令行（macOS / Linux）：
  ```bash
  ./gradlew installDebug
  ```
- 命令行（Windows）：
  ```bat
  gradlew.bat installDebug
  ```

**构建 Debug APK**

```bash
./gradlew assembleDebug        # 产物位于 app/build/outputs/apk/debug/
```

**构建 Release APK（需配置签名）**

```bash
./gradlew assembleRelease      # 产物位于 app/build/outputs/apk/release/
```

Release 构建会开启 R8 混淆与资源压缩（`isMinifyEnabled = true`、`isShrinkResources = true`），并应用 `proguard-rules.pro`。

### 运行测试

```bash
./gradlew testDebugUnitTest     # 单元测试（含 Robolectric 组件测试，约 1200+ 用例）
./gradlew connectedAndroidTest  # 设备端 Instrumented 测试（需连接设备/模拟器）
```

### 常见问题

- **`BUILD FAILED: Could not resolve`**：多为网络/代理问题，检查 `gradle.properties` 代理设置或切换网络。
- **KSP / Hilt 编译报错**：确认 Kotlin 2.4.0、Hilt 2.60.1、KSP 2.3.9 版本匹配，并执行 `./gradlew clean` 后重新构建。
- **Git worktree 分支无法构建**：`local.properties` 不在版本库，新 worktree 需从已有主工作区复制该文件。

## 使用示例

### 应用使用流程（终端用户）

1. **激活设备**：首次使用通过 12 位激活码激活；可继续用 Trakt 登录或选择游客模式。
2. **登录账号**：在「设置 → 账号」授权 Trakt，或选择豆瓣独立模式（用豆瓣账号同步观影数据）。
3. **发现影视**：在发现页浏览趋势、即将上映、豆瓣热门等栏目，或点击搜索进入 Trakt / 资源搜索。
4. **查找资源**：在影视详情页或搜索结果中点击「找资源」，应用会聚合多个网盘搜索源的结果，支持按网盘类型筛选与排序。
5. **管理清单**：在详情页一键加入想看 / 标记已看并评分；上映与新季会收到通知提醒。
6. **个性化**：在设置中切换深浅色主题，授权定位体验天气/节假日动态主题，管理发现页栏目顺序、启用/禁用搜索源、配置自定义搜索源。
7. **观影统计**：在统计页查看观影时长、类型、评分与标记记录。
8. **反馈**：在设置页进入反馈中心提交问题或建议，可附带截图与崩溃日志。

### 架构与代码约定（开发者）

项目采用 **MVVM + 仓库层 + Hilt** 的标准分层，对外通过仓库暴露领域模型，UI 只依赖仓库与 ViewModel。

**典型数据流（以想看列表为例）**

```kotlin
// 1) 网络层：Retrofit 接口（data/remote/trakt/...）
interface TraktApi {
    @GET("sync/watchlist/movies")
    suspend fun getWatchlistMovies(): List<TraktMovieDto>
}

// 2) 仓库层：聚合数据源，对外暴露领域模型（data/repository/...）
@Singleton
class TraktRepository @Inject constructor(
    private val traktApi: TraktApi,
    private val cache: TtlCache<Int, TraktMovie>,   // 全局内存缓存
) {
    suspend fun getWatchlistMovies(): List<TraktMovie> =
        cache.getOrAwait { traktApi.getWatchlistMovies().toDomain() }
}

// 3) ViewModel：通过 Hilt 注入仓库
@HiltViewModel
class WatchlistViewModel @Inject constructor(
    private val traktRepository: TraktRepository,
) : ViewModel() {
    fun load() = viewModelScope.launch { _uiState.value = traktRepository.getWatchlistMovies() }
}
```

**缓存使用约定**（详见 `AGENTS.md`）

- 一切缓存策略以尽量减少网络请求为第一目标；优先内存缓存，命中即同步返回，避免不必要的转圈 UI。
- 基本不变的数据（TMDB 详情、演职员、ID 映射、人物信息）使用持久化缓存（DataStore + TtlCache），跨进程重启保留；缓存 key 带版本号隔离旧格式。
- 频繁变化的数据（榜单、评论、想看/已看 ID 集合）使用短期 TTL 缓存。
- 图片沿用现有磁盘缓存目录与容量上限/LRU 清理边界，尽量跨重启复用。
- 豆瓣爬取尽量复用缓存与全局池，避免触发反爬。

## 项目结构

```
app/src/main/java/com/tracktosearch/
├── data/
│   ├── local/           # 本地存储（DataStore、Room）
│   ├── remote/          # 网络 API（trakt / tmdb / omdb / douban / pansou / zreso /
│   │                    #   cloud / config / feedback / translate / update / weather ...）
│   ├── repository/      # 数据仓库层（TraktRepository、TmdbRepository、ResourceRepository ...）
│   ├── notification/    # 通知与提醒
│   └── util/            # 工具类（TtlCache、PersistentTtlCache、加密等）
├── ui/
│   ├── component/       # 通用 UI 组件
│   ├── navigation/      # 导航路由（AppNavigation、SessionModeManager ...）
│   ├── screen/          # 各页面实现
│   │   ├── auth/        # 激活 / 登录
│   │   ├── main/        # 主界面（搜索/发现/我的/设置四 Tab）
│   │   ├── search/      # 搜索
│   │   ├── discover/    # 发现页 + 筛选（discoverfilter）
│   │   ├── detail/      # 详情页（含 resources/comments/recommendations）
│   │   ├── douban/      # 豆瓣独立模式（登录 / 详情 / 同步）
│   │   ├── watchlist/   # 想看 / 已看
│   │   ├── markrecord/  # 观影标记记录
│   │   ├── statistics/  # 观影统计（热力图/词云/分布）
│   │   ├── listdetail/  # 影视库列表
│   │   ├── traktsearch/ # Trakt 搜索（电影/剧集/人物/资源）
│   │   ├── person/      # 人物详情
│   │   ├── messages/    # 消息中心（开发者回复）
│   │   ├── feedback/    # 反馈与建议
│   │   ├── settings/    # 设置页
│   │   └── help/        # 帮助与说明
│   └── theme/           # 主题配置（含天气/节日动态主题）
├── di/                  # Hilt 依赖注入模块
├── widget/              # Glance 桌面组件（快捷入口）
├── service/             # 前台服务工作（豆瓣同步、批删、一致性校验等）
├── util/                # 全局工具
├── MainActivity.kt      # 入口 Activity
├── TraktSearchApp.kt    # Application
└── CrashHandler.kt      # 全局崩溃处理
```

## 仓库其他组件

该项目是一个多组件仓库，除 Android 客户端外还包含以下 Cloudflare 与前端组件：

| 目录 | 类型 | 职责 |
| --- | --- | --- |
| `app` | Android 客户端 | 主要的手机应用（本文档主体） |
| `auth-worker` | Cloudflare Worker | 设备激活 / 邀请码 / 迁移授权与令牌校验（D1 + KV） |
| `gateway-pages` | Cloudflare Pages Functions | API 网关，将客户端请求代理转发到 auth / feedback 等 Worker（含 OMDb 代理） |
| `feedback-worker` | Cloudflare Worker | 反馈与消息接口（D1 + R2 截图 + KV） |
| `app-config` | Cloudflare Pages | 云端配置服务 + 管理后台 + 加密配置下发（`/api`、`/admin-api`） |
| `douban-movie-api` | Cloudflare Worker | 豆瓣电影榜单/新片榜/Top250 等数据接口 |
| `tracktosearch-website` | 前端站点 | 产品宣传与落地页 |
| `tools` | 脚本 | 构建期辅助脚本（如开屏 branding 生成） |

部署与发布细节见各子目录的 `README.md` / `wrangler.toml`，以及本文档「贡献指南」、根目录 `AGENTS.md`。

## 许可证与素材边界

本项目自有原创代码采用 GNU GPLv3，完整文本见根目录 `LICENSE`。GPLv3 不自动覆盖第三方依赖、影视数据、海报、Logo、第三方服务返回内容、AI 生成角色素材或外部链接；发布 APK 或服务时还必须遵守各自的许可证、归属要求和服务条款。

- `THIRD-PARTY-NOTICES.md`：Android / Worker 依赖、外部服务和归属说明。
- `ASSET-LICENSES.md`：图标、启动页、Lottie 动画、截图、AI 生成角色和第三方标识清单。
- 官网的使用边界、TMDB 非背书声明、资源链接责任和公开法律/隐私请求入口位于官网的「使用与权利」章节。

## 贡献指南

欢迎 Issue 与 PR！提交前请阅读以下约定（更完整的工程规范见 `AGENTS.md`）。

### 分支与提交流程

- 主分支为 `master`，默认从 `master` 拉取特性分支（如 `feat/xxx`、`fix/xxx`）。
- 提交前请确保本地可编译、Debug 构建通过。
- 通过 PR 合入 `master`；涉及功能性损失的改动，先在 PR 描述中说明计划。

### 代码规范

- **注释语言**：代码注释使用**中文**；Git commit message 使用**中文**，遵循 Conventional Commits `type(scope): 描述` 格式。
- **国际化**：所有用户可见文字必须使用 `stringResource`，同步维护 `values/`（英文）、`values-zh/`、`values-ja/`、`values-ko/`。
- **依赖管理**：统一在 `gradle/libs.versions.toml` 中维护版本，禁止散落硬编码版本号。
- **外科手术式改动**：只动必须动的部分，跟随原有代码风格，不擅自「改进」无关代码。
- **API/库用法**：涉及第三方 API 或框架时，先查证官方用法再写。
- 涉及大量原代码删除的改动，先在改动前本地提交一次以便回滚；修复 + 功能 + UI 改动合计超过三处，构建验证后先本地提交一次。

### 如何新增一个搜索源

资源搜索由各源在 `ResourceRepository` 中聚合，新增源的步骤：

1. 在 `data/remote/<新源名>/` 下实现该源的请求与解析逻辑（建议统一映射为 `ResourceItem`）。
2. 在 `ResourceRepository` 中增加对该源的聚合与开关判断（参考现有实现）。
3. 通过 Hilt 注入所需 API，并在 `di/` 中补充对应的网络模块（如需要）。
4. 在设置页「搜索源配置」中为其提供启用/禁用开关。

### 提交信息格式

| type | 含义 |
| --- | --- |
| `feat` | 新功能 |
| `fix` | 修复 |
| `refactor` | 重构 |
| `docs` | 文档 |
| `style` | 格式/UI 调整 |
| `test` | 测试 |
| `chore` | 构建 / 工具链 |

示例：`feat(详情): 支持海报双击放大` / `fix(搜索): 深色模式下搜索框文字不可见`。

### 问题反馈

提交 Issue 时请尽量包含：操作步骤、预期 / 实际表现、设备型号与系统版本、应用版本号，以及（若可复现）崩溃日志（应用内「帮助与说明」可导出）。

## 鸣谢

本项目部分功能参考了以下开源项目：

- **[DoubanMovieListBackUpToNotion](https://github.com/Geetheshe/DoubanMovieListBackUpToNotion)** — 由 [Geetheshe](https://github.com/Geetheshe) 开源（将豆瓣电影标记记录爬取并备份至 Notion）。本应用的**豆瓣同步（爬取）功能**——标记列表（想看/已看/在看）的爬取方式与反爬思路——参考了该项目的实现思路。特此感谢开源作者的贡献。

## 相关文档

仓库 `docs/` 目录存放了更多工程资料，可作为深入参考，例如：

- `docs/trakt-api-reference.md` — Trakt API 速查
- `docs/code-review-*.md`、`docs/compose/`、`docs/superpowers/` — 设计稿与实现计划（plans / specs）

另外，根目录 `AGENTS.md`、`CONTEXT.md` 记录了工程规范、缓存原则、部署与踩坑经验。
