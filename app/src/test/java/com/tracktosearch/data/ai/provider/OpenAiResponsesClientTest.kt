package com.tracktosearch.data.ai.provider

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * OpenAI Responses 协议适配器单测：请求形状（/responses、input 数组、max_output_tokens、
 * text.format、无 messages）、output_text 与 output[] 两条文本提取路径、
 * temperature 400 重试（非流式与流式）、SSE 事件分发、错误映射、模型目录委托。
 */
class OpenAiResponsesClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: OpenAiResponsesClient

    private fun makeConfig(): AiResolvedConfig = AiResolvedConfig(
        // 协议枚举里暂时没有 OPENAI_RESPONSES 值，这里用兼容值占位：
        // 适配器本身不读取 protocol 字段，只关心 baseUrl / apiKey / model
        protocol = AiProviderProtocol.OPENAI_COMPATIBLE,
        baseUrl = server.url("/v1").toString().trimEnd('/'),
        apiKey = "sk-test",
        model = "gpt-test"
    )

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = OpenAiResponsesClient()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun jsonResponse(body: String) = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    private fun sseResponse(body: String) = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "text/event-stream")
        .setBody(body)

    private fun sse(vararg events: String): String = buildString {
        events.forEach { append("data: ").append(it).append("\n\n") }
    }

    @Test
    fun complete_sendsResponsesPayloadAndParsesOutputText() = runTest {
        server.enqueue(jsonResponse("""{"model":"gpt-5","output_text":"ok"}"""))

        val response = client.complete(
            AiChatRequest(
                system = "sys",
                user = "user text",
                temperature = 0.5,
                maxOutputTokens = 100,
                jsonMode = true
            ),
            makeConfig()
        )

        assertThat(response.text).isEqualTo("ok")
        assertThat(response.model).isEqualTo("gpt-5")

        val request = server.takeRequest()
        assertThat(request.method).isEqualTo("POST")
        assertThat(request.path).isEqualTo("/v1/responses")
        assertThat(request.getHeader("Authorization")).isEqualTo("Bearer sk-test")
        val body = request.body.readUtf8()
        assertThat(body).contains("\"model\":\"gpt-test\"")
        assertThat(body).contains("\"input\"")
        assertThat(body).contains("\"role\":\"system\"")
        assertThat(body).contains("\"role\":\"user\"")
        assertThat(body).contains("\"max_output_tokens\":100")
        assertThat(body).contains("\"temperature\":0.5")
        assertThat(body).contains("\"text\":{\"format\":{\"type\":\"json_object\"}}")
        // Responses 协议不使用 chat 的字段
        assertThat(body).doesNotContain("\"messages\"")
        assertThat(body).doesNotContain("\"response_format\"")
        assertThat(body).doesNotContain("\"max_tokens\"")
        // 非流式请求不带 stream
        assertThat(body).doesNotContain("\"stream\"")
    }

    @Test
    fun complete_fallsBackToOutputArrayWhenOutputTextMissing() = runTest {
        server.enqueue(
            jsonResponse(
                """{"model":"gpt-5","output":[
                   |{"type":"reasoning","summary":[]},
                   |{"type":"message","role":"assistant","content":[
                   |  {"type":"output_text","text":"你好"},
                   |  {"type":"output_text","text":"世界"},
                   |  {"type":"refusal","refusal":"x"}
                   |]}],"usage":{"total_tokens":12}}""".trimMargin()
            )
        )

        val response = client.complete(AiChatRequest(system = "s", user = "u"), makeConfig())

        assertThat(response.text).isEqualTo("你好世界")
        assertThat(response.model).isEqualTo("gpt-5")
    }

    @Test
    fun complete_withoutAnyOutputTextThrowsInvalidResponse() = runTest {
        server.enqueue(jsonResponse("""{"model":"gpt-5","output":[]}"""))

        val error = runCatching {
            client.complete(AiChatRequest(system = "s", user = "u"), makeConfig())
        }.exceptionOrNull() as AiProviderException

        assertThat(error.code).isEqualTo(AiProviderErrorCode.INVALID_RESPONSE)
        assertThat(error.message).isEqualTo("No output text in response")
    }

    @Test
    fun complete_temperature400RetriesWithoutTemperature() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(400)
                .setBody(
                    """{"error":{"message":"Unsupported parameter: 'temperature' is not supported with this model."}}"""
                )
        )
        server.enqueue(jsonResponse("""{"model":"o3","output_text":"重试成功"}"""))

        val response = client.complete(
            AiChatRequest(system = "s", user = "u", temperature = 1.0, maxOutputTokens = 64),
            makeConfig()
        )

        assertThat(response.text).isEqualTo("重试成功")
        assertThat(response.model).isEqualTo("o3")
        assertThat(server.requestCount).isEqualTo(2)

        val first = server.takeRequest().body.readUtf8()
        assertThat(first).contains("\"temperature\":1.0")

        val second = server.takeRequest().body.readUtf8()
        assertThat(second).doesNotContain("temperature")
        // 其余字段不变，保证重试语义等价
        assertThat(second).contains("\"model\":\"gpt-test\"")
        assertThat(second).contains("\"input\"")
        assertThat(second).contains("\"max_output_tokens\":64")
    }

    @Test
    fun complete_other400DoesNotRetry() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(400)
                .setBody("""{"error":{"message":"max_output_tokens is too large"}}""")
        )

        val error = runCatching {
            client.complete(AiChatRequest(system = "s", user = "u"), makeConfig())
        }.exceptionOrNull() as AiProviderException

        assertThat(error.code).isEqualTo(AiProviderErrorCode.INVALID_REQUEST)
        assertThat(error.httpStatus).isEqualTo(400)
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun stream_emitsDeltasThenDoneWithJoinedText() = runTest {
        server.enqueue(
            sseResponse(
                sse(
                    """{"type":"response.created","response":{"id":"r1"}}""",
                    """{"type":"response.output_text.delta","delta":"你"}""",
                    """{"type":"response.output_text.delta","delta":"好"}""",
                    """{"type":"response.completed","response":{"id":"r1"}}"""
                )
            )
        )

        val chunks = mutableListOf<AiStreamChunk>()
        client.stream(AiChatRequest(system = "s", user = "u"), makeConfig())
            .collect { chunks.add(it) }

        val deltas = chunks.filterIsInstance<AiStreamChunk.Delta>()
        assertThat(deltas.map { it.text }).containsExactly("你", "好").inOrder()
        val done = chunks.filterIsInstance<AiStreamChunk.Done>().single()
        assertThat(done.fullText).isEqualTo("你好")

        val request = server.takeRequest()
        assertThat(request.path).isEqualTo("/v1/responses")
        assertThat(request.body.readUtf8()).contains("\"stream\":true")
    }

    @Test
    fun stream_responseFailedThrowsAiProviderException() = runTest {
        server.enqueue(
            sseResponse(
                sse(
                    """{"type":"response.output_text.delta","delta":"半"}""",
                    """{"type":"response.failed","response":{"error":{"code":"server_error","message":"model overloaded"}}}"""
                )
            )
        )

        val error = runCatching {
            client.stream(AiChatRequest(system = "s", user = "u"), makeConfig())
                .collect { }
        }.exceptionOrNull() as AiProviderException

        assertThat(error.code).isEqualTo(AiProviderErrorCode.SERVER)
        assertThat(error.message).isEqualTo("model overloaded")
    }

    @Test
    fun stream_temperature400ReopensStreamWithoutTemperature() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(400)
                .setBody("""{"error":{"message":"temperature is not supported for this model"}}""")
        )
        server.enqueue(
            sseResponse(
                sse(
                    """{"type":"response.output_text.delta","delta":"重开"}""",
                    """{"type":"response.completed","response":{"id":"r2"}}"""
                )
            )
        )

        val chunks = mutableListOf<AiStreamChunk>()
        client.stream(AiChatRequest(system = "s", user = "u"), makeConfig())
            .collect { chunks.add(it) }

        val deltas = chunks.filterIsInstance<AiStreamChunk.Delta>()
        assertThat(deltas.map { it.text }).containsExactly("重开")
        assertThat(chunks.filterIsInstance<AiStreamChunk.Done>().single().fullText).isEqualTo("重开")

        assertThat(server.requestCount).isEqualTo(2)
        assertThat(server.takeRequest().body.readUtf8()).contains("\"temperature\"")
        assertThat(server.takeRequest().body.readUtf8()).doesNotContain("temperature")
    }

    @Test
    fun listModels_parsesDataArrayViaCompatiblePath() = runTest {
        server.enqueue(
            jsonResponse(
                """{"object":"list","data":[{"id":"gpt-5"},{"id":"o3","display_name":"o3 推理"}]}"""
            )
        )

        val models = client.listModels(makeConfig())

        assertThat(models.map { it.id }).containsExactly("gpt-5", "o3").inOrder()
        assertThat(models.first { it.id == "o3" }.displayName).isEqualTo("o3 推理")
        val request = server.takeRequest()
        assertThat(request.method).isEqualTo("GET")
        assertThat(request.path).isEqualTo("/v1/models")
        assertThat(request.getHeader("Authorization")).isEqualTo("Bearer sk-test")
    }
}
