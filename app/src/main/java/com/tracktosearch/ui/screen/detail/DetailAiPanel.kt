package com.tracktosearch.ui.screen.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.geometry.Rect
import com.tracktosearch.R
import com.tracktosearch.data.ai.AiDetailInterestLevel
import com.tracktosearch.data.ai.AiDetailWatchTiming
import com.tracktosearch.ui.component.AppBottomSheet

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun DetailAiPanel(
    state: DetailAiUiState,
    onDismiss: () -> Unit,
    onGrantProfileConsent: () -> Unit,
    onNavigateToLogin: () -> Unit,
    onRetry: () -> Unit,
    onOpenReview: () -> Unit,
    onBoundsChanged: (Rect) -> Unit = {}
) {
    if (!state.panelVisible) return
    AppBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .onGloballyPositioned { onBoundsChanged(it.boundsInRoot()) },
            contentPadding = WindowInsets.navigationBars.asPaddingValues()
        ) {
            item {
                Column(
                    modifier = Modifier.padding(start = 20.dp, end = 12.dp, top = 4.dp, bottom = 8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.AutoAwesome,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp)
                        )
                        Text(
                            text = stringResource(R.string.detail_ai_title),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 8.dp)
                        )
                        IconButton(onClick = onDismiss) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = stringResource(R.string.detail_ai_close)
                            )
                        }
                    }
                }
            }
            item {
                DetailAiPanelBody(
                    state = state,
                    onGrantProfileConsent = onGrantProfileConsent,
                    onNavigateToLogin = onNavigateToLogin,
                    onRetry = onRetry,
                    onOpenReview = onOpenReview
                )
            }
        }
    }
}

@Composable
private fun DetailAiPanelBody(
    state: DetailAiUiState,
    onGrantProfileConsent: () -> Unit,
    onNavigateToLogin: () -> Unit,
    onRetry: () -> Unit,
    onOpenReview: () -> Unit
) {
    Column(
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (!state.isAuthorized) {
            Text(
                text = stringResource(R.string.detail_ai_login_message),
                style = MaterialTheme.typography.bodyLarge
            )
            Button(onClick = onNavigateToLogin, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.detail_ai_login_action))
            }
            return@Column
        }

        if (!state.profileConsent) {
            Text(
                text = stringResource(R.string.detail_ai_profile_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = stringResource(R.string.detail_ai_profile_message),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(onClick = onGrantProfileConsent, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.detail_ai_profile_action))
            }
            return@Column
        }

        when (state.scene) {
            DetailAiScene.WATCHED_NEEDS_REVIEW -> ReviewPrompt(onOpenReview)
            DetailAiScene.WATCHED_REVIEWED -> ReviewedPrompt()
            DetailAiScene.UNMARKED,
            DetailAiScene.WATCHLIST_CONTEXT -> AnalysisContent(state, onRetry)
        }
        Spacer(Modifier.size(4.dp))
    }
}

@Composable
private fun ReviewPrompt(onOpenReview: () -> Unit) {
    Text(
        text = stringResource(R.string.detail_ai_review_title),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold
    )
    Text(
        text = stringResource(R.string.detail_ai_review_message),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Button(onClick = onOpenReview, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.detail_ai_review_action))
    }
}

@Composable
private fun ReviewedPrompt() {
    Text(
        text = stringResource(R.string.detail_ai_reviewed_title),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold
    )
    Text(
        text = stringResource(R.string.detail_ai_reviewed_message),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun AnalysisContent(state: DetailAiUiState, onRetry: () -> Unit) {
    val analysis = state.analysis
    if (state.isLoading) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 28.dp),
            horizontalArrangement = Arrangement.Center
        ) {
            CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
        }
        return
    }
    if (state.errorCode != null) {
        Text(
            text = stringResource(R.string.detail_ai_error),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium
        )
        OutlinedButton(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.detail_ai_retry))
        }
        return
    }
    if (analysis == null) {
        Text(
            text = stringResource(R.string.detail_ai_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        return
    }

    Text(
        text = stringResource(
            R.string.detail_ai_interest,
            interestLabel(analysis.interestLevel),
            confidenceLabel(analysis.confidence)
        ),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold
    )
    DetailAiTextBlock(R.string.detail_ai_summary, analysis.spoilerFreeSummary)
    DetailAiTextBlock(R.string.detail_ai_public_rating, analysis.publicRatingInterpretation)
    DetailAiTextBlock(R.string.detail_ai_watch_advice, analysis.watchAdvice)
    if (analysis.reasons.isNotEmpty()) {
        Text(
            text = stringResource(R.string.detail_ai_reasons),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold
        )
        analysis.reasons.forEach { reason ->
            Text("• $reason", style = MaterialTheme.typography.bodyMedium)
        }
    }
    if (analysis.watchTiming != AiDetailWatchTiming.UNKNOWN) {
        Text(
            text = stringResource(R.string.detail_ai_timing, timingLabel(analysis.watchTiming)),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun DetailAiTextBlock(titleRes: Int, body: String) {
    if (body.isBlank()) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = stringResource(titleRes),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold
        )
        Text(text = body, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun interestLabel(level: AiDetailInterestLevel): String = stringResource(
    when (level) {
        AiDetailInterestLevel.HIGH -> R.string.detail_ai_interest_high
        AiDetailInterestLevel.MEDIUM -> R.string.detail_ai_interest_medium
        AiDetailInterestLevel.LOW -> R.string.detail_ai_interest_low
    }
)

@Composable
private fun confidenceLabel(level: AiDetailInterestLevel): String = stringResource(
    when (level) {
        AiDetailInterestLevel.HIGH -> R.string.detail_ai_confidence_high
        AiDetailInterestLevel.MEDIUM -> R.string.detail_ai_confidence_medium
        AiDetailInterestLevel.LOW -> R.string.detail_ai_confidence_low
    }
)

@Composable
private fun timingLabel(timing: AiDetailWatchTiming): String = stringResource(
    when (timing) {
        AiDetailWatchTiming.NOW -> R.string.detail_ai_timing_now
        AiDetailWatchTiming.SOON -> R.string.detail_ai_timing_soon
        AiDetailWatchTiming.LATER -> R.string.detail_ai_timing_later
        AiDetailWatchTiming.UNKNOWN -> R.string.detail_ai_timing_unknown
    }
)
