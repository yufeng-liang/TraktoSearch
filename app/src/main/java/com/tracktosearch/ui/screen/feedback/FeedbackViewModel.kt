package com.tracktosearch.ui.screen.feedback

import android.content.Context
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tracktosearch.BuildConfig
import com.tracktosearch.R
import com.tracktosearch.data.auth.AuthManager
import com.tracktosearch.data.local.DoubanAuthStorage
import com.tracktosearch.data.local.UserProfileStorage
import com.tracktosearch.data.remote.feedback.FeedbackDetailResponse
import com.tracktosearch.data.remote.feedback.FeedbackListItem
import com.tracktosearch.data.remote.feedback.MessageItem
import com.tracktosearch.data.remote.feedback.MessagesResponse
import com.tracktosearch.data.remote.feedback.UnreadCountResponse
import com.tracktosearch.data.repository.FeedbackCacheStore
import com.tracktosearch.data.repository.FeedbackRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import com.tracktosearch.ui.util.toUserMessage
import javax.inject.Inject

/**
 * 反馈与建议 ViewModel
 * - 列表/详情/提交状态管理
 * - 自动收集设备信息和身份信息（朋友昵称/Trakt/豆瓣用户名）
 */
@HiltViewModel
class FeedbackViewModel @Inject constructor(
    private val feedbackRepository: FeedbackRepository,
    private val cacheStore: FeedbackCacheStore,
    private val authManager: AuthManager,
    private val userProfileStorage: UserProfileStorage,
    private val doubanAuthStorage: DoubanAuthStorage,
    @ApplicationContext private val context: Context
) : ViewModel() {

    sealed interface ListState {
        data object Loading : ListState
        data class Success(val items: List<FeedbackListItem>, val hasMore: Boolean, val offset: Int) : ListState
        data class Error(val message: String) : ListState
    }

    sealed interface DetailState {
        data object Loading : DetailState
        data class Success(val data: FeedbackDetailResponse, val isRefreshing: Boolean = false) : DetailState
        data class Error(val message: String) : DetailState
    }

    sealed interface SubmitState {
        data object Idle : SubmitState
        data class Uploading(val current: Int, val total: Int) : SubmitState
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

    private val _unreadCount = MutableStateFlow(0)
    val unreadCount: StateFlow<Int> = _unreadCount.asStateFlow()

    private val _unreadItems = MutableStateFlow<List<com.tracktosearch.data.remote.feedback.UnreadItem>>(emptyList())
    val unreadItems: StateFlow<List<com.tracktosearch.data.remote.feedback.UnreadItem>> = _unreadItems.asStateFlow()

    sealed interface MessagesState {
        data object Loading : MessagesState
        data class Success(val items: List<MessageItem>, val hasMore: Boolean, val offset: Int) : MessagesState
        data class Error(val message: String) : MessagesState
    }

    enum class MessageFilter { ALL, UNREAD, DEVELOPER, USER }

    private val _messagesState = MutableStateFlow<MessagesState>(MessagesState.Loading)
    val messagesState: StateFlow<MessagesState> = _messagesState.asStateFlow()

    private val _messagesFilter = MutableStateFlow(MessageFilter.ALL)
    val messagesFilter: StateFlow<MessageFilter> = _messagesFilter.asStateFlow()

    // 保留未筛选全集，筛选只影响展示列表，避免切换回“全部”时丢失其他消息。
    private var allMessages: List<MessageItem> = emptyList()

    private val _replyState = MutableStateFlow<ReplyState>(ReplyState.Idle)
    val replyState: StateFlow<ReplyState> = _replyState.asStateFlow()

    sealed interface ReplyState {
        data object Idle : ReplyState
        data class Uploading(val current: Int, val total: Int) : ReplyState
        data object Sending : ReplyState
        data class Success(val replyId: String) : ReplyState
        data class Error(val message: String) : ReplyState
    }

    private var loadListJob: Job? = null

    /** 加载我的反馈列表 */
    fun loadList(refresh: Boolean = false) {
        val currentOffset = (_listState.value as? ListState.Success)?.offset ?: 0
        val offset = if (refresh) 0 else currentOffset
        loadListJob?.cancel()
        loadListJob = viewModelScope.launch {
            try {
                // 先恢复本地列表；有缓存时保持内容可见，网络请求只做静默更新。
                if (refresh && _listState.value !is ListState.Success) {
                    cacheStore.loadFromDisk()
                    cacheStore.getCachedList()?.let { cached ->
                        _listState.value = ListState.Success(
                            items = cached.feedbacks,
                            hasMore = cached.hasMore,
                            offset = cached.offset + cached.feedbacks.size
                        )
                    }
                }
                if (refresh && _listState.value !is ListState.Success) {
                    _listState.value = ListState.Loading
                }
                val result = feedbackRepository.getMine(limit = 20, offset = offset)
                result.onSuccess { response ->
                    val prev = (_listState.value as? ListState.Success)?.items ?: emptyList()
                    val items = if (refresh) response.feedbacks else prev + response.feedbacks
                    _listState.value = ListState.Success(items, response.hasMore, offset + response.feedbacks.size)
                }.onFailure { e ->
                    if (_listState.value !is ListState.Success) {
                        _listState.value = ListState.Error(e.toUserMessage(context, R.string.error_load_failed))
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (_listState.value !is ListState.Success) {
                    _listState.value = ListState.Error(e.toUserMessage(context, R.string.error_load_failed))
                }
            }
        }
    }

    /** 加载详情 */
    fun loadDetail(id: String) {
        val current = (_detailState.value as? DetailState.Success)
            ?.takeIf { it.data.feedback.id == id }
        if (current != null) {
            _detailState.value = current.copy(isRefreshing = true)
        } else {
            _detailState.value = DetailState.Loading
        }
        viewModelScope.launch {
            try {
                if (current == null) {
                    cacheStore.loadFromDisk()
                    cacheStore.getCachedDetail(id)?.let {
                        _detailState.value = DetailState.Success(it, isRefreshing = true)
                    }
                }
                val result = feedbackRepository.getDetail(id)
                result.onSuccess { _detailState.value = DetailState.Success(it) }
                    .onFailure { e ->
                        if (_detailState.value !is DetailState.Success) {
                            _detailState.value = DetailState.Error(e.toUserMessage(context, R.string.error_load_failed))
                        } else {
                            _detailState.value = (_detailState.value as DetailState.Success)
                                .copy(isRefreshing = false)
                        }
                    }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (_detailState.value !is DetailState.Success) {
                    _detailState.value = DetailState.Error(e.toUserMessage(context, R.string.error_load_failed))
                } else {
                    _detailState.value = (_detailState.value as DetailState.Success)
                        .copy(isRefreshing = false)
                }
            }
        }
    }

    /** 提交反馈（含截图上传） */
    fun submit(
        type: String,
        content: String,
        screenshotBytes: List<ByteArray>,
        screenshotMimeTypes: List<String>
    ) {
        _submitState.value = SubmitState.Uploading(0, screenshotBytes.size)
        viewModelScope.launch {
            try {
                // 上传截图
                val screenshotKeys = mutableListOf<String>()
                for ((index, bytes) in screenshotBytes.withIndex()) {
                    _submitState.value = SubmitState.Uploading(index, screenshotBytes.size)
                    val mimeType = screenshotMimeTypes.getOrNull(index) ?: "image/jpeg"
                    val keyResult = feedbackRepository.uploadScreenshot(bytes, mimeType)
                    keyResult.onSuccess { screenshotKeys.add(it) }
                        .onFailure {
                            _submitState.value = SubmitState.Error(it.toUserMessage(context, R.string.feedback_submit_failed))
                            return@launch
                        }
                }
                if (screenshotBytes.isNotEmpty()) {
                    _submitState.value = SubmitState.Uploading(screenshotBytes.size, screenshotBytes.size)
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
                    screenshots = screenshotKeys,
                    friendNickname = friendNickname,
                    traktUsername = traktUsername,
                    doubanUsername = doubanUsername,
                    appVersion = BuildConfig.VERSION_NAME,
                    osVersion = "${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})",
                    deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}"
                )
                result.onSuccess { _submitState.value = SubmitState.Success(it.id) }
                    .onFailure { _submitState.value = SubmitState.Error(it.toUserMessage(context, R.string.feedback_submit_failed)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _submitState.value = SubmitState.Error(e.toUserMessage(context, R.string.feedback_submit_failed))
            }
        }
    }

    fun resetSubmitState() {
        _submitState.value = SubmitState.Idle
    }

    fun fetchUnreadCount() {
        viewModelScope.launch {
            try {
                val result = feedbackRepository.getUnreadCount()
                result.onSuccess {
                    _unreadCount.value = it.count
                    _unreadItems.value = it.items
                }
            } catch (e: CancellationException) { throw e } catch (_: Exception) {}
        }
    }

    fun markAsRead(feedbackId: String) {
        viewModelScope.launch {
            try {
                feedbackRepository.markAsRead(feedbackId).onSuccess {
                    val removed = _unreadItems.value.filter { it.feedback_id != feedbackId }
                    val reduced = _unreadItems.value.size - removed.size
                    if (reduced > 0) {
                        _unreadItems.value = removed
                        _unreadCount.value = (_unreadCount.value - reduced).coerceAtLeast(0)
                    }
                    allMessages = allMessages.map { item ->
                        if (item.feedback_id == feedbackId) item.copy(is_unread = false) else item
                    }
                    updateMessagesStateFromAll()
                }
            } catch (e: CancellationException) { throw e } catch (_: Exception) {}
        }
    }

    fun markAllRead() {
        viewModelScope.launch {
            try {
                feedbackRepository.markAllRead().onSuccess {
                    _unreadCount.value = 0
                    _unreadItems.value = emptyList()
                    allMessages = allMessages.map { it.copy(is_unread = false) }
                    updateMessagesStateFromAll()
                }
            } catch (e: CancellationException) { throw e } catch (_: Exception) {}
        }
    }

    fun loadMessages(refresh: Boolean = false, filter: MessageFilter? = null) {
        if (filter != null) _messagesFilter.value = filter
        val currentOffset = (_messagesState.value as? MessagesState.Success)?.offset ?: 0
        val offset = if (refresh) 0 else currentOffset
        val hadVisibleMessages = _messagesState.value is MessagesState.Success
        if (refresh && !hadVisibleMessages) {
            _messagesState.value = MessagesState.Loading
        }
        viewModelScope.launch {
            try {
                if (refresh && !hadVisibleMessages) {
                    cacheStore.loadMessagesFromDisk()
                    cacheStore.getCachedMessages()?.let { cached ->
                        allMessages = cached.messages
                        _messagesState.value = MessagesState.Success(
                            items = applyFilter(allMessages, _messagesFilter.value),
                            hasMore = cached.hasMore,
                            offset = cached.offset + cached.messages.size
                        )
                    }
                }
                val result = feedbackRepository.getMessages(limit = 50, offset = offset)
                result.onSuccess { response ->
                    allMessages = if (refresh) response.messages else allMessages + response.messages
                    _messagesState.value = MessagesState.Success(applyFilter(allMessages, _messagesFilter.value), response.hasMore, offset + response.messages.size)
                }.onFailure { e ->
                    if (_messagesState.value !is MessagesState.Success) {
                        _messagesState.value = MessagesState.Error(e.toUserMessage(context, R.string.error_load_failed))
                    }
                }
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                if (_messagesState.value !is MessagesState.Success) {
                    _messagesState.value = MessagesState.Error(e.toUserMessage(context, R.string.error_load_failed))
                }
            }
        }
    }

    fun setMessagesFilter(filter: MessageFilter) {
        _messagesFilter.value = filter
        updateMessagesStateFromAll()
    }

    private fun updateMessagesStateFromAll() {
        val current = _messagesState.value as? MessagesState.Success ?: return
        _messagesState.value = current.copy(items = applyFilter(allMessages, _messagesFilter.value))
    }

    private fun applyFilter(items: List<MessageItem>, filter: MessageFilter): List<MessageItem> {
        return when (filter) {
            MessageFilter.ALL -> items
            MessageFilter.UNREAD -> items.filter { it.is_unread }
            MessageFilter.DEVELOPER -> items.filter { it.author_role == "developer" }
            MessageFilter.USER -> items.filter { it.author_role == "user" }
        }
    }

    fun reply(feedbackId: String, content: String, screenshotBytes: List<ByteArray>, screenshotMimeTypes: List<String>) {
        _replyState.value = ReplyState.Uploading(0, screenshotBytes.size)
        viewModelScope.launch {
            try {
                val keys = mutableListOf<String>()
                for ((index, bytes) in screenshotBytes.withIndex()) {
                    _replyState.value = ReplyState.Uploading(index, screenshotBytes.size)
                    val mimeType = screenshotMimeTypes.getOrNull(index) ?: "image/jpeg"
                    val keyResult = feedbackRepository.uploadScreenshot(bytes, mimeType)
                    keyResult.onSuccess { keys.add(it) }.onFailure {
                        _replyState.value = ReplyState.Error(it.toUserMessage(context, R.string.error_operation_failed))
                        return@launch
                    }
                }
                _replyState.value = ReplyState.Sending
                val result = feedbackRepository.reply(feedbackId, content.trim(), keys)
                result.onSuccess { _replyState.value = ReplyState.Success(it.reply_id) }.onFailure { _replyState.value = ReplyState.Error(it.toUserMessage(context, R.string.error_operation_failed)) }
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                _replyState.value = ReplyState.Error(e.toUserMessage(context, R.string.error_operation_failed))
            }
        }
    }

    fun resetReplyState() { _replyState.value = ReplyState.Idle }
}
