package com.tracktosearch.data.ai.provider

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 适配器工厂单测：create 按协议返回正确实现，且同一协议复用同一单例。
 */
class AiProviderClientFactoryTest {

    @Test
    fun `create returns OpenAiCompatibleClient for OPENAI_COMPATIBLE`() {
        val client = AiProviderClientFactory.create(AiProviderProtocol.OPENAI_COMPATIBLE)
        assertThat(client).isInstanceOf(OpenAiCompatibleClient::class.java)
    }

    @Test
    fun `create returns GeminiClient for GEMINI`() {
        val client = AiProviderClientFactory.create(AiProviderProtocol.GEMINI)
        assertThat(client).isInstanceOf(GeminiClient::class.java)
    }

    @Test
    fun `create returns AnthropicClient for ANTHROPIC`() {
        val client = AiProviderClientFactory.create(AiProviderProtocol.ANTHROPIC)
        assertThat(client).isInstanceOf(AnthropicClient::class.java)
    }

    @Test
    fun `create reuses same instance per protocol`() {
        val first = AiProviderClientFactory.create(AiProviderProtocol.GEMINI)
        val second = AiProviderClientFactory.create(AiProviderProtocol.GEMINI)
        assertThat(first).isSameInstanceAs(second)
    }
}
