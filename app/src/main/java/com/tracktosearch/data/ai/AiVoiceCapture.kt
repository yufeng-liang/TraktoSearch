package com.tracktosearch.data.ai

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

/** 一次按住采集期间上报的事实，不含任何阈值判定。 */
sealed interface AiVoiceCaptureEvent {
    /**
     * 一块 PCM 的原始线性 RMS（0..1），约每 100ms 一帧。
     *
     * 这里刻意**不做**归一化与平滑：dBFS 映射与起快落慢的平滑属于展示策略，
     * 由 UI 逻辑层的 normalizeVoiceLevel / smoothVoiceLevel 负责，
     * data 层只报测量值，避免把可单测的纯逻辑埋进麦克风代码里。
     */
    data class Level(val level: Float) : AiVoiceCaptureEvent

    /** 流式命中关键词，characterId 取自 kws/keywords.txt 的 @ 标签。 */
    data class Matched(val characterId: String) : AiVoiceCaptureEvent

    /** 麦克风拿不到（无权限 / 被占用 / 构造失败）或 KWS 引擎加载失败。 */
    data object Unavailable : AiVoiceCaptureEvent
}

interface AiVoiceCapture {
    /** 冷 Flow：开始 collect 即开录，取消协程即停录并释放麦克风与解码会话。 */
    fun capture(): Flow<AiVoiceCaptureEvent>
}

/**
 * 麦克风采集实现：占麦克风、算电平、把 PCM 分块喂流式 KWS。
 *
 * 只报事实（电平 / 命中 / 不可用），收尾时机与阈值判定都在 ViewModel。
 * 命中后不自行停止，继续发 Level，让用户看到自己还在被拾音。
 */
@Singleton
class MicVoiceCapture @Inject constructor(
    @ApplicationContext private val context: Context,
    private val recognizer: AiKwsRecognizer
) : AiVoiceCapture {

    override fun capture(): Flow<AiVoiceCaptureEvent> = channelFlow {
        // UI 层按下时也查一次权限（为了"先弹窗再开录"的体验），这里是最后一道闸：
        // 缺权限的 AudioRecord 在部分 ROM 上会静默返回全 0，不能让用户对着假电平白喊
        if (!hasRecordPermission()) {
            send(AiVoiceCaptureEvent.Unavailable)
            return@channelFlow
        }
        // 先开解码会话再占麦克风：openSession() 要等上一段按住的解码锁，是本函数唯一会挂起、
        // 也就是唯一会被取消的地方。反过来先构造 AudioRecord 的话，用户刚按下就松手时
        // 取消异常在这个挂起点抛出，麦克风还没进下面的 try/finally 就漏掉了。
        val session = recognizer.openSession()
        if (session == null) {
            // 引擎不可用（模型缺失 / native 库加载失败）：引导用户走文字激活
            send(AiVoiceCaptureEvent.Unavailable)
            return@channelFlow
        }
        val recorder = createRecorder()
        if (recorder == null) {
            // 解码锁已经在手上，拿不到麦克风就得当场还回去，否则之后每一次按住都永久挂起
            session.close()
            send(AiVoiceCaptureEvent.Unavailable)
            return@channelFlow
        }

        var deviceFailed = false
        try {
            // CONFLATED：解码跟不上时新块直接顶掉队列里没消费的旧块。
            // 宁可漏一段音频，也不能让电平帧排在 native 解码后面——卡住的竖条比漏识别更难解释。
            val pendingChunk = Channel<FloatArray>(Channel.CONFLATED)
            // coroutineScope 而不是裸 launch：它保证退出时解码协程一定已经结束，
            // 这样 finally 里 release stream 时不会有人还在 native 里用它。
            coroutineScope {
                launch(Dispatchers.Default) {
                    for (chunk in pendingChunk) {
                        val characterId = session.accept(chunk) ?: continue
                        send(AiVoiceCaptureEvent.Matched(characterId))
                    }
                }
                deviceFailed = withContext(Dispatchers.IO) { readChunks(recorder, pendingChunk) }
                pendingChunk.close()
            }
        } catch (e: CancellationException) {
            // CancellationException 继承 IllegalStateException，必须在宽泛 catch 之前重抛，
            // 否则"上滑取消 / 到点收尾"会被当成录音失败报 Unavailable
            throw e
        } catch (_: Exception) {
            // startRecording 在麦克风被抢占时抛 IllegalStateException，read 在录音器被
            // 外部释放后同样可能抛异常：都按设备不可用处理，而不是让崩溃冒到 UI
            deviceFailed = true
        } finally {
            // 取消路径也必须走到：麦克风不还，下一次按住直接拿不到设备；
            // 解码锁不还，之后每一次 openSession() 都会永久挂起
            runCatching { recorder.stop() }
            recorder.release()
            session.close()
        }
        if (deviceFailed) send(AiVoiceCaptureEvent.Unavailable)
    }

    /**
     * 阻塞读麦克风直到协程取消或设备失效：每凑满一块就发一帧 Level，并把块投给解码协程。
     *
     * @return true 表示录音中途失效（需要上报 Unavailable），正常被取消返回 false。
     */
    private suspend fun ProducerScope<AiVoiceCaptureEvent>.readChunks(
        recorder: AudioRecord,
        pendingChunk: SendChannel<FloatArray>
    ): Boolean {
        recorder.startRecording()
        val bytes = ByteArray(CHUNK_BYTES)
        while (currentCoroutineContext().isActive) {
            var filled = 0
            // read 可能只填一部分（内部环形缓冲比一块小），凑满再算电平，
            // 否则块长忽长忽短会让 RMS 抖动
            while (filled < CHUNK_BYTES) {
                val count = recorder.read(bytes, filled, CHUNK_BYTES - filled)
                if (count <= 0) {
                    // 负值是 AudioRecord.ERROR_*（设备被抢占 / 对象已死）算失效；
                    // 0 表示录音已经停了，正常收尾，不报错
                    return count < 0
                }
                filled += count
            }
            // 16bit 小端 PCM → FloatArray [-1, 1]，每块新建数组，避免和解码协程共享可变缓冲
            val samples = FloatArray(CHUNK_SAMPLES) { index ->
                val low = bytes[index * 2].toInt() and 0xff
                val high = bytes[index * 2 + 1].toInt()
                (((high shl 8) or low).toShort()) / 32768.0f
            }
            // Level 走 channelFlow 自带缓冲，只受下游 collect 速度影响，不排在解码后面
            send(AiVoiceCaptureEvent.Level(rootMeanSquare(samples)))
            // CONFLATED 通道的 trySend 不挂起也不失败：满了就顶掉上一块
            pendingChunk.trySend(samples)
        }
        return false
    }

    /** 构造并校验 AudioRecord，任何一步失败返回 null（调用方上报 Unavailable）。 */
    private fun createRecorder(): AudioRecord? {
        val minimumBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        if (minimumBuffer <= 0) return null
        val bufferSize = (minimumBuffer * 2).coerceAtLeast(MIN_BUFFER_BYTES)
        // 部分设备在参数不被支持或麦克风资源异常时构造直接抛 IllegalArgumentException，
        // 未捕获会直达默认异常处理器导致崩溃，这里降级为"拿不到麦克风"
        val recorder = runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )
        }.getOrNull() ?: return null
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            return null
        }
        return recorder
    }

    private fun hasRecordPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /** 一块 PCM 的均方根，线性 0..1；dBFS 归一化与平滑交给 UI 逻辑层。 */
    private fun rootMeanSquare(samples: FloatArray): Float {
        if (samples.isEmpty()) return 0f
        var squareSum = 0.0
        for (sample in samples) squareSum += sample.toDouble() * sample
        return sqrt(squareSum / samples.size).toFloat()
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

        /** 约 100ms 一块：16kHz × 0.1s = 1600 采样点，16bit 单声道即 3200 字节。 */
        const val CHUNK_SAMPLES = 1_600
        const val CHUNK_BYTES = CHUNK_SAMPLES * 2
        const val MIN_BUFFER_BYTES = 2_048
    }
}
