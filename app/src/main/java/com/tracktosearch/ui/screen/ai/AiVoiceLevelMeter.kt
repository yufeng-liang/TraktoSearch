package com.tracktosearch.ui.screen.ai

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.random.Random

/*
 * 精灵中心「按住说话」的电平可视化：按钮上方那条竖条电平带，以及按钮外沿随音量呼吸的光环。
 * 单独开一个文件是因为 AiSpriteCenter.kt 已经九百行上下，这两块又只服务于激活面板，
 * 塞回去只会让那边更难读。
 */

/** 竖条根数。13 根在手机宽度下每根约 6dp，够密到像电平表，又不至于糊成一片。 */
private const val VOICE_METER_BAR_COUNT = 13

/**
 * 相位随机种子写死。
 *
 * 每次按住看到的高低起伏形状一致，用户才会把它读成「音量」；
 * 形状每次都变的话看起来只是个随机动画，说不说话都一样。
 */
private const val VOICE_METER_PHASE_SEED = 1_031L

/** 最矮的一根也吃掉这个比例的相位，避免两端在小音量时缩成看不见的点。 */
private const val VOICE_METER_MIN_PHASE = 0.35f

/** 相位抖动幅度：在包络上下浮动 ±18%，让整排不至于画成一条规整的拱形。 */
private const val VOICE_METER_PHASE_JITTER = 0.18f

/** 单根竖条占各自槽位宽度的比例，剩下的是条间空隙。 */
private const val VOICE_METER_BAR_WIDTH_FRACTION = 0.46f

/** 底噪轨道透明度：静音时这排淡竖条说明「在录，只是没拾到声」，而不是一行空白。 */
private const val VOICE_METER_TRACK_ALPHA = 0.18f

/**
 * 13 根竖条最多铺开这么宽。
 *
 * 组件本身要占满提示行的整行宽度（高度靠提示行撑着，不然行高会跳），
 * 但真把 13 根摊到整屏宽上每根会宽到 12dp 上下，看着像柱状图而不是电平表，
 * 所以只画在起始这一段里，起点也就和左对齐的提示文字对上了。
 */
private val VOICE_METER_MAX_WIDTH = 170.dp

/** 光环最大外扩距离：满电平时描边离按钮边缘 10dp。 */
private val VOICE_HALO_MAX_SPREAD = 10.dp

/** 光环线宽。 */
private val VOICE_HALO_STROKE_WIDTH = 2.dp

/** 满电平时的描边透明度。 */
private const val VOICE_HALO_MAX_ALPHA = 0.55f

/** 低于这个电平一笔不画：静音时光环该彻底消失，而不是在按钮外留一道死线。 */
private const val VOICE_HALO_MIN_LEVEL = 0.02f

/**
 * 声音强度电平带：13 根圆角竖条，条高 = 电平 × 各条固定相位系数。
 *
 * level 由 ViewModel 侧平滑过（起快落慢），这里不再做任何时间相关处理——
 * 画的时候再插值一次会让「喊中了」的那一瞬间晚半拍才在屏幕上立起来。
 *
 * 纯装饰，不挂任何语义：读屏用户听不出竖条高低，
 * 该说的话由按钮自己的 contentDescription 与 stateDescription 负责。
 */
@Composable
fun VoiceLevelMeter(level: Float, modifier: Modifier = Modifier) {
    // 相位只算一次。每次重组重算会让同一个电平画出不同形状，看起来像识别在抽风；
    // 种子写死，所以跨次按住的形状也一致。
    val phases = remember {
        val random = Random(VOICE_METER_PHASE_SEED)
        val center = (VOICE_METER_BAR_COUNT - 1) / 2f
        FloatArray(VOICE_METER_BAR_COUNT) { index ->
            // 中间高两侧低的包络，让电平带看起来是从中心发声
            val distance = abs(index - center) / center
            val envelope = VOICE_METER_MIN_PHASE + (1f - VOICE_METER_MIN_PHASE) * (1f - distance * distance)
            val jitter = 1f - VOICE_METER_PHASE_JITTER + random.nextFloat() * VOICE_METER_PHASE_JITTER * 2f
            (envelope * jitter).coerceIn(VOICE_METER_MIN_PHASE, 1f)
        }
    }
    val barColor = MaterialTheme.colorScheme.primary
    val trackColor = MaterialTheme.colorScheme.primary.copy(alpha = VOICE_METER_TRACK_ALPHA)
    // 采集层给的应该是 0..1，越界值直接画会把竖条画出格子
    val safeLevel = level.coerceIn(0f, 1f)
    Canvas(modifier = modifier) {
        val drawWidth = size.width.coerceAtMost(VOICE_METER_MAX_WIDTH.toPx())
        val slotWidth = drawWidth / VOICE_METER_BAR_COUNT
        val barWidth = slotWidth * VOICE_METER_BAR_WIDTH_FRACTION
        val radius = CornerRadius(barWidth / 2f)
        // 静音时也要留一颗圆点：一整行全空看着像组件坏了
        val minBarHeight = barWidth.coerceAtMost(size.height)
        phases.forEachIndexed { index, phase ->
            val left = index * slotWidth + (slotWidth - barWidth) / 2f
            val barHeight = (size.height * safeLevel * phase).coerceIn(minBarHeight, size.height)
            drawRoundRect(
                color = trackColor,
                topLeft = Offset(left, 0f),
                size = Size(barWidth, size.height),
                cornerRadius = radius
            )
            drawRoundRect(
                color = barColor,
                // 从中线上下对称长出去，读起来像波形而不是柱状图
                topLeft = Offset(left, (size.height - barHeight) / 2f),
                size = Size(barWidth, barHeight),
                cornerRadius = radius
            )
        }
    }
}

/**
 * 按钮呼吸光环：绕按钮外沿画一圈随电平变半径与 alpha 的圆角矩形描边。
 *
 * 用 drawBehind 挂在按钮外层，是为了让描边能长到按钮外面去——
 * 按钮自身的 Surface 会裁剪自己的内容，画在里面的光环会被切掉一半。
 * 传进来的 color 只取色相，alpha 由电平决定。
 * level 为 0（没在录）时一笔不画，静止状态下就是个普通按钮。
 */
fun Modifier.voiceLevelHalo(level: Float, color: Color, cornerRadius: Dp): Modifier = drawBehind {
    val safeLevel = level.coerceIn(0f, 1f)
    if (safeLevel <= VOICE_HALO_MIN_LEVEL) return@drawBehind
    val spread = VOICE_HALO_MAX_SPREAD.toPx() * safeLevel
    drawRoundRect(
        color = color.copy(alpha = VOICE_HALO_MAX_ALPHA * safeLevel),
        topLeft = Offset(-spread, -spread),
        size = Size(size.width + spread * 2f, size.height + spread * 2f),
        cornerRadius = CornerRadius(cornerRadius.toPx() + spread),
        style = Stroke(width = VOICE_HALO_STROKE_WIDTH.toPx())
    )
}
