package com.tracktosearch.ui.screen.swiftie.eras

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
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

/** 1 · Taylor Swift：青绿水彩晕染 + 细碎星点。 */
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
    }
    val random = Random(2006)
    repeat(if (lowRam) 12 else 26) { index ->
        val x = random.nextFloat() * size.width
        val y = random.nextFloat() * size.height
        val twinkle = sin((phase + random.nextFloat()) * TAU) * 0.5f + 0.5f
        drawCircle(
            color = Color.White,
            radius = size.minDimension * 0.004f * (0.6f + twinkle),
            center = Offset(x, y),
            alpha = 0.35f * twinkle
        )
    }
}

/** 2 · Fearless：金色旋转光晕 + 甩动的长发弧线。 */
private fun DrawScope.drawGoldenSwirl(color: Color, phase: Float) {
    val center = Offset(size.width * 0.68f, size.height * 0.38f)
    rotate(degrees = phase * 360f, pivot = center) {
        repeat(4) { ring ->
            val radius = size.minDimension * (0.14f + ring * 0.09f)
            drawArc(
                color = color,
                startAngle = ring * 40f,
                sweepAngle = 210f - ring * 25f,
                useCenter = false,
                topLeft = Offset(center.x - radius, center.y - radius),
                size = Size(radius * 2f, radius * 2f),
                alpha = MOTIF_ALPHA * (1f - ring * 0.15f),
                style = Stroke(width = size.minDimension * 0.012f)
            )
        }
    }
    // 三道长发弧线，各自相位错开，像被风甩起来
    val path = Path()
    repeat(3) { strand ->
        val sway = sin((phase + strand * 0.25f) * TAU) * size.width * 0.05f
        path.rewind()
        path.moveTo(size.width * 0.10f, size.height * (0.18f + strand * 0.08f))
        path.cubicTo(
            size.width * 0.35f + sway, size.height * (0.05f + strand * 0.10f),
            size.width * 0.55f - sway, size.height * (0.75f - strand * 0.06f),
            size.width * 0.92f, size.height * (0.55f + strand * 0.10f)
        )
        drawPath(
            path = path,
            color = color,
            alpha = MOTIF_ALPHA * 0.8f,
            style = Stroke(width = size.minDimension * 0.008f)
        )
    }
}

/** 3 · Speak Now：紫色薄纱正弦波。三层不同振幅叠出纱的层次。 */
private fun DrawScope.drawPurpleVeil(color: Color, phase: Float) {
    val path = Path()
    repeat(3) { layer ->
        val amplitude = size.height * (0.06f + layer * 0.03f)
        val baseline = size.height * (0.30f + layer * 0.20f)
        val shift = (phase + layer * 0.3f) * TAU
        path.rewind()
        path.moveTo(0f, baseline)
        // 48 段折线足够平滑，又不用碰贝塞尔的命名分歧
        for (step in 1..48) {
            val x = size.width * step / 48f
            val y = baseline + sin(step / 48f * TAU * 1.6f + shift) * amplitude
            path.lineTo(x, y)
        }
        path.lineTo(size.width, size.height)
        path.lineTo(0f, size.height)
        path.close()
        drawPath(path = path, color = color, alpha = MOTIF_ALPHA * 0.5f)
    }
}

/** 4 · Red：红色针织横纹。每条纹上叠小 V 字，才有毛线的编织感。 */
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
    }
}

/** 5 · 1989：宝丽来白框 + 框内淡蓝天空。轻微倾斜，像随手摆上去的。 */
private fun DrawScope.drawPolaroid(color: Color, phase: Float) {
    val frameWidth = size.width * 0.46f
    val frameHeight = frameWidth * 1.20f
    val center = Offset(size.width * 0.70f, size.height * 0.42f)
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
        }
    }
}

/** 6 · reputation：报纸半调网点 + 一条蛇形曲线。 */
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
    val snakeShift = if (lowRam) 0f else sin(phase * TAU) * size.width * 0.03f
    val snake = Path().apply {
        moveTo(size.width * 0.08f, size.height * 0.78f)
        cubicTo(
            size.width * 0.35f + snakeShift, size.height * 0.92f,
            size.width * 0.42f - snakeShift, size.height * 0.30f,
            size.width * 0.66f, size.height * 0.44f
        )
        cubicTo(
            size.width * 0.84f + snakeShift, size.height * 0.54f,
            size.width * 0.80f, size.height * 0.16f,
            size.width * 0.94f, size.height * 0.22f
        )
    }
    drawPath(
        path = snake,
        color = color,
        alpha = MOTIF_ALPHA * 1.8f,
        style = Stroke(width = size.minDimension * 0.014f)
    )
}

/** 7 · Lover：粉蓝云 + 上浮的亮粉心。 */
private fun DrawScope.drawPinkCloudHeart(color: Color, phase: Float) {
    // 云是三个交叠的软圆，半径递减
    listOf(
        Triple(0.28f, 0.30f, 0.20f),
        Triple(0.44f, 0.24f, 0.26f),
        Triple(0.62f, 0.32f, 0.18f)
    ).forEach { (cx, cy, r) ->
        val radius = size.minDimension * r
        val center = Offset(cx * size.width, cy * size.height)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color.White.copy(alpha = 0.55f), Color.Transparent),
                center = center,
                radius = radius
            ),
            radius = radius,
            center = center
        )
    }
    val random = Random(2019)
    val heart = Path()
    repeat(7) {
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

/** 8 · folklore：灰雾横带 + 松林垂直剪影。 */
private fun DrawScope.drawPineFog(color: Color, phase: Float) {
    val random = Random(2020)
    val pine = Path()
    // 先画树，雾压在树上才有纵深
    repeat(11) { index ->
        val x = (index + 0.5f) / 11f * size.width
        val height = size.height * (0.30f + random.nextFloat() * 0.40f)
        val halfWidth = size.width * (0.020f + random.nextFloat() * 0.020f)
        pine.rewind()
        pine.moveTo(x, size.height - height)
        pine.lineTo(x + halfWidth, size.height)
        pine.lineTo(x - halfWidth, size.height)
        pine.close()
        drawPath(path = pine, color = color, alpha = MOTIF_ALPHA * 1.6f)
    }
    repeat(3) { band ->
        val drift = sin((phase + band * 0.4f) * TAU) * size.width * 0.06f
        val top = size.height * (0.45f + band * 0.16f)
        val height = size.height * 0.14f
        translate(left = drift) {
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(Color.Transparent, Color.White.copy(alpha = 0.40f), Color.Transparent),
                    startY = top,
                    endY = top + height
                ),
                topLeft = Offset(-size.width * 0.1f, top),
                size = Size(size.width * 1.2f, height)
            )
        }
    }
}

/** 9 · evermore：棕色枝条分形 + 三股辫。 */
private fun DrawScope.drawBraidBranch(color: Color, phase: Float, lowRam: Boolean) {
    // 低端机走静态：分形递归本身便宜，贵的是每帧重建 Path
    val sway = if (lowRam) 0f else sin(phase * TAU) * 0.10f
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
        branch(to, angle - 0.5f + sway, length * 0.68f, depth - 1)
        branch(to, angle + 0.5f - sway, length * 0.68f, depth - 1)
    }
    branch(
        from = Offset(size.width * 0.16f, size.height),
        angle = -PI.toFloat() / 2.2f,
        length = size.height * 0.30f,
        depth = if (lowRam) 4 else 5
    )
    // 三股辫：三条相位各差 1/3 的正弦，交叠出编织感
    val path = Path()
    repeat(3) { strand ->
        path.rewind()
        val baseline = size.height * 0.30f
        val amplitude = size.height * 0.055f
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
}

/** 10 · Midnights：深蓝星芒 + 打火机火苗。 */
private fun DrawScope.drawStarburstFlame(color: Color, phase: Float) {
    val random = Random(2022)
    repeat(9) {
        val center = Offset(random.nextFloat() * size.width, random.nextFloat() * size.height * 0.8f)
        val arm = size.minDimension * (0.03f + random.nextFloat() * 0.04f)
        val twinkle = 0.4f + 0.6f * (0.5f + 0.5f * sin((phase + random.nextFloat()) * TAU))
        val alpha = MOTIF_ALPHA * 2f * twinkle
        // 四芒星：两条主轴 + 两条短斜轴，比画多边形便宜
        drawLine(color, center - Offset(arm, 0f), center + Offset(arm, 0f), size.minDimension * 0.003f, alpha = alpha)
        drawLine(color, center - Offset(0f, arm), center + Offset(0f, arm), size.minDimension * 0.003f, alpha = alpha)
        val diag = arm * 0.45f
        drawLine(color, center - Offset(diag, diag), center + Offset(diag, diag), size.minDimension * 0.002f, alpha = alpha * 0.7f)
        drawLine(color, center - Offset(diag, -diag), center + Offset(diag, -diag), size.minDimension * 0.002f, alpha = alpha * 0.7f)
    }
    // 火苗：底宽顶尖的水滴，宽度随 phase 呼吸
    val flameHeight = size.height * 0.22f
    val flameWidth = size.width * (0.045f + 0.010f * sin(phase * TAU * 3f))
    val baseX = size.width * 0.82f
    val baseY = size.height * 0.92f
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
    val lineCount = 9
    repeat(lineCount) { index ->
        val y = size.height * (0.16f + index * 0.075f)
        // 每行长度不一，像一段没写完的诗
        val ratio = when (index % 3) {
            0 -> 0.72f
            1 -> 0.84f
            else -> 0.58f
        }
        drawLine(
            color = ink,
            start = Offset(size.width * 0.12f, y),
            end = Offset(size.width * (0.12f + ratio * 0.76f), y),
            strokeWidth = size.minDimension * 0.006f,
            alpha = MOTIF_ALPHA * 0.9f
        )
    }
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

/** 12 · Showgirl：橙金羽毛扇 + 聚光灯锥。 */
private fun DrawScope.drawSpotlightFeather(color: Color, phase: Float) {
    // 聚光灯：从顶部一点向下张开的三角，边缘用渐变化开
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
            strokeWidth = size.minDimension * 0.026f,
            cap = StrokeCap.Round,
            alpha = MOTIF_ALPHA * (1.1f + 0.5f * t)
        )
        // 羽尖一点亮金，扇面才不是一把扫帚
        drawCircle(
            color = Color.White,
            radius = size.minDimension * 0.012f,
            center = tip,
            alpha = MOTIF_ALPHA * 1.8f
        )
    }
}
