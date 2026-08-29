package com.tracktosearch.ui.screen.privacy

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tracktosearch.data.local.AiTasteStorage
import com.tracktosearch.data.local.CrashLogStorage
import com.tracktosearch.data.util.CrashLogUploader
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 「数据与隐私」页 ViewModel。
 *
 * 独立于 SettingsViewModel 自建（设置页原「AI 与隐私」分组与崩溃日志开关行
 * 迁入本页），逻辑与原 SettingsViewModel 中的实现保持一致：
 * - AI taste 开关：存储层是冷 Flow，stateIn 起来给开关绑定；首值落地前用默认 true 占位
 * - 崩溃日志开关：存储层已是 StateFlow（默认 false），直接透传
 */
@HiltViewModel
class PrivacyViewModel @Inject constructor(
    private val aiTasteStorage: AiTasteStorage,
    private val crashLogStorage: CrashLogStorage,
    private val crashLogUploader: CrashLogUploader
) : ViewModel() {

    /** 「锐评我的看单」数据上传开关（默认开启） */
    val aiTasteEnabled: StateFlow<Boolean> = aiTasteStorage.tasteUploadEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    /** 崩溃日志上报开关（默认关闭，需用户授权） */
    val crashLogEnabled: StateFlow<Boolean> = crashLogStorage.enabled

    /** 「锐评我的看单」数据上传开关（关闭后功能项保留，点击时引导回本页开启） */
    fun setAiTasteEnabled(enabled: Boolean) {
        viewModelScope.launch { aiTasteStorage.setTasteUploadEnabled(enabled) }
    }

    fun setCrashLogEnabled(enabled: Boolean) {
        viewModelScope.launch {
            crashLogStorage.setEnabled(enabled)
            // 开启且有待传日志：立即触发上传（成功/失败反馈由主界面 CrashReportDialogHost 统一处理，避免双提示）
            if (enabled && crashLogUploader.hasPendingLogs()) {
                crashLogUploader.uploadPendingLogs()
            }
        }
    }
}
