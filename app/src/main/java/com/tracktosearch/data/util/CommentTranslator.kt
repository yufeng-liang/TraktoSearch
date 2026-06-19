package com.tracktosearch.data.util

import android.util.Log
import com.tracktosearch.data.remote.trakt.dto.TraktComment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CommentTranslator @Inject constructor() {

    private val jsonDecoder = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    // 百度翻译 API 配置
    // 大模型文本翻译使用 API Key（Bearer Token）
    // 通用文本翻译使用 APP ID + 密钥（MD5 签名）
    private val BAIDU_APP_ID = "20221130001478267"
    private val BAIDU_SECRET_KEY = "06CRIETtOe2Xcsk9DbC3"
    private val BAIDU_API_KEY = "59Uf_d8miv7c3um9ef2qq4060"

    /** 翻译提示词：让大模型知道这是影视评论，保留人名/专有名词 */
    private val TRANSLATION_CONTEXT = "这是一条外文影视评论，请翻译为中文。保留电影/电视剧名称、演员名、导演名等专有名词不翻译。"

    /** 翻译单条评论（按需调用） */
    suspend fun translateSingleComment(comment: TraktComment): TraktComment {
        val targetLang = getTargetLangCode()
        if (targetLang == "en") return comment

        return withContext(Dispatchers.IO) {
            try {
                withTimeoutOrNull(10000) {
                    var result = translateWithBaiduAI(comment.comment, targetLang)
                    val isLongText = comment.comment.length > 6000
                    if (result.isNullOrEmpty() && !isLongText) result = translateWithBaidu(comment.comment, targetLang)

                    if (!result.isNullOrEmpty() && result != comment.comment) {
                        comment.copy(comment = result)
                    } else {
                        comment
                    }
                } ?: run { comment }
            } catch (e: Exception) {
                Log.e("CommentTranslator", "Single translation error", e)
                comment
            }
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

        return withContext(Dispatchers.IO) {
            comments.map { comment ->
                try {
                    withTimeoutOrNull(10000) {
                        var result = translateWithBaiduAI(comment.comment, targetLang)
                        val isLongText = comment.comment.length > 6000
                        if (result.isNullOrEmpty() && !isLongText) result = translateWithBaidu(comment.comment, targetLang)

                        if (!result.isNullOrEmpty() && result != comment.comment) {
                            comment.copy(comment = result)
                        } else {
                            comment
                        }
                    } ?: run {
                        comment
                    }
                } catch (e: Exception) {
                    Log.e("CommentTranslator", "Translation error: ${e.message}", e)
                    comment
                }
            }
        }
    }

    /** 百度大模型文本翻译 API（AI） */
    private fun translateWithBaiduAI(text: String, targetLang: String): String? {
        val url = "https://fanyi-api.baidu.com/ait/api/aiTextTranslate"

        // 手动构建 JSON，正确转义文本中的特殊字符
        val escapedText = text.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")
        val escapedReference = TRANSLATION_CONTEXT.replace("\\", "\\\\").replace("\"", "\\\"")
        val body = "{" +
                "\"appid\":\"$BAIDU_APP_ID\"," +
                "\"q\":\"$escapedText\"," +
                "\"from\":\"en\"," +
                "\"to\":\"$targetLang\"," +
                "\"model_type\":\"llm\"," +
                "\"reference\":\"$escapedReference\"" +
                "}"

        val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        connection.connectTimeout = 5000
        connection.readTimeout = 10000
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14)")
        connection.setRequestProperty("Authorization", "Bearer $BAIDU_API_KEY")

        return try {
            connection.outputStream.write(body.toByteArray(Charsets.UTF_8))
            connection.outputStream.flush()
            connection.outputStream.close()

            val responseCode = connection.responseCode
            if (responseCode == 200) {
                val json = connection.inputStream.bufferedReader().readText()
                val response = jsonDecoder.decodeFromString<BaiduAIResponse>(json)
                if (response.error_code == null) {
                    // trans_result 是直接数组 [{src, dst}, ...]
                    response.trans_result?.joinToString("") { it.dst ?: "" }
                        ?.takeIf { it.isNotEmpty() }
                } else {
                    null
                }
            } else {
                null
            }
        } finally {
            connection.disconnect()
        }
    }

    /** 百度通用文本翻译 API（降级方案） */
    private fun translateWithBaidu(text: String, targetLang: String): String? {
        val salt = UUID.randomUUID().toString()
        val signStr = BAIDU_APP_ID + text + salt + BAIDU_SECRET_KEY
        val sign = md5(signStr)
        val encodedText = URLEncoder.encode(text, "UTF-8")

        val url = ("https://fanyi-api.baidu.com/api/trans/vip/translate?" +
                "q=$encodedText&from=en&to=$targetLang" +
                "&appid=$BAIDU_APP_ID&salt=$salt&sign=$sign")

        val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        connection.connectTimeout = 5000
        connection.readTimeout = 8000
        connection.requestMethod = "GET"
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14)")

        return try {
            val json = connection.inputStream.bufferedReader().readText()
            val response = jsonDecoder.decodeFromString<BaiduResponse>(json)
            response.trans_result?.joinToString("") { it.dst ?: "" }
                ?.takeIf { it.isNotEmpty() }
        } finally {
            connection.disconnect()
        }
    }

    private fun md5(str: String): String {
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(str.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun getTargetLangCode(): String {
        val deviceLang = Locale.getDefault().language
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
            else -> "zh"
        }
    }

    @Serializable
    private data class BaiduAIResponse(
        val from: String? = null,
        val to: String? = null,
        val trans_result: List<BaiduAITransItem>? = null,
        val error_code: String? = null,
        val error_msg: String? = null
    )

    @Serializable
    private data class BaiduAITransItem(
        val src: String? = null,
        val dst: String? = null
    )

    @Serializable
    private data class BaiduResponse(
        val trans_result: List<BaiduTransItem>? = null,
        val error_code: String? = null,
        val error_msg: String? = null
    )

    @Serializable
    private data class BaiduTransItem(
        val src: String? = null,
        val dst: String? = null
    )
}
