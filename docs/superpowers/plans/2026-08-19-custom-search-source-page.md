# 自定义搜索源页面化与易用性优化 实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 将自定义搜索源配置从设置页弹窗改为独立管理页 + 3 步向导编辑页，内置模板库、文本分享/导入、JSONPath 自动探测，降低配置门槛。

**架构：** 两级导航（设置页 → 搜索源列表页 → 分步向导编辑页）。数据层新增 ShareCodec（分享文本编解码）、SearchSourceTemplates（内置模板）、AutoProbe（自动探测）；UI 层新增 searchsource 包（列表页/编辑器/模板弹层/分享导入）。自定义源持久化沿用 CustomSearchSourceStorage（DataStore），内置源启停沿用 SearchSourceStorage，均无数据迁移。

**技术栈：** Kotlin + Jetpack Compose + Hilt + Navigation Compose + DataStore + kotlinx.serialization + okhttp（MockWebServer 测试）

---

## 文件结构

```
新增（main）：
- data/local/ShareCodec.kt                      分享文本编解码（T1）
- data/local/SearchSourceTemplates.kt           内置模板定义（T2）
- data/remote/custom/AutoProbe.kt               自动探测（T3）
- ui/screen/searchsource/SearchSourcesScreen.kt        列表页（T5）
- ui/screen/searchsource/SearchSourcesViewModel.kt     列表页 VM（T5）
- ui/screen/searchsource/CustomSourceFields.kt         手动配置表单（T6，从 CustomSourceEditDialog 迁移）
- ui/screen/searchsource/SearchSourceEditorScreen.kt   3 步向导页（T6）
- ui/screen/searchsource/SearchSourceEditorViewModel.kt 向导 VM（T6）
- ui/screen/searchsource/SourceTemplateSheet.kt        模板库 BottomSheet（T6）
- ui/screen/searchsource/ShareImportDialogs.kt         分享弹层 + 导入确认弹层（T6）

新增（test）：
- test/java/com/tracktosearch/data/local/ShareCodecTest.kt
- test/java/com/tracktosearch/data/local/SearchSourceTemplatesTest.kt
- test/java/com/tracktosearch/data/remote/custom/AutoProbeTest.kt

修改：
- data/remote/custom/CustomSearchService.kt     加 fetchRaw（T3）
- ui/navigation/AppNavigation.kt                加 Routes + composable（T7）
- ui/screen/settings/SettingsScreen.kt          搜索分组 → 单一入口（T7）
- ui/screen/settings/SettingsDialogs.kt         删 CustomSourceEditDialog（T7）
- ui/screen/settings/SettingsComponents.kt      删 SearchSourceCard/AddCard/CustomSearchSourceItem（T7）
- ui/screen/settings/SettingsViewModel.kt       删内置源启停/自定义源/测试结果相关（T7，保留 panHubConfig 供列表页 VM 复用逻辑的参考，实际移入 SearchSourcesViewModel）
- res/values/strings.xml + values-zh + values-ja + values-ko  全部新增文案（T4）
- ui/screen/help/HelpScreen.kt                  帮助页更新（T8）
```

任务顺序：T1→T2→T3（数据层，可并行）→ T4（字符串，被 UI 依赖）→ T5（列表页）→ T6（编辑器）→ T7（导航+清理）→ T8（帮助页）→ T9（整体验证）。

---

### 任务 T1：ShareCodec 分享文本编解码

**文件：**
- 创建：`app/src/main/java/com/tracktosearch/data/local/ShareCodec.kt`
- 测试：`app/src/test/java/com/tracktosearch/data/local/ShareCodecTest.kt`

- [ ] **步骤 1：编写失败的测试**

```kotlin
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
```

- [ ] **步骤 2：运行测试验证失败**

运行：`gradlew.bat :app:testDebugUnitTest --tests "com.tracktosearch.data.local.ShareCodecTest" -q`
预期：编译失败，`ShareCodec` 未定义

- [ ] **步骤 3：实现 ShareCodec**

```kotlin
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
```

- [ ] **步骤 4：运行测试验证通过**

运行：`gradlew.bat :app:testDebugUnitTest --tests "com.tracktosearch.data.local.ShareCodecTest" -q`
预期：PASS（5 个用例）

- [ ] **步骤 5：Commit**

```bash
git add app/src/main/java/com/tracktosearch/data/local/ShareCodec.kt app/src/test/java/com/tracktosearch/data/local/ShareCodecTest.kt
git commit -m "feat(search-source): 新增分享文本编解码 ShareCodec"
```

---

### 任务 T2：SearchSourceTemplates 内置模板库

**文件：**
- 创建：`app/src/main/java/com/tracktosearch/data/local/SearchSourceTemplates.kt`
- 测试：`app/src/test/java/com/tracktosearch/data/local/SearchSourceTemplatesTest.kt`

- [ ] **步骤 1：编写失败的测试**

```kotlin
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
            assertTrue("模板 ${t.id} 名称为空", t.defaults.name.isNotBlank())
            assertTrue("模板 ${t.id} 地址为空", t.defaults.baseUrl.isNotBlank())
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
```

- [ ] **步骤 2：运行测试验证失败**

运行：`gradlew.bat :app:testDebugUnitTest --tests "com.tracktosearch.data.local.SearchSourceTemplatesTest" -q`
预期：编译失败，`SearchSourceTemplates` 未定义

- [ ] **步骤 3：实现模板定义**

```kotlin
package com.tracktosearch.data.local

/**
 * 内置搜索源模板：用户点选后自动填充配置。
 */
data class SearchSourceTemplate(
    val id: String,
    val name: String,
    val description: String,
    val icon: String,
    /** true 表示点击后走完整分步向导（空白自定义）；false 表示参数完整，直接进确认步骤 */
    val wizardMode: Boolean,
    val defaults: CustomSearchSource
)

object SearchSourceTemplates {

    val all: List<SearchSourceTemplate> = listOf(
        SearchSourceTemplate(
            id = "pansou_public",
            name = "PanSou 公共源",
            description = "公共盘搜 API，模板解析，一键填好",
            icon = "🔍",
            wizardMode = false,
            defaults = CustomSearchSource(
                id = "", // 保存时生成
                name = "PanSou 公共源",
                baseUrl = "https://so.252035.xyz/",
                apiPath = "api/search",
                keywordParam = "kw",
                cloudTypesParam = "cloud_types",
                cloudTypesValue = "quark,baidu,aliyun,xunlei,uc,115",
                srcParam = "src",
                srcValue = "all",
                enabled = true,
                parseMode = "pansou_template"
            )
        ),
        SearchSourceTemplate(
            id = "pansou_self",
            name = "PanSou 自建",
            description = "自部署 PanSou API，只需改地址",
            icon = "⚙️",
            wizardMode = false,
            defaults = CustomSearchSource(
                id = "",
                name = "PanSou 自建",
                baseUrl = "http://127.0.0.1:8888/",
                apiPath = "api/search",
                keywordParam = "kw",
                cloudTypesParam = "cloud_types",
                cloudTypesValue = "quark,baidu,aliyun,xunlei,uc,115",
                srcParam = "src",
                srcValue = "all",
                enabled = true,
                parseMode = "pansou_template"
            )
        ),
        SearchSourceTemplate(
            id = "zreso",
            name = "Zreso 资源库",
            description = "zreso.cn 模板解析",
            icon = "📦",
            wizardMode = false,
            defaults = CustomSearchSource(
                id = "",
                name = "Zreso 资源库",
                baseUrl = "https://zreso.cn/",
                apiPath = "api/search",
                keywordParam = "kw",
                enabled = true,
                parseMode = "zreso_template"
            )
        ),
        SearchSourceTemplate(
            id = "blank",
            name = "空白自定义",
            description = "走分步向导，支持任意 JSON API",
            icon = "＋",
            wizardMode = true,
            defaults = CustomSearchSource(
                id = "",
                name = "",
                baseUrl = "",
                apiPath = "api/search",
                keywordParam = "kw",
                cloudTypesParam = "cloud_types",
                cloudTypesValue = "quark,baidu,aliyun,xunlei,uc,115",
                srcParam = "src",
                srcValue = "all",
                enabled = true,
                parseMode = "pansou_template"
            )
        )
    )

    fun byId(id: String): SearchSourceTemplate? = all.find { it.id == id }

    /** 空白模板：新建向导入口 */
    val blank: SearchSourceTemplate get() = byId("blank")!!
}
```

- [ ] **步骤 4：运行测试验证通过**

运行：`gradlew.bat :app:testDebugUnitTest --tests "com.tracktosearch.data.local.SearchSourceTemplatesTest" -q`
预期：PASS

- [ ] **步骤 5：Commit**

```bash
git add app/src/main/java/com/tracktosearch/data/local/SearchSourceTemplates.kt app/src/test/java/com/tracktosearch/data/local/SearchSourceTemplatesTest.kt
git commit -m "feat(search-source): 内置搜索源模板库"
```

---

### 任务 T3：AutoProbe 自动探测

**文件：**
- 修改：`app/src/main/java/com/tracktosearch/data/remote/custom/CustomSearchService.kt`
- 创建：`app/src/main/java/com/tracktosearch/data/remote/custom/AutoProbe.kt`
- 测试：`app/src/test/java/com/tracktosearch/data/remote/custom/AutoProbeTest.kt`

- [ ] **步骤 1：编写失败的测试**

```kotlin
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
```

- [ ] **步骤 2：运行测试验证失败**

运行：`gradlew.bat :app:testDebugUnitTest --tests "com.tracktosearch.data.remote.custom.AutoProbeTest" -q`
预期：编译失败，`AutoProbe` 未定义

- [ ] **步骤 3：CustomSearchService 加 fetchRaw**

在 `CustomSearchService` 的 `search` 方法之后新增（`buildUrl` 保持私有不变）：

```kotlin
/**
 * 获取原始响应 JSON（自动探测用）：请求失败或非 2xx 返回 null
 */
suspend fun fetchRaw(source: CustomSearchSource, keyword: String): JsonElement? = withContext(Dispatchers.IO) {
    try {
        val url = buildUrl(source, keyword)
        val request = Request.Builder()
            .url(url)
            .addHeader("User-Agent", com.tracktosearch.di.NetworkModule.USER_AGENT)
            .addHeader("Referer", source.baseUrl)
            .build()
        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@withContext null
            val body = response.body?.string() ?: return@withContext null
            json.parseToJsonElement(body)
        }
    } catch (_: Exception) {
        null
    }
}
```

- [ ] **步骤 4：实现 AutoProbe**

```kotlin
package com.tracktosearch.data.remote.custom

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 自动探测：给定接口响应 JSON，识别结果列表与字段路径。
 */
object AutoProbe {

    /** 自动探测结果：可直接构造 CustomSearchSource 的解析配置 */
    data class ProbeResult(
        val parseMode: String,
        val listPath: String,
        val namePath: String,
        val urlPath: String,
        val diskTypePath: String?,
        val datePath: String?
    )

    data class Variant(val apiPath: String, val keywordParam: String)

    val KEYWORD_PARAM_VARIANTS = listOf("kw", "q", "keyword", "query")
    val API_PATH_VARIANTS = listOf("api/search", "search", "api/query", "query")

    /** 路径 × 关键词参数的组合变体 */
    fun variants(): List<Variant> = API_PATH_VARIANTS.flatMap { p ->
        KEYWORD_PARAM_VARIANTS.map { Variant(p, it) }
    }

    /**
     * 分析 JSON 结构，识别列表与字段路径。
     * 返回 null 表示未找到可用列表结构。
     */
    fun analyze(root: JsonElement): ProbeResult? {
        val candidate = findListCandidate(root) ?: return null
        val (listPath, modeHint, firstItem) = candidate

        val namePath = findStringField(firstItem, NAME_PRIORITY)
        val urlPath = findStringField(firstItem, URL_PRIORITY)
        if (namePath == null || urlPath == null) return null

        val diskTypePath = findStringField(firstItem, DISK_PRIORITY)
        val datePath = findStringField(firstItem, DATE_PRIORITY)

        val parseMode = when {
            modeHint != null -> modeHint
            namePath != null && urlPath != null -> "custom"
            else -> "custom"
        }
        return ProbeResult(
            parseMode = parseMode,
            listPath = listPath,
            namePath = namePath,
            urlPath = urlPath,
            diskTypePath = diskTypePath,
            datePath = datePath
        )
    }

    private val NAME_PRIORITY = listOf("title", "name", "note", "filename", "file_name")
    private val URL_PRIORITY = listOf("url", "link", "download_url", "downloadUrl", "magnet")
    private val DISK_PRIORITY = listOf("type", "disk_type", "diskType", "source")
    private val DATE_PRIORITY = listOf("datetime", "date", "time", "upload_time", "update_time", "create_time")

    private data class ListCandidate(
        val path: String,
        val modeHint: String?,
        val firstItem: JsonElement
    )

    /**
     * 深度优先搜索根 JSON，找候选列表：
     * 1. 名字匹配的数组字段（results/data/items/list/rows/records）
     * 2. merged_by_type 这类"object 值全为数组"的字段（PanSou）
     * 3. 任意数组字段（兜底）
     */
    private fun findListCandidate(root: JsonElement): ListCandidate? {
        // 深度优先遍历，记录路径
        fun walk(element: JsonElement, path: String): ListCandidate? {
            when (element) {
                is JsonObject -> {
                    // 检查 object 值全为 array（merged_by_type 模式）
                    if (element.values.isNotEmpty() && element.values.all { it is JsonArray }) {
                        val arrays = element.values.filterIsInstance<JsonArray>().filter { it.isNotEmpty() }
                        if (arrays.isNotEmpty()) {
                            val item = arrays.first().first()
                            val cleanPath = path.ifBlank { "$" } + "[*]"
                            return ListCandidate(cleanPath, "pansou_template", item)
                        }
                    }
                    for ((key, value) in element) {
                        val childPath = if (path.isBlank()) "$.data" else path
                        val nextPath = if (path.endsWith("[*]") || path.isBlank()) "$.$key" else "$path.$key"
                        if (value is JsonArray && value.isNotEmpty() && value.first() is JsonObject) {
                            val hint = if (key == "results" && containsLinksField(value.first() as JsonObject)) {
                                "zreso_template"
                            } else {
                                null
                            }
                            return ListCandidate(nextPath, hint, value.first())
                        }
                        walk(value, nextPath)?.let { return it }
                    }
                }
                is JsonArray -> {
                    for ((index, item) in element.withIndex()) {
                        if (item is JsonObject) {
                            return ListCandidate("$path[$index]", null, item)
                        }
                        walk(item, "$path[$index]")?.let { return it }
                    }
                }
                else -> {}
            }
            return null
        }
        return walk(root, "")
    }

    private fun containsLinksField(obj: JsonObject): Boolean =
        obj.keys.any { it == "links" } || obj.keys.any { it.contains("link") }

    /**
     * 在列表元素中按优先级找字符串字段，返回相对路径（如 "title"、"links[0].url"）。
     */
    private fun findStringField(element: JsonElement, priorities: List<String>): String? {
        if (element !is JsonObject) return null
        // 优先精确名字匹配
        for (key in priorities) {
            if (element[key] is JsonPrimitive && (element[key] as JsonPrimitive).isString) {
                return key
            }
        }
        // 其次模糊匹配（字段名包含优先级关键词）
        for ((key, value) in element) {
            if (value is JsonPrimitive && value.isString) {
                val lower = key.lowercase()
                if (priorities.any { p -> lower.contains(p.lowercase()) }) {
                    return key
                }
            }
        }
        // 再查 links 数组首元素（zreso 场景）
        for ((key, value) in element) {
            if (value is JsonArray && value.isNotEmpty() && value.first() is JsonObject) {
                val first = value.first() as JsonObject
                for (p in priorities) {
                    if (first[p] is JsonPrimitive && (first[p] as JsonPrimitive).isString) {
                        return "$key[0].$p"
                    }
                }
            }
        }
        return null
    }
}
```

- [ ] **步骤 5：运行测试验证通过**

运行：`gradlew.bat :app:testDebugUnitTest --tests "com.tracktosearch.data.remote.custom.AutoProbeTest" -q`
预期：PASS（5 个用例）。若 `links[0].url` 识别失败，检查 `containsLinksField`/`findStringField` 中 links 分支顺序——`URL_PRIORITY` 包含 "url"，links 分支在模糊匹配之后命中 `$key[0].$p` = `links[0].url`。

- [ ] **步骤 6：Commit**

```bash
git add app/src/main/java/com/tracktosearch/data/remote/custom/AutoProbe.kt app/src/main/java/com/tracktosearch/data/remote/custom/CustomSearchService.kt app/src/test/java/com/tracktosearch/data/remote/custom/AutoProbeTest.kt
git commit -m "feat(search-source): JSONPath 自动探测 AutoProbe"
```

---

### 任务 T4：国际化字符串（四语同步）

**文件：**
- 修改：`app/src/main/res/values/strings.xml`（英）、`values-zh/strings.xml`（中）、`values-ja/strings.xml`（日）、`values-ko/strings.xml`（韩）

- [ ] **步骤 1：在 values/strings.xml 的搜索源相关区域（L1189-1224 附近）追加以下 key**

```xml
    <!-- 搜索源管理页 -->
    <string name="search_sources_title">Search Sources</string>
    <string name="search_sources_import">Import config</string>
    <string name="search_sources_builtin">Built-in</string>
    <string name="search_sources_custom">Custom</string>
    <string name="search_sources_add_from_template">Add from template</string>
    <string name="search_sources_empty">No custom sources yet.\nAdd one from a template, or paste a shared config.</string>
    <string name="search_sources_manage_entry_title">Search sources</string>
    <string name="search_sources_manage_entry_subtitle">Built-in &amp; custom sources</string>
    <!-- 模板库 -->
    <string name="template_library_title">Add from template</string>
    <string name="template_import_shortcut">Have a shared config? Import from text</string>
    <!-- 向导 -->
    <string name="editor_title_add">Add search source</string>
    <string name="editor_title_edit">Edit search source</string>
    <string name="editor_title_import">Import from text</string>
    <string name="editor_step_basic">Basic info</string>
    <string name="editor_step_auto">Auto parse</string>
    <string name="editor_step_confirm">Confirm</string>
    <string name="editor_next">Next: auto parse</string>
    <string name="editor_next_confirm">Next: confirm</string>
    <string name="editor_prev">Back</string>
    <string name="editor_applied_template">Template "%1$s" applied</string>
    <string name="editor_probe_start">Start parsing</string>
    <string name="editor_probe_progress">Requesting with sample keyword…</string>
    <string name="editor_probe_success">Recognized</string>
    <string name="editor_probe_failed">Could not recognize automatically, you can configure manually</string>
    <string name="editor_probe_result_list">Result list</string>
    <string name="editor_probe_result_title">Title</string>
    <string name="editor_probe_result_url">Link</string>
    <string name="editor_probe_result_disk">Disk type</string>
    <string name="editor_probe_result_date">Date</string>
    <string name="editor_probe_keyword_param">Keyword param</string>
    <string name="editor_probe_manual">Wrong? Adjust JSONPath manually</string>
    <string name="editor_confirm_url_preview">Request URL preview</string>
    <string name="editor_advanced_expand">Show all advanced params</string>
    <string name="editor_advanced_collapse">Hide advanced params</string>
    <string name="editor_parse_mode_label">Parse mode</string>
    <!-- 分享/导入 -->
    <string name="share_source_title">Share "%1$s"</string>
    <string name="share_source_desc">The config is sent as text; paste it in the app on the other device to import.</string>
    <string name="share_copy">Copy</string>
    <string name="share_to_apps">Share to other apps</string>
    <string name="share_copy_success">Config text copied</string>
    <string name="import_paste_hint">Paste config text</string>
    <string name="import_valid">Valid config detected</string>
    <string name="import_invalid">Not a valid config text</string>
    <string name="import_preview_name">Name</string>
    <string name="import_preview_url">API URL</string>
    <string name="import_preview_mode">Parse mode</string>
    <string name="import_duplicate_warning">A source with the same name exists; importing will overwrite it. You can rename before importing.</string>
    <string name="import_confirm">Confirm import</string>
    <string name="cd_share">Share</string>
```

- [ ] **步骤 2：同步 values-zh/strings.xml**

```xml
    <!-- 搜索源管理页 -->
    <string name="search_sources_title">搜索源</string>
    <string name="search_sources_import">导入配置</string>
    <string name="search_sources_builtin">内置源</string>
    <string name="search_sources_custom">自定义源</string>
    <string name="search_sources_add_from_template">从模板添加搜索源</string>
    <string name="search_sources_empty">还没有自定义搜索源。\n可以从模板添加，或粘贴分享的配置。</string>
    <string name="search_sources_manage_entry_title">搜索源</string>
    <string name="search_sources_manage_entry_subtitle">内置与自定义搜索源</string>
    <!-- 模板库 -->
    <string name="template_library_title">从模板添加</string>
    <string name="template_import_shortcut">有现成配置？从文本导入 →</string>
    <!-- 向导 -->
    <string name="editor_title_add">添加搜索源</string>
    <string name="editor_title_edit">编辑搜索源</string>
    <string name="editor_title_import">从文本导入</string>
    <string name="editor_step_basic">基本信息</string>
    <string name="editor_step_auto">自动解析</string>
    <string name="editor_step_confirm">确认参数</string>
    <string name="editor_next">下一步：自动解析</string>
    <string name="editor_next_confirm">下一步：确认参数</string>
    <string name="editor_prev">上一步</string>
    <string name="editor_applied_template">已应用模板「%1$s」</string>
    <string name="editor_probe_start">开始解析</string>
    <string name="editor_probe_progress">正在用示例关键词请求接口…</string>
    <string name="editor_probe_success">识别成功</string>
    <string name="editor_probe_failed">未能自动识别，可手动配置</string>
    <string name="editor_probe_result_list">结果列表</string>
    <string name="editor_probe_result_title">标题</string>
    <string name="editor_probe_result_url">链接</string>
    <string name="editor_probe_result_disk">网盘类型</string>
    <string name="editor_probe_result_date">时间</string>
    <string name="editor_probe_keyword_param">关键词参数</string>
    <string name="editor_probe_manual">识别不准？手动调整 JSONPath →</string>
    <string name="editor_confirm_url_preview">请求地址预览</string>
    <string name="editor_advanced_expand">展开全部高级参数</string>
    <string name="editor_advanced_collapse">收起高级参数</string>
    <string name="editor_parse_mode_label">解析模式</string>
    <!-- 分享/导入 -->
    <string name="share_source_title">分享「%1$s」</string>
    <string name="share_source_desc">配置以文本形式发送，对方在 App 内粘贴即可导入</string>
    <string name="share_copy">复制</string>
    <string name="share_to_apps">分享到其他应用</string>
    <string name="share_copy_success">配置文本已复制</string>
    <string name="import_paste_hint">粘贴配置文本</string>
    <string name="import_valid">检测到有效配置</string>
    <string name="import_invalid">不是有效的配置文本</string>
    <string name="import_preview_name">名称</string>
    <string name="import_preview_url">API 地址</string>
    <string name="import_preview_mode">解析模式</string>
    <string name="import_duplicate_warning">同名配置已存在，导入将覆盖。可在导入前修改名称。</string>
    <string name="import_confirm">确认导入</string>
    <string name="cd_share">分享</string>
```

- [ ] **步骤 3：同步 values-ja/strings.xml（日文）**

```xml
    <!-- 検索ソース管理ページ -->
    <string name="search_sources_title">検索ソース</string>
    <string name="search_sources_import">設定をインポート</string>
    <string name="search_sources_builtin">内蔵ソース</string>
    <string name="search_sources_custom">カスタムソース</string>
    <string name="search_sources_add_from_template">テンプレートから追加</string>
    <string name="search_sources_empty">カスタムソースはまだありません。\nテンプレートから追加するか、共有設定を貼り付けてください。</string>
    <string name="search_sources_manage_entry_title">検索ソース</string>
    <string name="search_sources_manage_entry_subtitle">内蔵・カスタムソース</string>
    <string name="template_library_title">テンプレートから追加</string>
    <string name="template_import_shortcut">共有設定をお持ちですか？テキストからインポート →</string>
    <string name="editor_title_add">検索ソースを追加</string>
    <string name="editor_title_edit">検索ソースを編集</string>
    <string name="editor_title_import">テキストからインポート</string>
    <string name="editor_step_basic">基本情報</string>
    <string name="editor_step_auto">自動解析</string>
    <string name="editor_step_confirm">パラメータ確認</string>
    <string name="editor_next">次へ：自動解析</string>
    <string name="editor_next_confirm">次へ：パラメータ確認</string>
    <string name="editor_prev">戻る</string>
    <string name="editor_applied_template">テンプレート「%1$s」を適用しました</string>
    <string name="editor_probe_start">解析開始</string>
    <string name="editor_probe_progress">サンプルキーワードでリクエスト中…</string>
    <string name="editor_probe_success">認識成功</string>
    <string name="editor_probe_failed">自動認識できませんでした。手動設定してください</string>
    <string name="editor_probe_result_list">結果リスト</string>
    <string name="editor_probe_result_title">タイトル</string>
    <string name="editor_probe_result_url">リンク</string>
    <string name="editor_probe_result_disk">クラウド種別</string>
    <string name="editor_probe_result_date">日時</string>
    <string name="editor_probe_keyword_param">キーワードパラメータ</string>
    <string name="editor_probe_manual">認識が不正確？JSONPath を手動調整 →</string>
    <string name="editor_confirm_url_preview">リクエスト URL プレビュー</string>
    <string name="editor_advanced_expand">詳細パラメータをすべて表示</string>
    <string name="editor_advanced_collapse">詳細パラメータを隠す</string>
    <string name="editor_parse_mode_label">解析モード</string>
    <string name="share_source_title">「%1$s」を共有</string>
    <string name="share_source_desc">設定はテキストとして送信されます。相手のアプリに貼り付けるとインポートできます。</string>
    <string name="share_copy">コピー</string>
    <string name="share_to_apps">他のアプリに共有</string>
    <string name="share_copy_success">設定テキストをコピーしました</string>
    <string name="import_paste_hint">設定テキストを貼り付け</string>
    <string name="import_valid">有効な設定を検出</string>
    <string name="import_invalid">有効な設定テキストではありません</string>
    <string name="import_preview_name">名前</string>
    <string name="import_preview_url">API URL</string>
    <string name="import_preview_mode">解析モード</string>
    <string name="import_duplicate_warning">同名のソースが存在します。インポートすると上書きされます。インポート前に名前を変更できます。</string>
    <string name="import_confirm">インポート確認</string>
    <string name="cd_share">共有</string>
```

- [ ] **步骤 4：同步 values-ko/strings.xml（韩文）**

```xml
    <!-- 검색 소스 관리 페이지 -->
    <string name="search_sources_title">검색 소스</string>
    <string name="search_sources_import">설정 가져오기</string>
    <string name="search_sources_builtin">내장 소스</string>
    <string name="search_sources_custom">사용자 소스</string>
    <string name="search_sources_add_from_template">템플릿에서 추가</string>
    <string name="search_sources_empty">사용자 검색 소스가 없습니다.\n템플릿에서 추가하거나 공유 설정을 붙여넣으세요.</string>
    <string name="search_sources_manage_entry_title">검색 소스</string>
    <string name="search_sources_manage_entry_subtitle">내장 · 사용자 소스</string>
    <string name="template_library_title">템플릿에서 추가</string>
    <string name="template_import_shortcut">공유 설정이 있나요? 텍스트에서 가져오기 →</string>
    <string name="editor_title_add">검색 소스 추가</string>
    <string name="editor_title_edit">검색 소스 편집</string>
    <string name="editor_title_import">텍스트에서 가져오기</string>
    <string name="editor_step_basic">기본 정보</string>
    <string name="editor_step_auto">자동 파싱</string>
    <string name="editor_step_confirm">파라미터 확인</string>
    <string name="editor_next">다음: 자동 파싱</string>
    <string name="editor_next_confirm">다음: 파라미터 확인</string>
    <string name="editor_prev">이전</string>
    <string name="editor_applied_template">템플릿「%1$s」적용됨</string>
    <string name="editor_probe_start">파싱 시작</string>
    <string name="editor_probe_progress">샘플 키워드로 요청 중…</string>
    <string name="editor_probe_success">인식 성공</string>
    <string name="editor_probe_failed">자동 인식 실패, 수동으로 설정하세요</string>
    <string name="editor_probe_result_list">결과 목록</string>
    <string name="editor_probe_result_title">제목</string>
    <string name="editor_probe_result_url">링크</string>
    <string name="editor_probe_result_disk">클라우드 유형</string>
    <string name="editor_probe_result_date">날짜</string>
    <string name="editor_probe_keyword_param">키워드 파라미터</string>
    <string name="editor_probe_manual">인식이 정확하지 않나요? JSONPath 수동 조정 →</string>
    <string name="editor_confirm_url_preview">요청 URL 미리보기</string>
    <string name="editor_advanced_expand">고급 파라미터 모두 표시</string>
    <string name="editor_advanced_collapse">고급 파라미터 숨기기</string>
    <string name="editor_parse_mode_label">파싱 모드</string>
    <string name="share_source_title">「%1$s」공유</string>
    <string name="share_source_desc">설정이 텍스트로 전송됩니다. 상대방 앱에 붙여넣으면 가져올 수 있습니다.</string>
    <string name="share_copy">복사</string>
    <string name="share_to_apps">다른 앱에 공유</string>
    <string name="share_copy_success">설정 텍스트가 복사되었습니다</string>
    <string name="import_paste_hint">설정 텍스트 붙여넣기</string>
    <string name="import_valid">유효한 설정 감지</string>
    <string name="import_invalid">유효한 설정 텍스트가 아닙니다</string>
    <string name="import_preview_name">이름</string>
    <string name="import_preview_url">API URL</string>
    <string name="import_preview_mode">파싱 모드</string>
    <string name="import_duplicate_warning">같은 이름의 소스가 있습니다. 가져오면 덮어씁니다. 가져오기 전에 이름을 바꿀 수 있습니다.</string>
    <string name="import_confirm">가져오기 확인</string>
    <string name="cd_share">공유</string>
```

- [ ] **步骤 5：编译验证**

运行：`gradlew.bat :app:compileDebugKotlin -q`
预期：BUILD SUCCESSFUL（strings 无重复 key、无格式错误）

- [ ] **步骤 6：Commit**

```bash
git add app/src/main/res/values/strings.xml app/src/main/res/values-zh/strings.xml app/src/main/res/values-ja/strings.xml app/src/main/res/values-ko/strings.xml
git commit -m "feat(search-source): 搜索源管理四语文案"
```

---

### 任务 T5：搜索源列表页 SearchSourcesScreen

**文件：**
- 创建：`app/src/main/java/com/tracktosearch/ui/screen/searchsource/SearchSourcesScreen.kt`
- 创建：`app/src/main/java/com/tracktosearch/ui/screen/searchsource/SearchSourcesViewModel.kt`
- 测试：`app/src/test/java/com/tracktosearch/ui/screen/searchsource/SearchSourcesViewModelTest.kt`

前置：T1、T2、T4。列表页展示内置源启停（SearchSourceStorage）+ 自定义源（CustomSearchSourceStorage）+ 测试（CustomSearchService）+ PanHub 配置（PanHubConfigStorage）。

- [ ] **步骤 1：编写 ViewModel 失败的测试**

```kotlin
package com.tracktosearch.ui.screen.searchsource

import app.cash.turbine.test
import com.tracktosearch.data.local.CustomSearchSource
import com.tracktosearch.data.local.CustomSearchSourceStorage
import com.tracktosearch.data.local.SearchSourceStorage
import com.tracktosearch.test.MainDispatcherRule
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * 列表页 VM 只验证状态委托与测试结果状态机；
 * Storage 用真实 DataStore（Robolectric 环境不可用时退化为假实现）。
 */
class SearchSourcesViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private class FakeCustomStorage(initial: List<CustomSearchSource>) : CustomSearchSourceStorage() {
        // 无法直接构造（ApplicationContext），测试改为验证可测试的纯逻辑部分
    }

    private fun sampleSource() = CustomSearchSource(
        id = "s1", name = "源", baseUrl = "https://a.com/", apiPath = "api/search",
        keywordParam = "kw", enabled = true
    )

    @Test
    fun `测试结果状态机转换`() {
        // 用真实依赖太重，这里直接验证 testCustomSource 的错误分支
        // 通过注入 mock CustomSearchService 实现（见实现步骤）
        assertTrue(true) // 占位：实际断言在步骤 3 完成后替换
    }
}
```

说明：ViewModel 依赖 Storage（Hilt @Singleton，构造需 Context），单测成本高。**该测试文件在步骤 3 实现后补写真实断言**，若环境限制则退化为仅验证 `testResults` 状态机（通过构造注入 fake service 的方式——把 `CustomSearchService` 抽象为接口 `CustomSearchApi` 不可行，改动过大）。**决定：T5 不写 VM 单测**（Storage 已各自覆盖，测试逻辑与 SettingsViewModel.testCustomSource 相同且已由现有行为保证），改为步骤 6 构建验证 + T9 模拟器走查。删除本测试文件。

- [ ] **步骤 2：删除测试文件**

```bash
rm app/src/test/java/com/tracktosearch/ui/screen/searchsource/SearchSourcesViewModelTest.kt
```

- [ ] **步骤 3：实现 SearchSourcesViewModel**

```kotlin
package com.tracktosearch.ui.screen.searchsource

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tracktosearch.R
import com.tracktosearch.data.local.CustomSearchSource
import com.tracktosearch.data.local.CustomSearchSourceStorage
import com.tracktosearch.data.local.PanHubConfig
import com.tracktosearch.data.local.PanHubConfigStorage
import com.tracktosearch.data.local.SearchSourceStorage
import com.tracktosearch.data.remote.custom.CustomSearchService
import com.tracktosearch.ui.util.toUserMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class SearchSourcesViewModel @Inject constructor(
    private val searchSourceStorage: SearchSourceStorage,
    private val customSearchSourceStorage: CustomSearchSourceStorage,
    private val panHubConfigStorage: PanHubConfigStorage,
    private val customSearchService: CustomSearchService,
    @ApplicationContext private val context: Context
) : ViewModel() {

    val pansouEnabled: StateFlow<Boolean> = searchSourceStorage.pansouEnabled
    val panhubEnabled: StateFlow<Boolean> = searchSourceStorage.panhubEnabled
    val zresoEnabled: StateFlow<Boolean> = searchSourceStorage.zresoEnabled
    val customSources: StateFlow<List<CustomSearchSource>> = customSearchSourceStorage.sources
    val panHubConfig: StateFlow<PanHubConfig> = panHubConfigStorage.config

    fun setPansouEnabled(enabled: Boolean) {
        viewModelScope.launch { searchSourceStorage.setPansouEnabled(enabled) }
    }

    fun setPanhubEnabled(enabled: Boolean) {
        viewModelScope.launch { searchSourceStorage.setPanhubEnabled(enabled) }
    }

    fun setZresoEnabled(enabled: Boolean) {
        viewModelScope.launch { searchSourceStorage.setZresoEnabled(enabled) }
    }

    fun setPanHubConcurrency(value: Int) {
        viewModelScope.launch { panHubConfigStorage.setConcurrency(value) }
    }

    fun setPanHubTimeoutMs(value: Int) {
        viewModelScope.launch { panHubConfigStorage.setTimeoutMs(value) }
    }

    fun setPanHubEnabledPlugins(pluginIds: Set<String>) {
        viewModelScope.launch { panHubConfigStorage.setEnabledPlugins(pluginIds) }
    }

    fun setPanHubEnabledChannels(channelIds: Set<String>) {
        viewModelScope.launch { panHubConfigStorage.setEnabledChannels(channelIds) }
    }

    fun deleteCustomSource(id: String) {
        viewModelScope.launch { customSearchSourceStorage.deleteSource(id) }
    }

    fun setCustomSourceEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch { customSearchSourceStorage.setEnabled(id, enabled) }
    }

    // 测试结果状态（与 SettingsViewModel.TestResultState 等价）
    @androidx.compose.runtime.Immutable
    data class TestResultState(
        val sourceId: String,
        val isTesting: Boolean = false,
        val success: Boolean? = null,
        val message: String? = null
    )

    private val _testResults = MutableStateFlow<Map<String, TestResultState>>(emptyMap())
    val testResults: StateFlow<Map<String, TestResultState>> = _testResults.asStateFlow()

    fun testCustomSource(source: CustomSearchSource) {
        viewModelScope.launch {
            _testResults.value = _testResults.value + (source.id to TestResultState(source.id, isTesting = true))
            val result = customSearchService.testSource(source)
            val state = when (result) {
                is CustomSearchService.TestResult.Success -> {
                    if (result.count > 0) {
                        TestResultState(source.id, isTesting = false, success = true,
                            message = context.getString(R.string.snackbar_test_success, result.count))
                    } else {
                        TestResultState(source.id, isTesting = false, success = true,
                            message = context.getString(R.string.snackbar_test_empty))
                    }
                }
                is CustomSearchService.TestResult.Error -> {
                    TestResultState(source.id, isTesting = false, success = false,
                        message = Exception(result.message).toUserMessage(context, R.string.error_unknown))
                }
            }
            _testResults.value = _testResults.value + (source.id to state)
        }
    }
}
```

先确认 `PanHubConfig`/`PanHubConfigStorage` 包路径：`com.tracktosearch.data.local`（grep SettingsViewModel import 确认，若不同则调整 import）。运行 `grep -n "import com.tracktosearch.data.local.PanHubConfig" app/src/main/java/com/tracktosearch/ui/screen/settings/SettingsViewModel.kt` 确认后修正。

- [ ] **步骤 4：实现列表页 UI**

```kotlin
package com.tracktosearch.ui.screen.searchsource

// 完整 import 按项目惯例；关键 import：
// androidx.compose.material3.*, androidx.lifecycle.compose.collectAsStateWithLifecycle,
// androidx.compose.foundation.lazy.*, dev.chrisbanes.haze.*,
// com.tracktosearch.ui.screen.settings.PanHubConfigDialog（复用），
// com.tracktosearch.R

/**
 * 搜索源管理列表页：内置源区 + 自定义源区 + 模板库/导入/分享/编辑入口。
 * 页面模板：毛玻璃吸顶标题栏 + 沉浸式 Scaffold。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchSourcesScreen(
    onBack: () -> Unit,
    onAddFromTemplate: (templateId: String) -> Unit,     // "blank" 或模板 id
    onAddBlank: () -> Unit,                              // 空白自定义（等效 onAddFromTemplate("blank")，可省略，统一用模板入口）
    onEditSource: (sourceId: String) -> Unit,
    onImport: () -> Unit,
    viewModel: SearchSourcesViewModel = hiltViewModel()
) {
    val hazeState = remember { HazeState() }
    val hazeStyle = HazeMaterials.thin()
    val pansouEnabled by viewModel.pansouEnabled.collectAsStateWithLifecycle()
    val panhubEnabled by viewModel.panhubEnabled.collectAsStateWithLifecycle()
    val zresoEnabled by viewModel.zresoEnabled.collectAsStateWithLifecycle()
    val customSources by viewModel.customSources.collectAsStateWithLifecycle()
    val testResults by viewModel.testResults.collectAsStateWithLifecycle()
    val panHubConfig by viewModel.panHubConfig.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var showTemplateSheet by remember { mutableStateOf(false) }
    var showDeleteSource by remember { mutableStateOf<CustomSearchSource?>(null) }
    var showShareSource by remember { mutableStateOf<CustomSearchSource?>(null) }
    var showPanHubConfig by remember { mutableStateOf(false) }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(
                modifier = Modifier.fillMaxSize().hazeSource(hazeState),
                contentPadding = PaddingValues(top = 72.dp, bottom = 24.dp)
            ) {
                // ---- 内置源 ----
                item(key = "builtin_header") {
                    SectionLabel(text = stringResource(R.string.search_sources_builtin))
                }
                item(key = "pansou") {
                    SourceRow(
                        icon = "🔍", iconBg = listOf(Color(0xFF4A90D9), Color(0xFF3568B8)),
                        name = "PanSou 盘搜", url = "so.252035.xyz",
                        badge = stringResource(R.string.settings_source_parse_mode_pansou), badgeColor = SourceBadge.GREEN,
                        checked = pansouEnabled,
                        onCheckedChange = { viewModel.setPansouEnabled(it) }
                    )
                }
                item(key = "panhub") {
                    SourceRow(
                        icon = "🦊", iconBg = listOf(Color(0xFFFF8A65), Color(0xFFE64A19)),
                        name = "Panhub 盘狐", url = "api.panhub.com",
                        badge = stringResource(R.string.settings_panhub_config), badgeColor = SourceBadge.ORANGE,
                        checked = panhubEnabled,
                        onCheckedChange = { viewModel.setPanhubEnabled(it) },
                        onConfig = { showPanHubConfig = true }
                    )
                }
                item(key = "zreso") {
                    SourceRow(
                        icon = "📦", iconBg = listOf(Color(0xFF7E57C2), Color(0xFF512DA8)),
                        name = "Zreso 资源库", url = "zreso.cn",
                        badge = stringResource(R.string.settings_source_parse_mode_zreso), badgeColor = SourceBadge.GREEN,
                        checked = zresoEnabled,
                        onCheckedChange = { viewModel.setZresoEnabled(it) }
                    )
                }
                // ---- 自定义源 ----
                item(key = "custom_header") {
                    SectionLabel(text = stringResource(R.string.search_sources_custom))
                }
                if (customSources.isEmpty()) {
                    item(key = "custom_empty") {
                        Text(
                            text = stringResource(R.string.search_sources_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                } else {
                    items(customSources, key = { it.id }) { source ->
                        CustomSourceRow(
                            source = source,
                            testResult = testResults[source.id],
                            onToggle = { viewModel.setCustomSourceEnabled(source.id, it) },
                            onTest = { viewModel.testCustomSource(source) },
                            onEdit = { onEditSource(source.id) },
                            onShare = { showShareSource = source },
                            onDelete = { showDeleteSource = source }
                        )
                    }
                }
                // ---- 模板入口 ----
                item(key = "add_template") {
                    AddFromTemplateCard(onClick = { showTemplateSheet = true })
                }
            }

            // ---- 毛玻璃吸顶标题栏 ----
            HeaderBar(
                title = stringResource(R.string.search_sources_title),
                hazeState = hazeState,
                hazeStyle = hazeStyle,
                onBack = onBack,
                actions = {
                    IconButton(onClick = onImport) {
                        Icon(Icons.Rounded.FileUpload, contentDescription = stringResource(R.string.search_sources_import))
                    }
                    IconButton(onClick = { showTemplateSheet = true }) {
                        Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.settings_add_source))
                    }
                }
            )
        }
    }

    // ---- 模板库 BottomSheet ----
    if (showTemplateSheet) {
        SourceTemplateSheet(
            onTemplateClick = { template ->
                showTemplateSheet = false
                onAddFromTemplate(template.id)
            },
            onImport = {
                showTemplateSheet = false
                onImport()
            },
            onDismiss = { showTemplateSheet = false }
        )
    }

    // ---- 删除确认 ----
    showDeleteSource?.let { source ->
        AlertDialog(
            onDismissRequest = { showDeleteSource = null },
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            title = { Text(stringResource(R.string.settings_delete_source)) },
            text = { Text(stringResource(R.string.settings_delete_source_confirm, source.name)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteCustomSource(source.id)
                    showDeleteSource = null
                }) { Text(stringResource(R.string.cd_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteSource = null }) { Text(stringResource(android.R.string.cancel)) }
            }
        )
    }

    // ---- 分享弹层 ----
    showShareSource?.let { source ->
        ShareSourceDialog(
            source = source,
            onDismiss = { showShareSource = null }
        )
    }

    // ---- PanHub 配置弹窗 ----
    if (showPanHubConfig) {
        PanHubConfigDialog(
            config = panHubConfig,
            enabled = panhubEnabled,
            onEnabledChange = { viewModel.setPanhubEnabled(it) },
            onConcurrencyChange = { viewModel.setPanHubConcurrency(it) },
            onTimeoutMsChange = { viewModel.setPanHubTimeoutMs(it) },
            onEnabledPluginsChange = { viewModel.setPanHubEnabledPlugins(it) },
            onEnabledChannelsChange = { viewModel.setPanHubEnabledChannels(it) },
            onDismiss = { showPanHubConfig = false }
        )
    }
}
```

**同文件内辅助组件**（`SourceRow`、`CustomSourceRow`、`SectionLabel`、`AddFromTemplateCard`、`HeaderBar`、`SourceBadge`）按设计稿实现：
- `SourceRow`：渐变圆角图标块 + 名称 + URL + 彩色解析模式徽章 + 可选配置按钮 + Switch（内置源）
- `CustomSourceRow`：图标 + 名称 + URL + 徽章（parseMode 映射：pansou_template→绿色「模板解析」、zreso_template→绿色「模板解析」、custom→粉色「JSONPath」）+ 操作按钮（测试✓/编辑✎/分享/删除）+ Switch；测试中转圈 + 结果消息（复用 CustomSearchSourceItem 的布局模式）
- `SectionLabel`：分组标题小字
- `AddFromTemplateCard`：虚线圆角卡片「＋ 从模板添加搜索源」
- `HeaderBar`：`Box` + `hazeEffect(hazeState, style = hazeStyle)` 毛玻璃 + 返回箭头 + actions

图标按钮图形参考：测试=Check 或文本、编辑=Edit、分享=Icons.Rounded.Share、删除=Delete（与 CustomSearchSourceItem 一致）。

- [ ] **步骤 5：编译验证**

运行：`gradlew.bat :app:compileDebugKotlin -q`
预期：BUILD SUCCESSFUL（`PanHubConfig` 包路径若不同按步骤 3 修正）

- [ ] **步骤 6：Commit**

```bash
git add app/src/main/java/com/tracktosearch/ui/screen/searchsource/SearchSourcesScreen.kt app/src/main/java/com/tracktosearch/ui/screen/searchsource/SearchSourcesViewModel.kt
git commit -m "feat(search-source): 搜索源管理列表页"
```

---

### 任务 T6：编辑向导页 + 模板弹层 + 分享/导入

**文件：**
- 创建：`app/src/main/java/com/tracktosearch/ui/screen/searchsource/CustomSourceFields.kt`
- 创建：`app/src/main/java/com/tracktosearch/ui/screen/searchsource/SearchSourceEditorScreen.kt`
- 创建：`app/src/main/java/com/tracktosearch/ui/screen/searchsource/SearchSourceEditorViewModel.kt`
- 创建：`app/src/main/java/com/tracktosearch/ui/screen/searchsource/SourceTemplateSheet.kt`
- 创建：`app/src/main/java/com/tracktosearch/ui/screen/searchsource/ShareImportDialogs.kt`

前置：T1、T2、T3、T4、T5。

- [ ] **步骤 1：实现 CustomSourceFields（从 CustomSourceEditDialog 迁移的手动表单）**

```kotlin
package com.tracktosearch.ui.screen.searchsource

// 从 SettingsDialogs.kt 的 CustomSourceEditDialog 表单体提取，
// 供向导「展开高级参数」与「手动调整 JSONPath」复用。
// 保留原有全部字段与 RadioButton 解析模式选择逻辑。

/** 手动配置表单：名称/地址/路径/关键词参数/网盘参数/src/解析模式/JSONPath */
@Composable
fun CustomSourceFields(
    name: String, onNameChange: (String) -> Unit,
    baseUrl: String, onBaseUrlChange: (String) -> Unit,
    apiPath: String, onApiPathChange: (String) -> Unit,
    keywordParam: String, onKeywordParamChange: (String) -> Unit,
    cloudTypesParam: String, onCloudTypesParamChange: (String) -> Unit,
    cloudTypesValue: String, onCloudTypesValueChange: (String) -> Unit,
    srcParam: String, onSrcParamChange: (String) -> Unit,
    srcValue: String, onSrcValueChange: (String) -> Unit,
    parseMode: String, onParseModeChange: (String) -> Unit,
    listPath: String, onListPathChange: (String) -> Unit,
    namePath: String, onNamePathChange: (String) -> Unit,
    urlPath: String, onUrlPathChange: (String) -> Unit,
    diskTypePath: String, onDiskTypePathChange: (String) -> Unit,
    datePath: String, onDatePathChange: (String) -> Unit
) {
    // 内容 = 原 CustomSourceEditDialog 中 AlertDialog.text 的 Column 全部字段
    // （OutlinedTextField × 10 + parseMode RadioButton × 3 + custom 模式条件 JSONPath × 5）
    // 文案复用现有 settings_source_* 字符串
}
```

实现时直接把 `SettingsDialogs.kt` L686-824 的表单 Column 搬入，弹窗标题/按钮逻辑丢弃。

- [ ] **步骤 2：实现 SearchSourceEditorViewModel**

```kotlin
package com.tracktosearch.ui.screen.searchsource

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tracktosearch.data.local.CustomSearchSource
import com.tracktosearch.data.local.CustomSearchSourceStorage
import com.tracktosearch.data.local.SearchSourceTemplates
import com.tracktosearch.data.remote.custom.AutoProbe
import com.tracktosearch.data.remote.custom.CustomSearchService
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 向导模式：新建（空白向导）/模板（参数完整直接确认）/编辑已有/导入 */
enum class EditorMode { BLANK, TEMPLATE, EDIT, IMPORT }

@HiltViewModel
class SearchSourceEditorViewModel @Inject constructor(
    private val customSearchSourceStorage: CustomSearchSourceStorage,
    private val customSearchService: CustomSearchService
) : ViewModel() {

    // 表单状态
    private val _name = MutableStateFlow("")
    val name: StateFlow<String> = _name.asStateFlow()
    private val _baseUrl = MutableStateFlow("")
    val baseUrl: StateFlow<String> = _baseUrl.asStateFlow()
    private val _apiPath = MutableStateFlow("api/search")
    val apiPath: StateFlow<String> = _apiPath.asStateFlow()
    private val _keywordParam = MutableStateFlow("kw")
    val keywordParam: StateFlow<String> = _keywordParam.asStateFlow()
    private val _cloudTypesParam = MutableStateFlow("cloud_types")
    val cloudTypesParam: StateFlow<String> = _cloudTypesParam.asStateFlow()
    private val _cloudTypesValue = MutableStateFlow("quark,baidu,aliyun,xunlei,uc,115")
    val cloudTypesValue: StateFlow<String> = _cloudTypesValue.asStateFlow()
    private val _srcParam = MutableStateFlow("src")
    val srcParam: StateFlow<String> = _srcParam.asStateFlow()
    private val _srcValue = MutableStateFlow("all")
    val srcValue: StateFlow<String> = _srcValue.asStateFlow()
    private val _parseMode = MutableStateFlow("pansou_template")
    val parseMode: StateFlow<String> = _parseMode.asStateFlow()
    private val _listPath = MutableStateFlow<String?>(null)
    val listPath: StateFlow<String?> = _listPath.asStateFlow()
    private val _namePath = MutableStateFlow<String?>(null)
    val namePath: StateFlow<String?> = _namePath.asStateFlow()
    private val _urlPath = MutableStateFlow<String?>(null)
    val urlPath: StateFlow<String?> = _urlPath.asStateFlow()
    private val _diskTypePath = MutableStateFlow<String?>(null)
    val diskTypePath: StateFlow<String?> = _diskTypePath.asStateFlow()
    private val _datePath = MutableStateFlow<String?>(null)
    val datePath: StateFlow<String?> = _datePath.asStateFlow()

    // 向导状态
    private val _step = MutableStateFlow(1)
    val step: StateFlow<Int> = _step.asStateFlow()

    private var editingId: String? = null
    private var appliedTemplateId: String? = null

    // 自动探测状态
    private val _probeState = MutableStateFlow<ProbeUiState>(ProbeUiState.Idle)
    val probeState: StateFlow<ProbeUiState> = _probeState.asStateFlow()

    sealed interface ProbeUiState {
        data object Idle : ProbeUiState
        data object Probing : ProbeUiState
        data class Found(val result: AutoProbe.ProbeResult, val keywordParam: String, val apiPath: String) : ProbeUiState
        data object Failed : ProbeUiState
    }

    // 导入状态
    private val _importState = MutableStateFlow<ImportUiState>(ImportUiState.Empty)
    val importState: StateFlow<ImportUiState> = _importState.asStateFlow()

    sealed interface ImportUiState {
        data object Empty : ImportUiState
        data object Invalid : ImportUiState
        data class Preview(val source: CustomSearchSource) : ImportUiState
    }

    /** 模式初始化：从模板/编辑/导入入口调用一次 */
    fun initMode(mode: EditorMode, templateId: String?, sourceId: String?, importText: String?) {
        when (mode) {
            EditorMode.TEMPLATE -> {
                val template = SearchSourceTemplates.byId(templateId.orEmpty())
                if (template != null && !template.wizardMode) {
                    applySource(template.defaults)
                    appliedTemplateId = template.id
                    _step.value = 3
                } else {
                    // blank 或未知：空白向导
                    applySource(SearchSourceTemplates.blank.defaults)
                    _step.value = 1
                }
            }
            EditorMode.BLANK -> {
                applySource(SearchSourceTemplates.blank.defaults)
                _step.value = 1
            }
            EditorMode.EDIT -> {
                val source = customSearchSourceStorage.sources.value.find { it.id == sourceId }
                if (source != null) {
                    editingId = source.id
                    applySource(source)
                    _step.value = 3
                }
            }
            EditorMode.IMPORT -> {
                _step.value = 3
                parseImport(importText.orEmpty())
            }
        }
    }

    private fun applySource(source: CustomSearchSource) {
        _name.value = source.name
        _baseUrl.value = source.baseUrl
        _apiPath.value = source.apiPath
        _keywordParam.value = source.keywordParam
        _cloudTypesParam.value = source.cloudTypesParam ?: ""
        _cloudTypesValue.value = source.cloudTypesValue ?: ""
        _srcParam.value = source.srcParam ?: ""
        _srcValue.value = source.srcValue ?: ""
        _parseMode.value = source.parseMode
        _listPath.value = source.listPath
        _namePath.value = source.namePath
        _urlPath.value = source.urlPath
        _diskTypePath.value = source.diskTypePath
        _datePath.value = source.datePath
    }

    fun nextStep() {
        if (_step.value < 3) _step.value += 1
    }

    fun prevStep() {
        if (_step.value > 1) _step.value -= 1
    }

    // ---- 自动探测 ----
    fun startProbe() {
        if (_probeState.value is ProbeUiState.Probing) return
        viewModelScope.launch {
            _probeState.value = ProbeUiState.Probing
            var found: ProbeUiState? = null
            for (variant in AutoProbe.variants()) {
                val probeSource = CustomSearchSource(
                    id = "probe", name = "probe", baseUrl = _baseUrl.value,
                    apiPath = variant.apiPath, keywordParam = variant.keywordParam,
                    enabled = true, parseMode = "custom"
                )
                val raw = customSearchService.fetchRaw(probeSource, "The Wandering Earth")
                if (raw != null) {
                    val result = AutoProbe.analyze(raw)
                    if (result != null) {
                        found = ProbeUiState.Found(result, variant.keywordParam, variant.apiPath)
                        break
                    }
                }
            }
            _probeState.value = found ?: ProbeUiState.Failed
            if (found is ProbeUiState.Found) {
                // 自动套用识别结果
                _apiPath.value = found.apiPath
                _keywordParam.value = found.keywordParam
                _parseMode.value = found.parseMode
                _listPath.value = found.listPath
                _namePath.value = found.namePath
                _urlPath.value = found.urlPath
                _diskTypePath.value = found.diskTypePath
                _datePath.value = found.datePath
            }
        }
    }

    /** 手动调整 JSONPath：跳到确认步骤并展开高级区（UI 侧控制展开态），解析模式切到 custom */
    fun switchToManual() {
        _parseMode.value = "custom"
        _step.value = 3
    }

    // ---- 导入 ----
    fun parseImport(text: String) {
        val decoded = ShareCodec.decode(text.trim())
        _importState.value = if (decoded == null) {
            ImportUiState.Invalid
        } else {
            ImportUiState.Preview(decoded)
        }
    }

    fun applyImportedSource(source: CustomSearchSource) {
        applySource(source)
        _step.value = 3
    }

    fun renameForImport(newName: String) {
        val current = _importState.value
        if (current is ImportUiState.Preview) {
            _importState.value = ImportUiState.Preview(current.source.copy(name = newName))
        }
    }

    /** 冲突检测：同名或同 baseUrl 已存在 */
    fun findConflict(): CustomSearchSource? =
        customSearchSourceStorage.sources.value.find { it.name == _name.value || it.baseUrl.trimEnd('/') == _baseUrl.value.trimEnd('/') }

    // ---- 保存 ----
    fun save(): CustomSearchSource? {
        val finalName = _name.value.trim()
        val finalBaseUrl = _baseUrl.value.trim()
        if (finalName.isBlank() || finalBaseUrl.isBlank()) return null
        val source = CustomSearchSource(
            id = editingId ?: java.util.UUID.randomUUID().toString(),
            name = finalName,
            baseUrl = finalBaseUrl,
            apiPath = _apiPath.value.trim(),
            keywordParam = _keywordParam.value.trim(),
            cloudTypesParam = _cloudTypesParam.value.trim().ifBlank { null },
            cloudTypesValue = _cloudTypesValue.value.trim().ifBlank { null },
            srcParam = _srcParam.value.trim().ifBlank { null },
            srcValue = _srcValue.value.trim().ifBlank { null },
            parseMode = _parseMode.value,
            listPath = _listPath.value?.trim()?.ifBlank { null },
            namePath = _namePath.value?.trim()?.ifBlank { null },
            urlPath = _urlPath.value?.trim()?.ifBlank { null },
            diskTypePath = _diskTypePath.value?.trim()?.ifBlank { null },
            datePath = _datePath.value?.trim()?.ifBlank { null }
        )
        viewModelScope.launch {
            if (editingId != null) customSearchSourceStorage.updateSource(source)
            else customSearchSourceStorage.addSource(source)
        }
        return source
    }

    /** 当前源（供保存/测试） */
    fun currentSource(): CustomSearchSource? {
        val n = _name.value.trim(); val b = _baseUrl.value.trim()
        if (n.isBlank() || b.isBlank()) return null
        return CustomSearchSource(
            id = editingId ?: "temp", name = n, baseUrl = b,
            apiPath = _apiPath.value.trim(), keywordParam = _keywordParam.value.trim(),
            cloudTypesParam = _cloudTypesParam.value.trim().ifBlank { null },
            cloudTypesValue = _cloudTypesValue.value.trim().ifBlank { null },
            srcParam = _srcParam.value.trim().ifBlank { null },
            srcValue = _srcValue.value.trim().ifBlank { null },
            parseMode = _parseMode.value,
            listPath = _listPath.value?.trim()?.ifBlank { null },
            namePath = _namePath.value?.trim()?.ifBlank { null },
            urlPath = _urlPath.value?.trim()?.ifBlank { null },
            diskTypePath = _diskTypePath.value?.trim()?.ifBlank { null },
            datePath = _datePath.value?.trim()?.ifBlank { null }
        )
    }
}
```

- [ ] **步骤 3：实现 SourceTemplateSheet（模板库 BottomSheet）**

```kotlin
package com.tracktosearch.ui.screen.searchsource

import com.tracktosearch.data.local.SearchSourceTemplate
import com.tracktosearch.data.local.SearchSourceTemplates

/** 模板库半屏弹层：网格展示内置模板 + 底部导入快捷通道 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourceTemplateSheet(
    onTemplateClick: (SearchSourceTemplate) -> Unit,
    onImport: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Text(
            text = stringResource(R.string.template_library_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            val chunks = SearchSourceTemplates.all.chunked(2)
            chunks.forEach { rowTemplates ->
                Row(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    rowTemplates.forEach { template ->
                        TemplateCell(
                            template = template,
                            onClick = { onTemplateClick(template) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    if (rowTemplates.size == 1) Spacer(modifier = Modifier.weight(1f))
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { onImport() }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.template_import_shortcut),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

/** 单个模板格：选中态描边 */
@Composable
private fun TemplateCell(
    template: SearchSourceTemplate,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable { onClick() }
            .padding(10.dp)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp)),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(template.icon, style = MaterialTheme.typography.headlineSmall)
        Text(template.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        Text(template.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
```

- [ ] **步骤 4：实现 ShareImportDialogs（分享弹层 + 导入确认弹层）**

```kotlin
package com.tracktosearch.ui.screen.searchsource

import android.content.Intent
import com.tracktosearch.data.local.CustomSearchSource
import com.tracktosearch.data.local.ShareCodec
import com.tracktosearch.data.remote.custom.CustomSearchService

/** 分享配置弹层：编码文本 + 复制 + 系统分享面板 */
@Composable
fun ShareSourceDialog(
    source: CustomSearchSource,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val text = remember(source.id) { ShareCodec.encode(source) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        title = { Text(stringResource(R.string.share_source_title, source.name)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.share_source_desc), style = MaterialTheme.typography.bodySmall)
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("source-config", text))
                            Toast.makeText(context, R.string.share_copy_success, Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text(stringResource(R.string.share_copy)) }
                    Button(
                        onClick = {
                            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, text)
                            }
                            context.startActivity(Intent.createChooser(sendIntent, null))
                        },
                        modifier = Modifier.weight(1.4f)
                    ) { Text(stringResource(R.string.share_to_apps)) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } }
    )
}

/** 导入配置弹层：粘贴 → 校验 → 预览 → 确认 */
@Composable
fun ImportSourceDialog(
    onConfirm: (CustomSearchSource) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var pasteText by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf<CustomSearchSource?>(null) }
    var invalid by remember { mutableStateOf(false) }
    var renameText by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        title = { Text(stringResource(R.string.editor_title_import)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = pasteText,
                    onValueChange = { newText ->
                        pasteText = newText
                        invalid = false
                        preview = null
                    },
                    label = { Text(stringResource(R.string.import_paste_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2
                )
                // 粘贴按钮：读取剪贴板
                TextButton(onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()?.let {
                        pasteText = it
                        parsePaste(it)
                    }
                }) { Text(stringResource(R.string.share_copy)) } // 复用按钮文案：显示「粘贴」逻辑上应为新文案，实现时新增 import_paste 字符串
                if (invalid) {
                    Text(stringResource(R.string.import_invalid), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                preview?.let { source ->
                    Text(stringResource(R.string.import_valid), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                    Column {
                        KeyValueRow(stringResource(R.string.import_preview_name), source.name.ifBlank { stringResource(R.string.settings_source_unnamed) })
                        KeyValueRow(stringResource(R.string.import_preview_url), source.baseUrl)
                        KeyValueRow(stringResource(R.string.import_preview_mode), parseModeLabel(source.parseMode))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = preview != null,
                onClick = { preview?.let { onConfirm(it) } }
            ) { Text(stringResource(R.string.import_confirm)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } }
    )
}

private fun parseModeLabel(mode: String): String = when (mode) {
    "pansou_template" -> "PanSou"
    "zreso_template" -> "Zreso"
    else -> "JSONPath"
}
```

**实现注意：**
- 新增字符串 `import_paste`（「粘贴」）——四语：en `Paste`、zh `粘贴`、ja `貼り付け`、ko `붙여넣기`。加到 T4 字符串任务或本任务内补（推荐本任务补，T4 已 commit 的话在此添加并随本任务 commit，保持四语同步）。
- `KeyValueRow` 为文件内私有组件（label 左 value 右）。
- 导入冲突提示由编辑器页在保存前调用 `viewModel.findConflict()` 展示（见步骤 5）。

- [ ] **步骤 5：实现 SearchSourceEditorScreen（3 步向导）**

```kotlin
package com.tracktosearch.ui.screen.searchsource

// 关键 import：hiltViewModel、collectAsStateWithLifecycle、HazeState/HazeMaterials/hazeSource/hazeEffect

/**
 * 分步向导编辑页：
 * 步骤 1 基本信息（名称+地址）→ 步骤 2 自动解析 → 步骤 3 确认参数。
 * 模板/编辑/导入模式直接进步骤 3。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchSourceEditorScreen(
    onBack: () -> Unit,
    onSaved: () -> Unit,
    viewModel: SearchSourceEditorViewModel = hiltViewModel()
) {
    val hazeState = remember { HazeState() }
    val name by viewModel.name.collectAsStateWithLifecycle()
    val baseUrl by viewModel.baseUrl.collectAsStateWithLifecycle()
    val apiPath by viewModel.apiPath.collectAsStateWithLifecycle()
    val keywordParam by viewModel.keywordParam.collectAsStateWithLifecycle()
    val cloudTypesParam by viewModel.cloudTypesParam.collectAsStateWithLifecycle()
    val cloudTypesValue by viewModel.cloudTypesValue.collectAsStateWithLifecycle()
    val srcParam by viewModel.srcParam.collectAsStateWithLifecycle()
    val srcValue by viewModel.srcValue.collectAsStateWithLifecycle()
    val parseMode by viewModel.parseMode.collectAsStateWithLifecycle()
    val listPath by viewModel.listPath.collectAsStateWithLifecycle()
    val namePath by viewModel.namePath.collectAsStateWithLifecycle()
    val urlPath by viewModel.urlPath.collectAsStateWithLifecycle()
    val diskTypePath by viewModel.diskTypePath.collectAsStateWithLifecycle()
    val datePath by viewModel.datePath.collectAsStateWithLifecycle()
    val step by viewModel.step.collectAsStateWithLifecycle()
    val probeState by viewModel.probeState.collectAsStateWithLifecycle()
    val importState by viewModel.importState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var advancedExpanded by remember { mutableStateOf(false) }
    var showConflictDialog by remember { mutableStateOf(false) }
    var testMessage by remember { mutableStateOf<String?>(null) }
    var isTesting by remember { mutableStateOf(false) }

    val title = stringResource(
        when {
            importState is SearchSourceEditorViewModel.ImportUiState.Preview -> R.string.editor_title_import
            else -> R.string.editor_title_add
        }
    )

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            Column(modifier = Modifier.fillMaxSize().hazeSource(hazeState).verticalScroll(rememberScrollState())) {
                // ---- 标题栏（毛玻璃吸顶）----
                EditorHeader(
                    title = title,
                    hazeState = hazeState,
                    onBack = onBack
                )
                // ---- 步骤指示器 ----
                StepIndicator(currentStep = step)

                when (step) {
                    1 -> StepBasic(
                        name = name, onNameChange = viewModel::setName,
                        baseUrl = baseUrl, onBaseUrlChange = viewModel::setBaseUrl
                    )
                    2 -> StepAutoProbe(
                        probeState = probeState,
                        onStartProbe = viewModel::startProbe,
                        onManual = viewModel::switchToManual
                    )
                    3 -> StepConfirm(
                        viewModel = viewModel,
                        name = name, onNameChange = viewModel::setName,
                        baseUrl = baseUrl, onBaseUrlChange = viewModel::setBaseUrl,
                        apiPath = apiPath, onApiPathChange = viewModel::setApiPath,
                        keywordParam = keywordParam, onKeywordParamChange = viewModel::setKeywordParam,
                        cloudTypesParam = cloudTypesParam, onCloudTypesParamChange = viewModel::setCloudTypesParam,
                        cloudTypesValue = cloudTypesValue, onCloudTypesValueChange = viewModel::setCloudTypesValue,
                        srcParam = srcParam, onSrcParamChange = viewModel::setSrcParam,
                        srcValue = srcValue, onSrcValueChange = viewModel::setSrcValue,
                        parseMode = parseMode, onParseModeChange = viewModel::setParseMode,
                        listPath = listPath, onListPathChange = viewModel::setListPath,
                        namePath = namePath, onNamePathChange = viewModel::setNamePath,
                        urlPath = urlPath, onUrlPathChange = viewModel::setUrlPath,
                        diskTypePath = diskTypePath, onDiskTypePathChange = viewModel::setDiskTypePath,
                        datePath = datePath, onDatePathChange = viewModel::setDatePath,
                        importState = importState,
                        onApplyImported = viewModel::applyImportedSource,
                        onImportRename = viewModel::renameForImport,
                        advancedExpanded = advancedExpanded,
                        onAdvancedToggle = { advancedExpanded = !advancedExpanded }
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            // ---- 底部操作栏 ----
            EditorBottomBar(
                step = step,
                canNext = when (step) {
                    1 -> name.isNotBlank() && baseUrl.isNotBlank()
                    2 -> probeState is SearchSourceEditorViewModel.ProbeUiState.Found ||
                            probeState is SearchSourceEditorViewModel.ProbeUiState.Failed ||
                            probeState is SearchSourceEditorViewModel.ProbeUiState.Idle // Idle 仅模板直达时出现
                    else -> false
                },
                onPrev = viewModel::prevStep,
                onNext = viewModel::nextStep,
                onTest = {
                    val source = viewModel.currentSource() ?: return@EditorBottomBar
                    isTesting = true
                    // 测试逻辑：与列表页 testCustomSource 相同，但以临时状态展示
                    // 简化：委托列表页 VM 不现实；在编辑器内直接调用 service 需要注入 —— 改为在 ViewModel 提供 testCurrent()
                    viewModel.testCurrent { message -> testMessage = message; isTesting = false }
                },
                isTesting = isTesting,
                testMessage = testMessage,
                onSave = {
                    val conflict = viewModel.findConflict()
                    if (conflict != null && conflict.id != viewModel.currentEditingId()) {
                        showConflictDialog = true
                    } else {
                        viewModel.save()
                        onSaved()
                    }
                },
                canSave = name.isNotBlank() && baseUrl.isNotBlank()
            )
        }
    }

    // 冲突确认弹窗
    if (showConflictDialog) {
        AlertDialog(
            onDismissRequest = { showConflictDialog = false },
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            title = { Text(stringResource(R.string.search_sources_title)) },
            text = { Text(stringResource(R.string.import_duplicate_warning)) },
            confirmButton = {
                TextButton(onClick = { showConflictDialog = false; viewModel.save(); onSaved() }) { Text(stringResource(R.string.import_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showConflictDialog = false }) { Text(stringResource(android.R.string.cancel)) }
            }
        )
    }
}
```

**ViewModel 补丁**（加到 SearchSourceEditorViewModel，编辑器内测试用）：

```kotlin
    fun currentEditingId(): String? = editingId

    /** 编辑器内测试当前表单（列表页逻辑的临时版本） */
    fun testCurrent(onResult: (String?) -> Unit) {
        val source = currentSource() ?: return
        viewModelScope.launch {
            val result = customSearchService.testSource(source)
            onResult(when (result) {
                is CustomSearchService.TestResult.Success ->
                    if (result.count > 0) "成功 ${result.count} 条：${result.sampleName ?: ""}"
                    else "成功 0 条"
                is CustomSearchService.TestResult.Error -> result.message
            })
        }
    }
```

测试结果文案使用现有 `snackbar_test_success`/`snackbar_test_empty`/`error_unknown` 字符串（用 context 取，不硬编码）。

**步骤内子组件**（同文件私有）：
- `EditorHeader`：毛玻璃标题栏（复用列表页 HeaderBar 样式）
- `StepIndicator`：3 格进度（当前步骤高亮，`editor_step_basic/auto/confirm`）
- `StepBasic`：`CustomSourceFields` 仅渲染名称+地址两字段 + 模板提示（`appliedTemplateId` 需暴露——ViewModel 加 `val appliedTemplateName: StateFlow<String?>`，`applySource` 时记录模板名）
- `StepAutoProbe`：按钮「开始解析」→ Probing 转圈 → Found 高亮路径列表（`editor_probe_result_list/title/url/disk/date` 标签 + 等宽路径文本 + 关键词参数）+ 「识别不准？」入口 → Failed 提示 + 手动入口
- `StepConfirm`：URL 预览（`${baseUrl.trimEnd('/')}/${apiPath.trimStart('/')}?keywordParam=…` 等宽）+ 参数汇总行 + 「展开全部高级参数」→ `CustomSourceFields` 全量渲染 + 导入预览态（ImportSourceDialog 已确认的 source 显示预览 + 可改名）

**ViewModel 补丁 2**（appliedTemplateName + setter 们 + 编辑模式标记）：

```kotlin
    private val _appliedTemplateName = MutableStateFlow<String?>(null)
    val appliedTemplateName: StateFlow<String?> = _appliedTemplateName.asStateFlow()

    fun setName(v: String) { _name.value = v }
    fun setBaseUrl(v: String) { _baseUrl.value = v }
    fun setApiPath(v: String) { _apiPath.value = v }
    fun setKeywordParam(v: String) { _keywordParam.value = v }
    fun setCloudTypesParam(v: String) { _cloudTypesParam.value = v }
    fun setCloudTypesValue(v: String) { _cloudTypesValue.value = v }
    fun setSrcParam(v: String) { _srcParam.value = v }
    fun setSrcValue(v: String) { _srcValue.value = v }
    fun setParseMode(v: String) { _parseMode.value = v }
    fun setListPath(v: String?) { _listPath.value = v }
    fun setNamePath(v: String?) { _namePath.value = v }
    fun setUrlPath(v: String?) { _urlPath.value = v }
    fun setDiskTypePath(v: String?) { _diskTypePath.value = v }
    fun setDatePath(v: String?) { _datePath.value = v }
```

`initMode` 的 TEMPLATE 分支中 `appliedTemplateId` 赋值处同步 `_appliedTemplateName.value = template.name`；EDIT/IMPORT 分支置 null。

- [ ] **步骤 6：编译验证**

运行：`gradlew.bat :app:compileDebugKotlin -q`
预期：BUILD SUCCESSFUL。常见修复点：
- `CustomSearchSourceStorage.sources.value` —— sources 是 StateFlow，`.value` 可直接读（同 storage 内 _sources）
- PanHubConfig 包路径（T5 已确认）
- `hazeSource`/`hazeEffect` 修饰符用法与项目其他页面一致

- [ ] **步骤 7：Commit**

```bash
git add app/src/main/java/com/tracktosearch/ui/screen/searchsource/ app/src/main/res/values/strings.xml app/src/main/res/values-zh/strings.xml app/src/main/res/values-ja/strings.xml app/src/main/res/values-ko/strings.xml
git commit -m "feat(search-source): 分步向导编辑页与模板库分享导入"
```

---

### 任务 T7：导航接入 + 设置页入口替换 + 旧代码清理

**文件：**
- 修改：`app/src/main/java/com/tracktosearch/ui/navigation/AppNavigation.kt`
- 修改：`app/src/main/java/com/tracktosearch/ui/screen/settings/SettingsScreen.kt`
- 修改：`app/src/main/java/com/tracktosearch/ui/screen/settings/SettingsDialogs.kt`（删 CustomSourceEditDialog L657-857）
- 修改：`app/src/main/java/com/tracktosearch/ui/screen/settings/SettingsComponents.kt`（删 SearchSourceCard/AdaptiveSourceNameText/SearchSourceAddCard/CustomSearchSourceItem）
- 修改：`app/src/main/java/com/tracktosearch/ui/screen/settings/SettingsViewModel.kt`（删自定义源/内置源启停/测试结果相关，保留 panHubConfig 仅当设置页其他地方还引用——检查后决定；搜索区迁移后应无引用，可删）

- [ ] **步骤 1：AppNavigation 加路由**

在 `Routes` object（AppNavigation.kt L120-140）加：

```kotlin
    const val SEARCH_SOURCES = "searchSources"
    const val SEARCH_SOURCE_EDITOR = "searchSourceEditor/{mode}/{payload}"

    fun searchSourceEditorRoute(mode: String, payload: String = ""): String =
        "searchSourceEditor/$mode/${java.net.URLEncoder.encode(payload, "UTF-8")}"
```

在 NavHost 的 MAIN composable 之后（如 L1096 MARK_RECORDS 前）加两个 composable：

```kotlin
                composable(Routes.SEARCH_SOURCES) {
                    CompositionLocalProvider(LocalAnimatedVisibilityScope provides this@composable) {
                        SearchSourcesScreen(
                            onBack = { navController.popBackStack() },
                            onAddFromTemplate = { templateId ->
                                navController.navigate(
                                    Routes.searchSourceEditorRoute(
                                        when (templateId) {
                                            "blank" -> "blank"
                                            else -> "template"
                                        },
                                        templateId
                                    )
                                )
                            },
                            onEditSource = { sourceId ->
                                navController.navigate(Routes.searchSourceEditorRoute("edit", sourceId))
                            },
                            onImport = {
                                navController.navigate(Routes.searchSourceEditorRoute("import"))
                            }
                        )
                    }
                }

                composable(Routes.SEARCH_SOURCE_EDITOR) { backStackEntry ->
                    CompositionLocalProvider(LocalAnimatedVisibilityScope provides this@composable) {
                        val mode = backStackEntry.arguments?.getString("mode")
                        val payload = backStackEntry.arguments?.getString("payload")?.let {
                            java.net.URLDecoder.decode(it, "UTF-8")
                        }.orEmpty()
                        val editorViewModel: SearchSourceEditorViewModel = hiltViewModel(backStackEntry)
                        LaunchedEffect(mode, payload) {
                            editorViewModel.initMode(
                                mode = when (mode) {
                                    "template" -> EditorMode.TEMPLATE
                                    "edit" -> EditorMode.EDIT
                                    "import" -> EditorMode.IMPORT
                                    else -> EditorMode.BLANK
                                },
                                templateId = payload,
                                sourceId = payload,
                                importText = payload
                            )
                        }
                        SearchSourceEditorScreen(
                            onBack = { navController.popBackStack() },
                            onSaved = { navController.popBackStack() }
                        )
                    }
                }
```

加 import：`com.tracktosearch.ui.screen.searchsource.SearchSourcesScreen`、`SearchSourceEditorScreen`、`SearchSourceEditorViewModel`、`EditorMode`。

**注意：** import 模式只有文本没有 templateId/sourceId——payload 传入 importText。template 模式 payload=templateId，edit 模式 payload=sourceId。initMode 内各自只用对应参数。

- [ ] **步骤 2：SettingsScreen 搜索分组替换为单一入口**

原 `item(key = "group_search")`（L512-514 附近）的 `SearchSourcesItem(viewModel, hazeState)` 替换为：

```kotlin
        item(key = "group_search") {
            SettingsGroupCard(
                title = stringResource(R.string.search_sources_manage_entry_title),
                hazeState = hazeState
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSearchSourcesClick() }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.search_sources_manage_entry_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(
                        Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
```

`SettingsScreen` 参数新增 `onSearchSourcesClick: () -> Unit = {}`（L159-181 参数区）。

- [ ] **步骤 3：删除旧弹窗与组件**

- `SettingsDialogs.kt`：删除 `CustomSourceEditDialog`（L657-857）。保留 `PanHubConfigDialog`（列表页复用）。
- `SettingsComponents.kt`：删除 `SearchSourceCard`、`AdaptiveSourceNameText`、`SearchSourceAddCard`、`CustomSearchSourceItem`（L208-290、L441-570）。确认无其他引用后删除（grep `SearchSourceCard|SearchSourceAddCard|CustomSearchSourceItem|AdaptiveSourceNameText` 全项目）。
- `SettingsScreen.kt`：删除 `SearchSourcesItem` 函数（L1126-1270）及其内部状态。删除相关 import（CustomSearchSource、CustomSearchSourceItem、CustomSourceEditDialog、PanHubConfigDialog、GroupDivider 若仅此处用）。
- `SettingsViewModel.kt`：删除内置源启停（L136-140、L192-202）、自定义源（L230-278）相关。**先 grep 确认** `pansouEnabled|panhubEnabled|zresoEnabled|customSources|testCustomSource|addCustomSource|updateCustomSource|deleteCustomSource|setCustomSourceEnabled|TestResultState|testResults|panHubConfig` 在 SettingsScreen 不再被引用后删除（panHubConfig 若 SearchSourcesItem 删除后无引用则一并删，`setPanHub*` 同）。
- 检查 `SettingsViewModel` 构造参数 `searchSourceStorage`/`customSearchSourceStorage`/`customSearchService` 删除后是否仍被构造使用——若不再使用从构造函数移除（影响 Hilt 注入与 SettingsViewModelTest 的构造调用，同步更新测试）。

- [ ] **步骤 4：编译 + 全量单测**

运行：`gradlew.bat :app:testDebugUnitTest -q`
预期：BUILD SUCCESSFUL + 全部测试通过（含 SettingsViewModelTest——其构造签名可能因删除参数变化，同步更新）

- [ ] **步骤 5：Commit**

```bash
git add app/src/main/java/com/tracktosearch/ui/navigation/AppNavigation.kt app/src/main/java/com/tracktosearch/ui/screen/settings/SettingsScreen.kt app/src/main/java/com/tracktosearch/ui/screen/settings/SettingsDialogs.kt app/src/main/java/com/tracktosearch/ui/screen/settings/SettingsComponents.kt app/src/main/java/com/tracktosearch/ui/screen/settings/SettingsViewModel.kt app/src/test/java/com/tracktosearch/ui/screen/settings/SettingsViewModelTest.kt
git commit -m "refactor(search-source): 设置页入口替换为管理页并清理旧弹窗"
```

---

### 任务 T8：帮助页更新

**文件：**
- 修改：`app/src/main/java/com/tracktosearch/ui/screen/help/HelpScreen.kt`

- [ ] **步骤 1：阅读现有帮助页结构**

运行：`grep -n "搜索源\|search\|search_source" app/src/main/java/com/tracktosearch/ui/screen/help/HelpScreen.kt`
先定位搜索源相关帮助条目位置与条目组件结构。

- [ ] **步骤 2：更新帮助内容**

在搜索源相关段落更新/新增：
- 搜索源管理入口：设置 → 搜索源
- 内置源：PanSou/Panhub/Zreso 启停，Panhub 可配置
- 添加自定义源：模板一键添加；空白自定义走三步向导（基本信息 → 自动解析 → 确认）；支持 JSONPath 自动识别与手动调整
- 分享/导入：列表项分享按钮生成配置文本；对方「导入」粘贴即可；同名冲突会提示

文案用现有帮助条目样式（标题 + 说明行），新增字符串按帮助页惯例加四语（若帮助页字符串也走 strings.xml，四语同步；若帮助文案在代码里，直接写中文+对应语言分支）。

- [ ] **步骤 3：编译验证 + Commit**

运行：`gradlew.bat :app:compileDebugKotlin -q`
预期：BUILD SUCCESSFUL

```bash
git add app/src/main/java/com/tracktosearch/ui/screen/help/HelpScreen.kt app/src/main/res/values/strings.xml app/src/main/res/values-zh/strings.xml app/src/main/res/values-ja/strings.xml app/src/main/res/values-ko/strings.xml
git commit -m "docs(help): 搜索源管理帮助说明更新"
```

---

### 任务 T9：整体验证

- [ ] **步骤 1：全量构建 + 单测**

运行：`gradlew.bat :app:testDebugUnitTest assembleDebug -q`
预期：BUILD SUCCESSFUL，所有单测通过

- [ ] **步骤 2：模拟器走查主流程**

```bash
adb devices                          # 确认设备在线
gradlew.bat :app:installDebug -q
adb shell am start -n com.tracktosearch/.MainActivity
```

走查流程：
1. 设置 → 搜索源 → 列表页：内置源三卡 + 启停、自定义区空态、底部模板卡
2. 点「+」→ 模板 BottomSheet → 选 PanSou 公共源 → 直接进确认步骤 → 保存 → 回列表可见新源
3. 列表项：测试（结果消息）、编辑（进向导确认步）、分享（复制文本成功 Toast）、启停
4. 导入：点顶部导入按钮 → 粘贴步骤 3 复制的文本 → 有效配置预览 → 确认导入 → 列表出现
5. 空白自定义：模板库选「空白自定义」→ 步骤 1 填名称+地址 → 步骤 2 开始解析（对 PanSou 地址应识别成功）→ 步骤 3 保存
6. 回到搜索页搜索关键词，新源结果出现（若启用）
7. `adb logcat -b crash -d` 无崩溃

- [ ] **步骤 3：走查截图记录**

`adb exec-out screencap -p > 走查图.png`（用 cmd 重定向避免损坏），关键页面截图存档。确认毛玻璃吸顶、沉浸式导航栏符合页面模板。

- [ ] **步骤 4：总结**

无遗留问题即可完成；如有发现，按 Bug 修复工作流修复后另行 commit。
