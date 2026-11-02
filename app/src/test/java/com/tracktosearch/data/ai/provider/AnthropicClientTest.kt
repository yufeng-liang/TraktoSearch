package com.tracktosearch.data.ai.provider

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Anthropic 原生适配器单测：URL/头/body 形状、jsonMode system 追加、
 * 流式 content_block_delta + message_stop 终止、错误映射、NO_TTS/NO_IMAGE。
 */
class AnthropicClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: AnthropicClient

    private fun makeConfig(baseUrl: String) = AiResolvedConfig(
        protocol = AiProviderProtocol.ANTHROPIC,
        baseUrl = baseUrl,
        apiKey = "sk-ant-test",
        model = "claude-test"
    )

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = AnthropicClient()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun chatResponse(text: String) = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody(
            """{"id":"msg_1","model":"claude-test","content":[
               |{"type":"text","text":"$text"}
               |],"stop_reason":"end_turn","usage":{"output_tokens":10}}""".trimMargin()
        )

    @Test
    fun complete_sendsCorrectUrlHeadersAndBody() = runTest {
        server.enqueue(chatResponse("hi there"))

        val response = client.complete(
            AiChatRequest(system = "be terse", user = "hello", temperature = 0.3, maxOutputTokens = 200),
            makeConfig(server.url("/").toString().trimEnd('/'))
        )

        assertThat(response.text).isEqualTo("hi there")
        assertThat(response.model).isEqualTo("claude-test")
        val request = server.takeRequest()
        assertThat(request.method).isEqualTo("POST")
        assertThat(request.path).isEqualTo("/v1/messages")
        assertThat(request.getHeader("x-api-key")).isEqualTo("sk-ant-test")
        assertThat(request.getHeader("anthropic-version")).isEqualTo("2023-06-01")
        val body = request.body.readUtf8()
        assertThat(body).contains("\"model\":\"claude-test\"")
        assertThat(body).contains("\"system\":\"be terse\"")
        assertThat(body).contains("\"max_tokens\":200")
        assertThat(body).contains("\"temperature\":0.3")
        assertThat(body).contains("\"stream\":false")
        assertThat(body).contains("\"role\":\"user\"")
    }

    @Test
    fun complete_jsonModeAppendsJsonInstructionToSystem() = runTest {
        server.enqueue(chatResponse("{}"))

        client.complete(
            AiChatRequest(system = "be terse", user = "u", jsonMode = true),
            makeConfig(server.url("/").toString().trimEnd('/'))
        )

        val body = server.takeRequest().body.readUtf8()
        // Anthropic 没有 response_format，jsonMode 通过 system 末尾追加实现
        assertThat(body).doesNotContain("response_format")
        assertThat(body).contains("Respond with valid JSON only.")
    }

    @Test
    fun stream_emitsThreeDeltasThenDoneAndUsesSseHeaders() = runTest {
        val sseBody = buildString {
            append("event: content_block_delta\n")
            append("data: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"早\"}}\n\n")
            append("event: content_block_delta\n")
            append("data: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"安\"}}\n\n")
            append("event: content_block_delta\n")
            append("data: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"~\"}}\n\n")
            append("event: message_stop\n")
            append("data: {\"type\":\"message_stop\"}\n\n")
        }
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody(sseBody)
        )

        val chunks = mutableListOf<AiStreamChunk>()
        client.stream(AiChatRequest(system = "s", user = "u"), makeConfig(server.url("/").toString().trimEnd('/')))
            .collect { chunks.add(it) }

        val deltas = chunks.filterIsInstance<AiStreamChunk.Delta>()
        assertThat(deltas.map { it.text }).containsExactly("早", "安", "~").inOrder()
        val done = chunks.filterIsInstance<AiStreamChunk.Done>().single()
        assertThat(done.fullText).isEqualTo("早安~")
        val request = server.takeRequest()
        assertThat(request.path).isEqualTo("/v1/messages")
        assertThat(request.getHeader("anthropic-version")).isEqualTo("2023-06-01")
        assertThat(request.body.readUtf8()).contains("\"stream\":true")
    }

    @Test
    fun complete_401MapsToAuthError() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setBody("""{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}""")
        )

        val error = runCatching {
            client.complete(AiChatRequest(system = "s", user = "u"), makeConfig(server.url("/").toString().trimEnd('/')))
        }.exceptionOrNull() as AiProviderException

        assertThat(error.code).isEqualTo(AiProviderErrorCode.PROVIDER_AUTH)
        assertThat(error.message).isEqualTo("invalid x-api-key")
    }

    @Test
    fun complete_429MapsToRateLimited() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(429)
                .setBody("""{"type":"error","error":{"type":"rate_limit_error","message":"Number of requests too high"}}""")
        )

        val error = runCatching {
            client.complete(AiChatRequest(system = "s", user = "u"), makeConfig(server.url("/").toString().trimEnd('/')))
        }.exceptionOrNull() as AiProviderException

        assertThat(error.code).isEqualTo(AiProviderErrorCode.RATE_LIMITED)
        assertThat(error.message).isEqualTo("Number of requests too high")
    }

    @Test
    fun complete_500MapsToServerError() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(500)
                .setBody("""{"type":"error","error":{"type":"api_error","message":"Internal server error"}}""")
        )

        val error = runCatching {
            client.complete(AiChatRequest(system = "s", user = "u"), makeConfig(server.url("/").toString().trimEnd('/')))
        }.exceptionOrNull() as AiProviderException

        assertThat(error.code).isEqualTo(AiProviderErrorCode.SERVER)
        assertThat(error.httpStatus).isEqualTo(500)
    }

    @Test
    fun synthesizeSpeech_throwsNoTts() = runTest {
        val error = runCatching {
            client.synthesizeSpeech(AiTtsRequest(text = "t", voice = "v"), makeConfig(server.url("/").toString().trimEnd('/')))
        }.exceptionOrNull() as AiProviderException

        assertThat(error.code).isEqualTo(AiProviderErrorCode.PROVIDER_NO_TTS)
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun generateImage_throwsNoImage() = runTest {
        val error = runCatching {
            client.generateImage(AiImageRequest(prompt = "p"), makeConfig(server.url("/").toString().trimEnd('/')))
        }.exceptionOrNull() as AiProviderException

        assertThat(error.code).isEqualTo(AiProviderErrorCode.PROVIDER_NO_IMAGE)
        assertThat(server.requestCount).isEqualTo(0)
    }
}
