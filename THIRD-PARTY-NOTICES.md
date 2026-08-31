# TraktoSearch 第三方声明

本文件记录 Android App、Cloudflare Workers 和相关工具使用的第三方软件与外部服务。第三方组件不因本项目自有代码采用 GPLv3 而自动变更许可证；发布 APK 或服务时，应同时提供相应的许可证文本和归属信息。

## Android 依赖

以下列表按当前 app/build.gradle.kts 与 gradle/libs.versions.toml 整理，版本升级后应重新核对依赖包内的 LICENSE / NOTICE 文件。

| 组件 | 用途 | 常见上游许可证 / 核对入口 |
| --- | --- | --- |
| AndroidX、Jetpack Compose、Material 3、Navigation、Room、WorkManager、Glance、Core、Activity、Browser、DataStore、Security、ProfileInstaller | Android 平台、UI、数据库、后台任务和安全能力 | Apache-2.0；[AndroidX](https://developer.android.com/jetpack/androidx) |
| Kotlin、kotlinx.coroutines、kotlinx.serialization | 语言与异步 / 序列化 | Apache-2.0；[Kotlin](https://github.com/JetBrains/kotlin) |
| Hilt / Dagger | 依赖注入 | Apache-2.0；[Dagger](https://github.com/google/dagger) |
| Retrofit、OkHttp、MockWebServer | HTTP 客户端和测试 | Apache-2.0；[Square](https://github.com/square) |
| Coil | 图片加载和缓存 | Apache-2.0；[Coil](https://github.com/coil-kt/coil) |
| Lottie Compose | 动画资源播放 | Apache-2.0；[Lottie Android](https://github.com/airbnb/lottie-android) |
| jsoup | HTML 解析 | MIT；[jsoup](https://github.com/jhy/jsoup) |
| Haze、Compose RichText、reorderable、zoomable | UI 效果、Markdown、拖拽与缩放 | 以各自发布包内许可证为准，发布前逐项复核 |
| SQLCipher for Android | 本地数据库加密 | 以当前 Community Edition 发布包内许可证和 Zetetic 条款为准；商业授权与开源授权不能混用 |
| jieba-analysis | 中文分词 | 以当前 Maven 发布包内许可证和上游仓库声明为准 |
| JUnit、MockK、Turbine、Truth、Robolectric、AndroidX Test、Error Prone annotations | 测试和编译辅助，仅部分进入测试 / 编译路径 | 以各依赖发布包内许可证为准 |

依赖许可证的完整文本通常随 Gradle 缓存或发布包提供；packaging.resources.excludes 只影响 APK 中部分重复许可证文件的打包方式，不代表项目取消了第三方归属义务。

## 随包字体

`app/src/main/res/font/` 下 13 个字体来自 Google Fonts，OFL-1.1 或 Apache-2.0，
均允许嵌入与商业分发。逐个文件的上游名称、版权持有人、许可证、子集化范围以及
保留字体名改名记录见 [ASSET-LICENSES.md](ASSET-LICENSES.md) 的「随包字体」一节。

发布 APK 时的三条义务：

- 许可证全文随包提供 —— 已放在 `app/src/main/assets/fonts/licenses/`（13 份），
  不要在 packaging 规则里排除 `assets/fonts/`。
- OFL-1.1 第 3 条：子集化后的字体不得沿用 Reserved Font Name。带 RFN 的 5 个字体
  已改内部家族名，改动脚本随仓库提交。
- Apache-2.0 第 4 条：需声明已修改。两个 Apache 字体（Yellowtail、Permanent Marker）
  的子集化事实已在 ASSET-LICENSES.md 中声明。

字体只用于彩蛋的专辑名、算式与签名渲染。专辑名与曲目名是事实性元数据，
彩蛋不含任何歌词、专辑封面图，签名是 Pacifico 字形而非真实签名。

## Cloudflare Workers 依赖

- wrangler、TypeScript 和 Cloudflare Workers 类型包属于开发 / 构建工具，不是运行时业务授权。
- Workers 使用 Cloudflare Pages、Workers、D1、KV、R2、Access、Email / Brevo 等外部服务。实际数据处理和服务可用性受对应服务条款、隐私政策和配置影响。

## 外部影视服务和资源源

Trakt、豆瓣、TMDB、OMDb、PanSou、PanHub、ZReso、GitHub、Gitee、网盘服务和磁力处理工具均为独立第三方。项目只在功能需要时调用、展示或打开结果，不主张拥有其内容、商标、接口或返回链接，也不代表得到其背书。

### TMDB 归属

This product uses TMDB and the TMDB APIs but is not endorsed, certified, or otherwise approved by TMDB.

### 使用边界

用户必须自行遵守所在地法律、版权许可、第三方服务条款、API / robots 规则和访问频率限制。不得用本项目绕过访问控制、批量抓取他人数据、压垮上游服务，或处理未经授权的影视内容。
