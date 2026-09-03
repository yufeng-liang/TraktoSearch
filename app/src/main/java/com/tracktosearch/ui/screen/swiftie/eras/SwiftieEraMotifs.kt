package com.tracktosearch.ui.screen.swiftie.eras

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

private const val TAU = 2f * PI.toFloat()

/** 母题整体的不透明度上限。再高就压过曲目名了。 */
private const val MOTIF_ALPHA = 0.20f

/**
 * 卡片上那层「光」：一束斜射进来的光晕 + 细颗粒。
 *
 * 12 个母题各画自己的元素，氛围靠这一层统一 —— 有了它卡片才像一个被照亮的场景，
 * 而不是一张贴了图案的纸。
 *
 * **只用白色**。文字是深色压在浅底上，提亮底色是提高对比度，压暗才会把
 * [SwiftieEraContrast] 那套「底色取最暗」的模型顶穿。所以这里没有暗角，
 * 纵深靠亮部的位置差做出来。
 *
 * @param phase 与母题同一个相位源，光晕随它极慢地漂
 * @param lowRam true 时颗粒减半
 */
fun DrawScope.drawEraAtmosphere(phase: Float, lowRam: Boolean) {
    // 主光：左上角斜射进来的一大团。半径比卡片还大，落在卡片里的只是它的一角，
    // 边界因此永远看不见 —— 看得见边界的光晕就是一个圆，不是光
    val drift = sin(phase * TAU) * 0.04f
    val keyCenter = Offset(size.width * (0.18f + drift), -size.height * 0.10f)
    val keyRadius = size.minDimension * 1.15f
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(Color.White.copy(alpha = 0.26f), Color.Transparent),
            center = keyCenter,
            radius = keyRadius
        ),
        radius = keyRadius,
        center = keyCenter
    )
    // 补光：右下角一小团，把主光照不到的那半边从「暗」拉回「远」
    val fillCenter = Offset(size.width * 0.92f, size.height * 0.88f)
    val fillRadius = size.minDimension * 0.55f
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(Color.White.copy(alpha = 0.14f), Color.Transparent),
            center = fillCenter,
            radius = fillRadius
        ),
        radius = fillRadius,
        center = fillCenter
    )
    // 颗粒：固定种子的细白点，密而淡。它不是「星星」，是空气里的浮尘 ——
    // 一整块纯色渐变看着像 PS 图层，撒上浮尘才像拍出来的
    val random = Random(1989)
    repeat(if (lowRam) 22 else 54) {
        val x = random.nextFloat() * size.width
        val y = random.nextFloat() * size.height
        val twinkle = 0.55f + 0.45f * sin((phase + random.nextFloat()) * TAU)
        drawCircle(
            color = Color.White,
            radius = size.minDimension * 0.0035f * (0.7f + random.nextFloat() * 0.8f),
            center = Offset(x, y),
            alpha = 0.30f * twinkle
        )
    }
}

/** 四芒星：两条主轴 + 两条短斜轴。比画多边形便宜，也比一个圆点更像「闪」。 */
private fun DrawScope.drawSparkleCross(
    center: Offset,
    arm: Float,
    color: Color,
    alpha: Float,
    width: Float
) {
    drawLine(color, center - Offset(arm, 0f), center + Offset(arm, 0f), width, alpha = alpha)
    drawLine(color, center - Offset(0f, arm), center + Offset(0f, arm), width, alpha = alpha)
    val diag = arm * 0.42f
    drawLine(
        color, center - Offset(diag, diag), center + Offset(diag, diag),
        width * 0.7f, alpha = alpha * 0.7f
    )
    drawLine(
        color, center - Offset(diag, -diag), center + Offset(diag, -diag),
        width * 0.7f, alpha = alpha * 0.7f
    )
}

/**
 * 画某个时代的视觉母题。
 *
 * 每帧重跑一遍（卡片的 `drawBehind` 每帧失效），所以循环里的 `Path` 一律
 * **建一个反复 `rewind()`**，而不是每次迭代 new 一个 —— Red 一帧 14 条、
 * folklore 11 棵、Lover 7 颗，攒起来就是 96 秒的 GC 抖动。
 *
 * @param phase 0f..1f 循环相位，由卡片按固定周期喂进来。低端机恒为 0f（整块定格）
 * @param lowRam true 时点阵与分形再减半（Spec §11.2）。**只有 3 个母题看这个参数** ——
 *   低端机的省电靠卡片那边「不读时钟」把整块母题定住，比在 12 个函数里各写一条
 *   低端分支干净得多；这里剩下的 lowRam 只是顺手把一次性的点数也压掉
 */
fun DrawScope.drawEraMotif(
    motif: SwiftieEraMotif,
    color: Color,
    phase: Float,
    lowRam: Boolean
) {
    when (motif) {
        SwiftieEraMotif.WATERCOLOR_STARS -> drawWatercolorStars(color, phase, lowRam)
        SwiftieEraMotif.GOLDEN_SWIRL -> drawGoldenSwirl(color, phase)
        SwiftieEraMotif.PURPLE_VEIL -> drawPurpleVeil(color, phase)
        SwiftieEraMotif.KNIT_STRIPES -> drawKnitStripes(color, phase)
        SwiftieEraMotif.POLAROID -> drawPolaroid(color, phase)
        SwiftieEraMotif.HALFTONE_SNAKE -> drawHalftoneSnake(color, phase, lowRam)
        SwiftieEraMotif.PINK_CLOUD_HEART -> drawPinkCloudHeart(color, phase)
        SwiftieEraMotif.PINE_FOG -> drawPineFog(color, phase)
        SwiftieEraMotif.BRAID_BRANCH -> drawBraidBranch(color, phase, lowRam)
        SwiftieEraMotif.STARBURST_FLAME -> drawStarburstFlame(color, phase)
        SwiftieEraMotif.TYPEWRITER_PAPER -> drawTypewriterPaper(phase)
        SwiftieEraMotif.SPOTLIGHT_FEATHER -> drawSpotlightFeather(color, phase)
    }
}

/** 1 · Taylor Swift：青绿水彩晕染 + 细碎星点 + 花体卷须。 */
private fun DrawScope.drawWatercolorStars(color: Color, phase: Float, lowRam: Boolean) {
    listOf(0.22f to 0.30f, 0.72f to 0.55f, 0.45f to 0.80f).forEachIndexed { index, (cx, cy) ->
        val drift = sin((phase + index * 0.33f) * TAU) * 0.02f
        val center = Offset((cx + drift) * size.width, cy * size.height)
        val radius = size.minDimension * (0.30f + index * 0.05f)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(color.copy(alpha = MOTIF_ALPHA), Color.Transparent),
                center = center,
                radius = radius
            ),
            radius = radius,
            center = center
        )
        // 晕染边缘那道积色的圈。水彩干掉之后颜料会往边界堆，少了它三团只是柔光斑
        drawCircle(
            color = color,
            radius = radius * 0.78f,
            center = center,
            alpha = MOTIF_ALPHA * 0.55f,
            style = Stroke(width = size.minDimension * 0.005f)
        )
    }
    // 花体卷须：三条从左下卷上来的细线，呼应首专封面那手花体签名
    val curl = Path()
    repeat(3) { index ->
        val sway = sin((phase + index * 0.3f) * TAU) * size.width * 0.02f
        val baseX = size.width * (0.10f + index * 0.30f)
        val baseY = size.height * (0.96f - index * 0.06f)
        val span = size.height * (0.26f + index * 0.05f)
        curl.rewind()
        curl.moveTo(baseX, baseY)
        curl.cubicTo(
            baseX - span * 0.55f + sway, baseY - span * 0.35f,
            baseX + span * 0.60f - sway, baseY - span * 0.70f,
            baseX + span * 0.12f, baseY - span
        )
        drawPath(
            path = curl,
            color = color,
            alpha = MOTIF_ALPHA * 1.2f,
            style = Stroke(width = size.minDimension * 0.006f, cap = StrokeCap.Round)
        )
    }
    val random = Random(2006)
    val starCount = if (lowRam) 12 else 26
    repeat(starCount) { index ->
        val x = random.nextFloat() * size.width
        val y = random.nextFloat() * size.height
        val twinkle = sin((phase + random.nextFloat()) * TAU) * 0.5f + 0.5f
        // 每四颗里挑一颗画成四芒星，其余留作细点 —— 全画成星会把「细碎」变成「满天」
        if (index % 4 == 0) {
            drawSparkleCross(
                center = Offset(x, y),
                arm = size.minDimension * 0.022f * (0.7f + twinkle),
                color = Color.White,
                alpha = 0.42f * twinkle,
                width = size.minDimension * 0.0028f
            )
        } else {
            drawCircle(
                color = Color.White,
                radius = size.minDimension * 0.004f * (0.6f + twinkle),
                center = Offset(x, y),
                alpha = 0.35f * twinkle
            )
        }
    }
}

/** 2 · Fearless：金色旋转光晕 + 甩动的长发弧线 + 金尘。 */
private fun DrawScope.drawGoldenSwirl(color: Color, phase: Float) {
    val center = Offset(size.width * 0.68f, size.height * 0.38f)
    // 旋涡中心的暖光。旋转的弧线本身没有中心，加一团光它才有轴
    val glow = size.minDimension * 0.42f
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(Color.White.copy(alpha = 0.30f), Color.Transparent),
            center = center,
            radius = glow
        ),
        radius = glow,
        center = center
    )
    rotate(degrees = phase * 360f, pivot = center) {
        repeat(6) { ring ->
            val radius = size.minDimension * (0.12f + ring * 0.075f)
            drawArc(
                color = color,
                startAngle = ring * 40f,
                sweepAngle = 210f - ring * 22f,
                useCenter = false,
                topLeft = Offset(center.x - radius, center.y - radius),
                size = Size(radius * 2f, radius * 2f),
                alpha = MOTIF_ALPHA * (1f - ring * 0.11f),
                style = Stroke(
                    width = size.minDimension * (0.014f - ring * 0.0015f),
                    cap = StrokeCap.Round
                )
            )
        }
    }
    // 五道长发弧线，粗细与相位各自错开，像被风甩起来；第三道用白色当高光
    val path = Path()
    repeat(5) { strand ->
        val sway = sin((phase + strand * 0.19f) * TAU) * size.width * 0.055f
        path.rewind()
        path.moveTo(size.width * 0.06f, size.height * (0.14f + strand * 0.055f))
        path.cubicTo(
            size.width * 0.35f + sway, size.height * (0.04f + strand * 0.075f),
            size.width * 0.55f - sway, size.height * (0.78f - strand * 0.05f),
            size.width * 0.94f, size.height * (0.50f + strand * 0.085f)
        )
        val highlight = strand == 2
        drawPath(
            path = path,
            color = if (highlight) Color.White else color,
            alpha = if (highlight) 0.28f else MOTIF_ALPHA * (0.9f - strand * 0.06f),
            style = Stroke(
                width = size.minDimension * (0.010f - strand * 0.0012f),
                cap = StrokeCap.Round
            )
        )
    }
    // 金尘：绕着旋涡飘的碎光，越靠外越淡
    val random = Random(2008)
    repeat(20) {
        val angle = random.nextFloat() * TAU
        val dist = size.minDimension * (0.16f + random.nextFloat() * 0.42f)
        val twinkle = 0.5f + 0.5f * sin((phase + random.nextFloat()) * TAU)
        drawCircle(
            color = Color.White,
            radius = size.minDimension * 0.0045f * (0.6f + twinkle),
            center = center + Offset(cos(angle) * dist, sin(angle) * dist * 0.8f),
            alpha = 0.38f * twinkle
        )
    }
}

/** 3 · Speak Now：紫色薄纱正弦波 + 裙裾弧线 + 波峰上的碎光。 */
private fun DrawScope.drawPurpleVeil(color: Color, phase: Float) {
    val path = Path()
    // 五层不同振幅 / 基线，纱才有层数；三层看着像三条波浪线
    repeat(5) { layer ->
        val amplitude = size.height * (0.045f + layer * 0.022f)
        val baseline = size.height * (0.22f + layer * 0.155f)
        val shift = (phase + layer * 0.21f) * TAU
        path.rewind()
        path.moveTo(0f, baseline)
        // 48 段折线足够平滑，又不用碰贝塞尔的命名分歧
        for (step in 1..48) {
            val x = size.width * step / 48f
            val y = baseline + sin(step / 48f * TAU * (1.4f + layer * 0.15f) + shift) * amplitude
            path.lineTo(x, y)
        }
        path.lineTo(size.width, size.height)
        path.lineTo(0f, size.height)
        path.close()
        drawPath(path = path, color = color, alpha = MOTIF_ALPHA * 0.34f)
        // 每层纱的上缘描一道亮边，层与层之间才分得开
        path.rewind()
        path.moveTo(0f, baseline)
        for (step in 1..48) {
            val x = size.width * step / 48f
            val y = baseline + sin(step / 48f * TAU * (1.4f + layer * 0.15f) + shift) * amplitude
            path.lineTo(x, y)
        }
        drawPath(
            path = path,
            color = Color.White,
            alpha = 0.16f,
            style = Stroke(width = size.minDimension * 0.004f)
        )
    }
    // 裙裾：一条从右上扫到左下的大弧，Speak Now 那件紫裙的裙摆
    val hem = Path().apply {
        moveTo(size.width * 1.02f, size.height * 0.06f)
        cubicTo(
            size.width * 0.62f, size.height * 0.20f,
            size.width * 0.48f, size.height * 0.52f,
            size.width * 0.04f, size.height * 0.44f
        )
    }
    drawPath(
        path = hem,
        color = color,
        alpha = MOTIF_ALPHA * 1.5f,
        style = Stroke(width = size.minDimension * 0.009f, cap = StrokeCap.Round)
    )
    // 波峰碎光：定点撒在最上面那层纱的峰上，随相位一起走
    val random = Random(2010)
    repeat(14) {
        val t = random.nextFloat()
        val x = size.width * t
        val y = size.height * 0.22f + sin(t * TAU * 1.4f + phase * TAU) * size.height * 0.045f
        val twinkle = 0.5f + 0.5f * sin((phase + random.nextFloat()) * TAU)
        drawCircle(
            color = Color.White,
            radius = size.minDimension * 0.004f * (0.6f + twinkle),
            center = Offset(x, y),
            alpha = 0.40f * twinkle
        )
    }
}

/** 4 · Red：红色针织横纹 + 上下针的珠圈 + 一条散开的毛线与围巾流苏。 */
private fun DrawScope.drawKnitStripes(color: Color, phase: Float) {
    val rows = 14
    val rowHeight = size.height / rows
    val stitch = size.width / 22f
    val path = Path()
    repeat(rows) { row ->
        val y = row * rowHeight + rowHeight * 0.5f
        // 逐行反向偏移，纹路错开才像织物而不是条形码
        val offset = if (row % 2 == 0) stitch * 0.5f else 0f
        val drift = sin((phase + row * 0.08f) * TAU) * stitch * 0.15f
        path.rewind()
        var x = -stitch + offset + drift
        path.moveTo(x, y)
        while (x < size.width + stitch) {
            path.lineTo(x + stitch * 0.5f, y - rowHeight * 0.30f)
            path.lineTo(x + stitch, y)
            x += stitch
        }
        drawPath(
            path = path,
            color = color,
            alpha = MOTIF_ALPHA * if (row % 2 == 0) 1f else 0.6f,
            style = Stroke(width = rowHeight * 0.18f)
        )
        // 反面的上下针：每个 V 谷底压一个小珠。少了它就只是一排折线，
        // 有了它才看得出是一针一针挑上去的
        if (row % 2 == 0) {
            var bead = -stitch + offset + drift + stitch * 0.5f
            while (bead < size.width + stitch) {
                drawCircle(
                    color = color,
                    radius = rowHeight * 0.10f,
                    center = Offset(bead, y - rowHeight * 0.30f),
                    alpha = MOTIF_ALPHA * 0.9f
                )
                bead += stitch
            }
        }
    }
    // 散开的那根毛线：从右上角垂下来绕一圈，是「围巾松了一线」而不是又一道纹
    val loose = Path().apply {
        moveTo(size.width * 0.96f, size.height * 0.06f)
        cubicTo(
            size.width * 0.58f, size.height * 0.16f,
            size.width * 0.86f, size.height * 0.48f,
            size.width * 0.46f, size.height * 0.56f
        )
        cubicTo(
            size.width * 0.18f, size.height * 0.62f,
            size.width * 0.30f, size.height * 0.88f,
            size.width * 0.08f, size.height * 0.90f
        )
    }
    drawPath(
        path = loose,
        color = color,
        alpha = MOTIF_ALPHA * 1.5f,
        style = Stroke(width = size.minDimension * 0.010f, cap = StrokeCap.Round)
    )
    // 底边流苏：长短不一的短线，围巾的下摆
    repeat(16) { index ->
        val x = (index + 0.5f) / 16f * size.width
        val length = rowHeight * (0.55f + ((index * 37) % 11) / 11f * 0.8f)
        val swing = sin((phase + index * 0.11f) * TAU) * size.width * 0.006f
        drawLine(
            color = color,
            start = Offset(x, size.height - length),
            end = Offset(x + swing, size.height),
            strokeWidth = size.minDimension * 0.006f,
            cap = StrokeCap.Round,
            alpha = MOTIF_ALPHA * 1.2f
        )
    }
}

/** 5 · 1989：三张叠着的宝丽来 + 框内海平线与海鸥 + 白边上的手写。 */
private fun DrawScope.drawPolaroid(color: Color, phase: Float) {
    val frameWidth = size.width * 0.46f
    val frameHeight = frameWidth * 1.20f
    val center = Offset(size.width * 0.70f, size.height * 0.42f)
    // 后面两张压在下面，只露出一角 —— 一叠照片才有「翻旧照」的意思
    listOf(11f to 0.30f, -16f to 0.40f).forEachIndexed { index, (deg, alpha) ->
        rotate(degrees = deg + sin((phase + index * 0.4f) * TAU) * 1.2f, pivot = center) {
            translate(left = center.x - frameWidth / 2f, top = center.y - frameHeight / 2f) {
                drawRect(
                    color = Color.White,
                    size = Size(frameWidth, frameHeight),
                    alpha = alpha
                )
                drawRect(
                    color = color,
                    topLeft = Offset(frameWidth * 0.07f, frameWidth * 0.07f),
                    size = Size(frameWidth * 0.86f, frameHeight * 0.71f),
                    alpha = MOTIF_ALPHA * 0.7f
                )
            }
        }
    }
    val tilt = -7f + sin(phase * TAU) * 1.5f
    rotate(degrees = tilt, pivot = center) {
        translate(left = center.x - frameWidth / 2f, top = center.y - frameHeight / 2f) {
            drawRect(
                color = Color.White,
                size = Size(frameWidth, frameHeight),
                alpha = 0.55f
            )
            // 相纸下缘的宽白边是宝丽来的辨识点
            val photo = Rect(
                left = frameWidth * 0.07f,
                top = frameWidth * 0.07f,
                right = frameWidth * 0.93f,
                bottom = frameHeight * 0.78f
            )
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(color.copy(alpha = 0.75f), Color.White.copy(alpha = 0.30f)),
                    startY = photo.top,
                    endY = photo.bottom
                ),
                topLeft = Offset(photo.left, photo.top),
                size = Size(photo.width, photo.height),
                alpha = 0.60f
            )
            // 框内的海平线：1989 是那张海边的宝丽来，一条水平线就把「空」变成「景」
            val horizonY = photo.top + photo.height * 0.62f
            drawLine(
                color = color,
                start = Offset(photo.left, horizonY),
                end = Offset(photo.right, horizonY),
                strokeWidth = frameWidth * 0.010f,
                alpha = 0.45f
            )
            // 三只海鸥，两笔一只
            listOf(0.26f to 0.28f, 0.48f to 0.19f, 0.68f to 0.33f).forEach { (gx, gy) ->
                val gullX = photo.left + photo.width * gx
                val gullY = photo.top + photo.height * gy
                val wing = frameWidth * 0.045f
                drawLine(
                    color = color,
                    start = Offset(gullX - wing, gullY + wing * 0.5f),
                    end = Offset(gullX, gullY),
                    strokeWidth = frameWidth * 0.008f,
                    alpha = 0.50f
                )
                drawLine(
                    color = color,
                    start = Offset(gullX, gullY),
                    end = Offset(gullX + wing, gullY + wing * 0.5f),
                    strokeWidth = frameWidth * 0.008f,
                    alpha = 0.50f
                )
            }
            // 白边上那行手写：宝丽来的白边就是用来写字的，一道连绵的波浪足够表示
            val inkY = frameHeight * 0.88f
            val step = frameWidth * 0.08f
            val scribble = Path().apply {
                var x = frameWidth * 0.14f
                moveTo(x, inkY)
                while (x < frameWidth * 0.78f) {
                    cubicTo(
                        x + step * 0.30f, inkY - frameHeight * 0.026f,
                        x + step * 0.70f, inkY + frameHeight * 0.014f,
                        x + step, inkY
                    )
                    x += step
                }
            }
            drawPath(
                path = scribble,
                color = color,
                alpha = 0.40f,
                style = Stroke(width = frameWidth * 0.007f, cap = StrokeCap.Round)
            )
        }
    }
}

/** 6 · reputation：报纸半调网点 + 排版栏 + 带鳞的蛇。 */
private fun DrawScope.drawHalftoneSnake(color: Color, phase: Float, lowRam: Boolean) {
    val step = size.minDimension / if (lowRam) 12f else 20f
    var y = step * 0.5f
    while (y < size.height) {
        var x = step * 0.5f
        while (x < size.width) {
            // 点半径随位置渐变，做出印刷网点的疏密。低端机不随相位动
            val wave = if (lowRam) 0f else sin((x / size.width + phase) * TAU) * 0.25f
            val ratio = (x / size.width) * 0.5f + (y / size.height) * 0.5f + wave
            drawCircle(
                color = color,
                radius = step * 0.36f * ratio.coerceIn(0.05f, 1f),
                center = Offset(x, y),
                alpha = MOTIF_ALPHA
            )
            x += step
        }
        y += step
    }
    // 报纸的版面：一块粗体大标题 + 两栏细排文字。reputation 整张封面就是压在
    // 一叠报纸头条上的，只有网点没有栏线时那只是一层纹理
    drawRect(
        color = color,
        topLeft = Offset(size.width * 0.08f, size.height * 0.10f),
        size = Size(size.width * 0.52f, size.minDimension * 0.030f),
        alpha = MOTIF_ALPHA * 1.5f
    )
    drawRect(
        color = color,
        topLeft = Offset(size.width * 0.08f, size.height * 0.10f + size.minDimension * 0.048f),
        size = Size(size.width * 0.34f, size.minDimension * 0.020f),
        alpha = MOTIF_ALPHA * 1.2f
    )
    repeat(2) { column ->
        val left = size.width * (0.08f + column * 0.30f)
        repeat(7) { line ->
            val lineY = size.height * 0.22f + line * size.minDimension * 0.026f
            // 每行长度按一个固定序列变化，读起来才像排好的字而不是等长横线
            val ratio = 0.62f + ((line * 5 + column * 3) % 7) / 7f * 0.36f
            drawRect(
                color = color,
                topLeft = Offset(left, lineY),
                size = Size(size.width * 0.26f * ratio, size.minDimension * 0.008f),
                alpha = MOTIF_ALPHA * 0.85f
            )
        }
    }
    // 蛇改成可采样的参数曲线：正弦调制的横向走向。要在身上排鳞，就必须能取到
    // 每一点的坐标与切线，贝塞尔控制点给不了这个
    val snakeShift = if (lowRam) 0f else sin(phase * TAU)
    val segments = if (lowRam) 24 else 48
    fun snakeAt(t: Float): Offset = Offset(
        x = size.width * (0.06f + t * 0.90f),
        y = size.height * (0.62f + 0.26f * sin(t * TAU * 1.15f + snakeShift * 0.5f))
    )
    val snake = Path().apply {
        moveTo(snakeAt(0f).x, snakeAt(0f).y)
        for (i in 1..segments) lineTo(snakeAt(i / segments.toFloat()).x, snakeAt(i / segments.toFloat()).y)
    }
    val bodyWidth = size.minDimension * 0.020f
    drawPath(
        path = snake,
        color = color,
        alpha = MOTIF_ALPHA * 1.5f,
        style = Stroke(width = bodyWidth, cap = StrokeCap.Round)
    )
    // 鳞：沿身子每隔几段压一道短横，垂直于走向
    for (i in 2..segments - 2 step 2) {
        val t = i / segments.toFloat()
        val here = snakeAt(t)
        val next = snakeAt((i + 1) / segments.toFloat())
        val dx = next.x - here.x
        val dy = next.y - here.y
        val len = kotlin.math.sqrt(dx * dx + dy * dy).coerceAtLeast(0.001f)
        val nx = -dy / len * bodyWidth * 0.42f
        val ny = dx / len * bodyWidth * 0.42f
        drawLine(
            color = Color.White,
            start = Offset(here.x - nx, here.y - ny),
            end = Offset(here.x + nx, here.y + ny),
            strokeWidth = size.minDimension * 0.0035f,
            alpha = 0.26f
        )
    }
    // 头与分叉的舌头：蛇之所以是蛇，一半靠这两笔
    val head = snakeAt(1f)
    drawCircle(
        color = color,
        radius = bodyWidth * 0.85f,
        center = head,
        alpha = MOTIF_ALPHA * 1.6f
    )
    val tongue = bodyWidth * 1.5f
    drawLine(
        color = color,
        start = head,
        end = Offset(head.x + tongue, head.y - tongue * 0.35f),
        strokeWidth = size.minDimension * 0.0035f,
        alpha = MOTIF_ALPHA * 1.8f
    )
    drawLine(
        color = color,
        start = head,
        end = Offset(head.x + tongue, head.y + tongue * 0.35f),
        strokeWidth = size.minDimension * 0.0035f,
        alpha = MOTIF_ALPHA * 1.8f
    )
}

/** 7 · Lover：粉蓝云 + 彩虹 + 蝴蝶 + 上浮的亮粉心。 */
private fun DrawScope.drawPinkCloudHeart(color: Color, phase: Float) {
    // 彩虹：三道同心弧，从左下角张出去。Lover 那张封面的天空里就有一道
    val bowCenter = Offset(size.width * 0.12f, size.height * 1.02f)
    repeat(3) { band ->
        val radius = size.minDimension * (0.52f + band * 0.075f)
        drawArc(
            color = if (band == 1) Color.White else color,
            startAngle = -92f,
            sweepAngle = 84f,
            useCenter = false,
            topLeft = Offset(bowCenter.x - radius, bowCenter.y - radius),
            size = Size(radius * 2f, radius * 2f),
            alpha = if (band == 1) 0.22f else MOTIF_ALPHA * 0.85f,
            style = Stroke(width = size.minDimension * 0.026f)
        )
    }
    // 云是五个交叠的软圆，半径递减；每团再压一个更小更亮的核心，才有蓬松的层
    listOf(
        Triple(0.20f, 0.26f, 0.17f),
        Triple(0.34f, 0.32f, 0.22f),
        Triple(0.48f, 0.22f, 0.26f),
        Triple(0.64f, 0.30f, 0.19f),
        Triple(0.78f, 0.24f, 0.15f)
    ).forEach { (cx, cy, r) ->
        val radius = size.minDimension * r
        val center = Offset(cx * size.width, cy * size.height)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color.White.copy(alpha = 0.50f), Color.Transparent),
                center = center,
                radius = radius
            ),
            radius = radius,
            center = center
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color.White.copy(alpha = 0.34f), Color.Transparent),
                center = center + Offset(0f, -radius * 0.22f),
                radius = radius * 0.55f
            ),
            radius = radius * 0.55f,
            center = center + Offset(0f, -radius * 0.22f)
        )
    }
    // 蝴蝶：Lover 时期那面壁画上的翅膀。一只两笔弧 + 一条身子
    val wing = Path()
    listOf(0.22f to 0.62f, 0.58f to 0.50f, 0.84f to 0.70f).forEachIndexed { index, (bx, by) ->
        val span = size.minDimension * (0.055f + index * 0.012f)
        // 翅膀随相位开合，扇动幅度很小 —— 大了就成了会飞的东西，抢过整张卡
        val open = 0.78f + 0.22f * (0.5f + 0.5f * sin((phase + index * 0.3f) * TAU * 2f))
        val cxp = bx * size.width
        val cyp = by * size.height
        repeat(2) { side ->
            val dir = if (side == 0) -1f else 1f
            wing.rewind()
            wing.moveTo(cxp, cyp)
            wing.cubicTo(
                cxp + dir * span * 1.5f * open, cyp - span * 1.15f,
                cxp + dir * span * 1.7f * open, cyp + span * 0.35f,
                cxp, cyp + span * 0.30f
            )
            wing.close()
            drawPath(path = wing, color = color, alpha = MOTIF_ALPHA * 1.5f)
        }
        drawLine(
            color = color,
            start = Offset(cxp, cyp - span * 0.30f),
            end = Offset(cxp, cyp + span * 0.42f),
            strokeWidth = size.minDimension * 0.004f,
            cap = StrokeCap.Round,
            alpha = MOTIF_ALPHA * 1.8f
        )
    }
    val random = Random(2019)
    val heart = Path()
    repeat(9) {
        val x = random.nextFloat()
        val travel = ((phase + random.nextFloat()) % 1f)
        val y = 1.05f - travel * 1.15f
        val side = size.minDimension * (0.03f + random.nextFloat() * 0.025f)
        translate(left = x * size.width - side / 2f, top = y * size.height - side / 2f) {
            // 与灯箱那颗心同一个两段贝塞尔轮廓，这里直接按 side 展开
            heart.rewind()
            heart.moveTo(side * 0.5f, side * 0.92f)
            heart.cubicTo(-side * 0.18f, side * 0.52f, side * 0.16f, side * 0.02f, side * 0.5f, side * 0.30f)
            heart.cubicTo(side * 1.18f, side * 0.02f, side * 0.84f, side * 0.52f, side * 0.5f, side * 0.92f)
            heart.close()
            drawPath(path = heart, color = color, alpha = MOTIF_ALPHA * 2.2f * (1f - travel))
        }
    }
}

/** 8 · folklore：两层松林 + 灰雾横带 + 飘落的细屑。 */
private fun DrawScope.drawPineFog(color: Color, phase: Float) {
    val random = Random(2020)
    /**
     * 一棵分三层的松：单个三角是「树形」，三层错开的三角才是松树。
     *
     * 整片林子攒进**一条** Path 再一次画完 —— 逐棵 `drawPath` 时相邻两棵重叠的地方
     * 会把不透明度叠上去，而 [SwiftieEraContrast] 的对比度模型是按「母题最深一层」
     * 算的，叠出来的那一块就超出了模型，压在它上面的曲目名会掉到 AA 以下。
     */
    fun Path.addPine(x: Float, height: Float, halfWidth: Float) {
        repeat(3) { tier ->
            val tierTop = size.height - height * (1f - tier * 0.26f)
            val tierBottom = size.height - height * (0.52f - tier * 0.26f)
            val tierHalf = halfWidth * (0.55f + tier * 0.30f)
            moveTo(x, tierTop)
            lineTo(x + tierHalf, tierBottom)
            lineTo(x - tierHalf, tierBottom)
            close()
        }
        // 露出来的那一小截树干
        moveTo(x - halfWidth * 0.14f, size.height)
        lineTo(x - halfWidth * 0.14f, size.height - height * 0.10f)
        lineTo(x + halfWidth * 0.14f, size.height - height * 0.10f)
        lineTo(x + halfWidth * 0.14f, size.height)
        close()
    }
    // 远处那层先画，颜色走白：雾里的远树是发亮的，不是更深的。这也顺手避开了
    // 「远近两层叠在一起变得比模型还深」的问题 —— 白色只会把底色提亮
    val far = Path()
    repeat(14) { index ->
        val x = (index + 0.5f) / 14f * size.width + random.nextFloat() * size.width * 0.02f
        far.addPine(
            x = x,
            height = size.height * (0.18f + random.nextFloat() * 0.16f),
            halfWidth = size.width * (0.013f + random.nextFloat() * 0.010f)
        )
    }
    drawPath(path = far, color = Color.White, alpha = 0.30f)
    val near = Path()
    repeat(9) { index ->
        val x = (index + 0.5f) / 9f * size.width
        near.addPine(
            x = x,
            height = size.height * (0.34f + random.nextFloat() * 0.38f),
            halfWidth = size.width * (0.024f + random.nextFloat() * 0.020f)
        )
    }
    drawPath(path = near, color = color, alpha = MOTIF_ALPHA * 1.5f)
    // 雾带：四条横向漂移的软带，压在树上才有纵深
    repeat(4) { band ->
        val drift = sin((phase + band * 0.31f) * TAU) * size.width * 0.07f
        val top = size.height * (0.40f + band * 0.14f)
        val height = size.height * 0.15f
        translate(left = drift) {
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(Color.Transparent, Color.White.copy(alpha = 0.42f), Color.Transparent),
                    startY = top,
                    endY = top + height
                ),
                topLeft = Offset(-size.width * 0.1f, top),
                size = Size(size.width * 1.2f, height)
            )
        }
    }
    // 雾丝：几道细的横向卷须，纯渐变带缺的就是这点笔触
    val wisp = Path()
    repeat(3) { index ->
        val baseY = size.height * (0.44f + index * 0.17f)
        val drift = sin((phase + index * 0.4f) * TAU) * size.width * 0.05f
        wisp.rewind()
        wisp.moveTo(-size.width * 0.05f, baseY)
        wisp.cubicTo(
            size.width * 0.30f + drift, baseY - size.height * 0.035f,
            size.width * 0.68f - drift, baseY + size.height * 0.035f,
            size.width * 1.05f, baseY
        )
        drawPath(
            path = wisp,
            color = Color.White,
            alpha = 0.30f,
            style = Stroke(width = size.minDimension * 0.006f)
        )
    }
    // 细屑：飘下来的雪 / 灰，慢慢往下走
    repeat(22) {
        val x = random.nextFloat()
        val fall = ((phase * 0.5f + random.nextFloat()) % 1f)
        drawCircle(
            color = Color.White,
            radius = size.minDimension * 0.0032f,
            center = Offset(x * size.width, fall * size.height),
            alpha = 0.40f * (1f - fall * 0.5f)
        )
    }
}

/** 9 · evermore：两株棕色枝条分形 + 枝头的叶 + 三股辫。 */
private fun DrawScope.drawBraidBranch(color: Color, phase: Float, lowRam: Boolean) {
    // 低端机走静态：分形递归本身便宜，贵的是每帧重建 Path
    val sway = if (lowRam) 0f else sin(phase * TAU) * 0.10f
    val leaf = Path()
    fun branch(from: Offset, angle: Float, length: Float, depth: Int) {
        if (depth == 0 || length < size.minDimension * 0.02f) return
        val to = Offset(from.x + cos(angle) * length, from.y + sin(angle) * length)
        drawLine(
            color = color,
            start = from,
            end = to,
            strokeWidth = size.minDimension * 0.004f * depth,
            alpha = MOTIF_ALPHA * 1.5f
        )
        if (depth == 1) {
            // 末梢挂一片叶：光秃秃的分形是根雷击木，有叶才是 evermore 那片林子
            val leafLen = length * 0.85f
            val tip = Offset(to.x + cos(angle) * leafLen, to.y + sin(angle) * leafLen)
            val nx = -sin(angle) * leafLen * 0.22f
            val ny = cos(angle) * leafLen * 0.22f
            leaf.rewind()
            leaf.moveTo(to.x, to.y)
            leaf.cubicTo(to.x + nx, to.y + ny, tip.x + nx, tip.y + ny, tip.x, tip.y)
            leaf.cubicTo(tip.x - nx, tip.y - ny, to.x - nx, to.y - ny, to.x, to.y)
            leaf.close()
            drawPath(path = leaf, color = color, alpha = MOTIF_ALPHA * 1.1f)
            return
        }
        branch(to, angle - 0.5f + sway, length * 0.68f, depth - 1)
        branch(to, angle + 0.5f - sway, length * 0.68f, depth - 1)
    }
    branch(
        from = Offset(size.width * 0.14f, size.height),
        angle = -PI.toFloat() / 2.2f,
        length = size.height * 0.30f,
        depth = if (lowRam) 4 else 5
    )
    // 右下角再来一株矮的，反向长。一株孤木是「一个图形」，两株才是林子的一角
    branch(
        from = Offset(size.width * 0.93f, size.height),
        angle = -PI.toFloat() / 1.7f,
        length = size.height * 0.20f,
        depth = if (lowRam) 3 else 4
    )
    // 三股辫：三条相位各差 1/3 的正弦，交叠出编织感
    val path = Path()
    val baseline = size.height * 0.30f
    val amplitude = size.height * 0.055f
    repeat(3) { strand ->
        path.rewind()
        path.moveTo(size.width * 0.55f, baseline)
        for (step in 1..40) {
            val t = step / 40f
            val x = size.width * (0.55f + t * 0.40f)
            val y = baseline + sin(t * TAU * 2f + strand * TAU / 3f + phase * TAU) * amplitude
            path.lineTo(x, y)
        }
        drawPath(
            path = path,
            color = color,
            alpha = MOTIF_ALPHA * 1.4f,
            style = Stroke(width = size.minDimension * 0.010f)
        )
    }
    // 交叉点压一点高光，辫子才有前后；否则三条线只是三条线
    repeat(8) { index ->
        val t = (index + 0.5f) / 8f
        val x = size.width * (0.55f + t * 0.40f)
        val y = baseline + sin(t * TAU * 2f + phase * TAU) * amplitude
        drawCircle(
            color = Color.White,
            radius = size.minDimension * 0.006f,
            center = Offset(x, y),
            alpha = 0.24f
        )
    }
}

/** 10 · Midnights：午夜钟面 + 深蓝星芒 + 弯月 + 打火机火苗。 */
private fun DrawScope.drawStarburstFlame(color: Color, phase: Float) {
    // 钟面：Midnights 的封面就是一只指向午夜的钟。少了它这张卡只是「夜空」，
    // 不是「午夜」——十二道刻度加两根指在正上方的针，一眼就认得出
    val clockCenter = Offset(size.width * 0.30f, size.height * 0.34f)
    val clockRadius = size.minDimension * 0.30f
    drawCircle(
        color = color,
        radius = clockRadius,
        center = clockCenter,
        alpha = MOTIF_ALPHA * 1.3f,
        style = Stroke(width = size.minDimension * 0.007f)
    )
    repeat(12) { index ->
        val angle = index / 12f * TAU - PI.toFloat() / 2f
        val outer = clockRadius * 0.94f
        val inner = clockRadius * if (index % 3 == 0) 0.78f else 0.86f
        drawLine(
            color = color,
            start = clockCenter + Offset(cos(angle) * inner, sin(angle) * inner),
            end = clockCenter + Offset(cos(angle) * outer, sin(angle) * outer),
            strokeWidth = size.minDimension * (if (index % 3 == 0) 0.006f else 0.004f),
            cap = StrokeCap.Round,
            alpha = MOTIF_ALPHA * 1.4f
        )
    }
    // 两根针都指向 12：差半分钟到午夜，秒针那一点抖动交给相位
    val tick = sin(phase * TAU) * 0.05f
    listOf(0.60f to 0.008f, 0.82f to 0.005f).forEach { (length, width) ->
        val angle = -PI.toFloat() / 2f + tick
        drawLine(
            color = color,
            start = clockCenter,
            end = clockCenter + Offset(
                cos(angle) * clockRadius * length,
                sin(angle) * clockRadius * length
            ),
            strokeWidth = size.minDimension * width,
            cap = StrokeCap.Round,
            alpha = MOTIF_ALPHA * 1.7f
        )
    }
    drawCircle(
        color = color,
        radius = size.minDimension * 0.010f,
        center = clockCenter,
        alpha = MOTIF_ALPHA * 1.8f
    )
    // 弯月：一大一小两个圆相减，挖出月牙
    val moonCenter = Offset(size.width * 0.80f, size.height * 0.20f)
    val moonRadius = size.minDimension * 0.13f
    val moon = Path().apply {
        addOval(
            Rect(
                moonCenter.x - moonRadius, moonCenter.y - moonRadius,
                moonCenter.x + moonRadius, moonCenter.y + moonRadius
            )
        )
    }
    val bite = Path().apply {
        addOval(
            Rect(
                moonCenter.x - moonRadius * 0.45f, moonCenter.y - moonRadius * 1.05f,
                moonCenter.x + moonRadius * 1.55f, moonCenter.y + moonRadius * 1.05f
            )
        )
    }
    moon.op(moon, bite, PathOperation.Difference)
    drawPath(path = moon, color = Color.White, alpha = 0.34f)
    val random = Random(2022)
    repeat(15) {
        val center = Offset(random.nextFloat() * size.width, random.nextFloat() * size.height * 0.9f)
        val arm = size.minDimension * (0.022f + random.nextFloat() * 0.045f)
        val twinkle = 0.4f + 0.6f * (0.5f + 0.5f * sin((phase + random.nextFloat()) * TAU))
        drawSparkleCross(
            center = center,
            arm = arm,
            color = color,
            alpha = MOTIF_ALPHA * 2f * twinkle,
            width = size.minDimension * 0.003f
        )
    }
    // 火苗：底宽顶尖的水滴，宽度随 phase 呼吸；外面再罩一圈暖光
    val flameHeight = size.height * 0.22f
    val flameWidth = size.width * (0.045f + 0.010f * sin(phase * TAU * 3f))
    val baseX = size.width * 0.82f
    val baseY = size.height * 0.92f
    val halo = flameHeight * 0.85f
    val haloCenter = Offset(baseX, baseY - flameHeight * 0.45f)
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(Color.White.copy(alpha = 0.30f), Color.Transparent),
            center = haloCenter,
            radius = halo
        ),
        radius = halo,
        center = haloCenter
    )
    val flame = Path().apply {
        moveTo(baseX, baseY)
        cubicTo(
            baseX - flameWidth, baseY - flameHeight * 0.45f,
            baseX - flameWidth * 0.35f, baseY - flameHeight * 0.80f,
            baseX, baseY - flameHeight
        )
        cubicTo(
            baseX + flameWidth * 0.35f, baseY - flameHeight * 0.80f,
            baseX + flameWidth, baseY - flameHeight * 0.45f,
            baseX, baseY
        )
        close()
    }
    drawPath(
        path = flame,
        brush = Brush.verticalGradient(
            colors = listOf(Color.White.copy(alpha = 0.55f), color.copy(alpha = 0.0f)),
            startY = baseY - flameHeight,
            endY = baseY
        )
    )
    // 焰心：里面那一小簇更亮
    val core = Path().apply {
        moveTo(baseX, baseY - flameHeight * 0.06f)
        cubicTo(
            baseX - flameWidth * 0.42f, baseY - flameHeight * 0.34f,
            baseX - flameWidth * 0.14f, baseY - flameHeight * 0.52f,
            baseX, baseY - flameHeight * 0.62f
        )
        cubicTo(
            baseX + flameWidth * 0.14f, baseY - flameHeight * 0.52f,
            baseX + flameWidth * 0.42f, baseY - flameHeight * 0.34f,
            baseX, baseY - flameHeight * 0.06f
        )
        close()
    }
    drawPath(path = core, color = Color.White, alpha = 0.34f)
}

/**
 * 11 · TTPD：米白纸纹 + 打字机游标。
 *
 * 唯一一个不吃 `color` 的母题：TTPD 主色是近白的 `#F5F1EA`，用它在白卡上画线等于没画。
 * 主色那层薄底由卡片自己铺。
 */
private fun DrawScope.drawTypewriterPaper(phase: Float) {
    // 纸纹用深灰
    val ink = Color(0xFF4A453E)
    // 纸的折痕：一道竖着的软亮带。纸之所以是纸，靠的是它折过
    val creaseX = size.width * 0.36f
    val creaseWidth = size.width * 0.10f
    drawRect(
        brush = Brush.horizontalGradient(
            colors = listOf(Color.Transparent, Color.White.copy(alpha = 0.34f), Color.Transparent),
            startX = creaseX - creaseWidth,
            endX = creaseX + creaseWidth
        ),
        topLeft = Offset(creaseX - creaseWidth, 0f),
        size = Size(creaseWidth * 2f, size.height)
    )
    drawLine(
        color = ink,
        start = Offset(creaseX, 0f),
        end = Offset(creaseX, size.height),
        strokeWidth = size.minDimension * 0.0025f,
        alpha = MOTIF_ALPHA * 0.5f
    )
    val lineCount = 9
    repeat(lineCount) { index ->
        val y = size.height * (0.16f + index * 0.075f)
        // 每行长度不一，像一段没写完的诗
        val ratio = when (index % 3) {
            0 -> 0.72f
            1 -> 0.84f
            else -> 0.58f
        }
        // 一行不再是一条整线，而是长短不一的「词」。整线读起来是横格纸，
        // 断成词才像真的打上去的字
        var x = size.width * 0.12f
        val lineEnd = size.width * (0.12f + ratio * 0.76f)
        var word = index * 3
        while (x < lineEnd) {
            val wordWidth = size.width * (0.032f + ((word * 7) % 9) / 9f * 0.075f)
            val end = (x + wordWidth).coerceAtMost(lineEnd)
            drawLine(
                color = ink,
                start = Offset(x, y),
                end = Offset(end, y),
                strokeWidth = size.minDimension * 0.006f,
                alpha = MOTIF_ALPHA * 0.9f
            )
            x = end + size.width * 0.018f
            word++
        }
    }
    // 咖啡渍：右下角一个不闭合的环，桌上放久了的那一页
    val stainCenter = Offset(size.width * 0.78f, size.height * 0.74f)
    val stainRadius = size.minDimension * 0.16f
    drawArc(
        color = ink,
        startAngle = 24f,
        sweepAngle = 306f,
        useCenter = false,
        topLeft = Offset(stainCenter.x - stainRadius, stainCenter.y - stainRadius),
        size = Size(stainRadius * 2f, stainRadius * 2f),
        alpha = MOTIF_ALPHA * 0.7f,
        style = Stroke(width = size.minDimension * 0.010f)
    )
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(Color.Transparent, ink.copy(alpha = MOTIF_ALPHA * 0.28f)),
            center = stainCenter,
            radius = stainRadius
        ),
        radius = stainRadius,
        center = stainCenter
    )
    // 游标：方块，1Hz 闪烁，停在最后一行末尾
    val blink = if (sin(phase * TAU * 2f) > 0f) 1f else 0f
    val caretY = size.height * (0.16f + (lineCount - 1) * 0.075f)
    val caretWidth = size.minDimension * 0.018f
    val caretHeight = size.minDimension * 0.045f
    drawRect(
        color = ink,
        topLeft = Offset(size.width * (0.12f + 0.58f * 0.76f) + caretWidth * 0.4f, caretY - caretHeight),
        size = Size(caretWidth, caretHeight),
        alpha = MOTIF_ALPHA * 2.4f * blink
    )
    // 主色的那层薄底由卡片自己铺（`SwiftieEraCard` 的 drawBehind 第一句
    // `drawRect(era.mainColor, alpha = 0.10f)`）。这里再铺一次会把 TTPD 的
    // 米白叠成 0.19，12 张卡片里只有它一张底色偏亮
}

/** 12 · Showgirl：橙金羽毛扇（带羽枝）+ 两道聚光灯锥 + 亮片。 */
private fun DrawScope.drawSpotlightFeather(color: Color, phase: Float) {
    // 主聚光：从顶部一点向下张开的三角，边缘用渐变化开
    val apex = Offset(size.width * 0.30f, -size.height * 0.05f)
    val spread = size.width * 0.34f
    val cone = Path().apply {
        moveTo(apex.x, apex.y)
        lineTo(apex.x - spread, size.height)
        lineTo(apex.x + spread, size.height)
        close()
    }
    drawPath(
        path = cone,
        brush = Brush.verticalGradient(
            colors = listOf(Color.White.copy(alpha = 0.45f), Color.Transparent),
            startY = apex.y,
            endY = size.height
        )
    )
    // 副聚光：从右上斜切下来，暗一档、窄一些。一束光是「照亮」，两束交叉才是舞台
    val apex2 = Offset(size.width * 0.88f, -size.height * 0.05f)
    val cone2 = Path().apply {
        moveTo(apex2.x, apex2.y)
        lineTo(apex2.x - spread * 1.10f, size.height)
        lineTo(apex2.x - spread * 0.30f, size.height)
        close()
    }
    drawPath(
        path = cone2,
        brush = Brush.verticalGradient(
            colors = listOf(Color.White.copy(alpha = 0.26f), Color.Transparent),
            startY = apex2.y,
            endY = size.height
        )
    )
    // 羽毛扇：以右下为轴心的一束长条，整束随 phase 轻微开合
    val pivot = Offset(size.width * 0.86f, size.height * 1.02f)
    val featherCount = 9
    val open = 0.92f + 0.08f * sin(phase * TAU)
    repeat(featherCount) { index ->
        val t = index / (featherCount - 1f)
        val angle = (-PI.toFloat() * 0.92f) + t * PI.toFloat() * 0.52f * open
        val length = size.minDimension * (0.46f + 0.10f * sin(t * PI.toFloat()))
        val tip = Offset(pivot.x + cos(angle) * length, pivot.y + sin(angle) * length)
        drawLine(
            color = color,
            start = pivot,
            end = tip,
            strokeWidth = size.minDimension * 0.020f,
            cap = StrokeCap.Round,
            alpha = MOTIF_ALPHA * (1.1f + 0.5f * t)
        )
        // 羽枝：沿羽轴两侧斜插的短线。没有它 9 条粗线就是一把扫帚 ——
        // 羽毛的辨识度全在这些分叉上
        val barbs = 7
        repeat(barbs) { barb ->
            val at = 0.30f + barb / (barbs - 1f) * 0.66f
            val root = Offset(
                pivot.x + cos(angle) * length * at,
                pivot.y + sin(angle) * length * at
            )
            // 越靠羽尖越短，羽毛才是纺锤形而不是长方形
            val barbLen = length * 0.16f * (1f - at * 0.55f)
            listOf(-1f, 1f).forEach { side ->
                val barbAngle = angle + side * 1.05f - side * 0.25f
                drawLine(
                    color = color,
                    start = root,
                    end = Offset(
                        root.x + cos(barbAngle) * barbLen,
                        root.y + sin(barbAngle) * barbLen
                    ),
                    strokeWidth = size.minDimension * 0.0035f,
                    cap = StrokeCap.Round,
                    alpha = MOTIF_ALPHA * (0.9f + 0.4f * t)
                )
            }
        }
        // 羽尖一点亮金，扇面才不是一把扫帚
        drawCircle(
            color = Color.White,
            radius = size.minDimension * 0.012f,
            center = tip,
            alpha = MOTIF_ALPHA * 1.8f
        )
    }
    // 亮片：光锥里飘的碎金，Showgirl 那身行头的反光
    val random = Random(2025)
    repeat(24) {
        val x = random.nextFloat()
        val y = random.nextFloat()
        val twinkle = 0.5f + 0.5f * sin((phase + random.nextFloat()) * TAU * 2f)
        drawSparkleCross(
            center = Offset(x * size.width, y * size.height),
            arm = size.minDimension * 0.013f * (0.6f + twinkle),
            color = Color.White,
            alpha = 0.42f * twinkle,
            width = size.minDimension * 0.0025f
        )
    }
}
