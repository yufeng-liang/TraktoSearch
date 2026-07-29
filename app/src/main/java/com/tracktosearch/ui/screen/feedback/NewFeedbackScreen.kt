package com.tracktosearch.ui.screen.feedback

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.SentimentDissatisfied
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.tracktosearch.R

private const val MAX_SCREENSHOTS = 5

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewFeedbackScreen(
    onBack: () -> Unit,
    onSuccess: () -> Unit,
    viewModel: FeedbackViewModel = hiltViewModel()
) {
    val submitState by viewModel.submitState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var selectedType by remember { mutableStateOf<String?>(null) }
    var content by remember { mutableStateOf("") }
    var contact by remember { mutableStateOf("") }
    var screenshots by remember { mutableStateOf<List<Pair<ByteArray, String>>>(emptyList()) }

    // 截图全屏查看
    var fullscreenIndex by remember { mutableStateOf<Int?>(null) }

    // 图片选择器
    val pickImageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris: List<Uri> ->
        val newScreenshots = uris.take(MAX_SCREENSHOTS - screenshots.size).mapNotNull { uri ->
            try {
                val mimeType = context.contentResolver.getType(uri) ?: "image/jpeg"
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                if (bytes != null) bytes to mimeType else null
            } catch (e: Exception) {
                null
            }
        }
        screenshots = (screenshots + newScreenshots).take(MAX_SCREENSHOTS)
    }

    // 提交成功后返回
    LaunchedEffect(submitState) {
        if (submitState is FeedbackViewModel.SubmitState.Success) {
            onSuccess()
            viewModel.resetSubmitState()
        }
    }

    val isSubmitting = submitState is FeedbackViewModel.SubmitState.Uploading || submitState is FeedbackViewModel.SubmitState.Submitting

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.feedback_new), fontWeight = FontWeight.ExtraBold) },
                navigationIcon = {
                    IconButton(onClick = onBack, enabled = !isSubmitting) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 类型选择 — 两行两列分散居中
            Text(stringResource(R.string.feedback_select_type), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                    FeedbackTypeChip(R.string.feedback_type_feature, Color(0xFF34D399), Icons.Rounded.Lightbulb, selectedType == "FEATURE") {
                        selectedType = if (selectedType == "FEATURE") null else "FEATURE"
                    }
                    FeedbackTypeChip(R.string.feedback_type_bug, Color(0xFFFB7185), Icons.Rounded.BugReport, selectedType == "BUG") {
                        selectedType = if (selectedType == "BUG") null else "BUG"
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                    FeedbackTypeChip(R.string.feedback_type_ux, Color(0xFFFBBF24), Icons.Rounded.SentimentDissatisfied, selectedType == "UX") {
                        selectedType = if (selectedType == "UX") null else "UX"
                    }
                    FeedbackTypeChip(R.string.feedback_type_other, Color(0xFF9CA3AF), Icons.Rounded.MoreHoriz, selectedType == "OTHER") {
                        selectedType = if (selectedType == "OTHER") null else "OTHER"
                    }
                }
            }

            // 正文
            OutlinedTextField(
                value = content,
                onValueChange = { if (it.length <= 2000) content = it },
                modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                placeholder = { Text(stringResource(R.string.feedback_content_placeholder)) },
                label = { Text(stringResource(R.string.feedback_content_label)) },
                isError = content.isNotEmpty() && content.length < 5,
                supportingText = {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        if (content.isNotEmpty() && content.length < 5) {
                            Text(stringResource(R.string.feedback_content_too_short), color = MaterialTheme.colorScheme.error)
                        }
                        Text("${content.length}/2000", fontSize = 11.sp)
                    }
                },
                enabled = !isSubmitting
            )

            // 截图
            Text(stringResource(R.string.feedback_screenshots), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ScreenshotRow(
                screenshots = screenshots,
                enabled = !isSubmitting,
                onAddClick = { pickImageLauncher.launch("image/*") },
                onRemoveClick = { index -> screenshots = screenshots.toMutableList().apply { removeAt(index) } },
                onImageClick = { index -> fullscreenIndex = index },
                onReorder = { from, to ->
                    screenshots = screenshots.toMutableList().apply {
                        add(to, removeAt(from))
                    }
                }
            )

            // 联系方式
            OutlinedTextField(
                value = contact,
                onValueChange = { contact = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.feedback_contact_placeholder)) },
                label = { Text(stringResource(R.string.feedback_contact)) },
                enabled = !isSubmitting,
                singleLine = true
            )

            // 错误信息
            (submitState as? FeedbackViewModel.SubmitState.Error)?.let {
                Text(it.message, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
            }

            // 提交按钮
            Button(
                onClick = {
                    if (selectedType != null && content.length >= 5) {
                        viewModel.submit(
                            type = selectedType!!,
                            content = content.trim(),
                            contact = contact.trim().ifEmpty { null },
                            screenshotBytes = screenshots.map { it.first },
                            screenshotMimeTypes = screenshots.map { it.second }
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = selectedType != null && content.length >= 5 && !isSubmitting
            ) {
                if (isSubmitting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(Modifier.width(8.dp))
                    val progressText = when (val s = submitState) {
                        is FeedbackViewModel.SubmitState.Uploading ->
                            stringResource(R.string.feedback_uploading, s.current + 1, s.total)
                        is FeedbackViewModel.SubmitState.Submitting ->
                            stringResource(R.string.feedback_submitting)
                        else -> stringResource(R.string.feedback_submitting)
                    }
                    Text(text = progressText, fontWeight = FontWeight.Bold)
                } else {
                    Text(
                        text = stringResource(R.string.feedback_submit),
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }

    // 截图全屏查看
    fullscreenIndex?.let { index ->
        if (screenshots.isNotEmpty()) {
            ScreenshotFullscreenOverlay(
                images = screenshots.map { it.first },
                initialIndex = index.coerceIn(0, screenshots.size - 1),
                onDismiss = { fullscreenIndex = null }
            )
        }
    }
}

/**
 * 截图行：支持添加、删除、点击查看大图、长按拖动排序
 */
@Composable
private fun ScreenshotRow(
    screenshots: List<Pair<ByteArray, String>>,
    enabled: Boolean,
    onAddClick: () -> Unit,
    onRemoveClick: (Int) -> Unit,
    onImageClick: (Int) -> Unit,
    onReorder: (Int, Int) -> Unit
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val swapThreshold = with(density) { (80.dp + 8.dp).toPx() }

    var draggingIndex by remember { mutableStateOf<Int?>(null) }
    var dragOffsetX by remember { mutableFloatStateOf(0f) }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        screenshots.forEachIndexed { index, (bytes, _) ->
            Box(
                Modifier
                    .size(80.dp)
                    .pointerInput(enabled, screenshots.size) {
                        if (!enabled || screenshots.size < 2) return@pointerInput
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                draggingIndex = index
                                dragOffsetX = 0f
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                val current = draggingIndex ?: return@detectDragGesturesAfterLongPress
                                dragOffsetX += dragAmount.x
                                if (dragOffsetX > swapThreshold && current < screenshots.size - 1) {
                                    onReorder(current, current + 1)
                                    draggingIndex = current + 1
                                    dragOffsetX -= swapThreshold
                                } else if (dragOffsetX < -swapThreshold && current > 0) {
                                    onReorder(current, current - 1)
                                    draggingIndex = current - 1
                                    dragOffsetX += swapThreshold
                                }
                            },
                            onDragEnd = {
                                draggingIndex = null
                                dragOffsetX = 0f
                            },
                            onDragCancel = {
                                draggingIndex = null
                                dragOffsetX = 0f
                            }
                        )
                    }
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(enabled = enabled) { onImageClick(index) },
                contentAlignment = Alignment.Center
            ) {
                AsyncImage(
                    model = remember(bytes) {
                        ImageRequest.Builder(context)
                            .data(bytes)
                            .crossfade(true)
                            .build()
                    },
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(80.dp)
                )
                // 右上角删除按钮
                if (enabled) {
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(2.dp)
                            .size(20.dp)
                            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
                            .clickable { onRemoveClick(index) },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Rounded.Close,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }
        }
        if (screenshots.size < MAX_SCREENSHOTS && enabled) {
            Box(
                Modifier
                    .size(80.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                    .clickable { onAddClick() },
                contentAlignment = Alignment.Center
            ) {
                Text("+", fontSize = 24.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun FeedbackTypeChip(
    labelRes: Int,
    color: Color,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    onClick: () -> Unit
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(stringResource(labelRes)) },
        leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp)) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = color.copy(alpha = 0.2f),
            selectedLabelColor = color,
            selectedLeadingIconColor = color
        )
    )
}
