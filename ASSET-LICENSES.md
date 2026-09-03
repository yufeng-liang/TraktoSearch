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

`app/src/main/res/font/` 下共 16 个字体文件：彩蛋序列用的 13 个、项目原有的
`ark_pixel_12px`（见下一节），以及两个印章字体（`hanwang_lisu_seal`、
`unyetgul_seal`）——**后两个的来源与许可证本清单尚未记录**，公开发布前需要补齐。

彩蛋那 13 个的许可证文本在 `app/src/main/assets/fonts/licenses/`。其中 12 个取自
Google Fonts，为 OFL-1.1 或 Apache-2.0，均允许商业使用与嵌入分发；**`swiftie_honey`
是例外**，见下表与其后的说明。下载与子集化过程记录在
`scripts/fetch-swiftie-fonts.sh`，产物已提交，正常构建不需要再跑。

| 资源 | 上游字体 | 版权 | 许可证 |
| --- | --- | --- | --- |
| swiftie_script | Pacifico | 2018 The Pacifico Project Authors | OFL-1.1 |
| swiftie_honey | Honey Script SemiBold | Typographer Mediengestaltung | 未核实（见下） |
| era_taylor_swift | Great Vibes | 2015 The Great Vibes Pro Project Authors | OFL-1.1 |
| era_fearless | Josefin Sans（定重 600） | 2010 The Josefin Sans Project Authors | OFL-1.1（RFN） |
| era_speak_now | Yellowtail | 2011 Brian J. Bonislawsky DBA Astigmatic (AOETI) | Apache-2.0 |
| era_red | Bebas Neue | 2010 Dharma Type | OFL-1.1 |
| era_1989 | Permanent Marker | 2010 Font Diner, Inc. | Apache-2.0 |
| era_reputation | UnifrakturMaguntia | 2010 j. 'mach' wust；2009 Peter Wiegel | OFL-1.1（RFN） |
| era_lover | Parisienne | 2012 Brian J. Bonislawsky DBA Astigmatic | OFL-1.1（RFN，见下） |
| era_imfell | IM Fell DW Pica Italic | 2007 Igino Marini | OFL-1.1（RFN） |
| era_midnights | Inter（定重 500 / opsz 28） | 2020 The Inter Project Authors | OFL-1.1 |
| era_ttpd | Libre Caslon Display | 2012 The Libre Caslon Display Authors | OFL-1.1 |
| era_showgirl | Playfair Display Italic（定重 400） | 2017 The Playfair Display Project Authors | OFL-1.1（RFN） |

`era_imfell` 同时服务 folklore 与 evermore 两个时代，因此 13 个文件对应 12 个时代。

`swiftie_script` 覆盖 `Taylor Swift` 与纪念页那句 `Taylor & Me — forever & always.`，
共 19 个码点。它比别的字体多一层耦合：签名动画的笔心中线
（`SwiftieSignaturePath.kt`）是用脚本从**这份二进制**的字形骨架量出来的，所以重新
子集化并且轮廓有变化时，必须重跑 `scripts/build-swiftie-signature-path.py`。

### Honey Script SemiBold 的授权状态：未核实

它是出题页那张海报上「13 + 87 = 100」的原字体，从 dafont 取得
（`dl.dafont.com/dl/?f=honey_script`）。dafont 的条目页标注 **100% Free**，
而字体内部 name ID 0 写的是 `All rights reserved`、厂商是
`Typographer Mediengestaltung`；**没有随字体分发任何许可证文本**，两处说法互相矛盾。
`OS/2.fsType = 0`（Installable Embedding），即字体自身允许嵌入分发。

已按此实情记录在 `app/src/main/assets/fonts/licenses/honeyscript-NOTICE.txt` ——
那是一份**说明**，不是许可证，因为没有许可证可放。需求方在知悉该风险后指示使用。
按本文件末尾「变更要求」一节的标准，公开发布前应取得书面许可，或换成许可证明确的
手写体字体。

### 子集化与保留字体名

13 个文件都是**逐专辑激进子集化**的产物：每个只保留自己那几个字的字形
（4–52 个，`era_1989` 最少 4 个、`swiftie_script` 最多 52 个），13 个合计 76 696 字节
= 74.9 KiB。子集化删除了绝大多数字形，属于 OFL-1.1 定义的 **Modified Version**，
因此第 3 条生效：Modified Version 不得沿用 Reserved Font Name。

判据是**入包的那份二进制**里 name ID 0 有没有 `With Reserved Font Name`，
而不是上游许可文本 —— 两者会不一致，本项目就撞上了两个方向各一例：

- `era_imfell`：随包的 `imfelldwpica-OFL.txt` 版权行没有 RFN 条款，但入包的二进制
  （2007 年版，`fetch-swiftie-fonts.sh` 从 `fonts.googleapis.com/css2` 取得）
  声明了 `With Reserved Font Name IM FELL DW Pica Italic`。按二进制算，要改名。
- `era_lover`（Parisienne）：反过来，二进制里没有 RFN 条款，但仍然改了名。
  多改无害，保持现状。

上表标 **RFN** 的 5 个字体已按此改掉内部 `name` 表的家族名与 PostScript 名，
改名脚本是 `scripts/rename-swiftie-font-rfn.py`（可带资源名只改指定的几个）：

| 资源 | 改后家族名 | 改后 PostScript 名 |
| --- | --- | --- |
| era_fearless | Swiftie Fearless | SwiftieFearless-Regular |
| era_lover | Swiftie Lover | SwiftieLover-Regular |
| era_showgirl | Swiftie Showgirl | SwiftieShowgirl-Italic |
| era_reputation | Swiftie Reputation | SwiftieReputation-Regular |
| era_imfell | Swiftie Fell | SwiftieFell-Italic |

`swiftie_honey` 不在改名表里：它的 name ID 0 没有 RFN 条款（写的是
`All rights reserved`，那不是 OFL 的保留字体名机制）。

name ID 0 的版权声明**保持原样不改** —— OFL 要求保留它，需要改的只是家族名。
Android 按资源 ID 加载 `res/font/*.ttf`，不看内部家族名，所以改名不影响渲染。
两个 Apache-2.0 字体（Yellowtail、Permanent Marker）按第 4 条要求在此声明：
**已被子集化修改**。它们随包的 `*-LICENSE.txt` 只有 Apache-2.0 全文、不含署名行，
上表的版权持有人取自字体内部 `name` 表第 0 项。

后续若再增删字形或换字重，改完必须同步更新本节的字形数、字节数、字重和改名表 ——
统计只算彩蛋那 13 个，不要把 `ark_pixel_12px` 与两个印章字体算进去
（`fetch-swiftie-fonts.sh` 收尾那句 `du -ch "$OUT"/*.ttf` 会把它们一起算上）。
新增字体时先读它 name ID 0：带 RFN 就必须改名，不是可选项。

## 随包字体（彩蛋之外）

| 资源 | 上游字体 | 版权 | 许可证 | 用途 |
| --- | --- | --- | --- | --- |
| ark_pixel_12px | Ark Pixel 12px Mono zh_cn | 2021 TakWolf | OFL-1.1 | 取票机式激活登录页的点阵字 |

12 236 字节，130 个字形，由 `ui/theme/Type.kt` 的 `PixelFontFamily` 引用、
用在 `ui/screen/login/ActivationLoginScreen.kt`。不是 Google Fonts 来源，
也不属于彩蛋序列。许可证全文随包提供在
`app/src/main/assets/fonts/licenses/arkpixel-OFL.txt`（与仓库根
`licenses/ArkPixelFont-OFL.txt` 同一份 —— 根目录那份不进 APK）。
name ID 0 无 RFN 条款，未改名。

## 随包音频

| 路径 | 来源文件 | 时长 | 格式 | 大小 |
| --- | --- | --- | --- | --- |
| app/src/main/res/raw/swiftie_theme.ogg | The Eras Tour Intro(2.0 official).mp3 | 125.998 s | Ogg Opus，128 kbps VBR，48 kHz 立体声 | 1.93 MiB |

霉粉彩蛋的配乐，由需求方提供并指定随包分发。彩蛋序列的时长账本从这个文件的实际长度
倒推：`SwiftieTimeline.MUSIC_MS` 就是它，换音轨必须同步改这个值。配乐从答对那一帧
起播，比序列时钟早一个前奏（`PREROLL_MS` = 1500 ms，键盘退场 + 算式归位 + 手写体
写出来），所以账本总长 `TOTAL_MS` = 音轨长度 − 前奏，序列最后一帧与音轨末尾同时到。
这三个值任何一个单独改动都会让 Lover 绽放与配乐错开，`SwiftieTimelineTest` 守着。

源文件是 320 kbps MP3（126.067 s，4.81 MiB），已转码为 Opus 128 kbps VBR
（`ffmpeg -c:a libopus -b:a 128k -vbr on -application audio`），体积降 60%。
Opus 按 20ms 帧对齐，预跳裁掉 69ms，所以时长从 126.067 s 变成 125.998 s ——
账本已同步。转码是有损转有损；这个码率下对手机喇叭与耳机的听感没有可辨差异。
Opus 解码自 Android 5.0 起支持、Ogg 容器同期可用，本项目 `minSdk` 为 26。

源文件带 162 699 字节 ID3v2.3 标签，其中 `APIC` 帧是 160 107 字节的专辑封面图。
转码时用 `-vn -map_metadata -1` 一并丢弃 —— 项目的彩蛋素材红线里包含「不含专辑
封面图」，随 ID3 夹带进 APK 同样违反该条。

**授权状态：未核实。** 文件名指向 The Eras Tour 的开场音乐，本项目未取得权利人
许可，也未能确认其为可自由分发的素材。需求方在知悉该风险后指示随包分发，授权
责任由需求方承担。按本文件末尾「变更要求」一节的标准，来源或授权不明的资源不
应进入公开 APK 或官网发布包 —— 公开发布前应取得书面许可、替换为自制或已授权
音轨，或改为不随包（彩蛋走静默路径，代码已支持：`SwiftieMusic` 抢不到音频焦点
或处于静音档时只播动画）。

## 随包图片（霉粉彩蛋出题页海报）

| 路径 | 内容 | 尺寸 | 格式 | 大小 |
| --- | --- | --- | --- | --- |
| app/src/main/res/drawable-nodpi/swiftie_poster_sky.webp | 抹去全部文字后的天空底版 | 1200×2136 | WebP 有损 q86 | 33 590 字节 |
| app/src/main/res/drawable-nodpi/swiftie_glitter.webp | 闪粉贴板（整条算式那一块，非闪粉像素已补） | 640×1064 | WebP 有损 q92 | 102 030 字节 |

**两张都是第三方海报的衍生物，授权状态由需求方声明。** 出题页复刻的是一张
「13 + 87 = 100 / Congrats on Forever!」的闪粉海报（Taylor Swift × Travis Kelce 主题，
13 与 87 分别是两人的号码）。需求方提供了原图并声明已获授权。本项目未独立核实。

三份衍生物：

- 天空底版：需求方提供的、已由其抹去文字的 PNG（1600×2848），本项目只做了色彩空间
  转换、缩放与 WebP 转码。原图带 **Display P3** 色彩配置，随包的 WebP 不带配置、被
  Android 按 sRGB 解读，所以数值必须先转成 sRGB —— 直接沿用 P3 数值会让整片天空的
  饱和度掉两成（脚本里有断言守着）。2026-09-02 需求方另找了一版抹字图替换先前那份：
  与原图逐像素对齐、色键扫不出任何残留墨迹，天空饱和度也更接近原图（转换后 50.5，
  原图 51.5，旧那份 48.3）。
- 闪粉贴板：原图上整条算式那一块（`13 + 87 = 100` 的并集包围盒，四周各多 24 个原图
  像素的出血），框内非闪粉的像素用邻近闪粉补掉，缩到 640 宽。它**不是平铺贴图** ——
  app 按同一个实测框把它贴回算式的位置，所以每个数字取到的就是当初印在这个数字里的
  闪粉。颜色和颗粒因此不是调出来的，是原图本身（实测亮度跨 46–232）。
- `app/src/main/java/com/tracktosearch/ui/screen/swiftie/SwiftieCongratsPath.kt`：
  「Congrats on Forever!」那三行手写体的**矢量描摹**（909 个点，三条路径）。原作者说
  用的是 Filmotype LaCrosse —— 商业字体，字体文件不能随包，所以改成从原图描轮廓。
  它是代码文件，但内容是这张海报的衍生物，与上面两张图同一授权状态。

重新生成这三份产物的脚本是 `scripts/build-swiftie-poster-assets.py`，输入是
`docs/previews/swiftie-poster/` 下的原图 `9x16.jpg`（1 609 629 字节）与天空底版
`sky-clean.png`（1600×2848，2 712 701 字节）。两者都**不进 APK**，但为了脚本可重跑
而破例入库 —— `.gitignore` 第 55 行忽略整个 `docs/`，这两份是 `git add -f` 强制加进
来的。所以仓库里除了上面三份产物，还留着它们的**原始来源图**，同一授权状态。

按本文件末尾「变更要求」一节的标准，公开发布前应取得书面许可，或把这一屏换成自制
图形。彩蛋整体是可选功能，移除这三份产物不影响 App 主流程。

## 第三方 Logo、图标和动画

- ic_douban_logo.xml、ic_trakt_logo.xml、ic_wikipedia.xml、社交平台图标以及网盘图标用于识别相应服务。名称、Logo、商标和品牌规范归各自权利人所有，不适用本项目 GPLv3。
- app/src/main/res/raw/cloud_*.json、easter_*.json 等 Lottie 动画必须按其原始来源和发布包中的许可证处理。目前代码目录未为每个动画保存完整的作者、来源和许可证元数据；在公开发布或重新分发前，应补齐这部分记录，无法核对的素材应移除或替换。
- Android 系统图标和系统字体属于 Android / 系统环境的一部分，不作为项目原创素材重新授权。彩蛋随包字体见上文「随包字体」。

## 影视数据与海报

TMDB、Trakt、豆瓣、OMDb 等返回的影视资料、海报、剧照、评论和评分仅用于应用功能。它们不属于项目原创作品，不因被缓存、展示或写入本地数据库而转为 GPLv3 素材；具体使用须遵守相应服务条款、API 归属要求和版权法。

## 变更要求

新增或替换资源时，应记录文件路径、来源、作者、许可证、归属文字、是否允许商业使用以及是否需要随包提供源文件。来源或授权不明的资源不得进入公开 APK 或官网发布包。
