# TrackToSearch

从观影清单到网盘资源，一步之遥。

一款 Android 影视搜索工具，基于 Trakt 观影清单，快速查找网盘资源。

## 功能

### 核心功能
- **Trakt 观影清单同步** — 登录 Trakt 账号，自动同步观影清单（想看/已看）
- **网盘资源搜索** — 支持夸克、百度、阿里、迅雷、UC、115 等多种网盘类型
- **影视详情** — 展示评分（IMDb / Metacritic / TMDB / Rotten Tomatoes）、简介、演职员、评论等
- **评论翻译** — 一键翻译 TMDB 评论，支持批量翻译
- **内置浏览器** — 点击演职员卡片查看 TMDB 人物页
- **搜索历史** — 记录搜索历史，支持删除和清空
- **访客模式** — 无需登录即可搜索资源
- **暗黑模式** — 完整支持 Material You 暗黑主题
- **崩溃日志** — 自动记录崩溃信息，便于问题反馈

### 发现与探索
- **热门趋势** — 展示今日/本周热门影视，支持时间窗口切换
- **热门列表** — 浏览 Trakt 热门列表，支持点击进入列表详情页
- **期待榜单** — 展示最受期待的电影和剧集
- **推荐剧集** — 基于观影历史的个性化推荐
- **豆瓣热门** — 同步豆瓣热门影视信息

### 个人中心
- **观影统计** — 展示观影时长、类型分布、评分分布等统计数据
- **想看列表** — 管理想看的电影和剧集
- **已看列表** — 记录已观看的影视内容
- **Trakt 搜索** — 在 Trakt 平台搜索影视和人物

### 体验优化
- **开屏动画** — 弹性放大 + 光晕扩散效果，流畅的启动体验
- **跑马灯标题** — 标题过长时自动滚动，确保完整显示
- **骨架屏加载** — 优雅的加载状态展示
- **快速回顶** — 长列表支持一键返回顶部
- **新手引导** — 首次使用引导，帮助快速上手
- **应用更新** — 自动检查新版本，支持一键更新

## 技术栈

- Kotlin + Jetpack Compose (Material 3)
- Hilt 依赖注入
- DataStore 数据持久化
- Coil 图片加载
- Retrofit + OkHttp 网络请求
- Kotlin Serialization
- Navigation Compose
- WebView 内置浏览器
- Haze 毛玻璃效果
- JPush 推送通知

## 下载

前往 [Releases](https://github.com/yufeng-liang/TrackToSearch/releases) 下载最新版本 APK。

也可以通过 Gitee 下载（国内镜像）：
- [Gitee Releases](https://gitee.com/yufeng-liang/TrackToSearch-release/releases)

## 截图

<!-- TODO: 添加截图 -->

## 开发

1. 克隆仓库
2. 在 `local.properties` 中配置 API Key：
   ```properties
   TRAKT_CLIENT_ID=your_trakt_client_id
   TRAKT_CLIENT_SECRET=your_trakt_client_secret
   TMDB_API_KEY=your_tmdb_api_key
   OMDB_API_KEY=your_omdb_api_key
   GITEE_ACCESS_TOKEN=your_gitee_access_token
   ```
3. 使用 Android Studio 打开项目，构建运行

## 许可

MIT License
