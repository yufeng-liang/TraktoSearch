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
 * Gemini 原生协议适配器：generateContent / streamGenerateContent。
 *
 * 设计定案：Gemini TTS 延后、Gemini 配图不支持，所以 synthesizeSpeech /
 * generateImage 一律抛 [AiProviderErrorCode.PROVIDER_NO_TTS] / [PROVIDER_NO_IMAGE]。
 *
 * baseUrl 约定为根地址（如 https://generativelanguage.googleapis.com），
 * Key 走查询参数 ?key= 而不是 Authorization 头（Gemini 官方习惯）。
 */
class GeminiClient(
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
        val url = buildUrl(config, streaming = false)
        val payload = generateContentPayload(
            request,
            config,
            stream = false,
            includeTemperature = includeTemperature
        )
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
            val text = root.candidatesJoinedText()
                ?: throw AiProviderException(
                    AiProviderErrorCode.INVALID_RESPONSE,
                    "No candidates content in response"
                )
            return AiChatResponse(
                text = text,
                model = AiProviderErrorMapper.stringField(root, "modelVersion")
            )
        }
    }

    override fun stream(request: AiChatRequest, config: AiResolvedConfig): Flow<AiStreamChunk> = flow {
        val url = buildUrl(config, streaming = true)
        var response = executeRequest(url, generateContentPayload(request, config, stream = true), config)
        if (!response.isSuccessful) {
            val code = response.code
            val errorBody = response.body?.string().orEmpty()
            response.close()
            // reasoning 类模型不接受 temperature：去掉该字段重开一次流（最多一次）
            if (code == 400 && AiProviderErrorMapper.mentionsTemperature(errorBody)) {
                response = executeRequest(
                    url,
                    generateContentPayload(request, config, stream = true, includeTemperature = false),
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
            val source = body.source()
            val collected = StringBuilder()
            while (true) {
                val line = source.readUtf8Line() ?: break
                if (line.isBlank()) continue
                if (!line.startsWith(SSE_DATA_PREFIX)) continue
                val data = line.removePrefix(SSE_DATA_PREFIX).trim()
                if (data.isBlank()) continue
                val root = runCatching { lenientJson.parseToJsonElement(data).jsonObject }.getOrNull()
                    ?: continue
                val delta = root.candidatesJoinedText()
                if (!delta.isNullOrEmpty()) {
                    collected.append(delta)
                    emit(AiStreamChunk.Delta(delta))
                }
                // finishReason 出现在流式块的 candidates[0] 上，表示模型停止生成
                val finishReason = root.candidatesFirstFinishReason()
                if (finishReason != null) {
                    break
                }
            }
            emit(AiStreamChunk.Done(collected.toString()))
        }
    }.flowOn(Dispatchers.IO)

    /**
     * 组装 generateContent 请求体。
     * 单轮对话：system_instruction 承载系统提示词，contents 承载用户输入；
     * Gemini 的 role 只认 "user"/"model"，单轮下固定 "user"。
     */
    private fun generateContentPayload(
        request: AiChatRequest,
        config: AiResolvedConfig,
        @Suppress("UNUSED_PARAMETER") stream: Boolean,
        includeTemperature: Boolean = true
    ): String = buildJsonObject {
        put(
            "system_instruction",
            buildJsonObject {
                put("parts", JsonArray(listOf(textPart(request.system))))
            }
        )
        put(
            "contents",
            JsonArray(
                listOf(
                    buildJsonObject {
                        put("role", "user")
                        put("parts", JsonArray(listOf(textPart(request.user))))
                    }
                )
            )
        )
        put(
            "generationConfig",
            buildJsonObject {
                // 不支持 temperature 的模型（部分 Gemini 3 预览版）重试时省略该字段
                if (includeTemperature) {
                    put("temperature", request.temperature ?: DEFAULT_TEMPERATURE)
                }
                put("maxOutputTokens", request.maxOutputTokens ?: DEFAULT_MAX_TOKENS)
                if (request.jsonMode) {
                    // Gemini 的 JSON 约束：response_mime_type 而不是 response_format
                    put("response_mime_type", "application/json")
                }
            }
        )
    }.toString()

    private fun textPart(text: String) = buildJsonObject { put("text", text) }

    private fun buildUrl(config: AiResolvedConfig, streaming: Boolean): String {
        val action = if (streaming) "streamGenerateContent" else "generateContent"
        // 流式必须带 alt=sse，否则 Gemini 返回整段 JSON 数组而不是 SSE 事件流
        val suffix = if (streaming) "?alt=sse&key=" else "?key="
        return "${config.baseUrl.trimEnd('/')}/v1beta/models/${config.model}:$action" +
            "$suffix${config.apiKey}"
    }

    /**
     * 取 candidates[0].content.parts[*].text 拼接。
     * 多个 part 是正常的（模型可能拆段输出），全部顺序拼接。
     */
    private fun JsonObject.candidatesJoinedText(): String? {
        val candidates = this["candidates"] as? JsonArray ?: return null
        val first = candidates.firstOrNull() as? JsonObject ?: return null
        val content = first["content"] as? JsonObject ?: return null
        val parts = content["parts"] as? JsonArray ?: return null
        val texts = parts.mapNotNull { part ->
            (part as? JsonObject)?.get("text") as? JsonPrimitive
        }.filter { it.isString }
        if (texts.isEmpty()) return null
        return texts.joinToString("") { it.content }
    }

    /** candidates[0].finish_reason：出现即表示流终止。 */
    private fun JsonObject.candidatesFirstFinishReason(): String? {
        val candidates = this["candidates"] as? JsonArray ?: return null
        val first = candidates.firstOrNull() as? JsonObject ?: return null
        val reason = first["finishReason"] as? JsonPrimitive ?: return null
        return reason.content
    }

    // ---------- 模型目录 ----------

    /**
     * GET {baseUrl}/v1beta/models?key=...：返回 {"models":[{"name":"models/gemini-2.0-flash",...}]}，
     * 这里的 name 带 "models/" 前缀，剥掉后才是请求里要用的模型名。
     */
    override suspend fun listModels(config: AiResolvedConfig): List<AiModelInfo> {
        val url = "${config.baseUrl.trimEnd('/')}/v1beta/models?key=${config.apiKey}"
        val request = Request.Builder().url(url).get().build()
        val response = runCatching { client.newCall(request).execute() }
            .getOrElse { error -> throw AiProviderErrorMapper.fromNetworkError(error) }
        response.use { resp ->
            val bodyText = resp.body?.string()
                ?: throw AiProviderException(AiProviderErrorCode.INVALID_RESPONSE, "Empty model list body")
            if (!resp.isSuccessful) {
                throw AiProviderErrorMapper.fromHttpStatus(resp.code, bodyText)
            }
            val root = parseObject(bodyText)
            val array = root["models"] as? JsonArray
                ?: throw AiProviderException(
                    AiProviderErrorCode.INVALID_RESPONSE,
                    "No model list in response"
                )
            return array.mapNotNull { element ->
                val item = element as? JsonObject ?: return@mapNotNull null
                val name = AiProviderErrorMapper.stringField(item, "name") ?: return@mapNotNull null
                AiModelInfo(
                    id = name.removePrefix("models/"),
                    displayName = AiProviderErrorMapper.stringField(item, "displayName")
                )
            }.distinctBy { it.id }.sortedBy { it.id }
        }
    }

    // ---------- 不支持的能力 ----------

    override suspend fun synthesizeSpeech(
        request: AiTtsRequest,
        config: AiResolvedConfig
    ): ByteArray {
        throw AiProviderException(
            AiProviderErrorCode.PROVIDER_NO_TTS,
            "Gemini provider does not support speech synthesis"
        )
    }

    override suspend fun generateImage(
        request: AiImageRequest,
        config: AiResolvedConfig
    ): ByteArray {
        throw AiProviderException(
            AiProviderErrorCode.PROVIDER_NO_IMAGE,
            "Gemini provider does not support image generation"
        )
    }

    // ---------- 公共执行 ----------

    private fun executeRequest(url: String, payload: String, config: AiResolvedConfig): Response {
        val request = Request.Builder()
            .url(url)
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
    }
}
