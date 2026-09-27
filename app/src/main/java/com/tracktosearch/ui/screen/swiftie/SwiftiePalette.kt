package com.tracktosearch.ui.screen.swiftie

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import com.tracktosearch.R

/**
 * 霉粉彩蛋取色的唯一来源。灯箱是「一张复刻图」，这些值**不随深色模式变化**。
 */
object SwiftiePalette {
    /** 闪粉玫红。仅用于无对比度要求的大字号图形——对白字只有 3.98:1。 */
    val Glitter = Color(0xFFE83A72)

    /** 箔面高光档，配合 [Glitter] 做渐变。 */
    val GlitterLight = Color(0xFFFF6E96)

    /** 箔面暗部档。 */
    val GlitterDeep = Color(0xFFC42356)

    /** 承载白色文字的填充块专用，对白字 5.04:1，合规。 */
    val Badge = Color(0xFFD0295F)

    /** 标题 / 未知数 X / 落款 */
    val RoyalBlue = Color(0xFF1E3FC4)

    val SkyBlue = Color(0xFF7EC8E8)
    val CloudPink = Color(0xFFF4A6C8)
    val Lavender = Color(0xFFC9A8DE)
    val PeachYellow = Color(0xFFF7D89B)
    val PinkWhite = Color(0xFFFBE4EE)
}

object SwiftieFonts {
    /**
     * 落款签名：Pacifico，粗圆花体。只子集化了 `Taylor Swift` 与那句
     * [FINALE_TAGLINE] 用到的字形 —— 拿它排别的字（比如用户昵称）会得到豆腐块。
     *
     * 重新子集化之后如果字形轮廓有变，必须重跑 `scripts/build-swiftie-signature-path.py`：
     * [SwiftieSignaturePath] 的中线是按这份轮廓量出来的。
     *
     * 出题页那句「Congrats on Forever!」**不用它** —— 那是从原图描出来的矢量
     * （见 [SwiftieCongratsPath]），原作者用的 Filmotype LaCrosse 是商业字体、
     * 字体文件不能随包。
     */
    val Script = FontFamily(Font(R.font.swiftie_script))

    /**
     * 算式、键盘数字：Honey Script SemiBold。
     *
     * 这就是原海报「13 + 87 = 100」用的字体，连那个卷曲的加号都对得上。
     * 键盘也用它，是为了让按下去的那个字形和海报上长出来的那个字形是同一个。
     */
    val Marker = FontFamily(Font(R.font.swiftie_honey))
}
