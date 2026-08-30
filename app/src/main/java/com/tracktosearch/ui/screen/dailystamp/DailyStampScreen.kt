package com.tracktosearch.ui.screen.dailystamp

import android.os.Build
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Scale
import com.tracktosearch.R
import com.tracktosearch.data.local.SplashQuote
import androidx.compose.ui.platform.LocalContext
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale

/**
 * 日签页：一个月的签到日历，每格印着那天的海报和关键词。
 *
 * 颜色跟随当前主题（[DailyStampPalette]），不再是开屏那张暖黄纸；衬线字和拉开的字距
 * 留着——那是日签自己的字面性格，和纸色无关。台词卡片仍是票根质感的纸，见
 * [rememberDailyStampCardPalette]。
 *
 * 格子是「上海报下关键词」：海报按原始 2:3 比例铺满上半格，关键词单独一行落在底下。
 * 早先的版本把关键词压在淡海报上，海报只剩色温差，等于白下载一张图。
 *
 * 点格子升起卡片浮层（[DailyStampCardOverlay]），不是 sheet：sheet 从底部推上来会
 * 把日历顶走，而这张卡应该是从那一格里长出来的。
 *
 * 这一屏也被设置页的「每日台词」二级页复用（只嵌 [DailyStampCalendar] 那一段）。
 */
@Composable
fun DailyStampScreen(
    onBack: () -> Unit,
    onQuoteClick: (tmdbId: Int, title: String, year: Int, posterUrl: String) -> Unit,
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
        // 卡片升起时把日历推到景深之外。Modifier.blur 要 API 31+，低版本靠浮层
        // 自己那层更重的压暗顶上（见 DailyStampCardOverlay 的 scrimColor）——
        // 没有模糊时如果连焦点变化都没有，卡片会像贴在日历上而不是浮在上面。
        val blurRadius by animateDpAsState(
            targetValue = if (content.card != null) 13.dp else 0.dp,
            animationSpec = tween(durationMillis = 240),
            label = "dailyStampBlur",
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && blurRadius > 0.dp) {
                        Modifier.blur(blurRadius)
                    } else {
                        Modifier
                    }
                )
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            DailyStampTopBar(palette = palette, onBack = onBack)
            // 海报按 2:3 铺开后六行网格在多数机型上都超过一屏，内容必须能滚
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                DailyStampCalendar(
                    state = state,
                    palette = palette,
                    locale = content.locale,
                    today = content.today,
                    cells = content.cells,
                    onPreviousMonth = viewModel::previousMonth,
                    onNextMonth = viewModel::nextMonth,
                    onDayClick = { date -> viewModel.select(date) },
                )
                Spacer(Modifier.height(24.dp))
            }
        }
        DailyStampCardOverlay(
            card = content.card,
            palette = cardPalette,
            openableDates = content.openableDates,
            onSelect = { date -> viewModel.select(date) },
            onDismiss = { viewModel.select(null) },
            onQuoteClick = onQuoteClick,
        )
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
    val card: DailyStampCardUi?,
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
    val card = remember(state.selected, state.stamps, lang) {
        state.selected
            ?.let { date -> state.stamps.firstOrNull { it.date == date } }
            ?.toCard(lang)
    }
    // 卡片左右滑动只在当月能打开的那些天之间走：越过月边界就得先换月加载，
    // 换月本来就有上一页/下一页两个箭头，没必要在卡片里再实现一遍。
    val openableDates = remember(state.stamps) {
        state.stamps.filter { it.openable }.map { it.date }
    }
    return DailyStampContent(
        locale = locale,
        today = today,
        cells = cells,
        card = card,
        openableDates = openableDates,
    )
}

/**
 * 日历本体：月份报头 + 星期表头 + 月视图网格 + 底部一行小字。
 *
 * 不含顶栏、不含背景、不含卡片浮层——浮层要盖满整屏，只能由各自的页面挂在最外层，
 * 嵌在这里会被裁进日历那一块。
 */
@Composable
internal fun DailyStampCalendar(
    state: DailyStampUiState,
    palette: DailyStampPalette,
    locale: Locale,
    today: LocalDate,
    cells: Map<LocalDate, DailyStampCellUi>,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onDayClick: (LocalDate) -> Unit,
) {
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
        Spacer(Modifier.height(18.dp))
        WeekdayRow(locale = locale, palette = palette)
        Spacer(Modifier.height(6.dp))
        MonthGrid(
            month = state.month,
            locale = locale,
            palette = palette,
            today = today,
            cells = cells,
            onDayClick = onDayClick,
        )
        Spacer(Modifier.height(22.dp))
        FooterHint(
            palette = palette,
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
 * 月份报头：年月刻度 + 月名 + 左右翻页 + 两个计数。
 *
 * 年月那行用等宽字体、拉开字距，和开屏顶上的日期是同一种处理——那是这两屏之间
 * 最直接的呼应。月名走衬线大字，locale 自己给「八月 / August / 8月 / 8월」。
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
            .padding(top = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = tick,
            color = palette.inkFaint,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 0.42.em,
        )
        Spacer(Modifier.height(6.dp))
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
                fontSize = 27.sp,
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
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Counter(
                value = pluralStringResource(R.plurals.daily_stamp_day_count, streak, streak),
                label = stringResource(R.string.daily_stamp_streak),
                palette = palette,
            )
            Spacer(
                Modifier
                    .padding(horizontal = 20.dp)
                    .width(1.dp)
                    .height(22.dp)
                    .background(palette.inkFaint.copy(alpha = 0.4f))
            )
            Counter(
                value = pluralStringResource(R.plurals.daily_stamp_day_count, total, total),
                label = stringResource(R.string.daily_stamp_total),
                palette = palette,
            )
        }
    }
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
            tint = if (enabled) palette.inkSoft else palette.inkFaint.copy(alpha = 0.28f),
            modifier = Modifier.size(20.dp),
        )
    }
}

/** 数字在上、名目在下：一眼先看到「几天」，再看到那是连续还是累计 */
@Composable
private fun Counter(
    value: String,
    label: String,
    palette: DailyStampPalette,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            color = palette.ink,
            fontSize = 16.sp,
            fontFamily = FontFamily.Serif,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = label,
            modifier = Modifier.padding(top = 2.dp),
            color = palette.inkFaint,
            fontSize = 9.5.sp,
            letterSpacing = 0.2.em,
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
                color = palette.inkFaint,
                fontSize = 10.sp,
                letterSpacing = 0.1.em,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * 月视图网格。
 *
 * 用 Column + Row 手排 7 列而不是 LazyVerticalGrid：一个月最多 42 格，而 lazy 网格
 * 嵌在可滚动的 Column 里必须先给死高度，反而更绕。
 *
 * 左右留 10dp、格间 4dp：海报按原比例铺开后，横向每省下的一点都直接变成海报宽度。
 */
@Composable
private fun MonthGrid(
    month: YearMonth,
    locale: Locale,
    palette: DailyStampPalette,
    today: LocalDate,
    cells: Map<LocalDate, DailyStampCellUi>,
    onDayClick: (LocalDate) -> Unit,
) {
    val leading = remember(month, locale) {
        val first = WeekFields.of(locale).firstDayOfWeek
        (month.atDay(1).dayOfWeek.value - first.value + 7) % 7
    }
    val length = month.lengthOfMonth()
    val rows = (leading + length + 6) / 7
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        repeat(rows) { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                repeat(7) { column ->
                    val dayOfMonth = row * 7 + column - leading + 1
                    Box(modifier = Modifier.weight(1f)) {
                        if (dayOfMonth in 1..length) {
                            val date = month.atDay(dayOfMonth)
                            DayCell(
                                date = date,
                                cell = cells[date],
                                palette = palette,
                                isToday = date == today,
                                isFuture = date.isAfter(today),
                                onClick = { onDayClick(date) },
                            )
                        } else {
                            // 月初月末的空位只占格，不画任何东西。高度要和有内容的格子
                            // 一致（海报 + 关键词那一行），否则首末行会比中间几行矮。
                            Column {
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

/**
 * 一格：上半格是海报，底下一行是关键词。
 *
 * 海报按原始 2:3 比例铺满，不裁成方块——一整月看过去是一墙小海报，那是这一屏的主体。
 * 日期退成海报左上角的小刻度，压在一道自上而下的浅暗渐变上，否则浅色海报上的小字
 * 读不出来。
 *
 * 没签到过的格子只留日期数字，未来的日子再淡一档，不画空框：空框比空白更吵。关键词
 * 那一行的高度所有格子都留着，不然整行没有关键词时这一行会比别行矮。
 */
@Composable
private fun DayCell(
    date: LocalDate,
    cell: DailyStampCellUi?,
    palette: DailyStampPalette,
    isToday: Boolean,
    isFuture: Boolean,
    onClick: () -> Unit,
) {
    val stamped = cell != null
    val hasPoster = cell?.poster != null
    val borderColor = when {
        isToday -> palette.seal.copy(alpha = 0.72f)
        stamped -> palette.inkFaint.copy(alpha = 0.5f)
        else -> Color.Transparent
    }
    val interactionSource = remember { MutableInteractionSource() }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (cell?.openable == true) {
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
                contentDescription = if (cell != null && cell.keyword.isNotBlank()) {
                    "${date.dayOfMonth} ${cell.keyword}"
                } else {
                    date.dayOfMonth.toString()
                }
            },
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(POSTER_ASPECT)
                .clip(RoundedCornerShape(5.dp))
                .background(
                    if (stamped) palette.cream.copy(alpha = if (palette.isDark) 0.5f else 0.6f)
                    else Color.Transparent
                )
                .border(if (isToday) 1.2.dp else 0.7.dp, borderColor, RoundedCornerShape(5.dp)),
        ) {
            if (cell?.poster != null) {
                CellPoster(model = cell.poster, palette = palette)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(15.dp)
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Black.copy(alpha = 0.45f), Color.Transparent)
                            )
                        )
                )
            }
            Text(
                text = date.dayOfMonth.toString(),
                modifier = Modifier.align(Alignment.TopStart).padding(start = 4.dp, top = 2.dp),
                color = when {
                    hasPoster -> Color.White.copy(alpha = 0.92f)
                    isFuture -> palette.inkFaint.copy(alpha = 0.34f)
                    stamped -> palette.inkFaint
                    else -> palette.inkFaint.copy(alpha = 0.62f)
                },
                fontSize = 8.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
        CellKeyword(
            keyword = cell?.keyword.orEmpty(),
            palette = palette,
        )
    }
}

/**
 * 格子里的海报。
 *
 * 显式给一个解码尺寸：一屏最多 31 张，按原图 342px 宽解码是没必要的内存开销，而格子
 * 宽也就 45dp 上下。160px 够铺满格子还留一点余量，不至于在大屏上发虚。
 *
 * 只在深色主题下压一点亮度：满亮度的小海报在深底上会一格一格地扎眼。
 */
@Composable
private fun CellPoster(
    model: Any,
    palette: DailyStampPalette,
) {
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
        alpha = if (palette.isDark) 0.88f else 1f,
        modifier = Modifier.fillMaxSize(),
    )
}

/**
 * 海报底下那行关键词。
 *
 * 一行写完，放不下就省略号。以前关键词压在格子中央的淡海报上，宽度只够两三个字，
 * 拉丁词只能退成一个首字母；现在它独占一行，长词也照原样显示，格子里就是完整的词。
 *
 * 空关键词也要占住这一行的高度：整行都没有关键词时，这一行不能比别行矮。
 */
@Composable
private fun CellKeyword(
    keyword: String,
    palette: DailyStampPalette,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(KEYWORD_LINE),
        contentAlignment = Alignment.Center,
    ) {
        if (keyword.isNotBlank()) {
            Text(
                text = keyword,
                color = palette.ink,
                fontSize = 9.sp,
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** 底部一行小字：这个月一片空白时换成「明天打开就有了」，不摆空状态插画 */
@Composable
private fun FooterHint(
    palette: DailyStampPalette,
    empty: Boolean,
) {
    Text(
        text = stringResource(
            if (empty) R.string.daily_stamp_empty else R.string.daily_stamp_hint
        ),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp),
        color = palette.inkFaint,
        fontSize = 10.5.sp,
        letterSpacing = 0.14.em,
        textAlign = TextAlign.Center,
    )
}

/** 海报的原始比例，2:3。整月是一墙小海报，比例一改就不像海报了 */
private const val POSTER_ASPECT = 2f / 3f

/** 关键词那一行的高度。所有格子都留着，空的也留，否则整行没词时这一行会矮一截 */
private val KEYWORD_LINE = 14.dp

/** 格子里海报的解码宽度：格子宽 45dp 上下，160px 铺满还留余量 */
private const val POSTER_DECODE_PX = 160
