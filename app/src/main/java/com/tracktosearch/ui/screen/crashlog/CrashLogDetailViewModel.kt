package com.tracktosearch.ui.screen.crashlog

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tracktosearch.data.local.CrashLogRecord
import com.tracktosearch.data.local.CrashLogRecordStore
import com.tracktosearch.data.util.CrashLogUploader
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 崩溃日志记录详情 ViewModel：按 id 从记录列表映射，上传后记录自动刷新 */
@HiltViewModel
class CrashLogDetailViewModel @Inject constructor(
    private val recordStore: CrashLogRecordStore,
    private val crashLogUploader: CrashLogUploader,
) : ViewModel() {

    fun record(id: String): StateFlow<CrashLogRecord?> =
        recordStore.records
            .map { list -> list.find { it.id == id } }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * 立即上传待传日志（成功后记录自动刷新为 SUCCESS）。
     *
     * 注：正常流程下 PENDING/FAILED 记录对应的日志文件必然存在（上传失败保留文件、未授权不建记录），
     * 故无文件时上传静默无操作属罕见边缘场景，不做额外提示。
     */
    fun uploadNow() {
        viewModelScope.launch { crashLogUploader.uploadPendingLogs() }
    }
}
