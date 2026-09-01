package com.tracktosearch.data.ai

import android.content.Context
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.sync.Mutex
import javax.inject.Inject
import javax.inject.Singleton

/** 一次按住对应一个流式解码会话。close() 之前只能被单线程使用。 */
interface AiKwsSession : AutoCloseable {
    /** 喂一块 PCM，返回本块解码出的角色 ID，没命中返回 null。native 同步调用，须在非主线程。 */
    fun accept(samples: FloatArray): String?

    override fun close()
}

/**
 * 离线语音识别接口：边录边喂 PCM → 角色 ID。
 *
 * 实现基于 sherpa-onnx 关键词检测（KWS），assets/kws 里打包
 * zipformer-wenetspeech 3.3M int8 模型与角色同音关键词表，
 * 纯本地推理，不依赖 Google 服务与网络，国产 ROM 无差别可用。
 */
interface AiKwsRecognizer {
    /** 打开流式会话，引擎不可用返回 null。内部持有解码锁，close() 释放。 */
    suspend fun openSession(): AiKwsSession?
}

/**
 * sherpa-onnx KWS 实现。
 *
 * keywords.txt 的 @ 标签直接写角色 ID，识别结果无需二次映射；
 * 同音命中由关键词表按拼音声调变体穷举保证（如"吉伊"同时收录"记忆/机翼"声调）。
 */
@Singleton
class SherpaOnnxKwsRecognizer @Inject constructor(
    @ApplicationContext private val context: Context
) : AiKwsRecognizer {
    private val mutex = Mutex()

    @Volatile
    private var spotter: KeywordSpotter? = null

    @Volatile
    private var initFailed = false

    private fun obtainSpotter(): KeywordSpotter? {
        spotter?.let { return it }
        if (initFailed) return null
        synchronized(this) {
            spotter?.let { return it }
            return try {
                val assets = context.assets
                KeywordSpotter(
                    assetManager = assets,
                    config = KeywordSpotterConfig(
                        featConfig = FeatureConfig(),
                        modelConfig = OnlineModelConfig(
                            transducer = OnlineTransducerModelConfig(
                                encoder = "kws/encoder-int8.onnx",
                                decoder = "kws/decoder-int8.onnx",
                                joiner = "kws/joiner-int8.onnx"
                            ),
                            tokens = "kws/tokens.txt",
                            numThreads = 2,
                            provider = "cpu",
                            modelType = "zipformer"
                        ),
                        keywordsFile = "kws/keywords.txt",
                        // 与官方示例一致：检测到关键词后需连续 2 个 blank 才确认
                        numTrailingBlanks = 2,
                        keywordsScore = 1.5f,
                        keywordsThreshold = 0.25f
                    )
                )
            } catch (_: Throwable) {
                // 模型缺失或 native 库加载失败：本会话内不再重试，走文字激活
                initFailed = true
                null
            }.also { spotter = it }
        }
    }

    override suspend fun openSession(): AiKwsSession? {
        val kws = obtainSpotter() ?: return null
        // KeywordSpotter 是全局单例的 native 对象，两段解码并行会互相污染内部状态，
        // 所以整段按住期间独占这把锁。刻意用 lock()/unlock() 而不是 withLock：
        // 解锁点在 close()，而 close() 会在协程取消的 finally 里被调用，那里不能挂起。
        mutex.lock()
        val stream = runCatching { kws.createStream() }.getOrNull()
        if (stream == null) {
            // createStream 在 native 侧失败时锁必须当场还回去，
            // 否则之后每一次 openSession() 都会永久挂起
            mutex.unlock()
            return null
        }
        return StreamingSession(kws, stream)
    }

    /** 一段按住的流式解码会话：持锁独占共享的 KeywordSpotter 直到 close()。 */
    private inner class StreamingSession(
        private val kws: KeywordSpotter,
        private val stream: OnlineStream
    ) : AiKwsSession {
        @Volatile
        private var closed = false

        override fun accept(samples: FloatArray): String? {
            // close() 之后 stream 已经 release，再喂数据等于操作已释放的 native 指针
            if (closed) return null
            return try {
                stream.acceptWaveform(samples, SAMPLE_RATE)
                while (kws.isReady(stream)) {
                    kws.decode(stream)
                }
                val keyword = kws.getResult(stream).keyword
                if (keyword.isBlank()) return null
                // 命中后立刻 reset，让同一段按住里后续的块从干净状态继续解码，
                // 否则同一个关键词会在之后每一块里被反复报出来
                kws.reset(stream)
                keyword
            } catch (_: Throwable) {
                // native 抛异常（内存不足/模型状态异常）时降级为"本块没命中"，
                // 不把 native 错误抛给采集协程导致整段按住崩掉
                null
            }
        }

        override fun close() {
            // 必须幂等：采集协程的 finally 与取消路径都可能调到这里。
            // 漏掉或重复 unlock 都会让之后每一次按住卡死在 openSession()，
            // 所以先原子地抢下"关闭"标记，再释放资源。
            synchronized(this) {
                if (closed) return
                closed = true
            }
            runCatching { stream.release() }
            runCatching { mutex.unlock() }
        }
    }

    companion object {
        private const val SAMPLE_RATE = 16_000
    }
}
