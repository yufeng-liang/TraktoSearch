package com.tracktosearch.data.remote.custom

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Test

class JsonPathParserTest {

    /**
     * 样例 JSON 结构：
     * {
     *   "a": {
     *     "b": { "c": "value_abc" },
     *     "arr": ["elem0", "elem1", "elem2"]
     *   },
     *   "items": [
     *     { "name": "item0" },
     *     { "name": "item1" }
     *   ]
     * }
     */
    private val sampleRoot = buildJsonObject {
        put("a", buildJsonObject {
            put("b", buildJsonObject {
                put("c", "value_abc")
            })
            putJsonArray("arr") {
                add(JsonPrimitive("elem0"))
                add(JsonPrimitive("elem1"))
                add(JsonPrimitive("elem2"))
            }
        })
        putJsonArray("items") {
            add(buildJsonObject { put("name", "item0") })
            add(buildJsonObject { put("name", "item1") })
        }
    }

    @Test
    fun extractList_simpleDotPath_returnsTargetElement() {
        val result = JsonPathParser.extractList(sampleRoot, "a.b.c")
        assertThat(result).hasSize(1)
        assertThat(result[0].jsonPrimitive.content).isEqualTo("value_abc")
    }

    @Test
    fun extractList_arrayIndex_returnsSpecifiedElement() {
        val result = JsonPathParser.extractList(sampleRoot, "a.arr[0]")
        assertThat(result).hasSize(1)
        assertThat(result[0].jsonPrimitive.content).isEqualTo("elem0")
    }

    @Test
    fun extractList_arrayWildcard_returnsAllElements() {
        val result = JsonPathParser.extractList(sampleRoot, "a.arr[*]")
        assertThat(result).hasSize(3)
        assertThat(result[0].jsonPrimitive.content).isEqualTo("elem0")
        assertThat(result[1].jsonPrimitive.content).isEqualTo("elem1")
        assertThat(result[2].jsonPrimitive.content).isEqualTo("elem2")
    }

    @Test
    fun extractList_nestedPath_returnsTarget() {
        val result = JsonPathParser.extractList(sampleRoot, "items[0].name")
        assertThat(result).hasSize(1)
        assertThat(result[0].jsonPrimitive.content).isEqualTo("item0")
    }

    @Test
    fun extractList_nonExistentPath_returnsEmptyList() {
        val result = JsonPathParser.extractList(sampleRoot, "x.y.z")
        assertThat(result).isEmpty()
    }

    @Test
    fun extractList_emptyPath_returnsRootInList() {
        // 源码行为：parsePath("") 返回 emptyList，extractList 跳过循环直接返回 listOf(root)
        val result = JsonPathParser.extractList(sampleRoot, "")
        assertThat(result).hasSize(1)
        assertThat(result[0]).isEqualTo(sampleRoot)
    }

    @Test
    fun extractList_dollarPrefixPath_alsoWorks() {
        // 源码 parsePath 会移除前导 "$" 与 "."，故 "$.a.b.c" 与 "a.b.c" 等价
        val result = JsonPathParser.extractList(sampleRoot, "$.a.b.c")
        assertThat(result).hasSize(1)
        assertThat(result[0].jsonPrimitive.content).isEqualTo("value_abc")
    }

    @Test
    fun extractList_arrayIndexOutOfBounds_returnsEmptyList() {
        val result = JsonPathParser.extractList(sampleRoot, "a.arr[99]")
        assertThat(result).isEmpty()
    }

    @Test
    fun extractList_arrayAllOnObject_flattensAllValues() {
        // 源码对 ArrayAll 处理：JsonObject 时扁平化所有 values
        val result = JsonPathParser.extractList(sampleRoot, "a[*]")
        assertThat(result).hasSize(2)
        // a 下有 b、arr 两个字段，扁平化为两个元素
        val keys = result.filterIsInstance<JsonObject>().mapNotNull { it["c"]?.jsonPrimitive?.contentOrNull }
        assertThat(keys).contains("value_abc")
    }

    @Test
    fun extractString_validPath_returnsStringValue() {
        val target = JsonPathParser.extractList(sampleRoot, "a.b")[0]
        val result = JsonPathParser.extractString(target, "c")
        assertThat(result).isEqualTo("value_abc")
    }

    @Test
    fun extractString_nonExistentPath_returnsNull() {
        val target = JsonPathParser.extractList(sampleRoot, "a.b")[0]
        val result = JsonPathParser.extractString(target, "nonexistent")
        assertThat(result).isNull()
    }

    @Test
    fun extractString_relativeNestedPath_returnsStringValue() {
        // 相对路径包含数组索引：从 items[0] 提取 name
        val target = JsonPathParser.extractList(sampleRoot, "items")[0]
        // target 是 items 数组本身，extractString 走 ArrayIndex 分支
        val result = JsonPathParser.extractString(target, "[0].name")
        assertThat(result).isEqualTo("item0")
    }

    @Test
    fun extractString_onNonPrimitive_returnsNull() {
        // 终止元素是对象而非 JsonPrimitive，应返回 null
        val target = JsonPathParser.extractList(sampleRoot, "a")[0]
        val result = JsonPathParser.extractString(target, "b")
        assertThat(result).isNull()
    }
}
