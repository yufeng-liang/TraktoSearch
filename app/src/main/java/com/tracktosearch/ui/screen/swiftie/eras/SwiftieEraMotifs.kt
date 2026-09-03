package com.tracktosearch.ui.screen.swiftie.eras

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

private const val TAU = 2f * PI.toFloat()

/**
 * 右侧道具列的不透明度。
 *
 * 从 0.20 提到 0.35 是需求方明确要求的「颜色更明显」。这个值**只给道具**，
 * 满卡片的底纹仍是 [TEXTURE_ALPHA] —— 0.35 铺满整张卡片会压过曲目名。
 *
 * 与 `SwiftieEraContrast.MOTIF_MAX_ALPHA`（0.32）的差额是有意的：那个模型算的是
 * 「成片盖在文字底下」的最坏情况，而道具只占右侧 32% 一列。真的会伸进这一列的
 * 只有长歌名，而那种行会把 columnFade 压到 [COLUMN_FADE_MIN]（合 0.18），
 * 比模型假设的 0.32 还低，所以 AA 结论在它真正生效的地方仍然成立。
 */
private const val PROP_ALPHA = 0.35f

/** 卡片剩余部分的低对比底纹。压到 0.10 才不会和曲目名抢。 */
private const val TEXTURE_ALPHA = 0.10f

/**
 * 长歌名让位时 `columnFade` 的下限：`0.35 × 0.514 ≈ 0.18`。
 *
 * 卡片那边只管在 1f 与这个值之间插值，「0.18」这个数字不重复写第二遍。
 */
internal const val COLUMN_FADE_MIN = 0.514f

/** 道具列占卡片宽度的比例。 */
private const val COLUMN_FRACTION = 0.32f

/**
 * 金饰件的固定色。
 *
 * 帷幕的流苏绳、化妆镜的灯泡不能吃时代主色 —— Speak Now 是紫、Showgirl 是橙，
 * 「金流苏」染成紫的就不是金流苏了。Fearless 自己的主色就是这个金，那一张仍走主色。
 */
private val PROP_GOLD = Color(0xFFC9A227)

/** 灯泡与窗里的暖光。白卡上纯白等于没画，得偏一点黄才读得出「亮着」。 */
private val PROP_WARM = Color(0xFFFFE7A8)

/**
 * 画某个时代卡片上的视觉母题：**低对比底纹铺满卡片 + 右侧一列中小道具**。
 *
 * 大件道具（打字机、舞台、天际线、王座）在 `SwiftieEraBackdrop` 那一层，
 * 这里只放拿得起的东西 —— 32% 一列塞不下一台打字机。
 *
 * 每帧重跑一遍（卡片的 `drawBehind` 每帧失效），所以循环里的 `Path` 一律
 * **建一个反复 `rewind()`**，而不是每次迭代 new 一个 —— 蛇鳞一帧 66 片、
 * 羽枝 28 根、格纹 20 条，攒起来就是 96 秒的 GC 抖动。同理，成排的小件
 * （鳞片、羽枝、松针、罗纹）都**合进一条 Path 一次描完**，省的是绘制调用。
 *
 * @param phase 0f..1f 循环相位，由卡片按固定周期喂进来。低端机恒为 0f（整块定格）
 * @param lowRam true 时点阵与分形再减半（Spec §11.2）。**只有 2 个母题看这个参数** ——
 *   低端机的省电靠卡片那边「不读时钟」把整块母题定住，比在 12 个函数里各写一条
 *   低端分支干净得多；这里剩下的 lowRam 只是顺手把一次性的点数也压掉
 * @param columnFade 右侧道具的亮度系数。1f = 全亮；[COLUMN_FADE_MIN] = 让位给长歌名
 *   （见 `SwiftieEraCard` 里的算法）。**只乘道具** —— 底纹跟着一起明暗会整张卡片闪
 */
fun DrawScope.drawEraMotif(
    motif: SwiftieEraMotif,
    color: Color,
    phase: Float,
    lowRam: Boolean,
    columnFade: Float
) {
    val alpha = PROP_ALPHA * columnFade.coerceIn(0f, 1f)
    when (motif) {
        SwiftieEraMotif.PORCH_GUITAR -> drawPorchGuitar(color, phase, alpha)
        SwiftieEraMotif.CASTLE_BALCONY -> drawCastleBalcony(color, phase, alpha)
        SwiftieEraMotif.STAGE_CURTAIN -> drawStageCurtain(color, phase, alpha)
        SwiftieEraMotif.RED_SCARF -> drawRedScarf(color, phase, alpha)
        SwiftieEraMotif.POLAROID -> drawPolaroidGull(color, phase, alpha)
        SwiftieEraMotif.COILED_SNAKE -> drawCoiledSnake(color, phase, lowRam, alpha)
        SwiftieEraMotif.LOVER_HOUSE -> drawLoverHouse(color, phase, alpha)
        SwiftieEraMotif.CARDIGAN_CHAIR -> drawCardiganChair(color, phase, alpha)
        SwiftieEraMotif.BRAID_PLAID -> drawBraidPlaid(color, phase, lowRam, alpha)
        SwiftieEraMotif.LIGHTER_STARS -> drawLighterStars(color, phase, alpha)
        SwiftieEraMotif.LETTER_QUILL -> drawLetterQuill(phase, alpha)
        SwiftieEraMotif.VANITY_MIRROR -> drawVanityMirror(color, phase, alpha)
    }
}

// ---------------------------------------------------------------------------
// 共用几何
// ---------------------------------------------------------------------------

/**
 * 右侧道具列的框。
 *
 * 高度按**框宽**定（1.55 倍）而不是跟着卡片高度拉满：TTPD 有 31 首、卡片高过
 * 400dp，跟着拉长会把吉他和打火机抽成竹竿。上缘再夹到卡片 34% 以下，
 * 让开标题与日期那一段（`CARD_CHROME_HEIGHT`）与前几行曲目。
 *
 * 右边留 2.5% 而不是贴边：道具被卡片圆角切一刀比留白更显廉价。
 */
private fun DrawScope.propBox(): Rect {
    val boxWidth = size.width * COLUMN_FRACTION
    val left = size.width * (0.975f - COLUMN_FRACTION)
    val bottom = size.height * 0.94f
    val top = maxOf(size.height * 0.34f, bottom - boxWidth * 1.55f)
    return Rect(left, top, left + boxWidth, bottom)
}

/**
 * 矩形周长上 [t]（0f..1f，从左上角起顺时针）处的点。
 *
 * 化妆镜那一圈灯泡要等距落在镜框上，按四条边分段算比拿角度硬凑准得多。
 */
private fun rectPerimeterPoint(rect: Rect, t: Float): Offset {
    val w = rect.width
    val h = rect.height
    var d = ((t % 1f) + 1f) % 1f * 2f * (w + h)
    if (d < w) return Offset(rect.left + d, rect.top)
    d -= w
    if (d < h) return Offset(rect.right, rect.top + d)
    d -= h
    if (d < w) return Offset(rect.right - d, rect.bottom)
    d -= w
    return Offset(rect.left, rect.bottom - d)
}

// ---------------------------------------------------------------------------
// 底纹：铺满整张卡片，alpha 一律 [TEXTURE_ALPHA] 上下
// ---------------------------------------------------------------------------

/** 1 · 青绿水彩晕染。三团半径不等的软圆，各自错相位轻飘。 */
private fun DrawScope.watercolorWashTexture(color: Color, phase: Float) {
    listOf(0.22f to 0.30f, 0.72f to 0.55f, 0.45f to 0.80f).forEachIndexed { index, (cx, cy) ->
        val drift = sin((phase + index * 0.33f) * TAU) * 0.02f
        val center = Offset((cx + drift) * size.width, cy * size.height)
        val radius = size.minDimension * (0.30f + index * 0.05f)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(color.copy(alpha = TEXTURE_ALPHA * 1.4f), Color.Transparent),
                center = center,
                radius = radius
            ),
            radius = radius,
            center = center
        )
    }
}

/**
 * 2 / 12 · 斜向细纹。
 *
 * **不随相位动** —— 满卡片的斜纹一起爬会晃眼，而且它是「纸的质地」，
 * 质地本来就不该动。动的只有右侧那一列道具。
 *
 * @param spacing 纹距占卡片宽的比例
 * @param downhill true = 左上往右下；false = 左下往右上
 */
private fun DrawScope.diagonalHatchTexture(color: Color, spacing: Float, downhill: Boolean) {
    val step = size.width * spacing
    val span = size.height
    val top = if (downhill) 0f else span
    val bottom = if (downhill) span else 0f
    var x = -span
    while (x < size.width + step) {
        drawLine(
            color = color,
            start = Offset(x, top),
            end = Offset(x + span, bottom),
            strokeWidth = size.minDimension * 0.004f,
            alpha = TEXTURE_ALPHA
        )
        x += step
    }
}

/** 3 · 紫色薄纱正弦波。三层不同振幅叠出纱的层次（原样保留，本来就是 0.10）。 */
private fun DrawScope.veilWaveTexture(color: Color, phase: Float) {
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
        drawPath(path = path, color = color, alpha = TEXTURE_ALPHA)
    }
}

/** 4 · 红色针织横纹。每条纹上叠小 V 字，才有毛线的编织感。 */
private fun DrawScope.knitStripeTexture(color: Color, phase: Float) {
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
            alpha = TEXTURE_ALPHA * if (row % 2 == 0) 1f else 0.6f,
            style = Stroke(width = rowHeight * 0.18f)
        )
    }
}

/**
 * 5 · 淡蓝横向渐层。
 *
 * 一次 `drawRect` 带多档竖向渐变就够 —— 画成 N 条带子要 N 次绘制调用，
 * 而肉眼在 0.10 不透明度下根本分不出接缝在哪。
 */
private fun DrawScope.skyBandTexture(color: Color) {
    drawRect(
        brush = Brush.verticalGradient(
            colors = listOf(
                color.copy(alpha = TEXTURE_ALPHA * 1.6f),
                Color.Transparent,
                color.copy(alpha = TEXTURE_ALPHA),
                Color.Transparent,
                color.copy(alpha = TEXTURE_ALPHA * 1.3f)
            )
        )
    )
}

/** 6 · 报纸半调网点。点半径随位置渐变，做出印刷网点的疏密。 */
private fun DrawScope.halftoneTexture(color: Color, phase: Float, lowRam: Boolean) {
    val step = size.minDimension / if (lowRam) 12f else 20f
    var y = step * 0.5f
    while (y < size.height) {
        var x = step * 0.5f
        while (x < size.width) {
            // 低端机不随相位动
            val wave = if (lowRam) 0f else sin((x / size.width + phase) * TAU) * 0.25f
            val ratio = (x / size.width) * 0.5f + (y / size.height) * 0.5f + wave
            drawCircle(
                color = color,
                radius = step * 0.36f * ratio.coerceIn(0.05f, 1f),
                center = Offset(x, y),
                alpha = TEXTURE_ALPHA
            )
            x += step
        }
        y += step
    }
}

/**
 * 7 · 粉蓝云。
 *
 * 蓝色是**硬编码**的：Lover 主色只有粉一种，「粉蓝云」的蓝没处取。用的是
 * `SwiftieErasData.STAGE` 里 Lover 那一档渐变的末色 `#9BC4E8`，两层对得上。
 * 云本体也不能用白 —— 白云画在白卡上等于没画。
 */
private fun DrawScope.pastelCloudTexture(color: Color, phase: Float) {
    val sky = Color(0xFF9BC4E8)
    repeat(4) { index ->
        val drift = sin((phase + index * 0.27f) * TAU) * 0.025f
        val center = Offset(
            (0.20f + index * 0.22f + drift) * size.width,
            (0.24f + (index % 2) * 0.30f) * size.height
        )
        val radius = size.minDimension * (0.26f + (index % 3) * 0.06f)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    (if (index % 2 == 0) color else sky).copy(alpha = TEXTURE_ALPHA * 1.5f),
                    Color.Transparent
                ),
                center = center,
                radius = radius
            ),
            radius = radius,
            center = center
        )
    }
}

/** 8 · 灰雾横带。三条错相位横向漂移的软带，压在松枝与开衫底下。 */
private fun DrawScope.fogBandTexture(color: Color, phase: Float) {
    repeat(3) { band ->
        val drift = sin((phase + band * 0.4f) * TAU) * size.width * 0.06f
        val top = size.height * (0.28f + band * 0.22f)
        val height = size.height * 0.16f
        translate(left = drift) {
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color.Transparent,
                        color.copy(alpha = TEXTURE_ALPHA * 1.8f),
                        Color.Transparent
                    ),
                    startY = top,
                    endY = top + height
                ),
                topLeft = Offset(-size.width * 0.1f, top),
                size = Size(size.width * 1.2f, height)
            )
        }
    }
}

/**
 * 9 · 枝条细纹。
 *
 * 低端机走静态：分形递归本身便宜，贵的是每帧重建 Path —— 这里画的是线段，
 * 所以只把递归深度减一层。
 */
private fun DrawScope.branchTexture(color: Color, phase: Float, lowRam: Boolean) {
    val sway = if (lowRam) 0f else sin(phase * TAU) * 0.10f
    fun branch(from: Offset, angle: Float, length: Float, depth: Int) {
        if (depth == 0 || length < size.minDimension * 0.02f) return
        val to = Offset(from.x + cos(angle) * length, from.y + sin(angle) * length)
        drawLine(
            color = color,
            start = from,
            end = to,
            strokeWidth = size.minDimension * 0.0022f * depth,
            alpha = TEXTURE_ALPHA
        )
        branch(to, angle - 0.5f + sway, length * 0.68f, depth - 1)
        branch(to, angle + 0.5f - sway, length * 0.68f, depth - 1)
    }
    branch(
        from = Offset(size.width * 0.14f, size.height),
        angle = -PI.toFloat() / 2.2f,
        length = size.height * 0.30f,
        depth = if (lowRam) 4 else 5
    )
}

/** 10 · 深蓝星芒。四芒星 = 两条主轴 + 两条短斜轴，比画多边形便宜。 */
private fun DrawScope.starburstTexture(color: Color, phase: Float) {
    val random = Random(2022)
    repeat(9) {
        val center = Offset(random.nextFloat() * size.width, random.nextFloat() * size.height * 0.8f)
        val arm = size.minDimension * (0.03f + random.nextFloat() * 0.04f)
        val twinkle = 0.4f + 0.6f * (0.5f + 0.5f * sin((phase + random.nextFloat()) * TAU))
        val alpha = TEXTURE_ALPHA * 1.6f * twinkle
        val thin = size.minDimension * 0.003f
        drawLine(color, center - Offset(arm, 0f), center + Offset(arm, 0f), thin, alpha = alpha)
        drawLine(color, center - Offset(0f, arm), center + Offset(0f, arm), thin, alpha = alpha)
        val diag = arm * 0.45f
        drawLine(color, center - Offset(diag, diag), center + Offset(diag, diag), thin * 0.7f, alpha = alpha * 0.7f)
        drawLine(color, center - Offset(diag, -diag), center + Offset(diag, -diag), thin * 0.7f, alpha = alpha * 0.7f)
    }
}

/**
 * 11 · 手稿横线。
 *
 * 唯一一个不吃 `color` 的底纹：TTPD 主色是近白的 `#F5F1EA`，用它在白卡上画线
 * 等于没画，所以墨色硬编码成 [LETTER_INK]。主色那层薄底由卡片自己铺
 * （`SwiftieEraCard` 的 `drawRect(era.mainColor, alpha = 0.10f)`），这里再铺一次
 * 会把 TTPD 的米白叠成 0.19，12 张卡片里只有它一张底色偏亮。
 */
private fun DrawScope.manuscriptLineTexture() {
    repeat(9) { index ->
        val y = size.height * (0.16f + index * 0.075f)
        // 每行长度不一，像一段没写完的诗
        val ratio = when (index % 3) {
            0 -> 0.72f
            1 -> 0.84f
            else -> 0.58f
        }
        drawLine(
            color = LETTER_INK,
            start = Offset(size.width * 0.12f, y),
            end = Offset(size.width * (0.12f + ratio * 0.76f), y),
            strokeWidth = size.minDimension * 0.005f,
            alpha = TEXTURE_ALPHA
        )
    }
}

// ---------------------------------------------------------------------------
// 12 个母题：底纹 + 右侧一列道具
// ---------------------------------------------------------------------------

/** 1 · Taylor Swift：木门廊栏杆一角 + 斜靠的民谣吉他。 */
private fun DrawScope.drawPorchGuitar(color: Color, phase: Float, alpha: Float) {
    watercolorWashTexture(color, phase)
    val box = propBox()
    val w = box.width
    val h = box.height
    val u = min(w, h)
    translate(left = box.left, top = box.top) {
        // 门廊栏杆：横扶手在上、地梁在下，中间四根竖柱
        val railY = h * 0.12f
        val baseY = h * 0.76f
        repeat(4) { index ->
            val x = w * (0.12f + index * 0.25f)
            drawRect(color, Offset(x, railY), Size(u * 0.032f, baseY - railY), alpha = alpha * 0.7f)
        }
        drawRect(color, Offset(-w * 0.02f, railY), Size(w * 1.04f, u * 0.055f), alpha = alpha)
        drawRect(color, Offset(-w * 0.02f, baseY), Size(w * 1.04f, u * 0.042f), alpha = alpha * 0.9f)
        // 木纹：扶手上两道断续的细横线，不然扶手就是一块色条
        val grain = Path()
        repeat(2) { line ->
            val y = railY + u * (0.018f + line * 0.022f)
            grain.moveTo(w * (0.04f + line * 0.10f), y)
            grain.lineTo(w * (0.44f + line * 0.08f), y)
            grain.moveTo(w * (0.56f - line * 0.06f), y)
            grain.lineTo(w * 0.96f, y)
        }
        drawPath(grain, color, alpha = alpha * 0.55f, style = Stroke(width = u * 0.006f))
        drawGuitar(color, phase, alpha, w, h, u)
    }
}

/**
 * 斜靠在栏杆上的民谣吉他。音孔、品丝、六根弦一样不少 —— 少了品丝就只是个葫芦。
 *
 * 在**已经 translate 到道具框**的坐标系里画，所以只吃 [w] / [h] / [u]，
 * 一律不读 `size`（那还是整张卡片的尺寸，读了就全错位）。
 */
private fun DrawScope.drawGuitar(
    color: Color,
    phase: Float,
    alpha: Float,
    w: Float,
    h: Float,
    u: Float
) {
    val bodyCx = w * 0.44f
    val bodyCy = h * 0.76f
    // 靠着栏杆微微晃：只给 1.2°，再大就读作要倒了
    rotate(degrees = -20f + sin(phase * TAU) * 1.2f, pivot = Offset(bodyCx, bodyCy)) {
        translate(left = bodyCx, top = bodyCy) {
            val lower = u * 0.20f
            val upper = u * 0.152f
            val waistY = -u * 0.24f
            val outline = Stroke(width = u * 0.014f)
            // 琴箱：上下两个圆一叠就是收腰的葫芦，比手搓贝塞尔准
            drawCircle(color, lower, Offset.Zero, alpha = alpha * 0.28f)
            drawCircle(color, upper, Offset(0f, waistY), alpha = alpha * 0.28f)
            drawCircle(color, lower, Offset.Zero, alpha = alpha, style = outline)
            drawCircle(color, upper, Offset(0f, waistY), alpha = alpha, style = outline)
            // 音孔 + 一圈镶边
            val holeY = waistY - u * 0.02f
            drawCircle(color, u * 0.058f, Offset(0f, holeY), alpha = alpha * 1.7f)
            drawCircle(
                color, u * 0.082f, Offset(0f, holeY),
                alpha = alpha * 0.8f, style = Stroke(width = u * 0.007f)
            )
            drawRect(color, Offset(-u * 0.062f, u * 0.085f), Size(u * 0.124f, u * 0.026f), alpha = alpha * 1.5f)
            // 琴颈 + 琴头
            drawRect(color, Offset(-u * 0.033f, -u * 0.95f), Size(u * 0.066f, u * 0.55f), alpha = alpha * 0.9f)
            drawRoundRect(
                color, Offset(-u * 0.05f, -u * 1.09f), Size(u * 0.10f, u * 0.15f),
                CornerRadius(u * 0.022f), alpha = alpha
            )
            repeat(3) { index ->
                val pegY = -u * (1.05f - index * 0.035f)
                drawCircle(color, u * 0.014f, Offset(-u * 0.062f, pegY), alpha = alpha * 1.3f)
                drawCircle(color, u * 0.014f, Offset(u * 0.062f, pegY), alpha = alpha * 1.3f)
            }
            // 品丝：从琴枕往琴箱方向间距递减 —— 等距排出来的是梯子，不是吉他
            val frets = Path()
            var fretY = -u * 0.94f
            var gap = u * 0.062f
            repeat(8) {
                fretY += gap
                frets.moveTo(-u * 0.031f, fretY)
                frets.lineTo(u * 0.031f, fretY)
                gap *= 0.88f
            }
            drawPath(frets, color, alpha = alpha * 0.9f, style = Stroke(width = u * 0.006f))
            // 六根弦：琴桥处略宽、琴枕处略窄
            val strings = Path()
            repeat(6) { index ->
                val spread = (index - 2.5f) / 2.5f
                strings.moveTo(spread * u * 0.030f, u * 0.085f)
                strings.lineTo(spread * u * 0.024f, -u * 0.94f)
            }
            drawPath(strings, color, alpha = alpha * 0.7f, style = Stroke(width = u * 0.0035f))
        }
    }
}

/** 2 · Fearless：城堡阳台一角 + 几缕金色流苏。 */
private fun DrawScope.drawCastleBalcony(color: Color, phase: Float, alpha: Float) {
    diagonalHatchTexture(color, spacing = 0.075f, downhill = false)
    val box = propBox()
    val w = box.width
    val h = box.height
    val u = min(w, h)
    translate(left = box.left, top = box.top) {
        val archLeft = w * 0.14f
        val archRight = w * 0.86f
        val span = archRight - archLeft
        val springY = h * 0.40f
        val stone = u * 0.042f
        // 券顶 + 两根柱脚。半圆券是「城堡」而不是「阳台」的辨识点
        drawArc(
            color = color,
            startAngle = 180f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(archLeft, springY - span / 2f),
            size = Size(span, span),
            alpha = alpha,
            style = Stroke(width = stone)
        )
        drawLine(color, Offset(archLeft, springY), Offset(archLeft, h * 0.60f), stone, alpha = alpha)
        drawLine(color, Offset(archRight, springY), Offset(archRight, h * 0.60f), stone, alpha = alpha)
        // 石砌纹：券石的辐射向接缝 + 柱脚上的错缝砖
        val joints = Path()
        repeat(7) { index ->
            val angle = PI.toFloat() + (index + 0.5f) / 7f * PI.toFloat()
            val cx = archLeft + span / 2f
            val inner = span / 2f - stone * 0.55f
            val outer = span / 2f + stone * 0.55f
            joints.moveTo(cx + cos(angle) * inner, springY + sin(angle) * inner)
            joints.lineTo(cx + cos(angle) * outer, springY + sin(angle) * outer)
        }
        repeat(3) { row ->
            val y = springY + u * (0.05f + row * 0.05f)
            val shift = if (row % 2 == 0) 0f else stone * 0.5f
            joints.moveTo(archLeft - stone / 2f + shift, y)
            joints.lineTo(archLeft + stone / 2f + shift, y)
            joints.moveTo(archRight - stone / 2f - shift, y)
            joints.lineTo(archRight + stone / 2f - shift, y)
        }
        drawPath(joints, color, alpha = alpha * 0.75f, style = Stroke(width = u * 0.006f))
        drawBalconyRail(color, alpha, w, h, u)
        drawGoldTassels(color, phase, alpha, w, h, u, springY)
    }
}

/** 石栏杆：上下两条石板夹住 5 根宝瓶柱。柱子鼓腰是「宝瓶」的全部意思。 */
private fun DrawScope.drawBalconyRail(color: Color, alpha: Float, w: Float, h: Float, u: Float) {
    val copingY = h * 0.60f
    val slab = u * 0.05f
    val baseY = h * 0.86f
    drawRect(color, Offset(-w * 0.02f, copingY), Size(w * 1.04f, slab), alpha = alpha)
    drawRect(color, Offset(-w * 0.02f, baseY), Size(w * 1.04f, slab * 1.1f), alpha = alpha)
    val top = copingY + slab
    val height = baseY - top
    val half = u * 0.055f
    val baluster = Path()
    repeat(5) { index ->
        val cx = w * (0.10f + index * 0.20f)
        baluster.rewind()
        baluster.moveTo(cx - half * 0.42f, top)
        baluster.cubicTo(
            cx - half, top + height * 0.28f,
            cx - half, top + height * 0.62f,
            cx - half * 0.34f, top + height
        )
        baluster.lineTo(cx + half * 0.34f, top + height)
        baluster.cubicTo(
            cx + half, top + height * 0.62f,
            cx + half, top + height * 0.28f,
            cx + half * 0.42f, top
        )
        baluster.close()
        drawPath(baluster, color, alpha = alpha * 0.8f)
    }
}

/**
 * 金色流苏：券洞里横一根杆，杆上垂 6 缕，每缕末端一个小结加三根短须。
 *
 * 挂在杆上而不是凭空垂着 —— 少了那根杆，流苏读起来像六道划痕。
 */
private fun DrawScope.drawGoldTassels(
    color: Color,
    phase: Float,
    alpha: Float,
    w: Float,
    h: Float,
    u: Float,
    springY: Float
) {
    drawLine(
        color, Offset(w * 0.17f, springY), Offset(w * 0.83f, springY),
        u * 0.012f, alpha = alpha * 0.9f
    )
    val fringe = Path()
    repeat(6) { index ->
        val x = w * (0.22f + index * 0.112f)
        val swing = sin((phase + index * 0.13f) * TAU) * u * 0.016f
        val tipY = springY + h * (0.10f + (index % 3) * 0.022f)
        drawLine(
            color, Offset(x, springY), Offset(x + swing, tipY),
            u * 0.010f, alpha = alpha * 1.1f
        )
        drawCircle(color, u * 0.017f, Offset(x + swing, tipY), alpha = alpha * 1.3f)
        repeat(3) { strand ->
            fringe.moveTo(x + swing, tipY + u * 0.014f)
            fringe.lineTo(
                x + swing + (strand - 1) * u * 0.014f,
                tipY + u * (0.048f - abs(strand - 1) * 0.008f)
            )
        }
    }
    drawPath(fringe, color, alpha = alpha * 0.85f, style = Stroke(width = u * 0.005f))
}

/** 3 · Speak Now：剧院帷幕一角（绒面垂褶 + 金流苏绳）+ 紫舞裙裙摆。 */
private fun DrawScope.drawStageCurtain(color: Color, phase: Float, alpha: Float) {
    veilWaveTexture(color, phase)
    val box = propBox()
    val w = box.width
    val h = box.height
    val u = min(w, h)
    translate(left = box.left, top = box.top) {
        // 绒面靠「褶缝暗、褶脊亮」读出来。白卡上「亮」等于色少，所以两侧 alpha 高、
        // 中间低 —— 反过来就是一排纸筒
        val folds = 5
        val foldWidth = w / folds
        val hemY = h * 0.44f
        val fold = Path()
        repeat(folds) { index ->
            val left = index * foldWidth
            val breathe = sin((phase + index * 0.2f) * TAU) * u * 0.012f
            fold.rewind()
            fold.moveTo(left, 0f)
            fold.lineTo(left + foldWidth, 0f)
            fold.lineTo(left + foldWidth, hemY + breathe)
            fold.quadraticTo(
                left + foldWidth * 0.5f, hemY + foldWidth * 0.42f + breathe,
                left, hemY + breathe
            )
            fold.close()
            drawPath(
                path = fold,
                brush = Brush.horizontalGradient(
                    colors = listOf(
                        color.copy(alpha = alpha * 1.3f),
                        color.copy(alpha = alpha * 0.45f),
                        color.copy(alpha = alpha * 1.3f)
                    ),
                    startX = left,
                    endX = left + foldWidth
                )
            )
        }
        drawCurtainRope(phase, alpha, w, h, u)
        drawDressHem(color, phase, alpha, w, h, u)
    }
}

/**
 * 系幕绳与穗子。绳身走一条二次贝塞尔（垂链），穗子挂在最低点。
 *
 * 绳上那几道斜短线是绳股的捻向 —— 少了它，金绳就是一条金色橡皮筋。
 */
private fun DrawScope.drawCurtainRope(phase: Float, alpha: Float, w: Float, h: Float, u: Float) {
    val x0 = -w * 0.02f
    val y0 = h * 0.12f
    val cx = w * 0.5f
    val cy = h * 0.34f
    val x1 = w * 1.02f
    val y1 = h * 0.08f
    val rope = Path()
    rope.moveTo(x0, y0)
    rope.quadraticTo(cx, cy, x1, y1)
    drawPath(rope, PROP_GOLD, alpha = alpha * 2.2f, style = Stroke(width = u * 0.020f))
    val twist = Path()
    repeat(11) { index ->
        val t = (index + 0.5f) / 11f
        val inv = 1f - t
        val px = inv * inv * x0 + 2f * inv * t * cx + t * t * x1
        val py = inv * inv * y0 + 2f * inv * t * cy + t * t * y1
        twist.moveTo(px - u * 0.012f, py + u * 0.010f)
        twist.lineTo(px + u * 0.012f, py - u * 0.010f)
    }
    drawPath(twist, Color.White, alpha = alpha * 1.4f, style = Stroke(width = u * 0.005f))
    // 穗子挂在垂链最低点（t = 0.5），随相位轻摆
    val knotX = 0.25f * x0 + 0.5f * cx + 0.25f * x1
    val knotY = 0.25f * y0 + 0.5f * cy + 0.25f * y1
    rotate(degrees = sin(phase * TAU) * 3.5f, pivot = Offset(knotX, knotY)) {
        drawCircle(PROP_GOLD, u * 0.024f, Offset(knotX, knotY), alpha = alpha * 2.4f)
        val cone = Path()
        cone.moveTo(knotX - u * 0.030f, knotY + u * 0.016f)
        cone.lineTo(knotX + u * 0.030f, knotY + u * 0.016f)
        cone.lineTo(knotX + u * 0.016f, knotY + u * 0.075f)
        cone.lineTo(knotX - u * 0.016f, knotY + u * 0.075f)
        cone.close()
        drawPath(cone, PROP_GOLD, alpha = alpha * 2.0f)
        val strands = Path()
        repeat(6) { index ->
            val sx = knotX + (index - 2.5f) * u * 0.011f
            strands.moveTo(sx, knotY + u * 0.070f)
            strands.lineTo(sx + (index - 2.5f) * u * 0.004f, knotY + u * 0.135f)
        }
        drawPath(strands, PROP_GOLD, alpha = alpha * 1.8f, style = Stroke(width = u * 0.006f))
    }
}

/**
 * 紫舞裙裙摆：三层弧形褶，越下面越深。
 *
 * 每层上沿再排一列短竖线 —— 少了这排褶痕，三层弧就只是三条彩带。
 */
private fun DrawScope.drawDressHem(
    color: Color,
    phase: Float,
    alpha: Float,
    w: Float,
    h: Float,
    u: Float
) {
    val ruffle = Path()
    val pleats = Path()
    repeat(3) { layer ->
        val top = h * (0.52f + layer * 0.15f)
        val sway = sin((phase + layer * 0.3f) * TAU) * u * 0.022f
        val depth = h * 0.13f
        ruffle.rewind()
        ruffle.moveTo(-w * 0.06f, top)
        ruffle.quadraticTo(w * 0.48f + sway, top + depth, w * 1.06f, top - h * 0.02f)
        ruffle.lineTo(w * 1.06f, top + h * 0.05f)
        ruffle.quadraticTo(w * 0.48f + sway, top + depth + h * 0.075f, -w * 0.06f, top + h * 0.06f)
        ruffle.close()
        drawPath(ruffle, color, alpha = alpha * (0.62f + layer * 0.18f))
        repeat(9) { index ->
            val t = (index + 0.5f) / 9f
            val px = -w * 0.06f + t * w * 1.12f
            // 上沿是条二次曲线，褶痕按同一条曲线取点才不会飘在布外面
            val inv = 1f - t
            val py = inv * inv * top + 2f * inv * t * (top + depth) + t * t * (top - h * 0.02f)
            pleats.moveTo(px, py)
            pleats.lineTo(px, py + h * 0.035f)
        }
    }
    drawPath(pleats, Color.White, alpha = alpha * 1.2f, style = Stroke(width = u * 0.008f))
}

/** 4 · Red：垂下的红围巾（针织罗纹 + 末端流苏）+ 一顶 fedora。 */
private fun DrawScope.drawRedScarf(color: Color, phase: Float, alpha: Float) {
    knitStripeTexture(color, phase)
    val box = propBox()
    val w = box.width
    val h = box.height
    val u = min(w, h)
    translate(left = box.left, top = box.top) {
        val half = w * 0.115f
        val endY = h * 0.60f
        val steps = 20
        // 中心线随相位轻摆。两条边共用同一条中心线，围巾才是「一条布」而不是两条曲线
        fun centerX(t: Float) = w * 0.34f + sin(t * 2.1f + phase * TAU) * w * 0.055f
        val scarf = Path()
        scarf.moveTo(centerX(0f) - half, 0f)
        for (step in 1..steps) {
            val t = step / steps.toFloat()
            scarf.lineTo(centerX(t) - half, t * endY)
        }
        for (step in steps downTo 0) {
            val t = step / steps.toFloat()
            scarf.lineTo(centerX(t) + half, t * endY)
        }
        scarf.close()
        drawPath(scarf, color, alpha = alpha * 0.8f)
        // 罗纹：四道贴边的纵向棱，加末端一小段横向针脚
        val ribs = Path()
        listOf(-0.78f, -0.44f, 0.44f, 0.78f).forEach { offset ->
            ribs.moveTo(centerX(0f) + offset * half, 0f)
            for (step in 1..steps) {
                val t = step / steps.toFloat()
                ribs.lineTo(centerX(t) + offset * half, t * endY)
            }
        }
        repeat(4) { row ->
            val t = 0.84f + row * 0.05f
            ribs.moveTo(centerX(t) - half, t * endY)
            ribs.lineTo(centerX(t) + half, t * endY)
        }
        drawPath(ribs, color, alpha = alpha * 0.55f, style = Stroke(width = u * 0.008f))
        // 流苏：末端 8 根，向外微微散开
        val fringe = Path()
        repeat(8) { index ->
            val spread = (index - 3.5f) / 3.5f
            val fx = centerX(1f) + spread * half * 0.92f
            fringe.moveTo(fx, endY)
            fringe.lineTo(fx + spread * u * 0.030f, endY + h * 0.085f)
        }
        drawPath(fringe, color, alpha = alpha * 0.9f, style = Stroke(width = u * 0.010f))
        drawFedora(color, alpha, w, h, u)
    }
}

/**
 * 一顶 fedora：帽檐（椭圆）+ 帽冠 + 帽带 + 冠顶折痕。
 *
 * 折痕是 fedora 与圆顶帽的唯一区别，省掉它画出来就是一顶礼帽。
 */
private fun DrawScope.drawFedora(color: Color, alpha: Float, w: Float, h: Float, u: Float) {
    val cx = w * 0.62f
    val brimY = h * 0.86f
    val brimW = w * 0.62f
    val brimH = h * 0.095f
    drawOval(
        color, Offset(cx - brimW / 2f, brimY - brimH / 2f), Size(brimW, brimH),
        alpha = alpha * 0.85f
    )
    // 前檐上翻：沿椭圆下半描一条深弧
    drawArc(
        color = color,
        startAngle = 20f,
        sweepAngle = 140f,
        useCenter = false,
        topLeft = Offset(cx - brimW / 2f, brimY - brimH / 2f),
        size = Size(brimW, brimH),
        alpha = alpha * 1.4f,
        style = Stroke(width = u * 0.008f)
    )
    val crownHalf = brimW * 0.30f
    val crownTop = brimY - h * 0.185f
    val crown = Path()
    crown.moveTo(cx - crownHalf, brimY)
    crown.cubicTo(
        cx - crownHalf * 1.04f, crownTop + h * 0.05f,
        cx - crownHalf * 0.88f, crownTop,
        cx - crownHalf * 0.34f, crownTop
    )
    crown.lineTo(cx + crownHalf * 0.34f, crownTop)
    crown.cubicTo(
        cx + crownHalf * 0.88f, crownTop,
        cx + crownHalf * 1.04f, crownTop + h * 0.05f,
        cx + crownHalf, brimY
    )
    crown.close()
    drawPath(crown, color, alpha = alpha)
    drawRect(
        color, Offset(cx - crownHalf * 0.99f, brimY - h * 0.058f),
        Size(crownHalf * 1.98f, h * 0.030f), alpha = alpha * 1.7f
    )
    val crease = Path()
    crease.moveTo(cx - crownHalf * 0.30f, crownTop + h * 0.004f)
    crease.quadraticTo(cx, crownTop + h * 0.045f, cx + crownHalf * 0.30f, crownTop + h * 0.004f)
    crease.moveTo(cx - crownHalf * 0.72f, crownTop + h * 0.030f)
    crease.lineTo(cx - crownHalf * 0.60f, crownTop + h * 0.075f)
    crease.moveTo(cx + crownHalf * 0.72f, crownTop + h * 0.030f)
    crease.lineTo(cx + crownHalf * 0.60f, crownTop + h * 0.075f)
    drawPath(crease, Color.White, alpha = alpha * 1.5f, style = Stroke(width = u * 0.009f))
}

/** 5 · 1989：宝丽来白框 + 一只海鸥。轻微倾斜，像随手摆上去的。 */
private fun DrawScope.drawPolaroidGull(color: Color, phase: Float, alpha: Float) {
    skyBandTexture(color)
    val box = propBox()
    val w = box.width
    val h = box.height
    val u = min(w, h)
    translate(left = box.left, top = box.top) {
        val frameW = w * 0.74f
        val frameH = frameW * 1.20f
        val center = Offset(w * 0.50f, h * 0.64f)
        rotate(degrees = -7f + sin(phase * TAU) * 1.5f, pivot = center) {
            translate(left = center.x - frameW / 2f, top = center.y - frameH / 2f) {
                // 白框画在白卡上没有边界，靠一圈描边把它「切」出来
                drawRect(Color.White, Offset.Zero, Size(frameW, frameH), alpha = 0.72f)
                drawRect(
                    color, Offset.Zero, Size(frameW, frameH),
                    alpha = alpha * 0.7f, style = Stroke(width = u * 0.010f)
                )
                // 相纸下缘的宽白边是宝丽来的辨识点：照面只占上 77%
                val photoInset = frameW * 0.07f
                val photoW = frameW - photoInset * 2f
                val photoH = frameH * 0.71f
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            color.copy(alpha = alpha * 2.2f),
                            color.copy(alpha = alpha * 0.5f)
                        ),
                        startY = photoInset,
                        endY = photoInset + photoH
                    ),
                    topLeft = Offset(photoInset, photoInset),
                    size = Size(photoW, photoH)
                )
                // 照面里一道地平线加一片海，才像一张照片而不是一块渐变色卡
                val horizon = photoInset + photoH * 0.62f
                drawRect(
                    color, Offset(photoInset, horizon),
                    Size(photoW, photoInset + photoH - horizon), alpha = alpha * 0.6f
                )
                drawLine(
                    color, Offset(photoInset, horizon), Offset(photoInset + photoW, horizon),
                    u * 0.007f, alpha = alpha * 1.8f
                )
                // 下白边上一道手写笔迹（写日期的那条），不写字：字会变成要翻译的内容
                drawLine(
                    color, Offset(frameW * 0.18f, frameH * 0.90f), Offset(frameW * 0.60f, frameH * 0.90f),
                    u * 0.008f, alpha = alpha * 0.9f
                )
            }
        }
        drawSeagull(color, phase, alpha, w, h, u)
    }
}

/**
 * 一只海鸥：两段弧线的翼展，翼尖随相位扑动。
 *
 * 只画翼展不画身子 —— 远处的海鸥本来就只剩这两笔，加了身子反而像蝙蝠。
 */
private fun DrawScope.drawSeagull(color: Color, phase: Float, alpha: Float, w: Float, h: Float, u: Float) {
    val cx = w * 0.24f
    val cy = h * 0.13f
    val span = w * 0.36f
    // 扑翼：翼尖上下 + 前缘弧度一起变，只动翼尖会像在摇尾巴
    val flap = sin(phase * TAU * 2f)
    val tipRise = span * 0.10f * flap
    val bend = span * 0.17f * (1f + 0.35f * flap)
    val gull = Path()
    gull.moveTo(cx - span * 0.5f, cy - tipRise)
    gull.quadraticTo(cx - span * 0.26f, cy - bend, cx, cy)
    gull.quadraticTo(cx + span * 0.26f, cy - bend, cx + span * 0.5f, cy - tipRise)
    drawPath(
        gull, color,
        alpha = alpha * 1.5f,
        style = Stroke(width = u * 0.014f, cap = StrokeCap.Round)
    )
}

/** 6 · reputation：盘绕的蛇（带鳞片与背脊高光）+ 一枚蛇戒。 */
private fun DrawScope.drawCoiledSnake(color: Color, phase: Float, lowRam: Boolean, alpha: Float) {
    halftoneTexture(color, phase, lowRam)
    val box = propBox()
    val w = box.width
    val h = box.height
    val u = min(w, h)
    translate(left = box.left, top = box.top) {
        val cx = w * 0.48f
        val cy = h * 0.34f
        val radius = w * 0.44f
        val turns = 1.8f
        // 整盘极慢地转（0.3 圈相位），读作活物而不是标本
        val spin = phase * 0.30f
        // y 压扁 0.76：这是俯视看下去的一盘蛇，不是正面看的一圈甜甜圈
        fun coilX(t: Float) = cx + cos(t * TAU * turns + spin) * radius * (1f - t * 0.58f)
        fun coilY(t: Float) = cy + sin(t * TAU * turns + spin) * radius * (1f - t * 0.58f) * 0.76f
        val steps = if (lowRam) 36 else 64
        val bodyWidth = u * 0.105f
        val body = Path()
        for (step in 0..steps) {
            val t = step / steps.toFloat()
            if (step == 0) body.moveTo(coilX(t), coilY(t)) else body.lineTo(coilX(t), coilY(t))
        }
        drawPath(
            body, color,
            alpha = alpha * 0.85f,
            style = Stroke(width = bodyWidth, cap = StrokeCap.Round)
        )
        // 背脊高光：同一条路径细描一遍，蛇身才有圆的体积
        drawPath(
            body, Color.White,
            alpha = alpha * 1.3f,
            style = Stroke(width = bodyWidth * 0.20f, cap = StrokeCap.Round)
        )
        // 鳞片：沿蛇身每格排三片、隔行错半格。66 片**合进一条 Path 一次描完** ——
        // 一片一次 drawPath 就是 66 个绘制调用
        val rows = if (lowRam) 12 else 22
        val scales = Path()
        for (row in 0 until rows) {
            val t = (row + 0.5f) / rows
            val px = coilX(t)
            val py = coilY(t)
            val dx = coilX(t + 0.01f) - px
            val dy = coilY(t + 0.01f) - py
            val len = sqrt(dx * dx + dy * dy)
            if (len < 0.0001f) continue
            val ux = dx / len
            val uy = dy / len
            val ox = -uy
            val oy = ux
            val reach = bodyWidth * 0.17f
            repeat(3) { lane ->
                val across = (lane - 1) * bodyWidth * 0.30f +
                    if (row % 2 == 0) 0f else bodyWidth * 0.15f
                val sx = px + ox * across
                val sy = py + oy * across
                scales.moveTo(sx - ox * reach, sy - oy * reach)
                // 鳞的自由边朝尾（-u 方向），顺着才叠得住
                scales.quadraticTo(
                    sx - ux * reach * 1.5f, sy - uy * reach * 1.5f,
                    sx + ox * reach, sy + oy * reach
                )
            }
        }
        drawPath(scales, color, alpha = alpha * 1.5f, style = Stroke(width = u * 0.005f))
        drawSnakeHead(color, phase, alpha, u, Offset(coilX(1f), coilY(1f)), Offset(coilX(0.97f), coilY(0.97f)), bodyWidth)
        drawSnakeRing(color, alpha, w, h, u)
    }
}

/**
 * 蛇头：吻端 + 两只眼 + 分叉的舌。
 *
 * 方向由盘绕末端两点决定（[snout] 与它前一格 [neck]），所以头永远是顺着蛇身长出来的，
 * 不用给每张卡片手调角度。
 */
private fun DrawScope.drawSnakeHead(
    color: Color,
    phase: Float,
    alpha: Float,
    u: Float,
    snout: Offset,
    neck: Offset,
    bodyWidth: Float
) {
    val dx = snout.x - neck.x
    val dy = snout.y - neck.y
    val len = sqrt(dx * dx + dy * dy)
    if (len < 0.0001f) return
    val ux = dx / len
    val uy = dy / len
    val ox = -uy
    val oy = ux
    val headLen = u * 0.15f
    val half = bodyWidth * 0.46f
    val head = Path()
    head.moveTo(snout.x + ox * half, snout.y + oy * half)
    head.quadraticTo(
        snout.x + ux * headLen * 0.8f + ox * half * 0.8f,
        snout.y + uy * headLen * 0.8f + oy * half * 0.8f,
        snout.x + ux * headLen, snout.y + uy * headLen
    )
    head.quadraticTo(
        snout.x + ux * headLen * 0.8f - ox * half * 0.8f,
        snout.y + uy * headLen * 0.8f - oy * half * 0.8f,
        snout.x - ox * half, snout.y - oy * half
    )
    head.close()
    drawPath(head, color, alpha = alpha * 1.25f)
    repeat(2) { side ->
        val eyeAcross = if (side == 0) half * 0.48f else -half * 0.48f
        drawCircle(
            Color.White,
            u * 0.011f,
            Offset(
                snout.x + ux * headLen * 0.42f + ox * eyeAcross,
                snout.y + uy * headLen * 0.42f + oy * eyeAcross
            ),
            alpha = alpha * 2.2f
        )
    }
    // 舌：随相位吐进吐出，一帧一个长度，不用另开时钟
    val flick = u * (0.04f + 0.045f * (0.5f + 0.5f * sin(phase * TAU * 3f)))
    val tip = Offset(snout.x + ux * headLen, snout.y + uy * headLen)
    val tongue = Path()
    tongue.moveTo(tip.x, tip.y)
    tongue.lineTo(tip.x + ux * flick, tip.y + uy * flick)
    tongue.lineTo(tip.x + ux * flick * 1.6f + ox * flick * 0.5f, tip.y + uy * flick * 1.6f + oy * flick * 0.5f)
    tongue.moveTo(tip.x + ux * flick, tip.y + uy * flick)
    tongue.lineTo(tip.x + ux * flick * 1.6f - ox * flick * 0.5f, tip.y + uy * flick * 1.6f - oy * flick * 0.5f)
    drawPath(tongue, color, alpha = alpha * 1.8f, style = Stroke(width = u * 0.006f))
}

/**
 * 蛇戒：指环 + 环顶盘着的一只小蛇头。
 *
 * 指环故意画成**开口的两段弧**（缺口在环顶），蛇头咬在缺口上 ——
 * 闭合的圆环加一个头会读成「戒指上粘了个东西」。
 */
private fun DrawScope.drawSnakeRing(color: Color, alpha: Float, w: Float, h: Float, u: Float) {
    val center = Offset(w * 0.74f, h * 0.88f)
    val radius = u * 0.115f
    val band = Stroke(width = u * 0.026f)
    drawArc(
        color = color,
        startAngle = -60f,
        sweepAngle = 200f,
        useCenter = false,
        topLeft = Offset(center.x - radius, center.y - radius),
        size = Size(radius * 2f, radius * 2f),
        alpha = alpha * 1.5f,
        style = band
    )
    drawArc(
        color = color,
        startAngle = 160f,
        sweepAngle = 70f,
        useCenter = false,
        topLeft = Offset(center.x - radius, center.y - radius),
        size = Size(radius * 2f, radius * 2f),
        alpha = alpha * 1.1f,
        style = Stroke(width = u * 0.018f)
    )
    // 环顶的蛇头：一个水滴 + 一只眼
    val headCenter = Offset(center.x + radius * 0.52f, center.y - radius * 0.86f)
    val headPath = Path()
    headPath.moveTo(headCenter.x - u * 0.030f, headCenter.y + u * 0.022f)
    headPath.quadraticTo(
        headCenter.x - u * 0.034f, headCenter.y - u * 0.030f,
        headCenter.x + u * 0.014f, headCenter.y - u * 0.034f
    )
    headPath.quadraticTo(
        headCenter.x + u * 0.048f, headCenter.y - u * 0.020f,
        headCenter.x + u * 0.020f, headCenter.y + u * 0.026f
    )
    headPath.close()
    drawPath(headPath, color, alpha = alpha * 1.8f)
    drawCircle(Color.White, u * 0.008f, Offset(headCenter.x + u * 0.008f, headCenter.y - u * 0.012f), alpha = alpha * 2.4f)
}

/** 7 · Lover：Lover House 小屋侧影（亮灯窗格）+ 一只蝴蝶。 */
private fun DrawScope.drawLoverHouse(color: Color, phase: Float, alpha: Float) {
    pastelCloudTexture(color, phase)
    val box = propBox()
    val w = box.width
    val h = box.height
    val u = min(w, h)
    translate(left = box.left, top = box.top) {
        val bodyTop = h * 0.50f
        val bodyBottom = h * 0.92f
        drawRect(color, Offset(w * 0.18f, bodyTop), Size(w * 0.60f, bodyBottom - bodyTop), alpha = alpha * 0.8f)
        // 烟囱在屋顶**之前**画：让屋顶盖住它下半截，露出的那截才像插在坡上
        drawRect(color, Offset(w * 0.62f, h * 0.24f), Size(w * 0.10f, h * 0.20f), alpha = alpha)
        drawRect(color, Offset(w * 0.59f, h * 0.232f), Size(w * 0.16f, h * 0.022f), alpha = alpha * 1.3f)
        val roof = Path()
        roof.moveTo(w * 0.08f, bodyTop + h * 0.02f)
        roof.lineTo(w * 0.48f, h * 0.24f)
        roof.lineTo(w * 0.88f, bodyTop + h * 0.02f)
        roof.close()
        drawPath(roof, color, alpha = alpha * 1.15f)
        // 两扇亮灯窗：暖光比主色亮，压不到曲目名，所以敢开到高不透明度
        repeat(2) { index ->
            val left = w * (0.24f + index * 0.32f)
            val top = h * 0.58f
            val windowW = w * 0.16f
            val windowH = h * 0.14f
            val center = Offset(left + windowW / 2f, top + windowH / 2f)
            val breathe = 0.82f + 0.18f * sin((phase + index * 0.4f) * TAU)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(PROP_WARM.copy(alpha = 0.55f * breathe), Color.Transparent),
                    center = center,
                    radius = windowW * 1.7f
                ),
                radius = windowW * 1.7f,
                center = center
            )
            drawRect(PROP_WARM, Offset(left, top), Size(windowW, windowH), alpha = 0.80f * breathe)
            drawRect(
                color, Offset(left, top), Size(windowW, windowH),
                alpha = alpha * 1.5f, style = Stroke(width = u * 0.010f)
            )
            // 窗格：一横一竖，四格窗是「有人在家」最省笔墨的说法
            drawLine(color, Offset(center.x, top), Offset(center.x, top + windowH), u * 0.008f, alpha = alpha * 1.5f)
            drawLine(color, Offset(left, center.y), Offset(left + windowW, center.y), u * 0.008f, alpha = alpha * 1.5f)
        }
        drawRect(color, Offset(w * 0.43f, h * 0.70f), Size(w * 0.10f, bodyBottom - h * 0.70f), alpha = alpha * 1.4f)
        drawCircle(PROP_WARM, u * 0.011f, Offset(w * 0.515f, h * 0.79f), alpha = 0.85f)
        drawButterfly(color, phase, alpha, w, h, u)
    }
}

/**
 * 一只蝴蝶：两对翼瓣 + 身子 + 触角。
 *
 * 右半边靠 `scale(-1)` 把左半边镜像过去 —— 手写两遍几何必然有一天只改了一边。
 * 扑翼只压翼展的 x（[flap]），不动 y：蝴蝶扇翅膀时看到的正是投影变窄。
 */
private fun DrawScope.drawButterfly(color: Color, phase: Float, alpha: Float, w: Float, h: Float, u: Float) {
    val bx = w * 0.26f
    val by = h * 0.12f
    val span = u * 0.30f
    val flap = 0.42f + 0.58f * abs(sin(phase * TAU * 1.5f))
    val fore = Path()
    fore.moveTo(bx, by)
    fore.cubicTo(
        bx - span * 0.95f * flap, by - span * 0.62f,
        bx - span * 1.02f * flap, by + span * 0.16f,
        bx - span * 0.06f * flap, by + span * 0.14f
    )
    fore.close()
    val hind = Path()
    hind.moveTo(bx - span * 0.04f * flap, by + span * 0.16f)
    hind.cubicTo(
        bx - span * 0.72f * flap, by + span * 0.30f,
        bx - span * 0.52f * flap, by + span * 0.76f,
        bx, by + span * 0.34f
    )
    hind.close()
    // 左半边照画，右半边以身子为轴镜像 —— 手写两遍几何必然有一天只改了一边
    butterflyHalf(fore, hind, color, alpha, u)
    scale(-1f, 1f, pivot = Offset(bx, by)) { butterflyHalf(fore, hind, color, alpha, u) }
    // 身子：三节。触角末端两个小点，缺了这两点读起来像一片叶子
    drawRoundRect(
        color, Offset(bx - u * 0.012f, by - span * 0.06f), Size(u * 0.024f, span * 0.46f),
        CornerRadius(u * 0.012f), alpha = alpha * 1.9f
    )
    val antenna = Path()
    antenna.moveTo(bx, by - span * 0.04f)
    antenna.quadraticTo(bx - span * 0.14f, by - span * 0.26f, bx - span * 0.20f, by - span * 0.34f)
    antenna.moveTo(bx, by - span * 0.04f)
    antenna.quadraticTo(bx + span * 0.14f, by - span * 0.26f, bx + span * 0.20f, by - span * 0.34f)
    drawPath(antenna, color, alpha = alpha * 1.6f, style = Stroke(width = u * 0.005f))
    drawCircle(color, u * 0.008f, Offset(bx - span * 0.20f, by - span * 0.34f), alpha = alpha * 1.8f)
    drawCircle(color, u * 0.008f, Offset(bx + span * 0.20f, by - span * 0.34f), alpha = alpha * 1.8f)
}

/** 蝴蝶的一半：前翼实心、后翼淡一档、前翼再描一圈亮边（翼脉的意思）。 */
private fun DrawScope.butterflyHalf(fore: Path, hind: Path, color: Color, alpha: Float, u: Float) {
    drawPath(fore, color, alpha = alpha * 1.5f)
    drawPath(hind, color, alpha = alpha * 1.2f)
    drawPath(fore, Color.White, alpha = alpha, style = Stroke(width = u * 0.006f))
}

/** 8 · folklore：开衫挂在木椅背上 + 一枝松枝。 */
private fun DrawScope.drawCardiganChair(color: Color, phase: Float, alpha: Float) {
    fogBandTexture(color, phase)
    val box = propBox()
    val w = box.width
    val h = box.height
    val u = min(w, h)
    translate(left = box.left, top = box.top) {
        // 椅背：两根立柱 + 顶横档 + 一道下横档。椅子先画，开衫盖在上面才是「挂着」
        val railY = h * 0.18f
        val postW = u * 0.048f
        drawRect(color, Offset(w * 0.14f, railY), Size(postW, h * 0.76f), alpha = alpha * 0.75f)
        drawRect(color, Offset(w * 0.80f, railY), Size(postW, h * 0.76f), alpha = alpha * 0.75f)
        drawRoundRect(
            color, Offset(w * 0.10f, railY - u * 0.032f), Size(w * 0.78f, u * 0.074f),
            CornerRadius(u * 0.036f), alpha = alpha * 0.9f
        )
        drawRect(color, Offset(w * 0.14f, h * 0.86f), Size(w * 0.70f, u * 0.040f), alpha = alpha * 0.6f)
        drawCardigan(color, alpha, w, h, u)
        drawPineSprig(color, alpha, w, h, u)
    }
}

/**
 * 一件开衫：两片前襟 + V 领 + 一排扣子 + 两只垂下的袖子 + 针织罗纹。
 *
 * 罗纹靠 `clipPath` 卡在衣身里 —— 不裁的话那几十条竖线会糊到椅子和卡片上，
 * 一眼就露出是「画上去的纹理」而不是毛线。
 */
private fun DrawScope.drawCardigan(color: Color, alpha: Float, w: Float, h: Float, u: Float) {
    val top = h * 0.20f
    val bottom = h * 0.74f
    val body = Path()
    body.moveTo(w * 0.30f, top)
    body.lineTo(w * 0.68f, top)
    body.cubicTo(w * 0.76f, h * 0.38f, w * 0.74f, h * 0.58f, w * 0.71f, bottom)
    body.quadraticTo(w * 0.49f, bottom + h * 0.045f, w * 0.27f, bottom)
    body.cubicTo(w * 0.24f, h * 0.58f, w * 0.22f, h * 0.38f, w * 0.30f, top)
    body.close()
    drawPath(body, color, alpha = alpha * 0.7f)
    clipPath(body) {
        val ribs = Path()
        var x = w * 0.18f
        while (x < w * 0.78f) {
            ribs.moveTo(x, top - h * 0.02f)
            ribs.lineTo(x + u * 0.022f, bottom + h * 0.08f)
            x += u * 0.044f
        }
        drawPath(ribs, color, alpha = alpha * 0.5f, style = Stroke(width = u * 0.011f))
    }
    // V 领：加粗描一条折线就够，领子本来就是双层布，本该比衣身深
    val collar = Path()
    collar.moveTo(w * 0.30f, top)
    collar.lineTo(w * 0.49f, h * 0.40f)
    collar.lineTo(w * 0.68f, top)
    drawPath(collar, color, alpha = alpha * 1.25f, style = Stroke(width = u * 0.046f))
    drawLine(
        color, Offset(w * 0.49f, h * 0.40f), Offset(w * 0.49f, bottom + h * 0.02f),
        u * 0.020f, alpha = alpha * 1.2f
    )
    repeat(5) { index ->
        val cy = h * (0.45f + index * 0.062f)
        drawCircle(color, u * 0.024f, Offset(w * 0.49f, cy), alpha = alpha * 1.9f)
        drawCircle(Color.White, u * 0.009f, Offset(w * 0.49f, cy), alpha = alpha * 1.8f)
    }
    val sleeve = Path()
    sleeve.moveTo(w * 0.31f, h * 0.22f)
    sleeve.cubicTo(w * 0.18f, h * 0.36f, w * 0.11f, h * 0.54f, w * 0.12f, h * 0.70f)
    sleeve.lineTo(w * 0.23f, h * 0.71f)
    sleeve.cubicTo(w * 0.23f, h * 0.54f, w * 0.27f, h * 0.38f, w * 0.38f, h * 0.26f)
    sleeve.close()
    val cuff = Path()
    repeat(3) { index ->
        val y = h * (0.655f + index * 0.019f)
        cuff.moveTo(w * 0.12f, y)
        cuff.lineTo(w * 0.23f, y)
    }
    cardiganSleeve(sleeve, cuff, color, alpha, u)
    scale(-1f, 1f, pivot = Offset(w * 0.49f, 0f)) { cardiganSleeve(sleeve, cuff, color, alpha, u) }
}

/** 一只袖子 + 袖口罗纹。右袖是左袖的镜像，几何只写一遍。 */
private fun DrawScope.cardiganSleeve(sleeve: Path, cuff: Path, color: Color, alpha: Float, u: Float) {
    drawPath(sleeve, color, alpha = alpha * 0.85f)
    drawPath(cuff, color, alpha = alpha * 1.3f, style = Stroke(width = u * 0.008f))
}

/**
 * 一枝松枝：主枝 + 两侧针叶。
 *
 * 针叶全部合进一条 Path 一次描完，而且**越靠枝梢越短** ——
 * 等长的针叶排出来是一把梳子。
 */
private fun DrawScope.drawPineSprig(color: Color, alpha: Float, w: Float, h: Float, u: Float) {
    val fromX = w * 0.04f
    val fromY = h * 0.98f
    val toX = w * 0.34f
    val toY = h * 0.70f
    drawLine(color, Offset(fromX, fromY), Offset(toX, toY), u * 0.014f, alpha = alpha * 1.3f)
    val needles = Path()
    repeat(8) { index ->
        val t = 0.12f + index * 0.11f
        val px = fromX + (toX - fromX) * t
        val py = fromY + (toY - fromY) * t
        val len = u * 0.13f * (1f - t * 0.55f)
        needles.moveTo(px, py)
        needles.lineTo(px - len * 0.55f, py - len * 0.85f)
        needles.moveTo(px, py)
        needles.lineTo(px + len * 0.90f, py - len * 0.42f)
    }
    drawPath(needles, color, alpha = alpha * 1.1f, style = Stroke(width = u * 0.006f))
}

/** 9 · evermore：三股辫 + 橙棕格纹布角。 */
private fun DrawScope.drawBraidPlaid(color: Color, phase: Float, lowRam: Boolean, alpha: Float) {
    branchTexture(color, phase, lowRam)
    val box = propBox()
    val w = box.width
    val h = box.height
    val u = min(w, h)
    translate(left = box.left, top = box.top) {
        // 三股辫：三条相位各差 1/3 的正弦交叠（沿用原来的做法，只是从横向转成竖向 ——
        // 32% 一列里横着编放不下）
        val braidCx = w * 0.30f
        val top = h * 0.04f
        val length = h * 0.56f
        val amplitude = w * 0.085f
        val strand = Path()
        repeat(3) { index ->
            strand.rewind()
            for (step in 0..40) {
                val t = step / 40f
                val x = braidCx + sin(t * TAU * 2.4f + index * TAU / 3f + phase * TAU) * amplitude
                val y = top + t * length
                if (step == 0) strand.moveTo(x, y) else strand.lineTo(x, y)
            }
            drawPath(
                strand, color,
                alpha = alpha * (0.7f + index * 0.15f),
                style = Stroke(width = u * 0.055f, cap = StrokeCap.Round)
            )
        }
        // 发圈 + 散出来的发梢：辫子得有个收头，不然是三根缠在一起的绳
        val tieY = top + length
        drawRoundRect(
            color, Offset(braidCx - u * 0.048f, tieY), Size(u * 0.096f, u * 0.052f),
            CornerRadius(u * 0.022f), alpha = alpha * 1.9f
        )
        val ends = Path()
        repeat(5) { index ->
            val spread = (index - 2) / 2f
            ends.moveTo(braidCx + spread * u * 0.022f, tieY + u * 0.052f)
            ends.lineTo(braidCx + spread * u * 0.075f, tieY + u * 0.052f + h * 0.055f)
        }
        drawPath(ends, color, alpha = alpha * 0.9f, style = Stroke(width = u * 0.008f))
        drawPlaidCorner(color, alpha, w, h, u)
    }
}

/**
 * 格纹布角。
 *
 * 交叉处的深格**不另算颜色** —— 纵横两组半透明带子叠在一起自然就深一档，
 * 这也正是真格纹的成因（同一根线织两遍）。下沿再留几根线头，布才有断口。
 */
private fun DrawScope.drawPlaidCorner(color: Color, alpha: Float, w: Float, h: Float, u: Float) {
    val cloth = Path()
    cloth.moveTo(w * 0.22f, h * 0.80f)
    cloth.quadraticTo(w * 0.62f, h * 0.70f, w * 1.04f, h * 0.62f)
    cloth.lineTo(w * 1.04f, h * 1.02f)
    cloth.lineTo(w * 0.30f, h * 1.02f)
    cloth.close()
    drawPath(cloth, color, alpha = alpha * 0.32f)
    clipPath(cloth) {
        val pitch = u * 0.15f
        var x = w * 0.20f
        while (x < w * 1.06f) {
            drawRect(color, Offset(x, h * 0.58f), Size(u * 0.085f, h * 0.46f), alpha = alpha * 0.5f)
            x += pitch
        }
        var y = h * 0.62f
        while (y < h * 1.04f) {
            drawRect(color, Offset(w * 0.18f, y), Size(w * 0.90f, u * 0.085f), alpha = alpha * 0.5f)
            y += pitch
        }
        val hairlines = Path()
        var hx = w * 0.20f + u * 0.118f
        while (hx < w * 1.06f) {
            hairlines.moveTo(hx, h * 0.58f)
            hairlines.lineTo(hx, h * 1.04f)
            hx += pitch
        }
        var hy = h * 0.62f + u * 0.118f
        while (hy < h * 1.04f) {
            hairlines.moveTo(w * 0.18f, hy)
            hairlines.lineTo(w * 1.08f, hy)
            hy += pitch
        }
        drawPath(hairlines, color, alpha = alpha * 0.9f, style = Stroke(width = u * 0.006f))
    }
    val frayed = Path()
    repeat(6) { index ->
        val fx = w * (0.36f + index * 0.11f)
        frayed.moveTo(fx, h * 1.00f)
        frayed.lineTo(fx + (index % 2 - 0.5f) * u * 0.02f, h * 1.05f)
    }
    drawPath(frayed, color, alpha = alpha * 0.8f, style = Stroke(width = u * 0.005f))
}

/** 10 · Midnights：一只打火机（风罩栅格 + 拨轮 + 呼吸的火苗）+ 几颗四芒星。 */
private fun DrawScope.drawLighterStars(color: Color, phase: Float, alpha: Float) {
    starburstTexture(color, phase)
    val box = propBox()
    val w = box.width
    val h = box.height
    val u = min(w, h)
    translate(left = box.left, top = box.top) {
        val bodyL = w * 0.34f
        val bodyR = w * 0.66f
        val bodyTop = h * 0.54f
        val bodyBottom = h * 0.92f
        val guardTop = h * 0.45f
        drawRoundRect(
            color, Offset(bodyL, bodyTop), Size(bodyR - bodyL, bodyBottom - bodyTop),
            CornerRadius(u * 0.030f), alpha = alpha * 0.95f
        )
        // 左侧一条白竖带：金属外壳的反光，没有它就是一块深色积木
        drawRect(
            Color.White, Offset(bodyL + u * 0.022f, bodyTop + u * 0.03f),
            Size(u * 0.020f, bodyBottom - bodyTop - u * 0.08f), alpha = alpha * 1.4f
        )
        drawLine(
            color, Offset(bodyR - u * 0.024f, bodyTop), Offset(bodyR - u * 0.024f, bodyBottom),
            u * 0.006f, alpha = alpha * 1.5f
        )
        // 风罩：矮矩形 + 镂空栅格。栅格用白线（镂空处透光），不是深线
        drawRect(
            color, Offset(bodyL + u * 0.014f, guardTop),
            Size(bodyR - bodyL - u * 0.028f, bodyTop - guardTop), alpha = alpha * 0.9f
        )
        val grid = Path()
        repeat(4) { index ->
            val gx = bodyL + u * 0.034f + index * (bodyR - bodyL - u * 0.068f) / 3f
            grid.moveTo(gx, guardTop + u * 0.010f)
            grid.lineTo(gx, bodyTop - u * 0.010f)
        }
        repeat(2) { index ->
            val gy = guardTop + (index + 1) * (bodyTop - guardTop) / 3f
            grid.moveTo(bodyL + u * 0.026f, gy)
            grid.lineTo(bodyR - u * 0.026f, gy)
        }
        drawPath(grid, Color.White, alpha = alpha * 1.6f, style = Stroke(width = u * 0.006f))
        // 拨轮 + 滚花
        val wheel = Offset(bodyR - u * 0.052f, guardTop + u * 0.020f)
        drawCircle(color, u * 0.038f, wheel, alpha = alpha * 1.6f)
        val knurl = Path()
        repeat(7) { index ->
            val angle = index / 7f * TAU
            knurl.moveTo(wheel.x + cos(angle) * u * 0.017f, wheel.y + sin(angle) * u * 0.017f)
            knurl.lineTo(wheel.x + cos(angle) * u * 0.036f, wheel.y + sin(angle) * u * 0.036f)
        }
        drawPath(knurl, Color.White, alpha = alpha * 1.7f, style = Stroke(width = u * 0.005f))
        drawFlame(phase, alpha, w, h, u, guardTop)
        drawBrightStars(color, phase, alpha, w, h, u)
    }
}

/**
 * 火苗：底宽顶尖的水滴，宽度随相位呼吸。
 *
 * 不吃时代主色 —— Midnights 是深蓝，蓝火苗读作煤气灶。外焰用 [PROP_WARM]、
 * 内焰用 [PROP_GOLD]，在白卡上都还剩得下辨识度。
 */
private fun DrawScope.drawFlame(phase: Float, alpha: Float, w: Float, h: Float, u: Float, baseY: Float) {
    val flameHeight = h * 0.26f
    val flameWidth = w * (0.052f + 0.012f * sin(phase * TAU * 3f))
    val baseX = w * 0.48f
    val flame = Path()
    flame.moveTo(baseX, baseY)
    flame.cubicTo(
        baseX - flameWidth, baseY - flameHeight * 0.45f,
        baseX - flameWidth * 0.35f, baseY - flameHeight * 0.80f,
        baseX, baseY - flameHeight
    )
    flame.cubicTo(
        baseX + flameWidth * 0.35f, baseY - flameHeight * 0.80f,
        baseX + flameWidth, baseY - flameHeight * 0.45f,
        baseX, baseY
    )
    flame.close()
    drawPath(
        path = flame,
        brush = Brush.verticalGradient(
            colors = listOf(PROP_WARM.copy(alpha = 0.30f), PROP_WARM.copy(alpha = 0.85f)),
            startY = baseY - flameHeight,
            endY = baseY
        )
    )
    val core = Path()
    core.moveTo(baseX, baseY - u * 0.01f)
    core.cubicTo(
        baseX - flameWidth * 0.40f, baseY - flameHeight * 0.32f,
        baseX - flameWidth * 0.14f, baseY - flameHeight * 0.48f,
        baseX, baseY - flameHeight * 0.58f
    )
    core.cubicTo(
        baseX + flameWidth * 0.14f, baseY - flameHeight * 0.48f,
        baseX + flameWidth * 0.40f, baseY - flameHeight * 0.32f,
        baseX, baseY - u * 0.01f
    )
    core.close()
    drawPath(core, PROP_GOLD, alpha = 0.75f)
}

/** 打火机旁的几颗亮星：比底纹那批粗一档，带柔光晕，随相位各自闪。 */
private fun DrawScope.drawBrightStars(color: Color, phase: Float, alpha: Float, w: Float, h: Float, u: Float) {
    val random = Random(2012)
    repeat(4) {
        val center = Offset(
            (0.08f + random.nextFloat() * 0.84f) * w,
            (0.02f + random.nextFloat() * 0.30f) * h
        )
        val arm = u * (0.05f + random.nextFloat() * 0.035f)
        val twinkle = 0.45f + 0.55f * (0.5f + 0.5f * sin((phase + random.nextFloat()) * TAU))
        val starAlpha = alpha * 2.2f * twinkle
        val thin = u * 0.008f
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(color.copy(alpha = alpha * 0.7f * twinkle), Color.Transparent),
                center = center,
                radius = arm * 1.6f
            ),
            radius = arm * 1.6f,
            center = center
        )
        drawLine(color, center - Offset(arm, 0f), center + Offset(arm, 0f), thin, alpha = starAlpha)
        drawLine(color, center - Offset(0f, arm), center + Offset(0f, arm), thin, alpha = starAlpha)
        val diag = arm * 0.42f
        drawLine(color, center - Offset(diag, diag), center + Offset(diag, diag), thin * 0.6f, alpha = starAlpha * 0.7f)
        drawLine(color, center - Offset(diag, -diag), center + Offset(diag, -diag), thin * 0.6f, alpha = starAlpha * 0.7f)
    }
}

/** TTPD 的墨色。见 [manuscriptLineTexture] 上面那段：这一张不吃时代主色。 */
private val LETTER_INK = Color(0xFF4A453E)

/** 米白麻纸。比白卡再暖一点、深一点，不然纸放在卡片上没有边界。 */
private val LETTER_PAPER = Color(0xFFEFE7D6)

/** 写字动画的行数。3 行够读出「在写」，再多每行就短得看不出笔迹。 */
private const val LETTER_LINES = 3

/** 写字占相位的比例；剩下的 12% 让墨迹淡掉再从头写。 */
private const val LETTER_WRITE_SPAN = 0.88f

/**
 * 第 [index] 行的手写笔迹路径。
 *
 * 三行各不相同（行高、行长、振幅都错开）—— 三条一样的曲线摞在一起读作花纹，
 * 不是字。写进传进来的 [path]，调用方复用同一个对象。
 */
private fun writingLine(path: Path, index: Int, left: Float, top: Float, width: Float, height: Float) {
    val y = top + height * (0.30f + index * 0.19f)
    val x0 = left + width * 0.12f
    val x1 = left + width * (0.60f + (index % 2) * 0.22f)
    val run = x1 - x0
    val amp = height * (0.035f + index * 0.008f)
    path.moveTo(x0, y)
    path.cubicTo(x0 + run * 0.22f, y - amp, x0 + run * 0.42f, y + amp, x0 + run * 0.60f, y)
    path.cubicTo(x0 + run * 0.76f, y - amp * 0.85f, x0 + run * 0.90f, y + amp * 0.95f, x1, y - amp * 0.2f)
}

/**
 * 11 · TTPD：米白麻纸信纸 + 羽毛笔（正在写）+ 墨水瓶 + 方块游标。
 *
 * 唯一一个不吃 `color` 的母题：TTPD 主色是近白的 `#F5F1EA`，用它在白卡上画等于没画，
 * 所以墨与纸都是硬编码的（[LETTER_INK] / [LETTER_PAPER]）。主色那层薄底由卡片自己铺。
 */
private fun DrawScope.drawLetterQuill(phase: Float, alpha: Float) {
    manuscriptLineTexture()
    val box = propBox()
    val w = box.width
    val h = box.height
    val u = min(w, h)
    translate(left = box.left, top = box.top) {
        val paperL = w * 0.02f
        val paperT = h * 0.08f
        val paperW = w * 0.82f
        val paperH = h * 0.54f
        val paperR = paperL + paperW
        val paperB = paperT + paperH
        // 整张纸连纸上的字、游标、羽毛笔一起歪 3.5°，像随手摊在桌上。
        // 墨水瓶**不进**这个 rotate —— 瓶子歪着读起来就是要倒了
        rotate(degrees = -3.5f, pivot = Offset(paperL + paperW / 2f, paperT + paperH / 2f)) {
            drawRect(LETTER_PAPER, Offset(paperL, paperT), Size(paperW, paperH), alpha = 0.80f)
            drawRect(
                LETTER_INK, Offset(paperL, paperT), Size(paperW, paperH),
                alpha = alpha * 0.8f, style = Stroke(width = u * 0.007f)
            )
            // 纸浆纤维：固定种子。每帧换一批纤维会像一层噪点在纸上爬
            val random = Random(2024)
            val fibers = Path()
            repeat(32) {
                val fx = paperL + random.nextFloat() * paperW
                val fy = paperT + random.nextFloat() * paperH
                val len = u * (0.02f + random.nextFloat() * 0.055f)
                // 纤维大致同向（抄纸时纸浆的走向），角度只在 ±17° 里抖
                val angle = (random.nextFloat() - 0.5f) * 0.6f
                fibers.moveTo(fx, fy)
                fibers.lineTo(fx + cos(angle) * len, fy + sin(angle) * len)
            }
            drawPath(fibers, LETTER_INK, alpha = alpha * 0.5f, style = Stroke(width = u * 0.004f))
            // 卷边：右下角翻起一小片，背面比正面亮
            val curl = u * 0.11f
            val fold = Path()
            fold.moveTo(paperR - curl, paperB)
            fold.quadraticTo(paperR - curl * 0.30f, paperB - curl * 0.30f, paperR, paperB - curl)
            fold.lineTo(paperR, paperB)
            fold.close()
            drawPath(fold, Color.White, alpha = 0.60f)
            drawPath(fold, LETTER_INK, alpha = alpha * 0.9f, style = Stroke(width = u * 0.005f))
            drawWriting(phase, alpha, u, paperL, paperT, paperW, paperH)
        }
        drawInkBottle(alpha, w, h, u)
    }
}

/**
 * 写字：笔尖沿 [writingLine] 的三次贝塞尔移动，墨迹用 `PathMeasure.getSegment`
 * 沿**同一条**路径同步渐显。
 *
 * 笔尖位置也从同一个 `PathMeasure` 取（`getPosition(已写长度)`）——
 * 自己按 t 解贝塞尔会和按弧长揭示的墨迹差半个字，笔尖就会飘在墨迹前面。
 *
 * 一行走完笔跳回下一行行首；末 12% 相位让整页墨迹淡掉再从头写 ——
 * 硬切回第一行会闪一下。
 */
private fun DrawScope.drawWriting(
    phase: Float,
    alpha: Float,
    u: Float,
    paperL: Float,
    paperT: Float,
    paperW: Float,
    paperH: Float
) {
    val fade = if (phase <= LETTER_WRITE_SPAN) {
        1f
    } else {
        ((1f - phase) / (1f - LETTER_WRITE_SPAN)).coerceIn(0f, 1f)
    }
    val cursor = (phase / LETTER_WRITE_SPAN * LETTER_LINES).coerceIn(0f, LETTER_LINES - 0.001f)
    val activeLine = cursor.toInt()
    val lineProgress = cursor - activeLine
    val measure = PathMeasure()
    val line = Path()
    val trail = Path()
    var nib = Offset.Unspecified
    for (index in 0..activeLine) {
        line.rewind()
        writingLine(line, index, paperL, paperT, paperW, paperH)
        measure.setPath(line, false)
        val total = measure.length
        val written = if (index < activeLine) total else total * lineProgress
        if (written <= u * 0.01f) continue
        trail.rewind()
        measure.getSegment(0f, written, trail, true)
        drawPath(
            trail, LETTER_INK,
            alpha = alpha * 1.9f * fade,
            style = Stroke(width = u * 0.011f, cap = StrokeCap.Round)
        )
        if (index == activeLine) nib = measure.getPosition(written)
    }
    if (!nib.isSpecified) return
    // 方块游标：跟着笔尖，1 个相位周期闪两拍（3.6s → 约 0.55Hz）。
    // 跟着相位而不是自己读时钟，低端机整块定格时它才停得住
    val blink = if (sin(phase * TAU * 2f) > 0f) 1f else 0f
    drawRect(
        LETTER_INK,
        Offset(nib.x + u * 0.014f, nib.y - u * 0.046f),
        Size(u * 0.020f, u * 0.050f),
        alpha = alpha * 2.2f * blink * fade
    )
    drawQuill(phase, alpha, u, nib)
}

/**
 * 羽毛笔：中央羽轴 + 两侧斜向羽枝 + 笔尖，斜插姿态。
 *
 * 笔尖钉在 [nib]（当前写到的位置）上，所以笔是「跟着字走」的。羽枝长度按纺锤形
 * 分布（中段最长）并全部合进一条 Path —— 等长羽枝排出来是一把梳子，
 * 一根一次 drawPath 则是 28 个绘制调用。
 */
private fun DrawScope.drawQuill(phase: Float, alpha: Float, u: Float, nib: Offset) {
    // 斜插约 59°，笔杆随相位极轻地转，读作握在手里而不是插在纸上
    val lean = 0.02f * sin(phase * TAU)
    val ux = 0.52f + lean
    val uy = -0.855f + lean * 0.3f
    val shaft = u * 0.44f
    val tip = Offset(nib.x + ux * shaft, nib.y + uy * shaft)
    drawLine(LETTER_INK, nib, tip, u * 0.013f, alpha = alpha * 1.7f)
    val ox = -uy
    val oy = ux
    val barbs = Path()
    repeat(14) { index ->
        val t = 0.30f + index * 0.05f
        val px = nib.x + (tip.x - nib.x) * t
        val py = nib.y + (tip.y - nib.y) * t
        // 纺锤形：羽面中段最宽，两端收窄
        val len = u * 0.115f * sin(((t - 0.30f) / 0.70f).coerceIn(0f, 1f) * PI.toFloat())
        barbs.moveTo(px, py)
        barbs.lineTo(px + (ox * 0.92f - ux * 0.42f) * len, py + (oy * 0.92f - uy * 0.42f) * len)
        barbs.moveTo(px, py)
        barbs.lineTo(px + (-ox * 0.92f - ux * 0.42f) * len, py + (-oy * 0.92f - uy * 0.42f) * len)
    }
    drawPath(barbs, LETTER_INK, alpha = alpha * 1.1f, style = Stroke(width = u * 0.005f))
    // 笔尖：一个小三角 + 中缝。少了这一笔，羽毛就是插在纸上而不是在写
    val point = Path()
    point.moveTo(nib.x, nib.y)
    point.lineTo(nib.x + ux * u * 0.085f + ox * u * 0.020f, nib.y + uy * u * 0.085f + oy * u * 0.020f)
    point.lineTo(nib.x + ux * u * 0.085f - ox * u * 0.020f, nib.y + uy * u * 0.085f - oy * u * 0.020f)
    point.close()
    drawPath(point, LETTER_INK, alpha = alpha * 2.2f)
    drawLine(
        Color.White, nib,
        Offset(nib.x + ux * u * 0.070f, nib.y + uy * u * 0.070f),
        u * 0.005f, alpha = alpha * 1.6f
    )
}

/**
 * 墨水瓶：方瓶 + 瓶颈 + 厚玻璃瓶口 + 瓶内墨面。
 *
 * 液面那道白高光是「这是玻璃器皿、里面有液体」的全部依据 ——
 * 去掉它，瓶子就是一个深色方块。
 */
private fun DrawScope.drawInkBottle(alpha: Float, w: Float, h: Float, u: Float) {
    val left = w * 0.62f
    val right = w * 0.96f
    val top = h * 0.72f
    val bottom = h * 0.96f
    val width = right - left
    drawRect(LETTER_INK, Offset(left, top), Size(width, bottom - top), alpha = alpha * 0.35f)
    // 瓶内墨：占下 58%，比玻璃深得多
    val inkTop = top + (bottom - top) * 0.42f
    drawRect(
        LETTER_INK, Offset(left + u * 0.012f, inkTop),
        Size(width - u * 0.024f, bottom - inkTop - u * 0.012f), alpha = alpha * 2.4f
    )
    drawLine(
        Color.White, Offset(left + u * 0.030f, inkTop + u * 0.007f),
        Offset(right - u * 0.045f, inkTop + u * 0.007f), u * 0.008f, alpha = alpha * 2.0f
    )
    drawRect(
        LETTER_INK, Offset(left, top), Size(width, bottom - top),
        alpha = alpha * 1.5f, style = Stroke(width = u * 0.009f)
    )
    // 左壁一条竖高光
    drawLine(
        Color.White, Offset(left + u * 0.026f, top + u * 0.030f),
        Offset(left + u * 0.026f, bottom - u * 0.040f), u * 0.010f, alpha = alpha * 1.3f
    )
    // 瓶颈 + 瓶口
    drawRect(
        LETTER_INK, Offset(left + width * 0.30f, top - h * 0.045f),
        Size(width * 0.40f, h * 0.045f), alpha = alpha * 1.2f
    )
    drawRect(
        LETTER_INK, Offset(left + width * 0.24f, top - h * 0.065f),
        Size(width * 0.52f, h * 0.022f), alpha = alpha * 1.9f
    )
}

/** 12 · Showgirl：更衣室化妆镜台（环绕灯泡轮转）+ 台面上的口红与粉扑。 */
private fun DrawScope.drawVanityMirror(color: Color, phase: Float, alpha: Float) {
    diagonalHatchTexture(color, spacing = 0.095f, downhill = true)
    val box = propBox()
    val w = box.width
    val h = box.height
    val u = min(w, h)
    translate(left = box.left, top = box.top) {
        val frame = Rect(w * 0.10f, h * 0.04f, w * 0.90f, h * 0.52f)
        val corner = CornerRadius(u * 0.06f)
        drawRoundRect(color, frame.topLeft, frame.size, corner, alpha = alpha * 0.30f)
        // 镜面高光：两道斜向平行四边形。白色压在有色玻璃上才读得出「这是镜子」
        val inset = u * 0.03f
        val glare = Path()
        repeat(2) { index ->
            val shift = frame.width * (0.16f + index * 0.34f)
            val band = frame.width * (0.13f - index * 0.05f)
            glare.moveTo(frame.left + shift, frame.bottom - inset)
            glare.lineTo(frame.left + shift + frame.width * 0.26f, frame.top + inset)
            glare.lineTo(frame.left + shift + frame.width * 0.26f + band, frame.top + inset)
            glare.lineTo(frame.left + shift + band, frame.bottom - inset)
            glare.close()
        }
        drawPath(glare, Color.White, alpha = 0.55f)
        drawRoundRect(
            color, frame.topLeft, frame.size, corner,
            alpha = alpha * 1.3f, style = Stroke(width = u * 0.030f)
        )
        drawVanityBulbs(color, phase, alpha, frame, u)
        drawVanityTable(color, alpha, w, h, u)
        drawLipstick(color, alpha, w, h, u)
        drawPowderPuff(color, alpha, w, h, u)
    }
}

/**
 * 镜框上一圈等距灯泡，随相位**逐颗轮转点亮**。
 *
 * 光晕只给最亮的那两三颗：每颗都来一个 `radialGradient` 就是每帧 16 个 Brush，
 * 而暗着的灯泡本来也不该有光。
 */
private fun DrawScope.drawVanityBulbs(
    color: Color,
    phase: Float,
    alpha: Float,
    frame: Rect,
    u: Float
) {
    val count = 16
    val radius = u * 0.026f
    val head = phase * count
    repeat(count) { index ->
        val center = rectPerimeterPoint(frame, index / count.toFloat())
        // 环形距离：轮到的那颗最亮，后面两颗留余辉，读作一圈灯在转
        var distance = head - index
        while (distance < 0f) distance += count
        val glow = (1f - distance / 3f).coerceIn(0f, 1f)
        if (glow > 0.35f) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(PROP_WARM.copy(alpha = 0.60f * glow), Color.Transparent),
                    center = center,
                    radius = radius * 3.2f
                ),
                radius = radius * 3.2f,
                center = center
            )
        }
        drawCircle(PROP_WARM, radius * 0.86f, center, alpha = 0.28f + 0.64f * glow)
        drawCircle(color, radius, center, alpha = alpha * 1.4f, style = Stroke(width = u * 0.008f))
    }
}

/** 台面：桌板 + 抽屉面 + 拉手 + 两条桌腿。台面是口红和粉扑「站得住」的前提。 */
private fun DrawScope.drawVanityTable(color: Color, alpha: Float, w: Float, h: Float, u: Float) {
    val topY = h * 0.58f
    val slab = u * 0.055f
    drawRect(color, Offset(w * 0.02f, topY), Size(w * 0.96f, slab), alpha = alpha * 1.2f)
    drawRect(color, Offset(w * 0.10f, topY + slab), Size(w * 0.80f, h * 0.10f), alpha = alpha * 0.5f)
    drawRect(
        color, Offset(w * 0.10f, topY + slab), Size(w * 0.80f, h * 0.10f),
        alpha = alpha * 0.9f, style = Stroke(width = u * 0.007f)
    )
    drawCircle(color, u * 0.018f, Offset(w * 0.50f, topY + slab + h * 0.05f), alpha = alpha * 1.7f)
    drawRect(color, Offset(w * 0.12f, topY + slab + h * 0.10f), Size(u * 0.038f, h * 0.24f), alpha = alpha * 0.9f)
    drawRect(color, Offset(w * 0.84f, topY + slab + h * 0.10f), Size(u * 0.038f, h * 0.24f), alpha = alpha * 0.9f)
}

/** 一支口红：管身 + 管口金属环 + 斜切的膏体。斜切是口红与蜡笔的区别。 */
private fun DrawScope.drawLipstick(color: Color, alpha: Float, w: Float, h: Float, u: Float) {
    val left = w * 0.20f
    val right = w * 0.30f
    val mouthY = h * 0.44f
    drawRect(color, Offset(left, mouthY), Size(right - left, h * 0.14f), alpha = alpha * 1.5f)
    drawRect(
        color, Offset(left - u * 0.007f, mouthY - h * 0.008f),
        Size(right - left + u * 0.014f, h * 0.016f), alpha = alpha * 2.1f
    )
    val bullet = Path()
    bullet.moveTo(left + u * 0.004f, mouthY - h * 0.006f)
    bullet.lineTo(right - u * 0.004f, mouthY - h * 0.006f)
    bullet.lineTo(right - u * 0.004f, h * 0.385f)
    bullet.quadraticTo(left + (right - left) * 0.5f, h * 0.358f, left + u * 0.004f, h * 0.408f)
    bullet.close()
    drawPath(bullet, color, alpha = alpha * 2.3f)
    // 管身一条竖高光，不然是一根深色小棍
    drawLine(
        Color.White, Offset(left + u * 0.020f, mouthY + h * 0.014f),
        Offset(left + u * 0.020f, mouthY + h * 0.120f), u * 0.009f, alpha = alpha * 1.5f
    )
}

/** 一个粉扑：圆饼 + 一圈绒边（22 段外凸小弧）+ 缎带提手。绒边全部合进一条 Path。 */
private fun DrawScope.drawPowderPuff(color: Color, alpha: Float, w: Float, h: Float, u: Float) {
    val center = Offset(w * 0.70f, h * 0.51f)
    val radius = w * 0.105f
    drawCircle(color, radius, center, alpha = alpha * 0.85f)
    drawCircle(Color.White, radius * 0.70f, center, alpha = 0.50f)
    val fuzz = Path()
    val segments = 22
    repeat(segments) { index ->
        val a0 = index / segments.toFloat() * TAU
        val a1 = (index + 1) / segments.toFloat() * TAU
        val mid = (a0 + a1) / 2f
        fuzz.moveTo(center.x + cos(a0) * radius, center.y + sin(a0) * radius)
        fuzz.quadraticTo(
            center.x + cos(mid) * radius * 1.16f, center.y + sin(mid) * radius * 1.16f,
            center.x + cos(a1) * radius, center.y + sin(a1) * radius
        )
    }
    drawPath(fuzz, color, alpha = alpha * 1.4f, style = Stroke(width = u * 0.006f))
    drawArc(
        color = color,
        startAngle = 200f,
        sweepAngle = 140f,
        useCenter = false,
        topLeft = Offset(center.x - radius * 0.34f, center.y - radius * 0.62f),
        size = Size(radius * 0.68f, radius * 0.48f),
        alpha = alpha * 1.8f,
        style = Stroke(width = u * 0.010f)
    )
}
