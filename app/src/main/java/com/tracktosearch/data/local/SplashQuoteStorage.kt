package com.tracktosearch.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.splashQuoteDataStore: DataStore<Preferences> by preferencesDataStore(name = "splash_quote")

/**
 * 开屏「每日一句」开关持久化存储。
 *
 * 默认开启：台词层是启动体验的一部分，关掉后系统场记板结束就直接进主页。
 * 与 [SharedTransitionStorage] 同样用 StateFlow 镜像磁盘值——开屏读值发生在
 * setContent 之前，必须是同步可得的真实值，否则会先闪一下默认态。
 */
@Singleton
class SplashQuoteStorage @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val _enabledState = MutableStateFlow(DEFAULT_ENABLED)
    val enabledState: StateFlow<Boolean> = _enabledState.asStateFlow()

    private val dataStoreFlow: Flow<Boolean> = context.splashQuoteDataStore.data.map { prefs ->
        prefs[KEY_ENABLED] ?: DEFAULT_ENABLED
    }.distinctUntilChanged()

    /** 预加载：启动时同步读取磁盘首值，填入 StateFlow */
    suspend fun preloadAndGetValue(): Boolean {
        val value = dataStoreFlow.first()
        _enabledState.value = value
        return value
    }

    suspend fun setEnabled(value: Boolean) {
        context.splashQuoteDataStore.edit { prefs ->
            prefs[KEY_ENABLED] = value
        }
        _enabledState.value = value
    }

    /**
     * 是否还没展示过开场那一条。
     *
     * 首次安装的第一屏不交给日期取模：装完就看到的那句是这个功能给人的第一印象，
     * 应该是选定的那一条，而不是碰巧落在当天的随便一条。
     */
    suspend fun isDebutPending(): Boolean =
        context.splashQuoteDataStore.data.map { prefs ->
            prefs[KEY_DEBUT_SHOWN] ?: false
        }.first().not()

    /** 开场那一条真的渲染出来之后才落盘，写之前先看标记，避免每次开屏都写一次磁盘 */
    suspend fun markDebutShown() {
        if (!isDebutPending()) return
        context.splashQuoteDataStore.edit { prefs ->
            prefs[KEY_DEBUT_SHOWN] = true
        }
    }

    /**
     * 今天是不是第一次看到开屏台词。
     *
     * 停留时长按它分档：当天第一次看的人要先认海报再从头念，同一天再进 App 的多留一秒都是挡路。
     * 所以问的是「今天」而不是「这次启动」，同一天冷启动几次只有第一次算。
     *
     * 存的是最后展示的那个 epochDay，而不是「今天看过了」的布尔：布尔跨天得有人负责清零，
     * 而这份数据唯一的写入点就是开屏本身——跨天那次启动若在清零前被杀掉，标记就永远是脏的。
     * 存日子把判断收成一次比较，不需要任何重置逻辑。
     */
    suspend fun isFirstShowToday(today: Long): Boolean =
        context.splashQuoteDataStore.data.map { prefs ->
            prefs[KEY_LAST_SHOWN_DAY]
        }.first() != today

    /** 当天真的展示过之后记一次，同一天重复写提前返回：照 [markDebutShown] 的写法，不为每次开屏白写一次盘 */
    suspend fun markShownOn(today: Long) {
        if (!isFirstShowToday(today)) return
        context.splashQuoteDataStore.edit { prefs ->
            prefs[KEY_LAST_SHOWN_DAY] = today
        }
    }

    companion object {
        const val DEFAULT_ENABLED = true
        private val KEY_ENABLED = booleanPreferencesKey("enabled")
        private val KEY_DEBUT_SHOWN = booleanPreferencesKey("debut_shown")
        private val KEY_LAST_SHOWN_DAY = longPreferencesKey("last_shown_day")
    }
}
