package com.tracktosearch.ui.screen.ai

import com.tracktosearch.BuildConfig

/**
 * AI 功能可见性的唯一判断入口，release 打包时整体隐藏。
 *
 * 开关本体是 `BuildConfig.AI_FEATURES_ENABLED`：defaultConfig 为 true（debug 与三个 preview
 * 包照常可见、照常装机验收），release 构建类型翻成 false。这里包一层，是给 UI 侧一个稳定
 * 的取值入口——所有能打开 AI 界面或展示 AI 文案的地方（两处搜索页的精灵中心入口与自动探头、
 * 云朵长按、详情页助手、隐私页 AI 开关组、帮助页 AI 章节）统一判它，别各自 import BuildConfig。
 *
 * 值是编译期常量，release 下 `if (aiFeaturesEnabled)` 恒为 false，R8 会把整段调用点剔除，
 * 与 AppNavigation 里既有的 if (BuildConfig.DEBUG) 同一套路。数据层与 worker 链路不动；
 * 隐私页几个 AI 授权开关的默认值本来就是关，正式包里不采集、不上传。
 */
val aiFeaturesEnabled: Boolean = BuildConfig.AI_FEATURES_ENABLED
