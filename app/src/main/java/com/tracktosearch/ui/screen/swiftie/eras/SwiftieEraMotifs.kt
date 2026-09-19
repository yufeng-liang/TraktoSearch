package com.tracktosearch.ui.screen.swiftie.eras

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
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
internal const val PROP_MASK = PROP_MASK_ALPHA / PROP_ALPHA

/**
 * 长歌名让位时 `columnFade` 的下限：`0.35 × 0.514 ≈ 0.18`。
 *
 * 卡片那边只管在 1f 与这个值之间插值，「0.18」这个数字不重复写第二遍。
 */
internal const val COLUMN_FADE_MIN = 0.514f

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
 * @param eraElapsedMs 本段已过的毫秒。**只有 Lover 那把弓读它** —— 拉弓 / 撒放是一次性的
 *   动作，[phase] 那条 3.6s 的锯齿波编不出「只发生一次」。负数 = 低配机那一档定格，
 *   弓永远停在松弦上着箭的静态（`drawLoverBow`）
 */
fun DrawScope.drawEraMotif(
    motif: SwiftieEraMotif,
    color: Color,
    phase: Float,
    lowRam: Boolean,
    columnFade: Float,
    eraElapsedMs: Long,
    loverAimAngle: Float = LOVER_FALLBACK_AIM_ANGLE,
    /**
     * 本母题的照片素材。**只有三个母题用得上**：Red 的红围巾、evermore 的背影与
     * 1989 拍立得里的照面 —— 其余母题一律传 null，那些函数根本不读这个参数，母题本身照旧画。
     *
     * 位图只能在组合阶段读（draw 阶段拿不到 resources），所以由 `SwiftieEraCard` 传进来。
     */
    propPhoto: ImageBitmap? = null,
    /**
     * 画布上排字用的测量器。**只有 Red 那杯拿铁用得上**（纸套上那行 MAPLE LATTE），
     * 其余母题一律传 null，那些函数根本不读它。
     *
     * 和 [propPhoto] 同一个原因由卡片传进来：文字只能在组合阶段量。
     */
    textMeasurer: TextMeasurer? = null,
    /**
     * Red 杯套上那片刻线枫叶的**墨线图**（参考图取墨，见 [MapleArt]）。
     * 只有 Red 传、且只读墨线那一张 —— 压印不填色，实心图没必要解码。
     * 为 null 时（没传或还没解码完）只跳过这片叶，杯与字照画。
     */
    propMapleInk: ImageBitmap? = null
) {
    val alpha = PROP_ALPHA * columnFade.coerceIn(0f, 1f)
    when (motif) {
        SwiftieEraMotif.PORCH_GUITAR -> drawPorchGuitar(color, phase, alpha)
        SwiftieEraMotif.CASTLE_BALCONY -> drawCastleBalcony(color, phase, alpha)
        SwiftieEraMotif.STAGE_CURTAIN -> drawStageCurtain(color, phase, alpha)
        SwiftieEraMotif.RED_SCARF -> drawRedScarf(color, phase, alpha, propPhoto, textMeasurer, propMapleInk)
        SwiftieEraMotif.POLAROID -> drawPolaroidGull(color, phase, alpha, propPhoto)
        SwiftieEraMotif.COILED_SNAKE -> drawCoiledSnake(color, phase, lowRam, alpha)
        SwiftieEraMotif.LOVER_ARCHER ->
            drawLoverArcher(color, phase, alpha, eraElapsedMs, loverAimAngle)
        SwiftieEraMotif.CARDIGAN_CHAIR -> drawCardiganChair(color, phase, alpha)
        SwiftieEraMotif.BRAID_PLAID -> drawBraidPlaid(color, phase, lowRam, alpha, propPhoto)
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
 * 式子在 [swiftiePropBox] 里 —— 弓画在卡片这一层、飞行中的箭画在页面最上层、
 * 插住的箭画在背景层，三处必须从同一个式子推出发点（见 `SwiftieLoverArcher`）。
 */
private fun DrawScope.propBox(): Rect = swiftiePropBox(size)

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
 * `SwiftieEraBackdrop` 里 Showgirl 那张舞台把人物留给中央原创舞女剪影，这里只画卡片道具。
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
 * 4 · Red：一条红围巾（卡片右上角）+ 一杯枫糖拿铁（共用的右侧列里）。
 *
 * 围巾是 *All Too Well* 的核心意象，所以它在卡片上不是「垂下来的一条」而是
 * **绕起来挂着**的样子；直挂的一条与页面背景那根枯枝、以及别张卡片上的垂布
 * （folklore 的开衫、Speak Now 的裙摆）都太像。
 *
 * 画的是**照片抠图**（`era_red_scarf.png`：用户给的素材，裁到外框、抹掉透明区的
 * 黑 RGB、降到 760 宽），不再用 Canvas 手画。手画那版把罗纹与流苏试到第七种画法，
 * 在 437px 的道具框里针脚的绒感与 `ALL TOO WELL` 的刺绣各占十几像素，几何化到
 * 最后只能是一圈带描边的色带；照片自带全部质感，代价只有一张 0.85MB 的 PNG。
 *
 * 位置在**右上角**而不是共用的右侧列：那一列从 0.34h 往下，正是曲目名最长那几行
 * （"We Are Never Ever Getting Back Together"）伸到的地方。照片下缘落在 0.39h，
 * 纵向压到的只有第 1..4 行那几条短歌名，横向离它们的行尾还差 0.08w ——
 * 也就是说这张卡上 `columnFade` 其实不会被触发，道具与长歌名在几何上根本不相交。
 * 照片本身也画在曲目文字**底下**（母题在卡片 `drawBehind` 那一层），
 * 让位系数仍然乘着，纯粹是与其他 11 张共用同一条通路。
 *
 * 那杯拿铁替掉的是一顶 fedora。理由不在「咖啡」，而在**这首歌自己的线索**：
 * 详见 [drawMapleLatte]。
 *
 * 手画稿留在仓库外（`build/egg-shots/red_scarf_handdrawn.kt.txt`，围巾与那顶 fedora
 * 都在里面），要再改结构时从那儿起。
 */
private fun DrawScope.drawRedScarf(
    color: Color,
    phase: Float,
    alpha: Float,
    scarf: ImageBitmap?,
    textMeasurer: TextMeasurer?,
    mapleInk: ImageBitmap?
) {
    knitStripeTexture(color, phase)
    // 位图读不到时（低配机预解压失败之类）就只剩底纹与那杯拿铁，不画半个围巾
    if (scarf != null) drawRedScarfPhoto(scarf, phase)
    val box = propBox()
    translate(left = box.left, top = box.top) {
        drawMapleLatte(color, alpha, box.width, box.height, textMeasurer, mapleInk)
    }
}

/** 照片的落位：右缘留 0.025w 的边距、上缘从 0.018h 起 —— 旧手绘围巾就挂在右上角这一块。 */
private const val RED_SCARF_PHOTO_RIGHT = 0.975f
private const val RED_SCARF_PHOTO_TOP = 0.018f
private const val RED_SCARF_PHOTO_WIDTH = 0.34f

/**
 * 把围巾照片贴到卡片右上角：宽度按卡片宽的比例算，高度按素材自己的长宽比跟出来。
 *
 * 显影：**不透明原样画**（2026-09-19 需求方定案「用原图颜色，不加透明度遮罩」——
 * 旧档 0.35×2.2≈0.77 在卡上读作灰蒙了一层）。围巾在卡上的落位（0.018h 起、
 * 高约 0.23h）整块在标题区里，压不到曲目行，长歌名让位（columnFade）时它不需要跟着让。
 *
 * 呼吸：整张照片极轻微地左右摆一下（吊着的一条围巾本来就会晃），幅度压在照片宽的
 * 1% —— 卡片上的道具会动，但不该「跳」。
 */
private fun DrawScope.drawRedScarfPhoto(photo: ImageBitmap, phase: Float) {
    val dstW = size.width * RED_SCARF_PHOTO_WIDTH
    val dstH = dstW * photo.height / photo.width
    val sway = sin(phase * TAU) * dstW * 0.010f
    drawImage(
        image = photo,
        srcOffset = IntOffset.Zero,
        srcSize = IntSize(photo.width, photo.height),
        dstOffset = IntOffset(
            (size.width * (RED_SCARF_PHOTO_RIGHT - RED_SCARF_PHOTO_WIDTH) + sway).roundToInt(),
            (size.height * RED_SCARF_PHOTO_TOP).roundToInt()
        ),
        dstSize = IntSize(dstW.roundToInt(), dstH.roundToInt()),
        alpha = 1f,
        filterQuality = FilterQuality.High
    )
    // 主色罩**不能画**：照片的透明区是整块矩形，罩上去在卡片上留下一个方框
    // （evermore 那版真机踩过）。照片自己的红本来就与这张卡的主色同源。
}

/** 杯身口径占道具框宽的比例（道具框 0.32 卡片宽 × 0.47 ≈ 0.15 卡片宽，真机约 194px）。 */
private const val LATTE_WT = 0.47f

/** 杯轴在道具框里的横向位置。 */
private const val LATTE_CX = 0.52f

/** 杯底落在道具框下缘 —— 道具框底 = 0.94 卡片高，往下还留着 0.06h 的纸。 */
private const val LATTE_BASE = 1.00f

// ── 杯体几何。单位一律是「杯身口径」，数值来自参考图逐行测量 ──
// 参考图 `build/egg-shots/ref-red/cup-sleeve-leaf.jpg`（侧视白杯 + 牛皮纸套 + 刻线枫叶），
// 复刻器 `build/egg-shots/red_cup.py`。量出来的关键几条：
//   锥度 底/口 0.74；总高（含盖）/口径 2.09；盖 = 穹顶圆唇 + 收进去的裙边；
//   纸套高 0.90 口径（占杯高 43%）且紧贴裙边下沿；套上枫叶高 0.855 口径、左偏 0.06。
private const val CUP_BOTTOM_RATIO = 0.74f
private const val CUP_TOTAL_RATIO = 2.09f
private const val LID_SKIRT = 0.24f
private const val LID_BEVEL = 0.09f
private const val LID_FLANGE = 0.19f
private const val LID_H = LID_SKIRT + LID_BEVEL + LID_FLANGE

/** 圆唇最宽处（在唇的下缘）—— 比杯身宽 16%，这一跳是「这是个杯盖」的唯一证据。 */
private const val FLANGE_W = 1.16f
private const val FLANGE_TOP_W = 0.94f
private const val SLEEVE_H = 0.90f
private const val SLEEVE_GAP = 0.02f
private const val SLEEVE_PROUD = 0.018f
/** 杯套上那片刻线枫叶的高度（占杯身口径）。参考图里它几乎占满整条纸套（0.855），
 *  这里压到 0.55 —— 底下要给那行字腾出半格。 */
private const val LEAF_H = 0.55f

/** 叶柄末端落在哪个标高（自杯底往上，口径为单位）。纸套是 0.65..1.55，
 *  叶占上半、字占下半：叶柄止于 0.825，字的字帽顶在 0.77，中间留 0.055 的空。 */
private const val LEAF_BASE = 0.825f

/** 叶柄根相对杯轴的横向偏移。参考图那片是**正**在套中央的，所以是 0。 */
private const val LEAF_DX = 0f

/**
 * 纸套上印的那行字。
 *
 * **Maple Latte 就是 *All Too Well* 的 liner notes 隐藏信息**（专辑内页里那句大写的
 * 密语之一，当年她被拍到和 Jake 在 Fido Café 喝的就是这个）。所以这行字不是装饰，
 * 它和卡片右上角那条围巾是**同一首歌的两条线索**：围巾是歌里的实物，拿铁是liner note。
 * 字母全大写、字距拉开，是外带杯套印刷那一路的写法。
 */
private const val LATTE_TEXT = "MAPLE LATTE"

/** 字宽目标（占杯身口径）。定死宽度、字号反推 —— 杯身尺寸随卡片走，字号不能写死。 */
private const val LATTE_TEXT_WIDTH = 0.80f

/** 字的中心标高（自杯底往上，口径为单位）：纸套下半段、枫叶叶柄之下。 */
private const val LATTE_TEXT_CENTER = 0.725f

/** 量宽度用的探针字号（sp）。只用来取比例，量完按比例再量一次。 */
private const val LATTE_TEXT_PROBE_SP = 16f

/** 字距（em）。 */
private const val LATTE_TEXT_SPACING = 0.12f

private const val PANEL_W = 0.86f
private const val HOLE_DX = 0.20f
private const val HOLE_W = 0.11f

/**
 * 椭圆压扁系数（视角约 5.4°）。
 *
 * 判据是参考图杯底前缘那道可见的平段只有 55px 宽：2·√(2·R·ry) = 55、R = 63.5
 * 推出 ry ≈ 6px，即 k = 2·ry/口径 ≈ 0.095。第一版按 0.16 画，杯口那道椭圆弧深得
 * 像俯视图。
 */
private const val CUP_ELLIPSE_K = 0.095f

/**
 * 白纸杯的显影倍率：有效 alpha = 0.35 × 1.65 ≈ 0.58。
 *
 * [PROP_MASK]（0.78）是「垫白」用的 —— 它的活是把后续的颜色顶到接近纸色以上，
 * 不是拿来当可见的白色。真机上量过 1989 那张的宝丽来白框（同样是白物件）：
 * 比卡片纸亮 Δ30，反解出白色这一层要 ~0.58。
 */
private const val LATTE_WHITE_GAIN = 1.65f

/**
 * 一杯枫糖拿铁：白纸杯 + 牛皮纸套（套上一片刻线枫叶）+ 白盖 + 一缕热气。
 *
 * 选它的理由不在「咖啡」而在**这张卡自己的线索**：*All Too Well* 的 liner notes
 * 隐藏信息就是 **Maple Latte**（当年被拍到与 Jake 在 Fido Café 喝枫糖拿铁）。围巾是那首歌
 * 里的实物、杯套上这片枫叶接的是同一首歌的另一条密语；而 Red 这个时代的三个公认符号
 * 正是「枫叶 / 围巾 / 枫糖拿铁」—— 前两个已经分别在背景与卡上，这是缺的那一个。
 *
 * 它替掉的是一顶 fedora。那顶帽子并非随手画的：2012 原版封面就是她低头、脸被一顶
 * **宽檐帽**的阴影遮住（TV 版才换成酒红丝绒渔夫帽 + 1932 敞篷车）。
 *
 * 结构对着实拍照片量过（口径 1.0）：盖是**穹顶圆唇 + 收进去的裙边**（不是三段等宽硬板），
 * 纸套高 0.90 口径并紧贴裙边下沿，套上那片刻线枫叶高 0.855 口径、**不居中**（左偏 0.06）。
 * 叶形与叶脉来自**参考图取墨**的墨线位图（[MapleArt]，与背景那五片、飘落的秋叶同一片真叶）——
 * 同一屏上出现两种枫叶比画得糙更糟。压印只有线、不填色，所以只读 [rememberMapleInkArt] 那一张；
 * 而且杯套上这片刻线用的是**直柄**那版（背景那套的叶柄按参考图是往左弯的，
 * 杯套垂直空间窄、柄歪着不好看 —— 用户 2026-09-12 定：杯子的柄保持竖直）。
 *
 * 纸套上还有一行 **MAPLE LATTE**（见 [LATTE_TEXT]），字号按纸套宽度反推。
 * 它要一个 [TextMeasurer]：位图与文字都只能在组合阶段取，所以由 `SwiftieEraCard` 传进来；
 * 为 null 时（没有测量器）只跳过这行字，杯与叶照画。
 *
 * 明暗：白纸杯的立体感全靠**右侧三段压暗 + 左缘一条高光**，都 `clipPath` 在剪影内。
 * 杯子每帧重画，所以只建一次 Path 反复 rewind，不给 GC 添抖动（同本文件其余母题）。
 */
private fun DrawScope.drawMapleLatte(
    color: Color,
    alpha: Float,
    w: Float,
    h: Float,
    textMeasurer: TextMeasurer?,
    mapleInk: ImageBitmap?
) {
    val wt = w * LATTE_WT
    val axis = w * LATTE_CX
    val baseY = h * LATTE_BASE
    // 尺寸一律以「口径」为单位，画的时候才乘 wt：横向 ax() 自杯轴、纵向 ay() 自杯底往上。
    // 单位与像素混着传是这套图最容易栽的坑 —— 复刻器上就栽过一次（每个横向尺寸被 wt 再乘一遍，
    // 纸套横贯整张卡）。传进来的数只有两种写法：裸小数 = 单位，带 wt = 像素。
    fun ax(u: Float) = axis + u * wt
    fun ay(u: Float) = baseY - u * wt

    val bodyTop = CUP_TOTAL_RATIO - LID_H
    val skirtTop = bodyTop + LID_SKIRT
    val flangeBot = skirtTop + LID_BEVEL
    // 顶面那道前弧本身要占掉 flangeTopHalf * k 的高度，所以把它从总高里扣掉：
    // CUP_TOTAL_RATIO 量的是「含盖最高那一点」，不是盖沿的圆心
    val flangeTopHalf = FLANGE_TOP_W * 0.5f
    val flangeTop = CUP_TOTAL_RATIO - flangeTopHalf * CUP_ELLIPSE_K
    val sleeveTop = bodyTop - SLEEVE_GAP
    val sleeveBot = sleeveTop - SLEEVE_H
    // 杯身在标高 u 处的宽度（单位）
    fun widthAt(u: Float): Float =
        CUP_BOTTOM_RATIO + (1f - CUP_BOTTOM_RATIO) * (u / bodyTop).coerceIn(0f, 1f)

    /**
     * 纸套的软木颗粒。参考图那条套子是**颗粒面**（软木/再生纸），不是平色牛皮纸。
     *
     * 颗粒位置全部由序号哈希出来（**不是每帧 Random**）：逐帧变的话静帧纹理就是一片
     * 噪点在跳。颗粒沿「该标高处的半宽」铺开，跟着杯身的锥度收；按深浅分三批合进
     * 三条 Path，一共只出三次绘制调用（同本文件成排小件的写法）。
     */
    fun sleeveGrain(a: Float, uTop: Float, uBot: Float) {
        val dark = Path()
        val light = Path()
        val fine = Path()
        for (i in 0 until SLEEVE_GRAIN) {
            val gu = hash01(SLEEVE_GRAIN_SEED, 0, i)
            val gv = hash01(SLEEVE_GRAIN_SEED, 1, i)
            val gt = hash01(SLEEVE_GRAIN_SEED, 2, i)
            val v = uBot + (uTop - uBot) * gv
            val halfW = (widthAt(v) + SLEEVE_PROUD * 2f) * 0.5f * wt
            val x = axis + (gu - 0.5f) * 2f * halfW * 0.96f
            val y = ay(v)
            val r = wt * (0.0026f + gt * 0.0085f)
            val target = when {
                gt < 0.34f -> dark
                gt < 0.72f -> light
                else -> fine
            }
            target.addOval(Rect(x - r, y - r * 0.75f, x + r, y + r * 0.75f))
        }
        drawPath(dark, PROP_KRAFT_DARK, alpha = a * 0.48f)
        drawPath(light, PROP_KRAFT_LIGHT, alpha = a * 0.58f)
        drawPath(fine, PROP_KRAFT_DARK, alpha = a * 0.30f)
    }

    val path = Path()

    // 圆环的前缘：视线在环上方，前缘一律往下鼓 k·半宽（k 由参考图 55px 的平底弧反推）
    fun Path.ringFront(u: Float, width: Float, backwards: Boolean = false) {
        val endX = if (backwards) -width * 0.5f else width * 0.5f
        quadraticTo(ax(0f), ay(u - width * 0.5f * CUP_ELLIPSE_K), ax(endX), ay(u))
    }

    // 一条「圆环带」：上下两条前弧 + 两条侧边（唇下投影、纸套都用它）
    fun Path.ringBand(uTop: Float, wTop: Float, uBot: Float, wBot: Float) {
        rewind()
        moveTo(ax(-wTop * 0.5f), ay(uTop))
        ringFront(uTop, wTop)
        lineTo(ax(wBot * 0.5f), ay(uBot))
        ringFront(uBot, wBot, backwards = true)
        close()
    }

    // ── 剪影：底前弧 → 右侧锥度 → 裙边 → 圆唇（从最宽处收到顶面）→ 顶面弧 → 左侧下来 ──
    val flangeHalf = FLANGE_W * 0.5f
    path.rewind()
    path.moveTo(ax(-CUP_BOTTOM_RATIO * 0.5f), ay(0f))
    path.ringFront(0f, CUP_BOTTOM_RATIO)
    path.lineTo(ax(0.5f), ay(bodyTop))
    path.lineTo(ax(0.5f), ay(skirtTop))
    // 圆唇：**蘑菇形**，三段都是曲线。上一版从裙边到最宽处画直线、顶面又是一段直坡，
    // 整只盖读成一个尖角六边形（真机 2026-09-12 一眼就看出来了）—— 圆唇之所以叫圆唇，
    // 就是这条侧面是个外凸的肚子：裙边 → 最宽处（切线竖直）→ 收进顶沿
    path.cubicTo(
        ax(flangeHalf * 0.92f), ay(flangeBot + 0.02f),
        ax(flangeHalf), ay(flangeBot + 0.06f),
        ax(flangeHalf), ay(flangeBot + (flangeTop - flangeBot) * 0.45f)
    )
    path.cubicTo(
        ax(flangeHalf), ay(flangeTop - 0.05f),
        ax(flangeTopHalf + 0.055f), ay(flangeTop - 0.012f),
        ax(flangeTopHalf), ay(flangeTop)
    )
    // 顶面：一道**上凸**的弧。省掉它杯盖读成一段管子；凸向搞反则盖顶凹成一个碗
    path.quadraticTo(
        ax(0f), ay(CUP_TOTAL_RATIO),
        ax(-flangeTopHalf), ay(flangeTop)
    )
    path.cubicTo(
        ax(-(flangeTopHalf + 0.055f)), ay(flangeTop - 0.012f),
        ax(-flangeHalf), ay(flangeTop - 0.05f),
        ax(-flangeHalf), ay(flangeBot + (flangeTop - flangeBot) * 0.45f)
    )
    path.cubicTo(
        ax(-flangeHalf), ay(flangeBot + 0.06f),
        ax(-flangeHalf * 0.92f), ay(flangeBot + 0.02f),
        ax(-flangeHalf), ay(flangeBot)
    )
    path.lineTo(ax(-0.5f), ay(skirtTop))
    path.lineTo(ax(-0.5f), ay(bodyTop))
    path.lineTo(ax(-CUP_BOTTOM_RATIO * 0.5f), ay(0f))
    path.close()

    val silhouette = Path().apply { addPath(path) }
    drawPath(silhouette, Color.White, alpha = alpha * LATTE_WHITE_GAIN)

    // ── 圆柱的明暗：右侧三段压暗 + 左缘一条高光。参考图右缘暗约 40/255 ──
    clipPath(silhouette) {
        val bandLeft = floatArrayOf(0.04f, 0.30f, 0.52f)
        val bandAlpha = floatArrayOf(0.26f, 0.22f, 0.20f)
        for (i in 0..2) {
            path.rewind()
            path.moveTo(ax(bandLeft[i]), ay(0f))
            path.lineTo(ax(0.72f), ay(0f))
            path.lineTo(ax(0.72f), ay(CUP_TOTAL_RATIO))
            path.lineTo(ax(bandLeft[i]), ay(CUP_TOTAL_RATIO))
            path.close()
            drawPath(path, color, alpha = alpha * bandAlpha[i])
        }
        path.rewind()
        path.moveTo(ax(-0.44f), ay(0f))
        path.lineTo(ax(-0.28f), ay(0f))
        path.lineTo(ax(-0.30f), ay(bodyTop))
        path.lineTo(ax(-0.46f), ay(bodyTop))
        path.close()
        drawPath(path, Color.White, alpha = alpha * 1.30f)
    }

    // ── 唇下的投影：外翻 16% 的唇压在杯身上必然留一道暗带。没有它这个盖是「贴」上去的 ──
    for (i in 0..2) {
        path.ringBand(
            flangeBot - 0.055f * i, 1f - 0.02f * i,
            flangeBot - 0.055f * (i + 1), 1f - 0.02f * (i + 1)
        )
        drawPath(path, color, alpha = alpha * (0.30f - i * 0.08f))
    }
    // 凹面内圈（参考图盖顶那一圈看得见的台阶）
    val panelW = FLANGE_TOP_W * PANEL_W * wt
    drawOval(
        color = PROP_KRAFT_EDGE,
        topLeft = Offset(ax(-PANEL_W * 0.5f), ay(flangeTop - 0.006f) - panelW * 0.5f * CUP_ELLIPSE_K),
        size = Size(panelW, panelW * CUP_ELLIPSE_K * 2f),
        alpha = alpha * 0.80f,
        style = Stroke(width = wt * 0.005f)
    )

    // ── 轮廓：剪影一圈 ──
    drawPath(
        path = silhouette, color = color, alpha = alpha * 1.55f,
        style = Stroke(width = wt * 0.0085f, join = StrokeJoin.Round)
    )
    // 唇的下缘：盖与杯身的分界
    path.rewind()
    path.moveTo(ax(-flangeHalf), ay(flangeBot))
    path.ringFront(flangeBot, FLANGE_W)
    drawPath(
        path, color, alpha = alpha * 0.90f,
        style = Stroke(width = wt * 0.005f)
    )

    // 吸口 + 从口里透出来的一点咖啡（参考图那只是能看见的）。画在轮廓之后、盖的台阶之内
    val holeW = HOLE_W * wt
    val holeX = ax(HOLE_DX)
    val holeY = ay(flangeTop - 0.010f)
    drawOval(
        color = Color.White,
        topLeft = Offset(holeX - holeW * 0.5f, holeY - holeW * 0.5f * CUP_ELLIPSE_K),
        size = Size(holeW, holeW * CUP_ELLIPSE_K * 1.4f),
        alpha = alpha * 1.10f
    )
    drawOval(
        color = PROP_COFFEE,
        topLeft = Offset(holeX - holeW * 0.40f, holeY - holeW * 0.40f * CUP_ELLIPSE_K * 1.1f),
        size = Size(holeW * 0.80f, holeW * 0.80f * CUP_ELLIPSE_K * 1.1f),
        alpha = alpha * 2.00f
    )

    // ── 牛皮纸套：比杯身外凸 1.8%（实测），所以画在轮廓之后 —— 它自己的边就是这一段的杯沿 ──
    val sleeveBulge = SLEEVE_PROUD * 2f
    val sleeveWTop = widthAt(sleeveTop) + sleeveBulge
    val sleeveWBot = widthAt(sleeveBot) + sleeveBulge
    path.ringBand(sleeveTop, sleeveWTop, sleeveBot, sleeveWBot)
    // 两遍：一遍 0.51 的覆盖率太透，杯身那三段压暗会从套子底下透出来（真机上一道竖带）
    drawPath(path, PROP_KRAFT, alpha = alpha * 1.45f)
    drawPath(path, PROP_KRAFT, alpha = alpha * 1.10f)
    // 软木颗粒（参考图那条套子是颗粒面，不是平色牛皮纸）。**必须剪进套子**：
    // 颗粒是按矩形撒的，不剪就会洒到杯身上去
    clipPath(path) { sleeveGrain(alpha, sleeveTop, sleeveBot) }
    // 上缘受光、下缘积暗：参考图套子上沿一道亮线、下沿一段发暗
    drawPath(
        path, PROP_KRAFT_LIGHT, alpha = alpha * 0.75f,
        style = Stroke(width = wt * 0.010f)
    )
    val shade = Path()
    shade.addRect(
        Rect(
            ax(-sleeveWBot * 0.5f), ay(sleeveBot),
            ax(sleeveWBot * 0.5f), ay(sleeveBot + SLEEVE_H * 0.16f)
        )
    )
    drawPath(shade, PROP_KRAFT_DARK, alpha = alpha * 0.55f)
    // 套子自己的边线（上下两道厚度 + 两侧立边）
    path.ringBand(sleeveTop, sleeveWTop, sleeveBot, sleeveWBot)
    drawPath(
        path, PROP_KRAFT_EDGE, alpha = alpha * 0.95f,
        style = Stroke(width = wt * 0.007f)
    )

    // ── 套上的刻线枫叶（压印，不填色）+ 那行 liner note 密语 ──
    // 取墨位图的锚点就是**叶柄末端**（资产的底边中点），落位直接给 `ay(LEAF_BASE)`。
    // 换图前这里要算两个 half（先按 1.30 反推叶身半高、再乘 0.85 才落到叶柄末端），
    // 那一步正是当年「整片叶子连叶柄吊到纸套外、穿字而过」与「落到 y = -4917」两次事故所在；
    // 「叶柄末端在资产的哪个位置」现在烘在 PNG 里，这一层算术没有了
    drawMapleInk(
        line = mapleInk,
        height = LEAF_H * wt,
        stemEnd = Offset(ax(LEAF_DX), ay(LEAF_BASE)),
        // 咖啡色刻线（不是时代主色）：这是纸套上的印刷，见 [PROP_ENGRAVE]。
        // 线宽烘在墨线图里（照片那条刻线折到设备尺度约 1.8px），不再给 strokeWidth
        color = PROP_ENGRAVE,
        alpha = alpha * 1.85f
    )
    if (textMeasurer != null) {
        val inkAlpha = (alpha * 1.85f).coerceAtMost(1f)
        fun styleOf(sizeSp: Float) = TextStyle(
            color = PROP_ENGRAVE.copy(alpha = inkAlpha),
            fontSize = sizeSp.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = LATTE_TEXT_SPACING.em
        )
        // 先按探针字号量一遍，按目标宽度求出字号再量 —— 纸套宽度随卡片走，
        // 字号只能反推。两次 measure 都吃 TextMeasurer 的缓存，每帧不重排
        val probe = textMeasurer.measure(AnnotatedString(LATTE_TEXT), styleOf(LATTE_TEXT_PROBE_SP))
        val fitted = if (probe.size.width > 0) {
            LATTE_TEXT_PROBE_SP * (LATTE_TEXT_WIDTH * wt) / probe.size.width
        } else {
            LATTE_TEXT_PROBE_SP
        }
        val layout = textMeasurer.measure(AnnotatedString(LATTE_TEXT), styleOf(fitted))
        drawText(
            textLayoutResult = layout,
            topLeft = Offset(
                axis - layout.size.width * 0.5f,
                ay(LATTE_TEXT_CENTER) - layout.size.height * 0.5f
            )
        )
    }

    // ── 热气：吸口升起的两缕，各三段、越往上越淡（热饮在静帧里全靠它）──
    for (s in 0..1) {
        val sx = ax(HOLE_DX + (if (s == 0) 0.02f else -0.07f))
        for (seg in 0..2) {
            val t0 = seg / 3f
            path.rewind()
            for (i in 0..6) {
                val t = t0 + i / 18f
                val px = sx + sin(t * 2.6f + s * 2.1f) * wt * 0.075f * (0.4f + t)
                val py = holeY - t * wt * 0.30f
                if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            // 白热气在粉色纸面上极跳：线要细、要短、要淡。第一版 0.46 口径高、0.020 宽、
            // 不透明度还乘了 1.15，真机上就是杯口窜起一团白火
            drawPath(
                path = path,
                color = Color.White,
                alpha = alpha * 0.62f * (1f - t0) * (if (s == 0) 0.85f else 0.55f),
                style = Stroke(width = wt * 0.011f, cap = StrokeCap.Round)
            )
        }
    }
}

/** 牛皮纸（杯套）。道具材质色，不吃时代主色 —— Red 是红、Showgirl 是橙，纸套不该跟着变。 */
private val PROP_KRAFT = Color(0xFFC08850)

/** 牛皮纸的暗色：刻线、下缘积暗、套子上下缘那道厚度线。 */
private val PROP_KRAFT_EDGE = Color(0xFF9C6034)

/** 颗粒的深粒。比 [PROP_KRAFT_EDGE] 浅一档 —— 硬边线要比颗粒重，否则整条套子糊成一团。 */
private val PROP_KRAFT_DARK = Color(0xFFAA6E3C)

/** 牛皮纸的亮色：颗粒的亮粒、上缘受光。 */
private val PROP_KRAFT_LIGHT = Color(0xFFD8A46C)

/** 软木颗粒的颗数。参考图那条套子上百来颗可见的深浅粒。 */
private const val SLEEVE_GRAIN = 380

/** 颗粒的哈希种子。**换这个数每次装机看到的颗粒都不同**，定一个就别再动。 */
private const val SLEEVE_GRAIN_SEED = 7717

/** 杯里的咖啡：吸口那一点棕色。 */
private val PROP_COFFEE = Color(0xFF6C4226)

/**
 * 纸套上那片刻线枫叶与那行字的墨色：**咖啡色**，不吃时代主色。
 *
 * 参考图上那片叶子就是压印的深棕刻线（实测 (79,49,32)），用户要的也是这个 ——
 * 它不是「Red 时代的一张红色线画」，而是**一杯枫糖拿铁纸套上的印刷**：
 * 套子是牛皮纸色、字与叶是咖啡色的刻痕，整件东西才读成实物。
 */
private val PROP_ENGRAVE = Color(0xFF5C3A21)

/**
 * 拍立得照面的显影强度：有效 alpha = 0.35 × 2.2 ≈ 0.77。
 *
 * 与 evermore 背影同一条硬规则（1.5 需求方看过嫌淡、2.2 定案的历史见
 * [EVERMORE_PHOTO_GAIN]）：照片细节都在、纸还透气，再往上就是一张实心贴纸。
 * Red 围巾不在这一档里 —— 2026-09-19 起它按需求方定案改成了不透明原样画。
 */
private const val POLAROID_PHOTO_GAIN = 2.2f

/** 5 · 1989：宝丽来白框（照面是真实照片）+ 一只海鸥。轻微倾斜，像随手摆上去的。 */
private fun DrawScope.drawPolaroidGull(color: Color, phase: Float, alpha: Float, photo: ImageBitmap?) {
    skyBandTexture(color)
    val box = propBox()
    val w = box.width
    val h = box.height
    val u = min(w, h)
    translate(left = box.left, top = box.top) {
        // 0.74 → 0.88：整框放大（白框连照面一起，用户点名「拍立得整体变大」）。
        // 中心横挪到 0.48w 找补：最大摆幅 8.5° 那一帧右缘还留约 0.4% 卡宽，照旧 0.50 会出卡
        val frameW = w * 0.88f
        val frameH = frameW * 1.20f
        val center = Offset(w * 0.48f, h * 0.64f)
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
                if (photo != null) {
                    // 照面 = 真实照片，cover 铺满窗口：源图 1:1、窗口 0.86 框宽 × 0.85 框宽，
                    // 中心裁掉的不满 1%。主色罩同样不能画 —— 位图是整块矩形，罩了在白框里留框
                    val scale = max(photoW / photo.width, photoH / photo.height)
                    val srcW = (photoW / scale).roundToInt().coerceAtMost(photo.width)
                    val srcH = (photoH / scale).roundToInt().coerceAtMost(photo.height)
                    drawImage(
                        image = photo,
                        srcOffset = IntOffset((photo.width - srcW) / 2, (photo.height - srcH) / 2),
                        srcSize = IntSize(srcW, srcH),
                        dstOffset = IntOffset(photoInset.roundToInt(), photoInset.roundToInt()),
                        dstSize = IntSize(photoW.roundToInt(), photoH.roundToInt()),
                        alpha = (alpha * POLAROID_PHOTO_GAIN).coerceAtMost(1f),
                        filterQuality = FilterQuality.High
                    )
                } else {
                    // 兜底（资源缺失才走）：原来的时代色渐变照面 + 一道地平线
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
                    val horizon = photoInset + photoH * 0.62f
                    drawRect(
                        color, Offset(photoInset, horizon),
                        Size(photoW, photoInset + photoH - horizon), alpha = alpha * 0.6f
                    )
                    drawLine(
                        color, Offset(photoInset, horizon), Offset(photoInset + photoW, horizon),
                        u * 0.007f, alpha = alpha * 1.8f
                    )
                }
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

/** 6 · reputation：盘起来的蛇（昂头做攻击姿态）+ 一枚蛇戒（在卡片右上角）。 */
private fun DrawScope.drawCoiledSnake(color: Color, phase: Float, lowRam: Boolean, alpha: Float) {
    halftoneTexture(color, phase, lowRam)
    val box = propBox()
    translate(left = box.left, top = box.top) {
        drawSnakeCoil(color, phase, lowRam, alpha, box.width, box.height, min(box.width, box.height))
    }
    // 戒指挪出道具框、上到卡片右上角：留在框里就得跟盘蛇分那 32% 卡宽，
    // 被压到 20dp 见方，蛇头细节一个也画不下。口径与 Lover 那只蝴蝶一致（见 [drawSnakeRing]）
    drawSnakeRing(
        color = color,
        alpha = alpha,
        center = Offset(size.width * 0.800f, 52.dp.toPx()),
        radius = box.width * 0.26f
    )
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
    // 在**左**侧：盘蛇自己的重心偏右（三圈 + 立起来的颈与头都在右半边），左下角整片是空的，
    // 尾巴甩到那边正好补上。上一版甩在右边，末端落在 `(0.78w, 0.876h)` ——
    // 那里当年正压着戒指的外圈（戒指现已挪去卡片右上角），两件道具糊成一件。
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
 * 蛇戒：蛇身盘成一枚戒指，头在缺口上咬住自己的尾尖。
 *
 * ## 位置
 *
 * 钉在卡片**右上角**（`0.80w`、52dp，半径 `0.26 × 道具框宽`，约 27dp），和 Lover 那只
 * 蝴蝶同一套口径：横向按卡宽比例、竖向按 dp —— 竖锚点若按卡高比例，31 首的 TTPD 与
 * 11 首的 reputation 会把它放在两个高度（顶上那条标题带是 `CARD_CHROME_HEIGHT` 的 dp 预算）。
 * 上一版它缩在道具框的右下角（半径 `0.088u` ≈ 10dp、20dp 见方，还被点名「太小」）：
 * 道具框整块要让给盘蛇，戒指塞在角上只剩一个圆点。
 *
 * ## 环不是一个等宽的圆
 *
 * 蛇身从尾尖到颈一路变粗（`0.060r → 0.190r`），所以环是「外缘 + 内缘两条弧夹出来的
 * 多边形」，不是 Stroke：描边整圈只能一个线宽，那就又成了一枚普通金属环，蛇身的锥度
 * 全丢。环顶留 34° 缺口，蛇头**按缺口那道弦**接出去 —— 头长取弦长、方向取弦的倾角，
 * 头尖就正好落在尾尖上，缺口由头这段接上，整枚戒指读作闭环。
 *
 * 缺口与管径是按「头占环径几成」定的：弦长 `2r·sin(gap/2)`，0.60 弧度下头长 0.59r，
 * 约占环径三成 —— 真戒指上那颗蛇头就是这个量级；0.82 弧度那一版头长 0.80r，
 * 装上去是一颗鱼头把环吞了一半，吻尖还从环的外缘戳出去。
 *
 * 细节：外缘一道亮边（受光）、内缘一道反光、环身 13 片鳞、头按盘蛇那颗头的语言画
 * （颅 + 下颌 + 口腔 + 两颗毒牙 + 金瞳、竖瞳），只是尺寸缩到五分之一。
 * 白卡上「亮」等于色少，所以这几笔都是白的。
 *
 * @param center 环心（卡片坐标）
 * @param radius 环身中心线的半径，管径、头、鳞的尺寸都从它派生
 */
private fun DrawScope.drawSnakeRing(color: Color, alpha: Float, center: Offset, radius: Float) {
    // 角度从正上（12 点）起、顺时针为正：蛇身从尾尖（缺口右沿）顺时针盘满一圈到环顶的颈
    val gap = 0.60f
    val span = TAU - gap
    val tailHalf = radius * 0.060f
    val neckHalf = radius * 0.190f
    val steps = 36

    // 管径沿身：尾尖细、颈部粗。smoothstep 起收，缺口两侧才不露折点
    fun halfAt(t: Float) = tailHalf + (neckHalf - tailHalf) * (t * t * (3f - 2f * t))
    fun at(t: Float, k: Float): Offset {
        val a = gap + t * span
        val r = radius + halfAt(t) * k
        return Offset(center.x + sin(a) * r, center.y - cos(a) * r)
    }

    // 环身本体 + 外缘亮边 + 内缘反光：三条带子共用同一组采样点，一次走完
    val band = Path()
    val ridge = Path()
    val gloss = Path()
    for (i in 0..steps) {
        val t = i / steps.toFloat()
        val o = at(t, 1f)
        val ro = at(t, 0.98f)
        val go = at(t, -0.98f)
        if (i == 0) {
            band.moveTo(o.x, o.y)
            ridge.moveTo(ro.x, ro.y)
            gloss.moveTo(go.x, go.y)
        } else {
            band.lineTo(o.x, o.y)
            ridge.lineTo(ro.x, ro.y)
            gloss.lineTo(go.x, go.y)
        }
    }
    for (i in steps downTo 0) {
        val t = i / steps.toFloat()
        val p = at(t, -1f)
        val ri = at(t, 0.56f)
        val gi = at(t, -0.62f)
        band.lineTo(p.x, p.y)
        ridge.lineTo(ri.x, ri.y)
        gloss.lineTo(gi.x, gi.y)
    }
    band.close()
    ridge.close()
    gloss.close()
    drawPath(band, Color.White, alpha = alpha * PROP_MASK)
    drawPath(band, color, alpha = alpha * 0.92f)
    drawPath(ridge, Color.White, alpha = alpha * 1.25f)
    drawPath(gloss, Color.White, alpha = alpha * 0.70f)

    // 鳞：横跨管径的短弧，自由边朝尾（控制点沿切向反着推），所以是叠着的。
    // 细的那一端不排鳞（`t < 0.20`，正是缺口右沿那一小截）：按角度均分的话，
    // 到了细端每一片都跟管径差不多长，读作一串挂在环上的白刺
    val scales = Path()
    val pieces = 11
    for (k in 1..pieces) {
        val t = k / (pieces + 1f)
        if (t < 0.20f) continue
        val a = gap + t * span
        val hw = halfAt(t)
        val o = at(t, 0.88f)
        val n = at(t, -0.88f)
        scales.moveTo(o.x, o.y)
        scales.quadraticTo(
            (o.x + n.x) * 0.5f - cos(a) * hw * 0.55f,
            (o.y + n.y) * 0.5f - sin(a) * hw * 0.55f,
            n.x, n.y
        )
    }
    // 鳞用主色加深（不是白）：卡片上道具的有效 alpha 只有 0.32（`PROP_ALPHA`），
    // 环身合出来是一层浅灰，压在上面的白线跟它只差一档，缩到 27dp 就什么也看不见了。
    // 与盘蛇身上那套鳞同一个写法 —— 浅底上「可见」等于色多
    drawPath(scales, color, alpha = alpha * 1.35f, style = Stroke(width = radius * 0.017f))

    // ── 头 ──
    // 颈在环顶、切向朝右（顺时针），而缺口那道弦从颈往右下斜 `gap / 2` ——
    // 头沿着弦长出去，头尖才落在尾尖上
    val hx = center.x
    val hy = center.y - radius
    val tilt = gap * 0.5f * (180f / PI.toFloat())
    rotate(degrees = tilt, pivot = Offset(hx, hy)) {
        val hl = 2f * radius * sin(gap * 0.5f)
        val hw = radius * 0.22f
        fun fx(a: Float) = hx + a * hl
        fun fy(b: Float) = hy + b * hw
        // 张口 0.18 固定，口正好夹住尾尖那一段（0.15r 高）。这是**咬住**的那一帧 ——
        // 盘蛇那颗头张到 0.62 是攻击姿态，戒指这枚缩到五分之一，大张的颌在这个尺寸上
        // 只读作一块多出来的肉。卡片是定格的一张图（低配机上相位恒为 0），
        // 开合动画在这里本来也看不见
        val gape = 0.18f
        val hx0 = fx(0.20f)
        val hy0 = fy(0.26f)
        val jux = cos(gape)
        val juy = sin(gape)
        fun jx(a: Float, b: Float) = hx0 + jux * a * hl - juy * b * hw
        fun jy(a: Float, b: Float) = hy0 + juy * a * hl + jux * b * hw

        // 口腔：上下颌之间那一块。比环上任何一处都重 —— 那是个洞
        val mouth = Path()
        mouth.moveTo(hx0, hy0)
        mouth.lineTo(fx(1.00f), fy(0.06f))
        mouth.lineTo(jx(0.97f, -0.06f), jy(0.97f, -0.06f))
        mouth.close()
        drawPath(mouth, Color.White, alpha = alpha * PROP_MASK)
        drawPath(mouth, color, alpha = alpha * 2.1f)

        // 下颌：铰在 (0.20, 0.26)，舌尖朝前收
        val jaw = Path()
        jaw.moveTo(jx(-0.10f, -0.06f), jy(-0.10f, -0.06f))
        jaw.cubicTo(
            jx(0.44f, 0.04f), jy(0.44f, 0.04f),
            jx(0.78f, 0.06f), jy(0.78f, 0.06f),
            jx(0.98f, 0.00f), jy(0.98f, 0.00f)
        )
        jaw.cubicTo(
            jx(0.84f, 0.34f), jy(0.84f, 0.34f),
            jx(0.40f, 0.48f), jy(0.40f, 0.48f),
            jx(-0.10f, 0.52f), jy(-0.10f, 0.52f)
        )
        jaw.close()
        drawPath(jaw, Color.White, alpha = alpha * PROP_MASK)
        drawPath(jaw, color, alpha = alpha * 1.10f)

        // 上颅：颈背 → 眉脊隆起 → 吻背下坡 → 吻端 → 上唇线收回颈
        val skull = Path()
        skull.moveTo(fx(-0.12f), fy(-0.82f))
        skull.cubicTo(
            fx(0.14f), fy(-1.08f),
            fx(0.48f), fy(-0.98f),
            fx(0.78f), fy(-0.54f)
        )
        skull.cubicTo(
            fx(0.94f), fy(-0.34f),
            fx(1.02f), fy(-0.12f),
            fx(1.00f), fy(0.06f)
        )
        skull.cubicTo(
            fx(0.80f), fy(0.22f),
            fx(0.42f), fy(0.26f),
            fx(-0.12f), fy(0.18f)
        )
        skull.close()
        drawPath(skull, Color.White, alpha = alpha * PROP_MASK)
        drawPath(skull, color, alpha = alpha * 1.20f)

        // 毒牙：两颗，从上颌垂进口腔
        val fangs = Path()
        repeat(2) { k ->
            val a = 0.86f - k * 0.12f
            fangs.moveTo(fx(a), fy(0.10f))
            fangs.lineTo(fx(a - 0.06f), fy(0.42f))
        }
        drawPath(
            fangs, Color.White,
            alpha = alpha * 2.4f,
            style = Stroke(width = radius * 0.026f, cap = StrokeCap.Round)
        )

        // 眉脊：压在眼上方的一道骨棱，蝮蛇的「凶」全在这一条
        val brow = Path()
        brow.moveTo(fx(0.14f), fy(-0.80f))
        brow.quadraticTo(fx(0.42f), fy(-1.00f), fx(0.66f), fy(-0.62f))
        drawPath(
            brow, color,
            alpha = alpha * 2.2f,
            style = Stroke(width = radius * 0.024f, cap = StrokeCap.Round)
        )

        // 眼：金瞳 + 竖梭形瞳孔（两段二次曲线拼的，画椭圆的话跟着头转会横过来）
        val eyeA = 0.40f
        val eyeB = -0.34f
        val eyeR = radius * 0.10f
        drawCircle(PROP_GOLD, eyeR, Offset(fx(eyeA), fy(eyeB)), alpha = alpha * 2.6f)
        val pupil = Path()
        pupil.moveTo(fx(eyeA), fy(eyeB) - eyeR * 0.88f)
        pupil.quadraticTo(
            fx(eyeA + 0.032f), fy(eyeB),
            fx(eyeA), fy(eyeB) + eyeR * 0.88f
        )
        pupil.quadraticTo(
            fx(eyeA - 0.032f), fy(eyeB),
            fx(eyeA), fy(eyeB) - eyeR * 0.88f
        )
        drawPath(pupil, color, alpha = alpha * 2.6f)

        // 鼻孔
        drawCircle(color, radius * 0.028f, Offset(fx(0.88f), fy(-0.26f)), alpha = alpha * 2.0f)
    }
}

/**
 * 7 · Lover：一把上了弦的弓 + 一只蝴蝶。
 *
 * 原来这里是两只互锁的彩纸环（*Paper Rings* 是 Lover 的第 8 首）。换成弓是需求方定的：
 * 这一张真正要发生的事是「箭射中背景彩虹上那颗心」，而弓是那件事的起点 ——
 * 起点不该藏在 12dp 的曲目行末尾（上一版），它得在道具位上，有一百多 dp 见方
 * 才画得出弓臂的收势、握把的缠绳与三片尾羽。几何与时间线都在 `SwiftieLoverArcher` 里。
 *
 * 小屋没有搬进来：它在背景那一层（`PASTEL_RAINBOW_HOUSE` 的彩虹 + 大屋），终局的雪景球里
 * 还有一栋，卡片再画一栋就是同一件道具同屏三遍 —— 用户对 Red 那条围巾提过同样的问题。
 */
private fun DrawScope.drawLoverArcher(
    color: Color,
    phase: Float,
    alpha: Float,
    eraElapsedMs: Long,
    loverAimAngle: Float
) {
    pastelCloudTexture(color, phase)
    val box = propBox()
    drawLoverBow(box, alpha, eraElapsedMs, loverAimAngle)
    // 蝴蝶挪出道具框、飞到卡片右上角：留在框里它就压在弓臂上，两件道具争同一块地方。
    //
    // 竖向锚点用 dp 而不是卡片高度的比例 —— 顶上那条标题带（16dp 内边距 + 34dp 专辑名
    // + 11dp 日期）是 dp 预算（`CARD_CHROME_HEIGHT`），按比例算会让 31 首的 TTPD 与
    // 14 首的 Lover 落在完全不同的地方。48dp 处蝴蝶上下缘约在 26dp..75dp，
    // 正好在标题带里、压不到第一行曲目。
    //
    // 横向 84.5%：专辑名「Lover」5 个字母在 34dp 下约占 93dp，撞不上；右缘还留下
    // 20dp 出头，不会被 20dp 的圆角切掉一只翅膀。尺寸不变，仍按道具列宽算
    drawButterfly(
        color = color,
        phase = phase,
        alpha = alpha,
        center = Offset(size.width * 0.845f, 48.dp.toPx()),
        u = box.width
    )
}

/**
 * 一只蝴蝶：两对翼瓣 + 身子 + 触角。
 *
 * 右半边靠 `scale(-1)` 把左半边镜像过去 —— 手写两遍几何必然有一天只改了一边。
 * 扑翼只压翼展的 x（[flap]），不动 y：蝴蝶扇翅膀时看到的正是投影变窄。
 *
 * @param center 身子的位置（卡片坐标）。收绝对坐标而不是「一个框 + 框内比例」：
 *   这只蝴蝶要落在卡片右上角，而它旁边的弓在右下的道具框里，两者早已不共用一个框
 * @param u 尺度单位，翼展取 `0.30f * u`，笔宽与身子也都从它派生
 */
private fun DrawScope.drawButterfly(
    color: Color,
    phase: Float,
    alpha: Float,
    center: Offset,
    u: Float
) {
    val bx = center.x
    val by = center.y
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

/**
 * 蝴蝶的一半：前翼实心、后翼淡一档、前翼再描一圈亮边（翼脉的意思）。
 *
 * alpha 系数比多数道具高（1.9 / 1.5）：这只蝴蝶搬到卡片右上角之后是那一块唯一的图形，
 * 压在白纸上按 1.5 / 1.2 画只剩一团淡影。上限是 [PROP_MASK]（≈2.23），垫白那一层
 * 就是按它算的。
 */
private fun DrawScope.butterflyHalf(fore: Path, hind: Path, color: Color, alpha: Float, u: Float) {
    drawPath(fore, color, alpha = alpha * 1.9f)
    drawPath(hind, color, alpha = alpha * 1.5f)
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
        // 松枝在**开衫之前**：枝梢探到衣摆底下就停住了（见 [drawPineSprig]），
        // 露出来的那一截正好停在衣摆边缘上，读作「塞在衣服背后」。上一版画在最后，
        // 枝子从衣摆前面横着穿过去 —— 需求方原话「松针挡在衣服前面很突兀」。
        drawPineSprig(color, alpha, w, h, u)
        drawCardigan(color, alpha, w, h, u)
    }
}

/**
 * 开衫上身这两遍色的倍率（相对 [PROP_ALPHA]），衣身与袖子同一套。
 *
 * 一遍 `0.35` 只能把垫白后的 248 拉回 222 —— 与纸同色；两遍到 `0.49` 才是 195。
 * 数字是离线复刻器（`build/egg-shots/fo_card.py`）按真机纸色算出来的，不是拍脑袋。
 */
private const val CARDIGAN_FIRST_LAYER = 1.00f
private const val CARDIGAN_SECOND_LAYER = 0.63f

/**
 * 一件开衫：两片前襟 + V 领 + 一排扣子 + 两只垂下的袖子 + 针织罗纹。
 *
 * 罗纹靠 `clipPath` 卡在衣身里 —— 不裁的话那几十条竖线会糊到椅子和卡片上，
 * 一眼就露出是「画上去的纹理」而不是毛线。
 *
 * ## 衣身为什么要**上两遍色**
 *
 * 垫白（[PROP_MASK]）把衣身顶到 248，而卡片纸只有 222 上下 —— 一遍色最多把 248 拉回
 * 222，**与纸逐像素同色**。真机量出来的正是这个：衣身 Δ0、椅子 Δ-20，于是整件衣服
 * 只剩领口那道折线、一排扣子和几十条罗纹浮在纸上，衣身本身是块空洞（需求方原话
 * 「衣服和卡片融为一体了」）。第二遍把衣身压到 195（Δ-27），比椅子这个背景结构更实，
 * 衣服才成了主体。
 *
 * 两遍合起来的遮盖力 `(1-0.78)(1-0.49) ≈ 0.11`，与原来一遍垫白 `0.22` 同一档，
 * 立柱与顶横档仍旧透不出来；把垫白调薄换成一遍厚色（`0.72×白 + 1.65×色`）也能压到
 * 同样的深，但遮盖力掉到 0.16，椅背会变成一道幽灵线横在衣服上。
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
    drawPath(body, color, alpha = alpha * CARDIGAN_FIRST_LAYER)
    drawPath(body, color, alpha = alpha * CARDIGAN_SECOND_LAYER)
    clipPath(body) {
        val ribs = Path()
        var x = w * 0.18f
        while (x < w * 0.78f) {
            // V 领那个三角形里是**衣服里面**，不该有罗纹：竖纹只长在两片前襟上。
            // 每条竖线改从领口斜边上起头 —— 起头点正好落在领子描边的中线上，
            // 而领子是后画的、又比衣身深，于是竖纹看上去是从领子底下钻出来的。
            // 不这么裁的话，那几十条竖线会一路顶到肩线，领口里糊成一片（需求方
            // 原话「v领所在三角形区域去掉衣服的竖纹，表示竖纹只在衣服外面有」）。
            val lean = abs(x - w * 0.49f)
            val from = if (lean < w * 0.19f) {
                h * 0.40f - h * 0.20f * (lean / (w * 0.19f))
            } else {
                top - h * 0.02f
            }
            ribs.moveTo(x, from)
            ribs.lineTo(x + u * 0.022f, bottom + h * 0.08f)
            x += u * 0.044f
        }
        drawPath(ribs, color, alpha = alpha * 0.5f, style = Stroke(width = u * 0.011f))
    }
    // 袖子排在**领口之前**：袖山（`w 0.38 → 0.31` 那一段肩）压在 V 领两条臂的上半截上，
    // 袖子后画就把领口切掉一段，V 读成「左右各一段斜线」而不是一件开衫的领子
    // （需求方原话「v领被左右两只袖子盖住了」）。真衣服上领贴边本来也在袖山之上。
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
}

/** 一只袖子 + 袖口罗纹。右袖是左袖的镜像，几何只写一遍。 */
private fun DrawScope.cardiganSleeve(sleeve: Path, cuff: Path, color: Color, alpha: Float, u: Float) {
    drawPath(sleeve, Color.White, alpha = alpha * PROP_MASK)
    drawPath(sleeve, color, alpha = alpha * 0.85f)
    drawPath(sleeve, color, alpha = alpha * CARDIGAN_SECOND_LAYER)
    drawPath(cuff, color, alpha = alpha * 1.3f, style = Stroke(width = u * 0.008f))
}

/**
 * 一枝松枝：主枝 + 两侧针叶。
 *
 * 针叶全部合进一条 Path 一次描完，而且**越靠枝梢越短** ——
 * 等长的针叶排出来是一把梳子。
 *
 * 枝梢停在 `(0.30w, 0.76h)` —— 再往上就伸进开衫的衣摆里了。它画在开衫**之前**
 * （见 [drawCardiganChair]），所以探进衣摆的那一小截被衣服盖掉，露出来的枝子正好
 * 收在衣摆边缘上，读作「塞在衣服背后」。上一版梢头到 `(0.34w, 0.70h)` 又画在最后，
 * 整枝从衣摆前面横穿过去，需求方原话「松针挡在衣服前面很突兀」。
 */
private fun DrawScope.drawPineSprig(color: Color, alpha: Float, w: Float, h: Float, u: Float) {
    val fromX = w * 0.04f
    val fromY = h * 0.98f
    val toX = w * 0.30f
    val toY = h * 0.76f
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

/**
 * 9 · evermore：站在那儿的背影 —— 从颅顶编下来的法式辫 + 格纹呢大衣。
 *
 * 画的是**照片抠图**（`era_evermore_back.png`：1254² 原图裁到外框、抹掉抠图彩边、
 * 降到 720 宽），不再用 Canvas 手画。手画那版把辫花试到第七种画法都读不「像」——
 * 道具框只有 413px 宽，头发的「编」与呢子的格纹各占几像素，几何化到最后只能是一堆
 * 带描边的色块；而照片自带全部质感，代价只有一张 0.8MB 的 PNG。
 *
 * 手画稿与对照脚本留在仓库外（`build/egg-shots/ev_back.py` 等），要再改结构时从那儿起。
 */
@Suppress("UNUSED_PARAMETER")
private fun DrawScope.drawBraidPlaid(
    color: Color,
    phase: Float,
    lowRam: Boolean,
    alpha: Float,
    back: ImageBitmap?
) {
    branchTexture(color, phase, lowRam)
    if (back == null) return
    val box = propBox()
    // 按框宽铺满、底边压在框底：**横向不出框**。上一版按「原图铺满一列」缩，
    // 下摆横着溢出一列 100px，屏幕上读作「一个比头大近三倍的裙摆」（需求方原话）。
    val dstW = box.width
    val dstH = dstW * back.height / back.width
    // 呼吸：整张照片极轻微地左右摆一下，卡片才不是一张贴上去的静物
    val sway = sin(phase * TAU) * box.width * 0.012f
    val dstLeft = box.left + sway
    val dstTop = box.bottom - dstH
    drawImage(
        image = back,
        srcOffset = IntOffset.Zero,
        srcSize = IntSize(back.width, back.height),
        dstOffset = IntOffset(dstLeft.roundToInt(), dstTop.roundToInt()),
        dstSize = IntSize(dstW.roundToInt(), dstH.roundToInt()),
        alpha = (alpha * EVERMORE_PHOTO_GAIN).coerceAtMost(1f),
        filterQuality = FilterQuality.High
    )
    // 主色罩**不能画**：照片的透明区是整块矩形，罩上去在卡片上留下一个方框
    // （真机 era9 那一版就带着这个框）。照片自己的暖棕与卡片主色本来就同源。
}

/**
 * 照片的显影强度：有效 alpha = 0.35 × 2.2 ≈ 0.77。
 *
 * [PROP_ALPHA]（0.35）是给线画道具定的 —— 线画道具在纸上本来就是「淡彩」，
 * 而照片整块有色，同值读作「褪色到快没了」。1.5（= 0.525）需求方看过仍嫌淡，
 * 定在 2.2：照片的细节（发丝、格纹）都在，纸还透气；再往上就是一张实心贴纸，
 * 12 个母题里只有它一个实色，整体会跳。
 *
 * 这张卡**不存在压文字的问题**：evermore 的歌名最长 19 字（`LONG_TITLE_CHARS` 是 28），
 * `columnFade` 恒为 1，右列本来也没有文字横穿。
 */
private const val EVERMORE_PHOTO_GAIN = 2.2f

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
