package com.tracktosearch.data.local

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

private val Context.crashLogRecordStore: DataStore<Preferences> by preferencesDataStore(name = "crash_log_records")

/**
 * 崩溃日志上传记录存储（DataStore key→JSON 列表）。
 *
 * - 每份日志文件一条记录，状态可更新（PENDING/UPLOADING/SUCCESS/FAILED）
 * - 记录始终含日志全文快照，详情页展示与日志文件生命周期解耦
 * - 上限 [MAX_RECORDS] 条，超出丢弃最旧
 * - 内存 StateFlow 为唯一读写源；写操作串行（Mutex 互斥），调用方（Uploader 单线程）保证顺序
 */
@Singleton
class CrashLogRecordStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val json: Json,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()

    private val _records = MutableStateFlow<List<CrashLogRecord>>(emptyList())
    val records: StateFlow<List<CrashLogRecord>> = _records.asStateFlow()

    init {
        scope.launch { loadFromDisk() }
    }

    /**
     * 幂等：等待 DataStore 磁盘数据加载完成（Uploader 上传前调用，避免启动竞态）。
     * 仅当内存尚无数据时应用磁盘内容；内存已有数据说明已有更新的写入，以内存为准。
     */
    suspend fun loadFromDisk() {
        val prefs = context.crashLogRecordStore.data.first()
        val saved = prefs[KEY_RECORDS] ?: "[]"
        val restored = runCatching {
            json.decodeFromString(ListSerializer(CrashLogRecord.serializer()), saved)
        }.getOrElse {
            Log.w(TAG, "崩溃日志记录解析失败，忽略损坏数据", it)
            emptyList()
        }
        // 原子读写：仅当内存尚无数据时应用磁盘内容；内存已有数据说明已有更新的写入，以内存为准
        _records.update { if (it.isEmpty()) restored else it }
    }

    suspend fun getRecord(id: String): CrashLogRecord? =
        _records.value.find { it.id == id }

    suspend fun upsert(record: CrashLogRecord) {
        lock.withLock {
            val updated = (_records.value.filterNot { it.id == record.id } + record)
                .sortedByDescending { it.crashTime }
                .take(MAX_RECORDS)
            _records.value = updated
            persist(updated)
        }
    }

    suspend fun markUploading(id: String) {
        val current = _records.value.find { it.id == id } ?: return
        upsert(current.copy(status = CrashLogRecord.Status.UPLOADING))
    }

    suspend fun markSuccess(id: String, uploadTime: Long) {
        val current = _records.value.find { it.id == id } ?: return
        upsert(current.copy(status = CrashLogRecord.Status.SUCCESS, uploadTime = uploadTime, error = ""))
    }

    suspend fun markFailed(id: String, error: String) {
        val current = _records.value.find { it.id == id } ?: return
        upsert(current.copy(status = CrashLogRecord.Status.FAILED, error = error, uploadTime = 0L))
    }

    private suspend fun persist(records: List<CrashLogRecord>) {
        context.crashLogRecordStore.edit { prefs ->
            prefs[KEY_RECORDS] = json.encodeToString(ListSerializer(CrashLogRecord.serializer()), records)
        }
    }

    companion object {
        private const val TAG = "CrashLogRecordStore"
        private const val MAX_RECORDS = 20
        private val KEY_RECORDS = stringPreferencesKey("crash_log_records_v1")
    }
}
