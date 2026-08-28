package com.tracktosearch.ui.screen.searchsource

import com.google.common.truth.Truth.assertThat
import com.tracktosearch.data.local.CustomSearchSource
import com.tracktosearch.data.local.CustomSearchSourceStorage
import com.tracktosearch.data.remote.custom.CustomSearchService
import com.tracktosearch.test.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Rule
import org.junit.Test

/**
 * 自动探测失败原因的单元测试。
 *
 * 探测会遍历多个 apiPath/keywordParam 变体，只有全部失败才落到 Failed。
 * 这里验证「失败原因取最靠前的进展」这条规则：只要有一个变体连上过服务器，
 * 就不该再告诉用户地址打不开；只要有一个变体拿到了 JSON，就该说结构认不出。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchSourceEditorViewModelProbeTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val storage = mockk<CustomSearchSourceStorage>(relaxed = true)
    private val service = mockk<CustomSearchService>()

    private fun viewModel(): SearchSourceEditorViewModel {
        every { storage.sources } returns MutableStateFlow(emptyList())
        return SearchSourceEditorViewModel(storage, service).apply {
            setBaseUrl("https://example.com/")
        }
    }

    private fun json(text: String): JsonElement = Json.parseToJsonElement(text)

    @Test
    fun `所有变体都连不上时原因为 UNREACHABLE`() = runTest {
        coEvery { service.probeRaw(any(), any()) } returns
            CustomSearchService.ProbeFetch.Unreachable

        val viewModel = viewModel()
        viewModel.startProbe()
        advanceUntilIdle()

        val state = viewModel.probeState.value
        assertThat(state).isInstanceOf(SearchSourceEditorViewModel.ProbeUiState.Failed::class.java)
        assertThat((state as SearchSourceEditorViewModel.ProbeUiState.Failed).reason)
            .isEqualTo(SearchSourceEditorViewModel.ProbeFailureReason.UNREACHABLE)
    }

    @Test
    fun `连上但返回非 JSON 时原因为 NOT_JSON`() = runTest {
        coEvery { service.probeRaw(any(), any()) } returns
            CustomSearchService.ProbeFetch.NotJson

        val viewModel = viewModel()
        viewModel.startProbe()
        advanceUntilIdle()

        val state = viewModel.probeState.value as SearchSourceEditorViewModel.ProbeUiState.Failed
        assertThat(state.reason).isEqualTo(SearchSourceEditorViewModel.ProbeFailureReason.NOT_JSON)
    }

    @Test
    fun `拿到 JSON 但结构认不出时原因为 UNRECOGNIZED`() = runTest {
        // 合法 JSON，但既没有结果数组也没有名称/链接字段
        coEvery { service.probeRaw(any(), any()) } returns
            CustomSearchService.ProbeFetch.Json(json("""{"ok":true,"count":0}"""))

        val viewModel = viewModel()
        viewModel.startProbe()
        advanceUntilIdle()

        val state = viewModel.probeState.value as SearchSourceEditorViewModel.ProbeUiState.Failed
        assertThat(state.reason)
            .isEqualTo(SearchSourceEditorViewModel.ProbeFailureReason.UNRECOGNIZED)
    }

    @Test
    fun `JSON 进展优先于连接进展`() = runTest {
        // 第一个变体拿到 JSON（认不出），后续变体全部连不上：
        // 结论应停在 UNRECOGNIZED，而不是被后面的 Unreachable 覆盖
        var first = true
        coEvery { service.probeRaw(any(), any()) } answers {
            if (first) {
                first = false
                CustomSearchService.ProbeFetch.Json(json("""{"ok":true}"""))
            } else {
                CustomSearchService.ProbeFetch.Unreachable
            }
        }

        val viewModel = viewModel()
        viewModel.startProbe()
        advanceUntilIdle()

        val state = viewModel.probeState.value as SearchSourceEditorViewModel.ProbeUiState.Failed
        assertThat(state.reason)
            .isEqualTo(SearchSourceEditorViewModel.ProbeFailureReason.UNRECOGNIZED)
    }

    @Test
    fun `识别成功时写回探测出的路径`() = runTest {
        val body = """
            {"data":{"results":[{"title":"流浪地球","url":"https://pan.quark.cn/s/abc","type":"quark"}]}}
        """.trimIndent()
        coEvery { service.probeRaw(any(), any()) } returns
            CustomSearchService.ProbeFetch.Json(json(body))

        val viewModel = viewModel()
        viewModel.startProbe()
        advanceUntilIdle()

        val state = viewModel.probeState.value
        assertThat(state).isInstanceOf(SearchSourceEditorViewModel.ProbeUiState.Found::class.java)
        assertThat(viewModel.parseMode.value).isEqualTo("custom")
        assertThat(viewModel.listPath.value).isNotEmpty()
        assertThat(viewModel.urlPath.value).isNotEmpty()
    }

    @Test
    fun `探测进行中重复触发不会重新开始`() = runTest {
        coEvery { service.probeRaw(any(), any()) } returns
            CustomSearchService.ProbeFetch.Unreachable

        val viewModel = viewModel()
        viewModel.startProbe()
        viewModel.startProbe()
        advanceUntilIdle()

        assertThat(viewModel.probeState.value)
            .isInstanceOf(SearchSourceEditorViewModel.ProbeUiState.Failed::class.java)
    }

    @Test
    fun `套用参数完整的模板直接跳到确认步骤`() = runTest {
        val viewModel = viewModel()
        viewModel.applyTemplate("pansou_public")
        advanceUntilIdle()

        // pansou_public 模板参数齐全，不需要探测
        assertThat(viewModel.step.value).isEqualTo(3)
        assertThat(viewModel.appliedTemplateName.value).isNotNull()
    }

    @Test
    fun `套用空白模板留在第一步`() = runTest {
        val viewModel = viewModel()
        viewModel.applyTemplate("blank")
        advanceUntilIdle()

        assertThat(viewModel.step.value).isEqualTo(1)
        assertThat(viewModel.appliedTemplateName.value).isNull()
    }

    @Test
    fun `未知模板 ID 不改变表单`() = runTest {
        val viewModel = viewModel()
        viewModel.setName("keep-me")
        viewModel.applyTemplate("no-such-template")
        advanceUntilIdle()

        assertThat(viewModel.name.value).isEqualTo("keep-me")
    }

    @Test
    fun `切换手动模式会切到 custom 解析并跳到确认步骤`() = runTest {
        val viewModel = viewModel()
        viewModel.switchToManual()

        assertThat(viewModel.parseMode.value).isEqualTo("custom")
        assertThat(viewModel.step.value).isEqualTo(3)
    }

    @Test
    fun `冲突检测按名称或 baseUrl 命中`() = runTest {
        val existing = CustomSearchSource(
            id = "existing", name = "已有源", baseUrl = "https://example.com",
            apiPath = "api/search", keywordParam = "kw", enabled = true,
            parseMode = "pansou_template"
        )
        every { storage.sources } returns MutableStateFlow(listOf(existing))
        val viewModel = SearchSourceEditorViewModel(storage, service).apply {
            setBaseUrl("https://example.com/")
            setName("另一个名字")
        }

        assertThat(viewModel.findConflict()?.id).isEqualTo("existing")
    }
}
