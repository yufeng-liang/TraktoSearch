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
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
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
import kotlin.math.round

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

/** 色带 12 + 间距 5 + 轴线 + 刻度 12 + 播放头旋钮 12 = 41dp 的图形高度。 */
private val AXIS_VISUAL_HEIGHT = 41.dp

/**
 * 轴的实际高度 —— 也就是它的**触控目标**。
 *
 * 图形只占 [AXIS_VISUAL_HEIGHT]，但拖播放头是这一段唯一的交互，38dp 低于
 * Material 的 48dp 下限。多出来的 10dp 上下各摊 5dp（见 `drawAxis` 里的 `top`），
 * 图形位置几乎没动，手指却多了 26% 的余量。
 */
private val AXIS_TOUCH_HEIGHT = 48.dp

private val RIBBON_HEIGHT = 12.dp

/**
 * 刻度长度。
 *
 * 12dp 而不是原来的 6dp：TS1-12 标签现在落在**两条刻度之间**（原来单独一行摆在刻度
 * 下面，读起来是「轴」和「一排标签」两件事），刻度得够长才框得住标签。
 */
private val TICK_HEIGHT = 12.dp
private val AXIS_SIDE_PADDING = 20.dp

/**
 * 轴下面那行的定高 —— 现在只放**两端的年份**。
 *
 * TS1-12 标签搬进了刻度之间（见 [TICK_HEIGHT]），这一行腾出来给首末两张专辑的年份：
 * 左端起点下面是 TS1 的年份、右端终点下面是 TS12 的年份，一眼就知道这条轴横跨多少年。
 * 年份取自 `releaseDate.take(4)`，不另写常量 —— 数据改了年份跟着改。
 *
 * 定高保证字号档位调不动这一行的高度（整段是定时动画里的固定版面）。
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

    // 两端的年份：左端是 TS1 的、右端是 TS12 的，取 releaseDate 前 4 位。
    // 与 TS 标签同一个字号 —— 它们同属「轴的刻度说明」，两种字号会读作两套信息
    val yearLabels = remember(measurer, density) {
        val style = TextStyle(fontSize = with(density) { AXIS_LABEL_FONT_SIZE.toSp() })
        listOf(
            measurer.measure(SwiftieErasData.ALL.first().releaseDate.take(4), style),
            measurer.measure(SwiftieErasData.ALL.last().releaseDate.take(4), style)
        )
    }

    // 12 张底色各一套标签墨色，走到哪张算哪张（见 swiftieLabelInk）。
    // readable / readableOnDark 是 24 步 HSL 二分，一套 12 个标签要上千次 Math.pow，
    // 放进每帧的 draw lambda 会直接吃掉绘制预算。缓存故意是普通数组而不是 state：
    // draw 阶段写它不该触发任何失效
    val labelInk = remember { arrayOfNulls<List<Color>>(SwiftieErasData.STAGE.size) }

    // 整条色带的圆角轮廓（12 段连成一条的裁剪区，见 draw 里那段注释）。
    // 尺寸只有 draw 阶段才知道，所以这里只借一个对象出去，每帧 rewind 重填
    val barPath = remember { Path() }

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

            // 第二拍：刻度与 TS1-12 标签一起淡入。刻度 13 条（含两端），
            // 标签落在**相邻两条刻度正中间** —— 一把尺子上的数字就该在两条刻线之间，
            // 而不是另起一行摆在尺子下面（上一版如此，读作轴与标签两件东西）
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
                val head = playheadFraction().coerceIn(0f, 1f)
                val stageIndex = activeIndex().coerceIn(0, SwiftieErasData.STAGE.lastIndex)
                val labelColors = labelInk[stageIndex]
                    ?: swiftieLabelInk(stageIndex).also { labelInk[stageIndex] = it }
                val bandCenterY = (lineY + tickBottom) / 2f
                labels.forEachIndexed { era, layout ->
                    drawText(
                        textLayoutResult = layout,
                        // 播放头扫过这一格的起点就换成该专辑主色 —— 标签自己就是进度指示，
                        // 所以不必再画第二套指示器
                        color = if (head >= SWIFTIE_ERA_EDGES[era]) {
                            labelColors[era]
                        } else {
                            DESATURATED
                        },
                        topLeft = Offset(
                            x = swiftieEraCenterFraction(era) * size.width -
                                layout.size.width / 2f,
                            y = bandCenterY - layout.size.height / 2f
                        ),
                        alpha = tickPhase
                    )
                }
            }

            // 第三拍：色带升上来。先整条灰化，再把走过的部分覆一层饱和色
            if (ribbonPhase > 0f) {
                val rise = ribbonTop + (1f - ribbonPhase) * 4.dp.toPx()
                // **12 段连成一条**：只有 TS1 的左端与 TS12 的右端是圆角，中间的接缝全部打通。
                // 逐段 `drawRoundRect` 做不到 —— 它四角同时圆，每段都圆就是 12 颗独立的胶囊
                // （上一版就是这样，读作一排药片而不是一条时间轴）。
                // 所以先把整条的圆角轮廓当裁剪区，再往里画 12 个**直角**矩形：
                // 外两端被轮廓切成圆角、内部接缝是直的
                barPath.rewind()
                barPath.addRoundRect(
                    RoundRect(
                        left = 0f,
                        top = rise,
                        right = size.width,
                        bottom = rise + ribbonHeight,
                        cornerRadius = corner
                    )
                )
                val head = playheadFraction().coerceIn(0f, 1f) * size.width
                clipPath(barPath) {
                    // 段边界取整到整像素：相邻两块共一条整数边才不会在接缝处抗锯齿出
                    // 一道亮线（12 段连起来之后那道线会横穿整条轴）
                    SWIFTIE_ERA_EDGES.zipWithNext().forEach { (from, to) ->
                        val x0 = round(from * size.width)
                        drawRect(
                            color = DESATURATED,
                            topLeft = Offset(x0, rise),
                            size = Size(round(to * size.width) - x0, ribbonHeight),
                            alpha = 0.55f * ribbonPhase
                        )
                    }
                    // 一个 clipRect 就够：当前段会被切成半亮，读起来正是「正在放这一段」
                    clipRect(left = 0f, top = 0f, right = head, bottom = size.height) {
                        SWIFTIE_ERA_EDGES.zipWithNext().forEachIndexed { index, (from, to) ->
                            val x0 = round(from * size.width)
                            drawRect(
                                color = SwiftieErasData.ALL[index].mainColor,
                                topLeft = Offset(x0, rise),
                                size = Size(round(to * size.width) - x0, ribbonHeight),
                                alpha = ribbonPhase
                            )
                        }
                    }
                }

                // 7·3 的巧思**不在这条轴上**。这里原来钉着一枚 5dp 的金心，在 360dp 屏上
                // 只有十几个像素、还压在同色系的色带上，需求方的判断是「太小了」。
                // 巧思整块搬到 Lover 卡片上：那里有一整行的宽度可用（见 SwiftieEraTracklist
                // 的描金行与 The Archer 那一箭）

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

        // 轴下面这一行现在只放两端的年份：左端起点是首专的年份、右端终点是 TS12 的，
        // 一眼看出这条轴横跨的年数。TS1-12 标签已经搬进刻度之间（见上面那块 Canvas）。
        // 跟着刻度那一拍淡入
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(AXIS_LABEL_HEIGHT)
                // 与上面那块 Canvas 同一个 20dp 侧边距，年份才对得上轴的两端
                .padding(horizontal = AXIS_SIDE_PADDING)
                .graphicsLayer {
                    alpha = ((introProgress() - 0.35f) / 0.35f).coerceIn(0f, 1f)
                }
                // 整块对 TalkBack 隐身：年份是给看得见的人的刻度说明，
                // 而卡片本身已经播报「专辑名，发行于 X，共 N 首」
                .clearAndSetSemantics { }
        ) {
            val head = playheadFraction().coerceIn(0f, 1f)
            val index = activeIndex().coerceIn(0, SwiftieErasData.STAGE.lastIndex)
            val ink = labelInk[index] ?: swiftieLabelInk(index).also { labelInk[index] = it }
            val first = yearLabels[0]
            val last = yearLabels[1]
            // 各自**以轴端点为中心**：一半落进 20dp 侧边距里，那块地方本来是空的。
            // 左对齐到 0 会把年份推进第一格里，看着像是在标 TS1 而不是标起点
            drawText(
                textLayoutResult = first,
                color = ink.first(),
                topLeft = Offset(
                    x = -first.size.width / 2f,
                    y = (size.height - first.size.height) / 2f
                )
            )
            drawText(
                textLayoutResult = last,
                // 终点年份走到最后才上色，和 TS 标签同一条规则
                color = if (head >= 1f) ink.last() else DESATURATED,
                topLeft = Offset(
                    x = size.width - last.size.width / 2f,
                    y = (size.height - last.size.height) / 2f
                )
            )
        }
    }
}
