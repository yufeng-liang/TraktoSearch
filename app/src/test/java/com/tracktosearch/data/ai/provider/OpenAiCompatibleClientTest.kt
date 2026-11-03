package com.tracktosearch.data.ai.provider

import android.app.Application
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * OpenAI 兼容适配器单测：请求形状（URL/头/body）、非流式与流式解析、
 * jsonMode 400 回退重试、TTS/配图端点、错误映射。
 *
 * 配图路径在主源码与测试两端都用到 android.util.Base64（android 框架类，
 * 纯 JVM 单测没有且 testOptions 未开 returnDefaultValues），所以挂 Robolectric。
 */
@RunWith(RobolectricTestRunner::class)
// application 必须显式指定：不写默认会加载真 App 类（TraktSearchApp → Hilt → sqlcipher），
// 本机没有 sqlcipher 原生库会让整类 UnsatisfiedLinkError；这里只用到 android.util.Base64。
@Config(sdk = [33], application = Application::class)
class OpenAiCompatibleClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: OpenAiCompatibleClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = OpenAiCompatibleClient()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    /** AiResolvedConfig 字段全 val，setUp 后才知道端口，只能每个测试整体重建。 */
    private fun makeConfig(): AiResolvedConfig = AiResolvedConfig(
        protocol = AiProviderProtocol.OPENAI_COMPATIBLE,
        baseUrl = server.url("/v1").toString().trimEnd('/'),
        apiKey = "sk-test",
        model = "gpt-test"
    )

    private fun chatResponse(content: String) = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody(
            """{"id":"c1","model":"gpt-test","choices":[
               |{"index":0,"message":{"role":"assistant","content":"$content"},"finish_reason":"stop"}
               |],"usage":{"total_tokens":10}}""".trimMargin()
        )

    @Test
    fun complete_sendsCorrectUrlHeadersAndBody() = runTest {
        server.enqueue(chatResponse("hello"))

        val response = client.complete(
            AiChatRequest(system = "sys", user = "user text", temperature = 0.5, maxOutputTokens = 100),
            makeConfig()
        )

        assertThat(response.text).isEqualTo("hello")
        assertThat(response.model).isEqualTo("gpt-test")
        val request = server.takeRequest()
        assertThat(request.method).isEqualTo("POST")
        assertThat(request.path).isEqualTo("/v1/chat/completions")
        assertThat(request.getHeader("Authorization")).isEqualTo("Bearer sk-test")
        val body = request.body.readUtf8()
        assertThat(body).contains("\"model\":\"gpt-test\"")
        assertThat(body).contains("\"role\":\"system\"")
        assertThat(body).contains("\"role\":\"user\"")
        assertThat(body).contains("\"temperature\":0.5")
        assertThat(body).contains("\"max_tokens\":100")
        assertThat(body).contains("\"stream\":false")
        assertThat(body).doesNotContain("response_format")
    }

    @Test
    fun complete_jsonModeAddsResponseFormat() = runTest {
        server.enqueue(chatResponse("{}"))

        client.complete(AiChatRequest(system = "s", user = "u", jsonMode = true), makeConfig())

        val body = server.takeRequest().body.readUtf8()
        assertThat(body).contains("\"response_format\":{\"type\":\"json_object\"}")
    }

    @Test
    fun complete_jsonMode400FallsBackWithoutResponseFormat() = runTest {
        // 第一次带 response_format 被 400 拒绝，回退重试一次
        server.enqueue(
            MockResponse()
                .setResponseCode(400)
                .setBody("""{"error":{"message":"response_format not supported"}}""")
        )
        // 响应体是 JSON 字符串，content 里的引号必须再转义一层，否则 MockWebServer 返回的就是坏 JSON
        server.enqueue(chatResponse("""{\"a\":1}"""))

        val response = client.complete(
            AiChatRequest(system = "s", user = "u", jsonMode = true),
            makeConfig()
        )

        assertThat(response.text).isEqualTo("{\"a\":1}")
        // 第一次请求带 response_format，第二次不带且 system 里追加了 JSON 指令
        val first = server.takeRequest()
        assertThat(first.body.readUtf8()).contains("response_format")
        val second = server.takeRequest()
        val secondBody = second.body.readUtf8()
        assertThat(secondBody).doesNotContain("response_format")
        assertThat(secondBody).contains("仅输出 JSON")
    }

    @Test
    fun stream_emitsThreeDeltasThenDoneWithJoinedText() = runTest {
        val sseBody = buildString {
            append("data: {\"choices\":[{\"delta\":{\"content\":\"你\"}}]}\n\n")
            append("data: {\"choices\":[{\"delta\":{\"content\":\"好\"}}]}\n\n")
            append("data: {\"choices\":[{\"delta\":{\"content\":\"呀\"}}]}\n\n")
            append("data: [DONE]\n\n")
        }
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody(sseBody)
        )

        val chunks = mutableListOf<AiStreamChunk>()
        client.stream(AiChatRequest(system = "s", user = "u"), makeConfig()).collect { chunks.add(it) }

        val deltas = chunks.filterIsInstance<AiStreamChunk.Delta>()
        assertThat(deltas.map { it.text }).containsExactly("你", "好", "呀").inOrder()
        val done = chunks.filterIsInstance<AiStreamChunk.Done>().single()
        assertThat(done.fullText).isEqualTo("你好呀")
        // 流式请求体必须带 stream=true
        assertThat(server.takeRequest().body.readUtf8()).contains("\"stream\":true")
    }

    @Test
    fun complete_401MapsToAuthErrorWithProviderMessage() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setBody("""{"error":{"message":"Incorrect API key"}}""")
        )

        val error = runCatching {
            client.complete(AiChatRequest(system = "s", user = "u"), makeConfig())
        }.exceptionOrNull() as AiProviderException

        assertThat(error.code).isEqualTo(AiProviderErrorCode.PROVIDER_AUTH)
        assertThat(error.httpStatus).isEqualTo(401)
        assertThat(error.message).isEqualTo("Incorrect API key")
    }

    @Test
    fun complete_429MapsToRateLimited() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(429)
                .setBody("""{"error":{"message":"Rate limit reached"}}""")
        )

        val error = runCatching {
            client.complete(AiChatRequest(system = "s", user = "u"), makeConfig())
        }.exceptionOrNull() as AiProviderException

        assertThat(error.code).isEqualTo(AiProviderErrorCode.RATE_LIMITED)
        assertThat(error.message).isEqualTo("Rate limit reached")
    }

    @Test
    fun complete_500MapsToServerError() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(500)
                .setBody("""{"error":{"message":"server exploded"}}""")
        )

        val error = runCatching {
            client.complete(AiChatRequest(system = "s", user = "u"), makeConfig())
        }.exceptionOrNull() as AiProviderException

        assertThat(error.code).isEqualTo(AiProviderErrorCode.SERVER)
        assertThat(error.httpStatus).isEqualTo(500)
    }

    @Test
    fun synthesizeSpeech_returnsAudioBytes() = runTest {
        val audioBytes = byteArrayOf(0x49, 0x44, 0x33, 0x04) // "ID3" 开头模拟 mp3
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "audio/mpeg")
                .setBody(okio.Buffer().write(audioBytes))
        )

        val result = client.synthesizeSpeech(AiTtsRequest(text = "你好", voice = "alloy"), makeConfig())

        assertThat(result).isEqualTo(audioBytes)
        val request = server.takeRequest()
        assertThat(request.path).isEqualTo("/v1/audio/speech")
        val body = request.body.readUtf8()
        assertThat(body).contains("\"model\":\"gpt-test\"")
        assertThat(body).contains("\"input\":\"你好\"")
        assertThat(body).contains("\"voice\":\"alloy\"")
        assertThat(body).contains("\"response_format\":\"mp3\"")
    }

    @Test
    fun generateImage_decodesB64Json() = runTest {
        val rawBytes = byteArrayOf(1, 2, 3, 4, 5)
        val b64 = android.util.Base64.encodeToString(rawBytes, android.util.Base64.DEFAULT)
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"created":1,"data":[{"b64_json":"$b64"}]}""")
        )

        val result = client.generateImage(
            AiImageRequest(prompt = "a cat", size = "1024x1024"),
            makeConfig()
        )

        assertThat(result).isEqualTo(rawBytes)
        val request = server.takeRequest()
        assertThat(request.path).isEqualTo("/v1/images/generations")
        val body = request.body.readUtf8()
        assertThat(body).contains("\"model\":\"gpt-test\"")
        assertThat(body).contains("\"prompt\":\"a cat\"")
        assertThat(body).contains("\"size\":\"1024x1024\"")
        assertThat(body).contains("\"response_format\":\"b64_json\"")
    }

    @Test
    fun generateImage_missingB64ThrowsInvalidResponse() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"created":1,"data":[{}]}""")
        )

        val error = runCatching {
            client.generateImage(AiImageRequest(prompt = "p"), makeConfig())
        }.exceptionOrNull() as AiProviderException

        assertThat(error.code).isEqualTo(AiProviderErrorCode.INVALID_RESPONSE)
    }
}
