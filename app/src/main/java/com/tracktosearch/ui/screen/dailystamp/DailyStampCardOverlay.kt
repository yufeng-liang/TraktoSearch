package com.tracktosearch.ui.screen.dailystamp

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Canvas as GraphicsCanvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toIntSize
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.tracktosearch.R
import com.tracktosearch.ui.component.SaveToAlbumResult
import com.tracktosearch.ui.screen.splash.QuoteSeal
import com.tracktosearch.ui.screen.splash.SplashPalette
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
    onQuoteClick: (tmdbId: Int, title: String, year: Int, posterUrl: String) -> Unit,
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
    onQuoteClick: (tmdbId: Int, title: String, year: Int, posterUrl: String) -> Unit,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val scope = rememberCoroutineScope()
    val graphicsLayer = rememberGraphicsLayer()
    val dragX = remember { Animatable(0f) }
    var busy by remember { mutableStateOf(false) }
    // 换到另一天时把拖动位移抹平，否则新卡片会歪在上一次松手的位置
    LaunchedEffect(card.date) { dragX.snapTo(0f) }

    val onPosterClick = {
        onQuoteClick(card.tmdbId, card.title, card.year, card.posterUrl)
    }

    /** 把当前卡面录下来交给保存/分享，两条路都用同一张软件位图 */
    val capture: suspend () -> Bitmap = {
        captureCardLayer(graphicsLayer, density, layoutDirection)
    }

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
                    // 录一份卡面留给保存/分享，再把这一份画到屏幕上：
                    // 屏幕上的和导出的是同一次绘制，不会出现「存下来的和看到的不一样」。
                    graphicsLayer.record(this, layoutDirection, size.toIntSize()) {
                        this@drawWithContent.drawContent()
                    }
                    drawLayer(graphicsLayer)
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
 * 从上到下：日期刻度、海报、台词、出处、一道虚线、印章。虚线是票根的撕口，
 * 它把「那天看到的那句话」和「那天的印」分成上下两半——上半是内容，下半是凭证。
 *
 * 海报和片名都可点，都进这部片的详情页：这张卡的下一步动作只有一个，
 * 不该逼用户去找唯一那个能点的地方。
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
    Column(
        modifier = Modifier
            .widthIn(max = 380.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(palette.sheet)
            .padding(horizontal = 22.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = tick,
            color = palette.inkFaint,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 0.42.em,
        )
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
        TearLine(palette)
        Spacer(Modifier.height(20.dp))
        QuoteSeal(
            keyword = card.sealKeyword,
            latin = card.keywordLatin,
            palette = palette,
            // 卡面是 sheet 不是 paper，做旧那层要拿卡面色去盖才不留色差
            ground = palette.sheet,
        )
    }
}

/**
 * 卡片上的海报，和开屏一样做成相纸装裱：奶油色卡纸 + 压一层纸色。
 *
 * 压色不是为了好看而已：未处理的彩色海报贴在暖纸卡面上像硬插进来的一块图，
 * 压掉一点饱和度之后它才像原本就印在这张卡上。
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

/** 出处行：破折号 + 书名号包起的片名 + 弱化的年份，整行可点进详情页 */
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
        // 年份压到 70% 透明度：它是注解不是标题，同色同重会和片名抢注意力
        withStyle(SpanStyle(color = palette.inkSoft.copy(alpha = palette.inkSoft.alpha * 0.7f))) {
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
private fun TearLine(palette: SplashPalette) {
    val dash = remember { PathEffect.dashPathEffect(floatArrayOf(6f, 7f), 0f) }
    Canvas(
        modifier = Modifier
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
 * 把已录制的卡面重放到软件位图。
 *
 * Android 9 及以上的 GraphicsLayer 硬件快照在部分设备会返回尺寸正常、内容却全白的位图。
 * 这里显式创建 ARGB_8888 Bitmap，再通过公开 DrawScope API 重放图层，绕开硬件快照。
 */
private suspend fun captureCardLayer(
    graphicsLayer: GraphicsLayer,
    density: Density,
    layoutDirection: LayoutDirection,
): Bitmap = withContext(Dispatchers.Main.immediate) {
    val layerSize = graphicsLayer.size
    require(layerSize.width > 0 && layerSize.height > 0) {
        "Daily stamp card has not been drawn yet"
    }

    Bitmap.createBitmap(layerSize.width, layerSize.height, Bitmap.Config.ARGB_8888).also { bitmap ->
        CanvasDrawScope().draw(
            density = density,
            layoutDirection = layoutDirection,
            canvas = GraphicsCanvas(AndroidCanvas(bitmap)),
            size = Size(layerSize.width.toFloat(), layerSize.height.toFloat()),
        ) {
            drawLayer(graphicsLayer)
        }
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
        // 位图录制失败（低内存、层还没画完）也只提示，不崩在一次保存上
        R.string.daily_stamp_save_failed
    }
    context.showToast(context.getString(message))
}

private suspend fun shareCard(
    context: android.content.Context,
    date: LocalDate,
    capture: suspend () -> Bitmap,
) {
    try {
        val bitmap = capture()
        val uri = try {
            shareStampCardUri(context, bitmap, date)
        } finally {
            bitmap.recycle()
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(intent, context.getString(R.string.daily_stamp_share_chooser))
        )
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        // 无接收方应用、FileProvider 写入失败等
        context.showToast(context.getString(R.string.daily_stamp_share_failed))
    }
}

/** 浮层的压暗程度：卡片是暖纸色，压得太浅就浮不起来，太深又像对话框 */
private fun scrimColor(palette: SplashPalette): Color =
    if (palette.isDark) Color(0xCC120C08) else Color(0x993C2212)

private const val EM_DASH = "—"

/** 松手翻页的位移门槛（像素）。位移本身打了三折，这里对应手指约走 130px */
private const val SWIPE_COMMIT_PX = 44f

/** 卡片起落用的缓动：快进慢出，像一张纸被托起来 */
private val CardEasing = CubicBezierEasing(0.2f, 0.75f, 0.28f, 1f)
