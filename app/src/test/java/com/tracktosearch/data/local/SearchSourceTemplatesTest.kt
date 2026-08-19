package com.tracktosearch.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchSourceTemplatesTest {

    @Test
    fun `内置模板非空且 id 唯一`() {
        assertTrue(SearchSourceTemplates.all.isNotEmpty())
        val ids = SearchSourceTemplates.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `每个模板的默认配置必填字段齐全`() {
        SearchSourceTemplates.all.forEach { t ->
            // 空白向导模板（wizardMode）名称由用户在向导中填写，允许为空
            assertTrue("模板 ${t.id} 名称为空", t.defaults.name.isNotBlank() || t.wizardMode)
            assertTrue("模板 ${t.id} 地址为空", t.defaults.baseUrl.isNotBlank() || t.wizardMode)
            assertTrue("模板 ${t.id} apiPath 为空", t.defaults.apiPath.isNotBlank())
            assertTrue("模板 ${t.id} keywordParam 为空", t.defaults.keywordParam.isNotBlank())
            assertTrue("模板 ${t.id} parseMode 非法", t.defaults.parseMode in setOf("pansou_template", "zreso_template", "custom"))
            assertNotNull("模板 ${t.id} 图标为空", t.icon)
        }
    }

    @Test
    fun `按 id 查找模板`() {
        assertNotNull(SearchSourceTemplates.byId("pansou_public"))
        assertEquals(null, SearchSourceTemplates.byId("nonexistent"))
    }

    @Test
    fun `空白自定义模板是唯一走向导的模板`() {
        val blank = SearchSourceTemplates.byId("blank")!!
        assertTrue(blank.wizardMode)
        assertFalse(SearchSourceTemplates.all.filter { it.id != "blank" }.any { it.wizardMode })
    }
}
