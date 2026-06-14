# TrackToSearch

从观影清单到网盘资源，一步之遥。

一款 Android 影视搜索工具，基于 Trakt 观影清单，快速查找网盘资源。

## 功能

- **Trakt 观影清单同步** — 登录 Trakt 账号，自动同步观影清单
- **网盘资源搜索** — 支持夸克、百度、阿里、迅雷、UC、115 等多种网盘类型
- **影视详情** — 展示评分（IMDb / Metacritic / TMDB / Rotten Tomatoes）、简介、演职员、评论等
- **评论翻译** — 一键翻译 TMDB 评论，支持批量翻译
- **内置浏览器** — 点击演职员卡片查看 TMDB 人物页
- **搜索历史** — 记录搜索历史，支持删除和清空
- **访客模式** — 无需登录即可搜索资源
- **暗黑模式** — 完整支持 Material You 暗黑主题
- **崩溃日志** — 自动记录崩溃信息，便于问题反馈

## 技术栈

- Kotlin + Jetpack Compose (Material 3)
- Hilt 依赖注入
- DataStore 数据持久化
- Coil 图片加载
- Retrofit + OkHttp 网络请求
- Kotlin Serialization
- Navigation Compose
- WebView 内置浏览器

## 下载

前往 [Releases](https://github.com/yufeng-liang/TrackToSearch/releases) 下载最新版本 APK。

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
   ```
3. 使用 Android Studio 打开项目，构建运行

## 许可

MIT License
