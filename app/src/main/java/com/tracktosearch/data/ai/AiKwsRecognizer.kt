package com.tracktosearch.data.ai

import android.content.Context
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** 本地语音关键词识别结果。 */
sealed class AiVoiceMatch {
    /** 识别引擎不可用（native 库/模型加载失败）：引导用户改用文字激活。 */
    data object Unavailable : AiVoiceMatch()

    /** 录音里没有命中任何角色关键词。 */
    data object NoMatch : AiVoiceMatch()

    /** 命中角色关键词，值为 AiCharacterCatalog 里的角色 ID。 */
    data class Matched(val characterId: String) : AiVoiceMatch()
}

/**
 * 离线语音识别接口：录音 PCM → 角色 ID。
 *
 * 实现基于 sherpa-onnx 关键词检测（KWS），assets/kws 里打包
 * zipformer-wenetspeech 3.3M int8 模型与角色同音关键词表，
 * 纯本地推理，不依赖 Google 服务与网络，国产 ROM 无差别可用。
 */
interface AiKwsRecognizer {
    suspend fun recognize(samples: FloatArray): AiVoiceMatch
}

/**
 *sherpa-onnx KWS 实现。
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

    override suspend fun recognize(samples: FloatArray): AiVoiceMatch = withContext(Dispatchers.Default) {
        val kws = obtainSpotter() ?: return@withContext AiVoiceMatch.Unavailable
        mutex.withLock {
            // OnlineStream.use 非 inline，内部要提前 return，这里手动 release
            val stream = kws.createStream()
            try {
                stream.acceptWaveform(samples, SAMPLE_RATE)
                // 官方示例收尾填充：给 trailing blanks 留出发射关键词的空间
                stream.acceptWaveform(FloatArray(TAIL_PADDING_SAMPLES), SAMPLE_RATE)
                stream.inputFinished()
                while (kws.isReady(stream)) {
                    kws.decode(stream)
                    val keyword = kws.getResult(stream).keyword
                    if (keyword.isNotBlank()) {
                        kws.reset(stream)
                        return@withContext AiVoiceMatch.Matched(keyword)
                    }
                }
                AiVoiceMatch.NoMatch
            } catch (_: Throwable) {
                AiVoiceMatch.NoMatch
            } finally {
                stream.release()
            }
        }
    }

    companion object {
        private const val SAMPLE_RATE = 16_000
        private const val TAIL_PADDING_SAMPLES = 10_560 // 0.66s，与官方示例一致
    }
}
