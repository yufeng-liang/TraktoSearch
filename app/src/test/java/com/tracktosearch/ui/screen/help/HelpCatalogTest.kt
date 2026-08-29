package com.tracktosearch.ui.screen.help

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 帮助页目录的完整性单测。
 *
 * 目录是全页唯一真源：渲染顺序、编号、分组、深链定位、搜索索引都从它推导。以前这些
 * 信息散在四处，漏改一处不会报错——只是某段搜不到，或者深链跳到隔壁段。这些断言就是
 * 把那类静默失效变成红色测试。
 */
class HelpCatalogTest {

    /** 反射拿 [HelpSections] 里所有 key 常量：新加常量却忘了写目录条目，得在这里炸。 */
    private val declaredKeys: List<String> = HelpSections::class.java.declaredFields
        .filter { it.type == String::class.java }
        .map { field ->
            field.isAccessible = true
            field.get(null) as String
        }

    @Test
    fun `目录覆盖了每一个段落 key`() {
        assertThat(HelpCatalog.map { it.key }).containsExactlyElementsIn(declaredKeys)
    }

    @Test
    fun `段落 key 不重复`() {
        val keys = HelpCatalog.map { it.key }
        assertThat(keys).hasSize(keys.toSet().size)
    }

    @Test
    fun `同组段落在目录里连续摆放`() {
        // 折叠相邻重复后还剩几个分组，就说明每个分组只出现过一段连续区间
        val collapsed = HelpCatalog.map { it.group }.zipWithNext()
            .filter { (a, b) -> a != b }
            .map { it.second }
        val groupRuns = listOf(HelpCatalog.first().group) + collapsed
        assertThat(groupRuns).containsNoDuplicates()
    }

    @Test
    fun `每个分组都有段落`() {
        assertThat(HelpGroupBlocks.map { it.group })
            .containsExactlyElementsIn(HelpGroup.entries)
    }

    @Test
    fun `每段至少有一条正文或一块附加内容`() {
        HelpCatalog.forEach { spec ->
            assertThat(spec.bullets.isNotEmpty() || spec.extra != null).isTrue()
        }
    }

    @Test
    fun `分组切分保住目录顺序和编号`() {
        val indices = HelpGroupBlocks.flatMap { block -> block.sections.map { it.index } }
        assertThat(indices).isEqualTo(HelpCatalog.indices.toList())
    }

    @Test
    fun `searchable 把标题条目和附加文案都收进来`() {
        val spec = HelpCatalog.first { it.key == HelpSections.CUSTOM_SOURCE }
        assertThat(spec.searchable).contains(spec.title)
        assertThat(spec.searchable).containsAtLeastElementsIn(spec.bullets)
        assertThat(spec.searchable).containsAtLeastElementsIn(spec.searchOnly)
        assertThat(spec.searchOnly).isNotEmpty()
    }

    @Test
    fun `深链认得每一个段落 key`() {
        declaredKeys.forEach { key ->
            assertThat(helpIndexOf(key)).isNotNull()
        }
    }

    @Test
    fun `深链遇到不认识的 key 返回 null`() {
        assertThat(helpIndexOf("notASection")).isNull()
    }

    @Test
    fun `深链没带参数时返回 null`() {
        assertThat(helpIndexOf(null)).isNull()
    }

    @Test
    fun `空搜索词一律算命中`() {
        assertThat(helpMatchesQuery(listOf("搜索功能"), "")).isTrue()
        assertThat(helpMatchesQuery(listOf("搜索功能"), "   ")).isTrue()
        // 连文案都没有的段落，空词照样算「没在筛」
        assertThat(helpMatchesQuery(emptyList(), "")).isTrue()
    }

    @Test
    fun `搜索命中任一条文案即算命中整段`() {
        val texts = listOf("搜索功能", "聚合四个搜索源", "支持人物搜索")
        assertThat(helpMatchesQuery(texts, "人物")).isTrue()
    }

    @Test
    fun `搜索大小写不敏感`() {
        assertThat(helpMatchesQuery(listOf("parseMode 决定怎么解析"), "PARSEMODE")).isTrue()
        assertThat(helpMatchesQuery(listOf("IMDb 导入"), "imdb")).isTrue()
    }

    @Test
    fun `搜索词前后空格不影响命中`() {
        assertThat(helpMatchesQuery(listOf("豆瓣回写"), "  豆瓣  ")).isTrue()
    }

    @Test
    fun `一条都不含时不算命中`() {
        assertThat(helpMatchesQuery(listOf("搜索功能", "想看与已看"), "热力图")).isFalse()
    }
}
