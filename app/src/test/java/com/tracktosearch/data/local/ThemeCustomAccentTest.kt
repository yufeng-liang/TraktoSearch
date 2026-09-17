package com.tracktosearch.data.local

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * [ThemeStorage] 自定义色调**收藏列表**的序列化与旧单值 key 迁移。
 *
 * 只调 companion 上的纯函数（[ThemeStorage.decodeAccentColors] /
 * [ThemeStorage.encodeAccentColors] / [ThemeStorage.mergeLegacyAccent]），
 * 不构造 [ThemeStorage]：它背后是进程单例 `preferencesDataStore`，各用例建实例仍
 * 读同一份磁盘数据，跨用例互相污染。纯 JVM 跑，被测路径上一个平台调用都没有。
 *
 * 设计依据：把老的「单个自定义色」升格为「上限 8 个自定义色收藏」，旧
 * `custom_accent_argb` key 首次读到即并入列表并删除（见 [ThemeStorage] init）。
 */
class ThemeCustomAccentTest {

    @Test
    fun `缺值与空串都解析为空列表`() {
        assertThat(ThemeStorage.decodeAccentColors(null)).isEmpty()
        assertThat(ThemeStorage.decodeAccentColors("")).isEmpty()
    }

    @Test
    fun `正常逗号分隔串按序解析`() {
        assertThat(ThemeStorage.decodeAccentColors("4278190080,4294901760,16711680"))
            .isEqualTo(listOf(4278190080L, 4294901760L, 16711680L))
    }

    @Test
    fun `脏数据段丢弃且不抛异常`() {
        // "x"、"  "、悬空逗号都不是数字，必须静默丢弃而非让 toLongOrNull 抛出去
        assertThat(ThemeStorage.decodeAccentColors("1,x,3,,5"))
            .isEqualTo(listOf(1L, 3L, 5L))
    }

    @Test
    fun `重复色去重保序`() {
        assertThat(ThemeStorage.decodeAccentColors("9,1,9,2,1"))
            .isEqualTo(listOf(9L, 1L, 2L))
    }

    @Test
    fun `超出上限的段被截断`() {
        val raw = (1L..10L).joinToString(",")
        assertThat(ThemeStorage.decodeAccentColors(raw).size)
            .isEqualTo(ThemeStorage.MAX_CUSTOM_ACCENTS)
    }

    @Test
    fun `写读往返无损`() {
        val colors = listOf(0xFF123456L, 0xFE000000L)
        assertThat(ThemeStorage.decodeAccentColors(ThemeStorage.encodeAccentColors(colors)))
            .isEqualTo(colors)
    }

    @Test
    fun `迁移：无旧值则列表原样且无迁移选中`() {
        val colors = listOf(1L, 2L)
        assertThat(ThemeStorage.mergeLegacyAccent(colors, null)).isEqualTo(colors to null)
    }

    @Test
    fun `迁移：旧值已在列表则不动且不重复选中`() {
        val colors = listOf(1L, 2L)
        // 旧 key 有值但列表里已经有同色，说明用户已在新结构下操作过，无需再迁
        assertThat(ThemeStorage.mergeLegacyAccent(colors, 2L)).isEqualTo(colors to null)
    }

    @Test
    fun `迁移：旧值未收藏则并入尾部并作为选中值`() {
        assertThat(ThemeStorage.mergeLegacyAccent(listOf(1L, 2L), 3L))
            .isEqualTo(listOf(1L, 2L, 3L) to 3L)
    }

    @Test
    fun `迁移：列表已满则放弃并入且不选中旧值`() {
        // 满 8 时旧色无处安放：保持现状、不覆盖任何已收藏色，选中也不该指向一个
        // 根本不在列表里的值（Theme 层会把不在列表中的选中当未选中处理）
        val full = (1L..ThemeStorage.MAX_CUSTOM_ACCENTS.toLong()).toList()
        assertThat(ThemeStorage.mergeLegacyAccent(full, 999L)).isEqualTo(full to null)
    }

    @Test
    fun `上限常量守住八色收藏契约`() {
        assertWithMessage("收藏上限变更需同步 UI 层「满额禁用添加入口」与迁移截断逻辑")
            .that(ThemeStorage.MAX_CUSTOM_ACCENTS).isEqualTo(8)
    }
}