package com.tracktosearch.ui.theme

import androidx.annotation.StringRes
import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import com.tracktosearch.R

// Primary - 深红色系 (Trakt 品牌)
val Red500 = Color(0xFFED1C24)
val Red700 = Color(0xFFC4161C)
val Red900 = Color(0xFF8B0000)

// Secondary
val DarkGray = Color(0xFF1A1A2E)
val MediumGray = Color(0xFF16213E)
val LightGray = Color(0xFFE0E0E0)

// 网盘官方品牌色
val QuarkBlue = Color(0xFF1E88E5)       // 夸克 - 蓝绿色
val BaiduBlue = Color(0xFF06A7FF)       // 百度网盘 - 亮蓝色
val AliIndigo = Color(0xFF6366F1)        // 阿里云盘 - 靛蓝色
val XunleiBlue = Color(0xFF2386EA)       // 迅雷 - 标准蓝
val UcOrange = Color(0xFFFF6B00)         // UC网盘 - 橙色
val Blue115 = Color(0xFF2563EB)          // 115网盘 - 蓝色

// Background
val LightBackground = Color(0xFFF0F1F3)
val LightSurface = Color.White
val DarkBackground = Color(0xFF0F0F1A)
val DarkSurface = Color(0xFF1A1A2E)
val DarkCard = Color(0xFF242442)

// 莫奈/印象派主题色
enum class MonetAccent(@StringRes val labelResId: Int, val light: Color, val dark: Color, val lightOn: Color = Color.White, val darkOn: Color = Color.White) {
    VINTAGE_TICKET(R.string.accent_vintage_ticket, Color(0xFF9A6242), Color(0xFFC48763)),
    WATER_LILY(R.string.accent_water_lily, Color(0xFF7B68AE), Color(0xFF9B8EC4)),
    SUNRISE(R.string.accent_sunrise, Color(0xFFE8915A), Color(0xFFF4A460)),
    JAPANESE_BRIDGE(R.string.accent_japanese_bridge, Color(0xFF5A8F6B), Color(0xFF7AB68A)),
    CATHEDRAL(R.string.accent_cathedral, Color(0xFF6B7FA0), Color(0xFF8A9BB8)),
    HAYSTACK(R.string.accent_haystack, Color(0xFFC4A94D), Color(0xFFD4B96A)),
    RENOIR(R.string.accent_renoir, Color(0xFFD4748A), Color(0xFFE8909A)),
    STARRY_NIGHT(R.string.accent_starry_night, Color(0xFF4A7FB5), Color(0xFF6B9FD4)),
    POPPY(R.string.accent_poppy, Color(0xFFD94040), Color(0xFFE86060)),
    WHEAT_FIELD(R.string.accent_wheat_field, Color(0xFFD4A030), Color(0xFFE8B84A)),
    ROUEN_CATHEDRAL(R.string.accent_rouen_cathedral, Color(0xFF6A5AAD), Color(0xFF8A7AC4)),
    BALLET(R.string.accent_ballet, Color(0xFFB06AA0), Color(0xFFC88AB8)),
    WATER_LILY_GREEN(R.string.accent_water_lily_green, Color(0xFF4A9A6A), Color(0xFF6AB88A)),
    BOAT_BREAKFAST(R.string.accent_boat_breakfast, Color(0xFF3A8AA0), Color(0xFF5AA8C0));
}

// UI 重设计新增颜色
val CinemaBackground = Color(0xFF0F0F1A)
val CinemaSurface = Color(0xFF1A1A2E)
val CinemaCard = Color(0xFF242442)

// ====== 玻璃棱镜固定色（不随主题变化） ======
val Void = Color(0xFF0A0A14)        // 主背景，比 CinemaBackground 更深
val Slate = Color(0xFF161628)       // 卡片/表面
val Frost = Color(0xFF1E1E3A)       // 搜索栏玻璃底色
// Glass 效果色（用于 Modifier.drawBehind / brush）
// GlassHighlight = white.copy(alpha = 0.08f) — 在使用处直接写
// GlassBorder = white.copy(alpha = 0.12f) — 在使用处直接写
// GlassRefraction = white.copy(alpha = 0.06f) — 在使用处直接写

val RatingGold = Color(0xFFFFD54F)
val RatingGoldDim = Color(0xFFFFD54F).copy(alpha = 0.7f)

// ====== 反馈类型/状态固定色（四个反馈页共用，不随主题变化） ======
// 浅色主题下小字号要压暗才够对比度，压暗逻辑见 FeedbackVisuals.feedbackAccent
val FeedbackFeature = Color(0xFF34D399)   // 功能建议 — 薄荷绿
val FeedbackBug = Color(0xFFFB7185)       // 问题反馈 — 珊瑚红
val FeedbackUx = Color(0xFFFBBF24)        // 体验问题 — 琥珀黄
val FeedbackOther = Color(0xFF9CA3AF)     // 其他 — 中性灰
val FeedbackReplied = Color(0xFF10B981)   // 已回复 / 上传成功 — 翠绿
val FeedbackDeveloper = Color(0xFF34D399) // 开发者角色标识 — 薄荷绿

// ====== 影视状态固定色（详情页状态绑带共用，不随主题变化） ======
// 原先散在 DetailHeaderContent.getStatusColor 里，9 个 Color(0xFF...) 直接写在 when 分支上，
// 违反「颜色统一收在 Color.kt」的约定。取色逻辑见 DetailVisuals.detailStatusColor
val StatusReleased = Color(0xFF4CAF50)       // 已上映 / 连载中 — 绿
val StatusInProduction = Color(0xFFFF9800)   // 制作中 / 后期 / 试播 — 橙
val StatusPlanned = Color(0xFF2196F3)        // 计划中 — 蓝
val StatusRumored = Color(0xFF9C27B0)        // 传闻中 — 紫
val StatusCanceled = Color(0xFFF44336)       // 已取消 — 红
val StatusEnded = Color(0xFF9E9E9E)          // 已完结 — 灰
val StatusUnknown = Color(0xFF757575)        // 未知状态 — 深灰

// ====== 评分平台品牌色（详情页四平台评分区，不随主题变化） ======
// 原先五个 Color(0xFF...) 直接写在 DetailRatingsDialog.RatingsRow 的 badge 构造里。
// 顺带修正 TMDB：以前误用了 IMDb 的黄，两个平台底色一模一样分不出来。
val BrandImdb = Color(0xFFF5C518)            // IMDb 黄
val OnBrandImdb = Color(0xFF000000)
val BrandDouban = Color(0xFF2E963D)          // 豆瓣绿
val BrandTmdb = Color(0xFF01B4E4)            // TMDB 青
val BrandRottenTomatoes = Color(0xFFFA320A)  // 烂番茄红
val BrandMetacritic = Color(0xFFFF9500)      // Metacritic 橙

/** 已看标记专用绿：季集进度条 / 已看计数 / 勾选图标共用。 */
val WatchedGreen = Color(0xFF4CAF50)

/** 拟态玻璃浅色主题描边：原先在 DetailComments 抄了 3 遍、SearchScreen 1 遍。 */
val NeumorphicBorderLight = Color(0xFFD0D5DC)

private val MonetDoubanGreenLight = Color(0xFF5E916A)
private val MonetDoubanGreenDark = Color(0xFF78A985)

/** 豆瓣按钮专用色：保留绿色识别，同时轻微吸收当前莫奈主题主色。 */
fun ColorScheme.monetDoubanGreen(): Color {
    val base = if (background.luminance() < 0.5f) MonetDoubanGreenDark else MonetDoubanGreenLight
    return lerp(base, primary, 0.14f)
}

fun ColorScheme.onMonetDoubanGreen(): Color = Color.White
