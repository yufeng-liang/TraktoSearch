package com.tracktosearch.ui.screen.swiftie.eras

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform

private val GULL_WHITE = Color(0xFFF9FAF6)
private val GULL_WING = Color(0xFFDCE5E8)
private val GULL_SHADE = Color(0xFF96ADB9)
private val GULL_INK = Color(0xFF405868)
private val GULL_BEAK = Color(0xFFCBA366)

internal fun DrawScope.drawFlyingSeagull(
    path: Path,
    pose: SeagullPose,
    halfSpan: Float,
    direction: Float,
    alpha: Float
) {
    withTransform({
        translate(pose.x * size.width, pose.y * size.height)
        rotate(pose.headingDegrees, Offset.Zero)
        scale(halfSpan * direction, halfSpan, Offset.Zero)
    }) {
        gullWing(path, pose, side = 1f, reach = 0.79f - pose.bank * 0.16f, alpha, far = true)

        path.rewind()
        path.moveTo(-0.18f, 0.025f)
        path.lineTo(-0.49f, 0.10f)
        path.lineTo(-0.46f, 0.19f)
        path.quadraticTo(-0.28f, 0.16f, -0.13f, 0.12f)
        path.close()
        drawPath(path, GULL_WING, alpha = alpha)
        drawPath(path, GULL_SHADE, alpha = alpha * 0.7f, style = Stroke(0.012f))

        // 身体和颈头连成实心轮廓，远翼藏在肩后，近翼再盖住肩根。
        path.rewind()
        path.moveTo(-0.28f, 0.065f)
        path.cubicTo(-0.10f, -0.025f, 0.06f, -0.025f, 0.18f, -0.07f)
        path.cubicTo(0.22f, -0.17f, 0.35f, -0.17f, 0.38f, -0.08f)
        path.cubicTo(0.39f, -0.01f, 0.32f, 0.025f, 0.24f, 0.025f)
        path.cubicTo(0.09f, 0.18f, -0.14f, 0.18f, -0.28f, 0.065f)
        path.close()
        drawPath(path, GULL_WHITE, alpha = alpha)
        drawPath(path, GULL_SHADE, alpha = alpha * 0.85f, style = Stroke(0.014f))
        path.rewind()
        path.moveTo(-0.22f, 0.085f)
        path.quadraticTo(0.02f, 0.20f, 0.22f, 0.035f)
        path.quadraticTo(0.03f, 0.13f, -0.22f, 0.085f)
        path.close()
        drawPath(path, GULL_SHADE, alpha = alpha * 0.48f)

        gullWing(path, pose, side = -1f, reach = 1f + pose.bank * 0.16f, alpha, far = false)

        path.rewind()
        path.moveTo(0.365f, -0.092f)
        path.lineTo(0.51f, -0.052f)
        path.lineTo(0.38f, -0.032f)
        path.close()
        drawPath(path, GULL_BEAK, alpha = alpha)
        drawCircle(GULL_INK, 0.014f, Offset(0.327f, -0.087f), alpha = alpha)
    }
}

private fun DrawScope.gullWing(
    path: Path,
    pose: SeagullPose,
    side: Float,
    reach: Float,
    alpha: Float,
    far: Boolean
) {
    val width = reach * (1f - pose.fold * 0.22f)
    val elbowX = side * width * 0.46f
    val tipX = side * width
    val elbowY = -0.18f + pose.shoulder * 0.34f + side * pose.bank * 0.15f
    val tipY = -0.10f + pose.wingTip * 0.66f + side * pose.bank * 0.24f
    val trailingY = 0.16f - pose.fold * 0.055f
    path.rewind()
    path.moveTo(-0.055f, 0.012f)
    path.cubicTo(side * 0.13f, -0.11f, elbowX * 0.64f, elbowY - 0.10f, elbowX, elbowY)
    path.cubicTo(
        elbowX + (tipX - elbowX) * 0.32f, elbowY + (tipY - elbowY) * 0.12f,
        tipX * 0.93f, tipY - 0.045f,
        tipX, tipY
    )
    path.lineTo(tipX * 0.91f, tipY + 0.075f)
    path.lineTo(tipX * 0.89f, tipY + 0.025f)
    path.lineTo(tipX * 0.80f, tipY + 0.125f)
    path.lineTo(tipX * 0.78f, tipY + 0.07f)
    path.lineTo(tipX * 0.68f, tipY + 0.16f)
    path.quadraticTo(elbowX, elbowY + trailingY, side * 0.23f, 0.105f)
    path.quadraticTo(side * 0.06f, 0.08f, -0.055f, 0.012f)
    path.close()
    drawPath(path, if (far) GULL_WING else GULL_WHITE, alpha = alpha)
    drawPath(path, GULL_SHADE, alpha = alpha * 0.78f, style = Stroke(0.012f))

    path.rewind()
    path.moveTo(side * 0.10f, 0.035f)
    path.quadraticTo(elbowX * 0.75f, elbowY + 0.015f, elbowX, elbowY + 0.055f)
    path.lineTo(tipX * 0.79f, tipY + 0.055f)
    path.lineTo(tipX * 0.68f, tipY + 0.13f)
    path.quadraticTo(elbowX, elbowY + trailingY * 0.8f, side * 0.10f, 0.035f)
    path.close()
    drawPath(path, GULL_SHADE, alpha = alpha * if (far) 0.64f else 0.42f)

    path.rewind()
    path.moveTo(tipX * 0.73f, tipY + (elbowY - tipY) * 0.36f - 0.025f)
    path.quadraticTo(tipX * 0.92f, tipY - 0.035f, tipX, tipY)
    path.lineTo(tipX * 0.91f, tipY + 0.075f)
    path.lineTo(tipX * 0.89f, tipY + 0.025f)
    path.lineTo(tipX * 0.80f, tipY + 0.125f)
    path.lineTo(tipX * 0.78f, tipY + 0.07f)
    path.lineTo(tipX * 0.68f, tipY + 0.16f)
    path.quadraticTo(tipX * 0.75f, tipY + 0.055f, tipX * 0.73f, tipY + (elbowY - tipY) * 0.36f - 0.025f)
    path.close()
    drawPath(path, GULL_INK, alpha = alpha * 0.88f)
    drawOval(
        GULL_WHITE,
        topLeft = Offset(tipX * 0.84f - 0.018f, tipY + 0.018f),
        size = Size(0.036f, 0.023f),
        alpha = alpha * 0.9f
    )
}
