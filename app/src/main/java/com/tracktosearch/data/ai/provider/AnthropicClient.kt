package com.tracktosearch.data.ai.provider

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * Anthropic 原生协议适配器：POST {baseUrl}/v1/messages（SSE 流式）。
 *
 * 鉴权：x-api-key 头 + anthropic-version 头（2023-06-01 是长期稳定版本）。
 *
 * 设计定案：Anthropic 不提供语音/配图 API，synthesizeSpeech / generateImage
 * 一律抛 [AiProviderErrorCode.PROVIDER_NO_TTS] / [PROVIDER_NO_IMAGE]。
 */
class AnthropicClient(
    client: OkHttpClient? = null
) : AiProviderClient {

    private val client: OkHttpClient = client ?: OpenAiCompatibleClient.buildDefaultClient()

    private val lenientJson = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    // ---------- 聊天 ----------

    override suspend fun complete(request: AiChatRequest, config: AiResolvedConfig): AiChatResponse =
        completeInternal(request, config, includeTemperature = true)

    /** 非流式补全：400 且错误体指向 temperature 不受支持时，去掉该字段重发一次。 */
    private suspend fun completeInternal(
        request: AiChatRequest,
        config: AiResolvedConfig,
        includeTemperature: Boolean
    ): AiChatResponse {
        val url = buildUrl(config.baseUrl)
        val payload = messagesPayload(request, config, stream = false, includeTemperature = includeTemperature)
        val response = executeRequest(url, payload, config)
        response.use { resp ->
            val bodyText = resp.body?.string()
                ?: throw AiProviderException(
                    AiProviderErrorCode.INVALID_RESPONSE,
                    "Empty response body"
                )
            if (!resp.isSuccessful) {
                if (resp.code == 400 && includeTemperature &&
                    AiProviderErrorMapper.mentionsTemperature(bodyText)
                ) {
                    return completeInternal(request, config, includeTemperature = false)
                }
                throw AiProviderErrorMapper.fromHttpStatus(resp.code, bodyText)
            }
            val root = parseObject(bodyText)
            val text = root.contentJoinedText()
                ?: throw AiProviderException(
                    AiProviderErrorCode.INVALID_RESPONSE,
                    "No content blocks in response"
                )
            return AiChatResponse(
                text = text,
                model = AiProviderErrorMapper.stringField(root, "model")
            )
        }
    }

    override fun stream(request: AiChatRequest, config: AiResolvedConfig): Flow<AiStreamChunk> = flow {
        val url = buildUrl(config.baseUrl)
        var response = executeRequest(url, messagesPayload(request, config, stream = true), config)
        if (!response.isSuccessful) {
            val code = response.code
            val errorBody = response.body?.string().orEmpty()
            response.close()
            // 模型不接受 temperature：去掉该字段重开一次流（最多一次）
            if (code == 400 && AiProviderErrorMapper.mentionsTemperature(errorBody)) {
                response = executeRequest(
                    url,
                    messagesPayload(request, config, stream = true, includeTemperature = false),
                    config
                )
            } else {
                throw AiProviderErrorMapper.fromHttpStatus(code, errorBody)
            }
        }
        response.use { resp ->
            val body = resp.body
                ?: throw AiProviderException(
                    AiProviderErrorCode.INVALID_RESPONSE,
                    "Empty stream body"
                )
            if (!resp.isSuccessful) {
                throw AiProviderErrorMapper.fromHttpStatus(resp.code, body.string())
            }
            // 协程被取消（用户退出页面）时立刻断开 socket，否则流式读会一直挂着
            currentCoroutineContext().job.invokeOnCompletion { resp.close() }
            val source = body.source()
            val collected = StringBuilder()
            // message_stop 才是正常收尾；读不到它说明流被截断，不能当作完成
            var sawTerminal = false
            while (true) {
                val line = try {
                    source.readUtf8Line()
                } catch (error: java.io.IOException) {
                    throw AiProviderErrorMapper.fromNetworkError(error)
                } ?: break
                if (line.isBlank()) continue
                // Anthropic SSE 是 event: + data: 双行结构，只处理 data: 行即可
                if (!line.startsWith(SSE_DATA_PREFIX)) continue
                val data = line.removePrefix(SSE_DATA_PREFIX).trim()
                if (data.isBlank()) continue
                val root = runCatching { lenientJson.parseToJsonElement(data).jsonObject }.getOrNull()
                    ?: continue
                when ((root["type"] as? JsonPrimitive)?.content) {
                    // 增量文本块：delta.text 是本次新增的内容
                    EVENT_CONTENT_BLOCK_DELTA -> {
                        val delta = ((root["delta"] as? JsonObject)
                            ?.get("text") as? JsonPrimitive)
                            ?.takeIf { it.isString }
                            ?.content
                            ?: continue
                        if (delta.isNotEmpty()) {
                            collected.append(delta)
                            emit(AiStreamChunk.Delta(delta))
                        }
                    }
                    // message_stop：官方规定的流终止事件
                    EVENT_MESSAGE_STOP -> {
                        sawTerminal = true
                        break
                    }
                    else -> Unit
                }
            }
            if (collected.isEmpty()) {
                throw AiProviderException(
                    AiProviderErrorCode.INVALID_RESPONSE,
                    if (sawTerminal) "Stream finished without any content" else "Stream closed before any data"
                )
            }
            emit(AiStreamChunk.Done(collected.toString()))
        }
    }.flowOn(Dispatchers.IO)

    /**
     * 组装 /v1/messages 请求体。
     * Anthropic 的 system 是顶层字符串字段（不是 messages 里的角色）；
     * max_tokens 是必填字段，缺省时必须给值。
     */
    private fun messagesPayload(
        request: AiChatRequest,
        config: AiResolvedConfig,
        stream: Boolean,
        includeTemperature: Boolean = true
    ): String {
        // jsonMode 时在 system 末尾追加显式 JSON 约束（Anthropic 没有 response_format）
        val system = if (request.jsonMode) {
            request.system + "\nRespond with valid JSON only."
        } else {
            request.system
        }
        return buildJsonObject {
            put("model", config.model)
            put("system", system)
            put("max_tokens", request.maxOutputTokens ?: DEFAULT_MAX_TOKENS)
            // 部分模型不接受 temperature（如 extended thinking 开启时），重试时省略该字段
            if (includeTemperature) {
                put("temperature", request.temperature ?: DEFAULT_TEMPERATURE)
            }
            put("stream", stream)
            put(
                "messages",
                JsonArray(
                    listOf(
                        buildJsonObject {
                            put("role", "user")
                            put("content", request.user)
                        }
                    )
                )
            )
        }.toString()
    }

    private fun buildUrl(baseUrl: String) = "${baseUrl.trimEnd('/')}/v1/messages"

    /** 非流式响应：content 是块数组，取 text 类型块的 text 字段顺序拼接。 */
    private fun JsonObject.contentJoinedText(): String? {
        val content = this["content"] as? JsonArray ?: return null
        val texts = content.mapNotNull { block ->
            val obj = block as? JsonObject ?: return@mapNotNull null
            val type = (obj["type"] as? JsonPrimitive)?.content
            if (type == "text") {
                (obj["text"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            } else {
                null
            }
        }
        if (texts.isEmpty()) return null
        return texts.joinToString("")
    }

    // ---------- 不支持的能力 ----------

    override suspend fun synthesizeSpeech(
        request: AiTtsRequest,
        config: AiResolvedConfig
    ): ByteArray {
        throw AiProviderException(
            AiProviderErrorCode.PROVIDER_NO_TTS,
            "Anthropic provider does not support speech synthesis"
        )
    }

    override suspend fun generateImage(
        request: AiImageRequest,
        config: AiResolvedConfig
    ): ByteArray {
        throw AiProviderException(
            AiProviderErrorCode.PROVIDER_NO_IMAGE,
            "Anthropic provider does not support image generation"
        )
    }

    // ---------- 模型目录 ----------

    /**
     * GET {baseUrl}/v1/models：返回 {"data":[{"id":"claude-sonnet-4-...","display_name":"..."}]}。
     * 需要 x-api-key + anthropic-version 两个头，与聊天端点一致。
     */
    override suspend fun listModels(config: AiResolvedConfig): List<AiModelInfo> {
        val url = "${config.baseUrl.trimEnd('/')}/v1/models"
        val request = Request.Builder()
            .url(url)
            .header("x-api-key", config.apiKey)
            .header("anthropic-version", ANTHROPIC_VERSION)
            .get()
            .build()
        val response = runCatching { client.newCall(request).execute() }
            .getOrElse { error -> throw AiProviderErrorMapper.fromNetworkError(error) }
        response.use { resp ->
            val bodyText = resp.body?.string()
                ?: throw AiProviderException(AiProviderErrorCode.INVALID_RESPONSE, "Empty model list body")
            if (!resp.isSuccessful) {
                throw AiProviderErrorMapper.fromHttpStatus(resp.code, bodyText)
            }
            val root = parseObject(bodyText)
            val array = root["data"] as? JsonArray
                ?: throw AiProviderException(
                    AiProviderErrorCode.INVALID_RESPONSE,
                    "No model list in response"
                )
            return array.mapNotNull { element ->
                val item = element as? JsonObject ?: return@mapNotNull null
                val id = AiProviderErrorMapper.stringField(item, "id") ?: return@mapNotNull null
                AiModelInfo(id = id, displayName = AiProviderErrorMapper.stringField(item, "display_name"))
            }.distinctBy { it.id }.sortedBy { it.id }
        }
    }

    // ---------- 公共执行 ----------

    /**
     * POST JSON 并返回响应；网络层失败统一转 NETWORK 异常。
     *
     * 切到 IO 线程执行：调用方可能跑在 Main.immediate 上（配置页拉目录/探测），
     * 在这里兜住比要求每个调用方自觉更可靠。
     */
    private suspend fun executeRequest(url: String, payload: String, config: AiResolvedConfig): Response {
        val request = Request.Builder()
            .url(url)
            .header("x-api-key", config.apiKey)
            .header("anthropic-version", ANTHROPIC_VERSION)
            .header("Content-Type", "application/json")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()
        return withContext(Dispatchers.IO) {
            runCatching { client.newCall(request).execute() }
                .getOrElse { error -> throw AiProviderErrorMapper.fromNetworkError(error) }
        }
    }

    private fun parseObject(text: String): JsonObject =
        runCatching { lenientJson.parseToJsonElement(text).jsonObject }.getOrElse { error ->
            throw AiProviderException(
                AiProviderErrorCode.INVALID_RESPONSE,
                "Unparseable provider response: ${error.message}"
            )
        }

    companion object {
        private const val DEFAULT_TEMPERATURE = 0.7
        private const val DEFAULT_MAX_TOKENS = 4096
        private const val SSE_DATA_PREFIX = "data:"
        private const val EVENT_CONTENT_BLOCK_DELTA = "content_block_delta"
        private const val EVENT_MESSAGE_STOP = "message_stop"

        /** Anthropic 官方长期支持的 API 版本号。 */
        private const val ANTHROPIC_VERSION = "2023-06-01"
    }
}
