package com.tracktosearch.ui.screen.swiftie.eras

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotateRad
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import kotlin.math.atan2
import kotlin.math.sin

/**
 * `1989` 的专辑名：照着封面上那四笔**马克笔墨迹**画出来，不走字体。
 *
 * 1989 的封面标题不是排版，是用一支很宽的马克笔在宝丽来上直接写的四个数字：笔尖宽到
 * 接近字高的五分之一、收笔是钝的圆头、笔画中间的墨是实的而边缘与两端**发干发花**。
 * 任何字体都给不出最后这一条 —— 字体的笔画是实心的，而这四个字的辨识特征恰恰是干笔的
 * 空隙。所以这里直接画：先按中线描出四个字的粗笔画，再往墨里**挖掉**一批小斑。
 *
 * 挖而不是叠：干笔处露出来的是卡片自己的底色。如果改成在墨上叠一层浅色斑，就得知道
 * 底色是什么（卡片底、专辑主色、深色模式各不相同），而且叠出来是「墨上有霜」而不是
 * 「墨没盖住纸」。所以整块画进一个离屏层（[CompositingStrategy.Offscreen]），
 * 干斑用 [BlendMode.DstOut] 打洞。
 *
 * 四个字都有自己的 `seed`：手写的两个 `9` 不会一模一样，圆的胖瘦、竖的弯度、干斑的
 * 位置都得差一点。同一套参数画两遍是印刷，不是手写。
 */
@Composable
internal fun SwiftieMarker1989(color: Color, capHeight: Dp, modifier: Modifier = Modifier) {
    Canvas(
        modifier = modifier
            .height(capHeight * MARKER_LINE_RATIO)
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    ) {
        val cap = capHeight.toPx()
        val stroke = cap * 0.178f
        // 0.10·cap：字身 0.035..0.975 加上圆头收笔的半个笔宽，正好落在 1.22·cap 的画布里
        val top = cap * 0.100f
        val gap = cap * 0.080f
        val oneW = cap * 0.25f
        val digitW = cap * 0.585f

        var x = cap * 0.03f
        val strokes = mutableListOf<Path>()
        strokes += markerOne(x, top, cap, oneW)
        x += oneW + gap
        strokes += markerNine(x, top, cap, digitW, seed = 0.7f)
        x += digitW + gap
        strokes += markerEight(x, top, cap, digitW)
        x += digitW + gap
        strokes += markerNine(x, top, cap, digitW, seed = 2.9f)

        // 墨：先一道宽的淡边（马克笔在纸上会洇一点），再一道窄的实芯。
        // 两道的透明度只差一成、宽度只差一成二 —— 上一版差到 0.72/1.0 与 0.82/1.0，
        // 每一笔外面镶出一道明显的浅边，读作描了轮廓的卡通笔画，不是洇开的墨
        for (path in strokes) {
            drawPath(path, color, alpha = 0.90f, style = Stroke(width = stroke, cap = StrokeCap.Round))
            drawPath(path, color, style = Stroke(width = stroke * 0.88f, cap = StrokeCap.Round))
        }
        for ((index, path) in strokes.withIndex()) {
            drawDryPatches(path, stroke, seed = index * 1.7f + 0.3f)
        }
    }
}

/** 标题行高与字高的比：给圆头收笔与干斑留出上下各一点余量。 */
internal const val MARKER_LINE_RATIO = 1.22f

/**
 * 干笔的斑：沿笔画中线取样，挑一部分位置挖掉一小块。
 *
 * 斑是**顺着笔画方向的长条**（宽是长的三分之一），因为干笔是笔尖擦过去留下的条状空隙，
 * 不是圆点。位置用两个互质频率的正弦取，一个定要不要挖、一个定横向偏移 —— 同一个频率
 * 两用会挖出一串等距的洞，那是虚线不是干笔。
 *
 * 两端各多挖两下：起笔落笔的墨最少，封面上四个字的头尾都是花的。
 */
private fun DrawScope.drawDryPatches(path: Path, stroke: Float, seed: Float) {
    val measure = PathMeasure()
    measure.setPath(path, false)
    val total = measure.length
    if (total <= 0f) return
    // 斑要**细**：长 0.3–0.7 个笔宽、厚不到 0.2 个笔宽，而且只挑三分之一的取样点挖。
    // 上一版一个斑长到 1.28 个笔宽、厚到 0.46 个笔宽，还挖掉三分之二的取样点 ——
    // 那不是干笔，那是奶酪上的洞
    val pitch = stroke * 0.70f
    val count = (total / pitch).toInt().coerceAtLeast(3)
    for (i in 0..count) {
        val d = total * i / count
        val pick = sin(i * 2.31f + seed)
        val end = i <= 1 || i >= count - 1
        if (!end && pick < 0.52f) continue
        val position = measure.getPosition(d)
        val tangent = measure.getTangent(d)
        val angle = atan2(tangent.y, tangent.x)
        // 干斑偏在笔画的**同一侧**（`bias`），而且分两种：细长的道（`streak`）与小点。
        // 均匀撒在中线两侧、大小又都差不多的话，读作有规律的斑点花纹；
        // 马克笔缺墨是笔尖某一边先离纸，所以干的是一整条边
        val bias = if (seed.toInt() % 2 == 0) 0.34f else -0.34f
        val across = (bias + sin(i * 5.77f + seed * 1.9f) * 0.24f) * stroke
        val streak = sin(i * 1.13f + seed * 2.7f) > 0f
        val long = stroke * when {
            end -> 0.20f
            streak -> 0.32f + 0.28f * pick
            else -> 0.11f + 0.09f * pick
        }
        val thick = stroke * (if (end) 0.11f else 0.040f + 0.040f * pick)
        rotateRad(angle, pivot = position) {
            drawOval(
                color = Color.Black,
                topLeft = Offset(position.x - long, position.y + across - thick),
                size = Size(long * 2f, thick * 2f),
                blendMode = BlendMode.DstOut
            )
        }
    }
}

/** 「1」：一根竖杠，往下微微收左。封面上这一笔没有起笔的小旗，就是一道。 */
private fun markerOne(x: Float, top: Float, cap: Float, w: Float): Path {
    val path = Path()
    path.moveTo(x + w * 0.66f, top + cap * 0.035f)
    path.quadraticTo(x + w * 0.56f, top + cap * 0.50f, x + w * 0.40f, top + cap * 0.975f)
    return path
}

/**
 * 「9」：一个圆圈 + 一根从圆右侧垂下来的竖。
 *
 * 圆画成**闭合的环**而不是留缺口的弧：留缺口那一版在 34dp 上只有几个像素，
 * 读作圆没画完；干笔的斑本来就会把环啃出缺口，交代「一笔写的」够了。
 */
private fun markerNine(x: Float, top: Float, cap: Float, w: Float, seed: Float): List<Path> {
    val jitter = sin(seed) * 0.022f
    val rx = w * (0.355f + jitter)
    val ry = cap * (0.232f - jitter)
    val bowlCx = x + w * 0.44f
    val bowlCy = top + cap * 0.268f
    val bowl = Path()
    bowl.addOval(Rect(bowlCx - rx, bowlCy - ry, bowlCx + rx, bowlCy + ry))
    val stem = Path()
    stem.moveTo(bowlCx + rx, bowlCy)
    stem.quadraticTo(
        x + w * (0.84f + jitter), top + cap * 0.66f,
        x + w * (0.70f - jitter * 2f), top + cap * 0.975f
    )
    return listOf(bowl, stem)
}

/** 「8」：上小下大两个环。上环小、下环大且往右偏一点，跟封面一样重心在下。 */
private fun markerEight(x: Float, top: Float, cap: Float, w: Float): List<Path> {
    val upper = Path()
    val upperCx = x + w * 0.445f
    val upperCy = top + cap * 0.232f
    val upperRx = w * 0.295f
    val upperRy = cap * 0.196f
    upper.addOval(Rect(upperCx - upperRx, upperCy - upperRy, upperCx + upperRx, upperCy + upperRy))
    val lower = Path()
    val lowerCx = x + w * 0.505f
    val lowerCy = top + cap * 0.738f
    val lowerRx = w * 0.375f
    val lowerRy = cap * 0.240f
    lower.addOval(Rect(lowerCx - lowerRx, lowerCy - lowerRy, lowerCx + lowerRx, lowerCy + lowerRy))
    return listOf(upper, lower)
}
