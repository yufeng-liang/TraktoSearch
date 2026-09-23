package com.tracktosearch.ui.screen.searchsource

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tracktosearch.R
import com.tracktosearch.data.local.SearchSourceTemplate
import com.tracktosearch.data.local.SearchSourceTemplates
import com.tracktosearch.ui.component.AppBottomSheet
import com.tracktosearch.ui.haptic.HapticSemantic
import com.tracktosearch.ui.haptic.hapticClickable

/**
 * 模板库半屏弹层：网格展示内置模板 + 底部导入快捷通道。
 *
 * [onImport] 传 null 时隐藏导入行——编辑页内没有导入弹层，摆一个点了没反应的入口更糟。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourceTemplateSheet(
    onTemplateClick: (SearchSourceTemplate) -> Unit,
    onImport: (() -> Unit)?,
    onDismiss: () -> Unit
) {
    AppBottomSheet(
        onDismissRequest = onDismiss,
        // 标题走组件标题栏：居中手写档与面板档只差对齐与字重，收进来后与其它带标题
        // 面板共用同一条标题栏（含右上角关闭）。
        title = stringResource(R.string.template_library_title),
        skipPartiallyExpanded = false,
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) }
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
            val chunks = SearchSourceTemplates.all.chunked(2)
            chunks.forEach { rowTemplates ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
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
            if (onImport != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .hapticClickable(semantic = HapticSemantic.LIGHT_TAP) { onImport() }
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
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

/** 单个模板格：固定高度等宽，描述限两行避免文字贴边 */
@Composable
private fun TemplateCell(
    template: SearchSourceTemplate,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .padding(12.dp)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
            .height(112.dp)
            // 模板格不是「选中某个模板」而是拿它去开编辑页，没有选中态，按列表项给 LIGHT_TAP
            .hapticClickable(semantic = HapticSemantic.LIGHT_TAP, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(template.icon, style = MaterialTheme.typography.headlineSmall)
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = template.name,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            template.description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 10.dp)
        )
    }
}