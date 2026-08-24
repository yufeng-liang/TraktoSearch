package com.tracktosearch.ui.screen.ai

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.tracktosearch.data.ai.AiCharacter

/** 圆形裁切会削掉四角，内容留一圈边距，耳朵和脚才不会被切掉。 */
private const val GLYPH_CONTENT_FRACTION = 0.88f

/** 描边色取自现有立绘的深棕，兜底角色必须和立绘同一族，不能用纯黑。 */
private val FallbackOutline = Color(0xFF43302B)
private val FallbackBlush = Color(0xFFF7A9BA)
private val FallbackBlushStripe = Color(0xFFE07E96)
private val FallbackChestnut = Color(0xFF8A5A3C)

/**
 * 圆形角色头像。精灵中心的角色条、立绘台和标题栏入口按钮共用同一份。
 *
 * 已上线的三个角色（吉伊 / 小八 / 乌萨奇）直接用 [automaticSpriteArt] 里已核对过的立绘：
 * 手绘 Canvas 再怎么调参也不可能比原图更接近原作，旧版那种「一个纯色圆 + 两个点 + 一条横线」
 * 和角色形象基本无关，是这里最主要的问题。
 *
 * 剩下四个「准备中」角色没有立绘，才走 [drawFallbackCharacter]：按同一套造型规则画
 * 头身合一的圆胖轮廓、深棕粗描边、甜甜圈眼、ω 嘴和条纹腮红，只用耳朵区分种类。
 */
@Composable
fun AiCharacterGlyph(character: AiCharacter, modifier: Modifier = Modifier) {
    val tint = characterTint(character)
    val artRes = automaticSpriteArt(character.id)?.standbyRes
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(tint.copy(alpha = 0.22f)),
        contentAlignment = Alignment.Center
    ) {
        if (artRes != null) {
            Image(
                painter = painterResource(artRes),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(GLYPH_CONTENT_FRACTION),
                contentScale = ContentScale.Fit
            )
        } else {
            Canvas(Modifier.fillMaxSize(GLYPH_CONTENT_FRACTION)) {
                drawFallbackCharacter(character.id, tint)
            }
        }
    }
}

/** 角色主色，同时用于头像底色、选中态和立绘台背景。 */
internal fun characterTint(character: AiCharacter): Color = when (character.id) {
    "chiikawa" -> Color(0xFFFFC8D8)
    "hachiware" -> Color(0xFF9DD8F2)
    "usagi" -> Color(0xFFFFD66B)
    "momonga", "flying-squirrel" -> Color(0xFFD3B3F3)
    "shisa" -> Color(0xFFFFAA80)
    "kurimanju" -> Color(0xFFB68C69)
    else -> Color(0xFFAED9C2)
}

private enum class FallbackEar { POINTED, ROUND, NONE }

/**
 * 兜底角色的造型参数。所有比例都是画布最短边的倍数，因此 28dp 的入口按钮和 168dp 的
 * 立绘台用的是同一份形状，不会在小尺寸下走形。
 */
private data class FallbackShape(
    val ear: FallbackEar,
    /** 耳朵中心到身体中轴的横向距离。 */
    val earSpread: Float = 0.21f,
    val earWidth: Float = 0.20f,
    /** 耳尖露出身体轮廓之外的高度。 */
    val earHeight: Float = 0.11f,
    /** 栗子馒头没有耳朵，头顶是一撮栗子尖。 */
    val topKnot: Boolean = false
)

private fun fallbackShape(characterId: String): FallbackShape = when (characterId) {
    // 飞鼠：圆耳朵、位置偏外
    "momonga", "flying-squirrel" -> FallbackShape(FallbackEar.ROUND, earSpread = 0.24f, earWidth = 0.17f, earHeight = 0.10f)
    // 狮萨：尖耳朵、更靠内也更高；耳尖再高描边就会顶到画布上沿
    "shisa" -> FallbackShape(FallbackEar.POINTED, earSpread = 0.19f, earWidth = 0.21f, earHeight = 0.115f)
    "kurimanju" -> FallbackShape(FallbackEar.NONE, topKnot = true)
    // 獭师：小圆耳朵贴在头顶两侧
    else -> FallbackShape(FallbackEar.ROUND, earSpread = 0.26f, earWidth = 0.14f, earHeight = 0.085f)
}

/** 身体轮廓的上下边界，耳朵和脚都以此为基准往外探。 */
private const val BODY_TOP = 0.155f
private const val BODY_BOTTOM = 0.845f
private const val EAR_BASE_Y = 0.30f

/**
 * 身体、耳朵、脚合成一条闭合路径再统一描边。
 *
 * 必须做并集：分开画的话身体填充会盖住耳朵下半段的描边，而身体自己的描边又会在耳根处
 * 横切一道线，看着像贴上去的三角形，而不是原作那种头身一体的轮廓。
 */
private fun fallbackSilhouette(shape: FallbackShape, cx: Float, u: Float): Path {
    val body = Path().apply {
        addRoundRect(
            RoundRect(
                Rect(cx - 0.355f * u, BODY_TOP * u, cx + 0.355f * u, BODY_BOTTOM * u),
                CornerRadius(0.33f * u)
            )
        )
    }
    val extras = Path()
    // 脚：两个小圆从身体底部探出，中间自然留出一道缺口
    listOf(-0.135f, 0.135f).forEach { dx ->
        extras.addOval(Rect(Offset(cx + dx * u, 0.862f * u), 0.055f * u))
    }
    val tipY = (BODY_TOP - shape.earHeight) * u
    listOf(-1f, 1f).forEach { side ->
        val earX = cx + side * shape.earSpread * u
        when (shape.ear) {
            FallbackEar.ROUND -> extras.addOval(
                Rect(Offset(earX, tipY + shape.earWidth * u / 2f), shape.earWidth * u / 2f)
            )
            FallbackEar.POINTED -> extras.apply {
                moveTo(earX - shape.earWidth * u / 2f, EAR_BASE_Y * u)
                lineTo(earX, tipY)
                lineTo(earX + shape.earWidth * u / 2f, EAR_BASE_Y * u)
                close()
            }
            FallbackEar.NONE -> Unit
        }
    }
    return Path().apply { op(body, extras, PathOperation.Union) }
}

private fun DrawScope.drawFallbackCharacter(characterId: String, tint: Color) {
    val u = size.minDimension
    val cx = size.width / 2f
    val shape = fallbackShape(characterId)
    // 立绘的身体接近纯白，主色只用来带一点色偏；整块铺主色就退回旧版那张"色块脸"了
    val bodyColor = lerp(Color.White, tint, 0.20f)
    val outline = Stroke(width = u * 0.036f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    val silhouette = fallbackSilhouette(shape, cx, u)
    drawPath(silhouette, bodyColor, style = Fill)
    drawPath(silhouette, FallbackOutline, style = outline)
    if (shape.topKnot) {
        val knot = Path().apply { addOval(Rect(Offset(cx, 0.145f * u), 0.062f * u)) }
        drawPath(knot, FallbackChestnut, style = Fill)
        drawPath(knot, FallbackOutline, style = outline)
    }
    drawFallbackFace(cx, u)
}

/** 眉毛、甜甜圈眼、ω 嘴和条纹腮红：这四样是原作最容易被认出来的特征。 */
private fun DrawScope.drawFallbackFace(cx: Float, u: Float) {
    val thin = Stroke(width = u * 0.020f, cap = StrokeCap.Round)
    listOf(-1f, 1f).forEach { side ->
        val brow = Path().apply {
            moveTo(cx + side * 0.225f * u, 0.400f * u)
            quadraticTo(cx + side * 0.160f * u, 0.348f * u, cx + side * 0.095f * u, 0.388f * u)
        }
        drawPath(brow, FallbackOutline, style = thin)
    }
    // 眼睛是三层同心圆：深色外圈 + 白色内圈 + 深色瞳孔，而不是一个实心点
    listOf(-1f, 1f).forEach { side ->
        val center = Offset(cx + side * 0.155f * u, 0.505f * u)
        drawCircle(FallbackOutline, 0.078f * u, center)
        drawCircle(Color.White, 0.046f * u, center)
        drawCircle(FallbackOutline, 0.024f * u, center.copy(y = center.y + 0.006f * u))
    }
    val nose = Path().apply {
        moveTo(cx - 0.028f * u, 0.585f * u)
        lineTo(cx + 0.028f * u, 0.585f * u)
        lineTo(cx, 0.618f * u)
        close()
    }
    drawPath(nose, FallbackOutline, style = Fill)
    listOf(-1f, 1f).forEach { side ->
        val mouth = Path().apply {
            moveTo(cx, 0.616f * u)
            quadraticTo(cx + side * 0.042f * u, 0.672f * u, cx + side * 0.072f * u, 0.626f * u)
        }
        drawPath(mouth, FallbackOutline, style = Stroke(width = u * 0.018f, cap = StrokeCap.Round))
    }
    listOf(-1f, 1f).forEach { side ->
        val bcx = cx + side * 0.245f * u
        val bcy = 0.578f * u
        drawRoundRect(
            color = FallbackBlush,
            topLeft = Offset(bcx - 0.0725f * u, bcy - 0.043f * u),
            size = Size(0.145f * u, 0.086f * u),
            cornerRadius = CornerRadius(0.043f * u)
        )
        (-1..1).forEach { i ->
            val sx = bcx + i * 0.036f * u
            drawLine(
                color = FallbackBlushStripe,
                start = Offset(sx + 0.008f * u, bcy - 0.026f * u),
                end = Offset(sx - 0.008f * u, bcy + 0.026f * u),
                strokeWidth = u * 0.013f,
                cap = StrokeCap.Round
            )
        }
    }
}
