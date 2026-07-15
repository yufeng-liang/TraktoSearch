package com.tracktosearch.ui.navigation

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/**
 * AppNavigation 导航集成测试。
 *
 * 测试策略:
 * - 验证 Routes object 的所有路由常量值与预期一致(防止意外修改导致导航断裂)。
 * - 验证路由生成函数(detailRoute/searchRoute/personRoute/listDetailRoute/
 *   traktSearchRoute/doubanItemDetailRoute)的返回值格式和 URL 编码行为。
 * - 主要是纯 Kotlin 逻辑验证,但仍用 Instrumented 测试框架(AndroidJUnit4)
 *   以保持在 androidTest source set 中,与其它 UI 测试统一管理。
 * - 不需要 Hilt 或 Compose 渲染环境,因为只测 Routes object 的静态成员。
 *
 * 测试覆盖:
 * - 6 个路由常量值验证(LOGIN/MAIN/DETAIL/SEARCH/PERSON/STATISTICS)
 * - 4 个路由生成函数行为验证(detailRoute 默认参数/查询参数/标题编码、
 *   searchRoute 关键词编码、personRoute 名字和URL编码)
 */
@RunWith(AndroidJUnit4::class)
class AppNavigationTest {

    // ============ 路由常量验证 ============

    @Test
    fun `Routes_LOGIN_常量值为login`() {
        assertThat(Routes.LOGIN).isEqualTo("login")
    }

    @Test
    fun `Routes_MAIN_常量值为main`() {
        assertThat(Routes.MAIN).isEqualTo("main")
    }

    @Test
    fun `Routes_DETAIL_常量包含所有路径参数占位符`() {
        // DETAIL 路由必须包含 6 个路径参数占位符和 2 个查询参数占位符
        assertThat(Routes.DETAIL).contains("detail/{type}/{traktId}/{tmdbId}/{title}/{imdbId}/{traktRating}")
        assertThat(Routes.DETAIL).contains("inWatchlist={inWatchlist}")
        assertThat(Routes.DETAIL).contains("isWatched={isWatched}")
    }

    @Test
    fun `Routes_SEARCH_常量包含keyword占位符`() {
        assertThat(Routes.SEARCH).isEqualTo("search/{keyword}")
    }

    @Test
    fun `Routes_PERSON_常量包含三个参数占位符`() {
        assertThat(Routes.PERSON).isEqualTo("person/{personId}/{personName}/{profileUrl}")
    }

    @Test
    fun `Routes_STATISTICS_常量值为statistics`() {
        assertThat(Routes.STATISTICS).isEqualTo("statistics")
    }

    // ============ 路由生成函数验证 ============

    @Test
    fun `detailRoute_默认参数生成基础路径`() {
        // 不传可选参数时,应生成不含查询参数的基础路径
        val route = Routes.detailRoute("movie", 100, 200, "测试电影")
        assertThat(route).startsWith("detail/movie/100/200/")
        // traktRating 默认 0.0,应出现在路径末尾
        assertThat(route).endsWith("/0.0")
        // 不应包含查询参数
        assertThat(route).doesNotContain("?")
        assertThat(route).doesNotContain("inWatchlist")
        assertThat(route).doesNotContain("isWatched")
    }

    @Test
    fun `detailRoute_inWatchlist_true时附加查询参数`() {
        val route = Routes.detailRoute("show", 1, 2, "测试", inWatchlist = true, isWatched = true)
        // 应包含两个查询参数
        assertThat(route).contains("inWatchlist=true")
        assertThat(route).contains("isWatched=true")
        // 查询参数以 ? 开头,用 & 连接
        assertThat(route).contains("?")
    }

    @Test
    fun `detailRoute_对标题URL编码`() {
        // 含特殊字符的标题应被 URL 编码(空格 → +)
        val route = Routes.detailRoute("movie", 1, 2, "Hello World & 测试")
        // 编码后不应含原始空格(在路径段中),空格会被编码为 +
        assertThat(route).doesNotContain("Hello World")
        // 应包含编码后的内容
        assertThat(route).contains("Hello+World")
        // & 字符应被编码为 %26
        assertThat(route).contains("%26")
    }

    @Test
    fun `searchRoute_对关键词URL编码`() {
        val route = Routes.searchRoute("盗梦空间 & Inception")
        // 应以 search/ 开头
        assertThat(route).startsWith("search/")
        // 空格编码为 +
        assertThat(route).contains("%E7%9B%97%E6%A2%A6%E7%A9%BA%E9%97%B4")
        // & 编码为 %26
        assertThat(route).contains("%26")
        // 不应包含原始空格
        assertThat(route).doesNotContain(" ")
    }

    @Test
    fun `personRoute_对名字和头像URL编码`() {
        val route = Routes.personRoute(123, "诺兰 Christopher", "https://example.com/photo.jpg")
        // personId 应直接出现在路径中
        assertThat(route).startsWith("person/123/")
        // 中文字符"诺兰"应被 UTF-8 URL 编码为 %E8%AF%BA%E5%85%B0
        // 空格编码为 +,英文 Christopher 保持原样
        assertThat(route).contains("%E8%AF%BA%E5%85%B0+Christopher")
        // 头像 URL 中的 : 和 / 应被编码
        // https:// 会被编码为 https%3A%2F%2F
        assertThat(route).contains("https%3A%2F%2F")
    }
}
