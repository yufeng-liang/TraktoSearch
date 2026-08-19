package com.tracktosearch.data.remote.custom

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AutoProbeTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `变体生成覆盖路径与参数组合`() {
        val variants = AutoProbe.variants()
        assertEquals(16, variants.size)
        assert(variants.any { it.apiPath == "api/search" && it.keywordParam == "kw" })
        assert(variants.any { it.apiPath == "search" && it.keywordParam == "q" })
    }

    @Test
    fun `识别 PanSou 结构并推断模板模式`() {
        val root = json.parseToJsonElement(
            """{"code":0,"data":{"merged_by_type":{"quark":[{"note":"电影 A","url":"https://pan.quark.cn/s/abc","datetime":"2026-08-01"}]}}}"""
        )
        val result = AutoProbe.analyze(root)
        assertNotNull(result)
        assertEquals("$.data.merged_by_type[*]", result!!.listPath)
        assertEquals("pansou_template", result.parseMode)
        assertEquals("note", result.namePath)
        assertEquals("url", result.urlPath)
    }

    @Test
    fun `识别 Zreso 结构并推断模板模式`() {
        val root = json.parseToJsonElement(
            """{"code":0,"data":{"results":[{"title":"电影 B","datetime":"2026-08-01","date":"1天前","links":[{"url":"https://zreso.cn/x","type":"quark"}]}]}}"""
        )
        val result = AutoProbe.analyze(root)
        assertNotNull(result)
        assertEquals("$.data.results", result!!.listPath)
        assertEquals("zreso_template", result.parseMode)
        assertEquals("title", result.namePath)
        assertEquals("links[0].url", result.urlPath)
    }

    @Test
    fun `识别自定义结构生成 JSONPath`() {
        val root = json.parseToJsonElement(
            """{"data":{"items":[{"title":"资源 1","link":"https://x.com/a","disk":"baidu","time":"2026-01-01"}]}}"""
        )
        val result = AutoProbe.analyze(root)
        assertNotNull(result)
        assertEquals("$.data.items", result!!.listPath)
        assertEquals("custom", result.parseMode)
        assertEquals("title", result.namePath)
        assertEquals("link", result.urlPath)
        assertEquals("disk", result.diskTypePath)
        assertEquals("time", result.datePath)
    }

    @Test
    fun `无列表结构时返回 null`() {
        val root = json.parseToJsonElement("""{"code":1,"msg":"error"}""")
        assertNull(AutoProbe.analyze(root))
    }
}
