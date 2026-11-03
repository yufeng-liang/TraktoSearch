package com.tracktosearch.data.ai.provider

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Gemini 原生适配器单测：URL 形状（含 key 查询参数与 alt=sse）、
 * 请求体（system_instruction/contents/generationConfig/response_mime_type）、
 * 流式解析（candidates[0].content.parts[*].text + finishReason 终止）、
 * NO_TTS / NO_IMAGE 设计定案。
 */
class GeminiClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: GeminiClient

    // setUp 里才知道 MockWebServer 的地址，AiResolvedConfig 字段全 val，只能整体重建
    private var config = AiResolvedConfig(
        protocol = AiProviderProtocol.GEMINI,
        baseUrl = "",
        apiKey = "gemini-key",
        model = "gemini-test"
    )

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        config = config.copy(baseUrl = server.url("/").toString().trimEnd('/'))
        client = GeminiClient()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun generateContentResponse(text: String) = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody(
            """{"modelVersion":"gemini-test","candidates":[
               |{"content":{"parts":[{"text":"$text"}],"role":"model"},
               | "finishReason":"STOP","index":0}
               |],"usageMetadata":{"totalTokenCount":10}}""".trimMargin()
        )

    @Test
    fun complete_sendsCorrectUrlAndBodyShape() = runTest {
        server.enqueue(generateContentResponse("hi"))

        val response = client.complete(
            AiChatRequest(system = "be nice", user = "hello", temperature = 0.2, maxOutputTokens = 256),
            config
        )

        assertThat(response.text).isEqualTo("hi")
        assertThat(response.model).isEqualTo("gemini-test")
        val request = server.takeRequest()
        assertThat(request.method).isEqualTo("POST")
        // 非流式：v1beta/models/{model}:generateContent，Key 走请求头而不是 URL
        assertThat(request.path)
            .isEqualTo("/v1beta/models/gemini-test:generateContent")
        assertThat(request.getHeader("x-goog-api-key")).isEqualTo("gemini-key")
        val body = request.body.readUtf8()
        assertThat(body).contains("\"system_instruction\":{\"parts\":[{\"text\":\"be nice\"}]}")
        assertThat(body).contains("\"role\":\"user\"")
        assertThat(body).contains("\"temperature\":0.2")
        assertThat(body).contains("\"maxOutputTokens\":256")
        assertThat(body).doesNotContain("response_mime_type")
    }

    @Test
    fun complete_jsonModeSetsResponseMimeType() = runTest {
        server.enqueue(generateContentResponse("{}"))

        client.complete(AiChatRequest(system = "s", user = "u", jsonMode = true), config)

        val body = server.takeRequest().body.readUtf8()
        assertThat(body).contains("\"response_mime_type\":\"application/json\"")
    }

    @Test
    fun stream_emitsDeltasThenDoneAndUsesSseUrl() = runTest {
        // Gemini SSE：每行 data: 一个 chunk，finishReason 出现在最后一个 chunk
        val sseBody = buildString {
            append("data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"早\"}]}}]}\n\n")
            append("data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"安\"}]}}]}\n\n")
            append("data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"!\"}]},\"finishReason\":\"STOP\"}]}\n\n")
        }
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody(sseBody)
        )

        val chunks = mutableListOf<AiStreamChunk>()
        client.stream(AiChatRequest(system = "s", user = "u"), config).collect { chunks.add(it) }

        val deltas = chunks.filterIsInstance<AiStreamChunk.Delta>()
        assertThat(deltas.map { it.text }).containsExactly("早", "安", "!").inOrder()
        val done = chunks.filterIsInstance<AiStreamChunk.Done>().single()
        assertThat(done.fullText).isEqualTo("早安!")
        // 流式必须走 :streamGenerateContent?alt=sse（Key 在请求头）
        val streamRequest = server.takeRequest()
        assertThat(streamRequest.path)
            .isEqualTo("/v1beta/models/gemini-test:streamGenerateContent?alt=sse")
        assertThat(streamRequest.getHeader("x-goog-api-key")).isEqualTo("gemini-key")
    }

    @Test
    fun stream_finishReasonStopsCollection() = runTest {
        // finishReason 之后还有 data 行也不该收进来
        val sseBody = buildString {
            append("data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"ok\"}]},\"finishReason\":\"STOP\"}]}\n\n")
            append("data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"AFTER\"}]}}]}\n\n")
        }
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody(sseBody)
        )

        val chunks = mutableListOf<AiStreamChunk>()
        client.stream(AiChatRequest(system = "s", user = "u"), config).collect { chunks.add(it) }

        assertThat(chunks.filterIsInstance<AiStreamChunk.Delta>().map { it.text })
            .containsExactly("ok")
        assertThat(chunks.filterIsInstance<AiStreamChunk.Done>().single().fullText).isEqualTo("ok")
    }

    @Test
    fun complete_skipsThoughtParts() = runTest {
        // 开思考的模型会把推理过程也放进 parts（thought: true），
        // 拼进正文会让下游 JSON 解析直接失败，必须跳过
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"candidates":[{"content":{"parts":[
                       |{"text":"让我想想…","thought":true},
                       |{"text":"{\"a\":1}"}
                       |],"role":"model"},"finishReason":"STOP"}]}""".trimMargin()
                )
        )

        val response = client.complete(AiChatRequest(system = "s", user = "u"), config)

        assertThat(response.text).isEqualTo("""{"a":1}""")
    }

    @Test
    fun listModels_usesHeaderKeyAndStripsPrefix() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"models":[
                       |{"name":"models/gemini-2.0-flash","displayName":"Gemini 2.0 Flash"},
                       |{"name":"models/text-embedding-004"}
                       |]}""".trimMargin()
                )
        )

        val models = client.listModels(config)

        assertThat(models.map { it.id }).containsExactly("gemini-2.0-flash", "text-embedding-004")
        val request = server.takeRequest()
        assertThat(request.path).isEqualTo("/v1beta/models")
        assertThat(request.getHeader("x-goog-api-key")).isEqualTo("gemini-key")
    }

    @Test
    fun complete_401MapsToAuthError() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setBody("""{"error":{"code":401,"message":"API key not valid","status":"UNAUTHENTICATED"}}""")
        )

        val error = runCatching {
            client.complete(AiChatRequest(system = "s", user = "u"), config)
        }.exceptionOrNull() as AiProviderException

        assertThat(error.code).isEqualTo(AiProviderErrorCode.PROVIDER_AUTH)
        assertThat(error.message).isEqualTo("API key not valid")
    }

    @Test
    fun synthesizeSpeech_throwsNoTts() = runTest {
        val error = runCatching {
            client.synthesizeSpeech(AiTtsRequest(text = "t", voice = "v"), config)
        }.exceptionOrNull() as AiProviderException

        assertThat(error.code).isEqualTo(AiProviderErrorCode.PROVIDER_NO_TTS)
        // 不应发出任何网络请求
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun generateImage_throwsNoImage() = runTest {
        val error = runCatching {
            client.generateImage(AiImageRequest(prompt = "p"), config)
        }.exceptionOrNull() as AiProviderException

        assertThat(error.code).isEqualTo(AiProviderErrorCode.PROVIDER_NO_IMAGE)
        assertThat(server.requestCount).isEqualTo(0)
    }
}
