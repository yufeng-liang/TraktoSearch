package com.tracktosearch.ui.screen.searchsource

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.rememberScrollState
import com.tracktosearch.R

/**
 * 手动配置表单：名称/地址/路径/关键词参数/网盘参数/src/解析模式/JSONPath。
 * 从 SettingsDialogs.kt 的 CustomSourceEditDialog 表单体迁移，
 * 供向导「展开高级参数」与「手动调整 JSONPath」复用。
 */
@Composable
fun CustomSourceFields(
    name: String, onNameChange: (String) -> Unit,
    baseUrl: String, onBaseUrlChange: (String) -> Unit,
    apiPath: String, onApiPathChange: (String) -> Unit,
    keywordParam: String, onKeywordParamChange: (String) -> Unit,
    cloudTypesParam: String, onCloudTypesParamChange: (String) -> Unit,
    cloudTypesValue: String, onCloudTypesValueChange: (String) -> Unit,
    srcParam: String, onSrcParamChange: (String) -> Unit,
    srcValue: String, onSrcValueChange: (String) -> Unit,
    parseMode: String, onParseModeChange: (String) -> Unit,
    listPath: String, onListPathChange: (String) -> Unit,
    namePath: String, onNamePathChange: (String) -> Unit,
    urlPath: String, onUrlPathChange: (String) -> Unit,
    diskTypePath: String, onDiskTypePathChange: (String) -> Unit,
    datePath: String, onDatePathChange: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedTextField(
            value = name,
            onValueChange = onNameChange,
            label = { Text(stringResource(R.string.settings_source_name)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = baseUrl,
            onValueChange = onBaseUrlChange,
            label = { Text(stringResource(R.string.settings_source_base_url)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = apiPath,
            onValueChange = onApiPathChange,
            label = { Text(stringResource(R.string.settings_source_api_path)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = keywordParam,
            onValueChange = onKeywordParamChange,
            label = { Text(stringResource(R.string.settings_source_keyword_param)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = cloudTypesParam,
            onValueChange = onCloudTypesParamChange,
            label = { Text(stringResource(R.string.settings_source_disk_param)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = cloudTypesValue,
            onValueChange = onCloudTypesValueChange,
            label = { Text(stringResource(R.string.settings_source_disk_param_value)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = srcParam,
            onValueChange = onSrcParamChange,
            label = { Text(stringResource(R.string.settings_source_src_param)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = srcValue,
            onValueChange = onSrcValueChange,
            label = { Text(stringResource(R.string.settings_source_src_value)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(4.dp))
        Text(stringResource(R.string.settings_source_parse_mode), style = MaterialTheme.typography.bodySmall)
        val parseModes = listOf(
            "pansou_template" to stringResource(R.string.settings_source_parse_mode_pansou),
            "zreso_template" to stringResource(R.string.settings_source_parse_mode_zreso),
            "custom" to stringResource(R.string.settings_source_parse_mode_custom)
        )
        parseModes.chunked(2).forEach { rowModes ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                rowModes.forEach { (mode, label) ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onParseModeChange(mode) }
                    ) {
                        RadioButton(
                            selected = parseMode == mode,
                            onClick = { onParseModeChange(mode) }
                        )
                        Text(label, style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (rowModes.size == 1) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }

        // 自定义 JSONPath 字段
        if (parseMode == "custom") {
            Spacer(modifier = Modifier.height(4.dp))
            Text(stringResource(R.string.settings_source_jsonpath_title), style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(
                value = listPath,
                onValueChange = onListPathChange,
                label = { Text(stringResource(R.string.settings_source_jsonpath_list)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = namePath,
                onValueChange = onNamePathChange,
                label = { Text(stringResource(R.string.settings_source_jsonpath_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = urlPath,
                onValueChange = onUrlPathChange,
                label = { Text(stringResource(R.string.settings_source_jsonpath_url)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = diskTypePath,
                onValueChange = onDiskTypePathChange,
                label = { Text(stringResource(R.string.settings_source_jsonpath_disk)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = datePath,
                onValueChange = onDatePathChange,
                label = { Text(stringResource(R.string.settings_source_jsonpath_date)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}