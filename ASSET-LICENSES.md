# TraktoSearch App 素材与标识清单

本清单记录 Android App 中不应被误认为 GPLv3 代码的图片、动画、Logo、截图和第三方标识。

## 项目自有素材

- app/src/main/res/drawable/ic_search_cloud.png、drawable-nodpi/ic_launcher_foreground_image.png、mipmap-*/ic_launcher*.png 和 drawable-xxxhdpi/splash_branding_image.png：TraktoSearch 图标、启动页和品牌素材；除非另有标注，按项目自有素材管理。
- app/src/main/res/drawable/splash_clapper*.xml、主题、布局和界面资源：项目代码 / 资源的一部分，代码部分适用根目录 LICENSE，图片或动画部分以本清单为准。
- 观影列表、设置、资源搜索等 App 截图：用于产品说明时应脱敏；截图内出现的影视海报、评分或第三方标识仍归相应权利人所有。

## AI 生成和角色素材

- 官网 website-trakt/assets/chiikawa/ 中的角色装饰由 AI 生成或经 AI 辅助制作，采用 Chiikawa 风格，仅作非官方装饰。
- 这些素材不代表获得 nagano、ちいかわ製作委員会或其他权利人的授权、合作或背书。若将官网素材复制进 APK，必须同时保留官网的 AI 生成和非官方说明，并在收到权利人通知后暂停公开使用。

## 第三方 Logo、图标和动画

- ic_douban_logo.xml、ic_trakt_logo.xml、ic_wikipedia.xml、社交平台图标以及网盘图标用于识别相应服务。名称、Logo、商标和品牌规范归各自权利人所有，不适用本项目 GPLv3。
- app/src/main/res/raw/cloud_*.json、easter_*.json 等 Lottie 动画必须按其原始来源和发布包中的许可证处理。目前代码目录未为每个动画保存完整的作者、来源和许可证元数据；在公开发布或重新分发前，应补齐这部分记录，无法核对的素材应移除或替换。
- Android 系统图标和系统字体属于 Android / 系统环境的一部分，不作为项目原创素材重新授权。

## 影视数据与海报

TMDB、Trakt、豆瓣、OMDb 等返回的影视资料、海报、剧照、评论和评分仅用于应用功能。它们不属于项目原创作品，不因被缓存、展示或写入本地数据库而转为 GPLv3 素材；具体使用须遵守相应服务条款、API 归属要求和版权法。

## 变更要求

新增或替换资源时，应记录文件路径、来源、作者、许可证、归属文字、是否允许商业使用以及是否需要随包提供源文件。来源或授权不明的资源不得进入公开 APK 或官网发布包。
