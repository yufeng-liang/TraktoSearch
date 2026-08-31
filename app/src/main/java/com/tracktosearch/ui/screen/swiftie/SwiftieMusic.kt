package com.tracktosearch.ui.screen.swiftie

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import com.tracktosearch.R

/** 静音档不播（Spec §5.1）。铃声静音或媒体音量为 0 都算。 */
private fun AudioManager.isSilenced(): Boolean =
    ringerMode == AudioManager.RINGER_MODE_SILENT ||
        getStreamVolume(AudioManager.STREAM_MUSIC) == 0

/** player 的持有者。`DisposableEffect` 里创建、`LaunchedEffect` 里暂停，需要一个共享的落点。 */
private class SwiftieMusicHolder {
    var player: MediaPlayer? = null
}

/**
 * 彩蛋配乐。只对齐总长，不与动画逐帧同步（Spec §5 约束 4）。
 *
 * @param enabled 整段是否该有声音。「减少动效」路径传 false
 * @param paused 与 [SwiftieSequenceClock.paused] 联动
 * @param onFocusChange 焦点变化。false 表示被抢走，调用方应把整条序列一起暂停；
 *   true 表示重新拿回来，应当继续（Spec §5.1）。**必须成对处理** ——
 *   只处理丢失会让一条系统提示音永久冻住整段 120s 序列
 */
@Composable
fun SwiftieMusic(
    enabled: Boolean,
    paused: Boolean,
    onFocusChange: (hasFocus: Boolean) -> Unit
) {
    val context = LocalContext.current
    val latestFocusChange by rememberUpdatedState(onFocusChange)
    val holder = remember { SwiftieMusicHolder() }
    val audioManager = remember(context) {
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    }

    DisposableEffect(enabled, audioManager) {
        if (!enabled || audioManager == null || audioManager.isSilenced()) {
            return@DisposableEffect onDispose { }
        }
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val request = AudioFocusRequest
            .Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener { change ->
                // MAY_DUCK 表示对方允许我们压低音量继续放，不必停整条序列
                when (change) {
                    AudioManager.AUDIOFOCUS_GAIN -> latestFocusChange(true)
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> Unit
                    else -> latestFocusChange(false)
                }
            }
            .build()
        val granted = audioManager.requestAudioFocus(request) ==
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        // 抢不到焦点就纯静默播动画，不硬盖别人正在放的东西
        holder.player = if (!granted) null else runCatching {
            MediaPlayer.create(context, R.raw.swiftie_theme)?.apply {
                setAudioAttributes(attributes)
                isLooping = false
                start()
            }
        }.getOrNull()

        onDispose {
            holder.player?.let { player ->
                runCatching { player.stop() }
                player.release()
            }
            holder.player = null
            audioManager.abandonAudioFocusRequest(request)
        }
    }

    LaunchedEffect(paused, enabled) {
        val player = holder.player ?: return@LaunchedEffect
        runCatching { if (paused) player.pause() else player.start() }
    }
}
