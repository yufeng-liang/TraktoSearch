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

## Vendored 二进制

`app/libs/richtap_sdk_lite.aar`

| 项 | 值 |
| --- | --- |
| 组件 | RichTap Android SDK（Lite），瑞声科技 AAC Technologies |
| 用途 | 线性马达自定义波形触感：`.he` 波形、包络（振幅 + 频率曲线）、50 个预置效果 |
| 大小 | 98 288 字节 |
| SHA-256 | `9a942e397e8236fb67e57e4379ed3f3ac19dacc2c53a1ae957a81691be08501f` |
| 许可证 | MIT，Copyright (c) 2022 RichTaper |
| 取得方式 | [richtap-haptics/RichTapBounce](https://github.com/richtap-haptics/RichTapBounce) 的 `app/libs/richtap_sdk_lite.aar` |
| 声明权限 | 仅 `android.permission.VIBRATE` |

上游没有发布到 Maven，只把二进制捆在 GitHub 上的 MIT 示例工程里，因此只能 vendored
到 `app/libs/`。MIT 许可证位于该仓库根目录，覆盖仓库内容。升级时需重新记录大小与
SHA-256，并复核许可证文件是否变更。

**必须取 Lite 版，不能取 NETWORK 版。** 同一 SDK 的
`RichTap_ASDK_2.2.0_20250313_NETWORK_release.aar`（见 RichTapDynamics、
RichTapAudioPlayer、RichTapVideoPlayer 三个示例工程）在 `RichTapUtils.init(Context)`
里会反射调用 `com.richtap.sdk.network.RTAPIService.track()`，向
`https://platform.richtap-haptics.com/richtap/sys/eventTrackingSdk/saveTrackingSdk`
上报事件。本项目有隐私政策页，不引入未声明的第三方数据上报。Lite 版不含
`com/richtap/sdk/network/` 包（实测 0 个类），SDK 内部那次反射取不到类会被捕获，
只留一行 `Log.d`，功能不受影响。

该 aar 自带 `<uses-library android:name="richtap-api" android:required="false" />`。
`richtap-api` 是厂商 ROM 侧提供的共享库，缺失时应用照样安装运行；运行期以
`RichTapUtils.isSupportedRichTap()` 判定，不支持则整层退回 AOSP 触感通路
（`HapticFeedbackConstants` / `VibrationEffect`）。

## sherpa-onnx 语音关键词识别

`app/libs/sherpa-onnx-1.13.6.aar`

| 项 | 值 |
| --- | --- |
| 组件 | sherpa-onnx Android native AAR |
| 版本 | 1.13.6 |
| 用途 | AI 精灵按住说话时的本地关键词检测（KWS），将 PCM 音频流匹配到 `keywords.txt` 中的角色 ID |
| 大小 | 49 097 942 字节（约 46.82 MiB） |
| SHA-256 | `0012d9a28f15bd6fb966b62b70a75da3990512fdccce28b83098248ce4be1698` |
| 上游项目 | [k2-fsa/sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) |
| 对应发布 | [v1.13.6](https://github.com/k2-fsa/sherpa-onnx/releases/tag/v1.13.6) |
| 许可证 | Apache-2.0；以上游仓库的 [LICENSE](https://github.com/k2-fsa/sherpa-onnx/blob/master/LICENSE) 为准 |
| 项目集成 | `implementation(files("libs/sherpa-onnx-1.13.6.aar"))` |

当前项目没有通过 Gradle/Maven 坐标解析该 Android 二进制，而是直接引用 `app/libs/` 下的固定 AAR。这样可以锁定当前验证过的 native 实现、版本与 ABI，保证源码克隆后能够按相同二进制复现构建；如果以后改为构建脚本下载或 Git LFS / Release 分发，应同步更新版本、下载来源和 SHA-256。

AAR 内未发现独立的 `LICENSE` / `NOTICE` 条目，因此本节保留上游许可证、版本和校验值作为归属记录。AAR 的许可证不自动覆盖项目另外打包的语音模型；`app/src/main/assets/kws/` 下的 ONNX 模型、tokens 和关键词表应按各自来源与授权另行核对。

构建配置只保留 `arm64-v8a`（见 `app/build.gradle.kts` 的 `abiFilters`），AAR 文件大小不等于最终 APK 增加量。删除该 AAR 会同时破坏 `SherpaOnnxKwsRecognizer`、`AiVoiceCapture` 和 AI 精灵语音激活链路，不能只删除二进制文件。

## 随包字体

`app/src/main/res/font/` 下共 14 个字体：彩蛋序列用的 13 个来自 Google Fonts，
外加项目原有的 `ark_pixel_12px`（Ark Pixel 12px Mono zh_cn，TakWolf，用于激活登录页）。
全部为 OFL-1.1 或 Apache-2.0，均允许嵌入与商业分发。逐个文件的上游名称、版权持有人、
许可证、子集化范围以及保留字体名改名记录见
[ASSET-LICENSES.md](ASSET-LICENSES.md) 的「随包字体」两节。

发布 APK 时的三条义务：

- 许可证全文随包提供 —— 已放在 `app/src/main/assets/fonts/licenses/`（14 份，
  与 14 个字体一一对应），不要在 packaging 规则里排除 `assets/fonts/`。
- OFL-1.1 第 3 条：子集化后的字体不得沿用 Reserved Font Name。判据是入包二进制的
  name ID 0，带 RFN 的 6 个字体已改内部家族名，改动脚本随仓库提交。
- Apache-2.0 第 4 条：需声明已修改。两个 Apache 字体（Yellowtail、Permanent Marker）
  的子集化事实已在 ASSET-LICENSES.md 中声明。

字体只用于彩蛋的专辑名、算式与签名渲染。专辑名与曲目名是事实性元数据，
彩蛋不含任何歌词、专辑封面图，签名是 Pacifico 字形而非真实签名。

## 随包音频

`app/src/main/res/raw/swiftie_theme.ogg`（Ogg Opus 128 kbps，1.93 MiB，125.998 s）
是霉粉彩蛋的配乐，由需求方提供并指示随包分发。源文件为 320 kbps MP3，已转码。

**该文件的授权未核实。** 文件名指向 The Eras Tour 的开场音乐，本项目未取得权利人
许可。它与随包字体不同 —— 字体有明确的 OFL-1.1 / Apache-2.0 授权，这个音频没有。
公开发布前必须取得书面许可、替换为自制或已授权音轨，或改为不随包。风险与责任
明细见 [ASSET-LICENSES.md](ASSET-LICENSES.md) 的「随包音频」一节。

源文件的 ID3v2 标签（含 160 107 字节专辑封面图）已在转码时丢弃。

## Cloudflare Workers 依赖

- wrangler、TypeScript 和 Cloudflare Workers 类型包属于开发 / 构建工具，不是运行时业务授权。
- Workers 使用 Cloudflare Pages、Workers、D1、KV、R2、Access、Email / Brevo 等外部服务。实际数据处理和服务可用性受对应服务条款、隐私政策和配置影响。

## 外部影视服务和资源源

Trakt、豆瓣、TMDB、OMDb、PanSou、PanHub、ZReso、GitHub、Gitee、网盘服务和磁力处理工具均为独立第三方。项目只在功能需要时调用、展示或打开结果，不主张拥有其内容、商标、接口或返回链接，也不代表得到其背书。

### TMDB 归属

This product uses TMDB and the TMDB APIs but is not endorsed, certified, or otherwise approved by TMDB.

### 使用边界

用户必须自行遵守所在地法律、版权许可、第三方服务条款、API / robots 规则和访问频率限制。不得用本项目绕过访问控制、批量抓取他人数据、压垮上游服务，或处理未经授权的影视内容。
