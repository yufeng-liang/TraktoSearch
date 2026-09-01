package com.tracktosearch.ui.screen.swiftie.bracelet

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState

/** 低通滤波系数。原始读数抖得厉害，直接用会让高光一直发颤。 */
private const val TILT_SMOOTHING = 0.12f

/**
 * 设备倾斜，各轴 -1f..1f，可直接当光源偏移用。
 *
 * 用**加速度计**（`TYPE_ACCELEROMETER`）而不是陀螺仪：前者不需要任何权限，给的又正是
 * 重力方向 —— 高光该往哪偏本来就由重力决定。陀螺仪给角速度，还得自己积分，会漂。
 *
 * [enabled] 为 false 时连监听都不注册，返回恒定的 [Offset.Zero]（低端机走这条，Spec §11.2）。
 * 息屏 / 切后台（`ON_STOP`）同样注销 —— Compose 的组合不会因为切后台而拆掉，
 * 不看生命周期的话加速度计会在**屏幕关着的时候**继续以 50Hz 回调，纯耗电。
 *
 * 返回 [State] 而不是 `Offset`：调用方在 draw lambda 里读 `.value`，
 * 50Hz 的传感器回调就只失效绘制，不会每秒触发 50 次重组。
 */
@Composable
fun rememberTiltHighlight(enabled: Boolean): State<Offset> {
    val highlight = remember { mutableStateOf(Offset.Zero) }
    val context = LocalContext.current
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val started = lifecycleState.isAtLeast(Lifecycle.State.STARTED)

    DisposableEffect(context, enabled, started) {
        if (!enabled || !started) return@DisposableEffect onDispose { }
        val manager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val sensor = manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (manager == null || sensor == null) return@DisposableEffect onDispose { }

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val nx = (-event.values[0] / SensorManager.GRAVITY_EARTH).coerceIn(-1f, 1f)
                val ny = (event.values[1] / SensorManager.GRAVITY_EARTH).coerceIn(-1f, 1f)
                val previous = highlight.value
                highlight.value = Offset(
                    x = previous.x + (nx - previous.x) * TILT_SMOOTHING,
                    y = previous.y + (ny - previous.y) * TILT_SMOOTHING
                )
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }

        // 显式给主线程 Handler：不传时事件派发线程由 SensorManager 决定，
        // 而 mutableStateOf 的写入必须在主线程
        manager.registerListener(
            listener,
            sensor,
            SensorManager.SENSOR_DELAY_GAME,
            Handler(Looper.getMainLooper())
        )
        onDispose { manager.unregisterListener(listener) }
    }

    return highlight
}
