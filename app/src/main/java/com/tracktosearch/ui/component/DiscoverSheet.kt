package com.tracktosearch.ui.component

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import com.tracktosearch.ui.theme.floatingSheetColor

/** 发现页栏目统一使用的底部 Sheet 容器。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoverModalBottomSheet(
    onDismissRequest: () -> Unit,
    content: @Composable () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = floatingSheetColor(),
        content = { content() }
    )
}
