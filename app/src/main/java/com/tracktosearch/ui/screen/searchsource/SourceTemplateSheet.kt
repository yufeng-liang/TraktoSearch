package com.tracktosearch.ui.screen.searchsource

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tracktosearch.R
import com.tracktosearch.data.local.SearchSourceTemplate
import com.tracktosearch.data.local.SearchSourceTemplates

/** 模板库半屏弹层：网格展示内置模板 + 底部导入快捷通道 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourceTemplateSheet(
    onTemplateClick: (SearchSourceTemplate) -> Unit,
    onImport: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Text(
            text = stringResource(R.string.template_library_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            val chunks = SearchSourceTemplates.all.chunked(2)
            chunks.forEach { rowTemplates ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    rowTemplates.forEach { template ->
                        TemplateCell(
                            template = template,
                            onClick = { onTemplateClick(template) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    if (rowTemplates.size == 1) Spacer(modifier = Modifier.weight(1f))
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { onImport() }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.template_import_shortcut),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

/** 单个模板格：选中态描边 */
@Composable
private fun TemplateCell(
    template: SearchSourceTemplate,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable { onClick() }
            .padding(10.dp)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp)),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(template.icon, style = MaterialTheme.typography.headlineSmall)
        Text(template.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        Text(
            template.description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}