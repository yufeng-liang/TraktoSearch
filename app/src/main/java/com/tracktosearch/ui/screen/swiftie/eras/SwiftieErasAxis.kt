package com.tracktosearch.ui.screen.swiftie.eras

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.tracktosearch.R
import com.tracktosearch.ui.screen.swiftie.SwiftiePalette
import com.tracktosearch.ui.screen.swiftie.SwiftieTimeline
import com.tracktosearch.ui.screen.swiftie.unitHeartPath

/**
 * 13 个边界比例：色带 `i` 占 `[SWIFTIE_ERA_EDGES[i], SWIFTIE_ERA_EDGES[i + 1]]`。
 *
 * 宽度**按各段时长成比例**（7.9%–9.4%），这样播放头是匀速的。等宽色带会让播放头在
 * 18 首的 Lover 段明显变慢，看着像卡住了。
 */
val SWIFTIE_ERA_EDGES: List<Float> = List(SwiftieTimeline.ERA_TRACK_COUNTS.size + 1) { index ->
    if (index == SwiftieTimeline.ERA_TRACK_COUNTS.size) {
        1f
    } else {
        (SwiftieTimeline.eraStartMs(index) - SwiftieTimeline.ERAS_CARDS_START).toFloat() /
            SwiftieTimeline.ERAS_CARDS_MS
    }
}

/** 第 [index] 段色带的中心比例。卡片的缩放轴心用它。 */
fun swiftieEraCenterFraction(index: Int): Float {
    val safe = index.coerceIn(0, SWIFTIE_ERA_EDGES.size - 2)
    return (SWIFTIE_ERA_EDGES[safe] + SWIFTIE_ERA_EDGES[safe + 1]) / 2f
}

/**
 * 横向比例落在第几段。拖动吸附用。
 *
 * 走一趟账本换算而不是自己二分 `SWIFTIE_ERA_EDGES`：段落归属只能有一个真相来源。
 */
fun swiftieEraIndexAtFraction(fraction: Float): Int {
    val ms = SwiftieTimeline.ERAS_CARDS_START +
        (fraction.coerceIn(0f, 1f) * SwiftieTimeline.ERAS_CARDS_MS).toLong()
    return SwiftieTimeline.eraIndexAt(ms) ?: SwiftieTimeline.ERA_TRACK_COUNTS.lastIndex
}

/** 播放头在轴上的比例。Eras 卡片段之外自动夹到 0f / 1f。 */
fun swiftiePlayheadFraction(elapsedMs: Long): Float =
    ((elapsedMs - SwiftieTimeline.ERAS_CARDS_START).toFloat() / SwiftieTimeline.ERAS_CARDS_MS)
        .coerceIn(0f, 1f)

/** 色带 12 + 间距 5 + 轴线 + 刻度 6 + 播放头旋钮 = 38dp 的图形高度。 */
private val AXIS_VISUAL_HEIGHT = 38.dp

/**
 * 轴的实际高度 —— 也就是它的**触控目标**。
 *
 * 图形只占 [AXIS_VISUAL_HEIGHT]，但拖播放头是这一段唯一的交互，38dp 低于
 * Material 的 48dp 下限。多出来的 10dp 上下各摊 5dp（见 `drawAxis` 里的 `top`），
 * 图形位置几乎没动，手指却多了 26% 的余量。
 */
private val AXIS_TOUCH_HEIGHT = 48.dp

private val RIBBON_HEIGHT = 12.dp
private val TICK_HEIGHT = 6.dp
private val AXIS_SIDE_PADDING = 20.dp

/**
 * TS1-12 标签行的定高。
 *
 * 这一行原本是两端的 `2006` / `2025` —— 那是装饰性重复信息（轴本身与 12 张卡片都带
 * 日期）、对读屏也隐身，所以整块让位给 12 个 TS 标签。**12dp 略低于原先那行 11sp
 * 的行盒**，所以版面净增为负、卡片插槽只会变大不会缩；定高同时保证字号档位调不动
 * 这一行的高度。
 */
private val AXIS_LABEL_HEIGHT = 12.dp

/**
 * 标签字号，用 `Dp.toSp()` 折算掉 `fontScale`。
 *
 * 理由同曲目列：360dp 屏上每格只有约 26.7dp，`TS12` 四个字符已经吃掉 2/3，
 * 跟着系统字号长起来必然与邻格压在一起。这一行是定时动画里的固定版面，没有让它
 * 长大的余地 —— 需要细看的用户走「拖播放头定格」那条路。
 */
private val AXIS_LABEL_FONT_SIZE = 8.dp

/** 未走过的段落**与它的标签**：灰化。走过的换成该时代主色。两层共用一个灰，读起来才是一件事。 */
private val DESATURATED = Color(0xFFB6AFAB)

/** 7·3 心刻度的边长。 */
private val HEART_TICK_SIZE = 5.dp

/** 心刻度的金。与 Fearless 的主色是同一个金 —— 彩蛋不再自己发明第二版金色。 */
private val HEART_TICK_GOLD = Color(0xFFD4AF37)

/**
 * 金心的暗色键线。
 *
 * 金 `#D4AF37` 与 Lover 粉 `#F7A8C4` 的相对亮度几乎相等（1.14:1），走过之后纯填色的
 * 心压在色带上等于没画；未走过时压在灰色带上同样接近 1:1。垫一圈半透明黑之后金对
 * 键线有 3.5:1，两种状态都读得出这是一枚心 —— 描金本来也要有键线才像金属。
 */
private val HEART_TICK_KEYLINE = Color.Black.copy(alpha = 0.55f)

private val HEART_TICK_KEYLINE_WIDTH = 0.6.dp

/**
 * 7·3 心刻度的横向比例。
 *
 * 霉霉 7 月 3 日结婚，Lover 恰好是第 **7** 张专辑、`Lover` 是其中第 **3** 首。
 *
 * 按**时间**折算而不是按曲目序号均分（`2.5 / 18`）：轴是时间轴，色带宽度按时长成
 * 比例，所以「第 3 首」在轴上的位置就是播放头走到那一行点亮时的位置。两种算法在
 * 360dp 屏上只差不到 2dp，但序号均分那个点对应的时刻晚了约 570ms —— 播放头压过
 * 金心时卡片上亮的已经是第 6 首，巧思就落空了。
 */
private val HEART_TICK_FRACTION: Float = run {
    val index = SwiftieErasData.LOVER_INDEX
    val within = (TRACK_REVEAL_START_MS + TRACK_STAGGER_MS * 2).toFloat() /
        SwiftieTimeline.cardDurationMs(index, SwiftieTimeline.ERA_TRACK_COUNTS[index])
    SWIFTIE_ERA_EDGES[index] +
        within * (SWIFTIE_ERA_EDGES[index + 1] - SWIFTIE_ERA_EDGES[index])
}

/** 舞台参数按 [index] 取，越界夹住 —— 这是 draw 阶段每帧都走的路，不能抛。 */
private fun stageAt(index: Int): SwiftieEraStage =
    SwiftieErasData.STAGE[index.coerceIn(0, SwiftieErasData.STAGE.lastIndex)]

/**
 * 第 [activeIndex] 张舞台的底色上，12 个 TS 标签压过 WCAG AA 之后的墨色。
 *
 * 底色取该张 `backdropColors` 的**末档**（屏幕下缘），极性由 `darkBottomInk` 决定：
 * 浅底压暗（[SwiftieEraContrast.readable]）、深底提亮（`readableOnDark`）。
 *
 * **按 active 索引各算一套**而不是钉一个代表底色：12 张的末档从纯黑（reputation）一直到
 * 天蓝（Lover `#9BC4E8`），拿其中任何一档当代表都会把另一端的标签推到看不见。
 * 最亮的那两张深底（1989 `#4A7590`、Fearless `#8A6A16`）本身就是中间调，
 * 提亮到 AA 之后标签会接近纯白 —— 那两段的时代辨识度确实要让一步，
 * 但让的只是那两段，而不是整套。
 *
 * 1989 的末档原本是 `#5B8CA8`，纯白在它上面只有 3.64:1，是 12 张里唯一连理论上限都
 * 到不了 AA 的一张，所以那一档已经在 `SwiftieErasData` 里压深（见那里的注释）。
 */
private fun swiftieLabelInk(activeIndex: Int): List<Color> {
    val stage = stageAt(activeIndex)
    val bottom = stage.backdropColors.last()
    return SwiftieErasData.ALL.map { era ->
        if (stage.darkBottomInk) {
            SwiftieEraContrast.readable(era.mainColor, bottom)
        } else {
            SwiftieEraContrast.readableOnDark(era.mainColor, bottom)
        }
    }
}

/**
 * 横向时间轴：轴线 + 12 段色带 + 刻度 + 播放头 + TS1-12 标签行。
 *
 * 入场分三拍（Spec §5 的 T1100–3100）：轴线自中点向两端铺开 → 刻度与 TS 标签淡入 →
 * 12 段色带以低饱和度升上来。
 *
 * @param introProgress 0f..1f，T1100–3100 的进度
 * @param playheadFraction 0f..1f，播放头位置。倒滑段由调用方给回退中的值
 * @param activeIndex 当前该显示第几张卡片。只用来决定墨色极性（见 [stageAt]），
 *   **只在 draw 阶段读** —— 它每张卡片翻一次，读进组合阶段就是白搭 12 次重组
 * @param interactive false 时整块不收触摸（倒滑与绽放期间）
 * @param onSeekToEra 按下 / 拖动时吸附到的段落索引。`gestureStart` 只在一次手势的
 *   第一个事件为 true —— 「首次拖动给宽限」这类判断必须只在那一下做，
 *   拖动过程中每个移动事件都会回调
 */
@Composable
fun SwiftieErasAxis(
    introProgress: () -> Float,
    playheadFraction: () -> Float,
    activeIndex: () -> Int,
    interactive: Boolean,
    onSeekToEra: (index: Int, gestureStart: Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val axisLabel = stringResource(R.string.swiftie_eras_axis_a11y)
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()

    // 12 个标签的排版一次算完，draw 里只剩「选色 + 摆位」
    val labels = remember(measurer, density) {
        val style = TextStyle(fontSize = with(density) { AXIS_LABEL_FONT_SIZE.toSp() })
        List(SwiftieErasData.ALL.size) { index -> measurer.measure("TS${index + 1}", style) }
    }

    // 12 张底色各一套标签墨色，走到哪张算哪张（见 swiftieLabelInk）。
    // readable / readableOnDark 是 24 步 HSL 二分，一套 12 个标签要上千次 Math.pow，
    // 放进每帧的 draw lambda 会直接吃掉绘制预算。缓存故意是普通数组而不是 state：
    // draw 阶段写它不该触发任何失效
    val labelInk = remember { arrayOfNulls<List<Color>>(SwiftieErasData.STAGE.size) }

    // 心形用共用的 unitHeartPath()，在这里一次性放大到 5dp。若改成 draw 里套一层
    // scale()，键线宽度会跟着一起放大 5 倍
    val heartPath = remember(density) {
        val edge = with(density) { HEART_TICK_SIZE.toPx() }
        unitHeartPath().apply { transform(Matrix().apply { scale(x = edge, y = edge) }) }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(AXIS_TOUCH_HEIGHT)
                .padding(horizontal = AXIS_SIDE_PADDING)
                // 倒滑与绽放期间必须整块停掉：alpha 只改绘制、不改命中区域，
                // 那时候碰一下就会把时钟倒拨回卡片段，配乐钉死的两个点当场失效
                // （同一个理由让「跳过」在那之后也整块不渲染，见 SwiftieSequenceControls）
                .then(
                    if (!interactive) {
                        // 不可交互时连语义也清掉：那几段轴的 alpha 已经是 0，
                        // 留着 contentDescription 只会让 TalkBack 停在一个看不见、
                        // 也点不动的东西上
                        Modifier.clearAndSetSemantics { }
                    } else {
                        Modifier
                            // 只给一条静态说明。**刻意不做 slider 语义** ——
                            // ProgressBarRangeInfo 要读逐帧的播放头位置，而语义 lambda
                            // 里的 state 读会让整棵语义树每帧失效重建；而且 12 段拖动
                            // 对读屏用户本来也没有可用性，卡片自己有完整的播报
                            .semantics { contentDescription = axisLabel }
                            .pointerInput(Unit) {
                                awaitEachGesture {
                                    // 按下即吸附，不等滑动阈值 —— 点一下色带就跳过去是最自然的期待
                                    val down = awaitFirstDown()
                                    onSeekToEra(
                                        swiftieEraIndexAtFraction(down.position.x / size.width),
                                        true
                                    )
                                    // 只消费 Main pass，Initial pass 留给外层 ——
                                    // 「点屏幕浮出控件」是在整页那一层判的，
                                    // 这里把它一起吃掉的话，点色带就再也叫不出控件
                                    down.consume()
                                    drag(down.id) { change ->
                                        onSeekToEra(
                                            swiftieEraIndexAtFraction(change.position.x / size.width),
                                            false
                                        )
                                        change.consume()
                                    }
                                }
                            }
                    }
                )
        ) {
            val intro = introProgress().coerceIn(0f, 1f)
            val linePhase = (intro / 0.45f).coerceIn(0f, 1f)
            val tickPhase = ((intro - 0.35f) / 0.35f).coerceIn(0f, 1f)
            val ribbonPhase = ((intro - 0.55f) / 0.45f).coerceIn(0f, 1f)

            // 轴墨跟着当前专辑翻极性：12 张舞台里末档底色是深色的占 10 张
            // （reputation 直接是纯黑），royal blue 压上去看不见。
            // 判断只读 STAGE 里的 darkBottomInk —— 同一个布尔量还驱动导航栏图标与
            // TS 标签，三处落在同一条底色上，所以**不在这里自己算亮度**：
            // 算出来的值会在换张的 500ms 交叉淡变里来回跨过阈值，墨色就一路闪
            val stage = stageAt(activeIndex())
            val ink = if (stage.darkBottomInk) SwiftiePalette.RoyalBlue else Color.White
            // 旋钮是「反色内芯 + 同色描边」。浅墨那一档若还留白内芯，
            // 白心白环会叠成一个纯白圆点，看不出这是个能拖的东西
            val knobCore = if (stage.darkBottomInk) {
                Color.White
            } else {
                Color.Black.copy(alpha = 0.45f)
            }

            // 图形在 48dp 的触控高度里垂直居中，上下各留 5dp 只做触控余量
            val top = ((size.height - AXIS_VISUAL_HEIGHT.toPx()) / 2f).coerceAtLeast(0f)
            val ribbonHeight = RIBBON_HEIGHT.toPx()
            val ribbonTop = top
            val lineY = top + ribbonHeight + 5.dp.toPx()
            val tickBottom = lineY + TICK_HEIGHT.toPx()
            val knobY = tickBottom + 7.dp.toPx()
            val gap = 2.dp.toPx()
            val corner = CornerRadius(ribbonHeight / 2f)

            // 第一拍：轴线自中点向两端铺开
            val half = size.width / 2f * linePhase
            drawLine(
                color = ink,
                start = Offset(size.width / 2f - half, lineY),
                end = Offset(size.width / 2f + half, lineY),
                strokeWidth = 1.5.dp.toPx(),
                alpha = 0.55f
            )

            // 第二拍：刻度淡入（13 条，含两端）
            if (tickPhase > 0f) {
                SWIFTIE_ERA_EDGES.forEach { edge ->
                    val x = edge * size.width
                    drawLine(
                        color = ink,
                        start = Offset(x, lineY),
                        end = Offset(x, tickBottom),
                        strokeWidth = 1.dp.toPx(),
                        alpha = 0.40f * tickPhase
                    )
                }
            }

            // 第三拍：色带升上来。先整条灰化，再把走过的部分覆一层饱和色
            if (ribbonPhase > 0f) {
                val rise = ribbonTop + (1f - ribbonPhase) * 4.dp.toPx()
                SWIFTIE_ERA_EDGES.zipWithNext().forEach { (from, to) ->
                    drawRoundRect(
                        color = DESATURATED,
                        topLeft = Offset(from * size.width + gap / 2f, rise),
                        size = Size((to - from) * size.width - gap, ribbonHeight),
                        cornerRadius = corner,
                        alpha = 0.55f * ribbonPhase
                    )
                }
                // 一个 clipRect 就够：当前段会被切成半亮，读起来正是「正在放这一段」
                val head = playheadFraction().coerceIn(0f, 1f) * size.width
                clipRect(left = 0f, top = 0f, right = head, bottom = size.height) {
                    SWIFTIE_ERA_EDGES.zipWithNext().forEachIndexed { index, (from, to) ->
                        drawRoundRect(
                            color = SwiftieErasData.ALL[index].mainColor,
                            topLeft = Offset(from * size.width + gap / 2f, rise),
                            size = Size((to - from) * size.width - gap, ribbonHeight),
                            cornerRadius = corner,
                            alpha = ribbonPhase
                        )
                    }
                }

                // 7·3：金心钉在第 7 段内部「第 3 首点亮」的那一刻（见 HEART_TICK_FRACTION）。
                // 必须画在饱和层**之后** —— 那一层是不透明的主色，画在前面会被整块盖掉。
                // 它画在 Canvas 上，本来就没有独立语义节点、读屏扫不到，
                // **不要再给它补 contentDescription**：这是留给看得见的人的巧思
                val heartEdge = HEART_TICK_SIZE.toPx()
                translate(
                    left = HEART_TICK_FRACTION * size.width - heartEdge / 2f,
                    top = rise + (ribbonHeight - heartEdge) / 2f
                ) {
                    drawPath(path = heartPath, color = HEART_TICK_GOLD, alpha = ribbonPhase)
                    drawPath(
                        path = heartPath,
                        color = HEART_TICK_KEYLINE,
                        alpha = ribbonPhase,
                        style = Stroke(width = HEART_TICK_KEYLINE_WIDTH.toPx())
                    )
                }

                // 播放头：竖线穿过色带与刻度，下面挂一个反色内芯 + 同色环的旋钮
                val headX = playheadFraction().coerceIn(0f, 1f) * size.width
                drawLine(
                    color = ink,
                    start = Offset(headX, ribbonTop),
                    end = Offset(headX, knobY),
                    strokeWidth = 2.5.dp.toPx(),
                    cap = StrokeCap.Round,
                    alpha = ribbonPhase
                )
                drawCircle(
                    color = knobCore,
                    radius = 5.dp.toPx(),
                    center = Offset(headX, knobY),
                    alpha = ribbonPhase
                )
                drawCircle(
                    color = ink,
                    radius = 5.dp.toPx(),
                    center = Offset(headX, knobY),
                    alpha = ribbonPhase,
                    style = Stroke(width = 1.5.dp.toPx())
                )
            }
        }

        // TS1-12 标签行，跟着刻度那一拍淡入。写的是专辑序号，
        // **不是 `3/12` 那种进度数字**（Spec §6.1 不许出现进度计数）
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(AXIS_LABEL_HEIGHT)
                // 与上面那块 Canvas 同一个 20dp 侧边距，标签才能与色带中心对得上
                .padding(horizontal = AXIS_SIDE_PADDING)
                .graphicsLayer {
                    alpha = ((introProgress() - 0.35f) / 0.35f).coerceIn(0f, 1f)
                }
                // 整块对 TalkBack 隐身：12 个标签各自可停靠会让读屏在轴上停 12 次，
                // 而卡片本身已经播报「专辑名，发行于 X，共 N 首」
                .clearAndSetSemantics { }
        ) {
            val head = playheadFraction().coerceIn(0f, 1f)
            val index = activeIndex().coerceIn(0, SwiftieErasData.STAGE.lastIndex)
            val ink = labelInk[index] ?: swiftieLabelInk(index).also { labelInk[index] = it }
            labels.forEachIndexed { era, layout ->
                drawText(
                    textLayoutResult = layout,
                    // 播放头扫过这一格的起点就换成该专辑主色 —— 标签自己就是进度指示，
                    // 所以不必再画第二套指示器
                    color = if (head >= SWIFTIE_ERA_EDGES[era]) ink[era] else DESATURATED,
                    topLeft = Offset(
                        x = swiftieEraCenterFraction(era) * size.width - layout.size.width / 2f,
                        y = (size.height - layout.size.height) / 2f
                    )
                )
            }
        }
    }
}

