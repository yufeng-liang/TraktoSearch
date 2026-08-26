package com.tracktosearch.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.tracktosearch.R

/**
 * 像素点阵字体：Ark Pixel Font 12px monospaced（zh_cn 变体），SIL OFL 1.1，
 * 许可证见 licenses/ArkPixelFont-OFL.txt。
 *
 * 仓库里这份是子集化后的 12 KB，只保留 ASCII、四语言激活码占位文案用到的汉字与假名；
 * 完整字体 4.7 MB，为一个输入框带这么大体积不划算。因此这个字体只适合激活码输入框，
 * 换到别处用会缺字（缺字时静默回退系统字体）。谚文不在覆盖范围内：Ark Pixel 12px
 * 本身不含谚文字形，韩语占位文案会回退系统字体。
 *
 * 字体按 12px 网格设计（unitsPerEm = 1200），字号取 12px 的整数倍才不会插值出灰边，
 * 具体换算见 [pixelFontSize]。
 */
val PixelFontFamily = FontFamily(Font(R.font.ark_pixel_12px))

/** 点阵字体设计网格：12px。字号必须是它的整数倍才能保持像素边缘锐利。 */
const val PIXEL_FONT_GRID_PX = 12

/**
 * 把「想要的视觉大小（dp）」换算成点阵字体可用的字号：向下取到 12px 的整数倍。
 * 直接写 13.sp 这类值时，12px 网格会被缩放到非整数像素，边缘出现灰色插值，像素感就没了。
 */
@Composable
fun pixelFontSize(target: Dp): TextUnit {
    val density = LocalDensity.current
    return with(density) {
        val steps = (target.toPx() / PIXEL_FONT_GRID_PX).toInt().coerceAtLeast(1)
        (PIXEL_FONT_GRID_PX * steps).toSp()
    }
}

val Typography = Typography(
    displayLarge = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 36.sp
    ),
    headlineMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp
    ),
    titleLarge = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 18.sp,
        lineHeight = 24.sp
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 22.sp
    ),
    bodyLarge = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp
    ),
    bodyMedium = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    labelSmall = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = DesignToken.TypeSize.HeadlineSmall,
        lineHeight = (DesignToken.TypeSize.HeadlineSmall * 1.3),
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = DesignToken.TypeSize.BodySmall,
        lineHeight = (DesignToken.TypeSize.BodySmall * 1.5),
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = DesignToken.TypeSize.LabelLarge,
        lineHeight = (DesignToken.TypeSize.LabelLarge * 1.4),
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = DesignToken.TypeSize.LabelMedium,
        lineHeight = (DesignToken.TypeSize.LabelMedium * 1.4),
    ),
)
