package com.tracktosearch.data.ai.provider

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * OpenAI 兼容协议适配器：覆盖 chat/completions（聊天）、audio/speech（TTS）、
 * images/generations（配图）三条端点，是国内中转站与 OpenAI 官方共用的最大公约数。
 *
 * baseUrl 约定为根地址（如 https://api.openai.com/v1），端点直接拼在其后。
 */
class OpenAiCompatibleClient(
    client: OkHttpClient? = null
) : AiProviderClient {

    private val client: OkHttpClient = client ?: buildDefaultClient()

    /** 宽松解析：兼容端经常多塞字段，禁止因未知键炸掉整个响应。 */
    private val lenientJson = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    // ---------- 聊天 ----------

    override suspend fun complete(request: AiChatRequest, config: AiResolvedConfig): AiChatResponse {
        val payload = chatPayload(request, config, stream = false)
        val response = executeRequest(buildChatUrl(config.baseUrl), payload, config)
        response.use { resp ->
            val bodyText = resp.body?.string()
                ?: throw AiProviderException(
                    AiProviderErrorCode.INVALID_RESPONSE,
                    "Empty response body"
                )
            if (!resp.isSuccessful) {
                // 400 可能是 response_format 不被兼容端支持：回退重试一次不带该字段的请求
                if (resp.code == 400 && request.jsonMode) {
                    return completeWithoutResponseFormat(request, config)
                }
                throw AiProviderErrorMapper.fromHttpStatus(resp.code, bodyText)
            }
            val root = parseObject(bodyText, AiProviderErrorCode.INVALID_RESPONSE)
            val text = root.choicesFirstDeltaText()
                ?: throw AiProviderException(
                    AiProviderErrorCode.INVALID_RESPONSE,
                    "No message content in response"
                )
            return AiChatResponse(
                text = text,
                model = AiProviderErrorMapper.stringField(root, "model")
            )
        }
    }

    /**
     * jsonMode 回退路径：部分兼容端（尤其老版本 vLLM / 一些中转）不认
     * response_format 字段，直接 400。此时去掉该字段重试，并在 system 里
     * 追加「仅输出 JSON」的显式指令兜底。
     */
    private suspend fun completeWithoutResponseFormat(
        request: AiChatRequest,
        config: AiResolvedConfig
    ): AiChatResponse {
        val retry = AiChatRequest(
            system = request.system + "\n仅输出 JSON，不要输出任何其他文字。",
            user = request.user,
            temperature = request.temperature,
            maxOutputTokens = request.maxOutputTokens,
            jsonMode = false
        )
        val payload = chatPayload(retry, config, stream = false)
        val response = executeRequest(buildChatUrl(config.baseUrl), payload, config)
        response.use { resp ->
            val bodyText = resp.body?.string()
                ?: throw AiProviderException(
                    AiProviderErrorCode.INVALID_RESPONSE,
                    "Empty response body"
                )
            if (!resp.isSuccessful) {
                throw AiProviderErrorMapper.fromHttpStatus(resp.code, bodyText)
            }
            val root = parseObject(bodyText, AiProviderErrorCode.INVALID_RESPONSE)
            val text = root.choicesFirstDeltaText()
                ?: throw AiProviderException(
                    AiProviderErrorCode.INVALID_RESPONSE,
                    "No message content in response"
                )
            return AiChatResponse(text = text, model = AiProviderErrorMapper.stringField(root, "model"))
        }
    }

    override fun stream(request: AiChatRequest, config: AiResolvedConfig): Flow<AiStreamChunk> = flow {
        val payload = chatPayload(request, config, stream = true)
        val response = executeRequest(buildChatUrl(config.baseUrl), payload, config)
        response.use { resp ->
            val body = resp.body
                ?: throw AiProviderException(
                    AiProviderErrorCode.INVALID_RESPONSE,
                    "Empty stream body"
                )
            if (!resp.isSuccessful) {
                val errorBody = body.string()
                throw AiProviderErrorMapper.fromHttpStatus(resp.code, errorBody)
            }
            val source = body.source()
            val collected = StringBuilder()
            while (true) {
                val line = source.readUtf8Line() ?: break
                // SSE 规范：data: 前缀 + 可选空格；空行是事件分隔符，直接跳过
                if (line.isBlank()) continue
                if (!line.startsWith(SSE_DATA_PREFIX)) continue
                val data = line.removePrefix(SSE_DATA_PREFIX).trim()
                if (data == SSE_DONE_MARKER) break
                val root = runCatching { lenientJson.parseToJsonElement(data).jsonObject }.getOrNull()
                    ?: continue
                val delta = root.choicesFirstDeltaText() ?: continue
                if (delta.isNotEmpty()) {
                    collected.append(delta)
                    emit(AiStreamChunk.Delta(delta))
                }
            }
            emit(AiStreamChunk.Done(collected.toString()))
        }
    }.flowOn(Dispatchers.IO)

    /**
     * 组装 chat/completions 请求体。
     * 非流式时 choices[0].message.content；流式时 choices[0].delta.content，
     * 两处取值路径相同（都用 delta 解析器，因为 message.content 和 delta.content
     * 的 JSON 形状一致，取第一条 choices 即可）。
     */
    private fun chatPayload(
        request: AiChatRequest,
        config: AiResolvedConfig,
        stream: Boolean
    ): String = buildJsonObject {
        put("model", config.model)
        put("stream", stream)
        put("temperature", request.temperature ?: DEFAULT_TEMPERATURE)
        put("max_tokens", request.maxOutputTokens ?: DEFAULT_MAX_TOKENS)
        put("messages", JsonArray(listOf(systemMessage(request), userMessage(request))))
        if (request.jsonMode) {
            put("response_format", buildJsonObject { put("type", "json_object") })
        }
    }.toString()

    private fun systemMessage(request: AiChatRequest) = buildJsonObject {
        put("role", "system")
        put("content", request.system)
    }

    private fun userMessage(request: AiChatRequest) = buildJsonObject {
        put("role", "user")
        put("content", request.user)
    }

    private fun buildChatUrl(baseUrl: String) = "${baseUrl.trimEnd('/')}/chat/completions"

    /**
     * 从 choices 数组取第一条的文本内容。
     * 非流式响应内容在 message.content，流式在 delta.content —— 两者的
     * JsonObject 取值写法一致，共用这一个函数。
     */
    private fun JsonObject.choicesFirstDeltaText(): String? {
        val choices = this["choices"] as? JsonArray ?: return null
        val first = choices.firstOrNull() as? JsonObject ?: return null
        val message = first["message"] as? JsonObject
            ?: first["delta"] as? JsonObject
            ?: return null
        val content = message["content"] as? JsonPrimitive ?: return null
        return if (content.isString) content.content else content.content
    }

    // ---------- TTS ----------

    override suspend fun synthesizeSpeech(
        request: AiTtsRequest,
        config: AiResolvedConfig
    ): ByteArray {
        val payload = buildJsonObject {
            // TTS 模型固定取 config.model：功能位覆盖模型在 ResolvedConfig 组装时已完成
            put("model", config.model)
            put("input", request.text)
            put("voice", request.voice)
            put("speed", request.speed)
            put("response_format", "mp3")
        }.toString()
        val url = "${config.baseUrl.trimEnd('/')}/audio/speech"
        val response = executeRequest(url, payload, config)
        response.use { resp ->
            val body = resp.body
                ?: throw AiProviderException(
                    AiProviderErrorCode.INVALID_RESPONSE,
                    "Empty audio response body"
                )
            if (!resp.isSuccessful) {
                throw AiProviderErrorMapper.fromHttpStatus(resp.code, body.string())
            }
            return body.bytes()
        }
    }

    // ---------- 配图 ----------

    override suspend fun generateImage(
        request: AiImageRequest,
        config: AiResolvedConfig
    ): ByteArray {
        val payload = buildJsonObject {
            put("model", config.model)
            put("prompt", request.prompt)
            put("size", request.size)
            put("n", request.n)
            put("response_format", "b64_json")
        }.toString()
        val url = "${config.baseUrl.trimEnd('/')}/images/generations"
        val response = executeRequest(url, payload, config)
        response.use { resp ->
            val bodyText = resp.body?.string()
                ?: throw AiProviderException(
                    AiProviderErrorCode.INVALID_RESPONSE,
                    "Empty image response body"
                )
            if (!resp.isSuccessful) {
                throw AiProviderErrorMapper.fromHttpStatus(resp.code, bodyText)
            }
            val root = parseObject(bodyText, AiProviderErrorCode.INVALID_RESPONSE)
            val data = root["data"] as? JsonArray
                ?: throw AiProviderException(
                    AiProviderErrorCode.INVALID_RESPONSE,
                    "Missing data array in image response"
                )
            val b64 = ((data.firstOrNull() as? JsonObject)
                ?.get("b64_json") as? JsonPrimitive)
                ?.takeIf { it.isString }
                ?.content
                ?: throw AiProviderException(
                    AiProviderErrorCode.INVALID_RESPONSE,
                    "Missing b64_json in image response"
                )
            return runCatching { android.util.Base64.decode(b64, android.util.Base64.DEFAULT) }
                .getOrElse { error ->
                    throw AiProviderException(
                        AiProviderErrorCode.INVALID_RESPONSE,
                        "Invalid base64 image data: ${error.message}"
                    )
                }
        }
    }

    // ---------- 公共执行 ----------

    /** POST JSON 并返回响应；网络层失败统一转 NETWORK 异常。 */
    private fun executeRequest(url: String, payload: String, config: AiResolvedConfig): Response {
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${config.apiKey}")
            .header("Content-Type", "application/json")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()
        return runCatching { client.newCall(request).execute() }
            .getOrElse { error -> throw AiProviderErrorMapper.fromNetworkError(error) }
    }

    private fun parseObject(text: String, errorCode: AiProviderErrorCode): JsonObject =
        runCatching { lenientJson.parseToJsonElement(text).jsonObject }.getOrElse { error ->
            throw AiProviderException(
                errorCode,
                "Unparseable provider response: ${error.message}"
            )
        }

    companion object {
        private const val DEFAULT_TEMPERATURE = 0.7
        private const val DEFAULT_MAX_TOKENS = 4096
        private const val SSE_DATA_PREFIX = "data:"
        private const val SSE_DONE_MARKER = "[DONE]"

        /** 独立使用时的默认客户端：connect 15s / read 90s / call 120s（两段式生成留足时间）。 */
        fun buildDefaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(java.time.Duration.ofMillis(15_000))
            .readTimeout(java.time.Duration.ofMillis(90_000))
            .callTimeout(java.time.Duration.ofMillis(120_000))
            .build()
    }
}
