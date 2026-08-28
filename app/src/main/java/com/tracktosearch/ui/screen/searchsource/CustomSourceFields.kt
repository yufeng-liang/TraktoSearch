package com.tracktosearch.ui.screen.searchsource

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.tracktosearch.R

// 示例值不随语言变化（URL、参数名、JSONPath 都是字面量），放常量比进四份 strings.xml 更省事
internal const val EXAMPLE_BASE_URL = "https://so.252035.xyz/"
private const val EXAMPLE_API_PATH = "api/search"
private const val EXAMPLE_KEYWORD_PARAM = "kw"
private const val EXAMPLE_DISK_PARAM = "cloud_types"
private const val EXAMPLE_DISK_VALUE = "quark,baidu"
private const val EXAMPLE_SRC_PARAM = "src"
private const val EXAMPLE_SRC_VALUE = "all"
private const val EXAMPLE_LIST_PATH = "\$.data.results"
private const val EXAMPLE_NAME_PATH = "title"
private const val EXAMPLE_URL_PATH = "url"
private const val EXAMPLE_DISK_PATH = "type"
private const val EXAMPLE_DATE_PATH = "datetime"

/** 编辑器表单的 14 个字段值。打包传递，免得每层组合都摊开 14 个参数。 */
@Immutable
data class SourceFormValues(
    val name: String,
    val baseUrl: String,
    val apiPath: String,
    val keywordParam: String,
    val cloudTypesParam: String,
    val cloudTypesValue: String,
    val srcParam: String,
    val srcValue: String,
    val parseMode: String,
    val listPath: String,
    val namePath: String,
    val urlPath: String,
    val diskTypePath: String,
    val datePath: String,
)

/**
 * 表单回调集合。
 *
 * 成员都是 ViewModel 的方法引用，同一个 ViewModel 拿到的引用相等，
 * 用 remember(viewModel) 建一次即可，不会因为它是普通类而破坏跳过重组。
 */
@Immutable
data class SourceFormCallbacks(
    val onName: (String) -> Unit,
    val onBaseUrl: (String) -> Unit,
    val onApiPath: (String) -> Unit,
    val onKeywordParam: (String) -> Unit,
    val onCloudTypesParam: (String) -> Unit,
    val onCloudTypesValue: (String) -> Unit,
    val onSrcParam: (String) -> Unit,
    val onSrcValue: (String) -> Unit,
    val onParseMode: (String) -> Unit,
    val onListPath: (String) -> Unit,
    val onNamePath: (String) -> Unit,
    val onUrlPath: (String) -> Unit,
    val onDiskTypePath: (String) -> Unit,
    val onDatePath: (String) -> Unit,
)

/**
 * 单个表单字段：浮动短标签 + 示例占位符 + 说明行。
 *
 * 原先每个字段是「一行说明文字 + 一个无标签输入框」，滚动时说明和输入框容易看错行；
 * 改成 Material 浮动标签后，字段名永远贴在自己的框上。
 */
@Composable
fun SourceTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    required: Boolean = false,
    supporting: String? = null,
    monospace: Boolean = false,
    trailingIcon: (@Composable () -> Unit)? = null,
) {
    val optional = stringResource(R.string.editor_optional)
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        label = {
            if (required) {
                Text(
                    buildAnnotatedString {
                        append(label)
                        withStyle(SpanStyle(color = MaterialTheme.colorScheme.error)) { append(" *") }
                    }
                )
            } else {
                Text(label)
            }
        },
        placeholder = placeholder?.let {
            {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }
        },
        supportingText = when {
            supporting != null -> {
                { Text(supporting, style = MaterialTheme.typography.labelSmall) }
            }
            !required -> {
                { Text(optional, style = MaterialTheme.typography.labelSmall) }
            }
            else -> null
        },
        trailingIcon = trailingIcon,
        textStyle = if (monospace) {
            MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace)
        } else {
            MaterialTheme.typography.bodyLarge
        }
    )
}

/**
 * 可折叠分组卡片：标题 + 一句说明 + 展开箭头。
 *
 * 13 个字段一次铺开是从前最大的问题——第一次添加源的人看到一屏输入框会直接退出。
 * 分成三组、默认只展开第一组，让「现在要填什么」始终只有几个框。
 */
@Composable
fun SourceGroupCard(
    title: String,
    description: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        label = "groupArrow"
    )
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = description,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(
                    imageVector = Icons.Rounded.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier
                        .size(22.dp)
                        .rotate(rotation),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    content = content
                )
            }
        }
    }
}

/** 基本信息组：名称 / Base URL / API 路径 */
@Composable
fun BasicFieldsGroup(
    values: SourceFormValues,
    callbacks: SourceFormCallbacks,
    expanded: Boolean,
    onToggle: () -> Unit,
    baseUrlTrailingIcon: (@Composable () -> Unit)? = null,
) {
    SourceGroupCard(
        title = stringResource(R.string.editor_group_basic),
        description = stringResource(R.string.editor_group_basic_desc),
        expanded = expanded,
        onToggle = onToggle
    ) {
        SourceTextField(
            label = stringResource(R.string.settings_source_name),
            value = values.name,
            onValueChange = callbacks.onName,
            required = true,
            supporting = stringResource(R.string.editor_name_hint)
        )
        SourceTextField(
            label = stringResource(R.string.editor_label_base_url),
            value = values.baseUrl,
            onValueChange = callbacks.onBaseUrl,
            placeholder = EXAMPLE_BASE_URL,
            required = true,
            supporting = stringResource(R.string.editor_base_url_hint),
            monospace = true,
            trailingIcon = baseUrlTrailingIcon
        )
        SourceTextField(
            label = stringResource(R.string.editor_label_api_path),
            value = values.apiPath,
            onValueChange = callbacks.onApiPath,
            placeholder = EXAMPLE_API_PATH,
            required = true,
            monospace = true
        )
    }
}

/** 请求参数组：关键字参数 + 可选的网盘类型 / 来源参数 */
@Composable
fun RequestFieldsGroup(
    values: SourceFormValues,
    callbacks: SourceFormCallbacks,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    SourceGroupCard(
        title = stringResource(R.string.editor_group_request),
        description = stringResource(R.string.editor_group_request_desc),
        expanded = expanded,
        onToggle = onToggle
    ) {
        SourceTextField(
            label = stringResource(R.string.editor_label_keyword_param),
            value = values.keywordParam,
            onValueChange = callbacks.onKeywordParam,
            placeholder = EXAMPLE_KEYWORD_PARAM,
            required = true,
            monospace = true
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            SourceTextField(
                label = stringResource(R.string.editor_label_disk_param),
                value = values.cloudTypesParam,
                onValueChange = callbacks.onCloudTypesParam,
                placeholder = EXAMPLE_DISK_PARAM,
                monospace = true,
                modifier = Modifier.weight(1f)
            )
            SourceTextField(
                label = stringResource(R.string.editor_label_disk_value),
                value = values.cloudTypesValue,
                onValueChange = callbacks.onCloudTypesValue,
                placeholder = EXAMPLE_DISK_VALUE,
                monospace = true,
                modifier = Modifier.weight(1f)
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            SourceTextField(
                label = stringResource(R.string.editor_label_src_param),
                value = values.srcParam,
                onValueChange = callbacks.onSrcParam,
                placeholder = EXAMPLE_SRC_PARAM,
                monospace = true,
                modifier = Modifier.weight(1f)
            )
            SourceTextField(
                label = stringResource(R.string.editor_label_src_value),
                value = values.srcValue,
                onValueChange = callbacks.onSrcValue,
                placeholder = EXAMPLE_SRC_VALUE,
                monospace = true,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/** 解析规则组：解析模式单选 + 自定义模式下的 5 个 JSONPath */
@Composable
fun ParseFieldsGroup(
    values: SourceFormValues,
    callbacks: SourceFormCallbacks,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    SourceGroupCard(
        title = stringResource(R.string.editor_group_parse),
        description = stringResource(R.string.editor_group_parse_desc),
        expanded = expanded,
        onToggle = onToggle
    ) {
        val parseModes = listOf(
            "pansou_template" to stringResource(R.string.settings_source_parse_mode_pansou),
            "zreso_template" to stringResource(R.string.settings_source_parse_mode_zreso),
            "custom" to stringResource(R.string.settings_source_parse_mode_custom)
        )
        Text(
            text = stringResource(R.string.settings_source_parse_mode),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        parseModes.forEach { (mode, label) ->
            val selected = values.parseMode == mode
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        if (selected) {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                        } else {
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.4f)
                        }
                    )
                    .clickable { callbacks.onParseMode(mode) }
                    .padding(end = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = selected,
                    onClick = { callbacks.onParseMode(mode) }
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    }
                )
            }
        }

        // 模板模式下 JSONPath 由模板内置，露出这 5 个框只会让人以为必须填
        AnimatedVisibility(visible = values.parseMode == "custom") {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.settings_source_jsonpath_title),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                SourceTextField(
                    label = stringResource(R.string.editor_label_list_path),
                    value = values.listPath,
                    onValueChange = callbacks.onListPath,
                    placeholder = EXAMPLE_LIST_PATH,
                    required = true,
                    monospace = true
                )
                SourceTextField(
                    label = stringResource(R.string.editor_label_name_path),
                    value = values.namePath,
                    onValueChange = callbacks.onNamePath,
                    placeholder = EXAMPLE_NAME_PATH,
                    required = true,
                    monospace = true
                )
                SourceTextField(
                    label = stringResource(R.string.editor_label_url_path),
                    value = values.urlPath,
                    onValueChange = callbacks.onUrlPath,
                    placeholder = EXAMPLE_URL_PATH,
                    required = true,
                    monospace = true
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    SourceTextField(
                        label = stringResource(R.string.editor_label_disk_path),
                        value = values.diskTypePath,
                        onValueChange = callbacks.onDiskTypePath,
                        placeholder = EXAMPLE_DISK_PATH,
                        monospace = true,
                        modifier = Modifier.weight(1f)
                    )
                    SourceTextField(
                        label = stringResource(R.string.editor_label_date_path),
                        value = values.datePath,
                        onValueChange = callbacks.onDatePath,
                        placeholder = EXAMPLE_DATE_PATH,
                        monospace = true,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}
