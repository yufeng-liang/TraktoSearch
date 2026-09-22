package com.tracktosearch.ui.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 统一设计 Token，消除全 App 硬编码散落。
 * 固定色（颜色值）在 Color.kt，本文件只管形状/尺寸/字体。
 */
object DesignToken {

    // ====== 圆角 ======
    val Card = RoundedCornerShape(12.dp)
    val Hero = RoundedCornerShape(20.dp)
    val Tag = RoundedCornerShape(20.dp)
    val SearchBar = RoundedCornerShape(28.dp)
    val SearchInput = RoundedCornerShape(24.dp)
    val Badge = RoundedCornerShape(4.dp)
    val Dialog = RoundedCornerShape(28.dp)
    val FloatingButton = CircleShape

    // ====== 间距 ======
    val ScreenHorizontal = 16.dp
    val SectionGap = 24.dp
    val ItemGap = 8.dp
    val CardPadding = 12.dp
    val ChipPaddingH = 14.dp
    val ChipPaddingV = 6.dp
    val ChipGap = 8.dp

    // ====== 弹窗与浮层（仅 AppDialog.kt 使用，别处不要引用） ======
    val DialogPadding = 24.dp
    val DialogActionGap = 12.dp
    val DialogActionMinHeight = 44.dp
    val DialogContentMaxHeight = 400.dp

    // ====== 阴影 ======
    val ElevationCard = 2.dp
    val ElevationPoster = 8.dp
    val ElevationFloating = 12.dp

    // ====== 字体大小 ======
    object TypeSize {
        val DisplayLarge = 28.sp
        val HeadlineMedium = 22.sp
        val HeadlineSmall = 20.sp
        val TitleLarge = 18.sp
        val TitleMedium = 16.sp
        val BodyLarge = 16.sp
        val BodyMedium = 14.sp
        val BodySmall = 12.sp
        val LabelLarge = 14.sp
        val LabelMedium = 12.sp
        val LabelSmall = 11.sp
    }

    // ====== 动效 ======
    const val PressScale = 0.96f
    const val PressScaleDuration = 100
    const val FadeInDuration = 200
}
