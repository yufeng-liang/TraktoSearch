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

private val Context.lastConsistencyCheckStore: DataStore<Preferences> by preferencesDataStore(name = "last_consistency_check")

/**
 * 上次状态一致性检查时间存储。
 *
 * 记录最近一次状态一致性检查的时间戳（包括同步后自动检查和设置页手动检查）。
 * 用于设置页手动检查入口的二次确认弹窗，提示用户上次检查时间，避免重复检查。
 */
@Singleton
class LastConsistencyCheckStorage @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val lastCheckAtKey = longPreferencesKey("last_check_at")

    /** 上次状态一致性检查时间戳（首次为 0） */
    suspend fun getLastCheckAt(): Long =
        context.lastConsistencyCheckStore.data.map { it[lastCheckAtKey] ?: 0L }.first()

    /** 记录本次检查完成时间 */
    suspend fun recordCheck() {
        val now = System.currentTimeMillis()
        context.lastConsistencyCheckStore.edit { prefs ->
            prefs[lastCheckAtKey] = now
        }
    }

    /** 清除（退出登录时调用） */
    suspend fun clear() {
        context.lastConsistencyCheckStore.edit { it.clear() }
    }
}
