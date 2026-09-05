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
import androidx.compose.ui.graphics.drawscope.withTransform
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
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
 * 前景道具身下垫的一层白。
 *
 * 12 个母题的道具全部画在 [PROP_ALPHA] 上下（有效 alpha 0.05..0.85），于是**谁先画谁后画
 * 在屏幕上分不出前后**：后画的半透明形状会把先画的整条透出来 —— 吉他琴箱里看得见栏杆、
 * 开衫上看得见椅背、两只纸环在相交处两条都在。物理上前面的东西挡住后面的，
 * 后面那一段就不该出现在屏幕上。
 *
 * 垫一层白最省：不用给每个道具算轮廓 Path 再 `clipPath(Difference)`（旋转过的道具还得
 * 反算矩阵），把同一组形状先用白描/填一遍就行，之后再上颜色。卡片底色本来就是
 * `Color.White.copy(alpha = 0.86f)`，这层白落在上面读作「受光的一面」，不是一块补丁。
 *
 * 0.78 是量出来的：被挡住的结构有效 alpha 多在 0.2..0.35，垫白之后剩 0.04..0.08，
 * 屏幕上看不出来；再高就会在卡片上留下一圈比周围亮的边。
 */
private const val PROP_MASK_ALPHA = 0.78f

/**
 * 垫白的实际用法：`alpha * PROP_MASK`。
 *
 * **不能直接写 [PROP_MASK_ALPHA] 这个定值** —— 曲目多的卡片上道具会被 `columnFade`
 * 整体压到 0.514 倍（给曲目名让路），而定值的白不跟着淡，屏幕上剩下的就是一片
 * 比谁都亮的白剪影：本来要藏起来的东西反而成了最扎眼的。写成相对 [PROP_ALPHA] 的
 * 倍数，垫白就和它盖住的、以及盖在它上面的东西同步淡出。
 */
private const val PROP_MASK = PROP_MASK_ALPHA / PROP_ALPHA

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
        SwiftieEraMotif.PAPER_RINGS -> drawPaperRings(color, phase, alpha)
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

/**
 * 1 · Taylor Swift：斜靠在门廊地板上的民谣吉他。
 *
 * 这里**只有一块地板**，栏杆已经删掉：`SwiftieEraBackdrop` 的首专背景整个上半屏就是那道
 * 门廊栏杆（横扶手 + 一排立柱 + 两根角柱），卡片上再摆一道，屏幕上同一时间有两副栏杆，
 * 而且卡片那副的立柱正好横穿琴箱与琴颈 —— 一件道具不能在卡片和背景上同时当主角。
 *
 * 留下的这条地板不是「缩小的栏杆」而是**地平线**：吉他得踩在什么东西上，
 * 不然 -20° 斜靠读作正在倒下去。位置按琴箱底那一点现算（见 [PORCH_FLOOR_Y]）。
 */
private fun DrawScope.drawPorchGuitar(color: Color, phase: Float, alpha: Float) {
    watercolorWashTexture(color, phase)
    val box = propBox()
    val w = box.width
    val h = box.height
    val u = min(w, h)
    translate(left = box.left, top = box.top) {
        // 地板：一条通栏的板边 + 上方一道板缝。0.55 / 0.30 都压得比道具本身淡，
        // 它是吉他站的地方，不是要被看的东西
        val floorY = h * 0.76f + u * PORCH_FLOOR_Y
        drawRect(color, Offset(-w * 0.02f, floorY), Size(w * 1.04f, u * 0.034f), alpha = alpha * 0.55f)
        drawLine(
            color,
            Offset(-w * 0.02f, floorY - u * 0.026f),
            Offset(w * 1.02f, floorY - u * 0.026f),
            strokeWidth = u * 0.006f,
            alpha = alpha * 0.30f
        )
        drawGuitar(color, phase, alpha, w, h, u)
    }
}

/**
 * 琴箱最低点相对 `drawGuitar` 支点（`0.76h`）的偏移，单位 u。
 *
 * `0.208`（琴箱底的局部坐标）× cos20°（那 -20° 的斜靠）。地板画在这个高度上，
 * 琴才是踩在板上而不是插进板里 —— 上一版地梁固定在 `0.76h`，正好横切下腹。
 */
private const val PORCH_FLOOR_Y = 0.196f

/**
 * 斜靠在门廊地板上的民谣吉他（dreadnought）。
 *
 * 在**已经 translate 到道具框**的坐标系里画，所以只吃 [w] / [h] / [u]，
 * 一律不读 `size`（那还是整张卡片的尺寸，读了就全错位）。
 *
 * ## 琴箱为什么不是两个圆
 *
 * 上一版是「上下两个圆一叠」。圆的问题是**上下对称**：真吉他的下腹比上腹宽得多、束腰偏上，
 * 两个圆叠出来的轮廓左右两侧曲率一样，屏幕上读作一只葫芦。这一版按四个控制高度
 * （下腹 / 束腰 / 上腹 / 琴颈根）拉三次贝塞尔，左右各四段，一条闭合路径 —— 收腰的位置、
 * 肩的斜度都能单独调。
 *
 * ## 一把吉他的辨识清单
 *
 * 少一样就退回「一块木头」，按重要性排：**音孔 + 一圈镶边**、**六根弦**（低音三根更粗）、
 * **品丝间距递减**、**琴桥上六个弦钉**、**琴头六个弦轴 + 侧面的旋钮**、**指板比琴颈窄**、
 * **箱边一道 binding**、**护板**。这一版全有；上一版缺后五样。
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
    // 靠着地板微微晃：只给 1.2°，再大就读作要倒了
    rotate(degrees = -20f + sin(phase * TAU) * 1.2f, pivot = Offset(bodyCx, bodyCy)) {
        translate(left = bodyCx, top = bodyCy) {
            // 琴箱的四个控制高度与三档半宽
            val bl = u * 0.200f
            val bWaist = u * 0.128f
            val bUp = u * 0.158f
            val yBottom = u * 0.208f
            val yWaist = -u * 0.230f
            val yUpper = -u * 0.418f
            val yTop = -u * 0.560f
            val nutY = -u * 0.960f
            val headTop = -u * 1.140f
            val neckHalfHeel = u * 0.034f
            val neckHalfNut = u * 0.026f
            val outline = Stroke(width = u * 0.013f)

            val body = Path()
            body.moveTo(0f, yTop)
            body.cubicTo(bUp * 0.72f, yTop, bUp, yUpper - u * 0.055f, bUp, yUpper)
            body.cubicTo(bUp, yUpper + u * 0.072f, bWaist, yWaist - u * 0.046f, bWaist, yWaist)
            body.cubicTo(bWaist, yWaist + u * 0.078f, bl, -u * 0.085f, bl, 0f)
            body.cubicTo(bl, u * 0.100f, bl * 0.62f, yBottom, 0f, yBottom)
            body.cubicTo(-bl * 0.62f, yBottom, -bl, u * 0.100f, -bl, 0f)
            body.cubicTo(-bl, -u * 0.085f, -bWaist, yWaist + u * 0.078f, -bWaist, yWaist)
            body.cubicTo(-bWaist, yWaist - u * 0.046f, -bUp, yUpper + u * 0.072f, -bUp, yUpper)
            body.cubicTo(-bUp, yUpper - u * 0.055f, -bUp * 0.72f, yTop, 0f, yTop)
            body.close()

            // 垫白：地板比吉他先画，而两者都是半透明的 —— 不垫这一层，板边与板缝
            // 会整条穿过琴箱（[PROP_MASK_ALPHA] 那段注释里的 X 光片）。
            // 琴颈与琴头也各垫一次，它们同样压在底纹上
            drawPath(body, Color.White, alpha = alpha * PROP_MASK)
            val neck = Path()
            neck.moveTo(-neckHalfHeel, yTop + u * 0.02f)
            neck.lineTo(neckHalfHeel, yTop + u * 0.02f)
            neck.lineTo(neckHalfNut, nutY)
            neck.lineTo(-neckHalfNut, nutY)
            neck.close()
            drawPath(neck, Color.White, alpha = alpha * PROP_MASK)
            val head = Path()
            head.moveTo(-u * 0.040f, nutY)
            head.lineTo(u * 0.040f, nutY)
            head.lineTo(u * 0.056f, headTop + u * 0.024f)
            head.quadraticTo(u * 0.052f, headTop, u * 0.026f, headTop)
            head.lineTo(-u * 0.026f, headTop)
            head.quadraticTo(-u * 0.052f, headTop, -u * 0.056f, headTop + u * 0.024f)
            head.close()
            drawPath(head, Color.White, alpha = alpha * PROP_MASK)

            // 面板 + 轮廓 + 一圈 binding（往内缩一档再描一遍，箱边就有了那道白线）
            drawPath(body, color, alpha = alpha * 0.26f)
            drawPath(body, color, alpha = alpha, style = outline)
            scale(0.90f, 0.94f, pivot = Offset(0f, (yTop + yBottom) * 0.5f)) {
                drawPath(body, color, alpha = alpha * 0.55f, style = Stroke(width = u * 0.005f))
            }

            // 琴颈 + 指板 + 品丝 + 位置点。指板比琴颈窄一圈，这一层是「按弦的地方」
            drawPath(neck, color, alpha = alpha * 0.55f)
            drawPath(neck, color, alpha = alpha * 0.95f, style = Stroke(width = u * 0.008f))
            val board = Path()
            board.moveTo(-neckHalfHeel * 0.86f, yTop + u * 0.03f)
            board.lineTo(neckHalfHeel * 0.86f, yTop + u * 0.03f)
            board.lineTo(neckHalfNut * 0.86f, nutY)
            board.lineTo(-neckHalfNut * 0.86f, nutY)
            board.close()
            drawPath(board, color, alpha = alpha * 0.85f)
            // 品丝：从琴枕往琴箱方向间距递减 —— 等距排出来的是梯子，不是吉他
            val frets = Path()
            var fretY = nutY
            var gap = u * 0.075f
            val fretDots = Path()
            repeat(12) { index ->
                fretY += gap
                val t = ((fretY - nutY) / (yTop + u * 0.03f - nutY)).coerceIn(0f, 1f)
                val half = neckHalfNut * 0.86f + (neckHalfHeel * 0.86f - neckHalfNut * 0.86f) * t
                frets.moveTo(-half, fretY)
                frets.lineTo(half, fretY)
                // 位置点：3 / 5 / 7 / 9 品之间各一颗，指板才不是一排等距的横线
                if (index == 2 || index == 4 || index == 6 || index == 8) {
                    fretDots.addOval(
                        Rect(
                            -u * 0.008f, fretY - gap * 0.5f - u * 0.008f,
                            u * 0.008f, fretY - gap * 0.5f + u * 0.008f
                        )
                    )
                }
                gap *= 0.90f
            }
            drawPath(frets, color, alpha = alpha * 0.75f, style = Stroke(width = u * 0.005f))
            drawPath(fretDots, Color.White, alpha = alpha * 1.6f)
            // 琴枕：指板顶端一条浅色横条
            drawRect(
                Color.White,
                Offset(-neckHalfNut * 0.95f, nutY - u * 0.010f),
                Size(neckHalfNut * 1.90f, u * 0.012f),
                alpha = alpha * 1.8f
            )

            // 琴头 + 六个弦轴。侧面那六个旋钮是「这是把吉他」而不是「一块板」的关键
            drawPath(head, color, alpha = alpha * 0.60f)
            drawPath(head, color, alpha = alpha, style = Stroke(width = u * 0.008f))
            val buttons = Path()
            repeat(3) { index ->
                val pegY = nutY - u * (0.042f + index * 0.048f)
                drawCircle(color, u * 0.011f, Offset(-u * 0.030f, pegY), alpha = alpha * 1.5f)
                drawCircle(color, u * 0.011f, Offset(u * 0.030f, pegY), alpha = alpha * 1.5f)
                buttons.addRect(Rect(-u * 0.082f, pegY - u * 0.007f, -u * 0.052f, pegY + u * 0.007f))
                buttons.addRect(Rect(u * 0.052f, pegY - u * 0.007f, u * 0.082f, pegY + u * 0.007f))
            }
            drawPath(buttons, color, alpha = alpha * 1.3f)

            // 音孔 + 两圈镶边。音孔是**洞**，所以填得比全琴任何一处都重
            val holeY = -u * 0.318f
            val holeR = u * 0.066f
            drawCircle(color, holeR, Offset(0f, holeY), alpha = alpha * 1.9f)
            drawCircle(
                color, u * 0.081f, Offset(0f, holeY),
                alpha = alpha * 0.85f, style = Stroke(width = u * 0.010f)
            )
            drawCircle(
                color, u * 0.093f, Offset(0f, holeY),
                alpha = alpha * 0.55f, style = Stroke(width = u * 0.004f)
            )

            // 护板：音孔右下那一片水滴。少了它面板右侧空一大块
            val guard = Path()
            guard.moveTo(u * 0.048f, holeY - u * 0.052f)
            guard.cubicTo(
                u * 0.108f, holeY - u * 0.040f,
                u * 0.126f, holeY + u * 0.060f,
                u * 0.104f, holeY + u * 0.150f
            )
            guard.cubicTo(
                u * 0.090f, holeY + u * 0.196f,
                u * 0.062f, holeY + u * 0.150f,
                u * 0.070f, holeY + u * 0.070f
            )
            guard.close()
            drawPath(guard, color, alpha = alpha * 0.55f)

            // 琴桥：底座 + 上弦枕 + 六个弦钉。三样缺一样，琴桥就是面板上的一条色块
            val bridgeY = u * 0.062f
            val bridge = Path()
            bridge.moveTo(-u * 0.072f, bridgeY)
            bridge.lineTo(u * 0.072f, bridgeY)
            bridge.lineTo(u * 0.060f, bridgeY + u * 0.030f)
            bridge.lineTo(-u * 0.060f, bridgeY + u * 0.030f)
            bridge.close()
            drawPath(bridge, color, alpha = alpha * 1.7f)
            drawRect(
                Color.White,
                Offset(-u * 0.058f, bridgeY - u * 0.011f),
                Size(u * 0.116f, u * 0.010f),
                alpha = alpha * 1.9f
            )
            val pins = Path()
            repeat(6) { index ->
                val px = (index - 2.5f) / 2.5f * u * 0.044f
                pins.addOval(
                    Rect(px - u * 0.007f, bridgeY + u * 0.011f, px + u * 0.007f, bridgeY + u * 0.025f)
                )
            }
            drawPath(pins, Color.White, alpha = alpha * 1.7f)

            // 六根弦：低音三根一档粗、高音三根一档细。等粗的六根读作一把梳子。
            // 两条 Path 各一次描完 —— 一根一次 drawLine 就是六个绘制调用
            val bass = Path()
            val treble = Path()
            repeat(6) { index ->
                val spread = (index - 2.5f) / 2.5f
                val target = if (index < 3) bass else treble
                target.moveTo(spread * u * 0.030f, bridgeY - u * 0.004f)
                target.lineTo(spread * u * 0.021f, nutY - u * 0.004f)
            }
            drawPath(bass, color, alpha = alpha * 0.85f, style = Stroke(width = u * 0.0042f))
            drawPath(treble, color, alpha = alpha * 0.60f, style = Stroke(width = u * 0.0024f))

            // 琴箱压在地梁上那一点的接触影。斜靠的东西少了这一小片，就是贴在栏杆上的贴纸
            drawOval(
                color = color,
                topLeft = Offset(-bl * 0.66f, bl * 0.96f),
                size = Size(bl * 1.32f, bl * 0.26f),
                alpha = alpha * 0.40f
            )
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
        drawDressSilhouette(color, phase, alpha, w, h, u)
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
 * 紫裙剪影：*Speak Now* 那件紫色礼服。
 *
 * 上一版是「三层弧形褶」的裙摆，被点名过 ——「一点都不像」。确实：三条同心的弧压在同样是
 * 弧形褶的帷幕上，两者的画法一模一样，屏幕上读作帷幕多了三条彩带。裙子之所以是裙子，
 * 靠的不是褶而是**穿着它的人**：头、肩、收进去的腰、张开的裙。
 *
 * 所以这一版是一整个剪影，而且**只是剪影**：一块纯色的形，不画脸、不画手指、不打高光。
 * 加了五官它立刻从「一个时代的符号」变成「某个人的画像」——
 * `SwiftieEraBackdrop` 里 Showgirl 那张舞台也是同一条规矩（不画人形）。
 *
 * 比例按时装插画的 9 头身而不是真人的 7.5：礼服的重点在裙，腿的长度让给裙摆。
 * 但**只拉长腿、不缩小头** —— 头一小于 1/10 身高，整个剪影就从「一个人」退回「一枚别针」。
 * 一条手臂抬起、一条垂下，加上裙摆随 [phase] 摆 —— 静止的对称剪影读作一枚图标。
 */
private fun DrawScope.drawDressSilhouette(
    color: Color,
    phase: Float,
    alpha: Float,
    w: Float,
    h: Float,
    u: Float
) {
    val figTop = h * 0.320f
    val figH = h * 0.655f
    val cx = w * 0.50f
    // 纵向一律「身高的几分之几」：改 figH 整个人一起缩放，比例不会散
    fun y(f: Float) = figTop + figH * f
    // 头占身高的 0.112 = 8.9 头身。上一版是 0.084（11.9 头身），
    // 屏幕上读作一根别针 —— 时装插画拉长的是腿，不是把头缩掉
    val headRx = figH * 0.049f
    val neckHalf = figH * 0.024f
    val shoulderHalf = figH * 0.098f
    val waistHalf = figH * 0.052f
    val hemHalf = figH * 0.300f
    // 裙摆摆动：左右不同步（左摆 = 右收），整块平移读作整个人在飘
    val sway = sin(phase * TAU) * u * 0.020f

    // 长卷发：Speak Now 那一头长卷发是这个时代的另一半标志，光有裙子读不出是哪一年
    val hair = Path()
    hair.moveTo(cx - headRx * 1.08f, y(0.062f))
    hair.cubicTo(
        cx - headRx * 1.72f, y(0.152f),
        cx - headRx * 2.08f, y(0.252f),
        cx - headRx * 1.30f, y(0.345f)
    )
    hair.cubicTo(
        cx - headRx * 0.68f, y(0.276f),
        cx - headRx * 0.54f, y(0.132f),
        cx, y(0.020f)
    )
    hair.cubicTo(
        cx + headRx * 0.54f, y(0.132f),
        cx + headRx * 0.68f, y(0.276f),
        cx + headRx * 1.30f, y(0.345f)
    )
    hair.cubicTo(
        cx + headRx * 2.08f, y(0.252f),
        cx + headRx * 1.72f, y(0.152f),
        cx + headRx * 1.08f, y(0.062f)
    )
    hair.close()

    // 两条手臂：一抬一垂。用圆头粗描而不是描轮廓 —— 这个尺寸下手臂只有几像素宽，
    // 描出来的轮廓线自己就把里面填满了
    val arms = Path()
    arms.moveTo(cx - shoulderHalf * 0.86f, y(0.200f))
    arms.quadraticTo(cx - shoulderHalf * 1.52f, y(0.290f), cx - shoulderHalf * 1.40f, y(0.430f))
    arms.moveTo(cx + shoulderHalf * 0.86f, y(0.196f))
    arms.quadraticTo(cx + shoulderHalf * 1.90f, y(0.150f), cx + shoulderHalf * 2.30f, y(0.048f))
    val armWidth = figH * 0.030f

    // 主体：头 → 颈 → 露肩胸衣 → 收腰 → 张开的裙。一条闭合路径，一次填完
    val body = Path()
    body.moveTo(cx - neckHalf, y(0.112f))
    body.lineTo(cx - neckHalf, y(0.146f))
    // 左肩 → 左胸 → 左腰
    body.quadraticTo(cx - shoulderHalf * 0.96f, y(0.162f), cx - shoulderHalf, y(0.208f))
    body.cubicTo(
        cx - shoulderHalf * 0.92f, y(0.286f),
        cx - waistHalf * 1.42f, y(0.352f),
        cx - waistHalf, y(0.404f)
    )
    // 左侧裙：一路外扩到裙摆
    body.cubicTo(
        cx - hemHalf * 0.50f, y(0.628f),
        cx - hemHalf * 0.90f, y(0.876f),
        cx - hemHalf + sway, y(0.972f)
    )
    // 裙摆下缘：三段起伏。**这一条必须是波浪** —— 一条直边的裙摆读作一只梯形
    body.quadraticTo(cx - hemHalf * 0.52f + sway, y(1.010f), cx - hemHalf * 0.14f, y(0.986f))
    body.quadraticTo(cx + hemHalf * 0.22f, y(0.962f), cx + hemHalf * 0.56f, y(0.998f))
    body.quadraticTo(cx + hemHalf * 0.86f, y(1.024f), cx + hemHalf - sway, y(0.964f))
    // 右侧裙上行回腰
    body.cubicTo(
        cx + hemHalf * 0.90f, y(0.876f),
        cx + hemHalf * 0.50f, y(0.628f),
        cx + waistHalf, y(0.404f)
    )
    // 右腰 → 右胸 → 右肩
    body.cubicTo(
        cx + waistHalf * 1.42f, y(0.352f),
        cx + shoulderHalf * 0.92f, y(0.286f),
        cx + shoulderHalf, y(0.208f)
    )
    body.quadraticTo(cx + shoulderHalf * 0.96f, y(0.162f), cx + neckHalf, y(0.146f))
    body.lineTo(cx + neckHalf, y(0.112f))
    // 头：右下颌 → 右侧 → 顶 → 左侧 → 左下颌
    body.cubicTo(
        cx + headRx * 0.62f, y(0.106f),
        cx + headRx * 1.05f, y(0.086f),
        cx + headRx * 1.05f, y(0.058f)
    )
    body.cubicTo(
        cx + headRx * 1.05f, y(0.014f),
        cx + headRx * 0.60f, y(0f),
        cx, y(0f)
    )
    body.cubicTo(
        cx - headRx * 0.60f, y(0f),
        cx - headRx * 1.05f, y(0.014f),
        cx - headRx * 1.05f, y(0.058f)
    )
    body.cubicTo(
        cx - headRx * 1.05f, y(0.086f),
        cx - headRx * 0.62f, y(0.106f),
        cx - neckHalf, y(0.112f)
    )
    body.close()

    // 垫白**三块一起先垫**，再统一上色。
    //
    // 上一版是「垫一块 → 上一块颜色」轮着来，而手臂的垫白比彩色描边宽一档
    // （0.034 vs 0.028）—— 那多出来的一圈白正好压在已经上过色的躯干上，
    // 手臂与身体之间就永远隔着一道白边，截出来两条胳膊像两根悬空的面条。
    // 白在白卡上是**不可见**的，一次垫完不会互相破坏；颜色才会。
    drawPath(hair, Color.White, alpha = alpha * PROP_MASK)
    drawPath(
        arms, Color.White,
        alpha = alpha * PROP_MASK,
        style = Stroke(width = armWidth, cap = StrokeCap.Round)
    )
    drawPath(body, Color.White, alpha = alpha * PROP_MASK)

    // 上色：发在最下（肩把发根压住），身体在最上（盖掉肩上的接缝）
    drawPath(hair, color, alpha = alpha * 2.0f)
    drawPath(
        arms, color,
        alpha = alpha * 2.1f,
        style = Stroke(width = armWidth, cap = StrokeCap.Round)
    )
    drawPath(body, color, alpha = alpha * 2.3f)

    // 裙上四道竖向的裥：同色再压一遍，深一档就是布的褶。剪影不打高光，
    // 白卡上「亮」等于色少，加白只会在紫裙上开四个洞
    val pleats = Path()
    repeat(4) { index ->
        val f = (index - 1.5f) / 2.0f
        pleats.moveTo(cx + waistHalf * f * 0.9f, y(0.425f))
        pleats.quadraticTo(
            cx + hemHalf * f * 0.62f, y(0.716f),
            cx + hemHalf * f * 0.90f + sway * f, y(0.958f)
        )
    }
    drawPath(pleats, color, alpha = alpha * 0.85f, style = Stroke(width = u * 0.011f))
    // 裙摆内里那一道暗边：布是有厚度的，翻起来的那一面比正面深
    val inner = Path()
    inner.moveTo(cx - hemHalf * 0.92f + sway, y(0.952f))
    inner.quadraticTo(cx - hemHalf * 0.50f + sway, y(0.988f), cx - hemHalf * 0.14f, y(0.964f))
    inner.quadraticTo(cx + hemHalf * 0.22f, y(0.940f), cx + hemHalf * 0.56f, y(0.976f))
    inner.quadraticTo(cx + hemHalf * 0.82f, y(1.000f), cx + hemHalf * 0.94f - sway, y(0.944f))
    drawPath(inner, color, alpha = alpha * 1.1f, style = Stroke(width = u * 0.014f))
}

/**
 * 4 · Red：**围成一圈**的红围巾（卡片右上角）+ 一顶 fedora（共用的右侧列里）。
 *
 * 围巾是 *All Too Well* 的核心意象，所以它在卡片上不是「垂下来的一条」而是
 * **绕成一圈**的 —— 围在脖子上时的样子，一个厚线圈加两条穿过圈的垂端。
 * 直挂的一条与页面背景那根枯枝、以及别张卡片上的垂布（folklore 的开衫、
 * Speak Now 的裙摆）都太像，绕成圈之后一眼就分得出这是围巾。
 *
 * 位置在**右上角**（见 [drawCoiledScarf] 的取框）而不是共用的右侧列：那一列
 * 从 0.34h 往下，正是曲目名最长那几行（"We Are Never Ever Getting Back Together"）
 * 伸到的地方。右上角在首行曲目以上，只有日期在它左边。
 */
private fun DrawScope.drawRedScarf(color: Color, phase: Float, alpha: Float) {
    knitStripeTexture(color, phase)
    drawCoiledScarf(color, phase, alpha)
    val box = propBox()
    val w = box.width
    val h = box.height
    translate(left = box.left, top = box.top) {
        drawFedora(color, alpha, w, h, min(w, h))
    }
}

/**
 * 绕成一圈的围巾：一个厚线圈 + 圈上的罗纹 + 两条穿过圈垂下来的端头（带流苏）。
 *
 * 圈用**一条很粗的描边椭圆**画，不是两条同心椭圆之间填色：描边天生等宽，
 * 而两条椭圆之间那块在长短轴处的厚度差三成，读出来是一枚戒指而不是一条布。
 *
 * 圈整体逆时针歪 14°，两条垂端长短不一：正着摆的圈加对称的两条端头是个图标，
 * 不是搭在那儿的一件东西。
 */
private fun DrawScope.drawCoiledScarf(color: Color, phase: Float, alpha: Float) {
    val cw = size.width
    val ch = size.height
    // 右上角那一块：右缘留 0.03 的边距，下缘停在 0.245h —— 首行曲目在 0.26h 起
    val bw = cw * 0.355f
    val bh = ch * 0.225f
    val cx = cw * 0.615f + bw * 0.46f
    val cy = ch * 0.018f + bh * 0.38f
    val rx = bw * 0.30f
    val ry = bh * 0.32f
    // 管的粗细。圈的内高 = 2·(ry − tube/2)，留得住一个看得见的洞
    val tube = bh * 0.15f
    // 呼吸：整圈随相位轻微起伏一格，幅度压在 1.5% —— 卡片上的道具会动，但不该「跳」
    val breath = 1f + sin(phase * TAU) * 0.015f

    // 两条垂端**先画**：它们是从线圈后面穿出来的，圈身要压在它们上面。
    // 先画圈后画端头（上一版）读作两条搭在圈上的布，「穿过去」那层意思就没了
    drawCoiledScarfEnds(color, alpha, cx, cy, rx, ry, tube, bh)
    withTransform({
        translate(cx, cy)
        rotate(-14f, Offset.Zero)
    }) {
        // 垫白：圈身是半透明的粗描边，不垫这一层两条垂端会从管面里透出来，
        // 读作画在圈上面而不是穿在圈后面
        drawOval(
            color = Color.White,
            topLeft = Offset(-rx * breath, -ry * breath),
            size = Size(rx * 2f * breath, ry * 2f * breath),
            alpha = alpha * PROP_MASK,
            style = Stroke(width = tube)
        )
        drawOval(
            color = color,
            topLeft = Offset(-rx * breath, -ry * breath),
            size = Size(rx * 2f * breath, ry * 2f * breath),
            alpha = alpha * 0.82f,
            style = Stroke(width = tube)
        )
        // 罗纹：14 道横跨管面的短痕，沿圈等角分布。这是它读作「针织」的全部原因
        for (i in 0 until 14) {
            val a = i / 14f * TAU
            val px = cos(a) * rx * breath
            val py = sin(a) * ry * breath
            // 法向按椭圆参数式取，短轴附近才不会歪
            val nx = cos(a) * ry
            val ny = sin(a) * rx
            val len = hypot(nx, ny).coerceAtLeast(0.0001f)
            val ux = nx / len
            val uy = ny / len
            drawLine(
                color = color,
                start = Offset(px - ux * tube * 0.46f, py - uy * tube * 0.46f),
                end = Offset(px + ux * tube * 0.46f, py + uy * tube * 0.46f),
                strokeWidth = tube * 0.13f,
                alpha = alpha * 0.5f,
                cap = StrokeCap.Round
            )
        }
    }
}

/**
 * 穿过线圈垂下来的两条端头，各带流苏。
 *
 * 两条**长短、粗细、撇向都不一样**：等长对称的两条挂在圈下面，整个道具就成了一枚吊坠。
 * 流苏沿端头自己的方向长，不是一律朝下 —— 端头是斜的，流苏垂直往下会脱开。
 */
private fun DrawScope.drawCoiledScarfEnds(
    color: Color,
    alpha: Float,
    cx: Float,
    cy: Float,
    rx: Float,
    ry: Float,
    tube: Float,
    bh: Float
) {
    for (e in 0..1) {
        val sx = cx - rx * (0.45f - e * 0.40f)
        val sy = cy + ry * (0.70f + e * 0.24f)
        val ex = sx - bh * (0.10f - e * 0.16f)
        val ey = sy + bh * (0.34f + e * 0.16f)
        val width = tube * (0.86f - e * 0.14f)
        drawLine(
            color = color,
            start = Offset(sx, sy),
            end = Offset(ex, ey),
            strokeWidth = width,
            cap = StrokeCap.Round,
            alpha = alpha * 0.80f
        )
        val dLen = hypot(ex - sx, ey - sy).coerceAtLeast(0.0001f)
        val dxu = (ex - sx) / dLen
        val dyu = (ey - sy) / dLen
        // 法向：端头是斜的，罗纹与流苏都得按它自己的方向摆
        val nxu = -dyu
        val nyu = dxu
        for (r in 1..3) {
            val t = r / 4f
            val mx = sx + (ex - sx) * t
            val my = sy + (ey - sy) * t
            drawLine(
                color = color,
                start = Offset(mx - nxu * width * 0.44f, my - nyu * width * 0.44f),
                end = Offset(mx + nxu * width * 0.44f, my + nyu * width * 0.44f),
                strokeWidth = width * 0.14f,
                alpha = alpha * 0.5f,
                cap = StrokeCap.Round
            )
        }
        for (f in 0 until 4) {
            val spread = (f - 1.5f) / 1.5f
            val fxp = ex + nxu * width * 0.44f * spread
            val fyp = ey + nyu * width * 0.44f * spread
            val len = bh * (0.055f + abs(spread) * 0.018f)
            drawLine(
                color = color,
                start = Offset(fxp, fyp),
                // 末端再往外撇一点，四根才不是一把梳子
                end = Offset(fxp + (dxu + nxu * spread * 0.35f) * len, fyp + dyu * len),
                strokeWidth = width * 0.16f,
                alpha = alpha * 0.9f,
                cap = StrokeCap.Round
            )
        }
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
    // 垫白：帽檐是整只椭圆，帽冠压在它中段上。不垫这一层，檐的后缘会横穿冠面 ——
    // 一顶能看透的帽子
    drawPath(crown, Color.White, alpha = alpha * PROP_MASK)
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
                // 影子：一张照片摆在卡片上就该有影子，右下偏 —— 光从左上来（与全彩蛋一致）。
                // 少了这一片，相纸是印在卡片上的图案而不是放在上面的一张纸
                drawRect(
                    color, Offset(u * 0.020f, u * 0.026f), Size(frameW, frameH),
                    alpha = alpha * 0.85f
                )
                // 白框画在白卡上没有边界，靠一圈描边把它「切」出来。
                // 白压到 0.90：相纸是**不透明**的，底纹的天空带不该从相纸里透出来
                drawRect(Color.White, Offset.Zero, Size(frameW, frameH), alpha = 0.90f)
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

/** 6 · reputation：盘起来的蛇（昂头做攻击姿态）+ 一枚蛇戒。 */
private fun DrawScope.drawCoiledSnake(color: Color, phase: Float, lowRam: Boolean, alpha: Float) {
    halftoneTexture(color, phase, lowRam)
    val box = propBox()
    val w = box.width
    val h = box.height
    val u = min(w, h)
    translate(left = box.left, top = box.top) {
        drawSnakeCoil(color, phase, lowRam, alpha, w, h, u)
        drawSnakeRing(color, alpha, w, h, u)
    }
}

/**
 * 盘起来的蛇：三圈叠成的盘 + 立起来的颈 + 昂着的头。
 *
 * ## 为什么不是一条螺线
 *
 * 上一版是「一条阿基米德螺线，y 压扁 0.76」—— 俯视看下去的一盘。被点名过：
 * 「不要画成一团圈圈」。俯视的盘从上往下看确实就是一团同心圆，而且**没有前后**：
 * 螺线的每一段都在同一个平面上，谁也挡不住谁，屏幕上是一团缠住的绳。
 *
 * 这一版改成**侧视**：三个横着的椭圆环从下往上叠，环一个比一个小、一个比一个高。
 * 由下往上画，**上面那圈盖住下面那圈的后半段** —— 挡住这件事就是盘的全部立体感来源。
 * 每圈都是 `drawOval` 加描边，不用 Path：环的横截面在顶点是竖的、在两端是横的，
 * 而按 `rx ± d / ry ± d` 取的内外两点在这两处都恰好对 —— 拿椭圆当环画是准的。
 *
 * ## 攻击姿态
 *
 * 颈从最上那圈的前沿立起来，走一条 S（先往左、再摆回右、最后头往左探出去），头因此
 * **高过盘 0.2h**、朝向偏水平 —— 那是要扑出去的样子。张着口、露两根牙。
 * 一条竖直立起、头朝上的蛇读作一根棍子；S 形的颈才有蓄力。
 */
private fun DrawScope.drawSnakeCoil(
    color: Color,
    phase: Float,
    lowRam: Boolean,
    alpha: Float,
    w: Float,
    h: Float,
    u: Float
) {
    val cx = w * 0.48f
    val bodyW = u * 0.098f
    // 整盘极慢地涨缩一点（0.6% 的半径），读作活物而不是标本
    val breathe = 1f + 0.006f * sin(phase * TAU)
    val scales = Path()

    // 尾尖：从最下那圈的**左**侧甩出来一小截，**在盘之前画**，根部由盘的下缘压住。
    // 少了这一截，最下面那圈是一个闭合的环，读作一只轮胎。
    //
    // 在左边而不是右边：[drawSnakeRing] 那枚蛇形戒指钉在 `(0.80w, 0.930h)`，
    // 半径 `0.088u` —— 上一版的右侧尾尖末端落在 `(0.78w, 0.876h)`，正好压进戒指的外圈，
    // 截出来两件道具糊成一件。左下角整片是空的。
    //
    // 起点 y 取 `0.768h` 不是随手挑的：那是最下那圈在 `x = 0.22w` 处下缘带子的中心
    // （椭圆上 `dx = -0.722` 对应 `dy = 0.048h`）。三圈都只是**描边**、内部是空的，
    // 根部要是落在圈里面，会有一截尾巴横穿空心。
    val tail = Path()
    tail.moveTo(cx - w * 0.26f, h * 0.768f)
    tail.cubicTo(
        cx - w * 0.47f, h * 0.800f,
        cx - w * 0.44f, h * 0.858f,
        cx - w * 0.27f, h * 0.874f
    )
    drawPath(
        tail, Color.White,
        alpha = alpha * PROP_MASK,
        style = Stroke(width = bodyW * 0.62f, cap = StrokeCap.Round)
    )
    drawPath(
        tail, color,
        alpha = alpha * 0.90f,
        style = Stroke(width = bodyW * 0.52f, cap = StrokeCap.Round)
    )

    // ── 三圈盘 ──
    // 由下往上：后画的那圈压住先画的，盘的前后关系全在这个顺序上
    for (i in 0 until 3) {
        val ringCy = h * (0.720f - i * 0.072f)
        val rx = (w * 0.360f - i * w * 0.052f) * breathe
        val ry = rx * 0.30f
        // 垫白：reputation 的底纹是一片大网点（[halftoneTexture]），不垫这一层
        // 每一颗点都从蛇身里透出来，蛇读作一段网纱而不是一条实体
        drawOval(
            color = Color.White,
            topLeft = Offset(cx - rx, ringCy - ry),
            size = Size(rx * 2f, ry * 2f),
            alpha = alpha * PROP_MASK,
            style = Stroke(width = bodyW * 1.14f)
        )
        drawOval(
            color = color,
            topLeft = Offset(cx - rx, ringCy - ry),
            size = Size(rx * 2f, ry * 2f),
            alpha = alpha * 0.88f,
            style = Stroke(width = bodyW)
        )
        // 背脊高光：同一个椭圆往上挪四分之一管径再细描一遍。
        // 白卡上「亮」等于色少，所以高光是白的
        drawOval(
            color = Color.White,
            topLeft = Offset(cx - rx, ringCy - ry - bodyW * 0.24f),
            size = Size(rx * 2f, ry * 2f),
            alpha = alpha * 1.25f,
            style = Stroke(width = bodyW * 0.20f)
        )
        // 鳞：横跨管径的短弧，一圈 18 片（低配 10 片）。合进一条 Path 一次描完 ——
        // 一片一次 drawPath 就是三圈 54 个绘制调用
        scales.rewind()
        val count = if (lowRam) 10 else 18
        for (k in 0 until count) {
            val a = k.toFloat() / count * TAU
            val ca = cos(a)
            val sa = sin(a)
            val d = bodyW * 0.44f
            val x0 = cx + (rx - d) * ca
            val y0 = ringCy + (ry - d) * sa
            val x1 = cx + (rx + d) * ca
            val y1 = ringCy + (ry + d) * sa
            // 控制点沿切向推出去：鳞的自由边朝尾，弧才是叠着的
            scales.moveTo(x0, y0)
            scales.quadraticTo(
                (x0 + x1) * 0.5f - sa * d * 0.55f,
                (y0 + y1) * 0.5f + ca * d * 0.55f,
                x1, y1
            )
        }
        drawPath(scales, color, alpha = alpha * 1.35f, style = Stroke(width = u * 0.0045f))
    }

    // ── 立起来的颈 ──
    // 一条三次贝塞尔的 S：起点在最上那圈的前沿，末端头朝左探出去。
    // 按采样点建**带锥度的多边形**而不是等宽描边 —— 颈根该比颈梢粗一档
    val p0x = cx - w * 0.060f
    val p0y = h * 0.615f
    val p1x = cx - w * 0.400f
    val p1y = h * 0.520f
    val p2x = cx + w * 0.160f
    val p2y = h * 0.318f
    val p3x = cx - w * 0.100f
    val p3y = h * 0.284f
    val steps = if (lowRam) 12 else 20
    fun neckX(t: Float): Float {
        val inv = 1f - t
        return inv * inv * inv * p0x + 3f * inv * inv * t * p1x + 3f * inv * t * t * p2x + t * t * t * p3x
    }
    fun neckY(t: Float): Float {
        val inv = 1f - t
        return inv * inv * inv * p0y + 3f * inv * inv * t * p1y + 3f * inv * t * t * p2y + t * t * t * p3y
    }
    fun neckHalf(t: Float) = bodyW * (0.50f - 0.11f * t)
    fun neckNx(t: Float): Float {
        val dxx = neckX(t + 0.02f) - neckX(t - 0.02f)
        val dyy = neckY(t + 0.02f) - neckY(t - 0.02f)
        val len = hypot(dxx, dyy)
        return if (len < 0.0001f) 0f else -dyy / len
    }
    fun neckNy(t: Float): Float {
        val dxx = neckX(t + 0.02f) - neckX(t - 0.02f)
        val dyy = neckY(t + 0.02f) - neckY(t - 0.02f)
        val len = hypot(dxx, dyy)
        return if (len < 0.0001f) 1f else dxx / len
    }

    val neck = Path()
    for (i in 0..steps) {
        val t = i / steps.toFloat()
        val x = neckX(t) + neckNx(t) * neckHalf(t)
        val y = neckY(t) + neckNy(t) * neckHalf(t)
        if (i == 0) neck.moveTo(x, y) else neck.lineTo(x, y)
    }
    for (i in steps downTo 0) {
        val t = i / steps.toFloat()
        neck.lineTo(neckX(t) - neckNx(t) * neckHalf(t), neckY(t) - neckNy(t) * neckHalf(t))
    }
    neck.close()
    drawPath(neck, Color.White, alpha = alpha * PROP_MASK)
    drawPath(neck, color, alpha = alpha * 0.90f)
    // 颈上的背脊高光 + 鳞。高光偏一侧 0.42 管径，颈才是圆的
    val gloss = Path()
    scales.rewind()
    for (i in 0..steps) {
        val t = i / steps.toFloat()
        val gx = neckX(t) + neckNx(t) * neckHalf(t) * 0.42f
        val gy = neckY(t) + neckNy(t) * neckHalf(t) * 0.42f
        if (i == 0) gloss.moveTo(gx, gy) else gloss.lineTo(gx, gy)
        if (i % 2 == 0) {
            scales.moveTo(
                neckX(t) - neckNx(t) * neckHalf(t) * 0.86f,
                neckY(t) - neckNy(t) * neckHalf(t) * 0.86f
            )
            scales.quadraticTo(
                neckX(t - 0.05f), neckY(t - 0.05f),
                neckX(t) + neckNx(t) * neckHalf(t) * 0.86f,
                neckY(t) + neckNy(t) * neckHalf(t) * 0.86f
            )
        }
    }
    drawPath(
        gloss, Color.White,
        alpha = alpha * 1.25f,
        style = Stroke(width = bodyW * 0.20f, cap = StrokeCap.Round)
    )
    drawPath(scales, color, alpha = alpha * 1.30f, style = Stroke(width = u * 0.0045f))

    // 头：朝向 = 颈末端的切线，所以头永远是顺着颈长出来的
    val hx = neckX(1f)
    val hy = neckY(1f)
    val dxh = hx - neckX(0.94f)
    val dyh = hy - neckY(0.94f)
    val lenh = hypot(dxh, dyh)
    if (lenh > 0.0001f) {
        drawSnakeHead(
            color = color,
            phase = phase,
            alpha = alpha,
            u = u,
            hx = hx,
            hy = hy,
            ux = dxh / lenh,
            uy = dyh / lenh,
            bodyWidth = bodyW
        )
    }
}

/**
 * 蛇头：颅 + 眉脊 + 金色竖瞳 + 张开的上下颌 + 两根毒牙 + 分叉舌。
 *
 * 全部画在**头自己的坐标系**里（`a` 沿朝向、`b` 横向，`+b` 恒是「屏幕下方」，见 `sgn`），
 * 朝向由颈末端的切线给进来 —— 头永远顺着蛇身长出来，一次都不用手调角度。
 *
 * 上一版是「一个水滴 + 两个白点 + 一条叉」：两只眼睛同时朝着看的人，读作正面的蛙脸；
 * 而蛇头是侧着的，只该看见一只眼。这一版按侧视画，辨识特征按重要性排：
 * **竖瞳**（哺乳动物是圆瞳）、**吻端收成铲形**、**眉脊压在眼上**（「凶」全在这一条）、
 * **张口露牙**、**分叉的舌**。
 *
 * 眼睛用 [PROP_GOLD]：reputation 整张卡片只有一档灰，那只金瞳是唯一的一点色，
 * 也就是这张卡片的视觉落点 —— 与页面背景那条大蛇的处理是同一套。
 */
private fun DrawScope.drawSnakeHead(
    color: Color,
    phase: Float,
    alpha: Float,
    u: Float,
    hx: Float,
    hy: Float,
    ux: Float,
    uy: Float,
    bodyWidth: Float
) {
    val hl = u * 0.150f
    val hh = bodyWidth * 0.52f
    // `+b` 必须恒指向**屏幕下方**。横向基是 `(-uy, ux)`，它的 y 分量就是 `ux` ——
    // 头朝左（`ux < 0`）时这个基指向屏幕上方，于是「下颌」画到了头顶、「眉脊」画到了下面，
    // 整个头倒过来。这条蛇的颈末端正是朝左探出去的，上一版截图里眼在下、颌在上就是这个原因。
    //
    // 乘一个符号 = 把头沿自身长轴镜像，而左向蛇本来就该是右向蛇的镜像，形状一点不用改。
    val sgn = if (ux >= 0f) 1f else -1f
    val ox = -uy * sgn
    val oy = ux * sgn
    fun fx(a: Float, b: Float) = hx + ux * a * hl + ox * b * hh
    fun fy(a: Float, b: Float) = hy + uy * a * hl + oy * b * hh
    // 张口固定 0.62：卡片是**定格**的一张图（低配机上相位恒为 0），
    // 开合动画在这里看不见，不如钉在攻击姿态最凶的那一帧
    val gape = 0.62f
    // 下颌的转向跟着镜像一起翻：转角同号于 sgn，`+a` 才是朝着（镜像后的）`+b` 转
    val jr = gape * 0.40f * sgn
    val jc = cos(jr)
    val js = sin(jr)
    val jux = ux * jc - uy * js
    val juy = uy * jc + ux * js
    val hingeX = fx(-0.08f, 0.14f)
    val hingeY = fy(-0.08f, 0.14f)
    val jox = -juy * sgn
    val joy = jux * sgn
    fun gx(a: Float, b: Float) = hingeX + jux * a * hl + jox * b * hh
    fun gy(a: Float, b: Float) = hingeY + juy * a * hl + joy * b * hh

    // 口腔：上下颌之间那一块。填得比全卡任何一处都重 —— 那是个洞
    val mouth = Path()
    mouth.moveTo(hingeX, hingeY)
    mouth.lineTo(fx(1.02f, 0.06f), fy(1.02f, 0.06f))
    mouth.lineTo(gx(0.96f, 0.12f), gy(0.96f, 0.12f))
    mouth.close()
    drawPath(mouth, Color.White, alpha = alpha * PROP_MASK)
    drawPath(mouth, color, alpha = alpha * 2.1f)

    // 下颌
    val jaw = Path()
    jaw.moveTo(gx(-0.08f, 0.02f), gy(-0.08f, 0.02f))
    jaw.cubicTo(
        gx(0.42f, 0.14f), gy(0.42f, 0.14f),
        gx(0.78f, 0.14f), gy(0.78f, 0.14f),
        gx(0.96f, 0.08f), gy(0.96f, 0.08f)
    )
    jaw.cubicTo(
        gx(0.82f, 0.50f), gy(0.82f, 0.50f),
        gx(0.38f, 0.66f), gy(0.38f, 0.66f),
        gx(-0.08f, 0.70f), gy(-0.08f, 0.70f)
    )
    jaw.close()
    drawPath(jaw, Color.White, alpha = alpha * PROP_MASK)
    drawPath(jaw, color, alpha = alpha * 1.15f)

    // 上颅：颈背 → 眉脊隆起 → 吻背下坡 → 吻端 → 上唇线收回颈
    val skull = Path()
    skull.moveTo(fx(-0.10f, -0.88f), fy(-0.10f, -0.88f))
    skull.cubicTo(
        fx(0.16f, -1.12f), fy(0.16f, -1.12f),
        fx(0.50f, -1.02f), fy(0.50f, -1.02f),
        fx(0.80f, -0.58f), fy(0.80f, -0.58f)
    )
    skull.cubicTo(
        fx(0.96f, -0.40f), fy(0.96f, -0.40f),
        fx(1.04f, -0.16f), fy(1.04f, -0.16f),
        fx(1.02f, 0.08f), fy(1.02f, 0.08f)
    )
    skull.cubicTo(
        fx(0.82f, 0.24f), fy(0.82f, 0.24f),
        fx(0.44f, 0.30f), fy(0.44f, 0.30f),
        fx(-0.10f, 0.20f), fy(-0.10f, 0.20f)
    )
    skull.close()
    drawPath(skull, Color.White, alpha = alpha * PROP_MASK)
    drawPath(skull, color, alpha = alpha * 1.20f)
    drawPath(skull, color, alpha = alpha * 1.9f, style = Stroke(width = u * 0.005f))

    // 毒牙：从上颌垂下来两根，白的（白卡上「白」= 留白，正是牙的样子）
    val fangs = Path()
    repeat(2) { k ->
        val a = 0.80f - k * 0.14f
        fangs.moveTo(fx(a, 0.12f), fy(a, 0.12f))
        fangs.lineTo(fx(a - 0.07f, 0.12f + 0.50f * gape), fy(a - 0.07f, 0.12f + 0.50f * gape))
    }
    drawPath(
        fangs, Color.White,
        alpha = alpha * 2.4f,
        style = Stroke(width = u * 0.007f, cap = StrokeCap.Round)
    )

    // 眉脊：压在眼睛上方的一道骨棱。蝮蛇的「凶」全在这一条，
    // 少了它就是一条温和的水蛇
    val brow = Path()
    brow.moveTo(fx(0.12f, -0.88f), fy(0.12f, -0.88f))
    brow.quadraticTo(
        fx(0.42f, -1.10f), fy(0.42f, -1.10f),
        fx(0.68f, -0.72f), fy(0.68f, -0.72f)
    )
    drawPath(
        brow, color,
        alpha = alpha * 2.2f,
        style = Stroke(width = u * 0.010f, cap = StrokeCap.Round)
    )

    // 眼：金色眼白 + 竖瞳。瞳孔用两段二次曲线拼成一枚梭形，跟着头的朝向一起转 ——
    // 画椭圆的话它永远是正的，头一转瞳孔就横了
    val eyeA = 0.40f
    val eyeB = -0.36f
    val eyeR = 0.34f
    drawCircle(
        PROP_GOLD, hh * eyeR, Offset(fx(eyeA, eyeB), fy(eyeA, eyeB)),
        alpha = alpha * 2.6f
    )
    val pupil = Path()
    pupil.moveTo(fx(eyeA, eyeB - eyeR * 0.88f), fy(eyeA, eyeB - eyeR * 0.88f))
    pupil.quadraticTo(
        fx(eyeA + eyeR * 0.32f, eyeB), fy(eyeA + eyeR * 0.32f, eyeB),
        fx(eyeA, eyeB + eyeR * 0.88f), fy(eyeA, eyeB + eyeR * 0.88f)
    )
    pupil.quadraticTo(
        fx(eyeA - eyeR * 0.32f, eyeB), fy(eyeA - eyeR * 0.32f, eyeB),
        fx(eyeA, eyeB - eyeR * 0.88f), fy(eyeA, eyeB - eyeR * 0.88f)
    )
    pupil.close()
    drawPath(pupil, color, alpha = alpha * 2.6f)

    // 鼻孔 + 颅顶三片鳞
    drawCircle(
        color, (u * 0.006f), Offset(fx(0.88f, -0.24f), fy(0.88f, -0.24f)),
        alpha = alpha * 2.0f
    )
    val crown = Path()
    for (s in 0..2) {
        val a = 0.18f + s * 0.20f
        crown.moveTo(fx(a, -0.86f), fy(a, -0.86f))
        crown.quadraticTo(
            fx(a - 0.14f, -0.44f), fy(a - 0.14f, -0.44f),
            fx(a, -0.04f), fy(a, -0.04f)
        )
    }
    drawPath(crown, color, alpha = alpha * 1.5f, style = Stroke(width = u * 0.0045f))

    // 舌：从张开的口里探出去，随相位吐进吐出，一帧一个长度，不用另开时钟
    val flick = 0.40f + 0.40f * sin(phase * TAU * 3f)
    val tongue = Path()
    tongue.moveTo(fx(0.92f, 0.30f), fy(0.92f, 0.30f))
    tongue.lineTo(fx(0.92f + flick * 0.62f, 0.24f), fy(0.92f + flick * 0.62f, 0.24f))
    tongue.moveTo(fx(0.92f + flick * 0.62f, 0.24f), fy(0.92f + flick * 0.62f, 0.24f))
    tongue.lineTo(fx(0.92f + flick * 1.10f, -0.04f), fy(0.92f + flick * 1.10f, -0.04f))
    tongue.moveTo(fx(0.92f + flick * 0.62f, 0.24f), fy(0.92f + flick * 0.62f, 0.24f))
    tongue.lineTo(fx(0.92f + flick * 1.10f, 0.50f), fy(0.92f + flick * 1.10f, 0.50f))
    drawPath(
        tongue, color,
        alpha = alpha * 2.0f,
        style = Stroke(width = u * 0.006f, cap = StrokeCap.Round)
    )
}

/**
 * 蛇戒：指环 + 环顶盘着的一只小蛇头。
 *
 * 指环故意画成**开口的两段弧**（缺口在环顶），蛇头咬在缺口上 ——
 * 闭合的圆环加一个头会读成「戒指上粘了个东西」。
 */
private fun DrawScope.drawSnakeRing(color: Color, alpha: Float, w: Float, h: Float, u: Float) {
    // 挪到右下角、并收小一档：新的那盘蛇底圈铺到 0.82h、右到 0.89w，
    // 戒指原来的位置（0.74w / 0.88h、半径 0.115u）正压在盘的右下沿上
    val center = Offset(w * 0.80f, h * 0.930f)
    val radius = u * 0.088f
    val band = Stroke(width = u * 0.021f)
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

/**
 * 7 · Lover：两只互锁的彩纸环 + 一只蝴蝶。
 *
 * *Paper Rings* 是 Lover 的第 8 首，纸环也是这一张里唯一**别处没有**的道具：
 * 小屋在背景那一层（`PASTEL_RAINBOW_HOUSE` 的彩虹 + 大屋），终局的雪景球里还有一栋，
 * 卡片再画一栋就是同一件道具同屏三遍 —— 用户对 Red 那条围巾提过同样的问题。
 *
 * 「互锁」靠一次补画实现：两环各自描完之后，把左环右侧那一段弧**再描一遍**压在右环上。
 * 少了这一步两只环只是叠在一起的两个圈，读作奥运标志的一角而不是套起来的纸环。
 */
private fun DrawScope.drawPaperRings(color: Color, phase: Float, alpha: Float) {
    pastelCloudTexture(color, phase)
    val box = propBox()
    val w = box.width
    val h = box.height
    val u = min(w, h)
    translate(left = box.left, top = box.top) {
        val strip = u * 0.075f
        val arx = w * 0.26f
        val ary = w * 0.30f
        val acx = w * 0.34f
        val acy = h * 0.58f
        val brx = w * 0.24f
        val bry = w * 0.28f
        val bcx = w * 0.68f
        val bcy = h * 0.51f
        // 呼吸：两环被风吹着轻轻转，右环幅度大一点 —— 它是挂在左环上的那一只
        val tilt = sin(phase * TAU) * 3.0f
        val aDeg = 8f + tilt * 0.4f
        rotate(degrees = aDeg, pivot = Offset(acx, acy)) {
            paperRing(acx, acy, arx, ary, strip, color, alpha, u)
        }
        rotate(degrees = -24f + tilt, pivot = Offset(bcx, bcy)) {
            paperRing(bcx, bcy, brx, bry, strip, color, alpha, u)
        }
        // 互锁：左环右侧那一段压回右环上面。右环在左环的右上方，
        // 两环相交的那段弧大致落在 -60°..10°，所以压回去的是它的下半段
        rotate(degrees = aDeg, pivot = Offset(acx, acy)) {
            // 补画的这一段同样要垫白，否则右环从左环的纸条里透出来，
            // 「压回去」这一步就白做了
            drawArc(
                color = Color.White,
                startAngle = -30f,
                sweepAngle = 44f,
                useCenter = false,
                topLeft = Offset(acx - arx, acy - ary),
                size = Size(arx * 2f, ary * 2f),
                alpha = alpha * PROP_MASK,
                style = Stroke(width = strip)
            )
            drawArc(
                color = color,
                startAngle = -30f,
                sweepAngle = 44f,
                useCenter = false,
                topLeft = Offset(acx - arx, acy - ary),
                size = Size(arx * 2f, ary * 2f),
                alpha = alpha * 1.15f,
                style = Stroke(width = strip)
            )
        }
        drawButterfly(color, phase, alpha, w, h, u)
    }
}

/** 一只纸环：环身 + 内缘高光 + 环底那一道粘缝。 */
private fun DrawScope.paperRing(
    cx: Float,
    cy: Float,
    rx: Float,
    ry: Float,
    strip: Float,
    color: Color,
    alpha: Float,
    u: Float
) {
    // 垫白：两只环相交那一段，后画的那只要真的挡住先画的。
    // 都是半透明描边的话相交处两条纸都在，读作两个叠印的圈
    drawOval(
        color = Color.White,
        topLeft = Offset(cx - rx, cy - ry),
        size = Size(rx * 2f, ry * 2f),
        alpha = alpha * PROP_MASK,
        style = Stroke(width = strip)
    )
    drawOval(
        color = color,
        topLeft = Offset(cx - rx, cy - ry),
        size = Size(rx * 2f, ry * 2f),
        alpha = alpha * 1.15f,
        style = Stroke(width = strip)
    )
    // 内缘高光：纸条的里侧受光。少了它环身是一根宽度均匀的粗线，读作橡皮圈
    val ix = rx - strip * 0.34f
    val iy = ry - strip * 0.34f
    drawOval(
        color = PROP_WARM,
        topLeft = Offset(cx - ix, cy - iy),
        size = Size(ix * 2f, iy * 2f),
        alpha = 0.42f,
        style = Stroke(width = strip * 0.26f)
    )
    // 粘缝：一条纸粘成环，接缝就在环底。这一道是「纸」环而不是金属环的唯一说明
    drawLine(
        color = color,
        start = Offset(cx - strip * 0.10f, cy + ry - strip * 0.55f),
        end = Offset(cx + strip * 0.10f, cy + ry + strip * 0.55f),
        strokeWidth = (u * 0.008f).coerceAtLeast(1f),
        alpha = alpha * 1.6f
    )
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
    // 垫白：椅子先画（注释见 [drawCardiganChair]），但两者都是半透明的 ——
    // 不垫这一层，两根立柱与顶横档会整条穿过衣身，「挂在椅背上」变成「印在椅子上」
    drawPath(body, Color.White, alpha = alpha * PROP_MASK)
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
    drawPath(sleeve, Color.White, alpha = alpha * PROP_MASK)
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

/** 9 · evermore：霉霉的背影 —— 法式辫 + 格纹呢大衣。照封面原图复刻。 */
private fun DrawScope.drawBraidPlaid(color: Color, phase: Float, lowRam: Boolean, alpha: Float) {
    branchTexture(color, phase, lowRam)
    val box = propBox()
    val w = box.width
    val h = box.height
    val u = min(w, h)
    translate(left = box.left, top = box.top) {
        drawBackView(color, phase, alpha, w, h, u)
    }
}

/**
 * 背影：后脑与收进辫子的头发 → 格纹呢大衣的翻领与肩 → 一条从颅顶编到背心的法式辫。
 *
 * *evermore* 的封面就是这一张，需求方给了原图，这一版照着原图复刻。原图里只有三样东西：
 *
 * 1. **一颗后脑**。头发是金色的，两侧的头发**全部被斜着往上、往中间收进辫子**，
 *    肩上一根都没有 —— 之前那一版让头发披到肩、下缘还做了波浪毛边，那是散发不是法式辫，
 *    整块读作一顶连帽衫的帽子。两只耳朵露在发外，皮肤比头发浅一档。
 * 2. **一条法式辫**。从**颅顶**起编（不是后脑中段），越往下越细：头上那一截接近头宽的
 *    四成，垂到背心的那一截只有二十分之一，收在一撮散开的发尾里，没有发圈。
 * 3. **一件格纹呢大衣**。翻领 + 驳头，格子是**大块**的（一个循环占掉小半个肩宽），
 *    锈橙那几道比底色亮。肩线直接出框。
 *
 * ## 辫花为什么是「一叠朝下的弧」
 *
 * 试过三种画法：三条相位差 1/3 的正弦（正弦是连续的，辫子是一段一段的）、
 * 一列左右交替倾斜的短肉段（相邻两段一段左高一段右高、两端凑不上，柱体边缘出现深豁口，
 * 中间的亮线还连成一条 W —— 截出来是拉链或糖棍）。都不对。
 *
 * 从背后看一条辫子的真实结构是**一叠嵌套的 V**：每一道辫花横跨整个柱宽、中间往下坠，
 * 相邻两道重叠一半。横跨全宽是关键，柱体因此是实心的，两侧只剩浅浅的起伏。
 *
 * 辫花画成**填充的带**而不是描边的线：柱宽从 0.085w 收到 0.011w，一条描边只有一个线宽，
 * 收不动；改成一道道闭合的带，全部塞进同一个 Path 一次填充，20 来道辫花只要 4 次绘制。
 *
 * 节距也跟着柱宽收（`pitch = half × 1.06`）—— 细辫子的辫花也细。定长节距的话尾巴那一截
 * 会散成几颗离得很远的小括号。
 */
private fun DrawScope.drawBackView(
    color: Color,
    phase: Float,
    alpha: Float,
    w: Float,
    h: Float,
    u: Float
) {
    val cx = w * 0.50f
    val headCy = h * 0.250f
    val headRx = w * 0.232f
    val napeY = h * 0.436f
    val napeHalf = w * 0.120f
    val shoulderY = h * 0.500f
    val edge = w * 0.62f

    // 某个高度上发块的半宽：耳际最宽，往上往下都收。侧发那批发缕靠它落在剪影里面
    val hairHalf = { y: Float ->
        val s = ((y - headCy) / (napeY - headCy)).coerceIn(-1.35f, 1f)
        if (s >= 0f) headRx - (headRx - napeHalf) * s * s else headRx * (1f - 0.60f * s * s)
    }

    // ── 头发 ──
    // 颅顶是一个穹：三次贝塞尔的控制点要抬 4/3 倍穹高，穹顶才落在 headCy - 穹高。
    // 下沿收到颈后（napeHalf），**不披到肩** —— 法式辫把两侧的头发全收走了
    val hair = Path()
    hair.moveTo(cx - headRx, headCy)
    hair.cubicTo(
        cx - headRx, headCy - h * 0.190f,
        cx + headRx, headCy - h * 0.190f,
        cx + headRx, headCy
    )
    hair.cubicTo(
        cx + headRx, headCy + h * 0.086f,
        cx + headRx * 0.80f, napeY - h * 0.026f,
        cx + napeHalf, napeY
    )
    hair.cubicTo(
        cx + napeHalf * 0.42f, napeY + h * 0.020f,
        cx - napeHalf * 0.42f, napeY + h * 0.020f,
        cx - napeHalf, napeY
    )
    hair.cubicTo(
        cx - headRx * 0.80f, napeY - h * 0.026f,
        cx - headRx, headCy + h * 0.086f,
        cx - headRx, headCy
    )
    hair.close()
    drawPath(hair, Color.White, alpha = alpha * PROP_MASK)
    drawPath(hair, color, alpha = alpha * 1.05f)
    drawPath(hair, color, alpha = alpha * 1.70f, style = Stroke(width = u * 0.007f))

    // 耳朵：封面上两只都露在发外。填充比头发浅一档 —— 皮肤不是头发
    for (side in 0..1) {
        val dir = if (side == 0) -1f else 1f
        val ex = cx + dir * headRx * 0.96f
        val ey = headCy + h * 0.050f
        val ear = Path()
        ear.moveTo(ex - dir * u * 0.006f, ey - h * 0.034f)
        ear.cubicTo(
            ex + dir * u * 0.032f, ey - h * 0.030f,
            ex + dir * u * 0.028f, ey + h * 0.024f,
            ex - dir * u * 0.004f, ey + h * 0.032f
        )
        ear.close()
        drawPath(ear, Color.White, alpha = alpha * PROP_MASK)
        drawPath(ear, color, alpha = alpha * 0.55f)
        drawPath(ear, color, alpha = alpha * 1.25f, style = Stroke(width = u * 0.006f))
    }

    // 把两侧头发**斜着往上、往中间**收进辫子的那一批发缕。
    // 这是「法式」的唯一标志，也是封面上最抓眼的纹理：普通三股辫从发尾编起、两侧的头发
    // 是垂下来的；法式辫一路把新头发拧进去，所以侧发的走向是斜向上而不是竖着往下
    //
    // 每侧八缕、短、贴着头形走：上一版每侧五缕、每缕从侧边一路斜到正中辫子上，
    // 十条长斜线加一个圆头轮廓，截出来是一片叶子的叶脉。真头发是**很多根短的**，
    // 而且只走到辫子边上就被卷进去了，不会画到中线
    val sweeps = Path()
    repeat(12) { index ->
        val t = (index / 2) / 5f
        val dir = if (index % 2 == 0) -1f else 1f
        val y0 = napeY - (napeY - (headCy - h * 0.140f)) * t
        val hw = hairHalf(y0)
        // 抬升量随高度变：靠颈后的几缕斜得厉害（要绕过后脑往上收），
        // 靠颅顶的几缕几乎是竖着往里。等角度的一排短斜线会把整个穹顶排成罗纹
        val rise = h * (0.082f - 0.055f * t)
        sweeps.moveTo(cx + dir * hw * 0.95f, y0)
        sweeps.quadraticTo(
            cx + dir * hw * 0.70f, y0 - rise * 0.55f,
            cx + dir * hw * (0.34f + 0.22f * t), y0 - rise
        )
    }
    // 1.35：整张卡片的母题都压在 PROP_ALPHA=0.35 上，发丝再淡就只剩一个空气球轮廓。
    // 上限是 PROP_MASK（0.78/0.35 ≈ 2.23），到那儿才开始截顶
    drawPath(sweeps, color, alpha = alpha * 1.35f, style = Stroke(width = u * 0.0055f))
    // ── 格纹呢大衣 ──
    // 立领 + 肩 + 下摆是**一整条剪影**：领子不另画一块。分成两块画就得分别裁格纹，
    // 交界处必然错纹，而真大衣的格子是连着穿过领子的
    val collarHalf = w * 0.250f
    val collarTop = shoulderY - h * 0.074f
    val coat = Path()
    coat.moveTo(cx - collarHalf, shoulderY - h * 0.016f)
    coat.cubicTo(
        cx - collarHalf * 0.90f, collarTop,
        cx + collarHalf * 0.90f, collarTop,
        cx + collarHalf, shoulderY - h * 0.016f
    )
    coat.cubicTo(
        cx + w * 0.330f, shoulderY + h * 0.012f,
        cx + w * 0.480f, shoulderY + h * 0.060f,
        cx + edge, shoulderY + h * 0.118f
    )
    // 两侧**往下外倾**而不是竖直下来：道具框只占卡片右侧 32%，怎么放都有一侧的边落在
    // 卡片里。竖直的那两条边加上收口的下摆，整块读作一只格纹袋（这条被点名过）。
    // 斜出去的边读作「衣服在框外接着走」，下摆同时压到 1.04h 由卡片圆角裁掉
    coat.lineTo(cx + edge * 1.26f, h * 1.04f)
    coat.lineTo(cx - edge * 1.26f, h * 1.04f)
    coat.lineTo(cx - edge, shoulderY + h * 0.118f)
    coat.cubicTo(
        cx - w * 0.480f, shoulderY + h * 0.060f,
        cx - w * 0.330f, shoulderY + h * 0.012f,
        cx - collarHalf, shoulderY - h * 0.016f
    )
    coat.close()
    drawPath(coat, Color.White, alpha = alpha * PROP_MASK)
    drawPath(coat, color, alpha = alpha * 0.62f)
    // 格纹：**大块**。封面上这件呢大衣的格子横过整个背，一个循环占掉小半个肩宽；
    // 上一版 0.150u 的密网加 0.005u 的细线读作衬衫或桌布，不是粗呢大衣。
    // 深格不另调色（纵横两条带子叠起来自然深一档，真格纹也是这么织的），
    // 亮条用白 —— 那是封面上锈橙的那几道，比呢子底色亮
    // 一个循环里放**三条宽窄不同**的带：宽深带 + 窄深带 + 亮细条。
    // 上一版一个循环只有一条等宽深带 + 一条亮条，纵横一叠是标准棋盘格 —— 那是桌布。
    // 真格纹的一个循环里带子宽窄不一，才有「组」的感觉
    clipPath(coat) {
        val span = edge * 2.6f
        val left = cx - edge * 1.3f
        val pitch = u * 0.320f
        var x = left - pitch * 0.35f
        while (x < cx + edge * 1.3f) {
            drawRect(color, Offset(x, collarTop), Size(u * 0.132f, h * 0.72f), alpha = alpha * 0.46f)
            drawRect(color, Offset(x + u * 0.170f, collarTop), Size(u * 0.052f, h * 0.72f), alpha = alpha * 0.30f)
            drawRect(
                Color.White, Offset(x + u * 0.246f, collarTop), Size(u * 0.030f, h * 0.72f),
                alpha = alpha * 0.34f
            )
            x += pitch
        }
        var y = collarTop - u * 0.105f
        while (y < h * 1.05f) {
            drawRect(color, Offset(left, y), Size(span, u * 0.132f), alpha = alpha * 0.46f)
            drawRect(color, Offset(left, y + u * 0.170f), Size(span, u * 0.052f), alpha = alpha * 0.30f)
            drawRect(
                Color.White, Offset(left, y + u * 0.246f), Size(span, u * 0.030f),
                alpha = alpha * 0.34f
            )
            y += pitch
        }
    }
    // 领口下沿 + 两条驳头折线。**只描线不填色**，格纹才连着穿过领子
    val collarLine = Path()
    collarLine.moveTo(cx - collarHalf, shoulderY - h * 0.016f)
    collarLine.cubicTo(
        cx - collarHalf * 0.52f, shoulderY + h * 0.036f,
        cx + collarHalf * 0.52f, shoulderY + h * 0.036f,
        cx + collarHalf, shoulderY - h * 0.016f
    )
    for (side in 0..1) {
        val dir = if (side == 0) -1f else 1f
        collarLine.moveTo(cx + dir * collarHalf, shoulderY - h * 0.014f)
        collarLine.quadraticTo(
            cx + dir * w * 0.320f, shoulderY + h * 0.052f,
            cx + dir * w * 0.372f, shoulderY + h * 0.116f
        )
    }
    drawPath(collarLine, color, alpha = alpha * 1.55f, style = Stroke(width = u * 0.0085f))
    // 肩线：从领子外端斜着出框。两侧与下摆都在框外，描了反而把「出框」封死
    val shoulderLine = Path()
    for (side in 0..1) {
        val dir = if (side == 0) -1f else 1f
        shoulderLine.moveTo(cx + dir * collarHalf, shoulderY - h * 0.016f)
        shoulderLine.cubicTo(
            cx + dir * w * 0.330f, shoulderY + h * 0.012f,
            cx + dir * w * 0.480f, shoulderY + h * 0.060f,
            cx + dir * edge, shoulderY + h * 0.118f
        )
    }
    drawPath(shoulderLine, color, alpha = alpha * 1.10f, style = Stroke(width = u * 0.0075f))

    // ── 法式辫 ──
    // 从颅顶（0.118h，穹顶在 0.1075h）编到背心（0.905h）。每一道辫花是一条朝下的弧带，
    // 横跨整个柱宽；柱宽与节距同步收窄。摆一点：辫尾比辫根摆得多，整条读作垂着的
    val braidTop = h * 0.118f
    val braidTip = h * 0.855f
    val swing = sin(phase * TAU) * u * 0.012f
    val braidUnder = Path()
    val braid = Path()
    val creases = Path()
    val gloss = Path()
    var by = braidTop
    var tipX = cx
    var guard = 0
    while (by < braidTip && guard < 64) {
        val f = ((by - braidTop) / (braidTip - braidTop)).coerceIn(0f, 1f)
        // 两段式收窄：颈后（BRAID_NAPE_F）之前是头上那一截法式辫 —— 宽、慢慢收；
        // 过了颈后立刻细一档，之后是垂着的绳。一条线性锥形从头贯到尾的话，
        // 「头上编的」与「垂下来的」两段没有分界，整条读作一只松果或鱼尾
        val half = if (f < BRAID_NAPE_F) {
            w * (0.085f - 0.030f * (f / BRAID_NAPE_F))
        } else {
            val g = (f - BRAID_NAPE_F) / (1f - BRAID_NAPE_F)
            w * (0.055f - 0.038f * g * (0.60f + 0.40f * g))
        }
        val pitch = half * 1.06f
        val bx = cx + swing * f * f
        val dip = pitch * 1.70f
        // 一道辫花 = 外弧 + 反向的内弧闭合成的带。同一个 Path 里所有带同向绕，
        // NonZero 填充规则下它们自然并成一整条实心柱
        // 1.02 而不是 1.06：垫白只需要挡住底纹，大一圈就会在每一道辫花外面镶出一道
        // 白边，二十道白边叠起来整条辫子读作麦穗或松果
        band(braidUnder, bx, by, half * 1.02f, dip, pitch * 1.02f)
        band(braid, bx, by, half, dip, pitch * 0.96f)
        band(creases, bx, by + pitch * 0.50f, half * 0.64f, dip * 0.86f, pitch * 0.15f)
        band(gloss, bx - half * 0.30f, by + pitch * 0.20f, half * 0.34f, dip * 0.55f, pitch * 0.11f)
        tipX = bx
        by += pitch
        guard++
    }
    drawPath(braidUnder, Color.White, alpha = alpha * PROP_MASK)
    // 辫身压淡一点、辫花之间的深缝加重一档：辫子读不读作辫子全看这道缝的对比，
    // 两者都是 0.8 左右的话整条是一根光滑的浅色管子
    drawPath(braid, color, alpha = alpha * 0.72f)
    drawPath(creases, color, alpha = alpha * 2.20f)
    // 0.20 而不是 0.40：亮面一道道叠起来会连成一条贯穿全长的白线，又变回拉链
    drawPath(gloss, Color.White, alpha = alpha * 0.20f)

    // 散开的发尾：封面上辫子末端没有发圈，直接散成一撮。少了这一撮，辫子读作被剪断的
    val ends = Path()
    repeat(5) { index ->
        val spread = (index - 2) / 2f
        ends.moveTo(tipX + spread * u * 0.008f, braidTip - h * 0.006f)
        ends.quadraticTo(
            tipX + spread * u * 0.030f, braidTip + h * 0.026f,
            tipX + spread * u * 0.052f, braidTip + h * 0.056f
        )
    }
    drawPath(ends, color, alpha = alpha * 1.05f, style = Stroke(width = u * 0.0055f, cap = StrokeCap.Round))
}

/**
 * 往 [path] 里加一道**朝下的弧带**：外弧从 (cx−half, cy−th/2) 坠到 cy+dip−th/2 再回升，
 * 内弧反向回来，闭合成一条等厚的带。
 *
 * 辫花不用描边而用填充，就是为了让宽度能逐道收窄 —— 一条 Path 只有一个线宽。
 */
/** 辫子从颅顶到发尾的哪个比例上是颈后：过了这里柱宽立刻细一档。 */
private const val BRAID_NAPE_F = 0.42f

private fun band(path: Path, cx: Float, cy: Float, half: Float, dip: Float, th: Float) {
    val up = cy - th * 0.5f
    val down = cy + th * 0.5f
    path.moveTo(cx - half, up)
    path.quadraticTo(cx, up + dip, cx + half, up)
    path.lineTo(cx + half, down)
    path.quadraticTo(cx, down + dip, cx - half, down)
    path.close()
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
        // 垫白：金属机身是不透明的，底纹那批星芒不该从机身里透出来
        drawRoundRect(
            Color.White, Offset(bodyL, bodyTop), Size(bodyR - bodyL, bodyBottom - bodyTop),
            CornerRadius(u * 0.030f), alpha = alpha * PROP_MASK
        )
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
            Color.White, Offset(bodyL + u * 0.014f, guardTop),
            Size(bodyR - bodyL - u * 0.028f, bodyTop - guardTop), alpha = alpha * PROP_MASK
        )
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
        val pivot = Offset(paperL + paperW / 2f, paperT + paperH / 2f)
        // 整张纸连纸上的字、游标、羽毛笔一起歪 [LETTER_TILT]，像随手摊在桌上。
        // 墨水瓶**不进**这个 rotate —— 瓶子歪着读起来就是要倒了
        rotate(degrees = LETTER_TILT, pivot = pivot) {
            // 纸底下的影子：一张纸摊在卡片上就有影子，右下偏（光在左上）。
            // 少了这一片，信纸是印在卡片上的一块米色，不是放在上面的一张纸 ——
            // 「压在信纸上」得先让信纸自己浮起来
            drawRect(
                LETTER_INK, Offset(paperL + u * 0.009f, paperT + u * 0.012f),
                Size(paperW, paperH), alpha = alpha * 0.34f
            )
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
        }
        // 瓶子压在信纸右上角。落位按**旋转之后**的那个纸角算 —— 纸整块歪了
        // [LETTER_TILT]，拿未旋转的角点摆瓶子会差出 tan(3.5°) × 半张纸那么多，
        // 屏幕上读作「摆在纸旁边」而不是压在纸上（上一版就是这样）。
        // 画在字之前 —— 羽毛笔的羽面要从瓶子前面掠过（笔握在手里，瓶子在桌上）
        drawInkBottle(
            alpha = alpha,
            w = w,
            h = h,
            u = u,
            corner = rotatedPoint(paperR, paperT, pivot, LETTER_TILT),
            paper = Rect(paperL, paperT, paperR, paperB),
            pivot = pivot
        )
        rotate(degrees = LETTER_TILT, pivot = pivot) {
            drawWriting(phase, alpha, u, paperL, paperT, paperW, paperH)
        }
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
        // 羽面按 **s² 的正弦**分布：最宽处落在羽轴 80% 高处，从那里往笔尖一路收细，
        // 靠笔尖那一段几乎是光杆 —— 真羽毛笔就是把羽根那截羽枝刮掉、再把杆削成笔尖的。
        // 上一版用 s 的正弦，最宽处在羽轴正中间、靠笔尖那头立刻就宽起来，
        // 屏幕上读作羽毛装反了
        val span = ((t - 0.30f) / 0.70f).coerceIn(0f, 1f)
        val len = u * 0.125f * sin(span * span * PI.toFloat())
        // 羽枝朝**羽尖**方向撇（+ux），不是朝笔尖：羽枝在羽轴上本来就是往梢部斜出去的，
        // 撇错方向整支笔看起来是倒插着的
        barbs.moveTo(px, py)
        barbs.lineTo(px + (ox * 0.92f + ux * 0.42f) * len, py + (oy * 0.92f + uy * 0.42f) * len)
        barbs.moveTo(px, py)
        barbs.lineTo(px + (-ox * 0.92f + ux * 0.42f) * len, py + (-oy * 0.92f + uy * 0.42f) * len)
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

/** 信纸（连纸上的字与笔）歪的角度。瓶子要按转过之后的纸角落位，所以只能有一处。 */
private const val LETTER_TILT = -3.5f

/** 把 [x] / [y] 绕 [pivot] 转 [degrees]。用来取「旋转之后」的纸角。 */
private fun rotatedPoint(x: Float, y: Float, pivot: Offset, degrees: Float): Offset {
    val rad = degrees / 180f * PI.toFloat()
    val cs = cos(rad)
    val sn = sin(rad)
    val dx = x - pivot.x
    val dy = y - pivot.y
    return Offset(pivot.x + dx * cs - dy * sn, pivot.y + dx * sn + dy * cs)
}

/**
 * 墨水瓶：**压在信纸右上角**的一只玻璃瓶。
 *
 * ## 为什么要重画
 *
 * 上一版信纸与瓶子都是正视图里的剪影：纸是个矩形、瓶是个圆角矩形，两者只是叠在一起，
 * 屏幕上读作「纸旁边还画了个瓶子」，压没压上去看不出来。压在东西上这件事要三样东西
 * 同时成立，缺一样都不成：
 *
 * 1. **视线略高于瓶口**。所以螺帽有一个椭圆顶面、瓶底也露出一条椭圆的底面 ——
 *    纯正视图里这两处都是直线，看不出瓶子站在一个平面上。
 * 2. **两片影子**。投影（瓶底右下那一片，光在左上）说明瓶子离开纸面有高度；
 *    紧贴瓶底那一圈更深更小的接触影说明它确实落在纸上，没有飘着。只有投影没有接触影，
 *    瓶子会像浮在纸上方一厘米。
 * 3. **真的挡住纸**。瓶身垫一层白（[PROP_MASK]）：上一版瓶身只有 0.35 alpha，
 *    麻纸的边框描边、纸浆纤维、甚至笔迹都整条从瓶子里穿过去 —— 那读作一张贴纸，
 *    不是一只压在纸上的瓶子。
 *
 * 瓶底横跨纸缘：一半压着纸、一半探到纸外的桌面上，这才是「镇纸」；整只都在纸内
 * 只是「纸上还放着个瓶子」。[corner] 是旋转之后的纸右上角，瓶身左 52% 落在纸上。
 */
private fun DrawScope.drawInkBottle(
    alpha: Float,
    w: Float,
    h: Float,
    u: Float,
    corner: Offset,
    paper: Rect,
    pivot: Offset
) {
    val bw = w * 0.255f
    val bh = h * 0.185f
    val left = corner.x - bw * 0.52f
    val right = left + bw
    val top = corner.y + bh * 0.16f
    val bottom = top + bh
    val cx = (left + right) * 0.5f
    // 底面在这个俯角下露出来的半高。瓶身底沿是它的下半个椭圆
    val footRy = bh * 0.070f
    val shoulderY = top + bh * 0.30f
    val neckHalf = bw * 0.20f
    val neckTop = top - h * 0.030f

    // ── 影子（画在瓶子之前，被瓶身压住近侧那半片）──
    val cast = u * 0.030f
    drawOval(
        LETTER_INK, Offset(left + cast, bottom - footRy + cast * 0.55f),
        Size(bw, footRy * 2.2f), alpha = alpha * 0.50f
    )
    drawOval(
        LETTER_INK, Offset(left + u * 0.008f, bottom - footRy * 0.94f),
        Size(bw - u * 0.016f, footRy * 1.7f), alpha = alpha * 0.95f
    )

    // ── 瓶身 ──
    val glass = Path()
    glass.moveTo(left, bottom - footRy)
    glass.lineTo(left, shoulderY)
    // 收肩到瓶颈：吹制玻璃身上没有一处直角，肩这一段是全瓶最像玻璃的地方
    glass.cubicTo(
        left, top + (shoulderY - top) * 0.12f,
        cx - neckHalf - u * 0.070f, top,
        cx - neckHalf, top
    )
    glass.lineTo(cx - neckHalf, neckTop)
    glass.lineTo(cx + neckHalf, neckTop)
    glass.lineTo(cx + neckHalf, top)
    glass.cubicTo(
        cx + neckHalf + u * 0.070f, top,
        right, top + (shoulderY - top) * 0.12f,
        right, shoulderY
    )
    glass.lineTo(right, bottom - footRy)
    // 底沿走下半个椭圆，不是两个圆角 —— 从略高的视线看下去，瓶底就是这么一条弧
    glass.arcTo(Rect(left, bottom - footRy * 2f, right, bottom), 0f, 180f, false)
    glass.close()
    drawPath(glass, Color.White, alpha = alpha * PROP_MASK)
    // 玻璃是透的，所以瓶身里该看见纸：压在纸上那半边透出麻纸的米白，探出纸缘那半边
    // 透出卡片的白，**纸缘那条线穿过瓶身接着往下走**。
    //
    // 上一版整只瓶填一个米白，于是瓶身左右一个色 —— 瓶子跨在纸缘上这件事在屏幕上
    // 完全看不出来（实测瓶内 (241,237,224) 与纸面 (240,235,222) 只差 1），
    // 读作「纸旁边放着个瓶子」。这一段是「压在信纸右上角」最直接的一笔。
    //
    // 纸只填色 + 描边，纸浆纤维与笔迹仍被上面那层白挡着：透过玻璃看东西是糊的，
    // 一根根纤维还清清楚楚反而不像玻璃
    clipPath(glass) {
        rotate(degrees = LETTER_TILT, pivot = pivot) {
            drawRect(LETTER_PAPER, paper.topLeft, paper.size, alpha = 0.74f)
            drawRect(
                LETTER_INK, paper.topLeft, paper.size,
                alpha = alpha * 0.5f, style = Stroke(width = u * 0.007f)
            )
        }
    }
    // 玻璃自己那层雾：把里面透出来的东西压淡一档，边界才像隔着一层玻璃看
    drawPath(glass, Color.White, alpha = 0.26f)

    // ── 瓶里的墨（不透明，上沿平、下沿跟着底面的弧）──
    val wall = u * 0.016f
    val inkTop = top + bh * 0.54f
    val ink = Path()
    ink.moveTo(left + wall, inkTop)
    ink.lineTo(right - wall, inkTop)
    ink.lineTo(right - wall, bottom - wall - footRy)
    ink.arcTo(Rect(left + wall, bottom - wall - footRy * 2f, right - wall, bottom - wall), 0f, 180f, false)
    ink.close()
    drawPath(ink, LETTER_INK, alpha = alpha * 2.5f)
    // 液面高光 + 两端上翘的弯液面。这三笔是「里面是液体」的全部依据
    drawLine(
        Color.White, Offset(left + wall + u * 0.024f, inkTop + u * 0.008f),
        Offset(right - wall - u * 0.042f, inkTop + u * 0.008f), u * 0.008f, alpha = alpha * 2.0f
    )
    drawLine(
        Color.White, Offset(left + wall + u * 0.004f, inkTop - u * 0.006f),
        Offset(left + wall + u * 0.026f, inkTop + u * 0.008f), u * 0.005f, alpha = alpha * 1.2f
    )
    drawLine(
        Color.White, Offset(right - wall - u * 0.004f, inkTop - u * 0.006f),
        Offset(right - wall - u * 0.026f, inkTop + u * 0.008f), u * 0.005f, alpha = alpha * 1.2f
    )

    drawPath(glass, LETTER_INK, alpha = alpha * 1.5f, style = Stroke(width = u * 0.009f))
    // 左壁一道长竖高光（光在左上）、右壁一道短的弱反光交代玻璃厚度
    drawLine(
        Color.White, Offset(left + u * 0.030f, shoulderY + u * 0.014f),
        Offset(left + u * 0.030f, bottom - footRy - u * 0.020f), u * 0.010f, alpha = alpha * 1.3f
    )
    drawLine(
        Color.White, Offset(right - u * 0.026f, inkTop + u * 0.030f),
        Offset(right - u * 0.026f, bottom - footRy - u * 0.024f), u * 0.006f, alpha = alpha * 0.7f
    )
    // 肩上一段弧高光：这一笔把「收肩」说清楚
    val gleam = Path()
    gleam.moveTo(left + u * 0.046f, shoulderY - u * 0.006f)
    gleam.quadraticTo(cx - neckHalf * 1.20f, top + u * 0.014f, cx - neckHalf * 0.55f, top + u * 0.026f)
    drawPath(gleam, Color.White, alpha = alpha * 0.9f, style = Stroke(width = u * 0.008f))
    // 底面：露出来的那一条比瓶身深一档，瓶子才像坐在纸上而不是浮着
    drawArc(
        color = LETTER_INK,
        startAngle = 8f,
        sweepAngle = 164f,
        useCenter = false,
        topLeft = Offset(left + wall * 0.5f, bottom - footRy * 2f),
        size = Size(bw - wall, footRy * 2f),
        alpha = alpha * 1.1f,
        style = Stroke(width = u * 0.007f)
    )

    // ── 螺帽：侧面 + 一个椭圆顶面（顶面是「视线高于瓶口」的唯一证据）+ 三道防滑纹 ──
    val capHalf = bw * 0.29f
    val capH = h * 0.024f
    val capTop = neckTop - capH
    val capRy = capH * 0.44f
    drawRoundRect(
        LETTER_INK, Offset(cx - capHalf, capTop), Size(capHalf * 2f, capH + h * 0.005f),
        CornerRadius(u * 0.020f), alpha = alpha * 1.9f
    )
    drawOval(
        LETTER_PAPER, Offset(cx - capHalf, capTop - capRy), Size(capHalf * 2f, capRy * 2f),
        alpha = 0.88f
    )
    drawOval(
        LETTER_INK, Offset(cx - capHalf, capTop - capRy), Size(capHalf * 2f, capRy * 2f),
        alpha = alpha * 1.6f, style = Stroke(width = u * 0.006f)
    )
    repeat(3) { index ->
        val gx = cx + capHalf * (-0.44f + index * 0.44f)
        drawLine(
            Color.White, Offset(gx, capTop + capH * 0.26f), Offset(gx, capTop + capH * 0.86f),
            u * 0.005f, alpha = alpha * 0.55f
        )
    }
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
    drawRect(Color.White, Offset(w * 0.02f, topY), Size(w * 0.96f, slab), alpha = alpha * PROP_MASK)
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
    val baseY = mouthY + h * 0.14f
    // 接触影：管底压在台面上那一小片，往右下偏（光在左上）。
    // 少了它口红是画在桌板上的一道色块，不是立在桌板上的一支管
    drawOval(
        color, Offset(left - u * 0.010f, baseY - h * 0.012f),
        Size(right - left + u * 0.052f, h * 0.026f), alpha = alpha * 0.85f
    )
    // 垫白：桌板比它先画，不垫这一层桌板的上沿会横穿管身
    drawRect(Color.White, Offset(left, mouthY), Size(right - left, h * 0.14f), alpha = alpha * PROP_MASK)
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
    val rx = w * 0.105f
    // 压扁 12%：粉扑是躺在台面上的一块圆饼，视线略高于桌板，看下去就是个椭圆。
    // 正圆读作正对着镜头立起来的一个球
    val ry = rx * 0.88f
    // 接触影 + 垫白，同口红
    drawOval(
        color, Offset(center.x - rx * 0.92f, center.y + ry * 0.62f),
        Size(rx * 1.84f, ry * 0.52f), alpha = alpha * 0.85f
    )
    drawOval(
        Color.White, Offset(center.x - rx, center.y - ry), Size(rx * 2f, ry * 2f),
        alpha = alpha * PROP_MASK
    )
    drawOval(
        color, Offset(center.x - rx, center.y - ry), Size(rx * 2f, ry * 2f),
        alpha = alpha * 0.85f
    )
    drawOval(
        Color.White, Offset(center.x - rx * 0.70f, center.y - ry * 0.70f),
        Size(rx * 1.40f, ry * 1.40f), alpha = 0.50f
    )
    val fuzz = Path()
    val segments = 22
    repeat(segments) { index ->
        val a0 = index / segments.toFloat() * TAU
        val a1 = (index + 1) / segments.toFloat() * TAU
        val mid = (a0 + a1) / 2f
        fuzz.moveTo(center.x + cos(a0) * rx, center.y + sin(a0) * ry)
        fuzz.quadraticTo(
            center.x + cos(mid) * rx * 1.16f, center.y + sin(mid) * ry * 1.16f,
            center.x + cos(a1) * rx, center.y + sin(a1) * ry
        )
    }
    drawPath(fuzz, color, alpha = alpha * 1.4f, style = Stroke(width = u * 0.006f))
    drawArc(
        color = color,
        startAngle = 200f,
        sweepAngle = 140f,
        useCenter = false,
        topLeft = Offset(center.x - rx * 0.34f, center.y - ry * 0.62f),
        size = Size(rx * 0.68f, ry * 0.48f),
        alpha = alpha * 1.8f,
        style = Stroke(width = u * 0.010f)
    )
}
