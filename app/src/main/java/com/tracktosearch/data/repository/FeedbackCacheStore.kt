package com.tracktosearch.data.repository

import com.tracktosearch.data.remote.feedback.FeedbackDetailResponse
import com.tracktosearch.data.remote.feedback.MineResponse
import com.tracktosearch.data.remote.feedback.MessagesResponse
import com.tracktosearch.data.util.PersistentTtlCache
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
class FeedbackCacheStore(
    private val listCache: PersistentTtlCache<MineResponse>,
    private val detailCache: PersistentTtlCache<FeedbackDetailResponse>,
    private val messagesCache: PersistentTtlCache<MessagesResponse>
) {
    private val _listState = MutableStateFlow<MineResponse?>(null)
    val listState: StateFlow<MineResponse?> = _listState.asStateFlow()
    private val _messagesState = MutableStateFlow<MessagesResponse?>(null)
    val messagesState: StateFlow<MessagesResponse?> = _messagesState.asStateFlow()

    suspend fun loadFromDisk() {
        listCache.awaitLoaded()
        listCache.get(LIST_KEY)?.let { _listState.value = it }
        loadMessagesFromDisk()
        detailCache.awaitLoaded()
    }

    suspend fun loadMessagesFromDisk() {
        messagesCache.awaitLoaded()
        messagesCache.get(MESSAGES_KEY)?.let { _messagesState.value = it }
    }

    fun getCachedList(): MineResponse? = _listState.value

    fun getCachedMessages(): MessagesResponse? = _messagesState.value

    suspend fun saveList(response: MineResponse) {
        _listState.value = response
        listCache.put(LIST_KEY, response)
    }

    suspend fun saveMessages(response: MessagesResponse) {
        val cached = _messagesState.value
        val snapshot = if (response.offset == 0 || cached == null) {
            response
        } else {
            response.copy(
                messages = (cached.messages + response.messages).distinctBy { it.id },
                offset = cached.offset
            )
        }
        _messagesState.value = snapshot
        messagesCache.put(MESSAGES_KEY, snapshot)
    }

    suspend fun getCachedDetail(id: String): FeedbackDetailResponse? {
        detailCache.awaitLoaded()
        return detailCache.get(id)
    }

    suspend fun mergeDetail(id: String, remote: FeedbackDetailResponse): FeedbackDetailResponse {
        val cached = detailCache.get(id)
        val merged = if (cached != null) {
            val mergedReplies = (cached.replies + remote.replies).distinctBy { it.id }.sortedBy { it.created_at }
            val lastReadAt = maxOf(cached.feedback.last_read_at, remote.feedback.last_read_at)
            remote.copy(feedback = remote.feedback.copy(last_read_at = lastReadAt), replies = mergedReplies)
        } else remote
        detailCache.put(id, merged)
        return merged
    }

    suspend fun clearAll() {
        listCache.clearAll()
        messagesCache.clearAll()
        detailCache.clearAll()
        _listState.value = null
        _messagesState.value = null
    }

    companion object {
        private const val LIST_KEY = "feedback_list_v1"
        private const val MESSAGES_KEY = "feedback_messages_v1"
    }
}
