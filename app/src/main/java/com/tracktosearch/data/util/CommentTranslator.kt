package com.tracktosearch.data.util

import android.util.Log
import com.tracktosearch.data.local.LanguageStorage
import com.tracktosearch.data.remote.translate.TranslateApiService
import com.tracktosearch.data.remote.translate.TranslateRequest
import com.tracktosearch.data.remote.trakt.dto.TraktComment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CommentTranslator @Inject constructor(
    private val translateApi: TranslateApiService,
    private val languageStorage: LanguageStorage,
) {

    // 翻译结果缓存（相同评论的翻译不会变，LRU 限制 200 条防内存增长）
    private val translationCache = android.util.LruCache<Int, String>(200)

    /** 翻译提示词：让大模型知道这是影视评论，保留人名/专有名词 */
    private val TRANSLATION_CONTEXT = "这是一条外文影视评论，请翻译为中文。保留电影/电视剧名称、演员名、导演名等专有名词不翻译。"

    /** 翻译单条评论（按需调用） */
    suspend fun translateSingleComment(comment: TraktComment): TraktComment {
        val targetLang = getTargetLangCode()
        if (targetLang == "en") return comment

        translationCache[comment.id]?.let { cached ->
            return comment.copy(comment = cached)
        }

        return withContext(Dispatchers.IO) {
        translateOneComment(comment, targetLang)
    }
}

    /**
     * 翻译评论列表为目标语言（设备语言）
     * 优先使用百度大模型翻译 API，降级到通用文本翻译
     */
    suspend fun translateComments(comments: List<TraktComment>): List<TraktComment> {
        if (comments.isEmpty()) return emptyList()

        val targetLang = getTargetLangCode()
        if (targetLang == "en") return comments

        // 先分离已缓存和未缓存的评论，避免重复翻译
        val results = arrayOfNulls<TraktComment>(comments.size)
        val pending = comments.mapIndexedNotNull { index, comment ->
            val cached = translationCache[comment.id]
            if (cached != null) {
                results[index] = comment.copy(comment = cached)
                null
            } else index to comment
        }
        if (pending.isEmpty()) return results.filterNotNull()

        // 并发翻译未缓存评论：10 条评论从串行 ~100s 降到并发 ~10s
        return withContext(Dispatchers.IO) {
            coroutineScope {
                val deferredList = pending.map { (originIndex, comment) ->
                    async {
                        originIndex to translateOneComment(comment, targetLang)
                    }
                }
                deferredList.awaitAll().forEach { (originIndex, translated) ->
                    results[originIndex] = translated
                }
            }
            results.filterNotNull()
        }
    }

    /**
     * 流式翻译评论列表，每条翻译完成立即 emit（index, 译文）。
     *
     * 缓存命中的评论立即发送；未命中的并发翻译，完成一条就推送一条，
     * 让 UI 可以渐进展示翻译结果，无需等待全部完成。
     *
     * 取消语义：collect 端取消会自动传播到 channelFlow 内部所有子协程。
     *
     * @return Flow<Pair<原索引, 译文>>
     */
    fun translateCommentsFlow(comments: List<TraktComment>): Flow<Pair<Int, TraktComment>> = channelFlow {
        if (comments.isEmpty()) return@channelFlow
        val targetLang = getTargetLangCode()
        if (targetLang == "en") {
            // 设备为英文：直接原样 emit
            comments.forEachIndexed { index, comment ->
                send(index to comment)
            }
            return@channelFlow
        }

        // 缓存命中的立即 emit；未命中的进入 pending 列表
        val pending = comments.mapIndexedNotNull { index, comment ->
            val cached = translationCache[comment.id]
            if (cached != null) {
                send(index to comment.copy(comment = cached))
                null
            } else {
                index to comment
            }
        }
        if (pending.isEmpty()) return@channelFlow

        // 并发翻译未缓存评论，每条完成立即 emit
        coroutineScope {
            pending.forEach { (originIndex, comment) ->
                launch {
                    val translated = translateOneComment(comment, targetLang)
                    send(originIndex to translated)
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    /** 翻译单条评论（内部复用，供并发调用） */
    private suspend fun translateOneComment(comment: TraktComment, targetLang: String): TraktComment {
        return try {
            withTimeoutOrNull(5000) {
                var result = translateWithBaiduAI(comment.comment, targetLang)
                val isLongText = comment.comment.length > 6000
                if (result.isNullOrEmpty() && !isLongText) result = translateWithBaidu(comment.comment, targetLang)

                if (!result.isNullOrEmpty() && result != comment.comment) {
                    translationCache.put(comment.id, result)
                    comment.copy(comment = result)
                } else comment
            } ?: comment
        } catch (e: Exception) {
            Log.e("CommentTranslator", "Translation error: ${e.message}", e)
            comment
        }

    }

    /**
     * 百度大模型文本翻译 API（AI）—— 走网关代理。
     * 密钥由 auth-worker 注入，客户端不持有 BAIDU_API_KEY。
     */
    private suspend fun translateWithBaiduAI(text: String, targetLang: String): String? {
        return try {
            val response = translateApi.translateAi(
                TranslateRequest(q = text, from = "en", to = targetLang, reference = TRANSLATION_CONTEXT)
            )
            val json = response.string()
            extractTranslation(json)
        } catch (e: Exception) {
            Log.w("CommentTranslator", "AI translate failed: ${e.message}")
            null
        }
    }

    /**
     * 百度通用文本翻译 API（降级方案）—— 走网关代理。
     * MD5 签名由 auth-worker 生成，客户端不持有 BAIDU_SECRET_KEY。
     */
    private suspend fun translateWithBaidu(text: String, targetLang: String): String? {
        return try {
            val response = translateApi.translateGeneral(
                TranslateRequest(q = text, from = "en", to = targetLang)
            )
            val json = response.string()
            extractTranslation(json)
        } catch (e: Exception) {
            Log.w("CommentTranslator", "General translate failed: ${e.message}")
            null
        }
    }

    /** 兼容百度原始响应和 Worker 标准化响应，避免接口形状变化时静默显示原文。 */
    private fun extractTranslation(json: String): String? {
        return try {
            val root = kotlinx.serialization.json.Json.parseToJsonElement(json) as? JsonObject
                ?: return null
            if (hasError(root)) return null
            extractFromObject(root)
                ?: listOf("result", "data")
                    .asSequence()
                    .mapNotNull { root[it] as? JsonObject }
                    .filterNot(::hasError)
                    .mapNotNull(::extractFromObject)
                    .firstOrNull()
        } catch (e: Exception) {
            Log.w("CommentTranslator", "Unable to parse translation response: ${e.message}")
            null
        }
    }

    private fun extractFromObject(value: JsonObject): String? {
        val direct = (value["translation"] as? JsonPrimitive)?.content
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        if (direct != null) return direct

        val items = value["trans_result"] as? JsonArray ?: return null
        return items.mapNotNull { item ->
            ((item as? JsonObject)?.get("dst") as? JsonPrimitive)?.content
        }.joinToString("").trim().takeIf { it.isNotEmpty() }
    }

    private fun hasError(value: JsonObject): Boolean {
        val error = value["error_code"] as? JsonPrimitive ?: return false
        return error.content.isNotEmpty()
    }

    private fun getTargetLangCode(): String {
        // 应用语言可能与系统语言不同，优先使用应用内设置；system 才回退到系统语言。
        val configuredLanguage = languageStorage.language.value
        val deviceLang = if (configuredLanguage == LanguageStorage.LANGUAGE_SYSTEM) {
            Locale.getDefault().language
        } else {
            configuredLanguage
        }
        return when (deviceLang) {
            "zh" -> "zh"
            "ja" -> "jp"
            "ko" -> "kor"
            "fr" -> "fra"
            "de" -> "de"
            "es" -> "spa"
            "pt" -> "pt"
            "ru" -> "ru"
            "it" -> "it"
            "ar" -> "ara"
            "th" -> "th"
            "vi" -> "vie"
            "en" -> "en"
            else -> "zh" // 不支持的小语种默认翻译成中文
        }
    }

}
