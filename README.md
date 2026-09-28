# TraktoSearch

从观影清单到网盘资源，一步之遥。

一款 Android 影视搜索与管理工具：基于 Trakt 观影清单，也可独立使用豆瓣账号，快速查找网盘资源、管理想看/已看清单。

`Android 8.0+ · Kotlin · Jetpack Compose · Material 3 · v4.1.0 · 中/英/日/韩`

## ✨ 特色功能

### 🔍 多源网盘资源搜索

内置 pansou、PanHub、Z-RESO 三大搜索源并发聚合，支持按网盘类型（夸克/百度网盘/阿里云盘/迅雷等）过滤，一键跳转打开。更提供**自定义搜索源引擎**：三步向导 + AutoProbe 自动探测接口结构（简化 JSONPath 解析），任何兼容接口都能接入，还支持分享导入。

### 🔄 Trakt + 豆瓣双生态同步

Trakt OAuth 登录同步想看/已看/评分；豆瓣独立模式无需 Trakt，用豆瓣账号直接同步观影数据——支持失败续传、完整重写可回滚、状态一致性检查。还可从 IMDb 导出的 CSV 一键导入想看列表。

### 🎬 发现与深度筛选

豆瓣猜你喜欢、新片榜/口碑榜/Top250，Trakt 热门/最受期待/社区列表等栏目全部支持拖拽排序；按类型、地区、标签、评分区间、年代多维筛选。详情页聚合 IMDb/TMDB/OMDb/豆瓣四源评分、演职员、预告片剧照、季集信息，评论支持一键批量翻译（AI 大模型优先）。

### 📊 观影统计与记录

GitHub 式观影热力图、总时长、评分分布、类型饼图、常看 Top 榜，以及基于 jieba 中文分词的短评词云。每一次想看/已看/评分标记都有历史记录可查。

### 🎨 液态玻璃视觉与动态主题

基于 [AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass)（backdrop 库）打造的全套液态玻璃组件：毛玻璃吸顶导航、可拖拽玻璃底栏水滴、自适应亮度玻璃表面，配合 Mesh Gradient 背景光晕（星云/水墨/极光/熔岩灯等预设）。14+ 种命名主题色，授权定位后随实时天气（晴雨雪雷电雾夜）与节日（圣诞/春节/中秋等 15 种）自动切换动态主题，附彩蛋动画。

## 🛠 技术栈

- **UI**: Jetpack Compose（Material 3）+ Haze / backdrop 液态玻璃 + Lottie + Mirage AGSL 着色器背景光晕
- **架构**: MVVM + 仓库层 + Hilt 依赖注入
- **网络**: Retrofit + OkHttp + Kotlin Serialization
- **存储**: DataStore + Room（SQLCipher 加密）
- **后台**: WorkManager（定时同步/通知/授权巡检）+ 前台服务（豆瓣同步/一致性检查/批量移除）
- **其他**: Coil、Glance 桌面小组件、jieba 分词、Baseline Profile 启动优化、R8 混淆

## 📦 项目结构

```
app/src/main/java/com/tracktosearch/
├── data/
│   ├── local/           # 本地存储（DataStore、Room）
│   ├── remote/          # 网络 API（trakt/tmdb/omdb/douban/pansou/panhub/zreso/custom/weather/...）
│   ├── repository/      # 数据仓库层（搜索源聚合、缓存策略）
│   └── util/            # TtlCache 多级缓存等工具
├── ui/
│   ├── component/       # 液态玻璃组件体系（GlassSurface/BackdropHost 等）
│   ├── navigation/      # 导航路由
│   ├── screen/          # 各页面（main/discover/detail/douban/watchlist/statistics/ai/settings/...）
│   └── theme/           # 主题（命名主题色、天气/节日动态主题、背景光效）
├── di/                  # Hilt 模块
├── widget/              # Glance 快速搜索桌面小组件
├── service/             # 前台服务（豆瓣同步/一致性检查/批删）
└── util/                # 全局工具
```

> 除 Android 客户端外，仓库还包含 Cloudflare Workers/Pages 组件（设备激活网关、反馈服务、云端配置、豆瓣榜单 API 等），详见各子目录 `README.md`。

## ⬇️ 下载

从官网 [tracktosearch.pages.dev](https://tracktosearch.pages.dev/#section-download) 下载最新 APK（直链，国内可达），或从 [GitHub Releases](https://github.com/yufeng-liang/TraktoSearch/releases) 获取。

## 🙏 鸣谢

- **[AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass)** — 由 [Kyant0](https://github.com/Kyant0) 开发。本应用的液态玻璃视觉效果基于其 backdrop 库（`io.github.kyant0:backdrop`）实现：折射、色散、高光等真实玻璃质感渲染。
- **[DoubanMovieListBackUpToNotion](https://github.com/Geetheshe/DoubanMovieListBackUpToNotion)** — 由 [Geetheshe](https://github.com/Geetheshe) 开源。本应用的豆瓣标记列表爬取方式与反爬思路参考了该项目的实现。

## 📄 许可证

本项目自有原创代码采用 GNU GPLv3，完整文本见 [LICENSE](LICENSE)。GPLv3 不自动覆盖第三方依赖、影视数据、海报及第三方服务返回内容，发布时须遵守各自的许可证与服务条款，详见 `THIRD-PARTY-NOTICES.md` 与 `ASSET-LICENSES.md`。
