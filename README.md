# TraktToSearch

从观影清单到网盘资源，一步之遥。

一款 Android 影视搜索与管理工具，基于 Trakt 观影清单，快速查找网盘资源。

## 核心功能

### 影视搜索与发现
- **多源资源搜索** — 支持盘搜、PanHub、ZReso 等内置搜索源，以及自定义搜索源
- **Trakt 集成** — 登录 Trakt 账号，同步想看/已看列表，支持 Trakt 搜索（电影/电视剧/人物）
- **发现页** — 热门趋势、即将上映、期待榜单、个性化推荐、豆瓣热门、Trakt 社区列表等栏目
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

- **语言**: Kotlin
- **UI**: Jetpack Compose + Material 3
- **架构**: MVVM + Hilt 依赖注入
- **网络**: Retrofit + OkHttp + Kotlin Serialization
- **图片**: Coil
- **存储**: DataStore + Room
- **导航**: Navigation Compose
- **特效**: Haze 毛玻璃效果、Lottie 动画
- **推送**: JPush

## 下载

前往 [GitHub Releases](https://github.com/yufeng-liang/TrackToSearch/releases) 下载最新版本 APK。

国内用户也可通过 Gitee 镜像下载：
- [Gitee Releases](https://gitee.com/yufeng-liang/TrackToSearch-release/releases)

## 开发

1. 克隆仓库
2. 在 `local.properties` 中配置 API 密钥：
   ```properties
   # Trakt API
   trakt.client.id=your_trakt_client_id
   trakt.client.secret=your_trakt_client_secret
   trakt.redirect.uri=tracktosearch://oauth/callback
   
   # TMDB API
   tmdb.api.key=your_tmdb_api_key
   
   # OMDb API（可选，用于评分信息）
   omdb.api.key=your_omdb_api_key
   
   # Gitee Access Token（用于应用内更新）
   gitee.access.token=your_gitee_access_token
   
   # GitHub Update Token（用于版本检查）
   github.update.token=your_github_token
   
   # JPush（推送通知）
   jpush.appkey=your_jpush_appkey
   ```
3. 使用 Android Studio 打开项目，同步 Gradle 后运行

## 项目结构

```
app/src/main/java/com/tracktosearch/
├── data/
│   ├── local/          # 本地存储（DataStore、Room）
│   ├── remote/         # 网络 API（Trakt、TMDB、OMDb、豆瓣等）
│   ├── repository/     # 数据仓库层
│   └── util/           # 工具类（缓存、加密等）
├── ui/
│   ├── component/      # 通用 UI 组件
│   ├── navigation/     # 导航路由
│   ├── screen/         # 各页面实现
│   │   ├── discover/   # 发现页
│   │   ├── search/     # 搜索页
│   │   ├── detail/     # 详情页
│   │   ├── watchlist/  # 想看/已看列表
│   │   ├── settings/   # 设置页
│   │   ├── statistics/ # 观影统计
│   │   └── ...
│   └── theme/          # 主题配置
└── di/                 # Hilt 依赖注入模块
```

## 许可证

MIT License
