package com.tracktosearch.ui.screen.dailystamp

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Color as AndroidColor
import android.graphics.Picture
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.SaveAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas as GraphicsCanvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.draw
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.tracktosearch.R
import com.tracktosearch.ui.component.SaveToAlbumResult
import com.tracktosearch.ui.screen.splash.QuoteSeal
import com.tracktosearch.ui.screen.splash.SplashPalette
import com.tracktosearch.ui.screen.splash.grainBrush
import com.tracktosearch.ui.util.showToast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 日签卡片浮层。
 *
 * 是页面自己 Box 里的一层，不是 Dialog：Dialog 会开一个新窗口，日历被系统按对话框
 * 处理（另一套边距、另一套暗化），卡片就不再像从那一格里升起来的。
 *
 * 关掉动作有三个：点卡片外、按返回、以及切月（切月由 ViewModel 顺手清掉选中）。
 * 卡片本身吃掉点击，否则点在卡上会穿到背后的关闭区。
 *
 * 左右拖动翻到相邻的那天，只在当月能打开的日子之间走。手势用
 * [detectHorizontalDragGestures]，它自带横向的 touch slop，竖直方向不会误触发。
 */
@Composable
internal fun DailyStampCardOverlay(
    card: DailyStampCardUi?,
    palette: SplashPalette,
    openableDates: List<LocalDate>,
    onSelect: (LocalDate) -> Unit,
    onDismiss: () -> Unit,
    onQuoteClick: (tmdbId: Int, mediaType: String, title: String, year: Int, posterUrl: String) -> Unit,
) {
    // 退场那一帧 card 已经是 null，留住上一张才有东西可淡出
    var retained by remember { mutableStateOf<DailyStampCardUi?>(null) }
    LaunchedEffect(card) { if (card != null) retained = card }
    val content = card ?: retained
    val visible = card != null

    BackHandler(enabled = visible) { onDismiss() }

    val interactionSource = remember { MutableInteractionSource() }
    Box(modifier = Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(220)),
            exit = fadeOut(tween(180)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(scrimColor(palette))
                    .clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        onClick = onDismiss,
                    )
            )
        }
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(240)) + scaleIn(tween(280, easing = CardEasing), initialScale = 0.93f),
            exit = fadeOut(tween(160)) + scaleOut(tween(200), targetScale = 0.96f),
            modifier = Modifier.align(Alignment.Center),
        ) {
            if (content != null) {
                CardStack(
                    card = content,
                    palette = palette,
                    openableDates = openableDates,
                    onSelect = onSelect,
                    onQuoteClick = onQuoteClick,
                )
            }
        }
    }
}

/**
 * 卡片 + 底下那一排动作。
 *
 * 动作按钮在卡片外面，这样导出的图里只有卡面本身——把「保存」两个字也存进相册里
 * 是最容易犯的错。
 */
@Composable
private fun CardStack(
    card: DailyStampCardUi,
    palette: SplashPalette,
    openableDates: List<LocalDate>,
    onSelect: (LocalDate) -> Unit,
    onQuoteClick: (tmdbId: Int, mediaType: String, title: String, year: Int, posterUrl: String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val picture = remember { Picture() }
    val dragX = remember { Animatable(0f) }
    var busy by remember { mutableStateOf(false) }
    // 换到另一天时把拖动位移抹平，否则新卡片会歪在上一次松手的位置
    LaunchedEffect(card.date) { dragX.snapTo(0f) }

    val onPosterClick = {
        onQuoteClick(card.tmdbId, card.mediaType, card.title, card.year, card.posterUrl)
    }

    /** 把当前卡面重放成位图交给保存/分享，两条路都用同一张软件位图 */
    val capture: suspend () -> Bitmap = { captureCardPicture(picture) }

    Column(
        modifier = Modifier.padding(horizontal = 26.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .graphicsLayer { translationX = dragX.value }
                .pointerInput(card.date, openableDates) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            val target = neighbour(card.date, openableDates, dragX.value)
                            scope.launch {
                                if (target != null) onSelect(target)
                                dragX.animateTo(0f, tween(220, easing = CardEasing))
                            }
                        },
                        onDragCancel = {
                            scope.launch { dragX.animateTo(0f, tween(220)) }
                        },
                    ) { _, delta ->
                        // 位移打三折：卡片是被「掀」一下，不是跟着手指整张走
                        scope.launch { dragX.snapTo(dragX.value + delta * 0.34f) }
                    }
                }
                .shadow(20.dp, RoundedCornerShape(11.dp), clip = false)
        ) {
            Box(
                modifier = Modifier.drawWithContent {
                    // 卡面先录进 Picture，再把这一份回放到屏幕上：屏幕上的和导出的是
                    // 同一串绘制指令，不会出现「存下来的和看到的不一样」。
                    recordThenReplay(picture)
                }
            ) {
                DailyStampCard(
                    card = card,
                    palette = palette,
                    onPosterClick = onPosterClick,
                )
            }
        }
        Spacer(Modifier.height(18.dp))
        CardActions(
            palette = palette,
            enabled = !busy,
            onDetail = onPosterClick,
            onSave = {
                if (!busy) {
                    busy = true
                    scope.launch {
                        saveCard(context, card.date, capture)
                        busy = false
                    }
                }
            },
            onShare = {
                if (!busy) {
                    busy = true
                    scope.launch {
                        shareCard(context, card.date, capture)
                        busy = false
                    }
                }
            },
        )
    }
}

/**
 * 卡面。
 *
 * 从上到下：票头、海报、台词、出处、一道虚线、印章。虚线是票根的撕口，
 * 它把「那天看到的那句话」和「那天的印」分成上下两半——上半是内容，下半是凭证。
 *
 * 海报和片名都可点，都进这部片的详情页：这张卡的下一步动作只有一个，
 * 不该逼用户去找唯一那个能点的地方。
 *
 * 颗粒和齿孔由 [drawCardTexture] 统一盖在最上面，它需要知道撕线落在卡面的哪个高度，
 * 而这个高度取决于台词有几行、有没有海报，只能等布局摆完才知道，所以撕线用
 * [onGloballyPositioned] 把自己的位置报上来。两个位置都取窗口坐标再相减，
 * 免得去猜中间隔了几层内边距。
 */
@Composable
private fun DailyStampCard(
    card: DailyStampCardUi,
    palette: SplashPalette,
    onPosterClick: () -> Unit,
) {
    val tick = remember(card.date) {
        card.date.format(DateTimeFormatter.ofPattern("yyyy.MM.dd", Locale.US))
    }
    val serial = remember(card.date) {
        // 当年的第几天，补零到三位：票据编号从来不写 No.7，写 No.007
        String.format(Locale.US, "No.%03d", card.date.dayOfYear)
    }
    // 和开屏是同一块噪点瓦片（固定种子），两屏的纸面纹理必须看起来是同一张纸
    val grain = remember { grainBrush() }
    var cardTop by remember { mutableFloatStateOf(Float.NaN) }
    var tearCenterY by remember { mutableFloatStateOf(Float.NaN) }

    Column(
        modifier = Modifier
            .widthIn(max = 380.dp)
            .clip(RoundedCornerShape(11.dp))
            // 质感层排在 background 左边，卡面底色才算在它的图层里：齿孔要擦掉的正是这层底色
            .drawWithContent {
                drawCardTexture(
                    palette = palette,
                    grain = grain,
                    notchCenterY = tearCenterY - cardTop,
                )
            }
            .background(palette.sheet)
            .onGloballyPositioned { cardTop = it.positionInRoot().y }
            .padding(horizontal = 22.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        TicketHeader(tick = tick, serial = serial, palette = palette)
        Spacer(Modifier.height(20.dp))
        CardPoster(card = card, palette = palette, onClick = onPosterClick)
        Spacer(Modifier.height(22.dp))
        card.lines.forEach { line ->
            CardLine(
                text = line,
                palette = palette,
                isEnglish = card.isEnglish,
                lineCount = card.lines.size,
            )
        }
        Spacer(Modifier.height(16.dp))
        CardSource(card = card, palette = palette, onClick = onPosterClick)
        Spacer(Modifier.height(22.dp))
        TearLine(
            palette = palette,
            modifier = Modifier.onGloballyPositioned {
                tearCenterY = it.positionInRoot().y + it.size.height / 2f
            },
        )
        Spacer(Modifier.height(20.dp))
        QuoteSeal(
            keyword = card.sealKeyword,
            latin = card.keywordLatin,
            sealLang = card.sealLang,
            palette = palette,
            // 卡面是 sheet 不是 paper，做旧那层要拿卡面色去盖才不留色差
            ground = palette.sheet,
        )
    }
}

/**
 * 票头：一行，左边日期，右边编号。
 *
 * 原先只有一行居中的日期刻度，读起来是装饰；票据的抬头总是日期在左、编号在右，
 * 摆成两端对齐这一行才像票根上印的东西。编号取当年的第几天：它和日期是同一个信息的
 * 两种写法，不用另存字段，而「今年的第 243 天」本身就有攒到年底的意思。
 *
 * 颜色走 [dateInk] 而不是 inkFaint，理由在那边。
 */
@Composable
private fun TicketHeader(tick: String, serial: String, palette: SplashPalette) {
    val ink = dateInk(palette)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = tick,
            color = ink,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 0.42.em,
        )
        Text(
            text = serial,
            color = ink,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            // 字距比日期收一半：编号是三位数字，撑开到 0.42em 会散成三个孤零零的字符
            letterSpacing = 0.2.em,
        )
    }
}

/**
 * 卡片上的海报，和开屏一样做成相纸装裱：奶油色卡纸 + 压一层纸色。
 *
 * 压色不是为了好看而已：未处理的彩色海报贴在暖纸卡面上像硬插进来的一块图，
 * 压掉一点饱和度之后它才像原本就印在这张卡上。
 *
 * 卡纸内侧还有一条发丝线：没有它海报是贴在卡纸上的另一张纸，有了它才像陷进卡纸
 * 开出来的窗里。
 */
@Composable
private fun CardPoster(
    card: DailyStampCardUi,
    palette: SplashPalette,
    onClick: () -> Unit,
) {
    if (card.poster == null) return
    val tintColor = if (palette.isDark) Color(0xFF16100B) else palette.paper
    val tintAlpha = if (palette.isDark) 0.22f else 0.14f
    // 暗色主题的 ink 本身是浅色，同一条线在两套主题下一边是压痕、一边是高光，
    // 都能把海报和卡纸分开，不必为此另兑颜色
    val hairline = palette.ink.copy(alpha = if (palette.isDark) 0.34f else 0.22f)
    val interactionSource = remember { MutableInteractionSource() }
    val context = LocalContext.current
    val posterRequest = remember(card.poster, context) {
        ImageRequest.Builder(context)
            .data(card.poster)
            // 导出会在软件 Canvas 上重放卡面，硬件 Bitmap 无法参与这次绘制。
            .allowHardware(false)
            .build()
    }
    Box(
        modifier = Modifier
            .size(width = 122.dp, height = 183.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(palette.cream)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .padding(5.dp)
    ) {
        AsyncImage(
            model = posterRequest,
            contentDescription = card.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(3.dp))
                .drawWithContent {
                    drawContent()
                    drawRect(color = tintColor, alpha = tintAlpha, blendMode = BlendMode.Multiply)
                    // 1 物理像素的发丝线，画在开窗内沿。整条往里收半像素：描边是沿路径
                    // 居中长的，贴着边画会有一半落在上面那道圆角裁切之外，只剩半条。
                    drawRoundRect(
                        color = hairline,
                        topLeft = Offset(0.5f, 0.5f),
                        size = Size(size.width - 1f, size.height - 1f),
                        cornerRadius = CornerRadius(3.dp.toPx() - 0.5f),
                        style = Stroke(width = 1f),
                    )
                }
        )
    }
}

/** 单行台词。字号随行数递减，保证 4 行也不触发自动折行——换行位置是排版的一部分 */
@Composable
private fun CardLine(
    text: String,
    palette: SplashPalette,
    isEnglish: Boolean,
    lineCount: Int,
) {
    val fontSize = when {
        isEnglish && lineCount >= 4 -> 15f
        isEnglish && lineCount == 3 -> 16f
        isEnglish -> 17f
        lineCount >= 4 -> 16.5f
        lineCount == 3 -> 18f
        else -> 19.5f
    }
    val lineHeightFactor = if (isEnglish) 1.58f else if (lineCount >= 4) 1.60f else 1.64f
    Text(
        text = text,
        modifier = Modifier.fillMaxWidth(),
        color = palette.ink,
        fontSize = fontSize.sp,
        lineHeight = (fontSize * lineHeightFactor).sp,
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.Medium,
        fontStyle = if (isEnglish) FontStyle.Italic else FontStyle.Normal,
        letterSpacing = if (isEnglish) 0.006.em else 0.012.em,
        textAlign = TextAlign.Center,
    )
}

/** 出处行：破折号 + 书名号包起的片名 + 退一档字号的年份，整行可点进详情页 */
@Composable
private fun CardSource(
    card: DailyStampCardUi,
    palette: SplashPalette,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val text = buildAnnotatedString {
        append(EM_DASH)
        append(' ')
        append(card.titleWrap.first)
        append(card.title)
        append(card.titleWrap.second)
        append(' ')
        // 年份和片名同色，只退一档字号。原先是把 inkSoft 再压到 70% 透明度，那样压在卡面上
        // 只有 2.84:1（暗色 3.34:1）；而 inkSoft 本身要够 4.5:1 就得留在 0.9，再乘掉三成
        // 必然不合格，调透明度救不回来。注解感交给字号和括号，开屏那层同样处理。
        withStyle(SpanStyle(fontSize = 10.5.sp)) {
            append("(${card.year})")
        }
    }
    Text(
        text = text,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
        color = palette.inkSoft,
        fontSize = 12.5.sp,
        letterSpacing = 0.1.em,
        textAlign = TextAlign.Center,
    )
}

/** 票根的撕口：一道虚线，不是实线也不是 Divider——实线会把卡片切成两张 */
@Composable
private fun TearLine(palette: SplashPalette, modifier: Modifier = Modifier) {
    val dash = remember { PathEffect.dashPathEffect(floatArrayOf(6f, 7f), 0f) }
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(1.dp)
    ) {
        drawLine(
            color = palette.inkFaint.copy(alpha = 0.55f),
            start = Offset(0f, size.height / 2f),
            end = Offset(size.width, size.height / 2f),
            strokeWidth = size.height,
            pathEffect = dash,
        )
    }
}

/**
 * 卡面的质感层：颗粒 + 撕线两端的齿孔。
 *
 * 两件事的先后是有讲究的，所以它们必须在同一次绘制里排队，不能各自散在子元素上。
 *
 * 颗粒盖在卡片内容之上，印章也在下面。印章的做旧是拿卡面底色按噪点盖回去的
 * （见 SealInk 的 KDoc）：颗粒要是只铺在底色上、印章画在颗粒之上，那些咬痕盖回去的就是
 * 干净的底色，看着比周围还新，正好和做旧反着来。
 *
 * 齿孔又排在颗粒之后。它是把像素擦掉，擦完再铺一层颗粒等于把洞填回去。
 */
private fun ContentDrawScope.drawCardTexture(
    palette: SplashPalette,
    grain: Brush,
    notchCenterY: Float,
) {
    val punch = notchPunchesThrough
    val nativeCanvas = drawContext.canvas.nativeCanvas
    // DstOut 擦的是「当前图层」的像素。不先开一层离屏的，卡片背后的压暗层和日历
    // 会一起被擦穿；开了之后擦出来的洞只是这一层透了，底下的东西照原样露出来。
    val layer = if (punch) {
        nativeCanvas.saveLayer(0f, 0f, size.width, size.height, null)
    } else {
        0
    }
    drawContent()
    drawRect(brush = grain, alpha = GRAIN_ALPHA, blendMode = BlendMode.Multiply)
    drawNotches(palette = palette, centerY = notchCenterY, punch = punch)
    if (punch) nativeCanvas.restoreToCount(layer)
}

/**
 * 撕线两端的半圆缺口。
 *
 * 首选真挖穿：[BlendMode.DstOut] 把离屏层里的像素擦掉，缺口透出卡片背后的东西，
 * 这才是票根从票上撕下来的样子。
 *
 * 兜底是拿 [SplashPalette.paper] 画实心半圆盖上去。卡片底下就是 paper 底，盖上去和挖穿
 * 看的是同一个结果，只是那两块不透明——导出的 PNG 上区别更小，缺口本来就落在
 * 透明背景边上。什么时候走兜底见 [notchPunchesThrough]。
 *
 * [centerY] 是撕线在卡面里的高度，布局还没报上来时是 NaN，那一帧先不画：
 * 齿孔的位置错了比暂时没有齿孔更难看。
 */
private fun DrawScope.drawNotches(palette: SplashPalette, centerY: Float, punch: Boolean) {
    if (centerY.isNaN() || centerY <= 0f || centerY >= size.height) return
    val radius = NOTCH_RADIUS.toPx()
    val color = if (punch) Color.Black else palette.paper
    // 擦洞时颜色本身没有意义，只有 alpha 参与运算，取纯黑是为了不让人误以为它是配色
    val blendMode = if (punch) BlendMode.DstOut else BlendMode.SrcOver
    drawCircle(color = color, radius = radius, center = Offset(0f, centerY), blendMode = blendMode)
    drawCircle(
        color = color,
        radius = radius,
        center = Offset(size.width, centerY),
        blendMode = blendMode,
    )
}

/**
 * 这台设备上 DstOut 到底挖不挖得穿。
 *
 * 在 1×1 的软件位图上先试一刀，走的是和真齿孔完全相同的 API：开一层离屏、涂满不透明、
 * 再用 DstOut 擦一遍，读回来的 alpha 归零才算挖得动。SealInk 当初放弃 DstOut 就是因为
 * 抠洞依赖离屏图层，而日签要在软件 Canvas 上重放一遍导出，多一层图层就多一处两条路径
 * 可能不一致的地方；现在屏幕和导出回放的已经是同一份 Picture，那个顾虑只剩「这一层
 * 到底拿不拿得到」，正好可以在软件画布上验——导出走的就是软件画布。
 *
 * 探测结果全进程留一份：它只跟设备的绘制实现有关，不会中途变。
 */
private val notchPunchesThrough: Boolean by lazy { probeNotchPunch() }

private fun probeNotchPunch(): Boolean = try {
    val probe = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
    try {
        val nativeCanvas = AndroidCanvas(probe)
        CanvasDrawScope().draw(
            density = Density(1f),
            layoutDirection = LayoutDirection.Ltr,
            canvas = GraphicsCanvas(nativeCanvas),
            size = Size(1f, 1f),
        ) {
            val layer = nativeCanvas.saveLayer(0f, 0f, 1f, 1f, null)
            drawRect(color = Color.Black)
            drawRect(color = Color.Black, blendMode = BlendMode.DstOut)
            nativeCanvas.restoreToCount(layer)
        }
        AndroidColor.alpha(probe.getPixel(0, 0)) == 0
    } finally {
        probe.recycle()
    }
} catch (_: Exception) {
    false
}

/**
 * 影片详情、保存、分享：卡片外的三枚小按钮，做成描边药丸，不用 Material 的填充按钮
 * 抢卡片的注意力。
 *
 * 详情排在最左边：海报和片名本来就能点进详情，但那两处没有任何可点的样式提示，
 * 摆一枚明写着「电影详情」的按钮才是给第一次用的人看的。
 *
 * 用 FlowRow 而不是 Row：三个标签在英文和日文下比中文长得多，窄屏上排不下时让第三枚
 * 折到第二行，而不是把三枚一起挤到看不清。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CardActions(
    palette: SplashPalette,
    enabled: Boolean,
    onDetail: () -> Unit,
    onSave: () -> Unit,
    onShare: () -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ActionPill(
            icon = Icons.Rounded.Movie,
            label = stringResource(R.string.daily_stamp_detail),
            palette = palette,
            enabled = enabled,
            onClick = onDetail,
        )
        ActionPill(
            icon = Icons.Rounded.SaveAlt,
            label = stringResource(R.string.daily_stamp_save),
            palette = palette,
            enabled = enabled,
            onClick = onSave,
        )
        ActionPill(
            icon = Icons.Rounded.IosShare,
            label = stringResource(R.string.daily_stamp_share),
            palette = palette,
            enabled = enabled,
            onClick = onShare,
        )
    }
}

@Composable
private fun ActionPill(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    palette: SplashPalette,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val tint = if (enabled) palette.ink else palette.inkFaint
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(palette.sheet.copy(alpha = 0.86f))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            )
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(15.dp),
        )
        Text(
            text = label,
            color = tint,
            fontSize = 12.sp,
            letterSpacing = 0.08.em,
        )
    }
}

/**
 * 松手时该翻到哪天。
 *
 * 位移不够就留在原地。往右拖是往前翻（把上一天从左边拉过来），往左拖是往后翻，
 * 和翻纸质日历的方向一致。到头了就没有下一张，不做循环——循环会让人以为月份变了。
 */
private fun neighbour(
    current: LocalDate,
    openable: List<LocalDate>,
    dragX: Float,
): LocalDate? {
    if (kotlin.math.abs(dragX) < SWIPE_COMMIT_PX) return null
    val index = openable.indexOf(current)
    if (index < 0) return null
    val target = if (dragX > 0) index - 1 else index + 1
    return openable.getOrNull(target)
}

/**
 * 把卡面录进 [picture]，再把录下来的这一份回放到屏幕上。
 *
 * 这么绕一下是为了让屏幕和导出共用同一串绘制指令：导出是拿同一个 Picture 在软件
 * Canvas 上再放一遍（见 [captureCardPicture]），两边一定长得一样。
 *
 * 之前走的是 GraphicsLayer 快照：内容先录进平台图层，再向图层要像素，部分 Android 9
 * 以上的设备会返回尺寸正常、内容全白的位图。Picture 只是一串指令，光栅化在哪张画布上
 * 发生由我们自己决定，白图问题从根上没有了。
 *
 * 尺寸还没量出来的那一帧照常画内容，只是不录——录一张 0×0 的 Picture 会让导出拿到空图。
 */
private fun ContentDrawScope.recordThenReplay(picture: Picture) {
    val width = size.width.toInt()
    val height = size.height.toInt()
    if (width <= 0 || height <= 0) {
        drawContent()
        return
    }
    val recordingCanvas = GraphicsCanvas(picture.beginRecording(width, height))
    // draw 会把当前 DrawScope 的画布临时换成录制画布，drawContent 于是画进了 Picture
    draw(this, layoutDirection, recordingCanvas, size) {
        this@recordThenReplay.drawContent()
    }
    picture.endRecording()
    drawIntoCanvas { canvas -> canvas.nativeCanvas.drawPicture(picture) }
}

/**
 * 把最近一次录下来的卡面重放成位图。
 *
 * 显式建一张 ARGB_8888 位图，用 android.graphics.Canvas 回放 Picture：整条路都是软件
 * 光栅化，不经过 GPU 快照，也不依赖任何平台图层。
 *
 * 跑在主线程上是因为 Picture 每帧都会被重新录制，换到后台线程读它就会和绘制撞上。
 * 后面的 PNG 压缩另有 IO 线程，那一段才是耗时的。
 */
private suspend fun captureCardPicture(picture: Picture): Bitmap =
    withContext(Dispatchers.Main.immediate) {
        require(picture.width > 0 && picture.height > 0) {
            "Daily stamp card has not been drawn yet"
        }
        Bitmap.createBitmap(picture.width, picture.height, Bitmap.Config.ARGB_8888)
            .also { bitmap -> AndroidCanvas(bitmap).drawPicture(picture) }
    }

private suspend fun saveCard(
    context: android.content.Context,
    date: LocalDate,
    capture: suspend () -> Bitmap,
) {
    val message = try {
        val bitmap = capture()
        try {
            when (saveStampCard(context, bitmap, date)) {
                SaveToAlbumResult.SAVED -> R.string.daily_stamp_saved
                SaveToAlbumResult.ALREADY_EXISTS -> R.string.daily_stamp_already_saved
                SaveToAlbumResult.FAILED -> R.string.daily_stamp_save_failed
            }
        } finally {
            bitmap.recycle()
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        // 位图录制失败（低内存、卡面还没画完）也只提示，不崩在一次保存上
        R.string.daily_stamp_save_failed
    }
    context.showToast(context.getString(message))
}

/**
 * 分享：先存相册，再把相册里那一项分享出去，相册不成才退到 FileProvider。
 * 三级回落的理由见 [stampShareSource]。
 *
 * 存进相册之后顺手提示一句：这一步是替用户做的决定，不说他不知道图已经留下来了。
 * 退到临时文件时不提示——那份留不住，说了是误导。两条路都失败、或者根本没有能接收
 * 图片的应用时提示分享失败，绝不静默收场。
 */
private suspend fun shareCard(
    context: android.content.Context,
    date: LocalDate,
    capture: suspend () -> Bitmap,
) {
    val source = try {
        val bitmap = capture()
        try {
            stampShareSource(context, bitmap, date)
        } finally {
            bitmap.recycle()
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        // 卡面录制失败、相册和临时文件都写不进去
        context.showToast(context.getString(R.string.daily_stamp_share_failed))
        return
    }
    if (source is StampShareSource.Album) {
        context.showToast(
            context.getString(
                if (source.alreadyExisted) {
                    R.string.daily_stamp_already_saved
                } else {
                    R.string.daily_stamp_saved
                }
            )
        )
    }
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "image/png"
        putExtra(Intent.EXTRA_STREAM, source.uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    try {
        context.startActivity(
            Intent.createChooser(intent, context.getString(R.string.daily_stamp_share_chooser))
        )
    } catch (_: Exception) {
        // 没有能接收图片的应用
        context.showToast(context.getString(R.string.daily_stamp_share_failed))
    }
}

/** 浮层的压暗程度：卡片是暖纸色，压得太浅就浮不起来，太深又像对话框 */
private fun scrimColor(palette: SplashPalette): Color =
    if (palette.isDark) Color(0xCC120C08) else Color(0x993C2212)

/**
 * 票头那一行的墨色。
 *
 * 不走 inkFaint：那是画线的颜色，压在卡面上只有 1.6:1（暗色 2.2:1），日期和编号
 * 再装饰也是字，得看得见。这里按 ink 另兑一档——明色 0.76、暗色 0.44，压在 sheet 上
 * 3.71:1 / 3.53:1，过了非正文文字那条 3:1 的线，又还比出处弱一档。
 *
 * 兑在这个文件里而不是往共享色板加一档：inkFaint 同时是撕口虚线、日历格线和禁用图标的
 * 颜色，为了这一行把它压深，那几处细线会立刻变成描边。开屏台词层顶部那行日期是同样的
 * 处理，两边各自兑，色板不动。
 */
private fun dateInk(palette: SplashPalette): Color =
    palette.ink.copy(alpha = if (palette.isDark) 0.44f else 0.76f)

private const val EM_DASH = "—"

/**
 * 颗粒层的不透明度。
 *
 * 卡面只有一块 sheet 底色，纯色印出来是塑料片；这一层把纸的粗糙度还回来。
 * 0.08 是「看不出有一层东西、但把纯色破掉了」的位置，再重一档字缘就开始发毛。
 *
 * 它同时是可读性的一部分：颗粒盖在文字之上，Multiply 之后每个像素被乘上
 * 0.958~0.980（瓦片灰度 120~191，见 grainBrush），也就是整卡最多压暗 4%。台词
 * 6.13:1、片名和年份 4.91:1、票头 3.64:1（明色实景，已经把这一层算进去）都还留着余量，
 * 但这个值往上调就是在吃这点余量，要动先重算一遍对比度。
 */
private const val GRAIN_ALPHA = 0.08f

/** 齿孔半径。半圆露在卡面上的那一半就是这个尺寸，再大就从票根变成信封的开窗 */
private val NOTCH_RADIUS: Dp = 7.dp

/** 松手翻页的位移门槛（像素）。位移本身打了三折，这里对应手指约走 130px */
private const val SWIPE_COMMIT_PX = 44f

/** 卡片起落用的缓动：快进慢出，像一张纸被托起来 */
private val CardEasing = CubicBezierEasing(0.2f, 0.75f, 0.28f, 1f)
