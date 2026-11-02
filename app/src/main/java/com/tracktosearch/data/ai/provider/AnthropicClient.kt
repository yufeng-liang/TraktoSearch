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

    override suspend fun complete(request: AiChatRequest, config: AiResolvedConfig): AiChatResponse {
        val url = buildUrl(config.baseUrl)
        val payload = messagesPayload(request, config, stream = false)
        val response = executeRequest(url, payload, config)
        response.use { resp ->
            val bodyText = resp.body?.string()
                ?: throw AiProviderException(
                    AiProviderErrorCode.INVALID_RESPONSE,
                    "Empty response body"
                )
            if (!resp.isSuccessful) {
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
        val payload = messagesPayload(request, config, stream = true)
        val response = executeRequest(url, payload, config)
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
                    EVENT_MESSAGE_STOP -> break
                    else -> Unit
                }
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
        stream: Boolean
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
            put("temperature", request.temperature ?: DEFAULT_TEMPERATURE)
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

    // ---------- 公共执行 ----------

    private fun executeRequest(url: String, payload: String, config: AiResolvedConfig): Response {
        val request = Request.Builder()
            .url(url)
            .header("x-api-key", config.apiKey)
            .header("anthropic-version", ANTHROPIC_VERSION)
            .header("Content-Type", "application/json")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()
        return runCatching { client.newCall(request).execute() }
            .getOrElse { error -> throw AiProviderErrorMapper.fromNetworkError(error) }
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
