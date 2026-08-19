package com.tracktosearch.ui.screen.searchsource

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tracktosearch.R
import com.tracktosearch.data.local.CustomSearchSource
import com.tracktosearch.data.local.CustomSearchSourceStorage
import com.tracktosearch.data.local.PanHubConfigStorage
import com.tracktosearch.data.local.SearchSourceStorage
import com.tracktosearch.data.remote.custom.CustomSearchService
import com.tracktosearch.data.remote.panhub.PanHubConfig
import com.tracktosearch.ui.util.toUserMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 搜索源管理列表页 ViewModel：内置源启停 + 自定义源管理 + 测试 + PanHub 配置。
 * 数据源直接委托各 Storage 的 StateFlow（已预加载首值，无默认值跳变）。
 */
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

    /** 导入冲突检测：同名或同地址的源已存在 */
    fun findImportConflict(source: CustomSearchSource): CustomSearchSource? =
        customSearchSourceStorage.sources.value.find {
            it.name == source.name || it.baseUrl.trimEnd('/') == source.baseUrl.trimEnd('/')
        }

    /** 导入分享配置：覆盖冲突源或新增（分享文本中的 id 属于原分享者，导入时改用本地 id） */
    fun importSource(source: CustomSearchSource, overwrite: Boolean = false) {
        viewModelScope.launch {
            val conflict = findImportConflict(source)
            if (conflict != null && !overwrite) return@launch
            val finalSource = source.copy(id = conflict?.id ?: java.util.UUID.randomUUID().toString())
            if (conflict != null) customSearchSourceStorage.updateSource(finalSource)
            else customSearchSourceStorage.addSource(finalSource)
        }
    }

    fun setCustomSourceEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch { customSearchSourceStorage.setEnabled(id, enabled) }
    }

    /** 单个自定义源的测试结果状态 */
    @Immutable
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
                        TestResultState(
                            source.id, isTesting = false, success = true,
                            message = context.getString(R.string.snackbar_test_success, result.count)
                        )
                    } else {
                        TestResultState(
                            source.id, isTesting = false, success = true,
                            message = context.getString(R.string.snackbar_test_empty)
                        )
                    }
                }
                is CustomSearchService.TestResult.Error -> {
                    TestResultState(
                        source.id, isTesting = false, success = false,
                        message = Exception(result.message).toUserMessage(context, R.string.error_unknown)
                    )
                }
            }
            _testResults.value = _testResults.value + (source.id to state)
        }
    }
}
