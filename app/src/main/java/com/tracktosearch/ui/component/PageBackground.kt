package com.tracktosearch.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.lerp

/** 暂时只保留低亮度中性灰底，并以 5% 主题色维持主题切换氛围。 */
@Composable
fun PageBackground(
    modifier: Modifier = Modifier
) {
    val pageColor = lerp(
        MaterialTheme.colorScheme.background,
        MaterialTheme.colorScheme.primary,
        0.05f
    )
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(pageColor)
    )
}
