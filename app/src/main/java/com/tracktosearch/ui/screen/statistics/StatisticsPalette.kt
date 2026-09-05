package com.tracktosearch.ui.screen.statistics

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.tracktosearch.ui.component.isAppDarkTheme

/**
 * 统计页图表专用分析色板（8 色暖调）。
 *
 * 饼图、类型排行、词云共用这一套，保证同一页里「同一种颜色语言」；
 * 热力图不用它——热力图表达的是单一维度的多少，仍用主题色明度阶。
 *
 * 不跟随用户强调色：强调色可自定义成任意色相，跟随后相邻扇区会撞色，
 * 图例可辨识度不可控。固定色板牺牲一点主题一致性，换来图表永远读得懂。
 */
private val ANALYTICS_LIGHT = listOf(
    Color(0xFFC9762F), // 焦糖
    Color(0xFFB1452E), // 砖红
    Color(0xFF6E7F3A), // 苔绿
    Color(0xFF3C5A78), // 靛蓝
    Color(0xFF8A5A2B), // 赭石
    Color(0xFF7A4A63), // 梅紫
    Color(0xFF2E7C7B), // 湖青
    Color(0xFFB8901F), // 芥黄
)

/** 深色主题变体：提亮、降饱和，保证在深色卡片上仍能分辨且不刺眼。 */
private val ANALYTICS_DARK = listOf(
    Color(0xFFE29A5C),
    Color(0xFFD9705C),
    Color(0xFF9FB067),
    Color(0xFF7FA3C4),
    Color(0xFFC08E5C),
    Color(0xFFB58098),
    Color(0xFF5FB0AE),
    Color(0xFFE0C267),
)

/** 色板容量。饼图扇区数、词云取色循环长度都以它为准。 */
const val ANALYTICS_PALETTE_SIZE = 8

/**
 * 取当前主题下的分析色板。
 *
 * 走 isAppDarkTheme() 而不是 isSystemInDarkTheme()：本 App 主题模式由 ThemeStorage 控制，
 * 可与系统不一致。那个判据也自带 remember 缓存，所以这里不用再包一层。
 */
@Composable
fun rememberAnalyticsPalette(): List<Color> =
    if (isAppDarkTheme()) ANALYTICS_DARK else ANALYTICS_LIGHT

/**
 * 浅色变体色板，供分享长图使用。
 *
 * 分享图固定暖纸底，不跟随 App 深色模式：一张发出去的图会在别人的聊天里被任意背景衬着，
 * 深色版在浅色气泡里像一块黑洞。
 */
internal fun analyticsPaletteLight(): List<Color> = ANALYTICS_LIGHT
