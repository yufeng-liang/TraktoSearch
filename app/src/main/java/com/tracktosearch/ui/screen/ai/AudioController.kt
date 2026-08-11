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
import android.util.Base64
import androidx.core.content.ContextCompat
import com.tracktosearch.data.ai.AiAudio
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

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
            val recorder = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                recorder.release()
                return@withContext null
            }

            val pcm = ByteArrayOutputStream()
            val buffer = ByteArray(bufferSize)
            try {
                recorder.startRecording()
                val deadline = System.nanoTime() + maxDurationMs.coerceAtMost(MAX_DURATION_MS) * 1_000_000
                // 协程取消时提前结束录音，尽早释放麦克风（activate 被新请求取消时）
                while (System.nanoTime() < deadline && isActive) {
                    val count = recorder.read(buffer, 0, buffer.size)
                    if (count > 0) pcm.write(buffer, 0, count)
                }
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

    // 每次 play 递增的代号。MediaPlayer 回调在独立线程派发，旧播放的延迟
    // onCompletion/onError/onPrepared 可能在新播放建立后才到达；代号不匹配则忽略，
    // 避免回调对新建的 player 执行 cleanup/release 后，其挂起的 onPrepared 再 start() 抛 IllegalStateException。
    @Volatile
    private var playEpoch = 0L

    fun play(audio: AiAudio, onFinished: () -> Unit = {}) {
        stop()
        val epoch = ++playEpoch
        val mediaPlayer = MediaPlayer()
        player = mediaPlayer
        try {
            val source = audio.audioDataUrl
            if (!source.isNullOrBlank() && source.startsWith("data:")) {
                val comma = source.indexOf(',')
                require(comma > 0) { "Invalid audio data URL" }
                val bytes = Base64.decode(source.substring(comma + 1), Base64.DEFAULT)
                val file = File.createTempFile("ai_voice_", ".audio", context.cacheDir)
                FileOutputStream(file).use { it.write(bytes) }
                temporaryFile = file
                mediaPlayer.setDataSource(file.absolutePath)
            } else if (!audio.audioUrl.isNullOrBlank()) {
                mediaPlayer.setDataSource(context, Uri.parse(audio.audioUrl))
            } else {
                stop()
                return
            }
            mediaPlayer.setOnCompletionListener {
                if (playEpoch != epoch) return@setOnCompletionListener
                cleanup()
                onFinished()
            }
            mediaPlayer.setOnErrorListener { _, _, _ ->
                if (playEpoch != epoch) return@setOnErrorListener true
                cleanup()
                true
            }
            mediaPlayer.prepareAsync()
            mediaPlayer.setOnPreparedListener { mp ->
                if (playEpoch == epoch) mp.start()
            }
        } catch (_: Exception) {
            if (playEpoch == epoch) cleanup()
        }
    }

    fun stop() {
        cleanup()
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
