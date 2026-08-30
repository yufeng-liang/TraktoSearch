package com.tracktosearch.data.repository

import com.google.common.truth.Truth.assertThat
import com.tracktosearch.data.local.CustomSearchSource
import com.tracktosearch.data.local.CustomSearchSourceStorage
import com.tracktosearch.data.local.PanHubConfigStorage
import com.tracktosearch.data.local.SearchSourceStorage
import com.tracktosearch.data.remote.custom.CustomSearchService
import com.tracktosearch.data.remote.dto.DiskType
import com.tracktosearch.data.remote.dto.ResourceItem
import com.tracktosearch.data.remote.panhub.PanHubApiService
import com.tracktosearch.data.remote.panhub.PanHubConfig
import com.tracktosearch.data.remote.pansou.PanSouApiService
import com.tracktosearch.data.remote.pansou.dto.PanSouData
import com.tracktosearch.data.remote.pansou.dto.PanSouMergedLink
import com.tracktosearch.data.remote.pansou.dto.PanSouResponse
import com.tracktosearch.data.remote.zreso.ZresoApiService
import com.tracktosearch.data.remote.zreso.dto.ZresoData
import com.tracktosearch.data.remote.zreso.dto.ZresoLink
import com.tracktosearch.data.remote.zreso.dto.ZresoResponse
import com.tracktosearch.data.remote.zreso.dto.ZresoResult
import com.tracktosearch.test.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.IOException

/**
 * ResourceRepository 单元测试。
 *
 * 覆盖：
 * - searchResources 缓存命中/未命中/过期/空结果不缓存
 * - effectiveSources 与设置页开关的交集逻辑
 * - filterItems 过滤无效项/源/diskType
 * - 排序规则（相关度/夸克优先/日期新/多季优先/PanSou源优先）
 * - API 失败降级（PanSou异常/code非0、Zreso异常、自定义源超时）
 * - 类型映射（PanSou/Zreso 各种网盘类型）
 * - searchPanHubGranular 无插件无频道时短路
 * - refreshResources 清除缓存
 * - getCachedAllResources 缓存读取
 * - mergeAndCacheResources 合并缓存
 * - getEnabledSources/getEnabledCustomSources/getSourceName
 * - searchResourcesFlow 缓存命中/keyword空/单源
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ResourceRepositoryTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val panSouApiService = mockk<PanSouApiService>(relaxed = true)
    private val panHubApiService = mockk<PanSouApiService>(relaxed = true)
    private val panHubGranularApiService = mockk<PanHubApiService>(relaxed = true)
    private val panHubConfigStorage = mockk<PanHubConfigStorage>(relaxed = true)
    private val zresoApiService = mockk<ZresoApiService>(relaxed = true)
    private val searchSourceStorage = mockk<SearchSourceStorage>(relaxed = true)
    private val customSearchSourceStorage = mockk<CustomSearchSourceStorage>(relaxed = true)
    private val customSearchService = mockk<CustomSearchService>(relaxed = true)
    private lateinit var repository: ResourceRepository

    @Before
    fun setup() = runTest {
        // 默认：pansou+zreso 启用，panhub 关闭
        every { searchSourceStorage.pansouEnabled } returns MutableStateFlow(true)
        every { searchSourceStorage.panhubEnabled } returns MutableStateFlow(false)
        every { searchSourceStorage.zresoEnabled } returns MutableStateFlow(true)
        // 默认无自定义源
        every { customSearchSourceStorage.sources } returns MutableStateFlow(emptyList())
        // 默认 PanHub config（空插件空频道，避免发起请求）
        every { panHubConfigStorage.config } returns MutableStateFlow(
            PanHubConfig(enabledPlugins = emptySet(), enabledChannels = emptySet())
        )

        repository = ResourceRepository(
            panSouApiService,
            panHubApiService,
            panHubGranularApiService,
            panHubConfigStorage,
            zresoApiService,
            searchSourceStorage,
            customSearchSourceStorage,
            customSearchService
        )
    }

    @After
    fun teardown() {
        clearCache()
    }

    // ==================== 反射辅助 ====================

    /** 清除 private cache 字段，避免跨测试污染 */
    private fun clearCache() {
        val cacheField = ResourceRepository::class.java.getDeclaredField("cache")
        cacheField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        (cacheField.get(repository) as MutableMap<String, Any>).clear()
    }

    // ==================== 测试数据 ====================

    private fun panSouItem(
        type: String = "quark",
        note: String = "测试资源",
        url: String = "https://pan.quark.cn/s/${type}url",
        datetime: String = "2024-01-01",
        source: String = ResourceRepository.SOURCE_PANSOU
    ) = PanSouMergedLink(
        url = url, note = note, datetime = datetime, password = "", source = "", images = emptyList()
    )

    private fun panSouResponse(links: Map<String, List<PanSouMergedLink>>, code: Int = 0) =
        PanSouResponse(code = code, message = "", data = PanSouData(total = links.values.sumOf { it.size }, merged_by_type = links))

    private fun resourceItem(
        name: String = "测试资源",
        diskType: DiskType = DiskType.QUARK,
        url: String = "https://example.com/$name",
        source: String = ResourceRepository.SOURCE_PANSOU,
        fileDate: String = "2024-01-01",
        fileCount: Int = 1,
        status: String = ""
    ) = ResourceItem(
        name = name, diskType = diskType, fileSize = "", fileDate = fileDate,
        fileCount = fileCount, status = status, url = url, source = source
    )

    private val testQuery = ResourceQuery(
        title = "情书", year = 1995, country = "日本", mediaType = MediaType.MOVIE,
        directors = listOf("岩井俊二")
    )

    /** 简化 CustomSearchSource 创建，必填的可空参数默认 null */
    private fun customSource(
        id: String = "custom1",
        name: String = "自定义源",
        baseUrl: String = "https://a.com",
        apiPath: String = "api",
        keywordParam: String = "kw",
        enabled: Boolean = true
    ) = CustomSearchSource(
        id = id, name = name, baseUrl = baseUrl, apiPath = apiPath,
        keywordParam = keywordParam, cloudTypesParam = null, cloudTypesValue = null,
        srcParam = null, srcValue = null, enabled = enabled
    )

    // ==================== searchResources 基础 ====================

    @Test
    fun searchResources_keyword为空_返回空列表() = runTest {
        val result = repository.searchResources("")
        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull()).isEmpty()
    }

    @Test
    fun searchResources_缓存未命中_调API并缓存() = runTest {
        val items = mapOf("quark" to listOf(panSouItem(url = "https://q1.com")))
        coEvery { panSouApiService.search(any(), any(), any(), any()) } returns panSouResponse(items)
        // zreso 返回空
        coEvery { zresoApiService.search(any(), any(), any()) } returns ZresoResponse(data = ZresoData())

        val result1 = repository.searchResources("情书")
        assertThat(result1.getOrNull()).hasSize(1)
        coVerify(atLeast = 1) { panSouApiService.search(any(), any(), any(), any()) }

        // 第二次应命中缓存，不调 API
        val result2 = repository.searchResources("情书")
        assertThat(result2.getOrNull()).hasSize(1)
        // API 调用次数不增加（仍为 1）
        coVerify(exactly = 1) { panSouApiService.search(any(), any(), any(), any()) }
    }


    @Test
    fun searchResources_空结果不缓存() = runTest {
        coEvery { panSouApiService.search(any(), any(), any(), any()) } returns panSouResponse(emptyMap())
        coEvery { zresoApiService.search(any(), any(), any()) } returns ZresoResponse(data = ZresoData())

        val result1 = repository.searchResources("情书")
        assertThat(result1.getOrNull()).isEmpty()

        // 第二次应再次调 API（因为空结果不缓存）
        val result2 = repository.searchResources("情书")
        assertThat(result2.getOrNull()).isEmpty()

        coVerify(atLeast = 2) { panSouApiService.search(any(), any(), any(), any()) }
    }

    @Test
    fun searchResources_设置页关闭的源不参与搜索() = runTest {
        // pansou 关闭，zreso 启用
        every { searchSourceStorage.pansouEnabled } returns MutableStateFlow(false)
        every { searchSourceStorage.zresoEnabled } returns MutableStateFlow(true)

        coEvery { zresoApiService.search(any(), any(), any()) } returns ZresoResponse(data = ZresoData())

        repository.searchResources("情书")

        // pansou 不应被调用
        coVerify(exactly = 0) { panSouApiService.search(any(), any(), any(), any()) }
        // zreso 应被调用
        coVerify(atLeast = 1) { zresoApiService.search(any(), any(), any()) }
    }

    @Test
    fun searchResources_all内置源关闭_返回空() = runTest {
        every { searchSourceStorage.pansouEnabled } returns MutableStateFlow(false)
        every { searchSourceStorage.zresoEnabled } returns MutableStateFlow(false)
        every { searchSourceStorage.panhubEnabled } returns MutableStateFlow(false)

        val result = repository.searchResources("情书")
        assertThat(result.getOrNull()).isEmpty()
    }

    @Test
    fun searchResources_enabledSources参数与设置页取交集() = runTest {
        // 设置页只启用 pansou，调用方传入 pansou+zreso
        every { searchSourceStorage.zresoEnabled } returns MutableStateFlow(false)

        coEvery { panSouApiService.search(any(), any(), any(), any()) } returns panSouResponse(
            mapOf("quark" to listOf(panSouItem()))
        )

        val result = repository.searchResources(
            "情书",
            enabledSources = setOf(ResourceRepository.SOURCE_PANSOU, ResourceRepository.SOURCE_ZRESO)
        )

        // zreso 被设置页关闭，不应调用
        coVerify(exactly = 0) { zresoApiService.search(any(), any(), any()) }
        // pansou 应调用
        coVerify(atLeast = 1) { panSouApiService.search(any(), any(), any(), any()) }
        assertThat(result.getOrNull()).isNotEmpty()
    }

    // ==================== searchResources 排序 ====================

    @Test
    fun searchResources_有query时按相关度排序() = runTest {
        // 高相关：标题完全匹配+年份+导演
        val highRel = panSouItem(note = "情书 1995 岩井俊二 1080p", url = "https://high.com")
        // 低相关：标题不匹配
        val lowRel = panSouItem(note = "电子情书 专辑", url = "https://low.com")
        coEvery { panSouApiService.search(any(), any(), any(), any()) } returns panSouResponse(
            mapOf("quark" to listOf(lowRel, highRel))
        )
        coEvery { zresoApiService.search(any(), any(), any()) } returns ZresoResponse(data = ZresoData())

        val result = repository.searchResources("情书", query = testQuery)
        val items = result.getOrNull()!!
        assertThat(items).hasSize(2)
        // 高相关应排前面
        assertThat(items[0].url).isEqualTo("https://high.com")
        assertThat(items[1].url).isEqualTo("https://low.com")
    }

    @Test
    fun searchResources_无query时夸克优先() = runTest {
        val baidu = panSouItem(note = "资源1", url = "https://baidu.com")
        val quark = panSouItem(note = "资源2", url = "https://quark.com")
        coEvery { panSouApiService.search(any(), any(), any(), any()) } returns panSouResponse(
            mapOf(
                "baidu" to listOf(baidu),
                "quark" to listOf(quark)
            )
        )
        coEvery { zresoApiService.search(any(), any(), any()) } returns ZresoResponse(data = ZresoData())

        val result = repository.searchResources("情书")
        val items = result.getOrNull()!!
        // 夸克应排前面
        assertThat(items[0].diskType).isEqualTo(DiskType.QUARK)
        assertThat(items[1].diskType).isEqualTo(DiskType.BAIDU)
    }

    @Test
    fun searchResources_无query时日期新优先() = runTest {
        val old = panSouItem(note = "资源1", url = "https://old.com", datetime = "2023-01-01")
        val new = panSouItem(note = "资源2", url = "https://new.com", datetime = "2024-06-01")
        coEvery { panSouApiService.search(any(), any(), any(), any()) } returns panSouResponse(
            mapOf("quark" to listOf(old, new))
        )
        coEvery { zresoApiService.search(any(), any(), any()) } returns ZresoResponse(data = ZresoData())

        val result = repository.searchResources("情书")
        val items = result.getOrNull()!!
        // 日期新的应排前面
        assertThat(items[0].url).isEqualTo("https://new.com")
    }

    @Test
    fun searchResources_isShow为true时多季优先() = runTest {
        val single = panSouItem(note = "情书 第一季", url = "https://single.com")
        val full = panSouItem(note = "情书 全季", url = "https://full.com")
        coEvery { panSouApiService.search(any(), any(), any(), any()) } returns panSouResponse(
            mapOf("quark" to listOf(single, full))
        )
        coEvery { zresoApiService.search(any(), any(), any()) } returns ZresoResponse(data = ZresoData())

        val result = repository.searchResources("情书", isShow = true)
        val items = result.getOrNull()!!
        // 全季（2分）应排第一季（1分）前面
        assertThat(items[0].url).isEqualTo("https://full.com")
    }

    @Test
    fun searchResources_isShow为false时多季不优先() = runTest {
        val full = panSouItem(note = "情书 全季", url = "https://full.com", datetime = "2023-01-01")
        val single = panSouItem(note = "情书", url = "https://single.com", datetime = "2024-06-01")
        coEvery { panSouApiService.search(any(), any(), any(), any()) } returns panSouResponse(
            mapOf("quark" to listOf(full, single))
        )
        coEvery { zresoApiService.search(any(), any(), any()) } returns ZresoResponse(data = ZresoData())

        val result = repository.searchResources("情书", isShow = false)
        val items = result.getOrNull()!!
        // isShow=false 时多季不加分，按日期排，single 日期新应排前面
        assertThat(items[0].url).isEqualTo("https://single.com")
    }

    @Test
    fun searchResources_PanSou源优先于其他() = runTest {
        // 同等条件下 pansou 源优先
        coEvery { panSouApiService.search(any(), any(), any(), any()) } returns panSouResponse(
            mapOf("quark" to listOf(panSouItem(note = "资源", url = "https://pansou.com", source = "pansou")))
        )
        coEvery { zresoApiService.search(any(), any(), any()) } returns ZresoResponse(
            data = ZresoData(results = listOf(
                ZresoResult(title = "资源", datetime = "2024-01-01", date = "", links = listOf(
                    ZresoLink(type = "quark", url = "https://zreso.com", status = "")
                ))
            ))
        )

        val result = repository.searchResources("情书")
        val items = result.getOrNull()!!
        // 两个结果，pansou 源应排前面
        assertThat(items).hasSize(2)
        assertThat(items[0].source).isEqualTo(ResourceRepository.SOURCE_PANSOU)
    }

    @Test
    fun searchResources_按url去重() = runTest {
        val sameUrl = "https://same.com"
        coEvery { panSouApiService.search(any(), any(), any(), any()) } returns panSouResponse(
            mapOf("quark" to listOf(panSouItem(note = "资源1", url = sameUrl)))
        )
        coEvery { zresoApiService.search(any(), any(), any()) } returns ZresoResponse(
            data = ZresoData(results = listOf(
                ZresoResult(title = "资源2", datetime = "2024-01-01", date = "", links = listOf(
                    ZresoLink(type = "quark", url = sameUrl, status = "")
                ))
            ))
        )

        val result = repository.searchResources("情书")
        val items = result.getOrNull()!!
        // 同 URL 去重后只剩 1 条
        assertThat(items).hasSize(1)
    }

    // ==================== searchResources API 失败降级 ====================

    @Test
    fun searchResources_各API失败场景按结果降级() = runTest {
        val zresoOnly = ZresoResponse(
            data = ZresoData(results = listOf(
                ZresoResult(title = "情书", datetime = "2024-01-01", date = "", links = listOf(
                    ZresoLink(type = "quark", url = "https://zreso.com", status = "")
                ))
            ))
        )
        val panSouOnly = panSouResponse(mapOf("quark" to listOf(panSouItem())))

        coEvery { panSouApiService.search(any(), any(), any(), any()) } throws IOException("网络错误")
        coEvery { zresoApiService.search(any(), any(), any()) } returns zresoOnly
        val panSouFailed = repository.searchResources("故障-panSou").getOrNull()!!
        assertThat(panSouFailed).hasSize(1)
        assertThat(panSouFailed[0].source).isEqualTo(ResourceRepository.SOURCE_ZRESO)

        coEvery { panSouApiService.search(any(), any(), any(), any()) } returns panSouResponse(emptyMap(), code = 500)
        coEvery { zresoApiService.search(any(), any(), any()) } returns zresoEmpty()
        assertThat(repository.searchResources("故障-code").getOrNull()).isEmpty()

        coEvery { panSouApiService.search(any(), any(), any(), any()) } returns panSouOnly
        coEvery { zresoApiService.search(any(), any(), any()) } throws IOException("网络错误")
        val zresoFailed = repository.searchResources("故障-zreso").getOrNull()!!
        assertThat(zresoFailed).hasSize(1)
        assertThat(zresoFailed[0].source).isEqualTo(ResourceRepository.SOURCE_PANSOU)

        coEvery { panSouApiService.search(any(), any(), any(), any()) } throws IOException("网络错误")
        coEvery { zresoApiService.search(any(), any(), any()) } throws IOException("网络错误")
        assertThat(repository.searchResources("故障-all").getOrNull()).isEmpty()
    }





    // ==================== searchResources 类型映射 ====================

    @Test
    fun searchResources_Zreso类型与空字段规则() = runTest {
        data class Case(
            val keyword: String,
            val datetime: String,
            val date: String,
            val links: List<ZresoLink>,
            val expectedUrl: String,
            val expectedCount: Int,
            val expectedDate: String?,
            val expectedType: DiskType?
        )
        val cases = listOf(
            Case("zreso-quark", "2024-01-01", "", listOf(
                ZresoLink(type = "quark", url = "https://z.com", status = "")
            ), "https://z.com", 1, "2024-01-01", DiskType.QUARK),
            Case("zreso-relative", "2024-01-01", "", listOf(
                ZresoLink(type = "quark", url = "/detail/123", status = "")
            ), "https://zreso.cn/detail/123", 1, "2024-01-01", DiskType.QUARK),
            Case("zreso-absolute", "2024-01-01", "", listOf(
                ZresoLink(type = "quark", url = "https://example.com/detail/123", status = "")
            ), "https://example.com/detail/123", 1, "2024-01-01", DiskType.QUARK),
            Case("zreso-count-date", "", "2023-05-01", listOf(
                ZresoLink(type = "quark", url = "https://count.com", status = ""),
                ZresoLink(type = "baidu", url = "https://count2.com", status = "")
            ), "https://count.com", 2, "2023-05-01", DiskType.QUARK),
            Case("zreso-unknown", "2024-01-01", "", listOf(
                ZresoLink(type = "unknown", url = "https://unknown.com", status = "")
            ), "", 0, null, null)
        )
        coEvery { panSouApiService.search(any(), any(), any(), any()) } returns panSouResponse(emptyMap())
        cases.forEach { case ->
            coEvery { zresoApiService.search(any(), any(), any()) } returns ZresoResponse(
                data = ZresoData(results = listOf(
                    ZresoResult(title = "情书", datetime = case.datetime, date = case.date, links = case.links)
                ))
            )
            val items = repository.searchResources(case.keyword).getOrNull()!!
            if (case.expectedType == null) {
                assertThat(items).isEmpty()
            } else {
                assertThat(items).hasSize(case.links.size)
                assertThat(items[0].diskType).isEqualTo(case.expectedType)
                assertThat(items[0].url).isEqualTo(case.expectedUrl)
                assertThat(items[0].fileCount).isEqualTo(case.expectedCount)
                assertThat(items[0].fileDate).isEqualTo(case.expectedDate)
            }
        }
    }

    @Test
    fun searchResources_PanSou类型映射保留全部网盘类型() = runTest {
        val cases = mapOf(
            "quark" to DiskType.QUARK,
            "baidu" to DiskType.BAIDU,
            "aliyun" to DiskType.ALI,
            "xunlei" to DiskType.XUNLEI,
            "uc" to DiskType.UC,
            "115" to DiskType.ONEONEFIVE,
            "magnet" to DiskType.MAGNET
        )
        coEvery { zresoApiService.search(any(), any(), any()) } returns zresoEmpty()
        cases.forEach { (type, expected) ->
            coEvery { panSouApiService.search(any(), any(), any(), any()) } returns panSouResponse(
                mapOf(type to listOf(panSouItem(type = type)))
            )
            val items = repository.searchResources("类型-$type").getOrNull()!!
            assertThat(items).hasSize(1)
            assertThat(items[0].diskType).isEqualTo(expected)
        }

        coEvery { panSouApiService.search(any(), any(), any(), any()) } returns panSouResponse(
            mapOf("unknown_type" to listOf(panSouItem()))
        )
        assertThat(repository.searchResources("类型-unknown").getOrNull()).isEmpty()
    }
















    // ==================== searchResources diskType 过滤 ====================

    @Test
    fun searchResources_diskType参数与结果过滤() = runTest {
        coEvery { panSouApiService.search(any(), any(), any(), any()) } returns panSouResponse(emptyMap())
        coEvery { zresoApiService.search(any(), any(), any()) } returns zresoEmpty()

        repository.searchResources("disk-partial", enabledDiskTypes = setOf(DiskType.QUARK, DiskType.BAIDU))
        coVerify { panSouApiService.search(any(), any(), eq("quark,baidu"), any()) }

        repository.searchResources("disk-all")
        coVerify {
            panSouApiService.search(any(), any(), eq("quark,baidu,aliyun,xunlei,uc,115,magnet"), any())
        }

        coEvery { panSouApiService.search(any(), any(), any(), any()) } returns panSouResponse(
            mapOf(
                "quark" to listOf(panSouItem(note = "夸克", url = "https://q.com")),
                "baidu" to listOf(panSouItem(note = "百度", url = "https://b.com"))
            )
        )
        val items = repository.searchResources("disk-filter", enabledDiskTypes = setOf(DiskType.QUARK)).getOrNull()!!
        assertThat(items).hasSize(1)
        assertThat(items[0].diskType).isEqualTo(DiskType.QUARK)
    }




    // ==================== filterItems ====================

    @Test
    fun filterItems_批量过滤无效状态与空文件() {
        data class InvalidCase(val status: String, val fileCount: Int)
        val cases = listOf(
            InvalidCase("fail", 1),
            InvalidCase("expired", 1),
            InvalidCase("invalid", 1),
            InvalidCase("", 0),
            InvalidCase("FAIL", 1)
        )
        cases.forEach { case ->
            val result = repository.filterItems(
                listOf(
                    resourceItem(name = "有效"),
                    resourceItem(name = "失效", status = case.status, fileCount = case.fileCount, url = "https://invalid-${case.status}.com")
                ),
                enabledSources = ResourceRepository.ALL_SOURCES,
                enabledDiskTypes = ResourceRepository.ALL_DISK_TYPES
            )
            assertThat(result).hasSize(1)
            assertThat(result[0].name).isEqualTo("有效")
        }
    }





    @Test
    fun filterItems_过滤源() {
        val items = listOf(
            resourceItem(name = "pansou", source = ResourceRepository.SOURCE_PANSOU),
            resourceItem(name = "zreso", source = ResourceRepository.SOURCE_ZRESO, url = "https://z.com")
        )
        val result = repository.filterItems(
            items,
            enabledSources = setOf(ResourceRepository.SOURCE_PANSOU),
            enabledDiskTypes = ResourceRepository.ALL_DISK_TYPES
        )
        assertThat(result).hasSize(1)
        assertThat(result[0].source).isEqualTo(ResourceRepository.SOURCE_PANSOU)
    }

    @Test
    fun filterItems_过滤diskType() {
        val items = listOf(
            resourceItem(name = "夸克", diskType = DiskType.QUARK),
            resourceItem(name = "百度", diskType = DiskType.BAIDU, url = "https://b.com")
        )
        val result = repository.filterItems(
            items,
            enabledSources = ResourceRepository.ALL_SOURCES,
            enabledDiskTypes = setOf(DiskType.QUARK)
        )
        assertThat(result).hasSize(1)
        assertThat(result[0].diskType).isEqualTo(DiskType.QUARK)
    }


    // ==================== refreshResources ====================

    @Test
    fun refreshResources_清除缓存后重新搜索() = runTest {
        val items = mapOf("quark" to listOf(panSouItem()))
        coEvery { panSouApiService.search(any(), any(), any(), any()) } returns panSouResponse(items)
        coEvery { zresoApiService.search(any(), any(), any()) } returns zresoEmpty()

        // 第一次搜索填充缓存
        repository.searchResources("情书")
        // 强制刷新
        repository.refreshResources("情书")

        // API 应被调用至少 2 次（第一次 + 刷新后）
        coVerify(atLeast = 2) { panSouApiService.search(any(), any(), any(), any()) }
    }


    // ==================== getCachedAllResources ====================

    @Test
    fun getCachedAllResources_空值未命中与命中() = runTest {
        assertThat(repository.getCachedAllResources("")).isEmpty()
        assertThat(repository.getCachedAllResources("不存在的key")).isEmpty()

        coEvery { panSouApiService.search(any(), any(), any(), any()) } returns panSouResponse(
            mapOf("quark" to listOf(panSouItem(note = "缓存测试", url = "https://cached.com")))
        )
        coEvery { zresoApiService.search(any(), any(), any()) } returns zresoEmpty()
        repository.searchResources("缓存key")

        val cached = repository.getCachedAllResources("缓存key")
        assertThat(cached).hasSize(1)
        assertThat(cached[0].url).isEqualTo("https://cached.com")
    }




    // ==================== mergeAndCacheResources ====================

    @Test
    fun mergeAndCacheResources_合并并缓存() = runTest {
        val items = listOf(
            resourceItem(name = "资源1", url = "https://a.com"),
            resourceItem(name = "资源2", url = "https://b.com")
        )
        val ranked = repository.mergeAndCacheResources("情书", items)
        assertThat(ranked.items).hasSize(2)
        // 验证已写入缓存
        val cached = repository.getCachedAllResources("情书")
        assertThat(cached).hasSize(2)
    }

    @Test
    fun mergeAndCacheResources_空列表不缓存() = runTest {
        val ranked = repository.mergeAndCacheResources("情书", emptyList())
        assertThat(ranked.items).isEmpty()
        // 空列表不缓存
        assertThat(repository.getCachedAllResources("情书")).isEmpty()
    }

    @Test
    fun mergeAndCacheResources_有query时返回scoreMap() {
        val items = listOf(
            resourceItem(name = "情书 1995", url = "https://a.com"),
            resourceItem(name = "其他", url = "https://b.com")
        )
        val ranked = repository.mergeAndCacheResources("情书", items, query = testQuery)
        // scoreMap 非空
        assertThat(ranked.scoreMap).isNotEmpty()
        assertThat(ranked.highRelevanceMap).containsKey("https://a.com")
        assertThat(ranked.highRelevanceMap["https://a.com"]).isTrue()
        assertThat(ranked.highRelevanceMap["https://b.com"]).isFalse()
        // 高相关应排前面
        assertThat(ranked.items[0].url).isEqualTo("https://a.com")
    }


    // ==================== getEnabledSources ====================

    @Test
    fun getEnabledSources_返回内置源加自定义源() = runTest {
        every { customSearchSourceStorage.sources } returns MutableStateFlow(
            listOf(
                customSource(id = "custom1", name = "自定义1", baseUrl = "https://a.com", enabled = true),
                customSource(id = "custom2", name = "自定义2", baseUrl = "https://b.com", enabled = false)
            )
        )

        val sources = repository.getEnabledSources()
        // 内置 pansou+zreso + custom1（custom2 禁用）
        assertThat(sources).contains(ResourceRepository.SOURCE_PANSOU)
        assertThat(sources).contains(ResourceRepository.SOURCE_ZRESO)
        assertThat(sources).contains("custom1")
        assertThat(sources).doesNotContain("custom2")
    }

    @Test
    fun getEnabledSources_设置页关闭的源不返回() = runTest {
        every { searchSourceStorage.zresoEnabled } returns MutableStateFlow(false)

        val sources = repository.getEnabledSources()
        assertThat(sources).contains(ResourceRepository.SOURCE_PANSOU)
        assertThat(sources).doesNotContain(ResourceRepository.SOURCE_ZRESO)
    }

    // ==================== getEnabledCustomSources ====================

    @Test
    fun getEnabledCustomSources_只返回enabled的自定义源() = runTest {
        every { customSearchSourceStorage.sources } returns MutableStateFlow(
            listOf(
                customSource(id = "custom1", name = "启用", enabled = true),
                customSource(id = "custom2", name = "禁用", enabled = false)
            )
        )

        val sources = repository.getEnabledCustomSources()
        assertThat(sources).hasSize(1)
        assertThat(sources[0].id).isEqualTo("custom1")
    }

    // ==================== getSourceName ====================

    @Test
    fun getSourceName_命中_返回名称() = runTest {
        every { customSearchSourceStorage.sources } returns MutableStateFlow(
            listOf(customSource(id = "custom1", name = "我的搜索源"))
        )

        val name = repository.getSourceName("custom1")
        assertThat(name).isEqualTo("我的搜索源")
    }

    @Test
    fun getSourceName_未命中_返回null() = runTest {
        every { customSearchSourceStorage.sources } returns MutableStateFlow(emptyList())

        val name = repository.getSourceName("不存在")
        assertThat(name).isNull()
    }

    // ==================== searchResourcesFlow ====================
    // searchResourcesFlow 使用 flowOn(Dispatchers.IO)，在 runTest 的虚拟时间下会挂起，
    // 改用 runBlocking 确保真实线程执行。


    @Test
    fun searchResourcesFlow_缓存命中_只发射一次() = runBlocking {
        val items = mapOf("quark" to listOf(panSouItem(note = "流测试", url = "https://flow.com")))
        coEvery { panSouApiService.search(any(), any(), any(), any()) } returns panSouResponse(items)
        coEvery { zresoApiService.search(any(), any(), any()) } returns zresoEmpty()

        // 先搜索填充缓存
        runBlocking { repository.searchResources("情书") }

        // flow 应命中缓存只发射 1 次
        val results = repository.searchResourcesFlow("情书").toList()
        assertThat(results).hasSize(1)
        assertThat(results[0]).isNotEmpty()
    }

    @Test
    fun searchResourcesFlow_单源_发射一次() = runBlocking {
        // 只启用 pansou
        every { searchSourceStorage.zresoEnabled } returns MutableStateFlow(false)
        coEvery { panSouApiService.search(any(), any(), any(), any()) } returns panSouResponse(
            mapOf("quark" to listOf(panSouItem(note = "单源", url = "https://single.com")))
        )

        val results = repository.searchResourcesFlow("情书").toList()
        assertThat(results).hasSize(1)
        assertThat(results[0]).isNotEmpty()
    }

    @Test
    fun searchResourcesFlow_无启用源_发射空列表() = runBlocking {
        every { searchSourceStorage.pansouEnabled } returns MutableStateFlow(false)
        every { searchSourceStorage.zresoEnabled } returns MutableStateFlow(false)

        val results = repository.searchResourcesFlow("情书").toList()
        assertThat(results).hasSize(1)
        assertThat(results[0]).isEmpty()
    }

    // ==================== searchPanHubGranular ====================

    @Test
    fun searchPanHubGranular_无启用插件和频道_返回空() = runTest {
        every { searchSourceStorage.panhubEnabled } returns MutableStateFlow(true)
        every { panHubConfigStorage.config } returns MutableStateFlow(
            PanHubConfig(enabledPlugins = emptySet(), enabledChannels = emptySet())
        )

        val result = repository.searchResources("情书")
        // panhub 无插件无频道，不会调用 granular API
        coVerify(exactly = 0) { panHubGranularApiService.search(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun searchPanHubGranular_有插件时调用API() = runTest {
        every { searchSourceStorage.panhubEnabled } returns MutableStateFlow(true)
        every { panHubConfigStorage.config } returns MutableStateFlow(
            PanHubConfig(enabledPlugins = setOf("plugin1"), enabledChannels = emptySet())
        )
        // pansou 和 zreso 关闭，只测 panhub
        every { searchSourceStorage.pansouEnabled } returns MutableStateFlow(false)
        every { searchSourceStorage.zresoEnabled } returns MutableStateFlow(false)

        coEvery {
            panHubGranularApiService.search(any(), any(), any(), any(), any(), any(), any())
        } returns panSouResponse(mapOf("quark" to listOf(panSouItem(url = "https://panhub.com"))))

        val result = repository.searchResources("情书")
        assertThat(result.getOrNull()).hasSize(1)
        coVerify(atLeast = 1) { panHubGranularApiService.search(any(), any(), any(), any(), any(), any(), any()) }
    }

    // ==================== 自定义源 ====================

    @Test
    fun searchResources_自定义源结果被filterItems过滤() = runTest {
        // searchResources 的 filterItems 用 effectiveSources 过滤，
        // effectiveSources = enabledSources ∩ storageEnabledSources，不含自定义源 ID，
        // 所以自定义源结果会被过滤。这是当前行为（ViewModel 用 searchResourcesFlow 而非 searchResources）。
        every { customSearchSourceStorage.sources } returns MutableStateFlow(
            listOf(customSource(id = "custom1", name = "自定义", enabled = true))
        )
        every { searchSourceStorage.pansouEnabled } returns MutableStateFlow(false)
        every { searchSourceStorage.zresoEnabled } returns MutableStateFlow(false)

        coEvery { customSearchService.search(any(), any()) } returns listOf(
            resourceItem(name = "自定义资源", url = "https://custom.com", source = "custom1")
        )

        val result = repository.searchResources("情书")
        // 自定义源结果被 filterItems 过滤（effectiveSources 不含 custom1）
        assertThat(result.getOrNull()).isEmpty()
    }

    @Test
    fun searchResources_自定义源异常_返回空() = runTest {
        every { customSearchSourceStorage.sources } returns MutableStateFlow(
            listOf(customSource(id = "custom1", name = "自定义", enabled = true))
        )
        every { searchSourceStorage.pansouEnabled } returns MutableStateFlow(false)
        every { searchSourceStorage.zresoEnabled } returns MutableStateFlow(false)

        coEvery { customSearchService.search(any(), any()) } throws IOException("连接失败")

        val result = repository.searchResources("情书")
        assertThat(result.getOrNull()).isEmpty()
    }

    // ==================== ResourceItem.isInvalid 辅助测试 ====================







    // ==================== multiSeasonScore（通过排序间接验证） ====================




    // ==================== 常量 ====================



    private fun zresoEmpty() = ZresoResponse(data = ZresoData())
}
