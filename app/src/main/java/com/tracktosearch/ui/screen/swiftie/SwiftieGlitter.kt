package com.tracktosearch.ui.screen.swiftie

import android.graphics.Matrix
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.res.imageResource
import com.tracktosearch.R
import kotlin.math.sin
import kotlin.random.Random

/** 一颗亮点：相对坐标 + 相位 + 基准半径（px）。 */
internal class Sparkle(val x: Float, val y: Float, val phase: Float, val radius: Float)

/** 固定种子，重组不会让亮点跳位。13 是 Taylor 的幸运数。 */
internal fun buildSparkles(count: Int): List<Sparkle> {
    val random = Random(13)
    return List(count) {
        Sparkle(
            x = random.nextFloat(),
            y = random.nextFloat(),
            phase = random.nextFloat(),
            radius = 0.8f + random.nextFloat() * 1.8f
        )
    }
}

/**
 * 程序化箔面：渐变 + 亮点脉动。**只剩落款签名在用**。
 *
 * 出题页的算式已经换成从原图挖出来的闪粉贴图（见 [rememberGlitterBrush]）——
 * 这条渐变在算式那个字号下读出来是一片平粉，跟真闪粉差得远。签名那一段没跟着换：
 * 它是另一个画面、另一段动画，不在这次复刻的范围里。
 */
internal fun DrawScope.drawGlitterBody(time: State<Float>, sparkles: List<Sparkle>) {
    val t = time.value
    // 底层箔面渐变极慢平移；Mirror 让往复无缝，不会在回头那帧跳一下
    val shift = t * size.width
    drawRect(
        brush = Brush.linearGradient(
            colors = listOf(
                SwiftiePalette.GlitterDeep,
                SwiftiePalette.Glitter,
                SwiftiePalette.GlitterLight,
                SwiftiePalette.Glitter,
                SwiftiePalette.GlitterDeep
            ),
            start = Offset(shift - size.width, 0f),
            end = Offset(shift, size.height),
            tileMode = TileMode.Mirror
        )
    )
    val tau = 2f * Math.PI.toFloat()
    sparkles.forEach { sparkle ->
        val pulse = sin((t + sparkle.phase) * tau) * 0.5f + 0.5f
        if (pulse <= 0.05f) return@forEach
        drawCircle(
            color = Color.White,
            radius = sparkle.radius * (0.55f + 0.45f * pulse),
            center = Offset(sparkle.x * size.width, sparkle.y * size.height),
            alpha = pulse * 0.9f
        )
    }
}

/**
 * 闪粉的共用时钟。2600ms 一圈：慢到不刺眼，又看得出在动。
 *
 * 抽出来是为了让海报与签名读同一个相位源 —— 各自起一条无限动画会闪得不同步。
 */
@Composable
internal fun rememberGlitterTime(): State<Float> {
    val transition = rememberInfiniteTransition(label = "swiftieGlitter")
    return transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "glitterPhase"
    )
}

/**
 * 真闪粉的画刷：`swiftie_glitter.webp` **按位置贴上去，不平铺**。
 *
 * 贴板是原海报上整条算式那一块（640×1064，含四周出血），非glitter 的像素已经用邻近的
 * 闪粉补过。这里把它按 [SwiftiePosterInk.GLITTER_PLATE] 映射回算式在海报里的位置 ——
 * 我们的字形和原图是同一套字体、拟合的是同一批实测包围盒，所以**每个数字取到的正是
 * 当初印在这个数字里的闪粉**，连「`100` 的下缘更亮」这种大尺度变化都对得上。
 *
 * 之前试过两版平铺，都在真机上翻车，记下来免得再走一遍：
 *
 * 1. 「取笔画里最大的内切正方形再镜像平铺」：找正方形用的掩膜做过 25px 闭运算，把两个
 *    `0` 的字腔一起填上了，于是最大内切正方形落在**天空**上 —— 每个数字上都横着几道
 *    淡紫竖带。
 * 2. 改成按连通域面积填洞（字腔再窄也填不上）之后正方形只有 42px。42px 的贴图镜像平铺
 *    到 500px 宽的数字上，读出来是壁纸花纹，不是闪粉。
 *
 * 贴板没有重复，所以这两个问题都不存在；`TileMode.Clamp` 保证轻微超出的字形取到边缘那
 * 圈补过的闪粉，而不是透明。
 */
@Composable
internal fun rememberGlitterBrush(poster: Size): Brush {
    val plate = ImageBitmap.imageResource(R.drawable.swiftie_glitter)
    return remember(plate, poster) {
        val box = SwiftiePosterInk.GLITTER_PLATE
        val matrix = Matrix().apply {
            setScale(
                box.width * poster.width / plate.width,
                box.height * poster.height / plate.height
            )
            postTranslate(box.left * poster.width, box.top * poster.height)
        }
        object : ShaderBrush() {
            override fun createShader(size: Size): Shader =
                ImageShader(plate, TileMode.Clamp, TileMode.Clamp).apply {
                    setLocalMatrix(matrix)
                }
        }
    }
}

/**
 * Showgirl 标题闪粉的**颗粒缩放**：贴板按这个比例缩小后再平铺。
 *
 * 1f = 原样 1:1 平铺。0.45f 意味着贴板占的物理尺寸只剩四成半，**单位面积内的颗粒数
 * 涨约五倍** —— 颗粒看起来细到近一半。
 *
 * 需求方 2026-09-25 在真机上连着过了三档：先嫌 1:1 粗（320px 的贴板放进 63px 高的
 * 字形里只够铺几块，每块都大得读成「亮斑」而不是「闪粉」），看过 0.5 之后在
 * 0.5 与 0.35 之间定了 **0.45**。
 *
 * **下限**：别再往下压太多 —— `rememberGlitterPatchBrush` 记着那个坑：把整板压进
 * 一个字形会把 1–3px 的亮粒糊成平粉。0.45 之后贴板仍有约 144px 的有效采样尺寸，
 * 远高于那个坑的位置。
 *
 * 两段标题（橙红与金）**共用这一个值**：同一行上并排，颗粒一大一小会立刻读成
 * 两种材质。
 */
private const val SHOWGIRL_GRAIN_SCALE = 0.45f

/**
 * 一张闪粉贴板按 [SHOWGIRL_GRAIN_SCALE] 缩小后平铺的 Brush。
 *
 * `Matrix.setScale` 在 `ImageShader` 上是**缩小采样窗口**：贴板被当成更小的图，
 * 平铺密度因此上升，颗粒变细。
 */
private fun glitterTiledBrush(plate: ImageBitmap): Brush {
    val matrix = Matrix().apply { setScale(SHOWGIRL_GRAIN_SCALE, SHOWGIRL_GRAIN_SCALE) }
    return object : ShaderBrush() {
        override fun createShader(size: Size): Shader =
            ImageShader(plate, TileMode.Mirror, TileMode.Mirror).apply {
                setLocalMatrix(matrix)
            }
    }
}

/**
 * Showgirl 标题的闪粉：官方封面上那圈橙红闪粉，按**原分辨率**挖出来的贴板
 * （`scripts/build-swiftie-showgirl-glitter.py`，源图在 `docs/previews/swiftie-showgirl/`）。
 *
 * Mirror 平铺、按 [SHOWGIRL_GRAIN_SCALE] 缩细。平铺不缩放字形本身，所以字形越大
 * 取到的颗粒越多，正是闪粉该有的行为。
 *
 * 贴板本身已经是「笔画内部最实心的一块」（脚本按局部方差挑的 —— 皮肤按颜色能骗过橙色
 * 掩膜，按颗粒度骗不过），所以这里不需要再补背景。
 */
@Composable
internal fun rememberShowgirlGlitterBrush(): Brush {
    val plate = ImageBitmap.imageResource(R.drawable.swiftie_showgirl_glitter)
    return remember(plate) { glitterTiledBrush(plate) }
}

/**
 * 续章那句 `: THE ENCORE` 的**金**闪粉。
 *
 * 与 [rememberShowgirlGlitterBrush] 是同一份颗粒：`scripts/build-swiftie-encore-glitter.py`
 * 把橙红贴板的色相旋到 42°（金），**饱和度与明度通道原样不动** —— 于是颗粒结构逐字节相同，
 * 变的只有颜色。这一点是这套做法成立的全部理由：闪粉之所以读作闪粉靠的是明暗颗粒，
 * 不是色相；换色若动了亮度通道，颗粒就糊了。
 *
 * 平铺与缩放策略与橙红那份**共用**（[glitterTiledBrush]），两张贴板的颗粒尺度因此
 * 逐像素一致。
 */
@Composable
internal fun rememberEncoreGlitterBrush(): Brush {
    val plate = ImageBitmap.imageResource(R.drawable.swiftie_encore_glitter)
    return remember(plate) { glitterTiledBrush(plate) }
}

/**
 * 原尺寸取贴板上一小块的画刷：不做任何缩放，只把采样窗口平移到 (offsetX, offsetY)。
 *
 * 给水晶球底座的 `7·3` 用 —— 出题页那条算式走 [rememberGlitterBrush] 是因为字形位置
 * 与贴板一一对应；铭牌这两个数字不在贴板里，把整板压进一个字形会因过度缩小把闪粉
 * 颗粒糊成平粉（贴板 640px 宽压到 50px 字形，1-3px 的亮粒全没了），所以按原分辨率
 * 开窗，两个数字各取一块不同区域避免读出复制感。
 */
@Composable
internal fun rememberGlitterPatchBrush(offsetX: Float, offsetY: Float): Brush {
    val plate = ImageBitmap.imageResource(R.drawable.swiftie_glitter)
    return remember(plate, offsetX, offsetY) {
        val matrix = Matrix().apply { postTranslate(-offsetX, -offsetY) }
        object : ShaderBrush() {
            override fun createShader(size: Size): Shader =
                ImageShader(plate, TileMode.Clamp, TileMode.Clamp).apply {
                    setLocalMatrix(matrix)
                }
        }
    }
}
