package com.tracktosearch.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareCodecTest {

    private fun sampleSource() = CustomSearchSource(
        id = "abc-123",
        name = "我的盘搜",
        baseUrl = "https://so.252035.xyz/",
        apiPath = "api/search",
        keywordParam = "kw",
        cloudTypesParam = "cloud_types",
        cloudTypesValue = "quark,baidu,aliyun",
        srcParam = "src",
        srcValue = "all",
        enabled = true,
        parseMode = "custom",
        listPath = "$.data.results",
        namePath = "$.title",
        urlPath = "$.url",
        diskTypePath = "$.type",
        datePath = "$.datetime"
    )

    @Test
    fun `编解码往返保持所有字段`() {
        val source = sampleSource()
        val text = ShareCodec.encode(source)
        assertTrue(text.startsWith("TRS-SOURCE:1:"))
        val decoded = ShareCodec.decode(text)
        assertNotNull(decoded)
        assertEquals(source, decoded)
    }

    @Test
    fun `中文与空可选字段可往返`() {
        val source = sampleSource().copy(
            name = "日本語の源",
            cloudTypesParam = null,
            srcValue = null,
            datePath = null
        )
        assertEquals(source, ShareCodec.decode(ShareCodec.encode(source)))
    }

    @Test
    fun `无前缀的文本返回 null`() {
        assertNull(ShareCodec.decode("hello world"))
        assertNull(ShareCodec.decode(""))
    }

    @Test
    fun `前缀正确但 base64 损坏返回 null`() {
        assertNull(ShareCodec.decode("TRS-SOURCE:1:!!not-base64!!"))
    }

    @Test
    fun `base64 合法但不是序列化 JSON 返回 null`() {
        val bad = java.util.Base64.getEncoder().encodeToString("not json".toByteArray())
        assertNull(ShareCodec.decode("TRS-SOURCE:1:$bad"))
    }
}
