package com.tracktosearch.ui.screen.ai

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Base64
import com.tracktosearch.data.ai.AiAudio
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

// 一次性录 3 秒的 AiAudioRecorder 已删除：按住说话改由 data 层的 MicVoiceCapture
// 边录边喂流式 KWS，留着旧 object 等于留下第二条会抢麦克风的录音路径。

private const val ANDROID_ASSET_URL_PREFIX = "file:///android_asset/"

/** 把预存试听伪 URL 还原为 AssetManager 可读取的相对路径。 */
internal fun bundledAssetPath(audioUrl: String?): String? = audioUrl
    ?.takeIf { it.startsWith(ANDROID_ASSET_URL_PREFIX) }
    ?.removePrefix(ANDROID_ASSET_URL_PREFIX)
    ?.takeIf { it.isNotBlank() && !it.startsWith('/') && ".." !in it.split('/') }

/** 使用系统 MediaPlayer 播放短音频，并在结束后清理临时文件。 */
class AiAudioPlayer(private val context: Context) {
    private var player: MediaPlayer? = null
    private var temporaryFile: File? = null
    private var textToSpeech: TextToSpeech? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    // 每次 play 递增的代号。MediaPlayer 回调在独立线程派发，旧播放的延迟
    // onCompletion/onError/onPrepared 可能在新播放建立后才到达；代号不匹配则忽略，
    // 避免回调对新建的 player 执行 cleanup/release 后，其挂起的 onPrepared 再 start() 抛 IllegalStateException。
    @Volatile
    private var playEpoch = 0L

    fun play(
        audio: AiAudio,
        onStarted: () -> Unit = {},
        onFinished: () -> Unit = {}
    ) {
        stop()
        val epoch = ++playEpoch
        val mediaPlayer = MediaPlayer()
        player = mediaPlayer
        val source = audio.audioDataUrl
        try {
            if (!source.isNullOrBlank() && source.startsWith("data:")) {
                val comma = source.indexOf(',')
                require(comma > 0) { "Invalid audio data URL" }
                // Base64 解码 + 临时文件写盘是耗时操作（长音频可达秒级），放后台线程执行，
                // 完成后 post 回主线程继续 MediaPlayer 流程，避免主线程卡顿/ANR。
                Thread {
                    try {
                        val bytes = Base64.decode(source.substring(comma + 1), Base64.DEFAULT)
                        val file = File.createTempFile("ai_voice_", ".audio", context.cacheDir)
                        FileOutputStream(file).use { it.write(bytes) }
                        mainHandler.post {
                            // 播放期间被 stop()/新 play() 取代：epoch 已变，丢弃本次结果
                            if (playEpoch != epoch) {
                                file.delete()
                                return@post
                            }
                            temporaryFile = file
                            prepareAndStart(mediaPlayer, epoch, onStarted, onFinished)
                        }
                    } catch (_: Exception) {
                        mainHandler.post {
                            if (playEpoch == epoch) {
                                cleanup()
                                onFinished()
                            }
                        }
                    }
                }.start()
            } else if (!audio.audioUrl.isNullOrBlank()) {
                val assetPath = bundledAssetPath(audio.audioUrl)
                if (assetPath != null) {
                    // file:///android_asset 只是 WebView 风格的伪路径，MediaPlayer 无法通过
                    // setDataSource(Context, Uri) 读取。预存试听必须交给 AssetManager 打开的
                    // 文件描述符，并携带 APK 内的偏移与长度。
                    context.assets.openFd(assetPath).use { descriptor ->
                        mediaPlayer.setDataSource(
                            descriptor.fileDescriptor,
                            descriptor.startOffset,
                            descriptor.length
                        )
                    }
                } else {
                    mediaPlayer.setDataSource(context, Uri.parse(audio.audioUrl))
                }
                prepareAndStart(mediaPlayer, epoch, onStarted, onFinished)
            } else {
                stop()
            }
        } catch (_: Exception) {
            if (playEpoch == epoch) {
                cleanup()
                onFinished()
            }
        }
    }

    /** 注册 MediaPlayer 回调并开始异步准备（dataSource 已由调用方设置好）。 */
    private fun prepareAndStart(
        mediaPlayer: MediaPlayer,
        epoch: Long,
        onStarted: () -> Unit,
        onFinished: () -> Unit
    ) {
        mediaPlayer.setOnCompletionListener {
            if (playEpoch != epoch) return@setOnCompletionListener
            cleanup()
            onFinished()
        }
        mediaPlayer.setOnErrorListener { _, _, _ ->
            if (playEpoch != epoch) return@setOnErrorListener true
            cleanup()
            onFinished()
            true
        }
        mediaPlayer.setOnPreparedListener { mp ->
            if (playEpoch == epoch) {
                mp.start()
                onStarted()
            }
        }
        mediaPlayer.prepareAsync()
    }

    /** 访客试听没有网关身份时使用系统中文语音，只用于浏览阶段的即时反馈。 */
    fun playText(
        text: String,
        onStarted: () -> Unit = {},
        onFinished: () -> Unit = {}
    ) {
        if (text.isBlank()) return
        stop()
        val epoch = ++playEpoch
        val engine = TextToSpeech(context) { status ->
            if (status != TextToSpeech.SUCCESS || playEpoch != epoch) {
                if (playEpoch == epoch) onFinished()
                return@TextToSpeech
            }
            val tts = textToSpeech ?: return@TextToSpeech
            tts.language = Locale.SIMPLIFIED_CHINESE
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    if (playEpoch == epoch) onStarted()
                }

                override fun onDone(utteranceId: String?) {
                    if (playEpoch == epoch) onFinished()
                }

                // 基类 onError(String) 已在 API 21 弃用，但框架仍按这条回调派发播放错误；
                // 覆盖它才能保证失败时一定回到 onFinished，这里显式抑制该弃用诊断。
                @Suppress("OVERRIDE_DEPRECATION")
                override fun onError(utteranceId: String?) {
                    if (playEpoch == epoch) onFinished()
                }
            })
            if (tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "ai_preview_$epoch") == TextToSpeech.ERROR) {
                if (playEpoch == epoch) onFinished()
            }
        }
        textToSpeech = engine
    }

    fun stop() {
        playEpoch++
        cleanup()
        textToSpeech?.let { tts ->
            runCatching { tts.stop() }
            runCatching { tts.shutdown() }
        }
        textToSpeech = null
    }

    private fun cleanup() {
        player?.let { current ->
            runCatching {
                if (current.isPlaying) current.stop()
            }
            current.reset()
            current.release()
        }
        player = null
        temporaryFile?.delete()
        temporaryFile = null
    }
}
