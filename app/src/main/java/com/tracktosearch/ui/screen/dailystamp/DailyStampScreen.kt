package com.tracktosearch.ui.screen.dailystamp

import android.os.Build
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Scale
import com.tracktosearch.R
import com.tracktosearch.data.local.SplashQuote
import com.tracktosearch.ui.screen.splash.SplashPalette
import com.tracktosearch.ui.screen.splash.grainBrush
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale

/**
 * 日签页：一个月的签到日历，每格印着那天的关键词。
 *
 * 视觉沿用开屏台词层的暖纸色板与胶片颗粒（[SplashPalette]、[grainBrush]），
 * 不跟随主题强调色——日签是开屏那一句的延续，两屏必须看着是同一张纸。
 *
 * 格子上印的是关键词而不是日期数字：日期退成角上的小刻度，一屏扫过去读到的是
 * 这个月攒下的一串词。关键词是 CJK 且不超过 4 字时按印文排布；拉丁词塞不进格子，
 * 退成一个衬线首字母，像花体起首——完整的词在卡片里。
 *
 * 点格子升起卡片浮层（[DailyStampCardOverlay]），不是 sheet：sheet 从底部推上来会
 * 把日历顶走，而这张卡应该是从那一格里长出来的。
 */
@Composable
fun DailyStampScreen(
    onBack: () -> Unit,
    onQuoteClick: (tmdbId: Int, title: String, year: Int, posterUrl: String) -> Unit,
    viewModel: DailyStampViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val palette = if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) {
        SplashPalette.Dark
    } else {
        SplashPalette.Light
    }
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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.paper)
    ) {
        DailyStampBackdrop(palette)
        // 卡片升起时把日历推到景深之外。Modifier.blur 要 API 31+，低版本靠浮层
        // 自己那层更重的压暗顶上（见 DailyStampCardOverlay 的 scrimColor）——
        // 没有模糊时如果连焦点变化都没有，卡片会像贴在日历上而不是浮在上面。
        val blurRadius by animateDpAsState(
            targetValue = if (card != null) 13.dp else 0.dp,
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
            MonthMasthead(
                month = state.month,
                locale = locale,
                palette = palette,
                streak = state.streak,
                total = state.total,
                canGoPrevious = state.canGoPrevious,
                canGoNext = state.canGoNext,
                onPrevious = viewModel::previousMonth,
                onNext = viewModel::nextMonth,
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
                onDayClick = { date -> viewModel.select(date) },
            )
            Spacer(Modifier.height(22.dp))
            FooterHint(
                palette = palette,
                empty = state.stamps.isEmpty() && !state.loading,
            )
        }
        DailyStampCardOverlay(
            card = card,
            palette = palette,
            openableDates = openableDates,
            onSelect = { date -> viewModel.select(date) },
            onDismiss = { viewModel.select(null) },
            onQuoteClick = onQuoteClick,
        )
    }
}

/**
 * 背景：顶部一团暖光 + 整屏胶片颗粒。
 *
 * 光晕比开屏弱得多，也不动：开屏只活 4 秒，光晕在呼吸是气氛；日签页要停着读，
 * 背景一直在动就成了干扰。暗色主题下改 Screen 混合，否则深棕叠深底糊成一片黑。
 */
@Composable
private fun DailyStampBackdrop(palette: SplashPalette) {
    val grain = remember { grainBrush() }
    Canvas(modifier = Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas
        val blend = if (palette.isDark) BlendMode.Screen else BlendMode.Multiply
        val center = Offset(0.5f * w, 0.06f * h)
        val radius = 0.92f * w
        drawCircle(
            brush = Brush.radialGradient(
                0f to palette.caramel,
                0.72f to Color.Transparent,
                center = center,
                radius = radius,
            ),
            radius = radius,
            center = center,
            alpha = if (palette.isDark) 0.34f else 0.42f,
            blendMode = blend,
        )
        drawRect(
            brush = grain,
            alpha = palette.grainAlpha * 0.72f,
            blendMode = BlendMode.Multiply,
        )
    }
}

/** 只有返回箭头和标题，没有 Material TopAppBar 的容器色——这一屏的底就是那张纸 */
@Composable
private fun DailyStampTopBar(
    palette: SplashPalette,
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
    palette: SplashPalette,
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
    palette: SplashPalette,
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
    palette: SplashPalette,
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
    palette: SplashPalette,
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
 * 用 Column + Row 手排 7 列而不是 LazyVerticalGrid：一个月最多 42 格、不滚动，
 * 而 lazy 网格嵌在 Column 里必须先给死高度，反而更绕。
 */
@Composable
private fun MonthGrid(
    month: YearMonth,
    locale: Locale,
    palette: SplashPalette,
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
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        repeat(rows) { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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
                            // 月初月末的空位只占格，不画任何东西
                            Spacer(Modifier.fillMaxWidth().aspectRatio(CELL_ASPECT))
                        }
                    }
                }
            }
        }
    }
}

/**
 * 一格。
 *
 * 签到过的格子：海报压得很淡当底纹 + 关键词 + 角上的日期刻度 + 一圈细边。
 * 底纹要压到几乎看不出是海报，只留一点色温差——31 张清晰的小海报是一堵图墙，
 * 会把关键词全盖住；压淡之后整月才是一版票根拼贴。
 *
 * 没签到过的格子只留日期数字，未来的日子再淡一档。都不画空框：空框比空白更吵。
 */
@Composable
private fun DayCell(
    date: LocalDate,
    cell: DailyStampCellUi?,
    palette: SplashPalette,
    isToday: Boolean,
    isFuture: Boolean,
    onClick: () -> Unit,
) {
    val stamped = cell != null
    val borderColor = when {
        isToday -> palette.seal.copy(alpha = 0.72f)
        stamped -> palette.inkFaint.copy(alpha = 0.5f)
        else -> Color.Transparent
    }
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(CELL_ASPECT)
            .clip(RoundedCornerShape(5.dp))
            .background(
                if (stamped) palette.cream.copy(alpha = if (palette.isDark) 0.5f else 0.6f)
                else Color.Transparent
            )
            .border(if (isToday) 1.2.dp else 0.7.dp, borderColor, RoundedCornerShape(5.dp))
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
        if (cell?.poster != null) {
            PosterGhost(model = cell.poster, palette = palette)
        }
        Text(
            text = date.dayOfMonth.toString(),
            modifier = Modifier.align(Alignment.TopStart).padding(start = 4.dp, top = 2.dp),
            color = when {
                isFuture -> palette.inkFaint.copy(alpha = 0.34f)
                stamped -> palette.inkFaint
                else -> palette.inkFaint.copy(alpha = 0.62f)
            },
            fontSize = 8.sp,
            fontFamily = FontFamily.Monospace,
        )
        if (cell != null && cell.keyword.isNotBlank()) {
            CellKeyword(
                keyword = cell.keyword,
                palette = palette,
                modifier = Modifier.align(Alignment.Center).padding(top = 5.dp),
            )
        }
    }
}

/**
 * 格子底纹用的海报。
 *
 * 走 Coil 并显式给一个很小的解码尺寸：一屏 31 张，按原图 342px 宽解码是没必要的
 * 内存开销，而格子只有 40dp 左右。压到 96px 宽足够当底纹。
 */
@Composable
private fun PosterGhost(
    model: Any,
    palette: SplashPalette,
) {
    val context = LocalContext.current
    val request = remember(model) {
        ImageRequest.Builder(context)
            .data(model)
            .size(GHOST_DECODE_PX)
            .scale(Scale.FILL)
            .crossfade(false)
            .build()
    }
    AsyncImage(
        model = request,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        alpha = if (palette.isDark) 0.30f else 0.24f,
        modifier = Modifier.fillMaxSize(),
    )
}

/**
 * 格子上的关键词。
 *
 * CJK 且不超过 4 字：按印文排布，两字一行。这是设计的正体。
 * 其余（英文这类拉丁词）：格子宽度放不下整个词，退成衬线首字母，
 * 像手抄本的起首花字；完整的词在卡片里。
 */
@Composable
private fun CellKeyword(
    keyword: String,
    palette: SplashPalette,
    modifier: Modifier = Modifier,
) {
    val square = remember(keyword) {
        keyword.length <= 4 && keyword.all { it.code >= CJK_START }
    }
    if (square) {
        val rows = remember(keyword) {
            when (keyword.length) {
                4 -> listOf(keyword.substring(0, 2), keyword.substring(2, 4))
                3 -> listOf(keyword.substring(0, 2), keyword.substring(2, 3))
                else -> listOf(keyword)
            }
        }
        val fontSize = if (keyword.length > 2) 8.5.sp else 11.sp
        Column(
            modifier = modifier,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            rows.forEach { row ->
                Text(
                    text = row,
                    color = palette.ink,
                    fontSize = fontSize,
                    lineHeight = fontSize * 1.14f,
                    fontFamily = FontFamily.Serif,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    } else {
        Text(
            text = keyword.take(1).uppercase(),
            modifier = modifier,
            color = palette.ink,
            fontSize = 15.sp,
            fontFamily = FontFamily.Serif,
            fontWeight = FontWeight.Medium,
        )
    }
}

/** 底部一行小字：这个月一片空白时换成「明天打开就有了」，不摆空状态插画 */
@Composable
private fun FooterHint(
    palette: SplashPalette,
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

/** 格子略高于正方形，取票根的比例；再高一点会在小屏上把六行网格顶出屏幕 */
private const val CELL_ASPECT = 0.86f

/** 底纹海报的解码宽度：格子只有 40dp 上下，96px 足够 */
private const val GHOST_DECODE_PX = 96

private const val CJK_START = 0x2E80
