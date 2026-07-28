package com.tracktosearch.ui.screen.feedback

import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tracktosearch.BuildConfig
import com.tracktosearch.data.auth.AuthManager
import com.tracktosearch.data.local.DoubanAuthStorage
import com.tracktosearch.data.local.UserProfileStorage
import com.tracktosearch.data.remote.feedback.FeedbackDetailResponse
import com.tracktosearch.data.remote.feedback.FeedbackListItem
import com.tracktosearch.data.repository.FeedbackRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 反馈与建议 ViewModel
 * - 列表/详情/提交状态管理
 * - 自动收集设备信息和身份信息（朋友昵称/Trakt/豆瓣用户名）
 */
@HiltViewModel
class FeedbackViewModel @Inject constructor(
    private val feedbackRepository: FeedbackRepository,
    private val authManager: AuthManager,
    private val userProfileStorage: UserProfileStorage,
    private val doubanAuthStorage: DoubanAuthStorage
) : ViewModel() {

    sealed interface ListState {
        data object Loading : ListState
        data class Success(val items: List<FeedbackListItem>, val hasMore: Boolean, val offset: Int) : ListState
        data class Error(val message: String) : ListState
    }

    sealed interface DetailState {
        data object Loading : DetailState
        data class Success(val data: FeedbackDetailResponse) : DetailState
        data class Error(val message: String) : DetailState
    }

    sealed interface SubmitState {
        data object Idle : SubmitState
        data object Uploading : SubmitState
        data object Submitting : SubmitState
        data class Success(val id: String) : SubmitState
        data class Error(val message: String) : SubmitState
    }

    private val _listState = MutableStateFlow<ListState>(ListState.Loading)
    val listState: StateFlow<ListState> = _listState.asStateFlow()

    private val _detailState = MutableStateFlow<DetailState>(DetailState.Loading)
    val detailState: StateFlow<DetailState> = _detailState.asStateFlow()

    private val _submitState = MutableStateFlow<SubmitState>(SubmitState.Idle)
    val submitState: StateFlow<SubmitState> = _submitState.asStateFlow()

    private var loadListJob: Job? = null

    /** 加载我的反馈列表 */
    fun loadList(refresh: Boolean = false) {
        val currentOffset = (_listState.value as? ListState.Success)?.offset ?: 0
        val offset = if (refresh) 0 else currentOffset
        if (refresh) _listState.value = ListState.Loading

        loadListJob?.cancel()
        loadListJob = viewModelScope.launch {
            try {
                val result = feedbackRepository.getMine(limit = 20, offset = offset)
                result.onSuccess { response ->
                    val prev = (_listState.value as? ListState.Success)?.items ?: emptyList()
                    val items = if (refresh) response.feedbacks else prev + response.feedbacks
                    _listState.value = ListState.Success(items, response.hasMore, offset + response.feedbacks.size)
                }.onFailure { e ->
                    _listState.value = ListState.Error(e.message ?: "LOAD_FAILED")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _listState.value = ListState.Error(e.message ?: "LOAD_FAILED")
            }
        }
    }

    /** 加载详情 */
    fun loadDetail(id: String) {
        _detailState.value = DetailState.Loading
        viewModelScope.launch {
            try {
                val result = feedbackRepository.getDetail(id)
                result.onSuccess { _detailState.value = DetailState.Success(it) }
                    .onFailure { e -> _detailState.value = DetailState.Error(e.message ?: "LOAD_FAILED") }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _detailState.value = DetailState.Error(e.message ?: "LOAD_FAILED")
            }
        }
    }

    /** 提交反馈（含截图上传） */
    fun submit(
        type: String,
        content: String,
        contact: String?,
        screenshotBytes: List<ByteArray>,
        screenshotMimeTypes: List<String>
    ) {
        _submitState.value = SubmitState.Uploading
        viewModelScope.launch {
            try {
                // 上传截图
                val screenshotKeys = mutableListOf<String>()
                for ((index, bytes) in screenshotBytes.withIndex()) {
                    val mimeType = screenshotMimeTypes.getOrNull(index) ?: "image/jpeg"
                    val keyResult = feedbackRepository.uploadScreenshot(bytes, mimeType)
                    keyResult.onSuccess { screenshotKeys.add(it) }
                        .onFailure {
                            _submitState.value = SubmitState.Error("SCREENSHOT_UPLOAD_FAILED: ${it.message}")
                            return@launch
                        }
                }

                // 提交
                _submitState.value = SubmitState.Submitting
                val friendNickname = authManager.nickname.value ?: "UNKNOWN"
                val traktUsername = userProfileStorage.getProfile()?.username?.takeIf { it.isNotBlank() }
                // 豆瓣用户名：从 DoubanAuthStorage.doubanProfile 取 nickname
                // （若未登录豆瓣或 nickname 为空，传 null）
                val doubanProfile = doubanAuthStorage.doubanProfile.value
                val doubanUsername = doubanProfile?.nickname?.takeIf { it.isNotBlank() }

                val result = feedbackRepository.submit(
                    type = type,
                    content = content,
                    contact = contact,
                    screenshots = screenshotKeys,
                    friendNickname = friendNickname,
                    traktUsername = traktUsername,
                    doubanUsername = doubanUsername,
                    appVersion = BuildConfig.VERSION_NAME,
                    osVersion = "${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})",
                    deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}"
                )
                result.onSuccess { _submitState.value = SubmitState.Success(it.id) }
                    .onFailure { _submitState.value = SubmitState.Error(it.message ?: "SUBMIT_FAILED") }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _submitState.value = SubmitState.Error(e.message ?: "SUBMIT_FAILED")
            }
        }
    }

    fun resetSubmitState() {
        _submitState.value = SubmitState.Idle
    }
}
