# TraktoSearch App 素材与标识清单

本清单记录 Android App 中不应被误认为 GPLv3 代码的图片、动画、Logo、截图和第三方标识。

## 项目自有素材

- app/src/main/res/drawable/ic_search_cloud.png、drawable-nodpi/ic_launcher_foreground_image.png、mipmap-*/ic_launcher*.png 和 drawable-xxxhdpi/splash_branding_image.png：TraktoSearch 图标、启动页和品牌素材；除非另有标注，按项目自有素材管理。
- app/src/main/res/drawable/splash_clapper*.xml、主题、布局和界面资源：项目代码 / 资源的一部分，代码部分适用根目录 LICENSE，图片或动画部分以本清单为准。
- 观影列表、设置、资源搜索等 App 截图：用于产品说明时应脱敏；截图内出现的影视海报、评分或第三方标识仍归相应权利人所有。

## AI 生成和角色素材

- 官网 website-trakt/assets/chiikawa/ 中的角色装饰由 AI 生成或经 AI 辅助制作，采用 Chiikawa 风格，仅作非官方装饰。
- 这些素材不代表获得 nagano、ちいかわ製作委員会或其他权利人的授权、合作或背书。若将官网素材复制进 APK，必须同时保留官网的 AI 生成和非官方说明，并在收到权利人通知后暂停公开使用。

## 随包字体

彩蛋序列（`app/src/main/java/com/tracktosearch/ui/screen/swiftie/`）使用 13 个随包字体，
文件在 `app/src/main/res/font/`，完整许可证文本在 `app/src/main/assets/fonts/licenses/`。
全部取自 Google Fonts，全部为 OFL-1.1 或 Apache-2.0，均允许商业使用与嵌入分发。
下载与子集化过程记录在 `scripts/fetch-swiftie-fonts.sh`，产物已提交，正常构建不需要再跑。

| 资源 | 上游字体 | 版权 | 许可证 |
| --- | --- | --- | --- |
| swiftie_script | Pacifico | 2018 The Pacifico Project Authors | OFL-1.1 |
| swiftie_marker | Gochi Hand | 2011 Juan Pablo del Peral | OFL-1.1（RFN） |
| era_taylor_swift | Great Vibes | 2015 The Great Vibes Pro Project Authors | OFL-1.1 |
| era_fearless | Josefin Sans（定重 600） | 2010 The Josefin Sans Project Authors | OFL-1.1（RFN） |
| era_speak_now | Yellowtail | 2011 Brian J. Bonislawsky DBA Astigmatic (AOETI) | Apache-2.0 |
| era_red | Bebas Neue | 2010 Dharma Type | OFL-1.1 |
| era_1989 | Permanent Marker | 2010 Font Diner, Inc. | Apache-2.0 |
| era_reputation | UnifrakturMaguntia | 2010 j. 'mach' wust；2009 Peter Wiegel | OFL-1.1（RFN） |
| era_lover | Parisienne | 2012 Brian J. Bonislawsky DBA Astigmatic | OFL-1.1（RFN） |
| era_imfell | IM Fell DW Pica Italic | 2010 Igino Marini | OFL-1.1 |
| era_midnights | Inter（定重 500 / opsz 28） | 2020 The Inter Project Authors | OFL-1.1 |
| era_ttpd | Libre Caslon Display | 2012 The Libre Caslon Display Authors | OFL-1.1 |
| era_showgirl | Playfair Display Italic（定重 400） | 2017 The Playfair Display Project Authors | OFL-1.1（RFN） |

`era_imfell` 同时服务 folklore 与 evermore 两个时代，因此 13 个文件对应 12 个时代。

### 子集化与保留字体名

13 个文件都是**逐专辑激进子集化**的产物：每个只保留自己那几个字的字形（7–41 个），
合计约 89 KiB。子集化删除了绝大多数字形，属于 OFL-1.1 定义的 **Modified Version**，
因此第 3 条生效：Modified Version 不得沿用 Reserved Font Name。

上表标 **RFN** 的 5 个字体（Gochi Hand、Josefin Sans、UnifrakturMaguntia、Parisienne、
Playfair Display）已按此改掉内部 `name` 表的家族名与 PostScript 名，改名脚本是
`scripts/rename-swiftie-font-rfn.py`：

| 资源 | 改后家族名 | 改后 PostScript 名 |
| --- | --- | --- |
| swiftie_marker | Swiftie Marker | SwiftieMarker-Regular |
| era_fearless | Swiftie Fearless | SwiftieFearless-Regular |
| era_lover | Swiftie Lover | SwiftieLover-Regular |
| era_showgirl | Swiftie Showgirl | SwiftieShowgirl-Italic |
| era_reputation | Swiftie Reputation | SwiftieReputation-Regular |

Android 按资源 ID 加载 `res/font/*.ttf`，不看内部家族名，所以改名不影响渲染。
无 RFN 的 8 个字体保留原家族名。两个 Apache-2.0 字体（Yellowtail、Permanent Marker）
按第 4 条要求在此声明：**已被子集化修改**。它们随包的 `*-LICENSE.txt` 只有 Apache-2.0
全文、不含署名行，上表的版权持有人取自字体内部 `name` 表第 0 项。

后续若再增删字形或换字重，改完必须同步更新本节的字形数、字重和改名表。
新增带 RFN 的字体时，改名是硬要求，不是可选项。

## 第三方 Logo、图标和动画

- ic_douban_logo.xml、ic_trakt_logo.xml、ic_wikipedia.xml、社交平台图标以及网盘图标用于识别相应服务。名称、Logo、商标和品牌规范归各自权利人所有，不适用本项目 GPLv3。
- app/src/main/res/raw/cloud_*.json、easter_*.json 等 Lottie 动画必须按其原始来源和发布包中的许可证处理。目前代码目录未为每个动画保存完整的作者、来源和许可证元数据；在公开发布或重新分发前，应补齐这部分记录，无法核对的素材应移除或替换。
- Android 系统图标和系统字体属于 Android / 系统环境的一部分，不作为项目原创素材重新授权。彩蛋随包字体见上文「随包字体」。

## 影视数据与海报

TMDB、Trakt、豆瓣、OMDb 等返回的影视资料、海报、剧照、评论和评分仅用于应用功能。它们不属于项目原创作品，不因被缓存、展示或写入本地数据库而转为 GPLv3 素材；具体使用须遵守相应服务条款、API 归属要求和版权法。

## 变更要求

新增或替换资源时，应记录文件路径、来源、作者、许可证、归属文字、是否允许商业使用以及是否需要随包提供源文件。来源或授权不明的资源不得进入公开 APK 或官网发布包。
