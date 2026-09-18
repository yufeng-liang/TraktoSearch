package com.tracktosearch.ui.screen.swiftie.eras

import android.content.Context
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.core.content.res.ResourcesCompat
import com.tracktosearch.R
import com.tracktosearch.ui.screen.swiftie.SignatureStroke
import com.tracktosearch.ui.screen.swiftie.SwiftieLetterPath
import com.tracktosearch.ui.screen.swiftie.buildSignatureWindows

/**
 * 信纸上那句 `All’s fair in love / and poetry.` 的墨。
 *
 * ## 与签名同一套机器
 *
 * 中线、每点半宽、笔顺、笔速全部来自 `SwiftieLetterPath`（离线脚本
 * `scripts/build-swiftie-letter-path.py` 从 Great Vibes 的字形骨架生成），
 * 铺墨用的是签名那套「沿中线铺圆头圆接的变宽粗线」（[SignatureStroke] / quad / disc）——
 * 两处笔性逐像素一致，因为笔都是同一支。
 *
 * 差别有两处：
 *
 * 1. **这里是两行句子**，不是一行词。行距与居中都是运行时的量（信纸大小随卡片），所以
 *    表里的一切都存在**参考字号的坐标系**里，画的时候只做一次等比缩放。
 * 2. **墨就是字形本身**。签名那十二个字形的墨有 mask 垫着，铺出字形之外看不见；
 *    这里铺出去的每一分都是画在纸上的墨，所以要用字形轮廓 `clipPath` 裁一刀 ——
 *    中线是逐点量的、笔宽是沿法向量量到墨的边缘，两笔交汇处（`A` 的横与竖、`o` 的收口）
 *    射线会一路穿进隔壁那一笔，不裁就在那里鼓出一个包。
 *
 * ## 逐字换轮廓
 *
 * 一个字形写完（该字形的最后一笔收笔）之后，它的墨换成 `getTextPath` 取来的**字形轮廓**
 * 直接填 —— 与铺出来的是同一块墨，但每帧要重建的裁剪路径小一个数量级。
 * 一笔一笔铺的那条路径只留「还没写完的那个字形」那几笔。
 */

/**
 * 参考字号（px）。
 *
 * 表里的 em 数乘上它变成这套中间坐标 —— 与设备无关，于是字形轮廓、中线、时间表都可以
 * 只建一次（字号在运行时要跟着信纸大小变）。
 */
private const val REF_FONT = 100f

/**
 * 整句话的**书写**部分占用的毫秒。抬笔停顿另算（[LETTER_PAUSE_MS]）—— 两者相加才是墨的
 * 总时长（`SwiftieLetterArt.writeEndMs`），按写字进度映射时要按总时长收口。
 *
 * 2026-09-18：2600 → 3900（约 1.5x）。逐行打印和羽毛笔共用 TTPD 卡片新增的
 * 3000ms，不再从定格额外扣时间。
 */
internal const val LETTER_WRITE_MS: Long = 3_900L

/**
 * 抬笔停顿合计，按 `SwiftieLetterPath.PAUSE_WEIGHT` 分给 26 个间隙。
 *
 * 与那台打字机的手速对齐：它 86ms 打一个字符，这里的笔 93ms 写一个字符 ——
 * 同一张卡片上两处「正在写」的速度一样，才不会一处像快进、一处像慢放。
 *
 * 2026-09-18：500 → 750，和 [LETTER_WRITE_MS] 同一比例放慢。
 */
internal const val LETTER_PAUSE_MS: Long = 750L

/**
 * 一句话的墨：字形轮廓 + 分笔的中线 + 时间表。
 *
 * 建成之后与字号无关（一切都存在 [REF_FONT] 的坐标系里），可以跨重组留住。
 */
internal class SwiftieLetterArt(
    /** 每个字形的轮廓（已含行内笔位与行距偏移）。写完的字形直接填它。 */
    val glyphs: List<Path>,
    /** 全部字形的并集。铺出来的墨按它裁一刀。 */
    val outline: Path,
    private val strokes: List<SignatureStroke>,
    /** 每一笔属于哪个字形（[glyphs] 的下标）。 */
    private val glyphOfStroke: IntArray,
    /** 每一笔收笔时该并入的整字轮廓；该字形还有笔没写完就是 null。 */
    private val finishedGlyph: List<Path?>
) {

    val writeEndMs: Long = strokes.maxOfOrNull { it.window.endMs } ?: 0L

    /** 已经整字写完的部分。跨帧留着，只在写完一个字时追加。 */
    private val done = Path()
    private var cached = 0

    /** 每帧复用，省掉一次分配。调用方只读，不留存。 */
    private val scratch = Path()

    /**
     * 已经**整字**写完的墨；null 表示一个字都还没写完。
     *
     * 时间倒回（低配机那一档定格、重组换了时钟）时缓存作废重建。
     */
    fun settledInk(elapsedMs: Long): Path? {
        val whole = wholeGlyphStrokes(elapsedMs)
        if (cached > whole) {
            done.reset()
            cached = 0
        }
        while (cached < whole) {
            finishedGlyph[cached]?.let { done.addPath(it) }
            cached++
        }
        return if (cached == 0) null else done
    }

    /** 正在写的那一笔，外加写完了但整字还没写完的那几笔。null 表示还没落笔。 */
    fun freshInk(elapsedMs: Long): Path? {
        val finished = finishedStrokes(elapsedMs)
        var any = false
        scratch.reset()
        for (index in wholeGlyphStrokes(elapsedMs) until finished) {
            strokes[index].appendTo(scratch, 1f)
            any = true
        }
        val active = strokes.getOrNull(finished)
        if (active != null && elapsedMs >= active.window.startMs) {
            active.appendTo(scratch, progressOf(active, elapsedMs))
            any = true
        }
        return if (any) scratch else null
    }

    /**
     * 笔尖在哪。**一直在**（写完了停在最后一个句点上），因为羽毛笔不能眨眼 ——
     * 抬笔的那几十毫秒里笔应该离纸等着，不是消失。
     */
    fun nib(elapsedMs: Long): Offset {
        val finished = finishedStrokes(elapsedMs)
        val active = strokes.getOrNull(finished)
        if (active != null && elapsedMs >= active.window.startMs) {
            return active.pointAt(progressOf(active, elapsedMs))
        }
        return strokes.getOrNull(finished - 1)?.pointAt(1f) ?: Offset.Zero
    }

    private fun progressOf(stroke: SignatureStroke, elapsedMs: Long): Float {
        val span = (stroke.window.endMs - stroke.window.startMs).coerceAtLeast(1L)
        return ((elapsedMs - stroke.window.startMs).toFloat() / span).coerceIn(0f, 1f)
    }

    private fun finishedStrokes(elapsedMs: Long): Int {
        var count = 0
        while (count < strokes.size && elapsedMs >= strokes[count].window.endMs) count++
        return count
    }

    /** 前多少笔属于「已经整字写完」的字形。 */
    private fun wholeGlyphStrokes(elapsedMs: Long): Int {
        var whole = 0
        for (index in 0 until finishedStrokes(elapsedMs)) {
            if (finishedGlyph[index] != null) whole = index + 1
        }
        return whole
    }
}

/**
 * 建一次就够了：字形轮廓、中线、时间表都不随字号变（它们活在 [REF_FONT] 的坐标系里）。
 *
 * **不用 `getTextPath(text, 0, length, …)` 一次取整串**：字母在行内的位置由离线脚本按
 * PIL 量出来的 advance 定死，整串交给 `Paint` 会带上它自己的字距（Great Vibes 有 kerning），
 * 于是墨与中线差出零点几个像素 —— 铺出来的笔迹会从字形边上漏出去一条。
 */
internal fun buildSwiftieLetterArt(context: Context): SwiftieLetterArt {
    val paint = android.graphics.Paint().apply {
        isAntiAlias = true
        typeface = ResourcesCompat.getFont(context, R.font.era_ttpd_letter)
        textSize = REF_FONT
    }
    // 行距（参考坐标）。字形轮廓与中线加的是同一个数，所以第二行不会错开
    val pitch = SwiftieLetterPath.LINE_PITCH_EM * REF_FONT
    val glyphs = ArrayList<Path>(SwiftieLetterPath.GLYPHS.size)
    for (index in SwiftieLetterPath.GLYPHS.indices) {
        val text = SwiftieLetterPath.GLYPHS[index]
        val line = SwiftieLetterPath.GLYPH_LINE[index]
        val path = android.graphics.Path()
        paint.getTextPath(
            text, 0, text.length,
            SwiftieLetterPath.PEN_X_EM[index] * REF_FONT,
            line * pitch,
            path
        )
        glyphs += path.asComposePath()
    }
    val outline = Path().apply { glyphs.forEach { addPath(it) } }

    val stride = SwiftieLetterPath.STRIDE
    val windows = buildSignatureWindows(
        writeWeights = SwiftieLetterPath.WRITE_WEIGHT,
        pauseWeights = SwiftieLetterPath.PAUSE_WEIGHT,
        writeMs = LETTER_WRITE_MS,
        pauseMs = LETTER_PAUSE_MS
    )
    val glyphOfStroke = IntArray(SwiftieLetterPath.POINTS.size)
    val strokes = SwiftieLetterPath.POINTS.mapIndexed { order, points ->
        val count = points.size / stride
        val glyph = SwiftieLetterPath.GLYPH_ORDINAL[order]
        glyphOfStroke[order] = glyph
        val line = SwiftieLetterPath.GLYPH_LINE[glyph]
        SignatureStroke(
            // 点表是 em、y 向下、基线为 0、x 从**该行的起笔点**算起
            x = FloatArray(count) { points[it * stride] * REF_FONT },
            y = FloatArray(count) { points[it * stride + 1] * REF_FONT + line * pitch },
            halfWidth = FloatArray(count) { points[it * stride + 2] * REF_FONT },
            t = FloatArray(count) { points[it * stride + 3] },
            window = windows[order]
        )
    }
    // 同一个字形的最后一笔才认「整字写完」
    val finishedGlyph = List(glyphOfStroke.size) { order ->
        val glyph = glyphOfStroke[order]
        val last = if (order + 1 < glyphOfStroke.size && glyphOfStroke[order + 1] == glyph) {
            null
        } else {
            glyphs[glyph]
        }
        last
    }
    return SwiftieLetterArt(
        glyphs = glyphs,
        outline = outline,
        strokes = strokes,
        glyphOfStroke = glyphOfStroke,
        finishedGlyph = finishedGlyph
    )
}

/**
 * 把这句话画在纸上。
 *
 * @param left 第 1 行**居中之后**的起笔边（表里 x 的原点）
 * @param firstBaseline 第 1 行的基线 y
 * @param fontSizePx 1 em 等于多少像素。行距、笔宽、行内笔位全部按它等比缩放
 * @return 笔尖在画布上的位置，交给羽毛笔
 */
internal fun DrawScope.drawLetterInk(
    art: SwiftieLetterArt,
    elapsedMs: Long,
    left: Float,
    firstBaseline: Float,
    fontSizePx: Float,
    color: Color,
    alpha: Float
): Offset {
    val scale = fontSizePx / REF_FONT
    withTransform({
        translate(left, firstBaseline)
        scale(scaleX = scale, scaleY = scale, pivot = Offset.Zero)
    }) {
        // 整字写完的：字形轮廓直接填。绕向与铺出来那条带子相反，两者**分别裁**没有
        // NonZero 抵消的问题（同一层里混画时才会互相抵消）
        art.settledInk(elapsedMs)?.let { drawPath(it, color, alpha = alpha) }
        art.freshInk(elapsedMs)?.let { ink ->
            clipPath(art.outline) { drawPath(ink, color, alpha = alpha) }
        }
    }
    val nib = art.nib(elapsedMs)
    return Offset(left + nib.x * scale, firstBaseline + nib.y * scale)
}
