package com.tracktosearch.ui.theme

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.tooling.preview.Preview
import io.github.om252345.composemeshgradient.MeshGradient
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 主页面背景彩色弥散光晕预设。各预设共享同一组主题调色板（统一配色），
 * 仅运动方式不同，确保搜索/发现/我的/设置四个页面视觉连贯。
 */
enum class MeshPreset {
    AURORA,     // 极光：帘幕状缓慢横向流动
    LAVA_LAMP,  // 熔岩灯：缓慢 blob 形变漂浮
    BLOOM;      // 弥散绽放：呼吸式缩放漂移

    fun toStorage(): String = name

    companion object {
        fun fromStorage(value: String?): MeshPreset {
            if (value == null) return AURORA
            return runCatching { valueOf(value) }.getOrDefault(AURORA)
        }
    }
}

@Composable
fun AmbientMeshBackground(
    modifier: Modifier = Modifier,
    preset: MeshPreset = MeshPreset.AURORA,
    enabled: Boolean = true,
) {
    val colorScheme = MaterialTheme.colorScheme
    val isDark = colorScheme.background.luminance() < 0.5f

    // 统一调色板：取自主题种子色，四页共享，仅相位不同 -> 视觉打通
    val palette = remember(colorScheme) {
        arrayOf(
            colorScheme.primary,
            colorScheme.secondary,
            colorScheme.tertiary,
            colorScheme.primary,
            colorScheme.secondary,
            colorScheme.tertiary,
            colorScheme.primary,
            colorScheme.secondary,
            colorScheme.tertiary,
        )
    }

    Box(modifier = modifier.fillMaxSize()) {
        if (enabled) {
            val transition = rememberInfiniteTransition(label = "mesh")
            val duration = when (preset) {
                MeshPreset.AURORA -> 14000
                MeshPreset.LAVA_LAMP -> 18000
                MeshPreset.BLOOM -> 11000
            }
            val animatedT by transition.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(duration, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart,
                ),
                label = "meshT",
            )
            val mesh = remember(animatedT, preset, palette, isDark) {
                computeMesh(preset, animatedT, palette, isDark)
            }
            MeshGradient(
                width = 3,
                height = 3,
                points = mesh.first,
                colors = mesh.second,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            // 关闭时回退到原有的中性灰底，保证背景不空
            val fallback = remember(colorScheme) {
                lerp(colorScheme.background, colorScheme.primary, 0.05f)
            }
            Box(Modifier.fillMaxSize().background(fallback))
        }

        // 可读性蒙层：压低彩色亮度，保证文字对比度（类似 iOS 墙纸的暗化叠加）
        val scrim = if (isDark) {
            colorScheme.background.copy(alpha = 0.55f)
        } else {
            colorScheme.background.copy(alpha = 0.40f)
        }
        Box(Modifier.fillMaxSize().background(scrim))
    }
}

private const val TWO_PI = 2f * PI.toFloat()

/**
 * 计算 3x3 网格的控制点与颜色。点随时间小幅漂移即形成"彩色弥散运动"，
 * 横向漂移分量让光晕在搜索页右溢、发现页左接，实现跨页连续。
 */
private fun computeMesh(
    preset: MeshPreset,
    t: Float,
    palette: Array<Color>,
    isDark: Boolean,
): Pair<Array<Offset>, Array<Color>> {
    val base = arrayOf(
        Offset(0f, 0f), Offset(0.5f, 0f), Offset(1f, 0f),
        Offset(0f, 0.5f), Offset(0.5f, 0.5f), Offset(1f, 0.5f),
        Offset(0f, 1f), Offset(0.5f, 1f), Offset(1f, 1f),
    )
    val phase = t * TWO_PI
    val points = Array(9) { i ->
        val b = base[i]
        when (preset) {
            MeshPreset.AURORA -> Offset(
                b.x + 0.05f * sin(phase + i * 0.6f) + 0.06f * t,
                b.y + 0.10f * cos(phase + i * 0.5f),
            )
            MeshPreset.LAVA_LAMP -> Offset(
                b.x + 0.12f * sin(phase * 0.8f + i.toFloat()),
                b.y + 0.14f * cos(phase * 0.7f + i * 0.7f),
            )
            MeshPreset.BLOOM -> Offset(
                b.x + 0.05f * sin(phase + i * 0.4f),
                b.y + 0.05f * cos(phase * 1.1f + i * 0.4f),
            )
        }
    }
    // 统一调色板；浅色主题下轻微降低饱和以避免过艳
    val colors = Array(9) { i ->
        val c = palette[i]
        if (!isDark) c.copy(alpha = 0.9f) else c
    }
    return points to colors
}

@Preview
@Composable
private fun AmbientMeshBackgroundPreview() {
    MaterialTheme {
        AmbientMeshBackground(
            modifier = Modifier.fillMaxSize(),
            preset = MeshPreset.AURORA,
            enabled = true,
        )
    }
}
