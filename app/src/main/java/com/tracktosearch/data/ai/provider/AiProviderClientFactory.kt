package com.tracktosearch.data.ai.provider

import java.util.concurrent.ConcurrentHashMap

/**
 * 协议适配器工厂：按 [AiProviderProtocol] 返回对应客户端单例。
 *
 * 三个适配器都无状态（请求参数全部来自 AiResolvedConfig），
 * 单例缓存避免反复构建 OkHttpClient 连接池。
 */
object AiProviderClientFactory {

    /** protocol → 客户端实例的懒初始化缓存。 */
    private val clients = ConcurrentHashMap<AiProviderProtocol, AiProviderClient>()

    /** 取指定协议的客户端：首次调用创建，之后复用同一实例。 */
    fun create(protocol: AiProviderProtocol): AiProviderClient =
        clients.getOrPut(protocol) {
            when (protocol) {
                AiProviderProtocol.OPENAI_COMPATIBLE -> OpenAiCompatibleClient()
                AiProviderProtocol.OPENAI_RESPONSES -> OpenAiResponsesClient()
                AiProviderProtocol.GEMINI -> GeminiClient()
                AiProviderProtocol.ANTHROPIC -> AnthropicClient()
            }
        }
}
