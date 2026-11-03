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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * OpenAI Responses 协议适配器：覆盖 POST {baseUrl}/responses 的聊天补全（非流式 + SSE 流式）。
 *
 * 与 OpenAI 兼容协议的两处关键差异（写错就是 400）：
 * - 输入是 `input[]` 消息数组，不是 `messages[]`；输出文本在 `output_text`（或 `output[]` 里的
 *   `output_text` 片段），不是 `choices[0].message.content`；
 * - JSON 模式在 `text.format`，不是 `response_format`；max token 字段叫 `max_output_tokens`，
 *   不是 `max_tokens`。
 *
 * o 系列 / gpt-5+ reasoning 模型不接受 temperature，会回 400 且错误文本含 temperature：
 * 这里做一次去 temperature 的重试（chat 与 stream 各一条路径，最多重试一次），
 * 避免上层因为「换个模型就 400」而误判配置错误。
 *
 * 媒体端点（TTS / 配图）Responses 协议本身没有，仍委托 [OpenAiCompatibleClient] 走 OpenAI
 * 兼容路径；媒体服务商的协议在配置层已收敛为 OPENAI_COMPATIBLE，这里的委托只是满足接口。
 *
 * 注意：本类不读取 [AiResolvedConfig.protocol]，协议由上层工厂选定（枚举值由主代理统一补）。
 */
class OpenAiResponsesClient(
    client: OkHttpClient? = null
) : AiProviderClient {

    private val client: OkHttpClient = client ?: buildDefaultClient()

    /** 媒体端点委托对象：共用同一个 OkHttpClient，避免重复建连接池与线程池。 */
    private val compatible = OpenAiCompatibleClient(this.client)

    /** 宽松解析：Responses 响应字段多且新增频繁，禁止因未知键炸掉整个响应。 */
    private val lenientJson = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    // ---------- 非流式补全 ----------

    override suspend fun complete(request: AiChatRequest, config: AiResolvedConfig): AiChatResponse {
        val url = buildResponsesUrl(config.baseUrl)
        val first = postForText(
            url,
            buildPayload(request, config, stream = false, includeTemperature = true),
            config
        )
        // 400 且错误文本点名 temperature：该模型（o 系列 / gpt-5+ reasoning）不接受温度参数，
        // 去掉字段原样重发一次，其余字段不变。
        val exchange = if (first.code == 400 && isTemperatureRejection(first.body)) {
            postForText(
                url,
                buildPayload(request, config, stream = false, includeTemperature = false),
                config
            )
        } else {
            first
        }
        if (exchange.code !in 200..299) {
            throw AiProviderErrorMapper.fromHttpStatus(exchange.code, exchange.body)
        }
        val root = parseObject(exchange.body, AiProviderErrorCode.INVALID_RESPONSE)
        val text = extractOutputText(root)
            ?: throw AiProviderException(
                AiProviderErrorCode.INVALID_RESPONSE,
                "No output text in response"
            )
        return AiChatResponse(
            text = text,
            model = AiProviderErrorMapper.stringField(root, "model") ?: config.model
        )
    }

    // ---------- 流式补全 ----------

    override fun stream(request: AiChatRequest, config: AiResolvedConfig): Flow<AiStreamChunk> = flow {
        val url = buildResponsesUrl(config.baseUrl)
        var response = executeRequest(
            url,
            buildPayload(request, config, stream = true, includeTemperature = true),
            config
        )
        // 首包 400 且报 temperature 不支持：读出错误体后换不带 temperature 的请求体重开一次流。
        // 只在首包判断（此时还没有任何 delta 产出），因此不需要关心「重放已发出的文本」。
        if (response.code == 400) {
            val errorBody = response.use { it.body?.string().orEmpty() }
            if (!isTemperatureRejection(errorBody)) {
                throw AiProviderErrorMapper.fromHttpStatus(400, errorBody)
            }
            response = executeRequest(
                url,
                buildPayload(request, config, stream = true, includeTemperature = false),
                config
            )
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
            // response.completed 才是正常收尾；读不到它说明流被截断，不能当作完成
            var sawTerminal = false
            while (true) {
                val line = try {
                    source.readUtf8Line()
                } catch (error: java.io.IOException) {
                    throw AiProviderErrorMapper.fromNetworkError(error)
                } ?: break
                // SSE 规范：data: 前缀 + 可选空格；空行是事件分隔符，直接跳过
                if (line.isBlank()) continue
                if (!line.startsWith(SSE_DATA_PREFIX)) continue
                val data = line.removePrefix(SSE_DATA_PREFIX).trim()
                if (data == SSE_DONE_MARKER) break
                val event = runCatching { lenientJson.parseToJsonElement(data).jsonObject }.getOrNull()
                    ?: continue
                when (AiProviderErrorMapper.stringField(event, "type")) {
                    EVENT_OUTPUT_TEXT_DELTA -> {
                        val delta = AiProviderErrorMapper.stringField(event, "delta")
                        if (!delta.isNullOrEmpty()) {
                            collected.append(delta)
                            emit(AiStreamChunk.Delta(delta))
                        }
                    }
                    // 正常终态：后面的 usage 之类事件不需要，直接结束
                    EVENT_COMPLETED -> {
                        sawTerminal = true
                        break
                    }
                    // 协议层失败事件：message 优先取 response.error.message，其次 error.message，
                    // 都没有就把原始 JSON 片段截断带回，便于排查
                    EVENT_FAILED, EVENT_ERROR -> throw streamFailure(event, data)
                    // 其他事件（created / in_progress / content_part.added 等）忽略
                    else -> continue
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

    // ---------- 媒体端点（委托 OpenAI 兼容路径） ----------

    /**
     * Responses 协议不提供语音合成端点，委托 OpenAI 兼容实现（/audio/speech）。
     * 媒体服务商的协议在配置层已收敛为 OPENAI_COMPATIBLE，这里的委托只是满足接口。
     */
    override suspend fun synthesizeSpeech(
        request: AiTtsRequest,
        config: AiResolvedConfig
    ): ByteArray = compatible.synthesizeSpeech(request, config)

    /**
     * Responses 协议不提供文生图端点，委托 OpenAI 兼容实现（/images/generations）。
     * 配图服务商协议同样在配置层已收敛为 OPENAI_COMPATIBLE。
     */
    override suspend fun generateImage(
        request: AiImageRequest,
        config: AiResolvedConfig
    ): ByteArray = compatible.generateImage(request, config)

    // ---------- 模型目录 ----------

    /**
     * GET {baseUrl}/models：Responses 与 Chat Completions 共用同一份模型目录，
     * 解析规则（data[] / models[]、id 或 name、display_name、去重排序）完全一致，
     * 直接委托 [OpenAiCompatibleClient] 复用同一份实现，避免两处漂移。
     */
    override suspend fun listModels(config: AiResolvedConfig): List<AiModelInfo> =
        compatible.listModels(config)

    // ---------- 请求体组装 ----------

    /**
     * 组装 responses 请求体。
     *
     * [includeTemperature] 是 temperature 容错开关：reasoning 模型不接受该参数，
     * 重试时传 false 去掉它；其余字段两种请求完全一致，保证重试语义等价。
     */
    private fun buildPayload(
        request: AiChatRequest,
        config: AiResolvedConfig,
        stream: Boolean,
        includeTemperature: Boolean
    ): String = buildJsonObject {
        put("model", config.model)
        if (stream) put("stream", true)
        // Responses 的输入是 input 数组（role/content），不接受 messages
        put("input", JsonArray(listOf(systemInput(request), userInput(request))))
        put("max_output_tokens", request.maxOutputTokens ?: DEFAULT_MAX_OUTPUT_TOKENS)
        if (includeTemperature) {
            put("temperature", request.temperature ?: DEFAULT_TEMPERATURE)
        }
        if (request.jsonMode) {
            // Responses 的 JSON 模式在 text.format，不是 chat 的 response_format
            put(
                "text",
                buildJsonObject {
                    put("format", buildJsonObject { put("type", "json_object") })
                }
            )
        }
    }.toString()

    private fun systemInput(request: AiChatRequest) = buildJsonObject {
        put("role", "system")
        put("content", request.system)
    }

    private fun userInput(request: AiChatRequest) = buildJsonObject {
        put("role", "user")
        put("content", request.user)
    }

    private fun buildResponsesUrl(baseUrl: String) = "${baseUrl.trimEnd('/')}/responses"

    // ---------- 响应解析 ----------

    /**
     * 提取输出文本：
     * 1) 优先顶层的 `output_text` 便捷字段（官方 SDK 也会回填）；
     * 2) 否则遍历 `output[]` 里 type 为 message 的条目，拼其 content[] 中 output_text 片段的 text。
     * 两处都取不到返回 null，由调用方抛 INVALID_RESPONSE。
     */
    private fun extractOutputText(root: JsonObject): String? {
        AiProviderErrorMapper.stringField(root, "output_text")
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }
        val output = root["output"] as? JsonArray ?: return null
        val builder = StringBuilder()
        for (element in output) {
            val item = element as? JsonObject ?: continue
            if (AiProviderErrorMapper.stringField(item, "type") != OUTPUT_MESSAGE_TYPE) continue
            val content = item["content"] as? JsonArray ?: continue
            for (part in content) {
                val partObject = part as? JsonObject ?: continue
                if (AiProviderErrorMapper.stringField(partObject, "type") != OUTPUT_TEXT_TYPE) continue
                AiProviderErrorMapper.stringField(partObject, "text")?.let { builder.append(it) }
            }
        }
        return builder.toString().takeIf { it.isNotBlank() }
    }

    /**
     * 构造流式失败异常：response.failed 的错误在 response.error.message，
     * error 事件的错误在 error.message；都取不到就用原始 JSON 片段兜底。
     * 这里统一归到 SERVER（生成侧失败），具体原因保留 Provider 原文。
     */
    private fun streamFailure(event: JsonObject, rawData: String): AiProviderException {
        val responseError = (event["response"] as? JsonObject)?.get("error") as? JsonObject
        val directError = event["error"] as? JsonObject
        val message = AiProviderErrorMapper.stringField(responseError, "message")
            ?.takeIf { it.isNotBlank() }
            ?: AiProviderErrorMapper.stringField(directError, "message")
                ?.takeIf { it.isNotBlank() }
            ?: rawData.take(200)
        return AiProviderException(AiProviderErrorCode.SERVER, message)
    }

    /**
     * 判断 400 是否是「模型不接受 temperature」。
     * 判定规则统一收在 [AiProviderErrorMapper.mentionsTemperature]：
     * 必须同时出现 temperature 与 unsupported / not supported 之类字样，
     * 单看 temperature 子串会误伤其他提及该字段的 400。
     */
    private fun isTemperatureRejection(body: String): Boolean =
        AiProviderErrorMapper.mentionsTemperature(body)

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
            .header("Authorization", "Bearer ${config.apiKey}")
            .header("Content-Type", "application/json")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()
        return withContext(Dispatchers.IO) {
            runCatching { client.newCall(request).execute() }
                .getOrElse { error -> throw AiProviderErrorMapper.fromNetworkError(error) }
        }
    }

    /** 一次性读完状态码与响应体（非流式路径用，避免 Response 被提前关闭）。 */
    private suspend fun postForText(url: String, payload: String, config: AiResolvedConfig): HttpExchange {
        val response = executeRequest(url, payload, config)
        return response.use { resp ->
            val bodyText = resp.body?.string()
                ?: throw AiProviderException(
                    AiProviderErrorCode.INVALID_RESPONSE,
                    "Empty response body"
                )
            HttpExchange(code = resp.code, body = bodyText)
        }
    }

    private fun parseObject(text: String, errorCode: AiProviderErrorCode): JsonObject =
        runCatching { lenientJson.parseToJsonElement(text).jsonObject }.getOrElse { error ->
            throw AiProviderException(
                errorCode,
                "Unparseable provider response: ${error.message}"
            )
        }

    /** 已完成读取的 HTTP 交换结果：状态码 + 响应体文本。 */
    private data class HttpExchange(val code: Int, val body: String)

    companion object {
        private const val DEFAULT_TEMPERATURE = 0.7
        private const val DEFAULT_MAX_OUTPUT_TOKENS = 4096
        private const val SSE_DATA_PREFIX = "data:"
        private const val SSE_DONE_MARKER = "[DONE]"

        /** 流式事件类型（Responses 协议）。 */
        private const val EVENT_OUTPUT_TEXT_DELTA = "response.output_text.delta"
        private const val EVENT_COMPLETED = "response.completed"
        private const val EVENT_FAILED = "response.failed"
        private const val EVENT_ERROR = "error"

        /** 输出条目里的类型标记。 */
        private const val OUTPUT_MESSAGE_TYPE = "message"
        private const val OUTPUT_TEXT_TYPE = "output_text"

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
