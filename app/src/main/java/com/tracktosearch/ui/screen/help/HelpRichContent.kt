package com.tracktosearch.ui.screen.help

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tracktosearch.R

/**
 * 段落里那些没法用一串 StringRes 表达的手写内容：三张数据卡、参数表、对照表、代码块。
 *
 * 表格和等宽字天生属于技术文档，做法是把它们收进 [HelpPanel]（比段落卡差一档的底色），
 * 当成手册末尾的技术附录页。
 */
@Composable
internal fun HelpExtraContent(
    extra: HelpExtra,
    query: String,
) {
    when (extra) {
        HelpExtra.DATA_SOURCES -> HelpDataSources(query = query)
        HelpExtra.CUSTOM_SOURCE_SPEC -> HelpCustomSourceSpec(query)
        HelpExtra.CONSISTENCY_TABLE -> HelpConsistencyTable()
    }
}

/** 导出 / IMDb 导入 / 豆瓣同步三件事，每件一行：图标、名字、格式、入口、说明。 */
@Composable
private fun HelpDataSources(query: String) {
    HelpSubtitle(stringResource(R.string.help_data_table_title))
    HelpPanel {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            HelpDataRow(
                icon = Icons.Rounded.FileDownload,
                title = stringResource(R.string.help_dc_export_t),
                format = stringResource(R.string.help_dc_export_fmt),
                entry = stringResource(R.string.help_dc_export_entry),
                description = stringResource(R.string.help_dc_export_desc),
                query = query,
            )
            HelpDivider()
            HelpDataRow(
                icon = Icons.Rounded.FileUpload,
                title = stringResource(R.string.help_dc_imdb_t),
                format = stringResource(R.string.help_dc_imdb_fmt),
                entry = stringResource(R.string.help_dc_imdb_entry),
                description = stringResource(R.string.help_dc_imdb_desc),
                query = query,
            )
            HelpDivider()
            HelpDataRow(
                icon = Icons.Rounded.Sync,
                title = stringResource(R.string.help_dc_douban_t),
                format = stringResource(R.string.help_dc_douban_fmt),
                entry = stringResource(R.string.help_dc_douban_entry),
                description = stringResource(R.string.help_dc_douban_desc),
                query = query,
            )
        }
    }
}

/** 附录面板内部的分隔线。原先是自制的极淡墨线，改走 M3 的 [HorizontalDivider]。 */
@Composable
private fun HelpDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(
        modifier = modifier,
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

/**
 * 一行数据操作。
 *
 * 格式标签做成描边小签而不是填充色块：这一格是「CSV」「JSON」这类字面值，
 * 描边把它标成技术标注，填充块看起来像可点的按钮。
 * 入口那一行原本前面挂了个 📍 emoji，一个彩色 emoji 比整段文字都抢眼，去掉，
 * 改用更弱一档的颜色把它压成注解。
 */
@Composable
private fun HelpDataRow(
    icon: ImageVector,
    title: String,
    format: String,
    entry: String,
    description: String,
    query: String,
) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.secondary,
            modifier = Modifier
                .padding(top = 2.dp, end = 10.dp)
                .size(18.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = helpHighlight(title, query, MaterialTheme.colorScheme.primary),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(end = 8.dp),
                )
                HelpFormatTag(format)
            }
            Spacer(Modifier.height(3.dp))
            HelpFineLine(entry, query, muted = true)
            HelpFineLine(description, query, muted = false)
        }
    }
}

/** 格式标签：主题强调色描边小签，等宽字保留技术文档层级。 */
@Composable
private fun HelpFormatTag(format: String) {
    val primary = MaterialTheme.colorScheme.primary
    Text(
        text = format,
        style = MaterialTheme.typography.labelSmall,
        fontFamily = FontFamily.Monospace,
        color = primary,
        modifier = Modifier
            .border(
                width = 1.dp,
                color = primary.copy(alpha = 0.42f),
                shape = RoundedCornerShape(4.dp),
            )
            .padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

/** 卡片里的细行。[muted] 的那行是「在哪找到它」，比说明更弱一档。 */
@Composable
private fun HelpFineLine(
    text: String,
    query: String,
    muted: Boolean,
) {
    Text(
        text = helpHighlight(text, query, MaterialTheme.colorScheme.primary),
        style = MaterialTheme.typography.bodySmall,
        color = if (muted) {
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        lineHeight = 18.sp,
        modifier = Modifier.padding(top = 1.dp),
    )
}

/**
 * 自定义搜索源的技术规格：参数表 → 解析说明 → 三段示例 JSON。
 *
 * 解析说明那三条重新从「1」编号：它们是这一小节的第一二三条，不是整段的第九十十一条。
 */
@Composable
private fun HelpCustomSourceSpec(query: String) {
    val primary = MaterialTheme.colorScheme.primary
    HelpSubtitle(stringResource(R.string.help_custom_source_params))
    HelpParamsTable()

    Spacer(Modifier.height(14.dp))
    HelpSubtitle(stringResource(R.string.help_custom_source_parse_title))
    listOf(
        R.string.help_custom_source_parse_b1,
        R.string.help_custom_source_parse_b2,
        R.string.help_custom_source_parse_b3,
    ).forEachIndexed { index, res ->
        HelpItem(
            numeral = helpItemNumeral(index),
            text = helpHighlight(stringResource(res), query, primary),
        )
    }

    Spacer(Modifier.height(6.dp))
    HelpCodeBlock(stringResource(R.string.help_custom_source_parse_pansou_example))
    Spacer(Modifier.height(6.dp))
    HelpCodeBlock(stringResource(R.string.help_custom_source_parse_zreso_example))

    Spacer(Modifier.height(14.dp))
    HelpSubtitle(stringResource(R.string.help_custom_source_example_title))
    HelpCodeBlock(stringResource(R.string.help_custom_source_example))
}

/** 十四个可配参数。参数名和示例值保留等宽字——这两列是要照着抄的。 */
@Composable
private fun HelpParamsTable() {
    val params = listOf(
        Triple("name", stringResource(R.string.help_cs_param_name), "我的搜索源"),
        Triple("baseUrl", stringResource(R.string.help_cs_param_baseUrl), "https://example.com/"),
        Triple("apiPath", stringResource(R.string.help_cs_param_apiPath), "api/search"),
        Triple("keywordParam", stringResource(R.string.help_cs_param_keyword), "kw"),
        Triple("cloudTypesParam", stringResource(R.string.help_cs_param_cloudTypes), "cloud_types"),
        Triple("cloudTypesValue", stringResource(R.string.help_cs_param_cloudTypesVal), "quark,baidu"),
        Triple("srcParam", stringResource(R.string.help_cs_param_src), "src"),
        Triple("srcValue", stringResource(R.string.help_cs_param_srcVal), "all"),
        Triple("parseMode", stringResource(R.string.help_cs_param_parseMode), "pansou_template"),
        Triple("listPath", stringResource(R.string.help_cs_param_listPath), "$.data.results"),
        Triple("namePath", stringResource(R.string.help_cs_param_namePath), "$.title"),
        Triple("urlPath", stringResource(R.string.help_cs_param_urlPath), "$.url"),
        Triple("diskTypePath", stringResource(R.string.help_cs_param_diskTypePath), "$.type"),
        Triple("datePath", stringResource(R.string.help_cs_param_datePath), "$.datetime"),
    )
    HelpPanel {
        Column {
            HelpTableHead(
                cells = listOf(
                    stringResource(R.string.help_cs_table_param) to 0.30f,
                    stringResource(R.string.help_cs_table_desc) to 0.40f,
                    stringResource(R.string.help_cs_table_example) to 0.30f,
                ),
            )
            params.forEach { (param, desc, example) ->
                HelpTableRow(
                    cells = listOf(
                        HelpCell(param, 0.30f, mono = true),
                        HelpCell(desc, 0.40f, mono = false),
                        HelpCell(example, 0.30f, mono = true),
                    ),
                )
            }
        }
    }
}

/** 豆瓣状态 × Trakt 状态 → 结果与动作，八种组合。 */
@Composable
private fun HelpConsistencyTable() {
    val rows = listOf(
        listOf(R.string.watchlist_mode_watched, R.string.watchlist_mode_watchlist, R.string.watchlist_mode_watched, R.string.help_consistency_act_trakt_watched_remove_wish),
        listOf(R.string.watchlist_mode_watchlist, R.string.watchlist_mode_watched, R.string.watchlist_mode_watched, R.string.help_consistency_act_douban_watched_upgrade),
        listOf(R.string.watchlist_mode_watched, R.string.help_status_unmarked, R.string.watchlist_mode_watched, R.string.help_consistency_act_trakt_watched),
        listOf(R.string.watchlist_mode_watchlist, R.string.help_status_unmarked, R.string.watchlist_mode_watchlist, R.string.help_consistency_act_trakt_wish),
        listOf(R.string.help_status_unmarked, R.string.watchlist_mode_watched, R.string.watchlist_mode_watched, R.string.help_consistency_act_douban_watched),
        listOf(R.string.help_status_unmarked, R.string.watchlist_mode_watchlist, R.string.watchlist_mode_watchlist, R.string.help_consistency_act_douban_wish),
        listOf(R.string.watchlist_mode_watched, R.string.watchlist_mode_watched, R.string.watchlist_mode_watched, R.string.help_no_action),
        listOf(R.string.watchlist_mode_watchlist, R.string.watchlist_mode_watchlist, R.string.watchlist_mode_watchlist, R.string.help_no_action),
    )
    val weights = listOf(0.21f, 0.21f, 0.17f, 0.41f)
    HelpPanel {
        Column {
            HelpTableHead(
                cells = listOf(
                    stringResource(R.string.help_consistency_col_douban) to weights[0],
                    stringResource(R.string.help_consistency_col_trakt) to weights[1],
                    stringResource(R.string.help_consistency_col_result) to weights[2],
                    stringResource(R.string.help_consistency_col_action) to weights[3],
                ),
            )
            rows.forEach { row ->
                HelpTableRow(
                    cells = row.mapIndexed { column, res ->
                        HelpCell(stringResource(res), weights[column], mono = false)
                    },
                )
            }
        }
    }
}

/** 表格里的一格。[mono] 的格子是要照抄的字面值，用等宽。 */
private data class HelpCell(
    val text: String,
    val weight: Float,
    val mono: Boolean,
)

/** 表头：主题强调色小字 + 一道分隔线收底。 */
@Composable
private fun HelpTableHead(cells: List<Pair<String, Float>>) {
    Row(modifier = Modifier.fillMaxWidth()) {
        cells.forEach { (text, weight) ->
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                lineHeight = 15.sp,
                modifier = Modifier
                    .weight(weight)
                    .padding(end = 6.dp),
            )
        }
    }
    HelpDivider(modifier = Modifier.padding(top = 5.dp, bottom = 6.dp))
}

@Composable
private fun HelpTableRow(cells: List<HelpCell>) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 5.dp)
    ) {
        cells.forEach { cell ->
            Text(
                text = cell.text,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = if (cell.mono) FontFamily.Monospace else null,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 15.sp,
                modifier = Modifier
                    .weight(cell.weight)
                    .padding(end = 6.dp),
            )
        }
    }
}
