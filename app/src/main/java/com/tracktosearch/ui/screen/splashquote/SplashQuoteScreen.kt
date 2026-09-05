package com.tracktosearch.ui.screen.splashquote

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.FormatQuote
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tracktosearch.R
import com.tracktosearch.ui.component.hasListScrolled
import com.tracktosearch.ui.component.hazeTopBar
import com.tracktosearch.ui.haptic.rememberAppHaptics
import com.tracktosearch.ui.screen.dailystamp.DAILY_STAMP_CALENDAR_CHROME
import com.tracktosearch.ui.screen.dailystamp.DailyStampCalendar
import com.tracktosearch.ui.screen.dailystamp.DailyStampCardOverlay
import com.tracktosearch.ui.screen.dailystamp.DailyStampViewModel
import com.tracktosearch.ui.screen.dailystamp.dailyStampCardBlur
import com.tracktosearch.ui.screen.dailystamp.rememberDailyStampCardPalette
import com.tracktosearch.ui.screen.dailystamp.rememberDailyStampContent
import com.tracktosearch.ui.screen.dailystamp.rememberDailyStampPalette
import com.tracktosearch.ui.screen.settings.SettingsGroupCard
import com.tracktosearch.ui.screen.settings.SettingsItemCard
import com.tracktosearch.ui.theme.appSwitchColors
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.materials.HazeMaterials
import dev.chrisbanes.haze.hazeSource

/**
 * 每日台词页：设置里「开屏每日台词」的二级页。
 *
 * 一页两件事——开屏那一句要不要显示，和这些日子攒下的日签。它们是同一件事的两面：
 * 日签记的就是每天开屏那一句，分在两处反而要用户自己去把它们联系起来。
 *
 * 骨架与隐私页一致（Scaffold + LazyColumn + 滚动后才起玻璃的顶栏），日历直接嵌在分组
 * 卡片里。台词卡片浮层挂在最外层 Box：它要盖满整屏，放进 LazyColumn 的 item 会被裁在
 * 日历那一块里。
 *
 * 这一页不做卡片升起时的背景模糊——独立日签页有，那一屏没有可滚动的列表和玻璃顶栏，
 * 加模糊不会和它们打架。这里靠浮层自己那层压暗做前后分离。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SplashQuoteScreen(
    onBack: () -> Unit,
    onQuoteClick: (tmdbId: Int, mediaType: String, title: String, year: Int, posterUrl: String) -> Unit,
    viewModel: DailyStampViewModel = hiltViewModel(),
) {
    val hazeState = remember { HazeState() }
    // HazeMaterials.thin() 读 colorScheme，是 @Composable 函数，不能 remember 缓存
    val hazeStyle = HazeMaterials.thin()
    val lazyListState = rememberLazyListState()
    // 静止时列表未位移、栏下无内容，顶栏保持全透明；滚动后再启用模糊/玻璃
    val hasContentUnderTopBar by remember {
        derivedStateOf {
            hasListScrolled(
                firstVisibleItemIndex = lazyListState.firstVisibleItemIndex,
                firstVisibleItemScrollOffsetPx = lazyListState.firstVisibleItemScrollOffset
            )
        }
    }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val enabled by viewModel.splashQuoteEnabled.collectAsStateWithLifecycle()
    val palette = rememberDailyStampPalette()
    val cardPalette = rememberDailyStampCardPalette()
    val content = rememberDailyStampContent(state)
    val haptics = rememberAppHaptics()

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { padding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            val statusBarHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
            // contentWindowInsets 关掉之后列表铺到导航栏底下，底部内边距得自己把它让出来
            val navBarHeight = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            val topPadding = 64.dp + statusBarHeight
            val bottomPadding = navBarHeight + 12.dp
            // 日历那一段的高度预算：整屏减去上下内边距、上面那张开关卡片、分组卡片自己的
            // 壳，以及日历除网格以外的报头 / 表头 / 底部小字。算出来交给日历，格子按它收窄，
            // 这一页于是和独立日签页一样一屏就能看完（那一屏的预算在 DailyStampScreen 里量）
            val gridBudget = maxHeight - topPadding - bottomPadding -
                SWITCH_GROUP_HEIGHT - SETTINGS_GROUP_CHROME -
                DAILY_STAMP_CALENDAR_CHROME - CALENDAR_TAIL_SPACER
            // 卡片升起时把这一屏推到景深之外，和独立日签页同一套（见 dailyStampCardBlur）：
            // 两屏点开的是同一张卡，一边模糊一边不模糊会显得是两个功能。列表和吸顶栏是
            // 兄弟节点，各挂一份；浮层不挂，它是浮在景深之上的那一层。
            val cardOpen = content.sheet != null
            LazyColumn(
                state = lazyListState,
                modifier = Modifier
                    .fillMaxSize()
                    .dailyStampCardBlur(active = cardOpen)
                    .hazeSource(state = hazeState),
                contentPadding = PaddingValues(
                    top = topPadding,
                    bottom = bottomPadding
                )
            ) {
                item(key = "splash_switch") {
                    SettingsGroupCard(
                        title = stringResource(R.string.splash_quote_section_splash),
                        hazeState = hazeState
                    ) {
                        SettingsItemCard(
                            icon = Icons.Rounded.FormatQuote,
                            title = stringResource(R.string.settings_splash_quote),
                            subtitle = stringResource(R.string.settings_splash_quote_subtitle),
                            // 行区域那一记归 SettingsItemCard 自己的 hapticClickable，
                            // 这里不能再发 —— 否则点行就是两下
                            onClick = { viewModel.setSplashQuoteEnabled(!enabled) },
                            trailing = {
                                Switch(
                                    checked = enabled,
                                    // 直接拨开关是另一个手势面（与行点一次只命中一个），按新状态发 toggle
                                    onCheckedChange = { value ->
                                        haptics.toggle(value)
                                        viewModel.setSplashQuoteEnabled(value)
                                    },
                                    colors = appSwitchColors()
                                )
                            }
                        )
                    }
                }

                item(key = "daily_stamp") {
                    SettingsGroupCard(
                        title = stringResource(R.string.daily_stamp_title),
                        hazeState = hazeState
                    ) {
                        DailyStampCalendar(
                            state = state,
                            palette = palette,
                            locale = content.locale,
                            today = content.today,
                            cells = content.cells,
                            openable = content.sheets.keys,
                            gridHeightBudget = gridBudget,
                            onPreviousMonth = viewModel::previousMonth,
                            onNextMonth = viewModel::nextMonth,
                            onDayClick = { date -> viewModel.select(date) },
                        )
                        Spacer(Modifier.height(CALENDAR_TAIL_SPACER))
                    }
                }
            }

            // 毛玻璃吸顶标题栏（与隐私页、帮助页一致）
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .dailyStampCardBlur(active = cardOpen)
                    .hazeTopBar(
                        state = hazeState,
                        style = hazeStyle,
                        blurRadius = 24.dp,
                        isContentUnderTopBar = hasContentUnderTopBar
                    )
                    // 拦截点击：顶栏覆盖可滚动列表，不消费会让点击穿透到下方列表项
                    .clickable(enabled = false, onClick = {})
            ) {
                Spacer(modifier = Modifier.statusBarsPadding())
                TopAppBar(
                    title = {
                        Text(
                            text = stringResource(R.string.splash_quote_title),
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = stringResource(R.string.content_desc_back),
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        scrolledContainerColor = Color.Transparent
                    ),
                    windowInsets = WindowInsets(0, 0, 0, 0)
                )
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
}

/**
 * 「开屏每日台词」那张分组卡片占的高度：分组壳 52 + 一条设置项 60。
 *
 * 用来反算日历的高度预算（见上面的 `gridBudget`）。真去测量它要 `SubcomposeLayout`，
 * 而这一页的第一段永远只有一个开关项，估一个常量够用 —— 估偏了只是格子大一点或
 * 小一点，不会错版。
 */
private val SWITCH_GROUP_HEIGHT = 112.dp

/** `SettingsGroupCard` 自己的壳：上下 8 + 标题 27 + 内层上下 4 */
private val SETTINGS_GROUP_CHROME = 52.dp

/** 日历末尾那一小段留白，也算进预算里 */
private val CALENDAR_TAIL_SPACER = 6.dp
