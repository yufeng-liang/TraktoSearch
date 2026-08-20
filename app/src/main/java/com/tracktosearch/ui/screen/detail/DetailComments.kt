package com.tracktosearch.ui.screen.detail

import androidx.compose.foundation.clickable
import com.tracktosearch.ui.theme.RatingGold
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.tracktosearch.R
import com.tracktosearch.data.remote.trakt.dto.TraktComment
import com.tracktosearch.ui.component.NeumorphicFrostedSurface
import com.tracktosearch.ui.component.GlassScene
import com.tracktosearch.ui.component.GlassSurfaceRole
import com.tracktosearch.ui.component.isAppDarkTheme

// ==================== 单条评论 ====================

@Composable
private fun ownCommentSyncDescription(targets: Set<OwnCommentTarget>): String = when {
    OwnCommentTarget.TRAKT in targets && OwnCommentTarget.DOUBAN in targets ->
        stringResource(R.string.detail_own_comment_sync_trakt_douban)
    OwnCommentTarget.TRAKT in targets -> stringResource(R.string.detail_own_comment_sync_trakt)
    OwnCommentTarget.DOUBAN in targets -> stringResource(R.string.detail_own_comment_sync_douban)
    else -> stringResource(R.string.detail_own_comment_sync_unavailable)
}

/** 输入框提示词：这里写下的短评将同步至 xx */
@Composable
private fun ownCommentPlaceholder(targets: Set<OwnCommentTarget>): String = when {
    OwnCommentTarget.TRAKT in targets && OwnCommentTarget.DOUBAN in targets ->
        stringResource(R.string.detail_own_comment_placeholder_trakt_douban)
    OwnCommentTarget.TRAKT in targets -> stringResource(R.string.detail_own_comment_placeholder_trakt)
    OwnCommentTarget.DOUBAN in targets -> stringResource(R.string.detail_own_comment_placeholder_douban)
    else -> stringResource(R.string.detail_own_comment_placeholder_unavailable)
}

@Composable
internal fun OwnCommentComposer(
    initialComment: String,
    targets: Set<OwnCommentTarget>,
    isSaving: Boolean,
    isEditing: Boolean,
    onSubmit: (String) -> Unit,
    onCancelEdit: () -> Unit,
    scene: GlassScene = GlassScene()
) {
    var comment by remember(initialComment) { mutableStateOf(initialComment) }
    val isDark = isAppDarkTheme()
    NeumorphicFrostedSurface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        isDark = isDark,
        shape = RoundedCornerShape(18.dp),
        elevation = 6.dp,
        blurRadius = 16.dp,
        shadowOffset = 5.dp,
        backgroundColor = if (isDark) Color.White.copy(alpha = 0.08f) else Color.White.copy(alpha = 0.65f),
        borderColor = if (isDark) Color.White.copy(alpha = 0.15f) else Color(0xFFD0D5DC).copy(alpha = 0.9f),
        darkShadowAlpha = if (isDark) 0.25f else 0.16f,
        lightShadowAlpha = if (isDark) 0.08f else 0.65f,
        hazeState = null,
        glassRole = GlassSurfaceRole.Card,
        scene = scene
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // 标题行：标题在左，发布按钮右对齐
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.detail_own_comment_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                if (isEditing) {
                    TextButton(onClick = onCancelEdit, enabled = !isSaving) {
                        Text(stringResource(R.string.detail_own_comment_cancel))
                    }
                }
                Button(
                    onClick = { onSubmit(comment) },
                    enabled = comment.isNotBlank() && !isSaving
                ) {
                    if (isSaving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    } else {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.Send,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.detail_own_comment_publish))
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
            OutlinedTextField(
                value = comment,
                onValueChange = { if (it.length <= 350) comment = it },
                modifier = Modifier.fillMaxWidth(),
                // 同步说明作为输入框提示词
                placeholder = { Text(ownCommentPlaceholder(targets)) },
                textStyle = MaterialTheme.typography.bodyMedium,
                minLines = 3,
                maxLines = 5,
                enabled = !isSaving,
                shape = RoundedCornerShape(12.dp)
            )
        }
    }
}

@Composable
internal fun OwnCommentCard(
    comment: String,
    targets: Set<OwnCommentTarget>,
    retryTargets: Set<OwnCommentTarget>,
    isSaving: Boolean,
    onEdit: () -> Unit,
    onRetry: () -> Unit,
    scene: GlassScene = GlassScene()
) {
    val isDark = isAppDarkTheme()
    val retryTargetName = when {
        OwnCommentTarget.TRAKT in retryTargets && OwnCommentTarget.DOUBAN in retryTargets ->
            stringResource(R.string.detail_own_comment_targets_trakt_douban)
        OwnCommentTarget.TRAKT in retryTargets -> stringResource(R.string.detail_own_comment_target_trakt)
        else -> stringResource(R.string.detail_own_comment_target_douban)
    }
    NeumorphicFrostedSurface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        isDark = isDark,
        shape = RoundedCornerShape(18.dp),
        elevation = 6.dp,
        blurRadius = 16.dp,
        shadowOffset = 5.dp,
        backgroundColor = if (isDark) Color.White.copy(alpha = 0.08f) else Color.White.copy(alpha = 0.65f),
        borderColor = if (isDark) Color.White.copy(alpha = 0.15f) else Color(0xFFD0D5DC).copy(alpha = 0.9f),
        darkShadowAlpha = if (isDark) 0.25f else 0.16f,
        lightShadowAlpha = if (isDark) 0.08f else 0.65f,
        hazeState = null,
        glassRole = GlassSurfaceRole.Card,
        scene = scene
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.detail_own_comment_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = ownCommentSyncDescription(targets),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onEdit, enabled = !isSaving) {
                    Icon(
                        imageVector = Icons.Rounded.Edit,
                        contentDescription = stringResource(R.string.detail_own_comment_edit),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            ExpandableText(text = comment)
            if (retryTargets.isNotEmpty()) {
                TextButton(
                    onClick = onRetry,
                    enabled = !isSaving,
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.detail_own_comment_retry, retryTargetName))
                }
            }
        }
    }
}

@Composable
internal fun CommentItem(
    comment: TraktComment,
    translatedText: String?,
    onTranslate: (Int) -> Unit,
    isTranslating: Boolean,
    isThisTranslating: Boolean,
    scene: GlassScene = GlassScene()
) {
    var showOriginal by remember(comment.id) { mutableStateOf(false) }
    var spoilerRevealed by remember { mutableStateOf(false) }

    val displayText = if (showOriginal) comment.comment else (translatedText ?: comment.comment)
    val isDark = isAppDarkTheme()
    val sourceLabel = if (comment.source == DOUBAN_COMMENT_SOURCE) {
        stringResource(R.string.detail_comment_source_douban)
    } else {
        comment.source
    }

    NeumorphicFrostedSurface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        isDark = isDark,
        shape = RoundedCornerShape(18.dp),
        elevation = 6.dp,
        blurRadius = 16.dp,
        shadowOffset = 5.dp,
        backgroundColor = if (isDark) Color.White.copy(alpha = 0.08f) else Color.White.copy(alpha = 0.65f),
        borderColor = if (isDark) Color.White.copy(alpha = 0.15f) else Color(0xFFD0D5DC).copy(alpha = 0.9f),
        darkShadowAlpha = if (isDark) 0.25f else 0.16f,
        lightShadowAlpha = if (isDark) 0.08f else 0.65f,
        hazeState = null,
        glassRole = GlassSurfaceRole.Card,
        scene = scene
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // 用户名 + 评分
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = comment.user.username,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = stringResource(R.string.detail_comment_source, sourceLabel),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (comment.user_rating != null) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Icon(
                        Icons.Rounded.Star,
                        contentDescription = null,
                        modifier = Modifier.size(12.dp),
                        tint = RatingGold
                    )
                    Text(
                        text = String.format("%.0f", comment.user_rating),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                // 单条翻译按钮（剧透未揭示时不显示）
                if ((!comment.spoiler || spoilerRevealed) && comment.source != DOUBAN_COMMENT_SOURCE) {
                    if (translatedText == null && !isThisTranslating) {
                        Text(
                            text = stringResource(R.string.detail_translate),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) { onTranslate(comment.id) }
                        )
                    } else if (isThisTranslating) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(12.dp),
                                strokeWidth = 1.5.dp
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = stringResource(R.string.detail_translating),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // 剧透遮罩
            if (comment.spoiler && !spoilerRevealed) {
                Surface(
                    onClick = { spoilerRevealed = true },
                    modifier = Modifier
                        .fillMaxWidth(),
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                ) {
                    Text(
                        text = stringResource(R.string.detail_spoiler),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(8.dp),
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                // 评论正文（折叠展开）
                ExpandableText(text = displayText)

                // 原文/译文切换
                if (translatedText != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        Text(
                            text = if (showOriginal) stringResource(R.string.detail_translated) else stringResource(R.string.detail_original),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) { showOriginal = !showOriginal }
                        )
                    }
                }
            }
        }
    }
}
