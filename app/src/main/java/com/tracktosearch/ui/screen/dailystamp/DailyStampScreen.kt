package com.tracktosearch.ui.screen.dailystamp

import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.KeyboardArrowLeft
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Scale
import com.tracktosearch.R
import com.tracktosearch.data.local.SplashQuote
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale

/**
 * 日签页：一个月的签到日历，每格印着那天的海报和关键词。
 *
 * 整屏的底、字色、强调色跟随当前主题（[DailyStampPalette]），格子那张纸不跟——
 * 纸永远是纸黄的（见 [DailyStampPalette.tile]）。衬线字和拉开的字距留着，那是日签
 * 自己的字面性格。台词卡片仍是票根质感的纸，见 [rememberDailyStampCardPalette]。
 *
 * 格子是「纸上贴海报，海报下印词」：海报按原始 2:3 比例贴在纸上，四周留一圈白边，
 * 关键词单独一行落在纸的下沿。早先的版本把关键词压在淡海报上，海报只剩色温差，
 * 等于白下载一张图。
 *
 * 点格子升起卡片浮层（[DailyStampCardOverlay]），不是 sheet：sheet 从底部推上来会
 * 把日历顶走，而这张卡应该是从那一格里长出来的。
 *
 * 这一屏也被设置页的「每日台词」二级页复用（只嵌 [DailyStampCalendar] 那一段）。
 */
@Composable
fun DailyStampScreen(
    onBack: () -> Unit,
    onQuoteClick: (tmdbId: Int, mediaType: String, title: String, year: Int, posterUrl: String) -> Unit,
    viewModel: DailyStampViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val palette = rememberDailyStampPalette()
    val cardPalette = rememberDailyStampCardPalette()
    val content = rememberDailyStampContent(state)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.paper)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .dailyStampCardBlur(active = content.sheet != null)
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            DailyStampTopBar(palette = palette, onBack = onBack)
            // 报头压到一屏能装下六行格子；真装不下（大字号、更高的状态栏）仍然能滚，
            // 只是常见情况下不必滚。格子按这里量出来的余高收缩，见 DailyStampCalendar
            BoxWithConstraints(modifier = Modifier.fillMaxWidth().weight(1f)) {
                val budget = maxHeight - DAILY_STAMP_CALENDAR_CHROME - 10.dp
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    DailyStampCalendar(
                        state = state,
                        palette = palette,
                        locale = content.locale,
                        today = content.today,
                        cells = content.cells,
                        openable = content.sheets.keys,
                        gridHeightBudget = budget,
                        onPreviousMonth = viewModel::previousMonth,
                        onNextMonth = viewModel::nextMonth,
                        onDayClick = { date -> viewModel.select(date) },
                    )
                    Spacer(Modifier.height(10.dp))
                }
            }
        }
        DailyStampCardOverlay(
            sheet = content.sheet,
            sheets = content.sheets,
            palette = cardPalette,
            openableDates = content.openableDates,
            onSelect = { date -> viewModel.select(date) },
            onDismiss = { viewModel.select(null) },
            onQuoteClick = onQuoteClick,
        )
    }
}

/**
 * 卡片升起时把底下那一屏推到景深之外。
 *
 * [Modifier.blur] 要 API 31+，低版本靠浮层自己那层更重的压暗顶上（见
 * DailyStampCardOverlay 的 scrimColor）——没有模糊时如果连焦点变化都没有，
 * 卡片会像贴在日历上而不是浮在上面。
 *
 * 抽成 Modifier 是因为两个入口都要：独立日签页，以及设置里的「每日台词」二级页。
 * 那两屏点开的是同一张卡，背景处理不一致会显得是两个功能。
 */
@Composable
internal fun Modifier.dailyStampCardBlur(active: Boolean): Modifier {
    val radius by animateDpAsState(
        targetValue = if (active) CARD_BLUR_RADIUS else 0.dp,
        animationSpec = tween(durationMillis = 240),
        label = "dailyStampBlur",
    )
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && radius > 0.dp) {
        this.blur(radius)
    } else {
        this
    }
}

/**
 * 一屏日历要用到的派生数据。
 *
 * 抽出来是因为独立日签页和设置里的「每日台词」页都要同一套派生：语言解析、格子映射、
 * 卡片内容、可翻的日子。两处各写一遍迟早只改一处。
 */
@androidx.compose.runtime.Immutable
internal data class DailyStampContent(
    val locale: Locale,
    val today: LocalDate,
    val cells: Map<LocalDate, DailyStampCellUi>,
    /** 当月所有能翻开的日子及其内容：签到过的、错过的、还没到的 */
    val sheets: Map<LocalDate, DailyStampSheet>,
    /** 当前选中那天的内容，没选时为 null */
    val sheet: DailyStampSheet?,
    val openableDates: List<LocalDate>,
)

@Composable
internal fun rememberDailyStampContent(state: DailyStampUiState): DailyStampContent {
    // 语言从 Configuration 取而不是读设置项：「跟随系统」这一档只有 Configuration 知道
    // 最终落到了哪种语言，而 applyLanguage 已经把用户选的语言同步进来了。
    val locale = LocalConfiguration.current.locales[0]
    val lang = remember(locale) { SplashQuote.resolveLang(locale.language) }
    val today = remember { LocalDate.now() }
    val cells = remember(state.stamps, lang) {
        state.stamps.associate { it.date to it.toCell(lang) }
    }
    // 三类日子合成一张表：签到过的、错过的、还没到的。错过的那天没有落库的行，台词是
    // 现算的（见 DailyStampRepository.missedMonth）；还没到的只带一张糊掉的海报。
    val sheets = remember(state.stamps, state.missed, state.latent, lang) {
        buildMap<LocalDate, DailyStampSheet> {
            state.stamps.forEach { stamp ->
                stamp.toCard(lang)?.let { put(stamp.date, DailyStampSheet.Line(it)) }
            }
            state.missed.forEach { stamp ->
                stamp.toCard(lang)?.let { put(stamp.date, DailyStampSheet.Line(it)) }
            }
            state.latent.forEach { stamp -> put(stamp.date, stamp.toLatent(lang)) }
        }
    }
    // 卡片左右滑动只在当月之内走：越过月边界就得先换月加载，而换月本来就有
    // 上一页/下一页两个箭头，没必要在卡片里再实现一遍。
    val openableDates = remember(sheets) { sheets.keys.sorted() }
    val sheet = remember(sheets, state.selected) { state.selected?.let(sheets::get) }
    return DailyStampContent(
        locale = locale,
        today = today,
        cells = cells,
        sheets = sheets,
        sheet = sheet,
        openableDates = openableDates,
    )
}

/**
 * 日历本体：月份报头 + 星期表头 + 月视图网格 + 底部一行小字。
 *
 * 不含顶栏、不含背景、不含卡片浮层——浮层要盖满整屏，只能由各自的页面挂在最外层，
 * 嵌在这里会被裁进日历那一块。
 *
 * 「你来之前」那些格子的提示也在这里：点一下不开卡片，改把底下那行小字换成那句话。
 * 放在这一层而不是各页自己实现，是因为那行字本来就属于日历，两个入口才不会一个有
 * 提示一个没有。
 *
 * 网格上可以左右滑动翻月（见 [monthSwipe]），和报头那两个箭头是同一件事的两种手势。
 *
 * @param gridHeightBudget 网格最多能占多高。给了值格子就按它收缩，整个日历一屏装得下；
 *   [Dp.Unspecified] 表示不限，格子只按宽度铺开。两个入口的余高不一样（独立页上面
 *   只有一条顶栏，设置页还压着一张开关卡片），所以这个数只能由调用方各自量
 */
@Composable
internal fun DailyStampCalendar(
    state: DailyStampUiState,
    palette: DailyStampPalette,
    locale: Locale,
    today: LocalDate,
    cells: Map<LocalDate, DailyStampCellUi>,
    /** 能翻开卡片的那些天，见 [DailyStampContent.sheets] */
    openable: Set<LocalDate>,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onDayClick: (LocalDate) -> Unit,
    gridHeightBudget: Dp = Dp.Unspecified,
) {
    // 点了「你来之前」那种格子的那一下：底下那行字临时换成东隅那句，过一会儿换回来
    var hinted by remember { mutableStateOf<LocalDate?>(null) }
    // 计时的 key 不能是 hinted 本身：连点同一格时它前后一样，LaunchedEffect 不会重启，
    // 第二下只能沿用第一下剩下的那点时间——点得越快提示消失得越突然。每次点击自增一个
    // 计数，日期没变也算换了 key，2.4 秒于是从头再走一遍。
    var hintTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(hintTick) {
        if (hinted != null) {
            delay(HINT_HOLD_MS)
            hinted = null
        }
    }
    Column {
        MonthMasthead(
            month = state.month,
            locale = locale,
            palette = palette,
            streak = state.streak,
            total = state.total,
            canGoPrevious = state.canGoPrevious,
            canGoNext = state.canGoNext,
            onPrevious = onPreviousMonth,
            onNext = onNextMonth,
        )
        Spacer(Modifier.height(10.dp))
        WeekdayRow(locale = locale, palette = palette)
        Spacer(Modifier.height(4.dp))
        MonthGrid(
            month = state.month,
            locale = locale,
            palette = palette,
            today = today,
            firstDay = state.firstDay,
            cells = cells,
            openable = openable,
            hinted = hinted,
            heightBudget = gridHeightBudget,
            onDayClick = onDayClick,
            onUnarrivedClick = { date ->
                hinted = date
                hintTick++
            },
            modifier = Modifier.monthSwipe(
                canGoPrevious = state.canGoPrevious,
                canGoNext = state.canGoNext,
                onPrevious = onPreviousMonth,
                onNext = onNextMonth,
            ),
        )
        Spacer(Modifier.height(14.dp))
        FooterHint(
            palette = palette,
            hinted = hinted != null,
            future = state.month.isAfter(YearMonth.from(today)),
            empty = state.stamps.isEmpty() && !state.loading,
        )
    }
}

/** 只有返回箭头和标题，没有 Material TopAppBar 的容器色——这一屏的底就是那张纸 */
@Composable
private fun DailyStampTopBar(
    palette: DailyStampPalette,
    onBack: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 12.dp, top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.Rounded.KeyboardArrowLeft,
                contentDescription = stringResource(R.string.content_desc_back),
                tint = palette.ink,
            )
        }
        Text(
            text = stringResource(R.string.daily_stamp_title),
            color = palette.ink,
            fontSize = 15.sp,
            fontFamily = FontFamily.Serif,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.16.em,
        )
    }
}

/**
 * 月份报头：月名 + 左右翻页，底下一行年月刻度与两个计数。
 *
 * 年月那段用等宽字体、拉开字距，和开屏顶上的日期是同一种处理——那是这两屏之间
 * 最直接的呼应。月名走衬线大字，locale 自己给「八月 / August / 8月 / 8월」。
 *
 * 刻度和计数挤进同一行、月名收到 23sp，是为了让六行格子在常见机型上一屏装得下。
 * 各占一行（刻度一行、月名一行、两个竖排计数一行）要 120dp，现在 53dp——
 * 省下的那 60dp 差不多正好是日历超出一屏的那一截。
 */
@Composable
private fun MonthMasthead(
    month: YearMonth,
    locale: Locale,
    palette: DailyStampPalette,
    streak: Int,
    total: Int,
    canGoPrevious: Boolean,
    canGoNext: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) {
    // 固定 Locale.US：这行是装饰性刻度，跟随系统会在部分语言下渲染成非阿拉伯数字
    val tick = remember(month) {
        month.atDay(1).format(DateTimeFormatter.ofPattern("yyyy.MM", Locale.US))
    }
    val monthName = remember(month, locale) {
        month.month.getDisplayName(TextStyle.FULL, locale)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MonthArrow(
                icon = Icons.Rounded.ChevronLeft,
                description = stringResource(R.string.daily_stamp_previous_month),
                enabled = canGoPrevious,
                palette = palette,
                onClick = onPrevious,
            )
            Text(
                text = monthName,
                modifier = Modifier.padding(horizontal = 6.dp),
                color = palette.ink,
                fontSize = 23.sp,
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.04.em,
                textAlign = TextAlign.Center,
            )
            MonthArrow(
                icon = Icons.Rounded.ChevronRight,
                description = stringResource(R.string.daily_stamp_next_month),
                enabled = canGoNext,
                palette = palette,
                onClick = onNext,
            )
        }
        Spacer(Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = tick,
                color = palette.inkMuted,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 0.24.em,
            )
            CounterDivider(palette = palette)
            Counter(
                value = pluralStringResource(R.plurals.daily_stamp_day_count, streak, streak),
                label = stringResource(R.string.daily_stamp_streak),
                palette = palette,
            )
            CounterDivider(palette = palette)
            Counter(
                value = pluralStringResource(R.plurals.daily_stamp_day_count, total, total),
                label = stringResource(R.string.daily_stamp_total),
                palette = palette,
            )
        }
    }
}

/** 刻度与两个计数之间的隔点。竖线在这行 13dp 高的字里显得比字还重，改成一个点 */
@Composable
private fun CounterDivider(palette: DailyStampPalette) {
    Text(
        text = "·",
        modifier = Modifier.padding(horizontal = 7.dp),
        color = palette.inkMuted,
        fontSize = 10.sp,
    )
}

/** 翻页箭头：翻不动时留在原位淡下去，不整个消失——按钮忽然没了会让人以为点错了 */
@Composable
private fun MonthArrow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    enabled: Boolean,
    palette: DailyStampPalette,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(34.dp)) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            // 禁用态从 inkSoft 收一档，不走 inkFaint：那是描边色，压在底色上只有 1.0:1，
            // 「淡下去」会淡成看不见，正好把上面那句注释想避开的事又做了一遍
            tint = if (enabled) palette.inkSoft else palette.inkSoft.copy(alpha = 0.45f),
            modifier = Modifier.size(20.dp),
        )
    }
}

/** 数字和名目排成一行小字：「1天 连续」。竖排两行时这一块占 37dp，横排 13dp */
@Composable
private fun Counter(
    value: String,
    label: String,
    palette: DailyStampPalette,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = value,
            color = palette.ink,
            fontSize = 12.sp,
            fontFamily = FontFamily.Serif,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = label,
            modifier = Modifier.padding(start = 4.dp),
            color = palette.inkHint,
            fontSize = 10.sp,
            letterSpacing = 0.1.em,
        )
    }
}

/**
 * 星期表头。
 *
 * 首日跟 locale 走：中文一到日、英文 Sunday 起。硬写成周一开头会让英文用户
 * 把整月的格子都读错一位。
 */
@Composable
private fun WeekdayRow(
    locale: Locale,
    palette: DailyStampPalette,
) {
    val labels = remember(locale) {
        val first = WeekFields.of(locale).firstDayOfWeek
        (0 until 7).map { first.plus(it.toLong()).getDisplayName(TextStyle.NARROW, locale) }
    }
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        labels.forEach { label ->
            Text(
                text = label,
                modifier = Modifier.weight(1f),
                color = palette.inkHint,
                fontSize = 10.sp,
                letterSpacing = 0.1.em,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * 左右滑动翻月。
 *
 * 用 [draggable] 而不是自己收 pointer 事件：它只认横向，纵向照样交给外面那层滚动
 * （独立页是 `verticalScroll`，设置页是 `LazyColumn`），而且横向越过触摸阈值之后
 * 格子上的 `clickable` 会自己取消，不会滑一下顺手翻开一张卡片。
 *
 * 一次手势只翻一页（[fired]）：不加这个锁的话，手指继续往同一边划，每多走
 * 一个阈值就再翻一个月，一甩过去能跳掉半年。
 *
 * 翻不动的方向直接不响应（[canGoPrevious] / [canGoNext]）——箭头在那个方向是淡掉的，
 * 手势也该是同一套规矩。
 */
@Composable
private fun Modifier.monthSwipe(
    canGoPrevious: Boolean,
    canGoNext: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
): Modifier {
    val threshold = with(LocalDensity.current) { MONTH_SWIPE_THRESHOLD.toPx() }
    val travel = remember { mutableFloatStateOf(0f) }
    val fired = remember { mutableStateOf(false) }
    val state = rememberDraggableState { delta ->
        travel.floatValue += delta
        if (fired.value) return@rememberDraggableState
        // 往右划是往回翻：内容跟着手指往右让，露出来的是更早的日子
        when {
            travel.floatValue >= threshold && canGoPrevious -> {
                fired.value = true
                onPrevious()
            }
            travel.floatValue <= -threshold && canGoNext -> {
                fired.value = true
                onNext()
            }
        }
    }
    return this.draggable(
        state = state,
        orientation = Orientation.Horizontal,
        onDragStarted = {
            travel.floatValue = 0f
            fired.value = false
        },
    )
}

/**
 * 月视图网格。
 *
 * 用 Column + Row 手排 7 列而不是 LazyVerticalGrid：一个月最多 42 格，而 lazy 网格
 * 嵌在可滚动的 Column 里必须先给死高度，反而更绕。
 *
 * **固定画 [MONTH_ROWS] 行**，不按当月实际占几周算。二月能排进 4 行、八月要 6 行，
 * 按实际行数画的话两个月的日历差着整整一行（约 90dp）——翻一下月整页的高度就跳一截,
 * 底下那行小字跟着上下弹。多出来的那一行是空格子，不画东西，只占位。
 *
 * 格子边长取「宽度铺开」与「[heightBudget] 装得下 6 行」里的小值：不给预算时按宽度
 * 铺满（老行为），给了预算就横向收窄、整体居中，让整个日历一屏放得下。
 *
 * 左右留 10dp、格间 [GRID_GAP]：海报按原比例铺开后，横向每省下的一点都直接变成海报宽度。
 */
@Composable
private fun MonthGrid(
    month: YearMonth,
    locale: Locale,
    palette: DailyStampPalette,
    today: LocalDate,
    firstDay: LocalDate?,
    cells: Map<LocalDate, DailyStampCellUi>,
    openable: Set<LocalDate>,
    hinted: LocalDate?,
    /** 网格能占的最大高度，见 [DailyStampCalendar] 的同名参数 */
    heightBudget: Dp,
    onDayClick: (LocalDate) -> Unit,
    onUnarrivedClick: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    val leading = remember(month, locale) {
        val first = WeekFields.of(locale).firstDayOfWeek
        (month.atDay(1).dayOfWeek.value - first.value + 7) % 7
    }
    val length = month.lengthOfMonth()
    BoxWithConstraints(
        modifier = modifier.fillMaxWidth().padding(horizontal = 10.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        val gaps = GRID_GAP * (7 - 1)
        val byWidth = (maxWidth - gaps) / 7
        // 一格的高度 = 上下白边 + 海报（宽 ÷ 2:3）+ 关键词那一行，反解出宽度
        val byHeight = if (heightBudget.isSpecified) {
            val row = (heightBudget - GRID_GAP * (MONTH_ROWS - 1)) / MONTH_ROWS
            (row - TILE_INSET * 2 - KEYWORD_LINE) * POSTER_ASPECT
        } else {
            byWidth
        }
        // 下限：预算再紧也不能把格子压成看不清海报的一小块，那时宁可让整页滚起来。
        // 外层再夹一次 byWidth —— 屏幕本来就窄到放不下 7 个下限宽的格子时，
        // 横向溢出比格子小更糟
        val tile = minOf(byWidth, maxOf(byHeight, TILE_MIN_WIDTH))
        Column(
            modifier = Modifier.width(tile * 7 + gaps),
            verticalArrangement = Arrangement.spacedBy(GRID_GAP),
        ) {
            repeat(MONTH_ROWS) { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(GRID_GAP)) {
                    repeat(7) { column ->
                        val dayOfMonth = row * 7 + column - leading + 1
                        Box(modifier = Modifier.width(tile)) {
                            if (dayOfMonth in 1..length) {
                                val date = month.atDay(dayOfMonth)
                                val cell = cells[date]
                                val kind = dayKind(
                                    date = date,
                                    today = today,
                                    firstUse = firstDay,
                                    stamped = cell != null,
                                )
                                DayCell(
                                    date = date,
                                    cell = cell,
                                    kind = kind,
                                    palette = palette,
                                    isToday = date == today,
                                    daysAhead = (date.toEpochDay() - today.toEpochDay()).toInt(),
                                    hinted = date == hinted,
                                    onClick = when {
                                        // 你来之前那些天不开卡片，只回一句话
                                        kind == DayKind.Unarrived -> {
                                            { onUnarrivedClick(date) }
                                        }
                                        date in openable -> {
                                            { onDayClick(date) }
                                        }
                                        // 台词解析不出来的那天点了也没有卡可开，索性不给点
                                        else -> null
                                    },
                                )
                            } else {
                                // 月初月末与补出来的那一行只占格，不画任何东西。高度要和有内容的
                                // 格子一致（纸的白边 + 海报 + 关键词那一行），否则那几行会矮一截。
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(TILE_INSET)
                                ) {
                                    Spacer(Modifier.fillMaxWidth().aspectRatio(POSTER_ASPECT))
                                    Spacer(Modifier.height(KEYWORD_LINE))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 一格：一张纸，纸上贴着那天的海报，海报底下印着关键词。
 *
 * 纸色不跟随主题（[DailyStampPalette.tile]）。深色主题下跟着主题走的格子只是一块比底色
 * 高一点的深灰，一整月看过去什么都没有；固定的纸黄让三十一格看起来是一版票根摊在桌上，
 * 而海报按原始 2:3 比例贴在纸上，四周留一圈白边——照片裱在纸上是这一屏的样子。
 *
 * 四种日子（[DayKind]）靠纸和标记区分，不靠字的深浅：
 * - 签到过：整张纸 + 海报 + 关键词，这是这一屏的奖励，只有真的来过那天才有
 * - 错过：旧一档的纸，海报的位置留一圈虚线，位子在人没来；点开仍能读到那天的台词
 * - 你来之前：连纸都没有，只有一个日期数字——空白本身就是「那时你还没来」
 * - 还没到：纸还在，右上角折起来一角，像那一页还没翻开；折角按距今天数递减
 *
 * 纸上的日期数字走 [DailyStampPalette.tileInk]（8.1:1），海报上那个走白色压在渐变上。
 * 没有纸的那一档（你来之前）才用主题的 [DailyStampPalette.inkHint]。
 */
@Composable
private fun DayCell(
    date: LocalDate,
    cell: DailyStampCellUi?,
    kind: DayKind,
    palette: DailyStampPalette,
    isToday: Boolean,
    /** 距今多少天，未来那天的折角按它递减；过去为负数 */
    daysAhead: Int,
    /** 是不是刚被点过的那一格「你来之前」，点过的数字亮一下，指明是哪一格答的话 */
    hinted: Boolean,
    onClick: (() -> Unit)?,
) {
    val hasPoster = cell?.poster != null
    // 你来之前那些天不给纸：那一档要的就是空白
    val tile = when (kind) {
        DayKind.Stamped -> palette.tile
        DayKind.Missed -> palette.tileMissed
        DayKind.Future -> palette.tileLatent
        DayKind.Unarrived -> Color.Transparent
    }
    val edge = when {
        kind == DayKind.Unarrived -> Color.Transparent
        isToday -> palette.tileSeal
        else -> palette.tileEdge.copy(alpha = 0.34f)
    }
    val numberColor by animateColorAsState(
        targetValue = when {
            hasPoster -> Color.White.copy(alpha = 0.94f)
            kind == DayKind.Unarrived -> if (hinted) palette.inkSoft else palette.inkHint
            else -> palette.tileInk
        },
        animationSpec = tween(durationMillis = 180),
        label = "dayNumberInk",
    )
    val interactionSource = remember { MutableInteractionSource() }
    val missedStroke = palette.tileEdge.copy(alpha = 0.55f)
    val missedLabel = stringResource(R.string.daily_stamp_missed)
    val latentLabel = stringResource(R.string.daily_stamp_latent)
    // 越远越淡：明天那一格最清楚，两周之后收到四成五，再远就只是「有那么一页」
    val foldAlpha = remember(daysAhead) {
        if (daysAhead <= 0) 0f
        else (1f - (daysAhead - 1) / 14f * 0.55f).coerceIn(0.45f, 1f)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(TILE_RADIUS))
            .background(tile)
            // 纸边先描，折角后画：折角要把右上那一段纸边连着纸一起切掉，
            // 顺序反过来就会有一道线横穿那个缺口
            .drawBehind {
                drawTileEdge(edge, if (isToday) 1.2.dp else 0.7.dp)
                if (kind == DayKind.Future) {
                    drawFoldedCorner(
                        back = palette.tileBack,
                        crease = palette.tileEdge,
                        cut = palette.paper,
                        alpha = foldAlpha,
                    )
                }
            }
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        onClick = onClick,
                    )
                } else {
                    Modifier
                }
            )
            .semantics {
                contentDescription = dayDescription(date, cell, kind, missedLabel, latentLabel)
            }
            .padding(TILE_INSET),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(POSTER_ASPECT)
                .clip(RoundedCornerShape(3.dp))
                .drawBehind {
                    if (kind == DayKind.Missed) drawMissedFrame(missedStroke)
                },
        ) {
            if (cell?.poster != null) {
                CellPoster(model = cell.poster)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(18.dp)
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Black.copy(alpha = 0.52f), Color.Transparent)
                            )
                        )
                )
            }
            Text(
                text = date.dayOfMonth.toString(),
                modifier = Modifier.align(Alignment.TopStart).padding(start = 3.dp, top = 1.dp),
                color = numberColor,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
        CellKeyword(
            keyword = cell?.keyword.orEmpty(),
            palette = palette,
        )
    }
}

/** 纸边：一圈发丝线。今天那一格换成朱红，粗一点——那是印在纸边上的记号，不是选中态 */
private fun DrawScope.drawTileEdge(color: Color, width: Dp) {
    if (color == Color.Transparent) return
    val stroke = width.toPx()
    val inset = stroke / 2f
    drawRoundRect(
        color = color,
        topLeft = Offset(inset, inset),
        size = Size(size.width - stroke, size.height - stroke),
        cornerRadius = CornerRadius(TILE_RADIUS.toPx()),
        style = Stroke(width = stroke),
    )
}

/**
 * 错过那天的虚线空框，画在海报本该贴的那个位置。
 *
 * 虚线对海报：签到过的格子那儿贴着一张图，错过的只剩一圈虚线，两者一眼分得开，
 * 而虚线本身就是「这里该有东西」的写法。颜色走纸上的墨（[DailyStampPalette.tileEdge]）
 * 而不是主题色——纸是固定的浅色，深色主题的浅墨压在纸上等于白画一圈。
 */
private fun DrawScope.drawMissedFrame(color: Color) {
    val stroke = 1.dp.toPx()
    val dash = 3.dp.toPx()
    val radius = CornerRadius(3.dp.toPx())
    // 描边沿路径居中长，整圈往里收半个线宽才不会有一半落在圆角裁切之外
    val inset = stroke / 2f
    drawRoundRect(
        color = color,
        topLeft = Offset(inset, inset),
        size = Size(size.width - stroke, size.height - stroke),
        cornerRadius = radius,
        style = Stroke(
            width = stroke,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash, dash), 0f),
        ),
    )
}

/**
 * 还没到那天那一角，真的折过来。
 *
 * 三笔，缺一笔就不像折角：
 * 1. **切掉**：右上角那个三角用整屏底色 [cut] 盖住，那块纸不在原来的位置了；
 * 2. **翻过来**：被折下来的正是同一个三角，绕折痕翻转后落在纸面里侧——角点 (w,0)
 *    以折痕为轴的镜像正好是 (w−side, side)，所以折面是
 *    [(w−side,0), (w,side), (w−side,side)] 这个三角，填的是纸背色 [back]，比正面深；
 * 3. **压折痕**：斜边补一道 [crease] 发丝线，纸才有厚度。
 *
 * 先前只做了第 1 和第 3 笔：角上少一块、斜边一道线，看着像被谁划了一刀，
 * 而不是一页翻起来的角——折角之所以认得出，靠的是那块翻过来的纸背。
 *
 * [alpha] 按距今天数递减（见调用处）：明天那一页最清楚，越远越像还没走到。
 */
private fun DrawScope.drawFoldedCorner(back: Color, crease: Color, cut: Color, alpha: Float) {
    if (alpha <= 0f) return
    val side = size.width * FOLD_RATIO
    val hinge = Offset(size.width - side, 0f)
    val tip = Offset(size.width, side)
    val cutAway = Path().apply {
        moveTo(hinge.x, hinge.y)
        lineTo(size.width, 0f)
        lineTo(tip.x, tip.y)
        close()
    }
    drawPath(path = cutAway, color = cut)
    val flap = Path().apply {
        moveTo(hinge.x, hinge.y)
        lineTo(tip.x, tip.y)
        lineTo(hinge.x, side)
        close()
    }
    drawPath(path = flap, color = back, alpha = alpha)
    drawLine(
        color = crease,
        start = hinge,
        end = tip,
        strokeWidth = 0.8.dp.toPx(),
        alpha = alpha * 0.55f,
    )
}

/**
 * 读屏念出来的那一格。
 *
 * 关键词是那天的内容，能念就念；错过和还没到各有一个词，否则读屏只会念出一串
 * 光秃秃的数字，看不见的人分不出这三种格子。
 */
private fun dayDescription(
    date: LocalDate,
    cell: DailyStampCellUi?,
    kind: DayKind,
    missedLabel: String,
    latentLabel: String,
): String {
    val day = date.dayOfMonth.toString()
    return when {
        cell != null && cell.keyword.isNotBlank() -> "$day ${cell.keyword}"
        kind == DayKind.Missed -> "$day $missedLabel"
        kind == DayKind.Future -> "$day $latentLabel"
        else -> day
    }
}

/**
 * 纸上贴的那张海报。
 *
 * 显式给一个解码尺寸：一屏最多 31 张，按原图 342px 宽解码是没必要的内存开销，而格子
 * 宽也就 45dp 上下。160px 够铺满格子还留一点余量，不至于在大屏上发虚。
 *
 * 不再按主题压亮度：海报现在贴在一张浅色纸上，压暗只会让它比纸还灰。早先海报直接
 * 铺在深色格子上，满亮度会一格一格地扎眼，那是没有纸的时候的事。
 */
@Composable
private fun CellPoster(model: Any) {
    val context = LocalContext.current
    val request = remember(model) {
        ImageRequest.Builder(context)
            .data(model)
            .size(POSTER_DECODE_PX)
            .scale(Scale.FILL)
            .crossfade(false)
            .build()
    }
    AsyncImage(
        model = request,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxSize(),
    )
}

/**
 * 海报底下那行关键词，印在纸上。
 *
 * 一行写完，放不下就省略号。以前关键词压在格子中央的淡海报上，宽度只够两三个字，
 * 拉丁词只能退成一个首字母；现在它独占一行，长词也照原样显示，格子里就是完整的词。
 *
 * 高度是**最小值**而不是固定值：钉死 14dp 的话，系统字号放大到 1.2 倍以上时这行字
 * 比框还高，上下被切掉一截——看起来像两个字叠在一起。空关键词也要占住这个最小高度，
 * 整行都没有关键词时这一行不能比别行矮。
 */
@Composable
private fun CellKeyword(
    keyword: String,
    palette: DailyStampPalette,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = KEYWORD_LINE),
        contentAlignment = Alignment.Center,
    ) {
        if (keyword.isNotBlank()) {
            Text(
                text = keyword,
                color = palette.tileInkSoft,
                fontSize = 9.5.sp,
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * 底部一行小字，四种情况说四句话。
 *
 * [hinted] 是刚点过「你来之前」那种格子：这一行临时换成东隅那句。不用 Snackbar——
 * 那是一条黑底的 Material 组件，弹在纸面日历上会把这一屏的质感打断，而这行字
 * 本来就是「说明」的位置。用完整的「东隅已逝，桑榆非晚」而不是只留上半句：
 * 后半句才是要说的话，光说前半句是责备，配上后半句是邀请。
 */
@Composable
private fun FooterHint(
    palette: DailyStampPalette,
    hinted: Boolean,
    future: Boolean,
    empty: Boolean,
) {
    Text(
        text = stringResource(
            when {
                hinted -> R.string.daily_stamp_unarrived_hint
                future -> R.string.daily_stamp_future_month
                empty -> R.string.daily_stamp_empty
                else -> R.string.daily_stamp_hint
            }
        ),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp),
        color = palette.inkHint,
        fontSize = 10.5.sp,
        letterSpacing = 0.14.em,
        textAlign = TextAlign.Center,
    )
}

/** 海报的原始比例，2:3。整月是一墙小海报，比例一改就不像海报了 */
private const val POSTER_ASPECT = 2f / 3f

/**
 * 月视图固定画几行。
 *
 * 6 是任何月份最多要的周数（30 天从周末起头、31 天也一样）。固定成 6 而不是按当月
 * 实际算，是为了让**每个月的日历一样高**：按实际算的话 5 行的九月比 6 行的八月矮
 * 整整一行，翻月时整页高度跳一截。
 */
private const val MONTH_ROWS = 6

/** 格与格之间的缝。海报按原比例铺开后，横向每省下的一点都直接变成海报宽度 */
private val GRID_GAP = 3.dp

/** 格子边长的下限。再窄海报就只是一个色块，不如让整页滚起来 */
private val TILE_MIN_WIDTH = 30.dp

/** 横滑翻月的位移阈值。比系统触摸阈值宽出不少，免得竖着滚的时候顺手翻了月 */
private val MONTH_SWIPE_THRESHOLD = 56.dp

/**
 * 日历除网格以外那几件东西的高度：报头 51 + 10 + 星期表头 14 + 4 + 14 + 底部小字 14。
 *
 * 两个入口都要用它反算网格的高度预算，所以是 `internal`。跟着 [MonthMasthead]、
 * [WeekdayRow]、[FooterHint] 改，改了这里就该跟着改 —— 估小了网格会顶出一屏，
 * 估大了格子白白缩一圈。
 */
internal val DAILY_STAMP_CALENDAR_CHROME = 107.dp

/** 纸的圆角。纸片不是卡片，角不该圆到像个按钮 */
private val TILE_RADIUS = 5.dp

/** 海报四周留的那圈白边，照片裱在纸上的样子。再宽就吃海报，再窄就看不出有纸 */
private val TILE_INSET = 2.5.dp

/** 关键词那一行的最小高度，见 [CellKeyword] */
private val KEYWORD_LINE = 14.dp

/** 格子里海报的解码宽度：格子宽 45dp 上下，160px 铺满还留余量 */
private const val POSTER_DECODE_PX = 160

/** 「东隅已逝」那句话在底下留多久。够读完两句七个字，又不至于让人以为它是常驻文案 */
private const val HINT_HOLD_MS = 2400L

/** 折角占格子宽的比例。再大就不像折角，像把右上角剪掉了 */
private const val FOLD_RATIO = 0.30f

/** 卡片升起时底下那一屏的模糊半径，见 [dailyStampCardBlur] */
private val CARD_BLUR_RADIUS = 13.dp
