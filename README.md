# TraktToSearch

从观影清单到网盘资源，一步之遥。

一款 Android 影视搜索与管理工具，基于 Trakt 观影清单，快速查找网盘资源。

- 包名：`com.tracktosearch`
- 当前版本：`2.25.0`（versionCode 41）
- 最低支持：Android 8.0（API 26）/ 编译目标：Android 13（API 33，compileSdk 37）

## 核心功能

### 影视搜索与发现
- **多源资源搜索** — 支持盘搜、PanHub、ZReso 等内置搜索源，以及自定义搜索源
- **Trakt 集成** — 登录 Trakt 账号，同步想看/已看列表，支持 Trakt 搜索（电影/电视剧/人物）
- **发现页** — 热门趋势、即将上映、期待榜单、个性化推荐、豆瓣热门、Trakt 社区列表等栏目
- **影视筛选** — 按类型、地区、标签、评分区间、年代多维度筛选电影/电视剧，支持排序与"仅展示未标看过"，电影与电视剧筛选条件互相独立保留
- **影视详情** — 评分（IMDb/TMDB/OMDb）、简介、演职员、季集信息、评论、相关推荐
- **评论翻译** — 一键翻译 TMDB 评论，支持批量翻译
- **人物详情** — 查看演职员详细信息和作品列表

### 观影管理
- **想看列表** — 管理想看的电影和电视剧，支持搜索和筛选
- **已看列表** — 记录已观看的影视内容
- **观影统计** — 展示观影时长、类型分布、评分分布等数据
- **自动标记** — 从资源页进入详情时自动标记为已看

### 个性化设置
- **主题定制** — 支持浅色/深色/跟随系统，Material You 动态配色
- **栏目管理** — 自定义发现页和详情页栏目的显示/隐藏和排序
- **搜索源配置** — 启用/禁用内置搜索源，添加自定义搜索源
- **通知设置** — 想看列表更新提醒、新季提醒
- **多语言** — 支持中文、英文、日文、韩文

### 体验优化
- **缓存优化** — 三级缓存策略（想看/已看缓存 → ID 转换缓存 → 网络请求），秒进详情页
- **骨架屏** — 优雅的加载状态展示
- **快速回顶** — 长列表一键返回顶部
- **新手引导** — 首次使用引导
- **自动更新** — 检查新版本，支持应用内下载更新
- **崩溃日志** — 自动记录崩溃信息，便于问题反馈
- **帮助与说明** — 应用内帮助文档

## 技术栈

- **语言**: Kotlin 2.4.0
- **构建**: Android Gradle Plugin 9.2.1 + Gradle Wrapper（KSP 2.3.9）
- **UI**: Jetpack Compose（Material 3，BOM 2026.06.01）+ Navigation Compose
- **架构**: MVVM + Hilt 依赖注入（JDK 17）
- **网络**: Retrofit + OkHttp + Kotlin Serialization
- **图片**: Coil
- **存储**: DataStore + Room
- **后台**: WorkManager（定时同步/通知）
- **桌面组件**: Glance（App Widget）
- **特效**: Haze 毛玻璃效果、Lottie 动画
- **其他**: jsoup（HTML 解析）、Security Crypto（密钥加密）、Baseline Profile

## 下载

前往 [GitHub Releases](https://github.com/yufeng-liang/TrackToSearch/releases) 下载最新版本 APK。

国内用户也可通过 Gitee 镜像下载：
- [Gitee Releases](https://gitee.com/yufeng-liang/TrackToSearch-release/releases)

## 安装与开发

本章节面向**开发者**，介绍如何在本机拉起项目、配置密钥并构建运行。

### 环境要求

| 工具 | 版本要求 | 说明 |
| --- | --- | --- |
| Android Studio | Hedgehog（2023.1.1）及以上 | 需支持 AGP 9.2.1 与 Kotlin 2.4.0 |
| JDK | 17 | `compileOptions` 与 Kotlin `jvmTarget` 均为 17 |
| Android SDK | compileSdk 37 / minSdk 26 | 需安装对应平台与 Build Tools |
| Gradle | 无需单独安装 | 直接使用仓库内置的 `gradlew` / `gradlew.bat` |
| 设备/模拟器 | Android 8.0+ | 真机调试需开启「USB 调试」 |

> ⚠️ `gradle.properties` 中默认带有作者本地的代理配置（`127.0.0.1:7890`）。**该配置仅适用于作者本机**，克隆后若你不在该代理环境下，请删除这几行或改为你自己的代理，否则会导致依赖下载失败。

### 1. 获取源码

```bash
# GitHub（主仓库）
git clone https://github.com/yufeng-liang/TrackToSearch.git
cd TrackToSearch

# 或 Gitee（国内镜像）
git clone https://gitee.com/yufeng-liang/TrackToSearch.git
```

### 2. 配置 API 密钥

项目通过 `local.properties`（位于仓库根目录，**已被 `.gitignore` 忽略，不会入库**）注入密钥。
请将下面的占位符替换为你自己申请的真实密钥：

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

# 应用内更新 / 版本检查
gitee.access.token=your_gitee_access_token
github.update.token=your_github_token

# 百度翻译（评论翻译可选）
baidu.app.id=your_baidu_app_id
baidu.secret.key=your_baidu_secret_key
baidu.api.key=your_baidu_api_key

# 云端配置热更新（可选；不填则使用默认 baseUrl）
config.aes.key=your_config_aes_key
config.base.url=https://app-config-1qe.pages.dev/

# Release 签名（仅构建正式包时需要）
release.store.file=../release.jks
release.store.password=your_store_password
release.key.alias=release
release.key.password=your_key_password
```

> 🔒 **安全提示**：`local.properties` 与 `release.jks` 均包含敏感信息，切勿提交到版本库。
> 未配置密钥时，依赖该密钥的功能（如登录、资源搜索）将在运行时不可用，但应用仍可编译运行。

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

### 常见问题

- **`BUILD FAILED: Could not resolve`**：多为网络/代理问题，检查 `gradle.properties` 代理设置或切换网络。
- **KSP / Hilt 编译报错**：确认 Kotlin 2.4.0 与 Hilt 2.60、KSP 2.3.9 版本匹配，并执行 `./gradlew clean` 后重新构建。

## 使用示例

### 应用使用流程（终端用户）

1. **登录 Trakt**：在「设置 → 账号」中授权 Trakt，自动同步你的想看 / 已看列表。
2. **发现影视**：在发现页浏览趋势、即将上映、豆瓣热门等栏目，或点击搜索进入 Trakt / TMDB 搜索。
3. **查找资源**：在影视详情页或搜索结果中点击「找资源」，应用会聚合多个网盘搜索源的结果，支持按网盘类型筛选与排序。
4. **管理清单**：在详情页一键加入想看 / 标记已看；想看列表有更新时会收到通知提醒。
5. **个性化**：在设置中切换深浅色主题、管理发现页栏目顺序、启用/禁用搜索源、配置自定义搜索源。
6. **观影统计**：在统计页查看观影时长、类型与评分分布。

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

- 优先查询内存缓存，命中即同步返回，避免不必要的转圈 UI。
- 基本不变的数据（TMDB 详情、演职员、ID 映射、人物信息）使用 `PersistentTtlCache`（DataStore 持久化，跨进程重启保留）。
- 频繁变化的数据（榜单、评论、想看/已看 ID 集合）使用短期缓存（如 6 小时）。
- 缓存查询必须在 `viewModelScope.launch` **之前**同步完成，避免协程启动后才误设 loading 状态。

## 项目结构

```
app/src/main/java/com/tracktosearch/
├── data/
│   ├── local/          # 本地存储（DataStore、Room）
│   ├── remote/         # 网络 API（trakt / tmdb / omdb / douban / pansou / panhub / zreso / cloud / config ...）
│   ├── repository/     # 数据仓库层（TraktRepository、TmdbRepository、ResourceRepository ...）
│   ├── notification/   # 通知与提醒
│   └── util/           # 工具类（TtlCache、PersistentTtlCache、加密等）
├── ui/
│   ├── component/      # 通用 UI 组件
│   ├── navigation/     # 导航路由
│   ├── screen/         # 各页面实现
│   │   ├── discover/   # 发现页
│   │   ├── discoverfilter/ # 影视筛选页
│   │   ├── search/     # 搜索页
│   │   ├── detail/     # 详情页
│   │   ├── watchlist/  # 想看/已看列表
│   │   ├── settings/   # 设置页
│   │   ├── statistics/ # 观影统计
│   │   ├── person/     # 人物详情
│   │   └── ...
│   └── theme/          # 主题配置
├── di/                 # Hilt 依赖注入模块（NetworkModule / ConfigModule / DoubanModule ...）
├── widget/             # Glance 桌面组件
├── service/            # 前台服务（豆瓣同步等）
├── util/               # 全局工具
├── MainActivity.kt     # 入口 Activity
├── TraktSearchApp.kt   # Application
└── CrashHandler.kt     # 全局崩溃处理
```

## 贡献指南

欢迎 Issue 与 PR！提交前请阅读以下约定（更完整的工程规范见 `AGENTS.md`）。

### 分支与提交流程

- 主分支为 `master`，默认从 `master` 拉取特性分支（如 `feat/xxx`、`fix/xxx`）。
- 提交前请确保本地可编译、Debug 构建通过。
- 通过 PR 合入 `master`；涉及功能性损失的改动，先在 PR 描述中说明计划。

### 代码规范

- **注释语言**：代码注释使用**中文**；Git commit message 使用**中文**。
- **非 UI 文字国际化**：所有用户可见文字必须使用 `stringResource`，同步维护 `values/`（英文）、`values-zh/`（中文）、`values-ja/`（日文）、`values-ko/`（韩文）。
- **依赖管理**：统一在 `gradle/libs.versions.toml` 中维护版本，禁止散落硬编码版本号。
- **外科手术式改动**：只动必须动的部分，跟随原有代码风格，不擅自"改进"无关代码。
- **API/库用法**：涉及第三方 API 或框架时，先查证官方用法再写。
- 涉及大量原代码删除的改动，先在改动前本地提交一次以便回滚；修复 + 功能 + UI 改动合计超过三处，构建验证后先本地提交一次。

### 提交信息格式

遵循 `<type>: <描述>`：

| type | 含义 |
| --- | --- |
| `feat` | 新功能 |
| `fix` | 修复 |
| `refactor` | 重构 |
| `docs` | 文档 |
| `style` | 格式调整 |
| `test` | 测试 |
| `chore` | 构建 / 工具链 |

示例：`feat: 详情页支持海报双击放大` / `fix: 深色模式下搜索框文字不可见`。

### 如何新增一个搜索源

资源搜索由各源在 `ResourceRepository` 中聚合，新增源的步骤：

1. 在 `data/remote/<新源名>/` 下实现该源的请求与解析逻辑（建议统一映射为 `ResourceItem`）。
2. 在 `ResourceRepository` 中增加对该源的聚合与开关判断（参考现有 `searchZreso` / `searchPanSource`）。
3. 通过 Hilt 注入所需 API，并在 `di/` 中补充对应的网络模块（如需要）。
4. 在设置页「搜索源配置」中为其提供启用/禁用开关（复用既有 `CustomSearchSource` / 内置源开关逻辑）。

### 开发前自查

- [ ] 新功能已通过本地 Debug 构建验证
- [ ] 用户可见文字已走 `stringResource` 并补全多语言
- [ ] 新增依赖已在 `libs.versions.toml` 登记
- [ ] 不硬编码密钥 / 敏感信息（密钥走 `local.properties` 与 `BuildConfig`）
- [ ] 涉及 UI 改动时检查了深色模式可读性

### 问题反馈

提交 Issue 时请尽量包含：操作步骤、预期 / 实际表现、设备型号与系统版本、应用版本号，以及（若可复现）崩溃日志（应用内「帮助与说明」可导出）。

## 相关文档

仓库 `docs/` 目录存放了更多工程资料，可作为深入参考：

- `docs/trakt-api-reference.md` — Trakt API 速查
- `docs/code-review-2026-07-01.md` — 历史代码评审记录
- `docs/compose/`、`docs/superpowers/` — 设计稿与实现计划（plans / specs）

## 许可证

MIT License
