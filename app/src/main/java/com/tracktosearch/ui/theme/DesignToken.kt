package com.tracktosearch.ui.theme

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
    val Badge = RoundedCornerShape(4.dp)
    val Dialog = RoundedCornerShape(28.dp)

    // ====== 间距 ======
    val ChipPaddingH = 14.dp
    val ChipPaddingV = 6.dp

    // ====== 弹窗与浮层（仅 AppDialog.kt 使用，别处不要引用） ======
    val DialogPadding = 24.dp
    val DialogActionGap = 12.dp
    val DialogActionMinHeight = 44.dp
    val DialogContentMaxHeight = 400.dp

    // ====== 阴影 ======
    val ElevationFloating = 12.dp

    // ====== 字体大小 ======
    object TypeSize {
        val HeadlineSmall = 20.sp
        val BodySmall = 12.sp
        val LabelLarge = 14.sp
        val LabelMedium = 12.sp
    }
}
