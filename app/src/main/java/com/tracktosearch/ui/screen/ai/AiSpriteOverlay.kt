package com.tracktosearch.ui.screen.ai

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.tracktosearch.R

/** 搜索页的短互动入口；它只提供四项固定能力，不开放闲聊。 */
@Composable
fun AiSpriteOverlay(
    visible: Boolean,
    character: com.tracktosearch.data.ai.AiCharacter?,
    onDismiss: () -> Unit,
    onOpenFeature: (AiFeature) -> Unit
) {
    if (character == null) return
    val isRight = remember(character.id) { character.id.hashCode() and 1 == 0 }
    val alignment = if (isRight) Alignment.CenterEnd else Alignment.CenterStart
    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .fillMaxWidth()
            .zIndex(4f)
    ) {
        AnimatedVisibility(
            visible = visible,
            modifier = Modifier.align(alignment),
            enter = fadeIn() + slideInHorizontally { if (isRight) it else -it },
            exit = fadeOut() + slideOutHorizontally { if (isRight) it else -it }
        ) {
            Row(
                modifier = Modifier.padding(
                    top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 92.dp,
                    bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 24.dp,
                    start = 8.dp,
                    end = 8.dp
                ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(0.dp)
            ) {
            if (!isRight) {
                SpritePeek(character = character, modifier = Modifier.offset(x = (-28).dp))
            }
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f),
                tonalElevation = 6.dp
            ) {
                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.ai_sprite_overlay_message, character.name),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = stringResource(R.string.ai_sprite_overlay_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = onDismiss) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = stringResource(R.string.ai_sprite_close)
                            )
                        }
                    }
                    Spacer(Modifier.size(4.dp))
                    FeatureActions(onOpenFeature = onOpenFeature, onDismiss = onDismiss)
                }
            }
                if (isRight) {
                    SpritePeek(character = character, modifier = Modifier.offset(x = 28.dp))
                }
            }
        }
    }
}

@Composable
private fun SpritePeek(
    character: com.tracktosearch.data.ai.AiCharacter,
    modifier: Modifier = Modifier
) {
    AiCharacterGlyph(character = character, modifier = modifier.size(86.dp))
}

@Composable
private fun FeatureActions(
    onOpenFeature: (AiFeature) -> Unit,
    onDismiss: () -> Unit
) {
    val actions = listOf(
        AiFeature.GREETING to (R.string.ai_feature_greeting to Icons.Rounded.AutoAwesome),
        AiFeature.TASTE to (R.string.ai_feature_taste to Icons.Rounded.Movie),
        AiFeature.QUIZ to (R.string.ai_feature_quiz to Icons.Rounded.Psychology),
        AiFeature.DAILY to (R.string.ai_feature_daily to Icons.Rounded.Lightbulb)
    )
    actions.chunked(2).forEach { rowActions ->
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            rowActions.forEach { (feature, labelAndIcon) ->
                val (labelRes, icon) = labelAndIcon
                androidx.compose.material3.TextButton(
                    onClick = {
                        onDismiss()
                        onOpenFeature(feature)
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(icon, contentDescription = null, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.size(4.dp))
                    Text(stringResource(labelRes), maxLines = 1)
                }
            }
        }
    }
}
