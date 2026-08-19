package com.tracktosearch.ui.screen.searchsource

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tracktosearch.data.local.CustomSearchSource
import com.tracktosearch.data.local.CustomSearchSourceStorage
import com.tracktosearch.data.local.SearchSourceTemplates
import com.tracktosearch.data.local.ShareCodec
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

    private val _appliedTemplateName = MutableStateFlow<String?>(null)
    val appliedTemplateName: StateFlow<String?> = _appliedTemplateName.asStateFlow()

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
                    _appliedTemplateName.value = template.name
                    _step.value = 3
                } else {
                    // blank 或未知：空白向导
                    applySource(SearchSourceTemplates.blank.defaults)
                    _appliedTemplateName.value = null
                    _step.value = 1
                }
            }
            EditorMode.BLANK -> {
                applySource(SearchSourceTemplates.blank.defaults)
                _appliedTemplateName.value = null
                _step.value = 1
            }
            EditorMode.EDIT -> {
                val source = customSearchSourceStorage.sources.value.find { it.id == sourceId }
                if (source != null) {
                    editingId = source.id
                    applySource(source)
                    _appliedTemplateName.value = null
                    _step.value = 3
                }
            }
            EditorMode.IMPORT -> {
                _appliedTemplateName.value = null
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
                _parseMode.value = found.result.parseMode
                _listPath.value = found.result.listPath
                _namePath.value = found.result.namePath
                _urlPath.value = found.result.urlPath
                _diskTypePath.value = found.result.diskTypePath
                _datePath.value = found.result.datePath
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

    /** 冲突检测：同名或同 baseUrl 已存在（排除自身） */
    fun findConflict(): CustomSearchSource? =
        customSearchSourceStorage.sources.value.find {
            it.id != editingId && (it.name == _name.value || it.baseUrl.trimEnd('/') == _baseUrl.value.trimEnd('/'))
        }

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

    fun currentEditingId(): String? = editingId

    /** 编辑器内测试当前表单（列表页逻辑的临时版本） */
    fun testCurrent(onResult: (String?) -> Unit) {
        val source = currentSource() ?: return
        viewModelScope.launch {
            val result = customSearchService.testSource(source)
            onResult(
                when (result) {
                    is CustomSearchService.TestResult.Success ->
                        if (result.count > 0) "成功 ${result.count} 条：${result.sampleName ?: ""}"
                        else "成功 0 条"
                    is CustomSearchService.TestResult.Error -> result.message
                }
            )
        }
    }

    // ---- 表单 setter ----
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
}