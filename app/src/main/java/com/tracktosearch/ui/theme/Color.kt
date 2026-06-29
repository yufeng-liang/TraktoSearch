package com.tracktosearch.ui.theme

import androidx.compose.ui.graphics.Color

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
enum class MonetAccent(val label: String, val light: Color, val dark: Color, val lightOn: Color = Color.White, val darkOn: Color = Color.White) {
    WATER_LILY("睡莲紫", Color(0xFF7B68AE), Color(0xFF9B8EC4)),
    SUNRISE("日出橙", Color(0xFFE8915A), Color(0xFFF4A460)),
    JAPANESE_BRIDGE("日本桥绿", Color(0xFF5A8F6B), Color(0xFF7AB68A)),
    CATHEDRAL("教堂蓝灰", Color(0xFF6B7FA0), Color(0xFF8A9BB8)),
    HAYSTACK("干草堆金", Color(0xFFC4A94D), Color(0xFFD4B96A)),
    RENOIR("雷诺阿粉", Color(0xFFD4748A), Color(0xFFE8909A)),
    STARRY_NIGHT("星夜蓝", Color(0xFF4A7FB5), Color(0xFF6B9FD4)),
    POPPY("虞美人红", Color(0xFFD94040), Color(0xFFE86060)),
    WHEAT_FIELD("麦田金黄", Color(0xFFD4A030), Color(0xFFE8B84A)),
    ROUEN_CATHEDRAL("鲁昂蓝紫", Color(0xFF6A5AAD), Color(0xFF8A7AC4)),
    BALLET("芭蕾粉紫", Color(0xFFB06AA0), Color(0xFFC88AB8)),
    WATER_LILY_GREEN("睡莲绿", Color(0xFF4A9A6A), Color(0xFF6AB88A)),
    BOAT_BREAKFAST("船上午餐蓝", Color(0xFF3A8AA0), Color(0xFF5AA8C0));
}
