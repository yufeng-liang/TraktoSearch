package com.tracktosearch.ui.screen.watchlist

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PinyinSearchTest {

    private fun matches(query: String) = PinyinSearch.matches("花束般的恋爱", query)

    @Test
    fun `拼音全拼前缀命中`() {
        assertThat(matches("huashu")).isTrue()
    }

    @Test
    fun `拼音全拼无空格连续命中`() {
        assertThat(matches("huashubandelianai")).isTrue()
    }

    @Test
    fun `拼音首字母缩写前缀命中`() {
        assertThat(matches("hsb")).isTrue()
        assertThat(matches("hsbd")).isTrue()
    }

    @Test
    fun `拼音完整缩写命中`() {
        assertThat(matches("hsbdla")).isTrue()
    }

    @Test
    fun `不匹配的拼音不命中`() {
        assertThat(matches("xyz")).isFalse()
        assertThat(matches("huashuzzz")).isFalse()
    }

    @Test
    fun `部分拼音前缀命中`() {
        // "huash" 是"花"拼音前缀，输入到一半也应命中
        assertThat(matches("huash")).isTrue()
    }

    @Test
    fun `汉字查询不做拼音匹配`() {
        assertThat(matches("花束")).isFalse()
    }

    @Test
    fun `大小写不敏感`() {
        assertThat(matches("HuaShu")).isTrue()
    }
}
