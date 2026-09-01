package com.tracktosearch.ui.screen.swiftie

import android.content.Context
import android.content.res.AssetFileDescriptor
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.tracktosearch.R

/**
 * 静音档不播（Spec §5.1）。铃声静音或媒体音量为 0 都算。
 *
 * `SwiftieAudioHint` 用同一个判断决定要不要劝用户开声音 —— 两处必须一致，
 * 否则会出现「提示说声音是开的、实际却不播」。
 */
internal fun AudioManager.isSwiftieSilenced(): Boolean =
    ringerMode == AudioManager.RINGER_MODE_SILENT ||
        getStreamVolume(AudioManager.STREAM_MUSIC) == 0

/**
 * 焦点变化的三种结局。**永久丢失必须和临时丢失分开** ——
 * 系统对 `AUDIOFOCUS_LOSS` 不保证后续补发 `AUDIOFOCUS_GAIN`，
 * 当成临时丢失处理会把整条序列永久钉在暂停态（谁也解不开）。
 */
internal enum class SwiftieAudioFocus { GAINED, LOST_TRANSIENT, LOST_PERMANENT }

/** player 的持有者。`DisposableEffect` 里创建、`LaunchedEffect` 里暂停，需要一个共享的落点。 */
private class SwiftieMusicHolder {
    var player: MediaPlayer? = null

    /** 已播到结尾。`MediaPlayer` 在 PlaybackCompleted 态调 `start()` 是从头重播。 */
    var completed: Boolean = false

    /** prepare 完成前不能碰 `start` / `pause` / `seekTo`，那几个在 Preparing 态会抛。 */
    var prepared: Boolean = false

    /** 异步 prepare 期间要保住的 raw 资源描述符。 */
    var descriptor: AssetFileDescriptor? = null

    fun closeDescriptor() {
        runCatching { descriptor?.close() }
        descriptor = null
    }
}

/**
 * 彩蛋配乐。总长与序列对齐，并在时钟被拨动时重新对位。
 *
 * ## 为什么要跟着生命周期走
 *
 * Compose 的帧时钟在 `ON_STOP` 时被暂停，`withFrameMillis` 会挂起 ——
 * 也就是说息屏或按 Home 之后**画面停了**。`MediaPlayer` 不受这个影响，
 * 不管它就会在后台把整首放完，回到前台时音画永久错开，而
 * [SwiftieTimeline] 整套账本的前提正是「1:58 那句 Lover 压在绽放上」。
 * 所以这里在 `ON_STOP` 就 stop + release + 放弃焦点（Spec §5.1 明文要求），
 * 回到前台重建并 seek 到时钟当前位置。
 *
 * @param enabled 整段是否该有声音。「减少动效」路径与永久丢失焦点后传 false
 * @param paused 与 [SwiftieSequenceClock.paused] 联动
 * @param positionMs 时钟当前位置。重建播放器与响应 seek 时用它对位
 * @param seekEpoch [SwiftieSequenceClock.seekEpoch]，变了就说明时钟被拨过、需要重新对位
 * @param onFocusChange 焦点变化。三种结局必须分开处理，见 [SwiftieAudioFocus]
 */
@Composable
internal fun SwiftieMusic(
    enabled: Boolean,
    paused: Boolean,
    positionMs: () -> Long,
    seekEpoch: Int,
    onFocusChange: (SwiftieAudioFocus) -> Unit
) {
    val context = LocalContext.current
    val latestFocusChange by rememberUpdatedState(onFocusChange)
    val latestPosition by rememberUpdatedState(positionMs)
    val latestPaused by rememberUpdatedState(paused)
    val holder = remember { SwiftieMusicHolder() }
    val audioManager = remember(context) {
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    }
    // 息屏 / 切后台就整块拆掉，回来再建 —— 见上面的类注释
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val started = lifecycleState.isAtLeast(Lifecycle.State.STARTED)

    DisposableEffect(enabled, started, audioManager) {
        if (!enabled || !started || audioManager == null || audioManager.isSwiftieSilenced()) {
            return@DisposableEffect onDispose { }
        }
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        // GAIN_TRANSIENT 而不是 MAY_DUCK：这两分钟配乐是主音频，
        // MAY_DUCK 只会把对方压小声继续放，变成两首歌同时响
        val request = AudioFocusRequest
            .Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener { change ->
                when (change) {
                    AudioManager.AUDIOFOCUS_GAIN -> latestFocusChange(SwiftieAudioFocus.GAINED)
                    // 对方允许我们压低音量继续放，不必停整条序列
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> Unit
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ->
                        latestFocusChange(SwiftieAudioFocus.LOST_TRANSIENT)
                    else -> latestFocusChange(SwiftieAudioFocus.LOST_PERMANENT)
                }
            }
            .build()
        val granted = audioManager.requestAudioFocus(request) ==
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        // 抢不到焦点就纯静默播动画，不硬盖别人正在放的东西
        holder.completed = false
        holder.prepared = false
        holder.player = if (!granted) null else {
            createSwiftiePlayer(context, attributes, holder) { player ->
                holder.prepared = true
                runCatching {
                    // 重建时从时钟当前位置接上（息屏回来那一下就靠这个对位）
                    val from = latestPosition()
                    if (from > 0L) player.seekTo(from.toInt())
                    if (!latestPaused) player.start()
                }
            }
        }

        onDispose {
            holder.player?.let { player ->
                runCatching { if (holder.prepared) player.stop() }
                player.release()
            }
            holder.player = null
            holder.prepared = false
            holder.completed = false
            holder.closeDescriptor()
            audioManager.abandonAudioFocusRequest(request)
        }
    }

    LaunchedEffect(paused, enabled, started) {
        val player = holder.player?.takeIf { holder.prepared } ?: return@LaunchedEffect
        // 播完之后再 start() 是从头重播，不如就让它静音收尾
        runCatching { if (paused) player.pause() else if (!holder.completed) player.start() }
    }

    // 时钟被拨过（「跳过」/ 拖播放头）就把播放头对回去，
    // 否则配乐钉死的那两个点全部失效
    LaunchedEffect(seekEpoch) {
        if (seekEpoch == 0) return@LaunchedEffect
        val player = holder.player?.takeIf { holder.prepared } ?: return@LaunchedEffect
        runCatching {
            player.seekTo(latestPosition().coerceIn(0L, Int.MAX_VALUE.toLong()).toInt())
            holder.completed = false
        }
    }
}

/**
 * 建播放器并在准备好后交给 [onPrepared] 决定要不要起播。
 *
 * 用 `prepareAsync()` 而不是 `MediaPlayer.create()`：后者在调用线程同步
 * `setDataSource` + `prepare`，而这里的调用点正是答对那一帧 —— 要和 AGSL
 * 着色器编译抢同一帧预算。项目既有的 `AudioController` 也是这个路子。
 *
 * `setAudioAttributes` 必须在 prepare **之前**：`create()` 返回的实例已经
 * prepare 过，事后再设属性不生效，播放器会跑在默认的 UNKNOWN usage 上。
 *
 * 资源描述符要活到 prepare 完成为止（异步 prepare 才真正去读它），
 * 所以由 [SwiftieMusicHolder] 持有、在 prepared 回调或 dispose 时关闭。
 */
private fun createSwiftiePlayer(
    context: Context,
    attributes: AudioAttributes,
    holder: SwiftieMusicHolder,
    onPrepared: (MediaPlayer) -> Unit
): MediaPlayer? = runCatching {
    val fd = context.resources.openRawResourceFd(R.raw.swiftie_theme)
        ?: error("swiftie_theme.ogg 拿不到 fd（被压缩了？）")
    holder.descriptor = fd
    MediaPlayer().apply {
        setAudioAttributes(attributes)
        isLooping = false
        setDataSource(fd.fileDescriptor, fd.startOffset, fd.declaredLength)
        setOnPreparedListener { player ->
            holder.closeDescriptor()
            onPrepared(player)
        }
        setOnCompletionListener { holder.completed = true }
        setOnErrorListener { _, _, _ ->
            holder.closeDescriptor()
            true
        }
        prepareAsync()
    }
}.getOrElse {
    holder.closeDescriptor()
    null
}
