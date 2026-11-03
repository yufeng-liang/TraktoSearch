package com.tracktosearch.data.ai.provider

/**
 * API 地址归一化。
 *
 * 用户从各家文档复制地址时，很容易把**完整端点**贴进来（`https://api.openai.com/v1/chat/completions`），
 * 我们再拼一次 `/chat/completions` 就变成 `/chat/completions/chat/completions`，服务端回 404，
 * 而 404 的报错信息（`Not Found`）看不出是地址填多了。
 *
 * 这里只做「剥掉多写的端点后缀」这一件事：
 * - 不自动补 `/v1`：DeepSeek、部分中转站本来就不带版本段，补了反而错。
 * - 不校验协议头、不猜补全：地址写错要让用户自己看见，而不是被悄悄改写。
 */
internal object AiProviderUrl {

    /** 已知端点后缀，长的排前面：`/v1/messages` 必须先于 `/messages` 命中。 */
    private val KNOWN_ENDPOINT_SUFFIXES = listOf(
        "/chat/completions",
        "/completions",
        "/responses",
        "/images/generations",
        "/audio/speech",
        "/v1/messages",
        "/messages",
        "/models",
    )

    /** Gemini 的端点长这样：`/v1beta/models/gemini-2.0-flash:generateContent`。 */
    private val GENERATE_CONTENT_SUFFIX = Regex(":\\w+$")

    fun normalizeBaseUrl(raw: String): String {
        var url = raw.trim()
        if (url.isEmpty()) return ""
        url = url.trimEnd('/')

        // Gemini：剥掉 :generateContent / :streamGenerateContent 以及前面的 /models/{模型名}
        if (GENERATE_CONTENT_SUFFIX.containsMatchIn(url) && url.contains("/models/")) {
            url = url.substringBefore("/models/")
        }

        // OpenAI 兼容 / Anthropic：剥掉端点路径，只留 base
        KNOWN_ENDPOINT_SUFFIXES.firstOrNull { url.endsWith(it, ignoreCase = true) }?.let { suffix ->
            url = url.dropLast(suffix.length)
        }

        return url.trimEnd('/')
    }
}
