package com.tracktosearch.ui.screen.swiftie.eras

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.sin

internal data class MidnightMoonPose(
    val pillowReveal: Float,
    val pillowLift: Float,
    val cuddle: Float,
    val eyeClose: Float,
    val sleep: Float,
    val breath: Float
)

// 与走针共用段内时间；预览定格、回拨和换张待机不能留下上一次的睡姿。
internal fun midnightMoonPose(eraMs: Long): MidnightMoonPose {
    val since = (eraMs - MIDNIGHT_WIND_START_MS).coerceAtLeast(0L).toFloat()
    val wind = MIDNIGHT_WIND_MS
    fun progress(start: Float, end: Float): Float {
        val t = ((since - start) / (end - start)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
    val sleep = progress(wind * 0.78f, wind + MIDNIGHT_SETTLE_MS)
    val breathingMs = (since - wind - MIDNIGHT_SETTLE_MS).coerceAtLeast(0f)
    return MidnightMoonPose(
        pillowReveal = progress(0f, wind * 0.30f),
        pillowLift = progress(wind * 0.15f, wind * 0.64f),
        cuddle = progress(wind * 0.48f, wind),
        eyeClose = progress(wind * 0.62f, wind * 0.92f),
        sleep = sleep,
        breath = sin(breathingMs / 3_200f * 2f * PI.toFloat()) * sleep
    )
}

private val MOON_INK = Color(0xFF514967)
private val MOON_EDGE = Color(0xFFE3C789)
private val MOON_BLUSH = Color(0xFFE4AC9B)
private val PILLOW_EDGE = Color(0xFFAEA0D2)
private val MOON_FILL = Brush.linearGradient(
    listOf(Color(0xFFFFF2CC), Color(0xFFEBD59C)),
    start = Offset(-75f, -90f), end = Offset(35f, 105f)
)
private val PILLOW_FILL = Brush.linearGradient(
    listOf(Color(0xFFF4EDFF), Color(0xFFCBBFE8)),
    start = Offset(-55f, -35f), end = Offset(55f, 40f)
)
private val MOON_GLOW = Brush.radialGradient(
    listOf(Color(0xFFFFE7AB).copy(alpha = 0.12f), Color.Transparent),
    center = Offset(-20f, 0f), radius = 155f
)

private val MOON_BODY: Path by lazy(LazyThreadSafetyMode.NONE) { Path().apply {
    moveTo(45f, -103f)
    cubicTo(-30f, -126f, -98f, -70f, -96f, 0f)
    cubicTo(-96f, 74f, -27f, 116f, 54f, 96f)
    cubicTo(14f, 86f, -13f, 52f, -13f, 13f)
    cubicTo(-13f, 7f, 5f, 5f, 4f, -2f)
    cubicTo(3f, -7f, -11f, -9f, -12f, -16f)
    cubicTo(-17f, -54f, 7f, -88f, 45f, -103f)
    close()
} }
private val MOON_RIM: Path by lazy(LazyThreadSafetyMode.NONE) { Path().apply {
    moveTo(16f, -96f)
    cubicTo(-43f, -99f, -85f, -48f, -83f, 6f)
    cubicTo(-82f, 55f, -45f, 87f, -7f, 94f)
} }
private val PILLOW_BODY: Path by lazy(LazyThreadSafetyMode.NONE) { Path().apply {
    moveTo(-62f, -40f)
    quadraticTo(-44f, -34f, -37f, -37f)
    quadraticTo(0f, -49f, 38f, -35f)
    quadraticTo(47f, -34f, 62f, -41f)
    quadraticTo(55f, -22f, 58f, -12f)
    quadraticTo(68f, 4f, 58f, 23f)
    quadraticTo(56f, 30f, 62f, 42f)
    quadraticTo(44f, 35f, 33f, 38f)
    quadraticTo(-2f, 48f, -38f, 35f)
    quadraticTo(-49f, 34f, -63f, 40f)
    quadraticTo(-55f, 22f, -58f, 12f)
    quadraticTo(-67f, -3f, -58f, -22f)
    quadraticTo(-57f, -29f, -62f, -40f)
    close()
} }
private val PILLOW_SEAM: Path by lazy(LazyThreadSafetyMode.NONE) { Path().apply {
    moveTo(-51f, -27f)
    quadraticTo(0f, -42f, 50f, -28f)
    quadraticTo(61f, 1f, 50f, 29f)
    quadraticTo(0f, 41f, -51f, 28f)
    quadraticTo(-61f, 0f, -51f, -27f)
    close()
} }

/**
 * 月牙相对「缺口朝钟」姿态的回转角（度）。
 *
 * 终态（抱枕抱好，`cuddle = 1`）把缺口精确对准钟心；初始态在后面这个基准上
 * 再顺时针偏 [MIDNIGHT_MOON_INITIAL_EXTRA_TURN_DEG]，抱枕过程中只回转这一小段。
 */
internal fun midnightMoonRotationDeg(lookAngleDeg: Float, cuddle: Float): Float =
    -lookAngleDeg +
        MIDNIGHT_MOON_INITIAL_EXTRA_TURN_DEG * (1f - cuddle.coerceIn(0f, 1f))

/**
 * 初始姿态相对终态的顺时针偏转（度）。
 *
 * 终态严格对齐钟心；初始态多转这一点，脸和抱枕会略偏向右下，
 * 抱枕抱起后再自然回到对齐姿态。
 */
internal const val MIDNIGHT_MOON_INITIAL_EXTRA_TURN_DEG: Float = 20f

/**
 * 月牙的基准缩放。
 *
 * 24 首版本用 1.305，卡片上缘把它右下角切掉一截。曲目减到 22 首后卡片虽然更高，
 * 但月牙仍要与钟面保持留白；收到 1.18，整只月牙和抱枕都完整落在卡片上缘之上。
 */
internal const val MIDNIGHT_MOON_SCALE: Float = 1.18f

/**
 * 月牙中心的纵向位置（屏高比例）。
 *
 * 从 0.352 上移到 0.22：卡片 22 首时上缘约在 0.34H，而抱枕落位后下缘约 0.33H；
 * 月牙和抱枕都完整留在卡片上方，且与左上角钟面保持距离。
 */
internal const val MOON_CENTER_Y: Float = 0.22f

/**
 * 月牙中心的横向位置（屏宽比例）。
 *
 * 从 0.65 右移到 0.70：初始姿态与抱枕状态都更贴近右边缘，
 * 同时给左上方留出钟面与月牙之间的深蓝留白。
 */
internal const val MOON_CENTER_X: Float = 0.70f

internal fun DrawScope.drawMidnightMoon(path: Path, eraMs: Long, alpha: Float, clockCenter: Offset) {
    val pose = midnightMoonPose(eraMs)
    val unit = minOf(size.width * 0.001695f, size.height * 0.00096f) * MIDNIGHT_MOON_SCALE
    val moonCenter = Offset(size.width * MOON_CENTER_X, size.height * MOON_CENTER_Y)
    val lookAngle = atan2(moonCenter.y - clockCenter.y, moonCenter.x - clockCenter.x) * 180f / PI.toFloat()
    withTransform({
        translate(moonCenter.x, moonCenter.y + pose.breath * unit * 1.3f)
        scale(-unit, unit, pivot = Offset.Zero)
        rotate(midnightMoonRotationDeg(lookAngle, pose.cuddle), pivot = Offset.Zero)
    }) {
        drawCircle(MOON_GLOW, radius = 155f, center = Offset(-20f, 0f), alpha = alpha)
        val pillowX = -60f + pose.pillowLift * 85f
        val pillowY = 79f - pose.pillowLift * 18f - sin(pose.pillowLift * PI.toFloat()) * 48f
        val pillowAngle = -28f + pose.pillowLift * 17f + pose.cuddle * 6f
        val pillowScale = 0.45f + pose.pillowReveal * 0.55f
        val hand = Offset(pillowX - 43f * pillowScale, pillowY - 22f * pillowScale)

        // 远侧手臂在月牙和枕头后方；近侧手掌稍后压住枕套。
        if (pose.pillowReveal > 0f) {
            path.rewind()
            path.moveTo(-62f, 22f)
            path.quadraticTo(-84f, 61f, hand.x, hand.y)
            drawPath(path, MOON_EDGE, alpha = alpha, style = Stroke(10f, cap = StrokeCap.Round))
            drawPath(path, Color(0xFFF6E4B5), alpha = alpha, style = Stroke(6.5f, cap = StrokeCap.Round))
        }

        rotate(18f * pose.cuddle, pivot = Offset(-30f, 38f)) {
            drawPath(MOON_BODY, MOON_FILL, alpha = alpha)
            drawPath(MOON_BODY, MOON_EDGE, alpha = alpha * 0.9f, style = Stroke(1.6f))
            drawPath(MOON_RIM, Color(0xFFFFFAE3), alpha = alpha * 0.72f, style = Stroke(2.4f, cap = StrokeCap.Round))
            drawOval(MOON_BLUSH, Offset(-53f, 7f), Size(24f, 12f), alpha = alpha * 0.45f)

            val eyeHeight = 9f * (1f - pose.eyeClose)
            if (eyeHeight > 0.4f) {
                drawOval(MOON_INK, Offset(-43f, -18f), Size(6f, eyeHeight), alpha = alpha)
                drawCircle(Color(0xFFFFFAE3), 1.1f, Offset(-40f, -16f), alpha = alpha * (1f - pose.eyeClose))
            }
            path.rewind()
            path.moveTo(-51f, -15f)
            path.quadraticTo(-40f, -15f + 12f * pose.eyeClose, -29f, -16f)
            drawPath(path, MOON_INK, alpha = alpha * pose.eyeClose, style = Stroke(2.5f, cap = StrokeCap.Round))
            for (i in 0..2) {
                val x = -48f + i * 6f
                drawLine(MOON_INK, Offset(x, -11f), Offset(x - 2f, -7f),
                    strokeWidth = 1.7f, alpha = alpha * pose.eyeClose, cap = StrokeCap.Round)
            }
            path.rewind()
            path.moveTo(-30f, 24f)
            path.quadraticTo(-22f, 30f, -16f, 22f)
            drawPath(path, MOON_INK, alpha = alpha * 0.8f, style = Stroke(2f, cap = StrokeCap.Round))
        }

        if (pose.pillowReveal > 0f) {
            withTransform({
                translate(pillowX, pillowY)
                rotate(pillowAngle, pivot = Offset.Zero)
                scale(pillowScale, pillowScale * (1f - pose.cuddle * 0.08f), pivot = Offset.Zero)
            }) {
                val pillowAlpha = alpha * pose.pillowReveal
                drawPath(PILLOW_BODY, PILLOW_FILL, alpha = pillowAlpha)
                drawPath(PILLOW_BODY, PILLOW_EDGE, alpha = pillowAlpha, style = Stroke(2f))
                drawPath(PILLOW_SEAM, Color.White, alpha = pillowAlpha * 0.66f, style = Stroke(1.2f))
                // 四角褶皱让枕头读成软布，而不是圆角卡片。
                for (side in intArrayOf(-1, 1)) {
                    for (end in intArrayOf(-1, 1)) {
                        drawLine(PILLOW_EDGE, Offset(side * 54f, end * 31f), Offset(side * 42f, end * 21f),
                            strokeWidth = 1.5f, alpha = pillowAlpha * 0.62f, cap = StrokeCap.Round)
                    }
                }
                path.rewind()
                path.moveTo(-18f, -22f)
                path.quadraticTo(-4f, -13f - pose.cuddle * 6f, 10f, -24f)
                drawPath(path, PILLOW_EDGE, alpha = pillowAlpha * pose.cuddle * 0.5f,
                    style = Stroke(1.7f, cap = StrokeCap.Round))
            }
            drawOval(Color(0xFFF5E1AD), Offset(hand.x - 8f, hand.y - 5f), Size(18f, 12f), alpha = alpha * pose.pillowReveal)
            path.rewind()
            path.moveTo(-46f, 47f)
            path.quadraticTo(-17f, 69f - pose.cuddle * 12f, pillowX + 7f, pillowY + 5f)
            drawPath(path, MOON_EDGE, alpha = alpha * pose.pillowLift, style = Stroke(10f, cap = StrokeCap.Round))
            drawPath(path, Color(0xFFF6E4B5), alpha = alpha * pose.pillowLift, style = Stroke(6.5f, cap = StrokeCap.Round))
            drawOval(Color(0xFFF5E1AD), Offset(pillowX, pillowY), Size(18f, 11f), alpha = alpha * pose.pillowLift)
        }
    }
}
