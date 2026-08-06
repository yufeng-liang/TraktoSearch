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

private val MonetDoubanGreenLight = Color(0xFF5E916A)
private val MonetDoubanGreenDark = Color(0xFF78A985)

/** 豆瓣按钮专用色：保留绿色识别，同时轻微吸收当前莫奈主题主色。 */
fun ColorScheme.monetDoubanGreen(): Color {
    val base = if (background.luminance() < 0.5f) MonetDoubanGreenDark else MonetDoubanGreenLight
    return lerp(base, primary, 0.14f)
}

fun ColorScheme.onMonetDoubanGreen(): Color = Color.White
