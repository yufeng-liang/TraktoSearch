package com.tracktosearch.ui.screen.ai

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.tracktosearch.R
import com.tracktosearch.data.ai.AiAudio
import com.tracktosearch.data.ai.AiDailyKnowledge
import com.tracktosearch.data.ai.AiGreeting
import com.tracktosearch.data.ai.AiRecommendation
import com.tracktosearch.data.ai.AiTasteAnalysis

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiFeatureScreen(
    feature: AiFeature,
    state: AiSpriteUiState,
    viewModel: AiSpriteViewModel,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onPlayAudio: (AiAudio) -> Unit,
    onMovieClick: (Int, Int, String, String, Double, Boolean, Boolean) -> Unit,
    onShowClick: (Int, Int, String, String, Double, Boolean, Boolean) -> Unit,
    onRecommendationClick: ((AiRecommendation) -> Unit)? = null
) {
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = featureTitle(feature),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.ai_feature_back)
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onRefresh, enabled = !state.isLoading) {
                        Icon(
                            Icons.Rounded.Refresh,
                            contentDescription = stringResource(R.string.ai_feature_refresh)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding())
        ) {
            when (feature) {
                AiFeature.GREETING -> GreetingFeature(greeting = state.greeting, onPlayAudio = onPlayAudio)
                AiFeature.TASTE -> TasteFeature(
                    taste = state.taste,
                    onMovieClick = onMovieClick,
                    onShowClick = onShowClick,
                    onRecommendationClick = onRecommendationClick
                )
                AiFeature.QUIZ -> AiQuizScreen(state = state, viewModel = viewModel)
                AiFeature.DAILY -> DailyFeature(daily = state.dailyKnowledge)
            }

            if (state.isLoading) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 12.dp),
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    tonalElevation = 2.dp
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text(stringResource(R.string.ai_feature_loading), style = MaterialTheme.typography.labelMedium)
                    }
                }
            }

            if (state.errorCode != null) {
                FeatureError(
                    modifier = Modifier.align(Alignment.Center),
                    onRetry = onRefresh
                )
            }
        }
    }
}

@Composable
private fun GreetingFeature(greeting: AiGreeting?, onPlayAudio: (AiAudio) -> Unit) {
    if (greeting == null) {
        FeatureUnavailable()
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                tonalElevation = 2.dp
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(Icons.Rounded.AutoAwesome, contentDescription = null)
                        Text(
                            text = stringResource(R.string.ai_feature_nickname),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                    Text(greeting.greeting, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)
                }
            }
        }
        item {
            MeaningSection(
                title = stringResource(R.string.ai_feature_meaning),
                text = greeting.nicknameMeaning
            )
        }
        item {
            MeaningSection(
                title = stringResource(R.string.ai_feature_comment),
                text = greeting.comment
            )
        }
        item {
            greeting.audio?.let { audio ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(Icons.Rounded.VolumeUp, contentDescription = null)
                        Text(stringResource(R.string.ai_audio_play), modifier = Modifier.weight(1f))
                        IconButton(onClick = { onPlayAudio(audio) }) {
                            Icon(Icons.Rounded.VolumeUp, contentDescription = stringResource(R.string.ai_audio_play))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MeaningSection(title: String, text: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(6.dp))
            Text(text, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun TasteFeature(
    taste: AiTasteAnalysis?,
    onMovieClick: (Int, Int, String, String, Double, Boolean, Boolean) -> Unit,
    onShowClick: (Int, Int, String, String, Double, Boolean, Boolean) -> Unit,
    onRecommendationClick: ((AiRecommendation) -> Unit)?
) {
    if (taste == null) {
        FeatureUnavailable()
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            SectionTitle(
                title = stringResource(R.string.ai_taste_title),
                icon = Icons.Rounded.AutoAwesome
            )
        }
        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.tertiaryContainer
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text(stringResource(R.string.ai_taste_roast), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onTertiaryContainer)
                    Spacer(Modifier.height(8.dp))
                    Text(taste.roast, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        item {
            MeaningSection(
                title = stringResource(R.string.ai_taste_profile),
                text = taste.tasteProfile
            )
        }
        if (taste.highlights.isNotEmpty()) {
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(stringResource(R.string.ai_taste_highlights), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        taste.highlights.forEach { highlight ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                                Icon(Icons.Rounded.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                                Text(highlight, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
        }
        item {
            Text(
                text = stringResource(R.string.ai_taste_recommendations),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }
        if (taste.recommendations.isEmpty()) {
            item { Text(stringResource(R.string.ai_taste_no_recommendations), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        } else {
            // id 可能为空（回退到 title），同名条目会重复 → 用 index 兜底保证 key 唯一
            itemsIndexed(taste.recommendations, key = { index, recommendation -> "${recommendation.id}_$index" }) { _, recommendation ->
                RecommendationCard(
                    recommendation = recommendation,
                    onOpen = {
                        if (onRecommendationClick != null) {
                            onRecommendationClick(recommendation)
                        } else {
                            val hasNativeId = (recommendation.traktId ?: 0) > 0 || (recommendation.tmdbId ?: 0) > 0
                            val imdbOrNavigationKey = if (hasNativeId) {
                                recommendation.imdbId.orEmpty()
                            } else {
                                recommendationNavigationKey(recommendation).orEmpty()
                            }
                            val traktId = recommendation.traktId ?: 0
                            val tmdbId = recommendation.tmdbId ?: 0
                            if (recommendation.mediaType.lowercase() == "show") {
                                onShowClick(traktId, tmdbId, recommendation.title, imdbOrNavigationKey, 0.0, false, false)
                            } else {
                                onMovieClick(traktId, tmdbId, recommendation.title, imdbOrNavigationKey, 0.0, false, false)
                            }
                        }
                    }
                )
            }
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun RecommendationCard(recommendation: AiRecommendation, onOpen: () -> Unit) {
    val hasMediaId = recommendationHasDetailRoute(recommendation)
    val mediaIcon = if (recommendation.mediaType.lowercase() == "show") Icons.Rounded.LiveTv else Icons.Rounded.Movie
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (hasMediaId) Modifier.clickable(onClick = onOpen) else Modifier),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 1.dp
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(width = 62.dp, height = 90.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center
            ) {
                if (recommendation.posterUrl.isNullOrBlank()) {
                    Icon(mediaIcon, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                } else {
                    AsyncImage(
                        model = recommendation.posterUrl,
                        contentDescription = recommendation.title,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                }
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(recommendation.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                recommendation.year?.let { Text(it.toString(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Text(recommendation.reason, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            if (hasMediaId) {
                Icon(Icons.Rounded.OpenInNew, contentDescription = stringResource(R.string.ai_taste_details), tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun DailyFeature(daily: AiDailyKnowledge?) {
    if (daily == null) {
        FeatureUnavailable()
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(26.dp),
                color = MaterialTheme.colorScheme.secondaryContainer
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(stringResource(R.string.ai_daily_title), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(10.dp))
                    Text(daily.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)
                }
            }
        }
        item { MeaningSection(title = stringResource(R.string.ai_daily_fact), text = daily.fact) }
        item { MeaningSection(title = stringResource(R.string.ai_daily_explanation), text = daily.explanation) }
        if (!daily.characterLine.isNullOrBlank()) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                    Icon(Icons.Rounded.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text(daily.characterLine.orEmpty(), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        if (daily.sourceName.isNotBlank()) {
            item {
                Text(
                    text = stringResource(R.string.ai_daily_source_format, daily.sourceName),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
    }
}

@Composable
private fun FeatureUnavailable() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = stringResource(R.string.ai_feature_unavailable),
            modifier = Modifier.padding(24.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun FeatureError(modifier: Modifier = Modifier, onRetry: () -> Unit) {
    Surface(
        modifier = modifier.padding(24.dp),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.errorContainer
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(stringResource(R.string.ai_error), color = MaterialTheme.colorScheme.onErrorContainer, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            OutlinedButton(onClick = onRetry) { Text(stringResource(R.string.ai_feature_retry)) }
        }
    }
}

@Composable
private fun featureTitle(feature: AiFeature): String = when (feature) {
    AiFeature.GREETING -> stringResource(R.string.ai_feature_greeting)
    AiFeature.TASTE -> stringResource(R.string.ai_feature_taste)
    AiFeature.QUIZ -> stringResource(R.string.ai_feature_quiz)
    AiFeature.DAILY -> stringResource(R.string.ai_feature_daily)
}
