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
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tracktosearch.R
import com.tracktosearch.ui.util.LocalScrollToTopProvider
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials
import kotlinx.coroutines.launch

@OptIn(ExperimentalHazeMaterialsApi::class, ExperimentalMaterial3Api::class)
@Composable
fun HelpScreen(
    onBack: () -> Unit
) {
    var expandedIndex by remember { mutableIntStateOf(0) }
    val hazeState = remember { HazeState() }
    val lazyListState = rememberLazyListState()
    val scope = rememberCoroutineScope()

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
                    .fillMaxSize()
                    .hazeSource(state = hazeState),
                contentPadding = PaddingValues(
                    top = 64.dp + statusBarHeight,
                    bottom = 80.dp
                )
            ) {
                // 搜索功能
                item {
                    HelpSection(
                        title = stringResource(R.string.help_search),
                        isExpanded = expandedIndex == 0,
                        onToggle = { expandedIndex = if (expandedIndex == 0) -1 else 0 }
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
                        isExpanded = expandedIndex == 1,
                        onToggle = { expandedIndex = if (expandedIndex == 1) -1 else 1 }
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
                        isExpanded = expandedIndex == 2,
                        onToggle = { expandedIndex = if (expandedIndex == 2) -1 else 2 }
                    ) {
                        HelpBullet(stringResource(R.string.help_statistics_b1))
                        HelpBullet(stringResource(R.string.help_statistics_b2))
                        HelpBullet(stringResource(R.string.help_statistics_b3))
                        HelpBullet(stringResource(R.string.help_statistics_b4))
                    }
                }

                // 通知提醒
                item {
                    HelpSection(
                        title = stringResource(R.string.help_notification),
                        isExpanded = expandedIndex == 3,
                        onToggle = { expandedIndex = if (expandedIndex == 3) -1 else 3 }
                    ) {
                        HelpBullet(stringResource(R.string.help_notification_b1))
                        HelpBullet(stringResource(R.string.help_notification_b2))
                        HelpBullet(stringResource(R.string.help_notification_b3))
                        HelpBullet(stringResource(R.string.help_notification_b4))
                    }
                }

                // 数据管理
                item {
                    HelpSection(
                        title = stringResource(R.string.help_data),
                        isExpanded = expandedIndex == 4,
                        onToggle = { expandedIndex = if (expandedIndex == 4) -1 else 4 }
                    ) {
                        HelpSubtitle(stringResource(R.string.help_data_export_title))
                        HelpBullet(stringResource(R.string.help_data_export_b1))
                        HelpBullet(stringResource(R.string.help_data_export_b2))
                        HelpBullet(stringResource(R.string.help_data_export_b3))

                        Spacer(modifier = Modifier.height(8.dp))
                        HelpSubtitle(stringResource(R.string.help_data_import_title))
                        HelpBullet(stringResource(R.string.help_data_import_b1))
                        HelpBullet(stringResource(R.string.help_data_import_b2))
                        HelpBullet(stringResource(R.string.help_data_import_b3))

                        Spacer(modifier = Modifier.height(8.dp))
                        // 导入来源表格
                        HelpSubtitle(stringResource(R.string.help_data_import_sources))
                        Spacer(modifier = Modifier.height(4.dp))
                        ImportSourceTable()
                    }
                }

                // 自定义搜索源
                item {
                    HelpSection(
                        title = stringResource(R.string.help_custom_source),
                        isExpanded = expandedIndex == 5,
                        onToggle = { expandedIndex = if (expandedIndex == 5) -1 else 5 }
                    ) {
                        HelpBullet(stringResource(R.string.help_custom_source_b1))
                        HelpBullet(stringResource(R.string.help_custom_source_b2))
                        HelpBullet(stringResource(R.string.help_custom_source_b3))

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
                        isExpanded = expandedIndex == 6,
                        onToggle = { expandedIndex = if (expandedIndex == 6) -1 else 6 }
                    ) {
                        HelpBullet(stringResource(R.string.help_detail_custom))
                        HelpBullet(stringResource(R.string.help_videos_images))
                    }
                }

                // 更多技巧
                item {
                    HelpSection(
                        title = stringResource(R.string.help_tips),
                        isExpanded = expandedIndex == 7,
                        onToggle = { expandedIndex = if (expandedIndex == 7) -1 else 7 }
                    ) {
                        HelpBullet(stringResource(R.string.help_tips_b1))
                        HelpBullet(stringResource(R.string.help_tips_b2))
                        HelpBullet(stringResource(R.string.help_tips_b3))
                        HelpBullet(stringResource(R.string.help_tips_b4))
                        HelpBullet(stringResource(R.string.help_tips_b5))
                        HelpBullet(stringResource(R.string.help_tips_b6))
                        HelpBullet(stringResource(R.string.help_tips_b7))
                        HelpBullet(stringResource(R.string.help_tips_b8))
                        HelpBullet(stringResource(R.string.help_tips_b9))
                        HelpBullet(stringResource(R.string.help_tips_b10))
                    }
                }

                // VPN 说明
                item {
                    HelpSection(
                        title = stringResource(R.string.help_vpn),
                        isExpanded = expandedIndex == 8,
                        onToggle = { expandedIndex = if (expandedIndex == 8) -1 else 8 }
                    ) {
                        HelpBullet(stringResource(R.string.help_vpn_b1))
                        HelpBullet(stringResource(R.string.help_vpn_b2))
                    }
                }
            }

            // Haze模糊TopAppBar
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .hazeEffect(state = hazeState, style = HazeMaterials.thin())
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.50f))
            ) {
                Spacer(
                    modifier = Modifier
                        .statusBarsPadding()
                        .fillMaxWidth()
                        .clickable {
                            scope.launch { lazyListState.animateScrollToItem(0) }
                        }
                )
                TopAppBar(
                    title = {
                        Text(
                            text = stringResource(R.string.help_title),
                            fontWeight = FontWeight.Bold
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    },
                    windowInsets = WindowInsets(0, 0, 0, 0)
                )
            }
        }
    }
}

@Composable
private fun HelpSection(
    title: String,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit
) {
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
                imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
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
private fun HelpBullet(text: String) {
    Text(
        text = "• $text",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
        lineHeight = MaterialTheme.typography.bodyMedium.lineHeight * 1.4f
    )
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
private fun ImportSourceTable() {
    data class ImportSource(
        val source: String,
        val format: String,
        val howTo: String,
        val effect: String
    )

    val sources = listOf(
        ImportSource(
            stringResource(R.string.help_import_src_letterboxd),
            "CSV",
            stringResource(R.string.help_import_src_letterboxd_how),
            stringResource(R.string.help_import_src_letterboxd_effect)
        ),
        ImportSource(
            stringResource(R.string.help_import_src_imdb),
            "CSV",
            stringResource(R.string.help_import_src_imdb_how),
            stringResource(R.string.help_import_src_imdb_effect)
        ),
        ImportSource(
            stringResource(R.string.help_import_src_appjson),
            "JSON",
            stringResource(R.string.help_import_src_appjson_how),
            stringResource(R.string.help_import_src_appjson_effect)
        )
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
            Text(stringResource(R.string.help_col_source), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(0.18f))
            Text(stringResource(R.string.help_col_format), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(0.12f))
            Text(stringResource(R.string.help_col_how_to_get), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(0.35f))
            Text(stringResource(R.string.help_col_import_effect), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(0.35f))
        }
        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
        sources.forEach { src ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 3.dp)
            ) {
                Text(src.source, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(0.18f))
                Text(src.format, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(0.12f))
                Text(src.howTo, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(0.35f))
                Text(src.effect, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(0.35f))
            }
        }
    }
}