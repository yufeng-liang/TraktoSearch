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

    override suspend fun complete(request: AiChatRequest, config: AiResolvedConfig): AiChatResponse =
        completeInternal(request, config, includeTemperature = true, allowJsonFallback = true)

    /**
     * 非流式补全，带两级降级：
     * 1. 400 且错误体指向 temperature 不受支持 → 去掉该字段重发（reasoning 模型常见）；
     * 2. 400 且开了 jsonMode → 退回无 response_format 的纯文本请求（部分兼容端不认该字段）。
     */
    private suspend fun completeInternal(
        request: AiChatRequest,
        config: AiResolvedConfig,
        includeTemperature: Boolean,
        allowJsonFallback: Boolean,
        includeMaxTokens: Boolean = true
    ): AiChatResponse {
        val payload = chatPayload(
            request = request,
            config = config,
            stream = false,
            includeTemperature = includeTemperature,
            includeMaxTokens = includeMaxTokens
        )
        val response = executeRequest(buildChatUrl(config.baseUrl), payload, config)
        response.use { resp ->
            val bodyText = resp.body?.string()
                ?: throw AiProviderException(
                    AiProviderErrorCode.INVALID_RESPONSE,
                    "Empty response body"
                )
            if (!resp.isSuccessful) {
                // 先处理更具体的 temperature，再处理输出长度参数，最后处理 response_format
                if (resp.code == 400 && includeTemperature &&
                    AiProviderErrorMapper.mentionsTemperature(bodyText)
                ) {
                    return completeInternal(
                        request = request,
                        config = config,
                        includeTemperature = false,
                        allowJsonFallback = allowJsonFallback,
                        includeMaxTokens = includeMaxTokens
                    )
                }
                // 输出长度字段名和模型不匹配（o 系只认 max_completion_tokens，部分中转只认 max_tokens）：
                // 去掉该字段重发一次，让服务端用默认长度
                if (resp.code == 400 && includeMaxTokens &&
                    AiProviderErrorMapper.mentionsMaxTokensRejection(bodyText)
                ) {
                    return completeInternal(
                        request = request,
                        config = config,
                        includeTemperature = includeTemperature,
                        allowJsonFallback = allowJsonFallback,
                        includeMaxTokens = false
                    )
                }
                // 400 可能是 response_format 不被兼容端支持：回退重试一次不带该字段的请求
                if (resp.code == 400 && request.jsonMode && allowJsonFallback) {
                    return completeWithoutResponseFormat(request, config, includeTemperature, includeMaxTokens)
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
        config: AiResolvedConfig,
        includeTemperature: Boolean,
        includeMaxTokens: Boolean = true
    ): AiChatResponse {
        val retry = AiChatRequest(
            system = request.system + "\n仅输出 JSON，不要输出任何其他文字。",
            user = request.user,
            temperature = request.temperature,
            maxOutputTokens = request.maxOutputTokens,
            jsonMode = false
        )
        val payload = chatPayload(
            request = retry,
            config = config,
            stream = false,
            includeTemperature = includeTemperature,
            includeMaxTokens = includeMaxTokens
        )
        val response = executeRequest(buildChatUrl(config.baseUrl), payload, config)
        response.use { resp ->
            val bodyText = resp.body?.string()
                ?: throw AiProviderException(
                    AiProviderErrorCode.INVALID_RESPONSE,
                    "Empty response body"
                )
            if (!resp.isSuccessful) {
                // 退化成纯文本后仍可能因为 temperature 被拒（reasoning 模型），再降一级
                if (resp.code == 400 && includeTemperature &&
                    AiProviderErrorMapper.mentionsTemperature(bodyText)
                ) {
                    return completeWithoutResponseFormat(
                        request,
                        config,
                        includeTemperature = false,
                        includeMaxTokens = includeMaxTokens
                    )
                }
                if (resp.code == 400 && includeMaxTokens &&
                    AiProviderErrorMapper.mentionsMaxTokensRejection(bodyText)
                ) {
                    return completeWithoutResponseFormat(
                        request,
                        config,
                        includeTemperature = includeTemperature,
                        includeMaxTokens = false
                    )
                }
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
        val url = buildChatUrl(config.baseUrl)
        var includeTemperature = true
        var includeMaxTokens = true
        var response = openStream(url, request, config, includeTemperature, includeMaxTokens)
        // 400 自愈：temperature / 输出长度字段不被该模型接受时，去掉对应字段重开一次（各最多一次）
        var downgrades = 0
        while (!response.isSuccessful) {
            val code = response.code
            val errorBody = response.body?.string().orEmpty()
            response.close()
            val temperatureRejected =
                includeTemperature && AiProviderErrorMapper.mentionsTemperature(errorBody)
            val maxTokensRejected = !temperatureRejected && includeMaxTokens &&
                AiProviderErrorMapper.mentionsMaxTokensRejection(errorBody)
            if (code != 400 || downgrades >= 2 || (!temperatureRejected && !maxTokensRejected)) {
                throw AiProviderErrorMapper.fromHttpStatus(code, errorBody)
            }
            if (temperatureRejected) includeTemperature = false else includeMaxTokens = false
            downgrades++
            response = openStream(url, request, config, includeTemperature, includeMaxTokens)
        }
        response.use { resp ->
            val body = resp.body
                ?: throw AiProviderException(
                    AiProviderErrorCode.INVALID_RESPONSE,
                    "Empty stream body"
                )
            // 协程取消（页面退出/被超时中断）时主动关响应，让阻塞中的读 socket 立刻返回
            currentCoroutineContext().job.invokeOnCompletion { resp.close() }
            val source = body.source()
            val collected = StringBuilder()
            var sawTerminal = false
            while (true) {
                val line = try {
                    source.readUtf8Line()
                } catch (error: java.io.IOException) {
                    // 读流中途断连/超时必须抛协议错误：调用方只 catch AiProviderException，
                    // 裸 IOException 会穿透成崩溃，而静默 break 会把半截回答当成最终结果
                    throw AiProviderErrorMapper.fromNetworkError(error)
                } ?: break
                // SSE 规范：data: 前缀 + 可选空格；空行是事件分隔符，直接跳过
                if (line.isBlank()) continue
                if (!line.startsWith(SSE_DATA_PREFIX)) continue
                val data = line.removePrefix(SSE_DATA_PREFIX).trim()
                if (data == SSE_DONE_MARKER) {
                    sawTerminal = true
                    break
                }
                val root = runCatching { lenientJson.parseToJsonElement(data).jsonObject }.getOrNull()
                    ?: continue
                val delta = root.choicesFirstDeltaText() ?: continue
                if (delta.isNotEmpty()) {
                    collected.append(delta)
                    emit(AiStreamChunk.Delta(delta))
                }
            }
            // 一个字节都没收到就结束 = 连接被提前关掉；报错而不是把空回答当成功
            if (collected.isEmpty()) {
                throw AiProviderException(
                    AiProviderErrorCode.INVALID_RESPONSE,
                    if (sawTerminal) "Stream finished without any content" else "Stream closed before any data"
                )
            }
            emit(AiStreamChunk.Done(collected.toString()))
        }
    }.flowOn(Dispatchers.IO)

    /** 流式请求单独入口：便于 400 自愈时用不同 payload 重开同一条流。 */
    private suspend fun openStream(
        url: String,
        request: AiChatRequest,
        config: AiResolvedConfig,
        includeTemperature: Boolean,
        includeMaxTokens: Boolean
    ): Response = executeRequest(
        url = url,
        payload = chatPayload(
            request = request,
            config = config,
            stream = true,
            includeTemperature = includeTemperature,
            includeMaxTokens = includeMaxTokens
        ),
        config = config
    )

    /**
     * 组装 chat/completions 请求体。
     * 非流式时 choices[0].message.content；流式时 choices[0].delta.content，
     * 两处取值路径相同（都用 delta 解析器，因为 message.content 和 delta.content
     * 的 JSON 形状一致，取第一条 choices 即可）。
     */
    private fun chatPayload(
        request: AiChatRequest,
        config: AiResolvedConfig,
        stream: Boolean,
        includeTemperature: Boolean = true,
        includeMaxTokens: Boolean = true
    ): String = buildJsonObject {
        put("model", config.model)
        put("stream", stream)
        // reasoning 模型（o 系列 / gpt-5 起）不接受 temperature，重试时整体省略该字段
        if (includeTemperature) {
            put("temperature", request.temperature ?: DEFAULT_TEMPERATURE)
        }
        // o 系列 / gpt-5 起换成 max_completion_tokens，老模型与多数兼容端只认 max_tokens。
        // 判断只在客户端做，猜错时由 400 自愈（mentionsMaxTokensRejection）去掉字段重发。
        if (includeMaxTokens) {
            val tokenField = if (REASONING_MODEL_PATTERN.containsMatchIn(config.model)) {
                "max_completion_tokens"
            } else {
                "max_tokens"
            }
            put(tokenField, request.maxOutputTokens ?: DEFAULT_MAX_TOKENS)
        }
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

    // ---------- 模型目录 ----------

    /**
     * GET {baseUrl}/models：OpenAI 官方与绝大多数兼容中转站都支持，
     * 返回 {"data":[{"id":"gpt-4o-mini",...}]}；按 id 字典序排列便于界面查找。
     */
    override suspend fun listModels(config: AiResolvedConfig): List<AiModelInfo> {
        val url = "${config.baseUrl.trimEnd('/')}/models"
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${config.apiKey}")
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
            val root = parseObject(bodyText, AiProviderErrorCode.INVALID_RESPONSE)
            val array = root["data"] as? JsonArray
                ?: root["models"] as? JsonArray
                ?: throw AiProviderException(
                    AiProviderErrorCode.INVALID_RESPONSE,
                    "No model list in response"
                )
            return array.mapNotNull { element ->
                val item = element as? JsonObject ?: return@mapNotNull null
                val id = AiProviderErrorMapper.stringField(item, "id")
                    ?: AiProviderErrorMapper.stringField(item, "name")
                    ?: return@mapNotNull null
                AiModelInfo(id = id, displayName = AiProviderErrorMapper.stringField(item, "display_name"))
            }.distinctBy { it.id }.sortedBy { it.id }
        }
    }

    // ---------- 公共执行 ----------

    /**
     * POST JSON 并返回响应；网络层失败统一转 NETWORK 异常。
     *
     * 必须在 IO 线程执行：调用方（配置页拉目录/探测、编排器）有的直接跑在 Main.immediate 上，
     * 在这里统一切线程比要求每个调用方自觉更可靠。
     */
    private suspend fun executeRequest(url: String, payload: String, config: AiResolvedConfig): Response {
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${config.apiKey}")
            .header("Content-Type", "application/json")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()
        return withContext(Dispatchers.IO) {
            runCatching { client.newCall(request).execute() }
                .getOrElse { error -> throw AiProviderErrorMapper.fromNetworkError(error) }
        }
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

        /**
         * o 系 / gpt-5 起只认 max_completion_tokens。
         * 只做「优先尝试」，猜错由 400 自愈兜底，所以宁可窄一点：误判成 max_tokens 的模型仍能靠重试救回。
         */
        private val REASONING_MODEL_PATTERN = Regex("^(o\\d|gpt-5|gpt-6)")

        /**
         * 独立使用时的默认客户端：connect 15s / read 90s，**不设 callTimeout**。
         * callTimeout 是整个调用（含流式读取）的硬上限，会给长流式生成砍半途；
         * read 超时已经能拦住「没有数据继续来」的卡死情况。
         */
        fun buildDefaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(java.time.Duration.ofMillis(15_000))
            .readTimeout(java.time.Duration.ofMillis(90_000))
            .callTimeout(java.time.Duration.ZERO)
            .build()
    }
}
