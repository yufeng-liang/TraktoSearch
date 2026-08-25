package com.tracktosearch.ui.screen.help

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tracktosearch.R
import com.tracktosearch.ui.util.LocalScrollToTopProvider
import kotlinx.coroutines.launch

/**
 * 帮助页各段落的可检索文案。
 *
 * 段落内容是手写的 Composable（含表格、代码块、数据卡），无法自动内省，这里把每段用到的
 * 字符串资源列一遍供段内搜索匹配。新增段落时同步补一行。
 */
private val HelpSectionSearchIndex: List<List<Int>> = listOf(
    // 0 搜索功能
    listOf(
        R.string.help_search, R.string.help_search_b1, R.string.help_search_b2,
        R.string.help_search_b3, R.string.help_search_b4, R.string.help_person_search
    ),
    // 1 想看与已看
    listOf(
        R.string.help_watchlist, R.string.help_watchlist_b1, R.string.help_watchlist_b2,
        R.string.help_watchlist_b3, R.string.help_watchlist_b4
    ),
    // 2 观看统计
    listOf(
        R.string.help_statistics, R.string.help_statistics_b1, R.string.help_statistics_b2,
        R.string.help_statistics_b3, R.string.help_statistics_b4
    ),
    // 3 标记记录
    listOf(
        R.string.mark_records_settings_entry, R.string.mark_records_help_entry_location,
        R.string.mark_records_help_data_source, R.string.mark_records_help_history_limit
    ),
    // 4 通知提醒
    listOf(
        R.string.help_notification, R.string.help_notification_b1, R.string.help_notification_b2,
        R.string.help_notification_b3, R.string.help_notification_b4, R.string.help_notification_b5
    ),
    // 5 数据管理
    listOf(
        R.string.help_data, R.string.help_data_table_title,
        R.string.help_dc_export_t, R.string.help_dc_export_entry, R.string.help_dc_export_desc,
        R.string.help_dc_imdb_t, R.string.help_dc_imdb_entry, R.string.help_dc_imdb_desc,
        R.string.help_dc_douban_t, R.string.help_dc_douban_entry, R.string.help_dc_douban_desc,
        R.string.help_dc_cooldown
    ),
    // 6 自定义搜索源
    listOf(
        R.string.help_custom_source, R.string.help_custom_source_b1, R.string.help_custom_source_b2,
        R.string.help_custom_source_b3, R.string.help_custom_source_b4, R.string.help_custom_source_b5,
        R.string.help_custom_source_params, R.string.help_custom_source_parse_title,
        R.string.help_custom_source_parse_b1, R.string.help_custom_source_parse_b2,
        R.string.help_custom_source_parse_b3, R.string.help_custom_source_example_title
    ),
    // 7 详情页
    listOf(R.string.help_detail, R.string.help_detail_custom, R.string.help_videos_images),
    // 8 影视筛选
    listOf(
        R.string.help_discover_filter, R.string.help_discover_filter_b1,
        R.string.help_discover_filter_b2, R.string.help_discover_filter_b3,
        R.string.help_discover_filter_b4, R.string.help_discover_filter_b6
    ),
    // 9 使用技巧
    listOf(
        R.string.help_tips, R.string.help_tips_b1, R.string.help_tips_b2, R.string.help_tips_b3,
        R.string.help_tips_b4, R.string.help_tips_b5, R.string.help_tips_b7, R.string.help_tips_b8,
        R.string.help_tips_b9, R.string.help_tips_b11, R.string.help_tips_b12
    ),
    // 10 网络环境
    listOf(R.string.help_vpn, R.string.help_vpn_b1, R.string.help_vpn_b2),
    // 11 豆瓣回写
    listOf(
        R.string.help_douban_writeback, R.string.help_douban_writeback_b1,
        R.string.help_douban_writeback_b3
    ),
    // 12 一致性检查
    listOf(R.string.help_consistency_check, R.string.help_consistency_intro),
    // 13 AI 精灵
    listOf(
        R.string.help_ai_sprite, R.string.help_ai_sprite_b1, R.string.help_ai_sprite_b2,
        R.string.help_ai_sprite_b3, R.string.help_ai_sprite_b4, R.string.help_ai_sprite_b5,
        R.string.help_ai_sprite_b6, R.string.help_ai_sprite_b7, R.string.ai_sprite_long_press_hint
    )
)

/** 功能页跳帮助用的段落 key → 段落序号。key 走导航参数，改动时注意调用方。 */
private val HelpSectionKeys: Map<String, Int> = mapOf(
    HelpSections.SEARCH to 0,
    HelpSections.WATCHLIST to 1,
    HelpSections.STATISTICS to 2,
    HelpSections.MARK_RECORDS to 3,
    HelpSections.NOTIFICATION to 4,
    HelpSections.DATA to 5,
    HelpSections.CUSTOM_SOURCE to 6,
    HelpSections.DETAIL to 7,
    HelpSections.DISCOVER_FILTER to 8,
    HelpSections.TIPS to 9,
    HelpSections.VPN to 10,
    HelpSections.DOUBAN_WRITEBACK to 11,
    HelpSections.CONSISTENCY_CHECK to 12,
    HelpSections.AI_SPRITE to 13
)

/** 帮助段落 key 常量，供功能页带参数跳转。 */
object HelpSections {
    const val SEARCH = "search"
    const val WATCHLIST = "watchlist"
    const val STATISTICS = "statistics"
    const val MARK_RECORDS = "markRecords"
    const val NOTIFICATION = "notification"
    const val DATA = "data"
    const val CUSTOM_SOURCE = "customSource"
    const val DETAIL = "detail"
    const val DISCOVER_FILTER = "discoverFilter"
    const val TIPS = "tips"
    const val VPN = "vpn"
    const val DOUBAN_WRITEBACK = "doubanWriteback"
    const val CONSISTENCY_CHECK = "consistencyCheck"
    const val AI_SPRITE = "aiSprite"
}

/** 段内搜索：命中段落标题或段落内任一条目文案即视为匹配。 */
@Composable
private fun helpSectionMatches(index: Int, query: String): Boolean {
    if (query.isBlank()) return true
    val context = LocalContext.current
    val ids = HelpSectionSearchIndex.getOrNull(index) ?: return true
    val keyword = query.trim()
    return ids.any { context.getString(it).contains(keyword, ignoreCase = true) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HelpScreen(
    onBack: () -> Unit,
    /** 功能页带过来的段落 key：进入后自动展开并滚到该段 */
    initialSection: String? = null
) {
    // rememberSaveable：旋屏/进程重建后保留展开位置，之前用 remember 一转屏就回到第一段
    var expandedIndex by rememberSaveable { mutableIntStateOf(0) }
    var helpQuery by rememberSaveable { mutableStateOf("") }
    var searchVisible by rememberSaveable { mutableStateOf(false) }
    val lazyListState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    // 功能页带 section 参数进来时：展开对应段并滚到它，省得用户在 12 段里自己找
    LaunchedEffect(initialSection) {
        val target = initialSection?.let { HelpSectionKeys[it] } ?: return@LaunchedEffect
        expandedIndex = target
        lazyListState.animateScrollToItem(target)
    }
    // 状态栏回顶
    val scrollToTopProvider = LocalScrollToTopProvider.current
    DisposableEffect(Unit) {
        scrollToTopProvider.register {
            scope.launch { lazyListState.animateScrollToItem(0) }
        }
        onDispose { scrollToTopProvider.unregister() }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            val statusBarHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
            LazyColumn(
                state = lazyListState,
                modifier = Modifier
                    .fillMaxSize(),
                contentPadding = PaddingValues(
                    top = 64.dp + statusBarHeight,
                    bottom = 80.dp
                )
            ) {
                // 搜索功能
                item {
                    HelpSection(
                        title = stringResource(R.string.help_search),
                        // 搜索时命中的段落自动展开，免得用户搜到了还要再点一下
                        isExpanded = expandedIndex == 0 || helpQuery.isNotBlank(),
                        onToggle = { expandedIndex = if (expandedIndex == 0) -1 else 0 },
                        visible = helpSectionMatches(0, helpQuery)
                    ) {
                        HelpBullet(stringResource(R.string.help_search_b1))
                        HelpBullet(stringResource(R.string.help_search_b2))
                        HelpBullet(stringResource(R.string.help_search_b3))
                        HelpBullet(stringResource(R.string.help_search_b4))
                        HelpBullet(stringResource(R.string.help_person_search))
                    }
                }

                // 想看与已看
                item {
                    HelpSection(
                        title = stringResource(R.string.help_watchlist),
                        // 搜索时命中的段落自动展开，免得用户搜到了还要再点一下
                        isExpanded = expandedIndex == 1 || helpQuery.isNotBlank(),
                        onToggle = { expandedIndex = if (expandedIndex == 1) -1 else 1 },
                        visible = helpSectionMatches(1, helpQuery)
                    ) {
                        HelpBullet(stringResource(R.string.help_watchlist_b1))
                        HelpBullet(stringResource(R.string.help_watchlist_b2))
                        HelpBullet(stringResource(R.string.help_watchlist_b3))
                        HelpBullet(stringResource(R.string.help_watchlist_b4))
                    }
                }

                // 观看统计
                item {
                    HelpSection(
                        title = stringResource(R.string.help_statistics),
                        // 搜索时命中的段落自动展开，免得用户搜到了还要再点一下
                        isExpanded = expandedIndex == 2 || helpQuery.isNotBlank(),
                        onToggle = { expandedIndex = if (expandedIndex == 2) -1 else 2 },
                        visible = helpSectionMatches(2, helpQuery)
                    ) {
                        HelpBullet(stringResource(R.string.help_statistics_b1))
                        HelpBullet(stringResource(R.string.help_statistics_b2))
                        HelpBullet(stringResource(R.string.help_statistics_b3))
                        HelpBullet(stringResource(R.string.help_statistics_b4))
                    }
                }

                // 标记记录
                item {
                    HelpSection(
                        title = stringResource(R.string.mark_records_settings_entry),
                        // 搜索时命中的段落自动展开，免得用户搜到了还要再点一下
                        isExpanded = expandedIndex == 3 || helpQuery.isNotBlank(),
                        onToggle = { expandedIndex = if (expandedIndex == 3) -1 else 3 },
                        visible = helpSectionMatches(3, helpQuery)
                    ) {
                        HelpBullet(stringResource(R.string.mark_records_help_entry_location))
                        HelpBullet(stringResource(R.string.mark_records_help_data_source))
                        HelpBullet(stringResource(R.string.mark_records_help_history_limit))
                    }
                }

                // 通知提醒
                item {
                    HelpSection(
                        title = stringResource(R.string.help_notification),
                        // 搜索时命中的段落自动展开，免得用户搜到了还要再点一下
                        isExpanded = expandedIndex == 4 || helpQuery.isNotBlank(),
                        onToggle = { expandedIndex = if (expandedIndex == 4) -1 else 4 },
                        visible = helpSectionMatches(4, helpQuery)
                    ) {
                        HelpBullet(stringResource(R.string.help_notification_b1))
                        HelpBullet(stringResource(R.string.help_notification_b2))
                        HelpBullet(stringResource(R.string.help_notification_b3))
                        HelpBullet(stringResource(R.string.help_notification_b4))
                        HelpBullet(stringResource(R.string.help_notification_b5))
                    }
                }

                // 数据管理
                item {
                    HelpSection(
                        title = stringResource(R.string.help_data),
                        // 搜索时命中的段落自动展开，免得用户搜到了还要再点一下
                        isExpanded = expandedIndex == 5 || helpQuery.isNotBlank(),
                        onToggle = { expandedIndex = if (expandedIndex == 5) -1 else 5 },
                        visible = helpSectionMatches(5, helpQuery)
                    ) {
                        HelpSubtitle(stringResource(R.string.help_data_table_title))
                        Spacer(modifier = Modifier.height(4.dp))
                        // 3 个图标卡片,放在圆角背景容器内
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 4.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .padding(8.dp)
                        ) {
                            HelpDataCard(
                                icon = Icons.Rounded.FileDownload,
                                title = stringResource(R.string.help_dc_export_t),
                                entry = stringResource(R.string.help_dc_export_entry),
                                format = stringResource(R.string.help_dc_export_fmt),
                                description = stringResource(R.string.help_dc_export_desc)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            HelpDataCard(
                                icon = Icons.Rounded.FileUpload,
                                title = stringResource(R.string.help_dc_imdb_t),
                                entry = stringResource(R.string.help_dc_imdb_entry),
                                format = stringResource(R.string.help_dc_imdb_fmt),
                                description = stringResource(R.string.help_dc_imdb_desc)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            HelpDataCard(
                                icon = Icons.Rounded.Sync,
                                title = stringResource(R.string.help_dc_douban_t),
                                entry = stringResource(R.string.help_dc_douban_entry),
                                format = stringResource(R.string.help_dc_douban_fmt),
                                description = stringResource(R.string.help_dc_douban_desc)
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        HelpBullet(stringResource(R.string.help_dc_cooldown))
                    }
                }

                // 自定义搜索源
                item {
                    HelpSection(
                        title = stringResource(R.string.help_custom_source),
                        // 搜索时命中的段落自动展开，免得用户搜到了还要再点一下
                        isExpanded = expandedIndex == 6 || helpQuery.isNotBlank(),
                        onToggle = { expandedIndex = if (expandedIndex == 6) -1 else 6 },
                        visible = helpSectionMatches(6, helpQuery)
                    ) {
                        HelpBullet(stringResource(R.string.help_custom_source_b1))
                        HelpBullet(stringResource(R.string.help_custom_source_b2))
                        HelpBullet(stringResource(R.string.help_custom_source_b3))
                        HelpBullet(stringResource(R.string.help_custom_source_b4))
                        HelpBullet(stringResource(R.string.help_custom_source_b5))

                        Spacer(modifier = Modifier.height(8.dp))
                        HelpSubtitle(stringResource(R.string.help_custom_source_params))
                        Spacer(modifier = Modifier.height(4.dp))
                        CustomSourceParamsTable()

                        Spacer(modifier = Modifier.height(8.dp))
                        HelpSubtitle(stringResource(R.string.help_custom_source_parse_title))
                        HelpBullet(stringResource(R.string.help_custom_source_parse_b1))
                        HelpBullet(stringResource(R.string.help_custom_source_parse_b2))
                        HelpBullet(stringResource(R.string.help_custom_source_parse_b3))

                        Spacer(modifier = Modifier.height(8.dp))
                        HelpCodeBlock(stringResource(R.string.help_custom_source_parse_pansou_example))
                        Spacer(modifier = Modifier.height(4.dp))
                        HelpCodeBlock(stringResource(R.string.help_custom_source_parse_zreso_example))

                        Spacer(modifier = Modifier.height(8.dp))
                        HelpSubtitle(stringResource(R.string.help_custom_source_example_title))
                        HelpCodeBlock(stringResource(R.string.help_custom_source_example))
                    }
                }

                // 详情页
                item {
                    HelpSection(
                        title = stringResource(R.string.help_detail),
                        // 搜索时命中的段落自动展开，免得用户搜到了还要再点一下
                        isExpanded = expandedIndex == 7 || helpQuery.isNotBlank(),
                        onToggle = { expandedIndex = if (expandedIndex == 7) -1 else 7 },
                        visible = helpSectionMatches(7, helpQuery)
                    ) {
                        HelpBullet(stringResource(R.string.help_detail_custom))
                        HelpBullet(stringResource(R.string.help_videos_images))
                    }
                }

                // 影视筛选
                item {
                    HelpSection(
                        title = stringResource(R.string.help_discover_filter),
                        // 搜索时命中的段落自动展开，免得用户搜到了还要再点一下
                        isExpanded = expandedIndex == 8 || helpQuery.isNotBlank(),
                        onToggle = { expandedIndex = if (expandedIndex == 8) -1 else 8 },
                        visible = helpSectionMatches(8, helpQuery)
                    ) {
                        HelpBullet(stringResource(R.string.help_discover_filter_b1))
                        HelpBullet(stringResource(R.string.help_discover_filter_b2))
                        HelpBullet(stringResource(R.string.help_discover_filter_b3))
                        HelpBullet(stringResource(R.string.help_discover_filter_b4))
                        HelpBullet(stringResource(R.string.help_discover_filter_b6))
                    }
                }

                // 更多技巧
                item {
                    HelpSection(
                        title = stringResource(R.string.help_tips),
                        // 搜索时命中的段落自动展开，免得用户搜到了还要再点一下
                        isExpanded = expandedIndex == 9 || helpQuery.isNotBlank(),
                        onToggle = { expandedIndex = if (expandedIndex == 9) -1 else 9 },
                        visible = helpSectionMatches(9, helpQuery)
                    ) {
                        HelpBullet(stringResource(R.string.help_tips_b1))
                        HelpBullet(stringResource(R.string.help_tips_b2))
                        HelpBullet(stringResource(R.string.help_tips_b3))
                        HelpBullet(stringResource(R.string.help_tips_b4))
                        HelpBullet(stringResource(R.string.help_tips_b5))
                        HelpBullet(stringResource(R.string.help_tips_b7))
                        HelpBullet(stringResource(R.string.help_tips_b8))
                        HelpBullet(stringResource(R.string.help_tips_b9))
                        HelpBullet(stringResource(R.string.help_tips_b11))
                        HelpBullet(stringResource(R.string.help_tips_b12))
                    }
                }

                // VPN 说明
                item {
                    HelpSection(
                        title = stringResource(R.string.help_vpn),
                        // 搜索时命中的段落自动展开，免得用户搜到了还要再点一下
                        isExpanded = expandedIndex == 10 || helpQuery.isNotBlank(),
                        onToggle = { expandedIndex = if (expandedIndex == 10) -1 else 10 },
                        visible = helpSectionMatches(10, helpQuery)
                    ) {
                        HelpBullet(stringResource(R.string.help_vpn_b1))
                        HelpBullet(stringResource(R.string.help_vpn_b2))
                    }
                }

                // 豆瓣标记双向写回
                item {
                    HelpSection(
                        title = stringResource(R.string.help_douban_writeback),
                        // 搜索时命中的段落自动展开，免得用户搜到了还要再点一下
                        isExpanded = expandedIndex == 11 || helpQuery.isNotBlank(),
                        onToggle = { expandedIndex = if (expandedIndex == 11) -1 else 11 },
                        visible = helpSectionMatches(11, helpQuery)
                    ) {
                        HelpBullet(stringResource(R.string.help_douban_writeback_b1))
                        HelpBullet(stringResource(R.string.help_douban_writeback_b3))
                    }
                }

                // 状态一致性检查
                item {
                    HelpSection(
                        title = stringResource(R.string.help_consistency_check),
                        // 搜索时命中的段落自动展开，免得用户搜到了还要再点一下
                        isExpanded = expandedIndex == 12 || helpQuery.isNotBlank(),
                        onToggle = { expandedIndex = if (expandedIndex == 12) -1 else 12 },
                        visible = helpSectionMatches(12, helpQuery)
                    ) {
                        HelpBullet(stringResource(R.string.help_consistency_intro))
                        Spacer(modifier = Modifier.height(8.dp))
                        ConsistencyCheckTable()
                    }
                }

                // AI 精灵
                item {
                    HelpSection(
                        title = stringResource(R.string.help_ai_sprite),
                        // 搜索时命中的段落自动展开，免得用户搜到了还要再点一下
                        isExpanded = expandedIndex == 13 || helpQuery.isNotBlank(),
                        onToggle = { expandedIndex = if (expandedIndex == 13) -1 else 13 },
                        visible = helpSectionMatches(13, helpQuery)
                    ) {
                        HelpBullet(stringResource(R.string.help_ai_sprite_b1))
                        HelpBullet(stringResource(R.string.help_ai_sprite_b2))
                        HelpBullet(stringResource(R.string.help_ai_sprite_b3))
                        HelpBullet(stringResource(R.string.help_ai_sprite_b4))
                        HelpBullet(stringResource(R.string.help_ai_sprite_b5))
                        HelpBullet(stringResource(R.string.help_ai_sprite_b6))
                        HelpBullet(stringResource(R.string.help_ai_sprite_b7))
                        HelpBullet(stringResource(R.string.ai_sprite_long_press_hint))
                    }
                }
            }

            // TopAppBar（纯色背景，无共享元素转场）
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .clickable(enabled = false, onClick = {})
            ) {
                Spacer(modifier = Modifier.statusBarsPadding())
                TopAppBar(
                    title = {
                        Text(
                            text = stringResource(R.string.help_title),
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = "Back",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    },
                    actions = {
                        // 段内搜索：12 段手风琴靠翻找效率太低
                        IconButton(onClick = {
                            searchVisible = !searchVisible
                            if (!searchVisible) helpQuery = ""
                        }) {
                            Icon(
                                imageVector = if (searchVisible) Icons.Rounded.Close else Icons.Rounded.Search,
                                contentDescription = stringResource(
                                    if (searchVisible) R.string.common_cancel else R.string.help_search_hint
                                ),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    },
                    windowInsets = WindowInsets(0, 0, 0, 0)
                )
                if (searchVisible) {
                    OutlinedTextField(
                        value = helpQuery,
                        onValueChange = { helpQuery = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        singleLine = true,
                        placeholder = { Text(stringResource(R.string.help_search_hint)) },
                        trailingIcon = {
                            if (helpQuery.isNotEmpty()) {
                                IconButton(onClick = { helpQuery = "" }) {
                                    Icon(
                                        imageVector = Icons.Rounded.Close,
                                        contentDescription = stringResource(R.string.common_cancel)
                                    )
                                }
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun HelpSection(
    title: String,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    /** 段内搜索未命中时整段不渲染 */
    visible: Boolean = true,
    content: @Composable () -> Unit
) {
    if (!visible) return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onToggle() }
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f)
            )
            Icon(
                imageVector = if (isExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        AnimatedVisibility(
            visible = isExpanded,
            enter = expandVertically(),
            exit = shrinkVertically()
        ) {
            Column(modifier = Modifier.padding(start = 4.dp, bottom = 12.dp)) {
                content()
            }
        }
    }
}

@Composable
private fun HelpBullet(text: String, icon: ImageVector? = null) {
    Row(
        modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.Top
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .size(16.dp)
                    .padding(top = 3.dp, end = 6.dp)
            )
        } else {
            Text(
                text = "•",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(end = 6.dp)
            )
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            lineHeight = MaterialTheme.typography.bodyMedium.lineHeight * 1.4f
        )
    }
}

@Composable
private fun HelpSubtitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
    )
}

@Composable
private fun HelpCodeBlock(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(12.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            lineHeight = 18.sp
        )
    }
}

@Composable
private fun HelpDataCard(
    icon: ImageVector,
    title: String,
    entry: String,
    format: String,
    description: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.Top
    ) {
        // 左侧图标
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .size(20.dp)
                .padding(end = 8.dp, top = 2.dp)
        )
        // 右侧内容
        Column(modifier = Modifier.weight(1f)) {
            // 第一行: 功能名 + 格式标签
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(end = 6.dp)
                )
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        text = format,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                    )
                }
            }
            // 第二行: 入口
            Text(
                text = "📍 $entry",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp)
            )
            // 第三行: 说明
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 1.dp)
            )
        }
    }
}

@Composable
private fun CustomSourceParamsTable() {
    val params = listOf(
        Triple("name", stringResource(R.string.help_cs_param_name), "我的搜索源"),
        Triple("baseUrl", stringResource(R.string.help_cs_param_baseUrl), "https://example.com/"),
        Triple("apiPath", stringResource(R.string.help_cs_param_apiPath), "api/search"),
        Triple("keywordParam", stringResource(R.string.help_cs_param_keyword), "kw"),
        Triple("cloudTypesParam", stringResource(R.string.help_cs_param_cloudTypes), "cloud_types"),
        Triple("cloudTypesValue", stringResource(R.string.help_cs_param_cloudTypesVal), "quark,baidu,aliyun"),
        Triple("srcParam", stringResource(R.string.help_cs_param_src), "src"),
        Triple("srcValue", stringResource(R.string.help_cs_param_srcVal), "all"),
        Triple("parseMode", stringResource(R.string.help_cs_param_parseMode), "pansou_template"),
        Triple("listPath", stringResource(R.string.help_cs_param_listPath), "$.data.results"),
        Triple("namePath", stringResource(R.string.help_cs_param_namePath), "$.title"),
        Triple("urlPath", stringResource(R.string.help_cs_param_urlPath), "$.url"),
        Triple("diskTypePath", stringResource(R.string.help_cs_param_diskTypePath), "$.type"),
        Triple("datePath", stringResource(R.string.help_cs_param_datePath), "$.datetime"),
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(8.dp)
    ) {
        // Header
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                stringResource(R.string.help_cs_table_param),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(0.28f)
            )
            Text(
                stringResource(R.string.help_cs_table_desc),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(0.44f)
            )
            Text(
                stringResource(R.string.help_cs_table_example),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(0.28f)
            )
        }
        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
        params.forEach {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp)
            ) {
                Text(
                    it.first,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(0.28f)
                )
                Text(
                    it.second,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(0.44f)
                )
                Text(
                    it.third,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(0.28f)
                )
            }
        }
    }
}

@Composable
private fun ConsistencyCheckTable() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(8.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(R.string.help_consistency_col_douban),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(0.22f)
            )
            Text(
                text = stringResource(R.string.help_consistency_col_trakt),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(0.22f)
            )
            Text(
                text = stringResource(R.string.help_consistency_col_result),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(0.16f)
            )
            Text(
                text = stringResource(R.string.help_consistency_col_action),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(0.40f)
            )
        }
        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
        val rows = listOf(
            ConsistencyRow(
                R.string.watchlist_mode_watched, R.string.watchlist_mode_watchlist,
                R.string.watchlist_mode_watched, R.string.help_consistency_act_trakt_watched_remove_wish
            ),
            ConsistencyRow(
                R.string.watchlist_mode_watchlist, R.string.watchlist_mode_watched,
                R.string.watchlist_mode_watched, R.string.help_consistency_act_douban_watched_upgrade
            ),
            ConsistencyRow(
                R.string.watchlist_mode_watched, R.string.help_status_unmarked,
                R.string.watchlist_mode_watched, R.string.help_consistency_act_trakt_watched
            ),
            ConsistencyRow(
                R.string.watchlist_mode_watchlist, R.string.help_status_unmarked,
                R.string.watchlist_mode_watchlist, R.string.help_consistency_act_trakt_wish
            ),
            ConsistencyRow(
                R.string.help_status_unmarked, R.string.watchlist_mode_watched,
                R.string.watchlist_mode_watched, R.string.help_consistency_act_douban_watched
            ),
            ConsistencyRow(
                R.string.help_status_unmarked, R.string.watchlist_mode_watchlist,
                R.string.watchlist_mode_watchlist, R.string.help_consistency_act_douban_wish
            ),
            ConsistencyRow(
                R.string.watchlist_mode_watched, R.string.watchlist_mode_watched,
                R.string.watchlist_mode_watched, R.string.help_no_action
            ),
            ConsistencyRow(
                R.string.watchlist_mode_watchlist, R.string.watchlist_mode_watchlist,
                R.string.watchlist_mode_watchlist, R.string.help_no_action
            )
        )
        rows.forEach { (douban, trakt, result, action) ->
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                Text(stringResource(douban), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 14.sp, modifier = Modifier.weight(0.22f))
                Text(stringResource(trakt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 14.sp, modifier = Modifier.weight(0.22f))
                Text(stringResource(result), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 14.sp, modifier = Modifier.weight(0.16f))
                Text(stringResource(action), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 14.sp, modifier = Modifier.weight(0.40f))
            }
        }
    }
}

private data class ConsistencyRow(
    val douban: Int,
    val trakt: Int,
    val result: Int,
    val action: Int
)
