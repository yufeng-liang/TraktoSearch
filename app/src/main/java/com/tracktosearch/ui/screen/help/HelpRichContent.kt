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
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.tracktosearch.R

/**
 * 段落里那些没法用一串 StringRes 表达的手写内容：三张数据卡、参数表、对照表、代码块。
 *
 * 这些是全页最不像「纸质说明书」的部分——表格和等宽字天生属于技术文档。做法是把它们
 * 收进 [HelpPaperPanel]（另一档纸色），当成手册末尾的技术附录页，而不是硬拗成散文。
 */
@Composable
internal fun HelpExtraContent(
    extra: HelpExtra,
    paper: HelpPaper,
    query: String,
) {
    when (extra) {
        HelpExtra.DATA_SOURCES -> HelpDataSources(paper = paper, query = query)
        HelpExtra.CUSTOM_SOURCE_SPEC -> HelpCustomSourceSpec(paper, query)
        HelpExtra.CONSISTENCY_TABLE -> HelpConsistencyTable(paper)
    }
}

/** 导出 / IMDb 导入 / 豆瓣同步三件事，每件一行：图标、名字、格式、入口、说明。 */
@Composable
private fun HelpDataSources(paper: HelpPaper, query: String) {
    HelpSubtitle(stringResource(R.string.help_data_table_title), paper)
    HelpPaperPanel(paper = paper) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            HelpDataRow(
                icon = Icons.Rounded.FileDownload,
                title = stringResource(R.string.help_dc_export_t),
                format = stringResource(R.string.help_dc_export_fmt),
                entry = stringResource(R.string.help_dc_export_entry),
                description = stringResource(R.string.help_dc_export_desc),
                paper = paper,
                query = query,
            )
            HelpInkRule(paper)
            HelpDataRow(
                icon = Icons.Rounded.FileUpload,
                title = stringResource(R.string.help_dc_imdb_t),
                format = stringResource(R.string.help_dc_imdb_fmt),
                entry = stringResource(R.string.help_dc_imdb_entry),
                description = stringResource(R.string.help_dc_imdb_desc),
                paper = paper,
                query = query,
            )
            HelpInkRule(paper)
            HelpDataRow(
                icon = Icons.Rounded.Sync,
                title = stringResource(R.string.help_dc_douban_t),
                format = stringResource(R.string.help_dc_douban_fmt),
                entry = stringResource(R.string.help_dc_douban_entry),
                description = stringResource(R.string.help_dc_douban_desc),
                paper = paper,
                query = query,
            )
        }
    }
}

/**
 * 一行数据操作。
 *
 * 格式标签做成描边小签而不是 Material 的填充色块：填充块会在纸面上变成一个塑料贴片。
 * 入口那一行原本前面挂了个 📍 emoji，纸面上一个彩色 emoji 比整段文字都抢眼，去掉，
 * 改用更淡的墨色把它压成注解。
 */
@Composable
private fun HelpDataRow(
    icon: ImageVector,
    title: String,
    format: String,
    entry: String,
    description: String,
    paper: HelpPaper,
    query: String,
) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = paper.palette.ochre,
            modifier = Modifier
                .padding(top = 2.dp, end = 10.dp)
                .size(16.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = helpHighlight(title, query, paper.palette.seal),
                    color = paper.palette.ink,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Serif,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(end = 8.dp),
                )
                HelpFormatTag(format, paper)
            }
            Spacer(Modifier.height(3.dp))
            HelpFineLine(entry, paper, query, muted = true)
            HelpFineLine(description, paper, query, muted = false)
        }
    }
}

/** 格式标签：主题强调色描边小签，等宽字保留技术文档层级。 */
@Composable
private fun HelpFormatTag(format: String, paper: HelpPaper) {
    Text(
        text = format,
        color = paper.palette.seal,
        fontSize = 9.5.sp,
        fontFamily = FontFamily.Monospace,
        letterSpacing = 0.1.em,
        modifier = Modifier
            .border(
                width = 1.dp,
                color = paper.palette.seal.copy(alpha = 0.42f),
                shape = RoundedCornerShape(3.dp),
            )
            .padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

/** 卡片里的细行。[muted] 的那行是「在哪找到它」，比说明更弱一档。 */
@Composable
private fun HelpFineLine(
    text: String,
    paper: HelpPaper,
    query: String,
    muted: Boolean,
) {
    Text(
        text = helpHighlight(text, query, paper.palette.seal),
        color = if (muted) paper.palette.inkSoft else paper.body,
        fontSize = 11.5.sp,
        lineHeight = 18.sp,
        fontFamily = FontFamily.Serif,
        modifier = Modifier.padding(top = 1.dp),
    )
}

/**
 * 自定义搜索源的技术规格：参数表 → 解析说明 → 三段示例 JSON。
 *
 * 解析说明那三条重新从「一」编号：它们是这一小节的第一二三条，不是整段的第九十十一条。
 */
@Composable
private fun HelpCustomSourceSpec(paper: HelpPaper, query: String) {
    val seal = paper.palette.seal
    HelpSubtitle(stringResource(R.string.help_custom_source_params), paper)
    HelpParamsTable(paper)

    Spacer(Modifier.height(14.dp))
    HelpSubtitle(stringResource(R.string.help_custom_source_parse_title), paper)
    listOf(
        R.string.help_custom_source_parse_b1,
        R.string.help_custom_source_parse_b2,
        R.string.help_custom_source_parse_b3,
    ).forEachIndexed { index, res ->
        HelpItem(
            numeral = helpItemNumeral(index),
            text = helpHighlight(stringResource(res), query, seal),
            paper = paper,
        )
    }

    Spacer(Modifier.height(6.dp))
    HelpCodeBlock(stringResource(R.string.help_custom_source_parse_pansou_example), paper)
    Spacer(Modifier.height(6.dp))
    HelpCodeBlock(stringResource(R.string.help_custom_source_parse_zreso_example), paper)

    Spacer(Modifier.height(14.dp))
    HelpSubtitle(stringResource(R.string.help_custom_source_example_title), paper)
    HelpCodeBlock(stringResource(R.string.help_custom_source_example), paper)
}

/** 十四个可配参数。参数名和示例值保留等宽字——这两列是要照着抄的。 */
@Composable
private fun HelpParamsTable(paper: HelpPaper) {
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
    HelpPaperPanel(paper = paper) {
        Column {
            HelpTableHead(
                cells = listOf(
                    stringResource(R.string.help_cs_table_param) to 0.30f,
                    stringResource(R.string.help_cs_table_desc) to 0.40f,
                    stringResource(R.string.help_cs_table_example) to 0.30f,
                ),
                paper = paper,
            )
            params.forEach { (param, desc, example) ->
                HelpTableRow(
                    cells = listOf(
                        HelpCell(param, 0.30f, mono = true),
                        HelpCell(desc, 0.40f, mono = false),
                        HelpCell(example, 0.30f, mono = true),
                    ),
                    paper = paper,
                )
            }
        }
    }
}

/** 豆瓣状态 × Trakt 状态 → 结果与动作，八种组合。 */
@Composable
private fun HelpConsistencyTable(paper: HelpPaper) {
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
    HelpPaperPanel(paper = paper) {
        Column {
            HelpTableHead(
                cells = listOf(
                    stringResource(R.string.help_consistency_col_douban) to weights[0],
                    stringResource(R.string.help_consistency_col_trakt) to weights[1],
                    stringResource(R.string.help_consistency_col_result) to weights[2],
                    stringResource(R.string.help_consistency_col_action) to weights[3],
                ),
                paper = paper,
            )
            rows.forEach { row ->
                HelpTableRow(
                    cells = row.mapIndexed { column, res ->
                        HelpCell(stringResource(res), weights[column], mono = false)
                    },
                    paper = paper,
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
private fun HelpTableHead(cells: List<Pair<String, Float>>, paper: HelpPaper) {
    Row(modifier = Modifier.fillMaxWidth()) {
        cells.forEach { (text, weight) ->
            Text(
                text = text,
                color = paper.palette.seal,
                fontSize = 10.sp,
                lineHeight = 15.sp,
                fontFamily = FontFamily.Serif,
                letterSpacing = 0.06.em,
                modifier = Modifier
                    .weight(weight)
                    .padding(end = 6.dp),
            )
        }
    }
    HelpInkRule(paper, modifier = Modifier.padding(top = 5.dp, bottom = 6.dp))
}

@Composable
private fun HelpTableRow(cells: List<HelpCell>, paper: HelpPaper) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 5.dp)
    ) {
        cells.forEach { cell ->
            Text(
                text = cell.text,
                color = paper.body,
                fontSize = if (cell.mono) 10.sp else 10.5.sp,
                lineHeight = 15.sp,
                fontFamily = if (cell.mono) FontFamily.Monospace else FontFamily.Serif,
                modifier = Modifier
                    .weight(cell.weight)
                    .padding(end = 6.dp),
            )
        }
    }
}
