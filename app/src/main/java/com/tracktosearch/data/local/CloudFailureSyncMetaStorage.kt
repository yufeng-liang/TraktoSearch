package com.tracktosearch.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.cloudFailureSyncMetaStore: DataStore<Preferences> by
    preferencesDataStore(name = "cloud_failure_sync_meta")

/**
 * 豆瓣失败数据「云端同步版本」本地存储。
 *
 * 用于解决「覆盖本地后再次拉取仍提示云端较新」的问题：
 * 云端 payload.uploadedAt 是上传时刻，总是晚于失败条目本身的 failedAt/updatedAt（数据产生时刻），
 * 若仅比较 entity 时间戳，覆盖本地后再次拉取会永远判定云端较新。
 *
 * 覆盖本地成功后，记录本次同步的云端 uploadedAt；
 * 下次比较时用 max(entity 时间戳, lastSyncedUploadedAt) 作为本地最新时间，
 * 即可正确识别「已同步过该云端版本」，不再重复提示覆盖。
 *
 * 本地在覆盖后新增/修改失败条目时，其 failedAt/updatedAt 会大于 lastSyncedUploadedAt，
 * 从而判定本地较新（本地新变更优先）。
 */
@Singleton
class CloudFailureSyncMetaStorage @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val lastSyncedUploadedAtKey = longPreferencesKey("last_synced_uploaded_at")

    /** 上次成功同步并覆盖本地的云端 uploadedAt（首次为 0） */
    suspend fun getLastSyncedUploadedAt(): Long =
        context.cloudFailureSyncMetaStore.data.map { it[lastSyncedUploadedAtKey] ?: 0L }.first()

    /** 记录本次同步覆盖的云端 uploadedAt（取较大值，避免旧值回退） */
    suspend fun recordSyncedUploadedAt(uploadedAt: Long) {
        context.cloudFailureSyncMetaStore.edit { prefs ->
            val prev = prefs[lastSyncedUploadedAtKey] ?: 0L
            prefs[lastSyncedUploadedAtKey] = maxOf(prev, uploadedAt)
        }
    }

    /** 清除（退出登录时调用） */
    suspend fun clear() {
        context.cloudFailureSyncMetaStore.edit { it.clear() }
    }
}
