package com.tracktosearch.ui.screen.dailystamp

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Picture
import android.os.Build
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas as GraphicsCanvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.draw
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Scale
import com.tracktosearch.R
import com.tracktosearch.ui.component.SaveToAlbumResult
import com.tracktosearch.ui.component.SharedOrigin
import com.tracktosearch.ui.haptic.HapticSemantic
import com.tracktosearch.ui.haptic.hapticClickable
import com.tracktosearch.ui.haptic.rememberAppHaptics
import com.tracktosearch.ui.navigation.DetailSeedStore
import com.tracktosearch.ui.screen.splash.PosterCompactFontScale
import com.tracktosearch.ui.screen.splash.QuoteSeal
import com.tracktosearch.ui.screen.splash.SealSizes
import com.tracktosearch.ui.screen.splash.SplashPalette
import com.tracktosearch.ui.screen.splash.StampBackdrop
import com.tracktosearch.ui.screen.splash.StampDesignHeight
import com.tracktosearch.ui.screen.splash.StampLinesToSource
import com.tracktosearch.ui.screen.splash.StampPage
import com.tracktosearch.ui.screen.splash.StampPageAspect
import com.tracktosearch.ui.screen.splash.StampPosterFrame
import com.tracktosearch.ui.screen.splash.StampQuoteBlock
import com.tracktosearch.ui.screen.splash.StampTextSize
import com.tracktosearch.ui.screen.splash.stampPosterSize
import com.tracktosearch.ui.util.showToast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import kotlin.math.abs
import kotlin.random.Random

/**
 * 日签卡片的 origin 基名。
 *
 * [SharedOrigin] 只收跨页面配对用到的公共值，某个屏幕私有的细分值就近声明在该屏幕文件里。
 */
private const val DAILY_STAMP_ORIGIN_BASE = "daily-stamp"

/**
 * 某一天卡片的 origin。
 *
 * 一排卡片一天一张，槽位就取那一天：台词库可以把同一部片子排在不同的日子，
 * 只靠 tmdbId 分不出点开的是哪一张卡。
 */
private fun dailyStampOrigin(date: LocalDate): String =
    SharedOrigin.of(DAILY_STAMP_ORIGIN_BASE, date.toString())

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
    val haptics = rememberAppHaptics()
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
            dates.getOrNull(page)?.let {
                if (it == selected) return@let
                // 「手势翻页落定」那一记收在这一处：滑动松手 settle 与点侧卡的
                // animateScrollToPage 都从这里过，各自再挂一层就是同一次翻页震两下。
                // 判等之后才发：snapshotFlow 起手会先吐一次当前页，那不是一次翻页
                haptics.gestureEnd()
                onSelect(it)
            }
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
        line?.card?.let {
            // 卡片海报来自台词库自带的地址，进不了 TMDB 详情缓存，详情页 peek 会落空，
            // 连同 origin 一起交给它：海报当首帧种子
            DetailSeedStore.remember(it.tmdbId, it.posterUrl, it.year, origin = dailyStampOrigin(it.date))
            onQuoteClick(it.tmdbId, it.mediaType, it.title, it.year, it.posterUrl)
        }
        Unit
    }
    /** 卡面底部那枚落款（图标 + 应用名），屏幕与导出图共用同一段绘制，见 [StampBrandRow] */
    val brand = rememberStampBrand(palette)
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
                brand = brand,
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
 * 摊开的纸；支点放在中心的话它会整张往外缩，看着是并排三张而不是叠在一起的三张。
 */
@Composable
private fun CarouselPage(
    sheet: DailyStampSheet,
    palette: SplashPalette,
    brand: StampBrand,
    turn: () -> Float,
    pictures: MutableMap<LocalDate, Picture>,
    onPosterClick: () -> Unit,
    onSideClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    // 页里那道圆角是按设计尺度定的（[StampCardCorner]），页缩进槽里之后它在屏幕上就不是
    // 24dp 了。阴影和压暗的纱都要按缩完的那个半径画，才跟纸的四角严丝合缝——差一点，
    // 角上就会露出一圈没压暗的纸、或者一道压在纸外的暗边。
    val corner = StampCardCorner * stampPageMetrics().scale
    val veil = scrimColor(palette).copy(alpha = 1f)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                val offset = turn()
                val away = abs(offset)
                val scale = lerp(1f, SIDE_SCALE, away)
                scaleX = scale
                scaleY = scale
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
                // 圆角跟着页内那道裁切走（见 StampPage 的 corner）：阴影是这一页的，
                // 页切了四角而影子还是方的，四个角上就会露出方影的直角
                .shadow(20.dp, RoundedCornerShape(corner), clip = false)
        ) {
            when (sheet) {
                is DailyStampSheet.Line -> {
                    val picture = remember(sheet.card.date) { Picture() }
                    DisposableEffect(sheet.card.date) {
                        pictures[sheet.card.date] = picture
                        onDispose { pictures.remove(sheet.card.date) }
                    }
                    RecordedDailyStampCard(
                        picture = picture,
                        card = sheet.card,
                        palette = palette,
                        brand = brand,
                        onPosterClick = onPosterClick,
                    )
                }

                is DailyStampSheet.Latent -> LatentCard(latent = sheet, palette = palette)
            }
            // 侧卡压暗的那层纱，见 [SIDE_VEIL]。画在卡面之上、录进 Picture 的那一页之外，
            // 所以导出的图里没有它——正中间那张的 away 是 0，这一层本来就是透明的。
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .graphicsLayer { alpha = lerp(0f, SIDE_VEIL, abs(turn())) }
                    .clip(RoundedCornerShape(corner))
                    .background(veil)
            )
        }
    }
}

/**
 * 卡面旁路录进 Picture，同时由 Compose 直接绘到屏幕。
 *
 * Picture 只供保存/分享使用，不能再回放成屏幕内容：异步图片的绘制节点不会可靠进入它，
 * 结果就是卡纸和文字都有、海报开窗却一直空着。
 */
@Composable
private fun RecordedDailyStampCard(
    picture: Picture,
    card: DailyStampCardUi,
    palette: SplashPalette,
    brand: StampBrand,
    onPosterClick: () -> Unit,
) {
    var posterRevision by remember(card.date, card.poster) { mutableIntStateOf(0) }
    Box(
        modifier = Modifier.drawWithContent {
            // 在 draw 阶段读取 revision；海报成功后明确让包住整张卡的绘制层失效。
            recordThenDraw(picture, posterRevision)
        }
    ) {
        DailyStampCard(
            card = card,
            palette = palette,
            brand = brand,
            onPosterClick = onPosterClick,
            onPosterLoaded = { posterRevision += 1 },
        )
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
 * 卡面：一页日签，和开屏那一屏同一套版面（见 StampPage）。
 *
 * 翻页槽是 9:16 的，这一页按设计高度 [StampDesignHeight] 排、再按密度缩进槽里。
 * 屏幕上这一张、导出出去的那一张、以及开屏那一屏于是是同一份绘制（导出走 Picture 重放，
 * 见 [RecordedDailyStampCard]）——原先卡片是另一套票根排版，导出图与开屏两张脸。
 *
 * 底部那一条是空的：屏幕上「轻触跳过」没有意义（点哪儿都不是跳过），应用名也是废话
 * （这张图本来就在应用里）。导出时才在那里补一行图标 + 名字，见 DailyStampExport 的
 * StampBrand。位置留着，所以屏幕与导出只差这一行。
 *
 * 字号比开屏大一档（[StampTextSize.Card]），四角是圆的（[StampCardCorner]）：这一页是浮在
 * 别的底色上的一张卡，不是一个铺满屏幕的界面。开屏两样都不跟——铺满整屏时圆角在屏幕外，
 * 而字大不大是「缩进槽里那一张」的问题。
 *
 * 海报和片名都可点，都进这部片的详情页：这张卡的下一步动作只有一个，
 * 不该逼用户去找唯一那个能点的地方。
 */
@Composable
private fun DailyStampCard(
    card: DailyStampCardUi,
    palette: SplashPalette,
    brand: StampBrand,
    onPosterClick: () -> Unit,
    onPosterLoaded: () -> Unit,
) {
    // 这一页的设计高度是钉死的（[StampDesignHeight]），于是只有超大字号那一档要收海报：
    // 字号翻上去等于把四行台词的行高整段拉长，占的还是纵向那点余量。
    val compact = LocalDensity.current.fontScale > PosterCompactFontScale
    StampPageBox {
        StampPage(
            palette = palette,
            date = card.date,
            modifier = Modifier.fillMaxSize(),
            corner = StampCardCorner,
            backdrop = { StampBackdrop(palette) },
            poster = {
                CardPoster(
                    card = card,
                    palette = palette,
                    size = stampPosterSize(card.lines.size, compact),
                    onClick = onPosterClick,
                    onLoaded = onPosterLoaded,
                )
            },
            quoteBlock = {
                StampQuoteBlock(
                    lines = card.lines,
                    isEnglish = card.isEnglish,
                    title = card.title,
                    titleWrap = card.titleWrap,
                    year = card.year,
                    palette = palette,
                    size = StampTextSize.Card,
                    sourceOnClick = onPosterClick,
                )
            },
            seal = {
                QuoteSeal(
                    keyword = card.sealKeyword,
                    latin = card.keywordLatin,
                    sealLang = card.sealLang,
                    palette = palette,
                    sizes = SealSizes.Card,
                    // 这一页的底是 paper 不是 sheet，做旧那层要拿正确的底色去盖才不留色差
                    ground = palette.paper,
                )
            },
            // 撕口虚线以下那一段：屏幕、导出图同高同一枚落款（未来那一页换成「那天见」）。
            footnote = { StampBrandRow(brand = brand) },
        )
    }
}

/**
 * 把这一页缩进轮播槽：9:16 的框 + 一份缩小的密度。
 *
 * 不能套 graphicsLayer 缩放：图层录不进 Picture，而这一页要旁路录一份给导出，
 * 套上去导出就只剩一张白图（这条路在 [RecordedDailyStampCard] 里已经踩过）。
 * 改密度之后页内所有 dp/sp 尺寸按同一比例落到目标像素上，字仍是按最终大小排的，不会糊。
 *
 * 尺寸取「槽宽」和「槽高换算成的宽」里小的那个，再按 9:16 推出高：形不能变形，能被让掉的
 * 是大小。按槽高直接定宽会在窄屏上顶出槽外——槽左右各留了 [SIDE_PEEK] 给侧卡探头，
 * 而一页纸比那点空宽，多出来的部分会盖到侧卡上。
 *
 * 外框用的是没改过的密度，于是它占的像素正好是「框高 × 屏密度」；里层换成缩小后的密度，
 * 按 [StampDesignHeight] 排的版面于是等比铺满这个框。设计宽度是推出来的（853 × 9/16 ≈ 480dp），
 * 与机型无关——同一张日签在任何机器上都是同一页，只是大小不同。
 *
 * 四个圆角不在这里裁：那是页自己的事（见 StampPage 的 corner），裁在这一层会连
 * Picture 一起裁——导出图缺四角。这一层只管把这一页摆进槽里。
 */
@Composable
private fun StampPageBox(content: @Composable () -> Unit) {
    val density = LocalDensity.current
    val metrics = stampPageMetrics()
    val pageDensity = remember(density, metrics.scale) {
        Density(density.density * metrics.scale, density.fontScale)
    }
    Box(modifier = Modifier.size(width = metrics.width, height = metrics.height)) {
        CompositionLocalProvider(LocalDensity provides pageDensity) {
            Box(modifier = Modifier.fillMaxSize()) { content() }
        }
    }
}

/**
 * 这一页铺在屏幕上占多大（屏幕 dp），以及缩进槽里的那份比例。
 *
 * 页里所有尺寸都是按设计尺度定的（[StampDesignHeight]、[StampCardCorner]），只有在这里
 * 才知道它们在屏幕上折成多少 dp。要这份数的不止页自己的框（[StampPageBox]）：阴影和
 * 压暗的纱画在页外、用的却是屏幕密度，圆角不跟着折算就会跟纸的四角对不上（[CarouselPage]）。
 */
private data class StampPageMetrics(val width: Dp, val height: Dp, val scale: Float)

@Composable
private fun stampPageMetrics(): StampPageMetrics {
    val slot = (LocalConfiguration.current.screenWidthDp - SIDE_PEEK.value * 2).dp
    val width = minOf(slot, bandHeight() * StampPageAspect)
    val height = width / StampPageAspect
    return StampPageMetrics(width = width, height = height, scale = height.value / StampDesignHeight.value)
}

/**
 * 卡片上的海报：装裱、投影、压色交给 [StampPosterFrame]，这里只负责把这一天的海报装进去。
 *
 * 走 Coil 而不是先把字节解成 Bitmap：海报可能是刚下载下来的，卡又是在点开时才出现，
 * 等它一帧比卡着不动好。海报到位之后由 [RecordedDailyStampCard] 重新录一遍卡面——
 * 异步图片的绘制节点不保证进得来，录制得赶在它画出来之后。
 *
 * `allowHardware(false)`：导出的那张图在软件 Canvas 上重放，硬件 Bitmap 参与不了。
 */
@Composable
private fun CardPoster(
    card: DailyStampCardUi,
    palette: SplashPalette,
    size: DpSize,
    onClick: () -> Unit,
    onLoaded: () -> Unit,
) {
    if (card.poster == null) return
    val interactionSource = remember { MutableInteractionSource() }
    val context = LocalContext.current
    val posterRequest = remember(card.poster, context) {
        ImageRequest.Builder(context)
            .data(card.poster)
            .allowHardware(false)
            .build()
    }
    StampPosterFrame(
        palette = palette,
        size = size,
        modifier = Modifier.hapticClickable(
            interactionSource = interactionSource,
            indication = null,
            // 日签卡是图片卡，进详情不震（用户规则：点击图片无触感）
            semantic = null,
            onClick = onClick,
        ),
    ) {
        AsyncImage(
            model = posterRequest,
            contentDescription = card.title,
            onSuccess = { onLoaded() },
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * 还没到那天的卡片。
 *
 * 版面和 [DailyStampCard] 完全一样（海报、台词、出处、撕口、印、日期），一眼看得出是
 * 同一页，只是每一格都还没显影：
 * - 海报取 w92 那一档、解到 12 像素再放大。放大靠双线性插值，出来的就是一团糊掉的
 *   色块——留下的是那天的色调，不是那部片。不用 Modifier.blur：它在 API 30 及以下是
 *   空操作，靠它保密等于在老机器上把海报直接摊开。糊完再压一层自上而下加重的纱。
 * - 台词和出处是几行墨条，条数长短按日期播种（[latentBars]），同一天每次打开都一样。
 *   有形无字：看得出那儿有两行字，读不出是哪两行。
 * - 印只剩界格，印文没落下。
 * - 底下那句未来提示替代了印章之下的留白：这一页没有「今天看到的那句话」，
 *   得有一句话说明这一格还没到。
 *
 * 台词、片名、印文这些字段压根没带进这一层（见 [DailyStampSheet.Latent]）：不给看不是
 * 「先显示再遮住」——遮罩会随实现走样，不带出来才是真的不给看。
 */
@Composable
private fun LatentCard(latent: DailyStampSheet.Latent, palette: SplashPalette) {
    val bars = remember(latent.date) { latentBars(latent.date) }
    StampPageBox {
        StampPage(
            palette = palette,
            date = latent.date,
            modifier = Modifier.fillMaxSize(),
            corner = StampCardCorner,
            backdrop = { StampBackdrop(palette) },
            poster = {
                LatentPoster(
                    url = latent.tinyPosterUrl,
                    palette = palette,
                    size = stampPosterSize(lineCount = 2, compact = false),
                )
            },
            quoteBlock = {
                bars.quote.forEachIndexed { index, row ->
                    if (index > 0) Spacer(Modifier.height(LATENT_QUOTE_BAR_GAP))
                    InkBars(widths = row, height = LATENT_QUOTE_BAR_HEIGHT, palette = palette)
                }
                Spacer(Modifier.height(StampLinesToSource))
                InkBars(widths = bars.source, height = LATENT_SOURCE_BAR_HEIGHT, palette = palette)
            },
            seal = { LatentSeal(palette = palette) },
            // 那句提示挪到撕口虚线以下那一段的正中：那一带是存根，导出图在那儿落款，
            // 未来这一页在那儿写「那天见」——两处同高，翻页时不会有东西跳一下。
            footnote = {
                Text(
                    text = stringResource(R.string.daily_stamp_future_card),
                    modifier = Modifier.fillMaxWidth(),
                    color = palette.inkSoft,
                    // 字号、字距跟这一页的注解语汇对齐（衬线、拉开字距），比出处行再大半档、
                    // 加到 SemiBold：它在页底，是这一页唯一要读的话，不能看着像页边的批注
                    fontSize = 17.sp,
                    lineHeight = 24.sp,
                    fontFamily = FontFamily.Serif,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.1.em,
                    textAlign = TextAlign.Center,
                )
            },
        )
    }
}

/**
 * 糊掉的海报：真模糊 + 一层自上而下加重的纱，装裱照旧走 [StampPosterFrame]。
 *
 * API 31 起走 [Modifier.blur]：真高斯模糊，色块之间是连续过渡，看着就是「隔着毛玻璃
 * 看一张海报」。低版本 blur 是空操作，退回把图解到十几个像素再放大——那一档只能靠
 * 插值糊，边界会看出方块，但至少认不出是哪部片。
 *
 * 走模糊那条路时解码尺寸要放大一档：40px 配上十几 dp 的模糊半径已经什么都读不出来，
 * 而 12px 的图再叠模糊会先看到方块的轮廓被抹开，成了「马赛克又被涂了一层」。
 *
 * 那层纱往卡纸色上化（不是往纸色）：海报陷在卡纸开出来的窗里，纱要收在窗内沿那圈。
 */
@Composable
private fun LatentPoster(url: String?, palette: SplashPalette, size: DpSize) {
    val context = LocalContext.current
    val blurs = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val request = remember(url, context, blurs) {
        url?.let { address ->
            ImageRequest.Builder(context)
                .data(address)
                .size(if (blurs) LATENT_BLUR_DECODE_PX else LATENT_DECODE_PX)
                .scale(Scale.FILL)
                // 这一页不进 Picture 录制（未显影那天没有可导出的东西），但两张卡走
                // 同一套装裱代码，这里也不开硬件位图
                .allowHardware(false)
                .crossfade(false)
                .build()
        }
    }
    val veil = remember(palette) {
        Brush.verticalGradient(
            0f to Color.Transparent,
            0.42f to palette.cream.copy(alpha = 0.20f),
            1f to palette.cream.copy(alpha = 0.74f),
        )
    }
    val desaturate = remember {
        ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(LATENT_SATURATION) })
    }
    StampPosterFrame(palette = palette, size = size) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawWithContent {
                    drawContent()
                    drawRect(brush = veil)
                }
        ) {
            if (request != null) {
                AsyncImage(
                    model = request,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    colorFilter = desaturate,
                    // 低版本靠这一档插值把小图抹开。显式写出来是因为默认值要是哪天变成
                    // 最近邻，糊掉的图会变成马赛克，反而把轮廓切得更清楚
                    filterQuality = FilterQuality.Low,
                    modifier = Modifier
                        .fillMaxSize()
                        .then(
                            if (blurs) {
                                // 边界跟着开窗的圆角裁（5dp，见 StampPosterFrame 的内层）：
                                // 不裁的话模糊会把颜色晕到卡纸上，像海报洇了出来
                                Modifier.blur(
                                    radius = LATENT_BLUR,
                                    edgeTreatment = BlurredEdgeTreatment(RoundedCornerShape(5.dp)),
                                )
                            } else {
                                Modifier
                            }
                        ),
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
        horizontalArrangement = Arrangement.spacedBy(LATENT_BAR_GAP, Alignment.CenterHorizontally),
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
 *
 * 长短按这一页的设计宽度（[StampDesignHeight] 那一档下、去掉页边距的 412dp）定：
 * 原先那几档是按 336dp 宽的票根卡定的，缩进这一页之后那几行墨条会细成一排小数点。
 */
private fun latentBars(date: LocalDate): LatentBars {
    val random = Random(date.toEpochDay())
    return LatentBars(
        quote = listOf(
            List(random.nextInt(6, 10)) { random.nextInt(11, 20).dp },
            List(random.nextInt(4, 8)) { random.nextInt(11, 20).dp },
        ),
        source = List(random.nextInt(3, 6)) { random.nextInt(9, 16).dp },
    )
}

private class LatentBars(val quote: List<List<Dp>>, val source: List<Dp>)

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

/**
 * 一枚动作胶囊。
 *
 * 淡掉那一档（未显影的卡片）连点击修饰符都不挂，而不是只把 enabled 置 false：
 * 那一排按钮看不见但位置还占着，挂着的点击会把落在那块地方的一下吃掉，用户点的是
 * 一片空白却什么也没发生——那块地方本该和卡片外的空处一样，点一下就把卡片收回去。
 */
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
            // hapticClickable 自己带 enabled，禁用时既不发触感也不吃点击，
            // 不必再套一层 .then(if (enabled))
            .hapticClickable(
                interactionSource = interactionSource,
                indication = null,
                semantic = HapticSemantic.TAP,
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
 * 把卡面旁路录进 [picture]，屏幕仍直接绘制 Compose 内容。
 *
 * 导出拿 Picture 在软件 Canvas 上放一遍（见 [captureCardPicture]）；屏幕不能回放 Picture，
 * 因为异步图片绘制节点不会可靠录入，回放会让海报开窗一直空着。
 *
 * 之前走的是 GraphicsLayer 快照：内容先录进平台图层，再向图层要像素，部分 Android 9
 * 以上的设备会返回尺寸正常、内容全白的位图。Picture 只是一串指令，光栅化在哪张画布上
 * 发生由我们自己决定，白图问题从根上没有了。
 *
 * 尺寸还没量出来的那一帧照常画内容，只是不录——录一张 0×0 的 Picture 会让导出拿到空图。
 */
private fun ContentDrawScope.recordThenDraw(
    picture: Picture,
    contentRevision: Int,
) {
    // 只为在 draw 阶段订阅 Snapshot；数值不参与画面。
    @Suppress("UNUSED_VARIABLE")
    val observedRevision = contentRevision
    val width = size.width.toInt()
    val height = size.height.toInt()
    if (width <= 0 || height <= 0) {
        drawContent()
        return
    }
    val recordingCanvas = GraphicsCanvas(picture.beginRecording(width, height))
    // draw 会把当前 DrawScope 的画布临时换成录制画布，drawContent 于是画进了 Picture
    draw(this, layoutDirection, recordingCanvas, size) {
        this@recordThenDraw.drawContent()
    }
    picture.endRecording()
    // 屏幕直接画 Compose 内容；Picture 只留给导出，避免它吞掉图片绘制节点。
    drawContent()
}

/**
 * 把最近一次录下来的那一页重放成位图：屏幕上有什么，导出的就有什么（落款也在 Picture 里）。
 *
 * 显式建一张 ARGB_8888 位图，用 android.graphics.Canvas 回放 Picture：整条路都是软件
 * 光栅化，不经过 GPU 快照，也不依赖任何平台图层。
 *
 * 位图就是这一页的尺寸，不再加高：落款画的是页面自己留出来的那一条（屏幕上那里空着、
 * 开屏那里是「轻触跳过」），于是屏幕、开屏、导出三张只差这一行——原先导出图要往上接
 * 一条带子，那张图比屏幕上多出一截。
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
            .also { bitmap ->
                AndroidCanvas(bitmap).drawPicture(picture)
            }
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
 * 未显影那几行的墨条：行距、条高、条与条之间的空。
 *
 * 按这一页的设计档（[StampDesignHeight] 与 [StampQuoteBlock] 的字号）配：两句台词的位置是
 * 两行 27sp 的正文（卡片那一档，见 [StampTextSize]），墨条高度取实心笔画那一档，
 * 行距取字号自带行高的一半——太整齐会读成表格，太细又成了下划线。
 */
private val LATENT_QUOTE_BAR_HEIGHT: Dp = 17.dp
private val LATENT_QUOTE_BAR_GAP: Dp = 22.dp
private val LATENT_SOURCE_BAR_HEIGHT: Dp = 11.dp
private val LATENT_BAR_GAP: Dp = 6.dp

/**
 * 侧卡缩到多小。
 *
 * 底下这一族值一起决定「旁边那两张是同一叠里的下一张」这个观感，单调一个都会走味：
 * 缩放和压暗让侧卡退到后面，下沉让它像垫在底下那张，内收把露出来的那条边挪到靠中间
 * 一侧，rotationY 才是把纸面转过去的那一下。全都从 [pageTurn] 那个 0~±1 的量线性插出来，
 * 所以这些数字是「翻到底时的样子」，滑动过程中取的是中间值。
 */
private const val SIDE_SCALE = 0.84f

/**
 * 侧卡压暗多少。
 *
 * 这里原先调的是整层的 alpha（0.62）：半透明的纸是拿压暗层兑出来的，于是压暗层背后的
 * 东西——底色的深浅斑块、卡影、被模糊过的内容——全都从纸里透出来显形。那不像「后面还有
 * 一张卡」，像卡面自己在漏光，浅色主题下右边那条发灰就是这么来的。
 *
 * 现在是实心的纸 + 一层压暗层色的纱：纱只把纸压暗，不透底。0.20 是把纸从 200 压到 167
 * 那一档，退后的量跟原来差不多，而纸是纸、底是底。
 *
 * 压暗层色取 [scrimColor]，深浅两个主题各取各的，所以这一档在两处都成立。
 */
private const val SIDE_VEIL = 0.20f

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
 * 未显影海报的解码宽度，像素。API 31 以下用，见 [LatentPoster]。
 *
 * 十几个像素放到 112dp 宽的开窗里，双线性插值出来的就是一团糊掉的色块：留下的是那天的
 * 色调，不是那部片。这个数字本身就是保密强度，往上调一档就是在多给一点答案。
 */
private const val LATENT_DECODE_PX = 12

/** 走真模糊那条路时的解码宽度。配上 [LATENT_BLUR] 已经什么都读不出来，见 [LatentPoster] */
private const val LATENT_BLUR_DECODE_PX = 40

/**
 * 未显影海报的模糊半径。
 *
 * 在常见密度上折成六七十像素，铺在一张三百像素宽的图上，人脸、字、轮廓一样不剩，
 * 留下的只有色调的走向。
 */
private val LATENT_BLUR: Dp = 14.dp

/** 未显影海报压掉多少饱和度：留下色相的方向，压掉「这是一张彩色海报」的存在感 */
private const val LATENT_SATURATION = 0.45f

/** 墨条的浓度。看得出那儿有一行字，读不出写的是什么；再深就像真印了些什么上去 */
private const val LATENT_BAR_ALPHA = 0.16f

/** 空印的边长，与 QuoteSeal 的方印一致——同一枚印的两种状态，尺寸不该差一个像素 */
private val LATENT_SEAL_SIZE: Dp = SealSizes.Card.width

/**
 * 卡片与导出图那一页的圆角（设计尺度，见 [StampPage]）：这一页是浮在别的底色上的一张卡。
 *
 * 开屏不跟——它铺满整屏，圆角落在屏幕外，只会给状态栏那一带留四个缺口。
 */
internal val StampCardCorner: Dp = 24.dp

/** 卡片起落用的缓动：快进慢出，像一张纸被托起来 */
private val CardEasing = CubicBezierEasing(0.2f, 0.75f, 0.28f, 1f)
