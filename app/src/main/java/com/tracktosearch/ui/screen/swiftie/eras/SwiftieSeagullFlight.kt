package com.tracktosearch.ui.screen.swiftie.eras

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

private const val GULL_TAU = 2f * PI.toFloat()

internal class SeagullFlight(
    val rounds: Int,
    val offset: Float,
    val halfSpan: Float,
    val altitude: Float,
    val bend: Float,
    val direction: Float,
    val beatOffset: Float
) {
    val pose = SeagullPose()
}

/** 每只鸟复用一份姿态，逐帧更新不分配对象。 */
internal class SeagullPose {
    var x = 0f
    var y = 0f
    var scale = 1f
    var alpha = 1f
    var headingDegrees = 0f
    var bank = 0f
    var shoulder = 0f
    var wingTip = 0f
    var fold = 0f
    var effort = 0f
}

/**
 * 按远到近绘制；低配保留一远一近，不把所有鸟一起缩成白点。
 *
 * 上面两只（明信片旁那两只）改从**右上角空白**起步向左飞：offset 按 1989 开场
 * （eraStart 33720ms → travelPhase≈0.124）标定，两张卡片期间跨过整条上天。
 * 标定值写死在注释与测试里，时间轴大改时这两只的起步位置会整体平移 —— 那时
 * 要一起重标。近处那一只维持原来的下半屏航线，不跟明信片那片抢视觉。
 *
 * 2026-09-27 需求方：删掉「飞得最低、且从右往左飞」的那一只
 * （`SeagullFlight(2, 0.56f, 0.094f, 0.305f, 0.038f, -1f, 0.87f)` —— altitude 0.305
 * 是三条逆向（右→左）航线里最低的一条；屏幕整体最低的是 `near` 那只，但它飞左→右，
 * 不在这次口径内）。非低配因此 4 只减到 3 只；低配那张表本来只有远处与近处两只，
 * 这一只不在里面，不受影响。
 */
internal fun seagullFlights(lowRam: Boolean): List<SeagullFlight> {
    val distant = SeagullFlight(2, 0.015f, 0.065f, 0.130f, 0.030f, -1f, 0.16f)
    val near = SeagullFlight(2, 0.78f, 0.118f, 0.355f, -0.046f, 1f, 0.63f)
    return if (lowRam) listOf(distant, near) else listOf(
        distant,
        SeagullFlight(3, 0.885f, 0.078f, 0.228f, -0.024f, -1f, 0.41f),
        near
    )
}

internal fun seagullSmoothStep(value: Float): Float {
    val t = value.coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

/** 下压占一拍的 38%，回收占 62%；端点速度为零，避免折返时抽动。 */
internal fun seagullWingStroke(cycle: Float): Float {
    val t = cycle - floor(cycle)
    return if (t < 0.38f) {
        -cos(PI.toFloat() * t / 0.38f)
    } else {
        cos(PI.toFloat() * (t - 0.38f) / 0.62f)
    }
}

internal fun updateSeagullPose(
    bird: SeagullFlight,
    phase: Float,
    travelPhase: Float,
    width: Float,
    height: Float
): SeagullPose {
    val pose = bird.pose
    val travel = travelPhase * bird.rounds + bird.offset
    val t = travel - floor(travel)
    val advance = t + 0.018f * sin(t * GULL_TAU)
    val dx = 1.44f * (1f + 0.018f * GULL_TAU * cos(t * GULL_TAU))
    val across = if (bird.direction > 0f) advance else 1f - advance
    pose.x = -0.22f + across * 1.44f

    // 一趟只走一条浅弧；远近跟着行程变，不跟扑翼节拍反复放大缩小。
    val arch = sin(PI.toFloat() * t)
    pose.y = bird.altitude + bird.bend * arch + 0.018f * (t - 0.5f)
    val dy = bird.bend * PI.toFloat() * cos(PI.toFloat() * t) + 0.018f
    pose.headingDegrees = atan2(dy * height, dx * width) * 180f / PI.toFloat() * bird.direction
    pose.bank = (-bird.bend * arch * 8f).coerceIn(-0.35f, 0.35f)
    pose.scale = 0.90f + 0.10f * arch
    pose.alpha = seagullSmoothStep(t / 0.065f) * seagullSmoothStep((1f - t) / 0.065f)

    // 12 秒全局相位分成三个 4 秒呼吸：三次振翅后滑翔，周期接缝落在滑翔段。
    val burst = phase * 3f + bird.beatOffset
    val beat = burst - floor(burst)
    val active = seagullSmoothStep(beat / 0.065f) *
        (1f - seagullSmoothStep((beat - 0.46f) / 0.12f))
    val cycle = beat * (3f / 0.58f)
    val recovery = cycle - floor(cycle)
    pose.effort = active
    pose.shoulder = seagullWingStroke(cycle) * active
    pose.wingTip = seagullWingStroke(cycle - 0.10f) * active
    pose.fold = if (recovery > 0.38f) {
        sin((recovery - 0.38f) / 0.62f * PI.toFloat()) * active
    } else 0f
    return pose
}
