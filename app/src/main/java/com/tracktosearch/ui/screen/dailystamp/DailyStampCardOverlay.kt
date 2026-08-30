package com.tracktosearch.ui.screen.dailystamp

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Color as AndroidColor
import android.graphics.Picture
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.SaveAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
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
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.draw
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
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
import androidx.compose.ui.util.lerp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Scale
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
import kotlin.math.abs
import kotlin.random.Random

/**
 * 日签卡片浮层。
 *
 * 是页面自己 Box 里的一层，不是 Dialog：Dialog 会开一个新窗口，日历被系统按对话框
 * 处理（另一套边距、另一套暗化），卡片就不再像从那一格里升起来的。
 *
 * 卡片排成一排：中间那张正对着看，左右两张缩小、往里转、压低一点，露出靠内的那条边。
 * 那两张侧卡本身就是「还能往两边翻」的提示——比在卡上画箭头或者写一行「左右滑动」
 * 都轻，因为它们就是接下来要看的东西。左右滑、或者直接点侧卡，都能翻过去。
 *
 * 关掉动作有三个：点卡片之外的空处、按返回、以及切月（切月由 ViewModel 顺手清掉选中）。
 */
@Composable
internal fun DailyStampCardOverlay(
    sheet: DailyStampSheet?,
    sheets: Map<LocalDate, DailyStampSheet>,
    palette: SplashPalette,
    openableDates: List<LocalDate>,
    onSelect: (LocalDate) -> Unit,
    onDismiss: () -> Unit,
    onQuoteClick: (tmdbId: Int, mediaType: String, title: String, year: Int, posterUrl: String) -> Unit,
) {
    // 退场那一帧 sheet 已经是 null，留住上一张才有东西可淡出
    var retained by remember { mutableStateOf<DailyStampSheet?>(null) }
    LaunchedEffect(sheet) { if (sheet != null) retained = sheet }
    val content = sheet ?: retained
    val visible = sheet != null

    BackHandler(enabled = visible) { onDismiss() }

    val interactionSource = remember { MutableInteractionSource() }
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        // 窗口有多高，下面那层滚动内容至少撑这么高：装得下时没有滚动距离，卡片照旧居中；
        // 装不下才真的滚起来。见下面那个 heightIn。
        val viewport = maxHeight
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(220)),
            exit = fadeOut(tween(180)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(scrimColor(palette))
            )
        }
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(240)) + scaleIn(tween(280, easing = CardEasing), initialScale = 0.93f),
            exit = fadeOut(tween(160)) + scaleOut(tween(200), targetScale = 0.96f),
        ) {
            if (content != null) {
                // 卡片那一排加底下的动作，合起来可能比窗口还高：横屏、分屏、小折叠屏内屏
                // 都会。那时整块跟着滚，动作行才不至于被挤到屏幕外边点不着——日历页
                // 本身也是这么处理的（见 DailyStampScreen 的 verticalScroll）。
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = viewport)
                            // 点空处关掉。关闭区跟着滚动内容走，不再留在压暗层上：
                            // 压暗层被这一层整个盖住了，留在那儿就收不到点击。
                            // 落在卡面上的那一下由卡片自己吃掉，见 CarouselPage。
                            .clickable(
                                interactionSource = interactionSource,
                                indication = null,
                                onClick = onDismiss,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        CardCarousel(
                            current = content.date,
                            dates = openableDates,
                            sheets = sheets,
                            palette = palette,
                            onSelect = onSelect,
                            onQuoteClick = onQuoteClick,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 一排卡片 + 底下那一排动作。
 *
 * 动作按钮在卡片外面，这样导出的图里只有卡面本身——把「保存」两个字也存进相册里
 * 是最容易犯的错。
 *
 * 翻页交给 [HorizontalPager] 而不是自己接横向拖动：它自带甩动、吸附、触摸 slop 和
 * 无障碍的翻页语义，自己搓一套只会少几样。选中那天由两个方向共同维持——从日历点进来时
 * 跳到对应那一页，滑停之后把那天写回去，两条各自判断「已经是那一页/那一天」就不会来回抖。
 *
 * 卡片带的那一圈高度是钉死的（[bandHeight]）而不是随内容长：台词有两到四行，未来那天
 * 的卡片又是另一个高度，跟着最高那一页长会让整排卡在翻页落定时忽然上下跳一下。
 */
@Composable
private fun CardCarousel(
    current: LocalDate,
    dates: List<LocalDate>,
    sheets: Map<LocalDate, DailyStampSheet>,
    palette: SplashPalette,
    onSelect: (LocalDate) -> Unit,
    onQuoteClick: (tmdbId: Int, mediaType: String, title: String, year: Int, posterUrl: String) -> Unit,
) {
    if (dates.isEmpty()) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    val pagerState = rememberPagerState(
        initialPage = dates.indexOf(current).coerceAtLeast(0),
        pageCount = { dates.size },
    )
    // 每一页各录一份卡面，保存/分享取的是当前那一页的那份。页被回收时顺手删掉，
    // 免得翻过一个月之后这张表里留着三十份 Picture。
    val pictures = remember { mutableMapOf<LocalDate, Picture>() }

    // 从日历点进来的那一下：直接跳过去。卡片本来就在淡入，没有滑动过程可看
    LaunchedEffect(current, dates) {
        val target = dates.indexOf(current)
        if (target >= 0 && target != pagerState.currentPage) pagerState.scrollToPage(target)
    }
    // 滑停之后把那天写回去：日历那边的选中格、以及关掉再打开时的初始页都看它
    val selected by rememberUpdatedState(current)
    LaunchedEffect(pagerState, dates) {
        snapshotFlow { pagerState.settledPage }.collect { page ->
            // 换月和退场那会儿，[dates] 已经换成新一列而 pager 还停在旧下标上，选中那天
            // 也不在这一列里了。这时写回去等于替用户随手翻开新月份里同下标的那天。
            if (selected !in dates) return@collect
            dates.getOrNull(page)?.let { if (it != selected) onSelect(it) }
        }
    }

    val currentDate = dates.getOrNull(pagerState.currentPage)
    val line = currentDate?.let { sheets[it] } as? DailyStampSheet.Line
    // 未来那天的卡片没有可存的东西，详情按钮更是直接报答案。整排按钮淡掉但位置留着：
    // 用 AnimatedVisibility 把它撤出布局，整排卡会跟着上下挪一截
    val actionAlpha by animateFloatAsState(
        targetValue = if (line != null) 1f else 0f,
        animationSpec = tween(durationMillis = 180),
        label = "dailyStampActionAlpha",
    )
    val onDetail = {
        line?.card?.let { onQuoteClick(it.tmdbId, it.mediaType, it.title, it.year, it.posterUrl) }
        Unit
    }
    /** 把当前那一页的卡面重放成位图交给保存/分享，两条路都用同一张软件位图 */
    val capture: suspend () -> Bitmap = {
        val picture = currentDate?.let { pictures[it] }
        requireNotNull(picture) { "Daily stamp card has not been recorded yet" }
        captureCardPicture(picture)
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        HorizontalPager(
            state = pagerState,
            // 左右各留一条：侧卡就从这里探出来
            contentPadding = PaddingValues(horizontal = SIDE_PEEK),
            verticalAlignment = Alignment.CenterVertically,
            // 相邻两页提前组合好，滑过去时不会先看到一张空卡再长出内容
            beyondViewportPageCount = 1,
            modifier = Modifier.fillMaxWidth().height(bandHeight()),
        ) { page ->
            val date = dates[page]
            val pageSheet = sheets[date] ?: return@HorizontalPager
            CarouselPage(
                sheet = pageSheet,
                palette = palette,
                turn = { pageTurn(pagerState.currentPage, pagerState.currentPageOffsetFraction, page) },
                pictures = pictures,
                onPosterClick = onDetail,
                onSideClick = {
                    if (page != pagerState.currentPage) {
                        scope.launch { pagerState.animateScrollToPage(page) }
                    }
                },
            )
        }
        Spacer(Modifier.height(18.dp))
        Box(modifier = Modifier.graphicsLayer { alpha = actionAlpha }) {
            CardActions(
                palette = palette,
                enabled = line != null && !busy,
                onDetail = onDetail,
                onSave = {
                    val date = line?.card?.date
                    if (!busy && date != null) {
                        busy = true
                        scope.launch {
                            saveCard(context, date, capture)
                            busy = false
                        }
                    }
                },
                onShare = {
                    val date = line?.card?.date
                    if (!busy && date != null) {
                        busy = true
                        scope.launch {
                            shareCard(context, date, capture)
                            busy = false
                        }
                    }
                },
            )
        }
    }
}

/**
 * 排里的一页。
 *
 * [turn] 报的是这一页离正中间有多远：0 是正对着，±1 是左右那一张，滑动过程里是中间值，
 * 所有形变都从它算出来，滑到哪儿卡片就转到哪儿，不是滑完才跳一下。传进来的是个函数而不是
 * 算好的数：这个值每一帧都在变，在组合里读它会让三页卡片每帧重组一次，而放在
 * graphicsLayer 里读只让这一层重画。
 *
 * 缩放和旋转的支点放在靠内那条边上：侧卡于是绕着贴着中间卡的那条边往里转，像一叠
 * 摊开的票根；支点放在中心的话它会整张往外缩，看着是并排三张而不是叠在一起的三张。
 */
@Composable
private fun CarouselPage(
    sheet: DailyStampSheet,
    palette: SplashPalette,
    turn: () -> Float,
    pictures: MutableMap<LocalDate, Picture>,
    onPosterClick: () -> Unit,
    onSideClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                val offset = turn()
                val away = abs(offset)
                val scale = lerp(1f, SIDE_SCALE, away)
                scaleX = scale
                scaleY = scale
                alpha = lerp(1f, SIDE_ALPHA, away)
                translationY = away * SIDE_DROP.toPx()
                // 往中间收一点：露出来的是靠内那条边，卡片才像叠着而不是排着
                translationX = -offset * SIDE_PULL.toPx()
                cameraDistance = CAMERA_DISTANCE * density
                rotationY = -offset * SIDE_TURN_DEG
                transformOrigin = TransformOrigin(if (offset >= 0f) 0f else 1f, 0.5f)
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                // 点侧卡翻过去；点正中间那张什么也不做，但这一下要吃掉——否则会穿到
                // 背后的关闭区，点自己正在看的卡片反而把它关了。
                // 只盖住卡面，不盖满整页：卡片比这一圈矮，上下剩出来的那段空处照旧该
                // 点得着背后的关闭区
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onSideClick,
                )
                .shadow(20.dp, RoundedCornerShape(11.dp), clip = false)
        ) {
            when (sheet) {
                is DailyStampSheet.Line -> {
                    val picture = remember(sheet.card.date) { Picture() }
                    DisposableEffect(sheet.card.date) {
                        pictures[sheet.card.date] = picture
                        onDispose { pictures.remove(sheet.card.date) }
                    }
                    Box(
                        modifier = Modifier.drawWithContent {
                            // 卡面先录进 Picture，再把这一份回放到屏幕上：屏幕上的和导出的是
                            // 同一串绘制指令，不会出现「存下来的和看到的不一样」。
                            recordThenReplay(picture)
                        }
                    ) {
                        DailyStampCard(
                            card = sheet.card,
                            palette = palette,
                            onPosterClick = onPosterClick,
                        )
                    }
                }

                is DailyStampSheet.Latent -> LatentCard(latent = sheet, palette = palette)
            }
        }
    }
}

/**
 * 这一页离正中间有多远：0 正中，+1 右边那张，-1 左边那张。
 *
 * 夹到 ±1 之内：再远的页要么在屏幕外，要么已经被回收，让它们和最边上那张形变一致
 * 就够了，继续按真实距离算只会把它们缩成一个点。
 */
private fun pageTurn(currentPage: Int, offsetFraction: Float, page: Int): Float =
    ((page - currentPage) - offsetFraction).coerceIn(-1f, 1f)

/**
 * 卡片那一圈的高度。
 *
 * 钉死一个值而不是随内容长：翻页落定那一下，相邻两页的卡片高度不同会让整排跟着上下跳。
 *
 * 窗口高减去底下那排动作和上下留白，再夹进一个区间。下限按「一整张卡大致多高」定，
 * 窗口比这还矮时卡片就不再缩——缩下去只会把台词和印章切掉，而浮层本身是能滚的
 * （见 [DailyStampCardOverlay]），动作行不会因此被挤出屏幕。上限是防超高屏上卡片
 * 被拉成一条：卡面内容撑不满，多出来的都是空处。
 */
@Composable
private fun bandHeight(): Dp {
    val screenHeight = LocalConfiguration.current.screenHeightDp
    return (screenHeight - BAND_CHROME).dp.coerceIn(BAND_MIN, BAND_MAX)
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
    var cardCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var notchCenterY by remember { mutableFloatStateOf(Float.NaN) }

    Column(
        modifier = Modifier
            .widthIn(max = 380.dp)
            .clip(RoundedCornerShape(11.dp))
            // 质感层排在 background 左边，卡面底色才算在它的图层里：齿孔要擦掉的正是这层底色
            .drawWithContent {
                drawCardTexture(
                    palette = palette,
                    grain = grain,
                    notchCenterY = notchCenterY,
                )
            }
            .background(palette.sheet)
            .onGloballyPositioned { cardCoords = it }
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
            modifier = Modifier.onGloballyPositioned { tear ->
                notchCenterY = tearCenterIn(cardCoords, tear)
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

/**
 * 还没到那天的卡片。
 *
 * 骨架和 [DailyStampCard] 完全一样（票头、海报、台词、出处、撕口、印），一眼看得出是
 * 同一种票根，只是每一格都还没显影：
 * - 海报取 w92 那一档、解到 12 像素再放大。放大靠双线性插值，出来的就是一团糊掉的
 *   色块——留下的是那天的色调，不是那部片。不用 Modifier.blur：它在 API 30 及以下是
 *   空操作，靠它保密等于在老机器上把海报直接摊开。糊完再压一层自上而下加重的纱。
 * - 台词和出处是几行墨条，条数长短按日期播种（[latentBars]），同一天每次打开都一样。
 *   有形无字：看得出那儿有两行字，读不出是哪两行。
 * - 印只剩界格，印文没落下。
 *
 * 台词、片名、印文这些字段压根没带进这一层（见 [DailyStampSheet.Latent]）：不给看不是
 * 「先显示再遮住」——遮罩会随实现走样，不带出来才是真的不给看。
 */
@Composable
private fun LatentCard(latent: DailyStampSheet.Latent, palette: SplashPalette) {
    val tick = remember(latent.date) {
        latent.date.format(DateTimeFormatter.ofPattern("yyyy.MM.dd", Locale.US))
    }
    val serial = remember(latent.date) {
        String.format(Locale.US, "No.%03d", latent.date.dayOfYear)
    }
    val grain = remember { grainBrush() }
    val bars = remember(latent.date) { latentBars(latent.date) }
    var cardCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var notchCenterY by remember { mutableFloatStateOf(Float.NaN) }

    Column(
        modifier = Modifier
            .widthIn(max = 380.dp)
            .clip(RoundedCornerShape(11.dp))
            .drawWithContent {
                drawCardTexture(
                    palette = palette,
                    grain = grain,
                    notchCenterY = notchCenterY,
                )
            }
            .background(palette.sheet)
            .onGloballyPositioned { cardCoords = it }
            .padding(horizontal = 22.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        TicketHeader(tick = tick, serial = serial, palette = palette)
        Spacer(Modifier.height(20.dp))
        LatentPoster(url = latent.tinyPosterUrl, palette = palette)
        Spacer(Modifier.height(22.dp))
        bars.quote.forEachIndexed { index, row ->
            if (index > 0) Spacer(Modifier.height(12.dp))
            InkBars(widths = row, height = 11.dp, palette = palette)
        }
        Spacer(Modifier.height(18.dp))
        InkBars(widths = bars.source, height = 7.dp, palette = palette)
        Spacer(Modifier.height(22.dp))
        TearLine(
            palette = palette,
            modifier = Modifier.onGloballyPositioned { tear ->
                notchCenterY = tearCenterIn(cardCoords, tear)
            },
        )
        Spacer(Modifier.height(20.dp))
        LatentSeal(palette = palette)
        Spacer(Modifier.height(18.dp))
        Text(
            text = stringResource(R.string.daily_stamp_future_card),
            modifier = Modifier.fillMaxWidth(),
            color = palette.inkSoft,
            fontSize = 11.sp,
            lineHeight = 19.sp,
            fontFamily = FontFamily.Serif,
            letterSpacing = 0.08.em,
            textAlign = TextAlign.Center,
        )
    }
}

/** 糊掉的海报：小图放大 + 一层自上而下加重的纱，卡纸内沿那道发丝线照旧 */
@Composable
private fun LatentPoster(url: String?, palette: SplashPalette) {
    val context = LocalContext.current
    val request = remember(url, context) {
        url?.let { address ->
            ImageRequest.Builder(context)
                .data(address)
                .size(LATENT_DECODE_PX)
                .scale(Scale.FILL)
                // 导出用不到这张卡，但两张卡走同一套装裱代码，这里也不要硬件位图
                .allowHardware(false)
                .crossfade(false)
                .build()
        }
    }
    val veil = remember(palette) {
        Brush.verticalGradient(
            0f to Color.Transparent,
            0.42f to palette.sheet.copy(alpha = 0.20f),
            1f to palette.sheet.copy(alpha = 0.74f),
        )
    }
    val desaturate = remember {
        ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(LATENT_SATURATION) })
    }
    val hairline = palette.ink.copy(alpha = if (palette.isDark) 0.34f else 0.22f)
    Box(
        modifier = Modifier
            .size(width = 122.dp, height = 183.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(palette.cream)
            .padding(5.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(3.dp))
                .drawWithContent {
                    drawContent()
                    drawRect(brush = veil)
                    drawRoundRect(
                        color = hairline,
                        topLeft = Offset(0.5f, 0.5f),
                        size = Size(size.width - 1f, size.height - 1f),
                        cornerRadius = CornerRadius(3.dp.toPx() - 0.5f),
                        style = Stroke(width = 1f),
                    )
                }
        ) {
            if (request != null) {
                AsyncImage(
                    model = request,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    colorFilter = desaturate,
                    // 十几个像素放到一百多 dp，靠的就是这一档插值。显式写出来是因为默认值
                    // 要是哪天变成最近邻，糊掉的图会变成马赛克，反而把轮廓切得更清楚
                    filterQuality = FilterQuality.Low,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

/** 一行墨条，居中排开。有形无字：看得出那儿有一行字，读不出写的是什么 */
@Composable
private fun InkBars(widths: List<Dp>, height: Dp, palette: SplashPalette) {
    val color = palette.ink.copy(alpha = LATENT_BAR_ALPHA)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        widths.forEach { width ->
            Box(
                modifier = Modifier
                    .width(width)
                    .height(height)
                    .clip(RoundedCornerShape(1.5.dp))
                    .background(color)
            )
        }
    }
}

/**
 * 只有界格、没有印文的一枚印。
 *
 * 几何与 QuoteSeal 的方印一致（46dp 见方、圆角 3dp、印边 1.4dp、界格 4dp 内缩），只把
 * 两条线收到不足一半浓度，不铺印泥底、不做旧：这是一枚还没落下的印，不是一枚盖淡了的印。
 */
@Composable
private fun LatentSeal(palette: SplashPalette) {
    Canvas(modifier = Modifier.size(LATENT_SEAL_SIZE)) {
        val frame = 1.4.dp.toPx()
        drawRoundRect(
            color = palette.seal.copy(alpha = 0.30f),
            topLeft = Offset(frame / 2f, frame / 2f),
            size = Size(size.width - frame, size.height - frame),
            cornerRadius = CornerRadius((3.dp.toPx() - frame / 2f).coerceAtLeast(0f)),
            style = Stroke(frame),
        )
        val inner = 0.7.dp.toPx()
        val inset = 4.dp.toPx() + inner / 2f
        drawRoundRect(
            color = palette.seal.copy(alpha = 0.14f),
            topLeft = Offset(inset, inset),
            size = Size(size.width - inset * 2f, size.height - inset * 2f),
            cornerRadius = CornerRadius((1.5.dp.toPx() - inner / 2f).coerceAtLeast(0f)),
            style = Stroke(inner),
        )
    }
}

/**
 * 墨条的条数和长短，按 epochDay 播种。
 *
 * 播种是为了同一天每次打开都是同一副样子：随手 random 一下，同一张卡在滑过去滑回来
 * 之间会变形，那就不像「那天的字还没显出来」，而像一堆随机方块。
 *
 * 条数和字数无关，也不该有关：真台词有几个字是当天才该知道的事。
 */
private fun latentBars(date: LocalDate): LatentBars {
    val random = Random(date.toEpochDay())
    return LatentBars(
        quote = listOf(
            List(random.nextInt(6, 10)) { random.nextInt(9, 16).dp },
            List(random.nextInt(4, 8)) { random.nextInt(9, 16).dp },
        ),
        source = List(random.nextInt(3, 6)) { random.nextInt(7, 12).dp },
    )
}

private class LatentBars(val quote: List<List<Dp>>, val source: List<Dp>)

/**
 * 撕线中心在卡面坐标系里的纵坐标；卡片还没量到时是 NaN，齿孔那一步会跳过。
 *
 * 不用两次 positionInRoot() 相减：卡片现在坐在轮播页的 graphicsLayer 里，缩放和 rotationY
 * 都作用在根坐标上，相减得到的差值被 scaleY 乘过一遍（侧卡 0.84），而 drawNotches 拿它
 * 当卡面内的局部坐标用，齿孔会画偏。更麻烦的是 onGloballyPositioned 只在布局变化时回调，
 * 侧卡变成中间卡只改图层属性、不重新布局，偏掉的那个值就一直留着。
 * localPositionOf 会把中间这些变换逆掉，无论页面正处在哪一档形变，量出来的都是卡面内的位置。
 */
private fun tearCenterIn(card: LayoutCoordinates?, tear: LayoutCoordinates): Float =
    card?.localPositionOf(tear, Offset(0f, tear.size.height / 2f))?.y ?: Float.NaN

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

/**
 * 侧卡缩到多小。
 *
 * 底下这一族值一起决定「旁边那两张是同一叠里的下一张」这个观感，单调一个都会走味：
 * 缩放和透明度让侧卡退到后面，下沉让它像垫在底下那张，内收把露出来的那条边挪到靠中间
 * 一侧，rotationY 才是把纸面转过去的那一下。全都从 [pageTurn] 那个 0~±1 的量线性插出来，
 * 所以这些数字是「翻到底时的样子」，滑动过程中取的是中间值。
 */
private const val SIDE_SCALE = 0.84f

/** 侧卡淡到多少。再淡就不像下一张卡，像一层没关掉的残影 */
private const val SIDE_ALPHA = 0.62f

/** 侧卡下沉多少：正中那张才像被托在最上面 */
private val SIDE_DROP: Dp = 10.dp

/** 侧卡往中间收多少。露出来的要是靠内那条边，卡片才像叠着而不是并排排着 */
private val SIDE_PULL: Dp = 12.dp

/** 侧卡绕靠内那条边转多少度。配着 [CAMERA_DISTANCE] 调，单看这一个数没有意义 */
private const val SIDE_TURN_DEG = 18f

/**
 * 透视的相机距离，单位是「屏幕密度的倍数」（乘 density 之后才是 graphicsLayer 要的值）。
 *
 * 太近侧卡会被透视拉成一个夸张的梯形，像被掰弯了；太远就等于没转，[SIDE_TURN_DEG] 也
 * 跟着白给。16 是这个转角下还看得出是一张平整的纸在转的位置。
 */
private const val CAMERA_DISTANCE = 16f

/**
 * 左右各留出来的那一条，侧卡从这里探出来。
 *
 * 这一条就是「还能往两边翻」的全部提示——比在卡上画箭头、或者写一行「左右滑动」都轻，
 * 因为露出来的正是接下来要看的东西。40dp 是既看得出那是另一张卡的边、又不至于把中间
 * 那张挤窄的位置。
 */
private val SIDE_PEEK: Dp = 40.dp

/** 卡片那一圈之外还要占掉的高度：底下那排动作、它上面那条间距，以及上下留白。见 bandHeight */
private const val BAND_CHROME = 132

/** 卡片那一圈的最矮和最高，见 bandHeight */
private val BAND_MIN: Dp = 480.dp
private val BAND_MAX: Dp = 600.dp

/**
 * 未显影海报的解码宽度，像素。
 *
 * 十几个像素放到 112dp 宽的开窗里，双线性插值出来的就是一团糊掉的色块：留下的是那天的
 * 色调，不是那部片。这个数字本身就是保密强度，往上调一档就是在多给一点答案。
 */
private const val LATENT_DECODE_PX = 12

/** 未显影海报压掉多少饱和度：留下色相的方向，压掉「这是一张彩色海报」的存在感 */
private const val LATENT_SATURATION = 0.45f

/** 墨条的浓度。看得出那儿有一行字，读不出写的是什么；再深就像真印了些什么上去 */
private const val LATENT_BAR_ALPHA = 0.16f

/** 空印的边长，与 QuoteSeal 的方印一致——同一枚印的两种状态，尺寸不该差一个像素 */
private val LATENT_SEAL_SIZE: Dp = 46.dp

/** 卡片起落用的缓动：快进慢出，像一张纸被托起来 */
private val CardEasing = CubicBezierEasing(0.2f, 0.75f, 0.28f, 1f)
