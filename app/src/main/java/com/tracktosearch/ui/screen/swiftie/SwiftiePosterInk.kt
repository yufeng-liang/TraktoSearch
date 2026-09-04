package com.tracktosearch.ui.screen.swiftie

import android.content.Context
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.vector.PathParser
import androidx.core.content.res.ResourcesCompat
import com.tracktosearch.R

/**
 * 原海报的版式实测值，以及照着它排字的几何。
 *
 * 所有比例都量自 `docs/previews/swiftie-poster/9x16.jpg`（2160×3840）：色键把宝蓝墨层与
 * 闪粉墨层分离出来，取每一组的连通域包围盒。**不是照着截图目测的**，`=` 那两道横杠在
 * 原图里被横向拉长了 36%，目测排不出来。
 *
 * 重新生成这些数字的办法见 `scripts/build-swiftie-poster-assets.py`（它会打印每个连通域
 * 的框）。
 */
internal object SwiftiePosterInk {

    /** 原图尺寸。下面所有比例都以它为基准。 */
    const val SOURCE_WIDTH = 2160f
    const val SOURCE_HEIGHT = 3840f
    const val ASPECT = SOURCE_WIDTH / SOURCE_HEIGHT

    /** 一组墨迹在原图里的包围盒，四个值都是占宽 / 高的比例。 */
    class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val width: Float get() = right - left
        val height: Float get() = bottom - top
        val centerX: Float get() = (left + right) / 2f
    }

    /** `13` 的位置。出题态的答案槽就落在这里 —— 填的是原图那个数。 */
    val ANSWER = Box(0.52593f, 0.43099f, 0.68519f, 0.55339f)

    /** 那个卷曲的加号。Honey Script 的 `+` 本来就长这样，不是 `&`。 */
    val PLUS = Box(0.24815f, 0.65260f, 0.36481f, 0.71823f)

    val EIGHTY_SEVEN = Box(0.43287f, 0.59661f, 0.72269f, 0.73776f)

    /** 两道横杠，横向被拉长过，所以它是唯一按非等比拟合的一组。 */
    val EQUALS = Box(0.28333f, 0.82109f, 0.40833f, 0.85000f)

    val HUNDRED = Box(0.42917f, 0.76589f, 0.73009f, 0.88958f)

    /** 整条算式的竖向跨度，出题态的位移量按它算。 */
    val EQUATION_TOP = ANSWER.top
    val EQUATION_BOTTOM = HUNDRED.bottom

    /** 整条算式的包围盒，五组取并集。高光颗粒按它铺，铺满整张海报会有大半被字形裁掉。 */
    val EQUATION = Box(
        left = PLUS.left,
        top = EQUATION_TOP,
        right = HUNDRED.right,
        bottom = EQUATION_BOTTOM
    )

    /**
     * 闪粉贴板在原图里的范围：比 [EQUATION] 四周各多 24 个原图像素的出血。
     *
     * **必须与 `scripts/build-swiftie-poster-assets.py` 的 `PLATE_BOX` + `PLATE_MARGIN`
     * 一致。** 贴板是按这个框从原图裁出来的，这里再按同一个框贴回去；两边不一致就等于把
     * 闪粉整体挪出了字形。出血本身是给拟合误差留的余量 —— 我们的字形是同一套字体拟合同
     * 一批实测框，但吻合到百分之几，不是逐像素。
     */
    val GLITTER_PLATE = Box(
        left = EQUATION.left - PLATE_MARGIN / SOURCE_WIDTH,
        top = EQUATION.top - PLATE_MARGIN / SOURCE_HEIGHT,
        right = EQUATION.right + PLATE_MARGIN / SOURCE_WIDTH,
        bottom = EQUATION.bottom + PLATE_MARGIN / SOURCE_HEIGHT
    )

    /** 探路字号。先按它取一次轮廓，再把包围盒缩放到目标框。 */
    private const val PROBE_TEXT_SIZE = 200f

    /** 闪粉贴板的出血，单位是原图像素。见 [GLITTER_PLATE]。 */
    private const val PLATE_MARGIN = 24f

    private fun paint(context: Context): Paint = Paint().apply {
        isAntiAlias = true
        typeface = ResourcesCompat.getFont(context, R.font.swiftie_honey)
        textSize = PROBE_TEXT_SIZE
    }

    /** 取 [text] 的墨迹轮廓（基线在 y = 0）与它的包围盒。 */
    private fun outline(paint: Paint, text: String): Pair<android.graphics.Path, RectF> {
        val path = android.graphics.Path()
        paint.getTextPath(text, 0, text.length, 0f, 0f, path)
        val bounds = RectF()
        path.computeBounds(bounds, true)
        return path to bounds
    }

    /**
     * 把 [text] 的墨迹**非等比**拉进 [box]。只有 `=` 用它。
     *
     * @param size 海报在屏幕上的尺寸（px）
     */
    fun stretched(context: Context, text: String, box: Box, size: Size): Path {
        val paint = paint(context)
        val (path, bounds) = outline(paint, text)
        if (bounds.width() <= 0f || bounds.height() <= 0f) return Path()
        val matrix = Matrix().apply {
            postTranslate(-bounds.left, -bounds.top)
            postScale(
                box.width * size.width / bounds.width(),
                box.height * size.height / bounds.height()
            )
            postTranslate(box.left * size.width, box.top * size.height)
        }
        path.transform(matrix)
        // 字体轮廓必须走 NonZero（android.graphics.Path 的默认值）：字形之间会互相压叠
        // （原图的 8 与 7 就叠着），EvenOdd 会把叠在一起的地方挖成洞。字腔靠轮廓绕向
        // 反转来表达，NonZero 一样挖得出来。描原图的手写体则相反，见 [scriptLine]
        return path.asComposePath()
    }

    /**
     * 把 [text] 的墨迹**等比**放进 [box]：高度对齐，横向按框心居中。
     *
     * 用高度而不是宽度定标：实测原图各组的横纵缩放差在 2–4% 以内（作者是等比排的），
     * 按高度取一个再居中，位置误差不到 1%，而按宽度取会让 `87` 与 `100` 的字高错开。
     */
    fun uniform(context: Context, text: String, box: Box, size: Size): Path {
        val paint = paint(context)
        val (path, bounds) = outline(paint, text)
        if (bounds.width() <= 0f || bounds.height() <= 0f) return Path()
        val scale = box.height * size.height / bounds.height()
        val matrix = Matrix().apply {
            postTranslate(-bounds.left, -bounds.top)
            postScale(scale, scale)
            postTranslate(
                box.centerX * size.width - bounds.width() * scale / 2f,
                box.top * size.height
            )
        }
        path.transform(matrix)
        return path.asComposePath()
    }

    /**
     * 答案槽的度量，全部从 `13` 那一组反推。
     *
     * 输入几位数都用同一个 [textSize] 与同一条 [baselineY]，横向按 [centerX] 居中 ——
     * 所以 `X` → `1` → `13` 三步之间字不会变大变小、也不会左右跳。
     */
    class SlotMetrics(val textSize: Float, val baselineY: Float, val centerX: Float)

    fun slotMetrics(context: Context, size: Size): SlotMetrics {
        val paint = paint(context)
        val (_, bounds) = outline(paint, SwiftieEggController.ANSWER.toString())
        val scale = if (bounds.height() > 0f) {
            ANSWER.height * size.height / bounds.height()
        } else {
            1f
        }
        return SlotMetrics(
            textSize = PROBE_TEXT_SIZE * scale,
            // 轮廓的基线在 y = 0，墨迹上沿是负的 bounds.top，所以基线要往下让出这一截
            baselineY = ANSWER.top * size.height - bounds.top * scale,
            centerX = ANSWER.centerX * size.width
        )
    }

    /** 按 [metrics] 排一段答案槽文字。空串返回空路径。 */
    fun slotPath(context: Context, text: String, metrics: SlotMetrics): Path {
        if (text.isEmpty()) return Path()
        val paint = paint(context).apply { textSize = metrics.textSize }
        val (path, bounds) = outline(paint, text)
        path.offset(metrics.centerX - (bounds.left + bounds.right) / 2f, metrics.baselineY)
        return path.asComposePath()
    }

    /**
     * 把 [SwiftieCongratsPath] 的一行缩放到海报坐标系。
     *
     * 路径自带的坐标空间是墨层包围盒，`POSTER_*_FRACTION` 说明这个盒子在原图里的位置，
     * 所以这里只是一次比例换算，没有任何目测的偏移量。
     *
     * 这条路径走 **EvenOdd**，和字体轮廓相反：它的轮廓是 OpenCV 从位图描出来的，绕向
     * 不遵循字体那套约定；而每一行内部的各个连通域互不接触（是连通域，定义上就不接触），
     * 所以不存在叠加区，EvenOdd 只会把字腔挖成洞 —— 正是要的效果。
     */
    fun scriptLine(index: Int, size: Size): Path {
        val path = PathParser().parsePathString(SwiftieCongratsPath.LINES[index]).toPath()
        val scaleX = SwiftieCongratsPath.POSTER_WIDTH_FRACTION * size.width /
            SwiftieCongratsPath.VIEWPORT_WIDTH
        val scaleY = SwiftieCongratsPath.POSTER_HEIGHT_FRACTION * size.height /
            SwiftieCongratsPath.VIEWPORT_HEIGHT
        // asAndroidPath() 拿到的是同一条底层路径，transform 就地生效
        path.asAndroidPath().transform(
            Matrix().apply {
                postScale(scaleX, scaleY)
                postTranslate(
                    SwiftieCongratsPath.POSTER_LEFT_FRACTION * size.width,
                    SwiftieCongratsPath.POSTER_TOP_FRACTION * size.height
                )
            }
        )
        path.fillType = PathFillType.EvenOdd
        return path
    }

    /** [scriptLine] 的横向揭示范围，单位与它返回的路径一致。 */
    fun scriptLineXRange(index: Int, size: Size): ClosedFloatingPointRange<Float> {
        val range = SwiftieCongratsPath.LINE_X_RANGES[index]
        val scaleX = SwiftieCongratsPath.POSTER_WIDTH_FRACTION * size.width /
            SwiftieCongratsPath.VIEWPORT_WIDTH
        val left = SwiftieCongratsPath.POSTER_LEFT_FRACTION * size.width
        return (left + range.start * scaleX)..(left + range.endInclusive * scaleX)
    }
}
