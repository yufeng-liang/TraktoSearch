package com.tracktosearch.ui.screen.ai

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Base64
import androidx.core.content.ContextCompat
import com.tracktosearch.data.ai.AiAudio
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

/** 一次性采集短语音；录音结束后立即释放麦克风，不做常驻监听。 */
object AiAudioRecorder {
    private const val SAMPLE_RATE = 16_000
    private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
    private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    private const val MAX_DURATION_MS = 3_000L

    suspend fun recordOnce(context: Context, maxDurationMs: Long = MAX_DURATION_MS): String? =
        withContext(Dispatchers.IO) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                return@withContext null
            }
            val minimumBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
            if (minimumBuffer <= 0) return@withContext null
            val bufferSize = (minimumBuffer * 2).coerceAtLeast(2_048)
            // 部分设备的 AudioRecord 构造在参数不被支持或麦克风资源异常时抛 IllegalArgumentException，
            // 未捕获会直达默认异常处理器导致崩溃，这里降级为“无音频”（调用方有 AUDIO_UNAVAILABLE 兜底）。
            val recorder = runCatching {
                AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT,
                    bufferSize
                )
            }.getOrNull() ?: return@withContext null
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                recorder.release()
                return@withContext null
            }

            val pcm = ByteArrayOutputStream()
            val buffer = ByteArray(bufferSize)
            try {
                // startRecording 在麦克风被占用时抛 IllegalStateException；
                // read 在录音器被外部释放后同样可能抛异常。
                recorder.startRecording()
                val deadline = System.nanoTime() + maxDurationMs.coerceAtMost(MAX_DURATION_MS) * 1_000_000
                // 协程取消时提前结束录音，尽早释放麦克风（activate 被新请求取消时）
                while (System.nanoTime() < deadline && isActive) {
                    val count = recorder.read(buffer, 0, buffer.size)
                    if (count > 0) pcm.write(buffer, 0, count)
                }
            } catch (e: CancellationException) {
                // CancellationException 继承 IllegalStateException，必须优先重抛，否则会吞掉协程取消
                throw e
            } catch (_: Exception) {
                return@withContext null
            } finally {
                runCatching { recorder.stop() }
                recorder.release()
            }

            val pcmBytes = pcm.toByteArray()
            if (pcmBytes.isEmpty()) return@withContext null
            val wav = ByteArrayOutputStream(pcmBytes.size + 44)
            writeWavHeader(wav, pcmBytes.size)
            wav.write(pcmBytes)
            "data:audio/wav;base64," + Base64.encodeToString(wav.toByteArray(), Base64.NO_WRAP)
        }

    private fun writeWavHeader(output: ByteArrayOutputStream, dataLength: Int) {
        fun writeAscii(value: String) = output.write(value.toByteArray(Charsets.US_ASCII))
        fun writeLittleEndian(value: Int) {
            output.write(value and 0xff)
            output.write(value shr 8 and 0xff)
            output.write(value shr 16 and 0xff)
            output.write(value shr 24 and 0xff)
        }
        fun writeLittleEndianShort(value: Int) {
            output.write(value and 0xff)
            output.write(value shr 8 and 0xff)
        }

        writeAscii("RIFF")
        writeLittleEndian(36 + dataLength)
        writeAscii("WAVEfmt ")
        writeLittleEndian(16)
        writeLittleEndianShort(1)
        writeLittleEndianShort(1)
        writeLittleEndian(SAMPLE_RATE)
        writeLittleEndian(SAMPLE_RATE * 2)
        writeLittleEndianShort(2)
        writeLittleEndianShort(16)
        writeAscii("data")
        writeLittleEndian(dataLength)
    }
}

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
                mediaPlayer.setDataSource(context, Uri.parse(audio.audioUrl))
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
