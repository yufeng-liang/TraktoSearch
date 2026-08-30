package com.tracktosearch.ui.screen.dailystamp

import android.os.Build
import androidx.compose.animation.animateColorAsState
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
        // 卡片升起时把日历推到景深之外。Modifier.blur 要 API 31+，低版本靠浮层
        // 自己那层更重的压暗顶上（见 DailyStampCardOverlay 的 scrimColor）——
        // 没有模糊时如果连焦点变化都没有，卡片会像贴在日历上而不是浮在上面。
        val blurRadius by animateDpAsState(
            targetValue = if (content.sheet != null) 13.dp else 0.dp,
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
                    openable = content.sheets.keys,
                    onPreviousMonth = viewModel::previousMonth,
                    onNextMonth = viewModel::nextMonth,
                    onDayClick = { date -> viewModel.select(date) },
                )
                Spacer(Modifier.height(24.dp))
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
) {
    // 点了「你来之前」那种格子的那一下：底下那行字临时换成东隅那句，过一会儿换回来
    var hinted by remember { mutableStateOf<LocalDate?>(null) }
    LaunchedEffect(hinted) {
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
        Spacer(Modifier.height(18.dp))
        WeekdayRow(locale = locale, palette = palette)
        Spacer(Modifier.height(6.dp))
        MonthGrid(
            month = state.month,
            locale = locale,
            palette = palette,
            today = today,
            firstDay = state.firstDay,
            cells = cells,
            openable = openable,
            hinted = hinted,
            onDayClick = onDayClick,
            onUnarrivedClick = { date -> hinted = date },
        )
        Spacer(Modifier.height(22.dp))
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
            color = palette.inkMuted,
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
            // 禁用态从 inkSoft 收一档，不走 inkFaint：那是描边色，压在底色上只有 1.0:1，
            // 「淡下去」会淡成看不见，正好把上面那句注释想避开的事又做了一遍
            tint = if (enabled) palette.inkSoft else palette.inkSoft.copy(alpha = 0.45f),
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
            color = palette.inkHint,
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
                color = palette.inkHint,
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
    firstDay: LocalDate?,
    cells: Map<LocalDate, DailyStampCellUi>,
    openable: Set<LocalDate>,
    hinted: LocalDate?,
    onDayClick: (LocalDate) -> Unit,
    onUnarrivedClick: (LocalDate) -> Unit,
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
 * 四种日子（[DayKind]）靠标记区分，不靠字的深浅：
 * - 签到过：海报 + 关键词 + 一道实线淡框，这是这一屏的奖励，只有真的来过那天才有
 * - 错过：一圈虚线空框，位子留着人没来；点开仍能读到那天的台词，但海报不上墙
 * - 你来之前：只有日期数字，没有底、没有框、没有标记——空白本身就是「那时你还没来」
 * - 还没到：极淡的纸面 + 右上角一个折角，像那一页还没翻开；折角按距今天数递减
 *
 * 日期数字四种都用 [DailyStampPalette.inkHint]（4.9:1 / 9.4:1）。8sp 的数字是信息不是
 * 装饰，不该为了「淡下去」压到 3:1 那一档；要淡的是标记，不是日期本身。
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
    val stamped = kind == DayKind.Stamped
    val borderColor = when {
        isToday -> palette.seal.copy(alpha = 0.72f)
        stamped -> palette.inkFaint.copy(alpha = 0.5f)
        else -> Color.Transparent
    }
    // 还没到的日子给一层极淡的纸：折角要有纸可折，纯透明的格子折不出角来
    val fill = when {
        stamped -> palette.cream.copy(alpha = if (palette.isDark) 0.5f else 0.6f)
        kind == DayKind.Future -> palette.cream.copy(alpha = if (palette.isDark) 0.18f else 0.22f)
        else -> Color.Transparent
    }
    val numberColor by animateColorAsState(
        targetValue = if (hinted) palette.inkSoft else palette.inkHint,
        animationSpec = tween(durationMillis = 180),
        label = "dayNumberInk",
    )
    val interactionSource = remember { MutableInteractionSource() }
    val missedStroke = palette.inkMuted
    val foldInk = palette.inkMuted
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
            },
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(POSTER_ASPECT)
                .clip(RoundedCornerShape(5.dp))
                .background(fill)
                .border(if (isToday) 1.2.dp else 0.7.dp, borderColor, RoundedCornerShape(5.dp))
                .drawBehind {
                    when (kind) {
                        DayKind.Missed -> drawMissedFrame(missedStroke)
                        DayKind.Future -> drawFoldedCorner(foldInk, foldAlpha, palette.paper)
                        else -> Unit
                    }
                },
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
                color = if (hasPoster) Color.White.copy(alpha = 0.92f) else numberColor,
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
 * 错过那天的虚线空框。
 *
 * 虚线对实线：签到过的格子是实线淡框加一张海报，两者一眼分得开，而虚线本身就是
 * 「这里该有东西」的写法。颜色走 inkMuted 而不是 inkFaint——描边色压在底色上只有
 * 1.1:1，看不出是一圈框；inkMuted 是 3.3:1，刚过非文本那条 3:1 的线。
 */
private fun DrawScope.drawMissedFrame(color: Color) {
    val stroke = 1.dp.toPx()
    val dash = 3.dp.toPx()
    val radius = CornerRadius(5.dp.toPx())
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
 * 还没到那天的折角。
 *
 * 右上角切掉一个三角，用整屏底色 [paper] 盖，看起来是这一页的角被折起来了；斜边补一道
 * 发丝线，折痕才有厚度。日期数字在左上角，右上角是空的，两者不打架。
 *
 * [alpha] 按距今天数递减（见调用处）：明天那一页最清楚，越远越像还没走到。
 */
private fun DrawScope.drawFoldedCorner(ink: Color, alpha: Float, paper: Color) {
    if (alpha <= 0f) return
    val side = size.width * FOLD_RATIO
    val corner = Path().apply {
        moveTo(size.width - side, 0f)
        lineTo(size.width, 0f)
        lineTo(size.width, side)
        close()
    }
    drawPath(path = corner, color = paper, alpha = alpha)
    drawLine(
        color = ink,
        start = Offset(size.width - side, 0f),
        end = Offset(size.width, side),
        strokeWidth = 0.8.dp.toPx(),
        alpha = alpha,
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

/** 关键词那一行的高度。所有格子都留着，空的也留，否则整行没词时这一行会矮一截 */
private val KEYWORD_LINE = 14.dp

/** 格子里海报的解码宽度：格子宽 45dp 上下，160px 铺满还留余量 */
private const val POSTER_DECODE_PX = 160

/** 「东隅已逝」那句话在底下留多久。够读完两句七个字，又不至于让人以为它是常驻文案 */
private const val HINT_HOLD_MS = 2400L

/** 折角占格子宽的比例。再大就不像折角，像把右上角剪掉了 */
private const val FOLD_RATIO = 0.30f
