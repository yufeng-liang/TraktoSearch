package com.tracktosearch.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.sharedTransitionDataStore: DataStore<Preferences> by preferencesDataStore(name = "shared_transition")

/**
 * 共享元素转场动画开关持久化存储。
 *
 * 默认关闭(false):首次使用时所有转场动画消失,设置页 scrollGate 保存机制也随之移除。
 * 用户可在设置页「外观」分组手动开启。
 */
@Singleton
class SharedTransitionStorage @Inject constructor(
    @ApplicationContext private val context: Context
) {
    val enabled: Flow<Boolean> = context.sharedTransitionDataStore.data.map { prefs ->
        prefs[KEY_ENABLED] ?: DEFAULT_ENABLED
    }.distinctUntilChanged()

    suspend fun setEnabled(value: Boolean) {
        context.sharedTransitionDataStore.edit { prefs ->
            prefs[KEY_ENABLED] = value
        }
    }

    companion object {
        /** 默认关闭:转场动画默认不启用 */
        const val DEFAULT_ENABLED = false
        private val KEY_ENABLED = booleanPreferencesKey("enabled")
    }
}
