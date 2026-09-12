package com.tracktosearch.ui.screen.swiftie.eras

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotateRad
import com.tracktosearch.ui.screen.swiftie.unitHeartPath
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.sin

/*
 * Lover 那一箭：卡片右侧道具位上的一把弓，射向**页面背景彩虹上那颗心**。
 *
 * ## 为什么跨了三层
 *
 * 弓画在卡片的道具层（`drawLoverArcher`），箭在飞的那 900ms 画在**页面最上层**
 * （`SwiftieLoverArrowFlight`），插住之后的那支箭画在**背景层**（`drawPastelRainbowHouse`）。
 * 三层各画一段是被层级逼出来的：
 *
 * - 弓在卡片里，卡片压在背景之上；
 * - 心在背景里，背景压在卡片之下 —— 箭要从卡片里飞出去落到背景上，途中必须压在卡片上面，
 *   所以飞行段只能画在比卡片更高的层；
 * - 而「箭穿过心」要的是**一部分在心后面、一部分在心前面**，那就只能与心同层画。
 *
 * 三段共用同一套几何（swiftieLoverArrowLength / swiftieLoverStuckTip / drawArcheryArrow），
 * 所以交接的那两帧位置、角度、长度都对得上，看不出换了手。
 *
 * ## 为什么不是原来那颗行内小爱心
 *
 * 上一版把弓和心都塞进曲目行的行尾（12dp 见方）。那个尺寸画不出弓臂的收势、更画不出
 * 尾羽与箭头的区别 —— 截图里两头都是一个小 V，分不清哪头是尖。搬到道具位之后有一百多
 * dp 见方，弓臂、握把缠绳、弦垫、三片尾羽都放得下。
 */

/** 拉弓开始。Lover 15 行曲目在 `400 + 130 × 15 = 2350ms` 点完，拉弓压在它后面起。 */
internal const val LOVER_DRAW_START_MS: Long = 2_400L

/** 拉满用 620ms。再快就读作弦被拽了一下，不是拉弓。 */
private const val LOVER_DRAW_MS: Float = 620f

/** 撒放时刻。 */
internal const val LOVER_SHOT_MS: Long = LOVER_DRAW_START_MS + 620L

/**
 * 飞行 900ms。
 *
 * 这一段要跨过大半个屏幕（卡片右下到屏幕上方约 450dp），900ms 折合约 500dp/s ——
 * 真箭当然比这快得多，但屏幕上快过 800dp/s 就只剩一道残影，看不出是箭。
 */
internal const val LOVER_FLIGHT_MS: Long = 900L

/** 命中时刻。背景那颗心从这一刻起是**带箭的**。 */
internal const val LOVER_HIT_MS: Long = LOVER_SHOT_MS + LOVER_FLIGHT_MS

/** 命中之后心抖那一下的时长。 */
internal const val LOVER_RECOIL_MS: Float = 420f

/** 撒放之后弓弦的余震。 */
private const val LOVER_TWANG_MS: Float = 320f

// ─────────────────────── 几何：三层共用 ───────────────────────

/** 弦耳（弓臂末端）离中线的距离，占道具框宽。 */
private const val BOW_EAR_X = 0.44f

/**
 * 弦耳的高度。
 *
 * 松弦时弦就是这两点之间的**一条横线** —— 需求方那句「除了圆弧还有那一条横线才是弓箭」
 * 说的正是它：只有圆弧的是篮子提手，加上这条弦才是弓。所以弦的颜色不能是象牙白
 * （见 [BOW_STRING]），它压在白卡片上必须读得出来。
 */
private const val BOW_EAR_Y = 0.68f

/**
 * 弓背（冠）的高度。
 *
 * 拱高 `0.68 − 0.54 = 0.14` 个框高（框高是框宽的 1.55 倍，折算成 0.217 个框宽），
 * 弦长 `2 × 0.44 = 0.88` 个框宽，**拱高比弦长 ≈ 0.25**。参考图上量出来是 0.28
 * （两颗球之间 685px，弓臂最外点到弦的垂距 193px）。
 *
 * 这个数改过三轮，每一轮都栽在同一件事上 —— [setLimb] 那条曲线的形状是**归一化**的
 * （x 以半弦长为 1、y 以拱高为 1），所以拱越高、同一条曲线的实际斜率就被放大得越多：
 * - 0.26（比值 0.73）：马蹄铁；
 * - 0.38（0.42）与 0.42（0.35）：中段实际斜率被放大到 1.0 上下，两条臂读作两根斜杠，
 *   合起来是个**鸡心领**。
 * 0.14 之后中段的实际斜率约 0.69（35°），与参考图的 31° 同一档。
 *
 * 拱高只剩 0.217 个框宽、弓臂本身厚 0.088 个框宽 —— 看着薄，但参考图正是这个比例
 * （拱高 193px、臂厚 85px，2.3 倍），弓本来就是「一根厚棒被绷成一张浅弧」。
 *
 * 整把弓在框里的高度（冠 0.54 → 弦 0.68 → 拉满 0.86）也一起下移过：早先落在
 * 0.42/0.62/0.80，弦下面空出三分之一个框，松弦那几秒读作弓浮在半空。
 */
private const val BOW_APEX_Y = 0.54f

/** 拉满时扣弦点的高度。 */
private const val BOW_FULL_DRAW_Y = 0.86f

/**
 * 箭长占道具框宽的比例。
 *
 * 这个数是「射出去之后看不看得见穿心」定下来的，不是从弓上量出来的 —— 两头夹：
 *
 * - 短了穿不出去。0.72 那一版：命中的箭只探过心中心 0.10 个箭长（约 27px），
 *   而心形箭镞自身就有 0.20 个箭长，于是整颗镞都叠在心的正面 —— 真机截图上
 *   读作**一颗小粉心贴在大粉心上**，没有穿刺。
 * - 长了整支箭在画面上拖得太远：0.95 那一版尾羽垂到曲目第 13 行上，需求方判「箭还是太长」。
 *
 * 0.62 是最后定下的档：真机约 190px、心宽约 127px。再长一点点（0.75 试过）尾羽就拖到
 * 心的下方读作「箭太长了」；再短（<0.55）箭镞自身的尺寸跟着缩，穿心的读数又不够。
 */
private const val ARROW_LEN = 0.62f

/**
 * 插住之后箭尖停在心中心之外多少个**箭长**（沿瞄准方向）。
 *
 * 这个值决定箭镞露不露得出来，以及**尾羽落在哪里**。三个实测锚点（真机屏上）：
 * 心中心到心的下缘约 73px、镞心到箭尖 0.101L、镞自身高 0.201L。
 *
 * 约束是三头夹的：① 镞心要落到心的下缘之外（≥ (73 + 0.101L)/L）；② 尾羽要收在心
 * 下缘那一带、不能垂进卡片文字（尾端 = 心中心 − L(1−插深) ≥ 心下缘 − 约 30px）；
 * ③ 箭要短。①②联立可得箭长上限 ≈ 心高的 0.9 倍，于是箭长定在 0.62（真机约 190px）、
 * 插深 0.36：镞身露到心外约 30px，尾羽梢停在下缘下方一点。
 *
 * 0.10 时镞心还在心面正中（整颗镞叠在心的正面，真机截图确认这就是「贴在爱心上」）；
 * 0.48（0.75 箭长）时镞露得够，但尾羽收得太高、陷进心的下半张脸，整支箭读作插在心的正面。
 */
private const val STUCK_DEPTH = 0.36f

/**
 * 测量尚未完成时的保守兜底方向。
 *
 * 正常绘制不会使用它：卡片布局回报根坐标后，方向由「真实扣弦点 → 彩虹爱心」计算。
 * 保留一个稳定的斜向兜底，是为了避免首帧在等待布局时突然出现一根完全竖直的箭。
 */
internal val LOVER_FALLBACK_AIM_UNIT: Offset = Offset(
    -cos(35f * PI.toFloat() / 180f),
    -sin(35f * PI.toFloat() / 180f)
)

internal val LOVER_FALLBACK_AIM_ANGLE: Float =
    atan2(LOVER_FALLBACK_AIM_UNIT.y, LOVER_FALLBACK_AIM_UNIT.x)

/*
 * 配色与形制：**按 `build/refs/vecteezy.png` 那张 3D 丘比特弓复刻**。
 *
 * 那一族图（Lover 一整个时代的周边、The Archer 的视觉、所有画丘比特的商用插画）共用
 * 一套：金弓 + 白杆 + 心形箭镞 + 粉羽扇。其中有五条是「一眼是丘比特的弓」的全部内容，
 * 缺一条就退回成猎弓甚至篮子提手：
 *
 * 1. 弓臂**每一段的弧度都不一样**：中段是一段近乎平的冠，往外是一段长而缓的腹，
 *    再折下去，最后那一小截**向内反勾**着收进球里。一整条同曲率的弧就是彩虹。
 * 2. 弓臂**粗细也在变**：腹最粗、球前那截颈最细（约腹的三分之一）。
 * 3. 两个臂梢各顶一颗**球**。
 * 4. 弦是**一条直线**（拉开后是个三角形），且弦与臂梢的接点处有一小道**缠结**。
 * 5. 箭：**白杆 + 心形的粉镞 + 粉色的羽扇**，羽扇是一把**圆头的羽片**、各自一个后掠角。
 *
 * 金里分三档（本体 / 受光的上棱 / 暗边），少一档就是一块黄色色块。
 */

/** 箭杆：纯白。参考图里这根杆是白的，压在白卡片上靠 [ARROW_OUTLINE] 那圈描边立住。 */
private val ARROW_SHAFT = Color(0xFFFFFFFF)

/**
 * 箭杆的描边：中性灰。
 *
 * 上一版是暖褐（`#B39A7A`），杆整体偏象牙 —— 参考图里那根杆是**白的、底下一道灰影**，
 * 木杆的暖色不属于这一支。灰比参考图里那一档深一点：那张图的底是纯白背景，
 * 而这里杆要压在白卡片上，`#DDD` 那一档在卡片上等于没画。
 */
private val ARROW_OUTLINE = Color(0xFFABA3A0)

/** 箭杆下缘那道灰影。白管子的圆柱感全在这一条上，少了它杆是一根扁白条。 */
private val ARROW_SHAFT_SHADE = Color(0xFFE6DEDA)

/** 箭杆受光的那一条，也是箭尾扣弦槽的亮面。 */
private val ARROW_SHAFT_LIT = Color(0xFFFFFFFF)

/**
 * 心形箭镞：**粉**，复刻参考图。
 *
 * 上一版改成了金，理由是「粉镞压在背景那颗粉心上等于没有镞」—— 那条理由是错的：
 * 插住之后箭尖停在 [swiftieLoverStuckTip]，只在心心沿落点方向外 0.10 个箭长处，
 * 心形镞主体会嵌进心面；粉色镞与心底色接近，所以保留暗边和前景重绘来立住轮廓。
 */
private val ARROW_POINT = Color(0xFFF08FB1)

/** 箭镞的暗边。心形只有实心一层时读作一块糖。 */
private val ARROW_POINT_DEEP = Color(0xFFDE6E97)

/** 羽扇：Lover 的玫红。参考图里羽片与箭镞同一族粉，只差一档明度。 */
private val ARROW_VANE = Color(0xFFF08FB1)

/** 羽片的暗边：每一枚各自描一道，同色实心一片是塑料，分开才是一扇羽毛。 */
private val ARROW_VANE_DEEP = Color(0xFFDE6E97)

/** 羽轴：每一枚羽片中间那条淡色的线。少了它一枚羽片就是一根粉色的棒。 */
private val ARROW_VANE_RIB = Color(0xFFFFE1EC)

/**
 * 弓臂的金。
 *
 * 上一版 `#C79A3E` 偏褐 —— 参考图里那把弓是**琥珀色的糖**，饱和度高得多。
 */
private val BOW_GOLD = Color(0xFFE5A22A)

/** 弓臂的暗边。本体提亮之后，描边必须真的深一档才立得起来。 */
private val BOW_GOLD_DEEP = Color(0xFFB97A12)

/** 弓臂受光的上缘、臂梢那颗球的高光。 */
private val BOW_GOLD_LIT = Color(0xFFFBD97C)


/**
 * 弓弦。
 *
 * 上一版是 `#F3E3D2`（象牙白）—— 卡片是白纸，那条弦等于没画，整把弓在截图里读作
 * 一个篮子提手。这个暖褐是「一根上了蜡的麻弦」的色，压在白纸与粉天空上都看得见。
 */
private val BOW_STRING = Color(0xFF7A5F38)

/**
 * 右侧道具列的框，纯几何版本。
 *
 * `DrawScope.propBox()` 委托给它，[SwiftieLoverArrowFlight] 与背景那颗心也调它 ——
 * 弓在卡片里、箭在最上层、插住的箭在背景里，三处必须从**同一个式子**推出发点，
 * 各写一份「卡片右边留一点」必然在某个屏宽上错开。
 *
 * 高度按**框宽**定（1.55 倍）而不是跟着卡片高度拉满：TTPD 有 31 首、卡片高过 400dp，
 * 跟着拉长会把吉他和打火机抽成竹竿。上缘再夹到卡片 34% 以下，让开标题与日期那一段
 * （`CARD_CHROME_HEIGHT`）与前几行曲目。
 *
 * 右边留 2.5% 而不是贴边：道具被卡片圆角切一刀比留白更显廉价。
 */
internal fun swiftiePropBox(size: Size): Rect {
    val boxWidth = size.width * SWIFTIE_PROP_COLUMN_FRACTION
    val left = size.width * (0.975f - SWIFTIE_PROP_COLUMN_FRACTION)
    val bottom = size.height * 0.94f
    val top = maxOf(size.height * 0.34f, bottom - boxWidth * 1.55f)
    return Rect(left, top, left + boxWidth, bottom)
}

/** 道具列占卡片宽度的比例。 */
internal const val SWIFTIE_PROP_COLUMN_FRACTION = 0.32f

/** 心形箭镞占箭长的比例。参考图里那颗心相当大 —— 小了就退回成普通箭头。 */
private const val ARROW_POINT_FRACTION = 0.17f

/**
 * 反复 `rewind()` 的那一条 Path。
 *
 * 一支箭要画 5 个形状（箭头、两片尾羽、箭尾、弓臂另算），每帧 new 五个 Path 就是
 * 96 秒的 GC 抖动 —— 与 `SwiftieEraMotifs` / `SwiftieEraBackdrop` 同一条铁律。
 * 顶层单例安全：Compose 的 draw 全在主线程上顺序跑，本文件也从不嵌套使用它。
 */
private val ARCHER_SCRATCH = Path()

/**
 * 一支箭：箭尖在 [tip]，杆沿 [angle] 的反方向退 [length]。
 *
 * [from] / [to] 是**沿箭身的可见区间**（0 = 箭尖，1 = 箭尾）。整支画就是 0..1；
 * 背景那颗心画完之后还要拿 0.42..1 再画一遍压在心上面 —— 心挡住中段，靠近尾巴这一段
 * 又压回心的上面，读作「箭从这一面插进去、从那一面穿出来」。少了这一步，箭只是躺在
 * 心背后的一根棍子，没有穿透。
 *
 * 旋转用 `rotateRad` 绕箭尖转画布，所以里面所有坐标都是「箭沿 +x 指」的局部坐标，
 * 不必对每个点各算一遍三角函数。
 */
internal fun DrawScope.drawArcheryArrow(
    tip: Offset,
    angle: Float,
    length: Float,
    alpha: Float,
    from: Float = 0f,
    to: Float = 1f
) {
    if (alpha <= 0.004f || length <= 0f || to <= from) return
    val half = (length * 0.019f).coerceAtLeast(0.8f)
    // 羽毛收短并后移，避免压到弓弦与扣弦点。
    val vaneLen = length * 0.20f
    val vaneH = length * 0.014f
    rotateRad(angle, pivot = tip) {
        val y = tip.y
        val tailX = tip.x - length
        // 杆：描边 → 纯白实心 → 下缘一道灰影。参考图里这根杆是白的、底下带一道灰，
        // 圆柱感全在那道灰上。杆从爱心箭镞的尾端接出，不再穿进爱心内部；
        // 爱心只负责箭头，杆头与它在尾端相接。
        val shaftHead = tip.x - length * swiftieLoverArrowShaftHeadFraction(from)
        val shaftTail = tip.x - length * to
        if (shaftHead > shaftTail) {
            drawLine(
                ARROW_OUTLINE, Offset(shaftTail, y), Offset(shaftHead, y),
                strokeWidth = half * 2.7f, alpha = alpha * 0.9f
            )
            drawLine(
                ARROW_SHAFT, Offset(shaftTail, y), Offset(shaftHead, y),
                strokeWidth = half * 1.8f, alpha = alpha
            )
            drawLine(
                ARROW_SHAFT_SHADE,
                Offset(shaftTail, y + half * 0.52f), Offset(shaftHead, y + half * 0.52f),
                strokeWidth = half * 0.62f, alpha = alpha * 0.95f
            )
        }
        if (to >= 0.82f) drawArrowFletching(tailX, y, vaneLen, vaneH, half, alpha)
    }
    // 心形镞画在**旋转之外**：沿真实箭身方向旋转，与箭杆在尾端相接。
    if (from <= ARROW_POINT_FRACTION) {
        drawArrowHeart(tip, length * ARROW_POINT_FRACTION, angle, alpha)
    }
}

/**
 * 心形镞跟着箭身斜的比例。
 *
 * 0 = 保持正立，1 = 完全沿箭身旋转。使用 1：爱心箭镞与箭身同轴，
 * 尖端始终朝射出方向，并由后绘制的实体轮廓覆盖箭杆前端。
 */
private const val HEART_TILT = 1.00f

/**
 * 心形箭镞：**尖朝外**（尖落在箭尖上、朝箭飞的方向），两瓣与凹口朝里贴着杆。
 *
 * 丘比特那支箭的镞就是一颗心 —— 这是「谁射的」唯一不用画出小天使的说法。
 *
 * ## 朝向
 *
 * 需求方定的：尖尖朝外。参考图（`vecteezy.png`）恰好是反过来的 —— 那颗心的凹口朝外、
 * 尖抵在杆头上；但「尖朝外」才读作一支**箭**（镞是尖的），所以按需求方的来。
 *
 * 心身因此从箭尖往**后**长，箭杆只画到心形末端 —— 这正是镞套在箭杆上的样子，
 * 爱心尖端和心身都不会被白色箭杆穿出。
 *
 * ## 为什么不整个跟着箭身转
 *
 * 参考图里的心形箭镞保留圆润的糖瓷体积和高光，不把轮廓压扁成普通三角箭头。
 * 箭镞在基准形制里跟着箭身转，外层弓整体旋转后仍与白色箭杆保持同轴。
 *
 * 所以本函数在 [drawArcheryArrow] 的 `rotateRad` **之外**调用，坐标直接按屏幕算：
 * [unitHeartPath] 的六个控制点当场折算、沿箭尖上下翻一次（`0.92 − k`），再完整绕箭尖
 * 旋转到箭身方向。
 */
private fun DrawScope.drawArrowHeart(tip: Offset, size: Float, angle: Float, alpha: Float) {
    val s = size * 1.18f
    val head = ARCHER_SCRATCH
    val ox = tip.x - 0.5f * s
    // 单位心的 y 沿箭尖翻过来：尖（0.92）落在箭尖上，两瓣（0.02..0.30）朝后长。
    // 0.30 对应心身的尾端，必须与箭杆的停止位置共用同一比例。
    fun hy(k: Float): Float = tip.y + (0.92f - k) * s
    head.rewind()
    head.moveTo(ox + 0.5f * s, hy(0.92f))
    head.cubicTo(
        ox - 0.18f * s, hy(0.52f),
        ox + 0.16f * s, hy(0.02f),
        ox + 0.5f * s, hy(ARROW_HEART_CENTER_K)
    )
    head.cubicTo(
        ox + 0.84f * s, hy(0.02f),
        ox + 1.18f * s, hy(0.52f),
        ox + 0.5f * s, hy(0.92f)
    )
    head.close()
    rotateRad((angle + PI.toFloat() / 2f) * HEART_TILT, pivot = tip) {
        drawPath(head, ARROW_POINT, alpha = alpha)
        drawPath(head, ARROW_POINT_DEEP, alpha = alpha * 0.85f, style = Stroke(width = s * 0.05f))
    }
}

/** 心形两瓣之间的中心回折点，也是箭杆与爱心的连接点。 */
private const val ARROW_HEART_CENTER_K = 0.30f

/** 爱心从箭尖到中心回折点的深度。 */
private const val ARROW_HEART_BODY_DEPTH = 0.92f - ARROW_HEART_CENTER_K

/** 爱心中心末端相对整支箭长的位置。 */
private const val ARROW_HEART_BACK_FRACTION =
    ARROW_POINT_FRACTION * 1.18f * ARROW_HEART_BODY_DEPTH

/**
 * 箭杆与爱心的隐藏咬合量，占箭长比例。
 *
 * 端点数学上重合时，两个抗锯齿边界只共享一个像素点，手机高密度屏上仍会读成断开。
 * 让杆头向爱心内侧咬入约 2px，再由后绘制的爱心盖住，既无缝又不会看到白杆穿心。
 */
private const val ARROW_HEART_JOIN_OVERLAP = 0.012f

/** 箭杆前端停止的位置：与爱心中心末端有极小咬合，不留视觉空隙。 */
internal fun swiftieLoverArrowShaftHeadFraction(from: Float): Float =
    maxOf(from, (ARROW_HEART_BACK_FRACTION - ARROW_HEART_JOIN_OVERLAP).coerceAtLeast(0f))

/**
 * 羽片与箭身的夹角（度，自 +x 起算，>90° = 朝后）。
 *
 * **三枚共用同一个角度**，靠羽根沿杆错开来分开（[VANE_ROOT_STEP]）—— 这是从参考图上
 * 看明白的：那把羽扇的四五枚羽片彼此几乎平行，梢头连起来是一条与杆平行的线，
 * 而不是从一个点散出去的扇形。
 *
 * 前两版都是「共一个羽根、各自一个后掠角」：无论把角度铺到 126° 还是收到 141°，
 * 羽根附近所有羽片都叠在一起，截图里就是一把**粉色的扇贝**压在弦上。
 */
private const val VANE_SWEEP_DEG = 145f

/** 相邻两枚羽根沿杆错开多少个羽长。0.26 之后羽片之间留得出一条看得见的缝。 */
private const val VANE_ROOT_STEP = 0.18f

/** 每一枚羽片的长度（相对一个羽长）。越往后越短一点，扇尾才是圆的。 */
private val VANE_LEN = floatArrayOf(0.90f, 0.82f, 0.74f)

/**
 * 尾羽：一把粉色的羽扇，两侧各三枚圆头羽片。
 *
 * 每一枚是一根等宽、两头圆的胶囊（长宽约 6.5:1），三枚彼此平行、羽根沿杆依次后错，
 * 于是整把扇子是**顺着杆往后铺**的一把梳子 —— 参考图里就是这个形制。
 *
 * 每一枚画三道：暗边的胶囊、窄一点且朝外缘偏一点的亮面、中间一条淡色的羽轴。
 * 相邻两枚之间因此自带一条暗线，每一枚自己又被羽轴分成两半 —— 少了羽轴，
 * 一枚羽片就是一根粉色的棒；少了暗线，三枚合起来是一块粉色的膜。
 * 由后向前画，靠前那一枚压在最上面，与参考图的叠序一致。
 *
 * 尺寸改过两轮：羽长从 0.34 收到 0.20 个箭长、四枚减到三枚 —— 固定箭长后，
 * 0.34 那一版的羽扇比那颗心还大，读作一把梳子架在心上。
 *
 * 尾端那一小截 `nock` 是扣弦的槽口：比杆粗、亮一档，箭「扣在弦上」才有落点。
 */
private fun DrawScope.drawArrowFletching(
    tailX: Float,
    y: Float,
    vaneLen: Float,
    vaneH: Float,
    half: Float,
    alpha: Float
) {
    val rad = VANE_SWEEP_DEG * PI.toFloat() / 180f
    val dx = cos(rad)
    val dy = sin(rad)
    for (side in -1..1 step 2) {
        for (k in VANE_LEN.indices.reversed()) {
            val len = vaneLen * VANE_LEN[k]
            // 最靠前那一枚的羽根落在尾端往前 1.3 个羽长处，于是最后一枚的梢头正好收在
            // 尾端上 —— 再往后铺，羽毛就穿过弦垂到弓下面去了
            val root = Offset(tailX + vaneLen * (0.82f - VANE_ROOT_STEP * k), y)
            val tip = root + Offset(dx * len, side * dy * len)
            // 法线乘 side 之后，-n 恒为羽片的外缘，左右自动镜像
            val n = Offset(-side * dy, dx) * side.toFloat()
            val thick = vaneH * (1f - k * 0.05f)
            drawLine(
                ARROW_VANE_DEEP, root, tip,
                strokeWidth = thick * 2f, cap = StrokeCap.Round, alpha = alpha * 0.92f
            )
            val lift = n * (thick * 0.15f)
            drawLine(
                ARROW_VANE, root - lift, tip - lift,
                strokeWidth = thick * 1.70f, cap = StrokeCap.Round, alpha = alpha * 0.98f
            )
            drawLine(
                ARROW_VANE_RIB,
                root + (tip - root) * 0.18f,
                root + (tip - root) * 0.92f,
                strokeWidth = thick * 0.40f,
                cap = StrokeCap.Round,
                alpha = alpha * 0.65f
            )
        }
    }
    drawLine(
        ARROW_SHAFT_LIT, Offset(tailX, y), Offset(tailX + vaneLen * 0.16f, y),
        strokeWidth = half * 2.4f, alpha = alpha
    )
    drawLine(
        ARROW_OUTLINE, Offset(tailX, y), Offset(tailX + vaneLen * 0.16f, y),
        strokeWidth = half * 2.4f, alpha = alpha * 0.45f
    )
}

/** 箭长（px）：搭在弓上那一支。三层共用的基准长度。 */
internal fun swiftieLoverArrowLength(propBox: Rect): Float = propBox.width * ARROW_LEN

/** 插在心上那支箭的长度：与弓上和飞行中的箭保持完全一致。 */
internal fun swiftieLoverStuckLength(propBox: Rect): Float =
    swiftieLoverArrowLength(propBox)

/** 扣弦点：[draw] = 0f 松弦（弦是直线）、1f 拉满。 */
private fun bowNock(box: Rect, drawY: Float): Offset =
    Offset(box.left + box.width * 0.50f, box.top + box.height * drawY)

/** 页面根坐标中的箭头朝向。单位向量和角度始终由同一条直线推出。 */
internal data class SwiftieLoverAim(
    val unit: Offset,
    val angle: Float
)

/** 页面根坐标中的飞行几何，箭尖从 [launchTip] 直线移动到 [targetTip]。 */
internal data class SwiftieLoverArrowGeometry(
    val launchTip: Offset,
    val targetTip: Offset,
    val unit: Offset,
    val angle: Float
)

private fun normalizeOrFallback(vector: Offset, fallback: Offset): Offset {
    val length = hypot(vector.x, vector.y)
    return if (length > 0.001f && length.isFinite()) {
        Offset(vector.x / length, vector.y / length)
    } else {
        fallback
    }
}

/**
 * 从真实卡片扣弦点瞄向页面彩虹爱心。
 *
 * `cardBounds` 与背景画布使用同一套根坐标；不要按屏幕比例猜卡片位置，否则平板居中卡片
 * 和手机窄屏会得到不同的箭线。目标先取爱心中心，再沿同一单位向量推到插箭箭尖。
 */
internal fun swiftieLoverAim(cardBounds: Rect, pageSize: Size): SwiftieLoverAim {
    if (cardBounds.width <= 0f || cardBounds.height <= 0f ||
        pageSize.width <= 0f || pageSize.height <= 0f
    ) {
        return SwiftieLoverAim(LOVER_FALLBACK_AIM_UNIT, LOVER_FALLBACK_AIM_ANGLE)
    }
    val box = swiftiePropBox(cardBounds.size)
    val nock = cardBounds.topLeft + bowNock(box, BOW_FULL_DRAW_Y)
    val delta = swiftieLoverHeartBox(pageSize).center - nock
    val unit = normalizeOrFallback(delta, LOVER_FALLBACK_AIM_UNIT)
    return SwiftieLoverAim(unit, atan2(unit.y, unit.x))
}

/**
 * 计算三层共用的发射点和命中点。
 *
 * `launchTip` 是弓上箭尖，不是扣弦点；`targetTip` 是心上插住后露出的箭尖。两点之间
 * 只做线性插值，保证箭全程沿着瞄准爱心的直线运动，不再使用贝塞尔弧线。
 */
internal fun swiftieLoverArrowGeometry(
    cardBounds: Rect,
    pageSize: Size
): SwiftieLoverArrowGeometry {
    val box = swiftiePropBox(cardBounds.size)
    val aim = swiftieLoverAim(cardBounds, pageSize)
    val nock = cardBounds.topLeft + bowNock(box, BOW_FULL_DRAW_Y)
    val launchTip = nock + aim.unit * swiftieLoverArrowLength(box)
    val targetTip = swiftieLoverStuckTip(
        swiftieLoverHeartBox(pageSize),
        swiftieLoverStuckLength(box),
        aim.unit
    )
    return SwiftieLoverArrowGeometry(launchTip, targetTip, aim.unit, aim.angle)
}

/**
 * 撒放那一刻箭尖的位置（道具框局部坐标）。
 *
 * [aimUnit] 由扣弦点指向彩虹爱心，箭尖从扣弦点沿该方向伸出箭长；不能再固定减去 Y。
 */
internal fun swiftieLoverArrowStart(
    propBox: Rect,
    aimUnit: Offset = LOVER_FALLBACK_AIM_UNIT
): Offset = bowNock(propBox, BOW_FULL_DRAW_Y) +
    normalizeOrFallback(aimUnit, LOVER_FALLBACK_AIM_UNIT) * swiftieLoverArrowLength(propBox)

/**
 * 插住之后箭尖停在哪：从心的中心沿 [aimUnit] 再往前 [STUCK_DEPTH] 个箭长。
 *
 * 深度由「箭镞要露出来」反推（见 [STUCK_DEPTH] 的注释）：露太少读作贴在心的正面，
 * 露太多箭尖与心的尖瓣脱开、悬在下面。箭尾则继续从心的另一侧露出，保留「穿过」的层次。
 */
internal fun swiftieLoverStuckTip(
    heart: Rect,
    length: Float,
    aimUnit: Offset = LOVER_FALLBACK_AIM_UNIT
): Offset = heart.center +
    normalizeOrFallback(aimUnit, LOVER_FALLBACK_AIM_UNIT) * (length * STUCK_DEPTH)

/** 三次贝塞尔上 [t] 处的点。弓臂的局部参考曲线仍保留，箭的飞行不再使用它。 */
private fun cubicAt(p0: Offset, p1: Offset, p2: Offset, p3: Offset, t: Float): Offset {
    val u = 1f - t
    return p0 * (u * u * u) + p1 * (3f * u * u * t) +
        p2 * (3f * u * t * t) + p3 * (t * t * t)
}

/** 同一条弓臂曲线在 [t] 处的切线（未归一化）。 */
private fun cubicTangent(p0: Offset, p1: Offset, p2: Offset, p3: Offset, t: Float): Offset {
    val u = 1f - t
    return (p1 - p0) * (3f * u * u) + (p2 - p1) * (6f * u * t) +
        (p3 - p2) * (3f * t * t)
}

/**
 * 弓臂中线的 7 个控制点（两段三次曲线首尾相接），按 `x0 y0 x1 y1 …` 摊平存在这里。
 *
 * 摊成 FloatArray 而不是 `Array<Offset>`：Offset 是 inline value class，装进数组就会被
 * 装箱 —— 每帧两条臂各 7 个对象，与本文件「一条 Path 反复 rewind」是同一条铁律。
 * 顶层单例安全的理由同 [ARCHER_SCRATCH]：draw 全在主线程上顺序跑，一条臂画完才写下一条。
 */
private val LIMB_PTS = FloatArray(14)

/** [LIMB_PTS] 里第 [i] 个控制点。 */
private fun limbPt(i: Int): Offset = Offset(LIMB_PTS[i * 2], LIMB_PTS[i * 2 + 1])

/**
 * 把弓臂的 7 个控制点写进 [LIMB_PTS]。**只算右半边**：左半边由 [bowLimbEdge] 沿 [cx]
 * 镜像出来，两侧因此共用同一条带、正中没有接缝（见 [bowLimbPath]）。
 *
 * 单位：x 以中线 [cx] 为原点、[earDx] 为 1；y 以冠 [apexY] 为原点、到弦耳的落差
 * [depth] 为 1。
 *
 * 这 14 个数就是「弓」与「彩虹」的全部差别（文件头形制第 1 条），而且是**从参考图上量的**：
 * 把那张图的弦当基线、弓臂到弦的垂距当落差，沿臂取六个点归一化之后是
 * `(0, 0) (0.18, 0.04) (0.38, 0.22) (0.60, 0.53) (0.80, 0.84) (1, 1)` ——
 * - 冠上出发的切线是**水平**的，但只平那么一小段：到半个臂展处已经降了四成落差
 *   （一段圆弧在同一处只降 13%）；
 * - 中段（0.4..0.8 个臂展）是**最陡**的一截，斜率约 1.5；
 * - 到弦耳前又**缓回来**（斜率降到 0.86），曲率在这里反号 —— 那就是参考图里球前
 *   那一勾（反曲），也是这条曲线不是抛物线的地方。
 *
 * 前一版把前 66% 的臂展压在 6% 落差以内、然后陡然折下，截图里读作一个**方括号**：
 * 平顶 + 两个直角 + 两条竖边。弓的扁不是「平顶」，是「整条都在缓缓下坠」。
 *
 * 接点两侧的切线取**同一个向量**（`p3 − p2` 与 `q1 − q0` 都是 `(0.17, 0.24 + 0.01 flex)`），
 * 两段严格 C1 连续，接缝在带的边缘上看不出折角。
 *
 * @param flex 拉弦量（[bowDrawAt]）。拉弓时腹再外扩、折点再下沉，臂就「绷」起来了；
 *   撒放后它为负，臂反向绷直再收回来
 */
private fun setLimb(cx: Float, apexY: Float, earDx: Float, depth: Float, flex: Float) {
    fun put(i: Int, ux: Float, uy: Float) {
        LIMB_PTS[i * 2] = cx + earDx * ux
        LIMB_PTS[i * 2 + 1] = apexY + depth * uy
    }
    put(0, 0f, 0f)
    put(1, 0.22f, 0f)
    put(2, 0.38f, 0.20f + 0.02f * flex)
    put(3, 0.55f, 0.44f + 0.03f * flex)
    put(4, 0.72f, 0.68f + 0.04f * flex)
    put(5, 0.86f, 0.88f)
    put(6, 1.00f, 1.00f)
}

/** 弓臂中线在 [t]（0 = 冠，1 = 弦耳）处的点。前半段走第一条曲线，后半段走第二条。 */
private fun limbAt(t: Float): Offset = if (t < 0.5f) {
    cubicAt(limbPt(0), limbPt(1), limbPt(2), limbPt(3), t * 2f)
} else {
    cubicAt(limbPt(3), limbPt(4), limbPt(5), limbPt(6), (t - 0.5f) * 2f)
}

/** 同一条中线在 [t] 处的切线（未归一化，只用方向）。 */
private fun limbTangent(t: Float): Offset = if (t < 0.5f) {
    cubicTangent(limbPt(0), limbPt(1), limbPt(2), limbPt(3), t * 2f)
} else {
    cubicTangent(limbPt(3), limbPt(4), limbPt(5), limbPt(6), (t - 0.5f) * 2f)
}

/**
 * 弓臂在 [t] 处的半宽。
 *
 * 参考图里粗细也是变的（形制第 2 条）：腹最粗（约冠的 1.09 倍）、球前那截颈最细
 * （冠的 0.44）。上一版是从冠单调收到 0.62，且整条只有冠宽的一半粗 —— 截图里读作
 * 一根弯过来的铁丝，而参考图里那是一根**糖**。
 */
private fun bowLimbHalf(gripHalf: Float, t: Float): Float =
    gripHalf * (1f + 0.14f * sin(t * PI.toFloat()) - 0.56f * t * t * t)

/** 中线在 [t] 处沿法线偏 [offset] 的点。正 = 下缘（弦那一侧），负 = 上缘。 */
private fun limbEdge(t: Float, offset: Float): Offset {
    val pt = limbAt(t)
    val tangent = limbTangent(t)
    val len = hypot(tangent.x, tangent.y).coerceAtLeast(1e-4f)
    return pt + Offset(-tangent.y / len, tangent.x / len) * offset
}

/**
 * 第 [i] 个取样点（负 = 沿 [cx] 镜像的那一侧）在上/下缘 [k] 倍半宽处的位置。
 *
 * 镜像只翻 x —— 绕一条竖线翻转不会把上缘翻成下缘，所以两侧同一个 [k] 取到的仍是同一条缘。
 */
private fun bowLimbEdge(cx: Float, gripHalf: Float, i: Int, k: Float): Offset {
    val u = abs(i) / BOW_LIMB_STEPS.toFloat()
    val e = limbEdge(u, k * bowLimbHalf(gripHalf, u))
    return if (i < 0) Offset(2f * cx - e.x, e.y) else e
}

/**
 * 整把弓的弓臂：从左弦耳翻过冠走到右弦耳，**一条带画完两侧**。
 *
 * 为什么合成一条：两侧各画一条带的话，两条带在正中各有一个平口切面，接缝正好落在 cx
 * 上，那圈暗边描边会把它描成一道竖线 —— 截图里读作「弓中间断了一节」。合成一条之后
 * 只在两个弦耳处各有一个切面，而那两处正好被球压住。
 *
 * [edgeOnly] > 0 时只取上缘往里 [edgeOnly] 倍半宽处的那一条折线（受光的棱），不闭合成带。
 */
private fun bowLimbPath(path: Path, cx: Float, gripHalf: Float, edgeOnly: Float) {
    path.rewind()
    val top = if (edgeOnly > 0f) -edgeOnly else -1f
    for (i in -BOW_LIMB_STEPS..BOW_LIMB_STEPS) {
        val o = bowLimbEdge(cx, gripHalf, i, top)
        if (i == -BOW_LIMB_STEPS) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y)
    }
    if (edgeOnly > 0f) return
    for (i in BOW_LIMB_STEPS downTo -BOW_LIMB_STEPS) {
        val o = bowLimbEdge(cx, gripHalf, i, 1f)
        path.lineTo(o.x, o.y)
    }
    path.close()
}

/** 弓臂取样数。两段接起来一共 28 段，折下去那一段（后半）分到 14 段才看不出折线。 */
private const val BOW_LIMB_STEPS = 28

/**
 * 一把丘比特的金弓，先按参考图的三维金弓形制绘制，再整体转向页面彩虹爱心 ——
 * 弓臂、弦、箭杆共享同一个瞄准角度，箭尖从扣弦点沿直线指向目标。
 *
 * ## 形制（复刻，见本文件配色那一段的五条）
 *
 * 一侧一条弓臂，由**两段三次曲线**接成（[setLimb]）：中段一段近乎平的冠，往外一段长而
 * 缓的腹，再陡然折下，最后那一小截向内反勾着收进球里。粗细同时在变（[bowLimbHalf]），
 * 腹最厚、球前那截颈最薄。两条臂在正中以水平切线相接，合起来是一条连续的曲线。
 * 早先的版本是一段等曲率的半圆拱（两端以 45° 斜落到弦上）—— 那是彩虹，不是弓。
 * 臂梢各顶一颗球，弦与球的接点内侧再来一小道缠结。
 *
 * ## 弹力
 *
 * [bowDrawAt] 那一个数同时驱动三件事：扣弦点下沉、**弦耳被拽向弦那一侧、弓臂弯度加深**。
 * 撒放之后它变成一段衰减振荡，于是弦耳会越过静止位往上弹、弓臂反向绷直再收回来 ——
 * 弹力是「臂在动」，不是「弦在动」。
 *
 * 时间线：[LOVER_DRAW_START_MS] 起拉弓 620ms 到满，[LOVER_SHOT_MS] 撒放，之后余震
 * 320ms 收住、箭不再画在弦上（此刻它由 [SwiftieLoverArrowFlight] 接手）。
 * [elapsedMs] 为负 = 低配机那一档定格：永远是松弦上着箭的静态，也不会有飞行段与命中。
 */
internal fun DrawScope.drawLoverBow(
    box: Rect,
    alpha: Float,
    elapsedMs: Long,
    aimAngle: Float = LOVER_FALLBACK_AIM_ANGLE
) {
    val w = box.width
    val h = box.height
    val cx = box.left + w * 0.50f
    val apexY = box.top + h * BOW_APEX_Y
    val drawT = bowDrawAt(elapsedMs)
    // 弦耳随拉弦量下沉并微微内收：弓一拉，臂梢就朝拉弦的那一侧走。
    // drawT 在撒放后为负，于是这里自动变成「弹过静止位」
    val earY = box.top + h * (BOW_EAR_Y + 0.055f * drawT)
    val earDx = w * (BOW_EAR_X - 0.020f * drawT)
    val gripHalf = w * 0.044f
    val hair = (w * 0.0055f).coerceAtLeast(1f)
    val limb = ARCHER_SCRATCH
    val nockY = box.top + h * (BOW_EAR_Y + (BOW_FULL_DRAW_Y - BOW_EAR_Y) * drawT)
    val nock = Offset(cx, nockY)
    // 所有弓臂、弦、箭都先按参考图的「弓背朝右、箭尖向左」基准形制绘制，
    // 再围绕真实扣弦点整体转到「扣弦点 → 彩虹爱心」的方向。这样弓臂和箭杆不会各自漂移。
    rotateRad(aimAngle + PI.toFloat() / 2f, pivot = nock) {
        // 弓臂：两侧合成一条带，正中不留接缝。
        // 描边 → 填充 → 上缘那一条受光的棱，三档金缺一档就是一块黄色色块
        setLimb(cx, apexY, earDx, earY - apexY, drawT)
        bowLimbPath(limb, cx, gripHalf, edgeOnly = 0f)
        drawPath(limb, Color.White, alpha = alpha * PROP_MASK)
        drawPath(limb, BOW_GOLD, alpha = alpha * 1.6f)
        drawPath(limb, BOW_GOLD_DEEP, alpha = alpha * 2.0f, style = Stroke(width = hair))
        bowLimbPath(limb, cx, gripHalf, edgeOnly = 0.46f)
        drawPath(limb, BOW_GOLD_LIT, alpha = alpha * 1.8f, style = Stroke(width = hair * 1.3f))

        for (side in -1..1 step 2) {
            val ear = Offset(cx + side * earDx, earY)
            // 臂梢那颗球：参考图里两端各一颗，弦就系在球的内侧。
            // 少了它弓臂末端是被平口切断的，读作一根断掉的金属条。
            drawCircle(Color.White, radius = gripHalf * 1.25f, center = ear, alpha = alpha * PROP_MASK)
            drawCircle(BOW_GOLD, radius = gripHalf * 1.25f, center = ear, alpha = alpha * 2.1f)
            drawCircle(
                BOW_GOLD_LIT,
                radius = gripHalf * 0.42f,
                center = Offset(ear.x - side * gripHalf * 0.34f, ear.y - gripHalf * 0.38f),
                alpha = alpha * 1.9f
            )
            // 缠结：弦系在球内侧的那一小道。参考图上就是这么两笔，位置不能离球太远
            val bind = ear + (nock - ear) * 0.11f
            drawLine(
                BOW_STRING,
                Offset(bind.x - side * hair * 1.4f, bind.y - hair * 2.2f),
                Offset(bind.x + side * hair * 1.4f, bind.y + hair * 2.2f),
                strokeWidth = hair * 2.2f,
                alpha = alpha * 2.2f
            )
        }

        drawBowString(
            earL = Offset(cx - earDx, earY),
            earR = Offset(cx + earDx, earY),
            cx = cx,
            nockY = nockY,
            w = w,
            hair = hair,
            alpha = alpha,
            drawT = drawT
        )

        if (elapsedMs < LOVER_SHOT_MS) {
            // 箭尖在扣弦点沿基准方向伸出，外层旋转后正好指向爱心。
            drawArcheryArrow(
                tip = Offset(cx, nockY - swiftieLoverArrowLength(box)),
                angle = -PI.toFloat() / 2f,
                length = swiftieLoverArrowLength(box),
                alpha = (alpha * 2.2f).coerceAtMost(1f)
            )
        }
    }
}

/**
 * 拉弦量：0f = 松弦，1f = 拉满，**负数 = 撒放之后弦往前弹过头**。
 *
 * 撒放那一下是 `衰减 × 正弦`：起手为负（弦朝目标那边弹出去），周期约 100ms、
 * 110ms 衰减一个 e，320ms 之后已经小于 0.06 看不出来了。少了这一下弦从满弓
 * 直接回到直线，读作弦被剪断而不是箭被射出去。
 */
private fun bowDrawAt(elapsedMs: Long): Float {
    if (elapsedMs < LOVER_DRAW_START_MS) return 0f
    val since = elapsedMs - LOVER_SHOT_MS
    if (since < 0L) {
        val t = ((elapsedMs - LOVER_DRAW_START_MS) / LOVER_DRAW_MS).coerceIn(0f, 1f)
        // smoothstep：拉弓两头慢中间快，线性拉起来像被机器匀速拽
        return t * t * (3f - 2f * t)
    }
    if (since >= LOVER_TWANG_MS) return 0f
    return -0.30f * exp(-since / 110f) * sin(since * 0.062f)
}

/**
 * 弓弦：两个弦耳到扣弦点的两段 + 扣弦点那一小截弦垫。
 *
 * 松弦时这两段合成**一条横线**，那是「这是一把弓」的另一半证据（见 [BOW_EAR_Y]）。
 * 所以线宽兜底到 1.6px 而不是「越细越真」—— 上一版按 0.004 个框宽画，在 1080p 上
 * 不到一个像素，抗锯齿一摊就没了。
 *
 * 撒放之后（[drawT] 为负）多画两道半透明的影线：真弦弹起来是一团振动的虚影，
 * 一根实线来回摆读作「弦在被人拖着走」。
 */
private fun DrawScope.drawBowString(
    earL: Offset,
    earR: Offset,
    cx: Float,
    nockY: Float,
    w: Float,
    hair: Float,
    alpha: Float,
    drawT: Float
) {
    val nock = Offset(cx, nockY)
    val thin = (w * 0.0075f).coerceAtLeast(1.6f)
    if (drawT < -0.01f) {
        // 振动虚影：以扣弦点为中心上下各一道，幅度跟着余震收干
        val spread = w * 0.045f * abs(drawT)
        for (dir in -1..1 step 2) {
            val ghost = Offset(cx, nockY + dir * spread)
            drawLine(BOW_STRING, earL, ghost, strokeWidth = thin * 0.8f, alpha = alpha * 0.7f)
            drawLine(BOW_STRING, ghost, earR, strokeWidth = thin * 0.8f, alpha = alpha * 0.7f)
        }
    }
    drawLine(BOW_STRING, earL, nock, strokeWidth = thin, alpha = alpha * 2.2f)
    drawLine(BOW_STRING, nock, earR, strokeWidth = thin, alpha = alpha * 2.2f)
    // 弦垫（serving）：扣弦处缠的那一小段线，比弦粗一倍 ——
    // 有了它「箭扣在弦上」才有落点
    drawLine(
        BOW_STRING,
        Offset(cx - w * 0.016f, nockY - w * 0.007f),
        Offset(cx + w * 0.016f, nockY + w * 0.007f),
        strokeWidth = hair * 1.8f,
        alpha = alpha * 2.2f
    )
}

/**
 * 那 900ms 的飞行段：画在**页面最上层**，因为它要从卡片里飞出去。
 *
 * 挂在 `SwiftieEggScreen` 的根 Box 里（不吃 insets），所以本层的局部坐标就是根坐标。
 * [swiftieLoverArrowGeometry] 把卡片扣弦点和背景彩虹爱心放进同一个坐标系，箭尖从真实
 * 发射点到命中点做线性插值；弓、飞行箭、背景插箭共用同一条瞄准直线。
 *
 * @param elapsedMs 本段（Lover）已过的毫秒，与卡片自己的 `elapsedInCard` 同一个时基
 * @param cardBounds Lover 卡片在根坐标里的边框（px）。[Rect.Zero] = 还没量到，本层不画
 */
@Composable
internal fun SwiftieLoverArrowFlight(
    elapsedMs: () -> Long,
    cardBounds: () -> Rect,
    modifier: Modifier = Modifier
) {
    Spacer(
        modifier = modifier.fillMaxSize().drawBehind {
            val t = (elapsedMs() - LOVER_SHOT_MS).toFloat() / LOVER_FLIGHT_MS
            if (t <= 0f || t >= 1f) return@drawBehind
            val card = cardBounds()
            if (card.width <= 0f || card.height <= 0f) return@drawBehind
            val geometry = swiftieLoverArrowGeometry(card, size)
            val tip = geometry.launchTip +
                (geometry.targetTip - geometry.launchTip) * t
            // 飞行全程保持与弓上、命中后的同一箭长，不做视觉缩放。
            val length = swiftieLoverArrowLength(swiftiePropBox(card.size))
            drawArcheryArrow(
                tip = tip,
                angle = geometry.angle,
                length = length,
                // 起飞与命中各留一小段淡入淡出：凭空出现又凭空消失读作贴图闪了一下
                alpha = 0.95f * (t / 0.10f).coerceAtMost(1f) *
                    ((1f - t) / 0.08f).coerceAtMost(1f)
            )
        }
    )
}
