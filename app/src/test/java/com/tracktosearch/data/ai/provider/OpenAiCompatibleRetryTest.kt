package com.tracktosearch.data.ai.provider

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

/**
 * OpenAI 兼容适配器的两级降级重试单测（纯 JVM，不挂 Robolectric）：
 * 1) reasoning 模型（o 系列 / gpt-5 起）拒绝 temperature → 去掉该字段重发一次；
 * 2) 兼容端不认 response_format → 退回纯文本请求。
 *
 * 单独成类的原因：既有的 OpenAiCompatibleClientTest 为覆盖配图的 Base64 走了
 * Robolectric，整类加载会因缺失 sqlcipher 原生库而失败；这两条重试逻辑不碰
 * Android 框架类，用纯 JUnit 就能真跑。
 */
class OpenAiCompatibleRetryTest {

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

    private fun makeConfig(): AiResolvedConfig = AiResolvedConfig(
        protocol = AiProviderProtocol.OPENAI_COMPATIBLE,
        baseUrl = server.url("/v1").toString().trimEnd('/'),
        apiKey = "sk-test",
        model = "gpt-5"
    )

    private fun jsonResponse(body: String, code: Int = 200) = MockResponse()
        .setResponseCode(code)
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    private fun sseResponse(body: String) = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "text/event-stream")
        .setBody(body)

    private fun temperatureRejection() = jsonResponse(
        """{"error":{"message":"Unsupported parameter: 'temperature' is not supported with this model."}}""",
        code = 400
    )

    @Test
    fun complete_temperatureRejected_retriesWithoutTemperature() = runTest {
        server.enqueue(temperatureRejection())
        server.enqueue(
            jsonResponse("""{"model":"gpt-5","choices":[{"message":{"content":"ok"}}]}""")
        )

        val response = client.complete(
            AiChatRequest(system = "s", user = "u", temperature = 0.7),
            makeConfig()
        )

        assertThat(response.text).isEqualTo("ok")
        assertThat(server.requestCount).isEqualTo(2)

        val first = server.takeRequest().body.readUtf8()
        assertThat(first).contains("\"temperature\"")
        val second = server.takeRequest().body.readUtf8()
        assertThat(second).doesNotContain("\"temperature\"")
        // 其余字段必须原样保留，重试语义才等价
        assertThat(second).contains("\"model\":\"gpt-5\"")
        assertThat(second).contains("\"messages\"")
    }

    @Test
    fun stream_temperatureRejected_reopensStreamWithoutTemperature() = runTest {
        server.enqueue(temperatureRejection())
        server.enqueue(
            sseResponse(
                "data: " + """{"choices":[{"delta":{"content":"你"}}]}""" + "\n\n" +
                    "data: " + """{"choices":[{"delta":{"content":"好"}}]}""" + "\n\n" +
                    "data: [DONE]\n\n"
            )
        )

        val chunks = mutableListOf<AiStreamChunk>()
        client.stream(AiChatRequest(system = "s", user = "u"), makeConfig()).collect { chunks.add(it) }

        val deltas = chunks.filterIsInstance<AiStreamChunk.Delta>().map { it.text }
        assertThat(deltas).containsExactly("你", "好").inOrder()
        assertThat(chunks.last()).isInstanceOf(AiStreamChunk.Done::class.java)
        assertThat((chunks.last() as AiStreamChunk.Done).fullText).isEqualTo("你好")

        assertThat(server.requestCount).isEqualTo(2)
        server.takeRequest()
        assertThat(server.takeRequest().body.readUtf8()).doesNotContain("\"temperature\"")
    }

    @Test
    fun complete_otherBadRequest_doesNotRetry() {
        server.enqueue(jsonResponse("""{"error":{"message":"bad request"}}""", code = 400))

        val thrown = assertThrows(AiProviderException::class.java) {
            kotlinx.coroutines.test.runTest {
                client.complete(AiChatRequest(system = "s", user = "u"), makeConfig())
            }
        }

        assertThat(thrown.code).isEqualTo(AiProviderErrorCode.INVALID_REQUEST)
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun complete_jsonModeRejected_fallsBackWithoutResponseFormat() = runTest {
        // 兼容端不认 response_format：第一次 400 且不涉及 temperature
        server.enqueue(jsonResponse("""{"error":{"message":"response_format is not supported"}}""", code = 400))
        server.enqueue(
            jsonResponse("""{"model":"gpt-5","choices":[{"message":{"content":"{\"a\":1}"}}]}""")
        )

        val response = client.complete(
            AiChatRequest(system = "s", user = "u", jsonMode = true),
            makeConfig()
        )

        assertThat(response.text).isEqualTo("""{"a":1}""")
        assertThat(server.requestCount).isEqualTo(2)

        server.takeRequest()
        val fallbackBody = server.takeRequest().body.readUtf8()
        assertThat(fallbackBody).doesNotContain("response_format")
        // 回退路径要在 system 里补上「仅输出 JSON」的显式指令
        assertThat(fallbackBody).contains("仅输出 JSON")
    }
}
