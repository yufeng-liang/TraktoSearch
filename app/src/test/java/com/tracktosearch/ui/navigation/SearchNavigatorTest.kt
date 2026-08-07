package com.tracktosearch.ui.navigation

import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test

/**
 * SearchNavigator 请求状态契约测试。
 *
 * JVM 测试只验证 request()/consume() 的幂等状态；
 * Android Intent 解析留给后续 androidTest。
 */
class SearchNavigatorTest {

    @Before
    fun setUp() {
        SearchNavigator.consume()
    }

    @Test
    fun 初始状态没有待处理请求() {
        assertThat(SearchNavigator.pending.value).isFalse()
    }

    @Test
    fun request后保留一个待处理请求且重复请求不改变状态() {
        SearchNavigator.request()
        SearchNavigator.request()

        assertThat(SearchNavigator.pending.value).isTrue()
    }

    @Test
    fun consume后清除待处理请求且重复消费保持清除状态() {
        SearchNavigator.request()

        SearchNavigator.consume()
        SearchNavigator.consume()

        assertThat(SearchNavigator.pending.value).isFalse()
    }
}
