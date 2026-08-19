package com.tracktosearch.data.local

import kotlinx.serialization.json.Json
import java.util.Base64

/**
 * 自定义搜索源分享文本编解码。
 * 格式：`TRS-SOURCE:1:` + Base64(URL_SAFE_NO_WRAP, JSON)
 */
object ShareCodec {

    const val PREFIX = "TRS-SOURCE:1:"

    private val json = Json { ignoreUnknownKeys = true }

    fun encode(source: CustomSearchSource): String =
        PREFIX + Base64.getUrlEncoder().withoutPadding()
            .encodeToString(json.encodeToString(CustomSearchSource.serializer(), source).toByteArray(Charsets.UTF_8))

    fun decode(text: String): CustomSearchSource? {
        if (!text.startsWith(PREFIX)) return null
        val payload = text.removePrefix(PREFIX)
        return try {
            val jsonStr = String(Base64.getUrlDecoder().decode(payload), Charsets.UTF_8)
            json.decodeFromString(CustomSearchSource.serializer(), jsonStr)
        } catch (_: Exception) {
            null
        }
    }
}
