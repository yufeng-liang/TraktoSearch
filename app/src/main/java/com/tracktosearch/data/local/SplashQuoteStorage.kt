package com.tracktosearch.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
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
 * 开屏「每日一句」开关与固定序列进度持久化存储。
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
     * 当前应该展示固定开场序列里的第几条；序列结束后返回 null。
     *
     * 序列按「成功展示过的不同本地日期」推进，不要求连续打开：中间隔几天没来，下一次仍接着
     * 上一条往后展示。同一天反复启动则继续显示当天那一条，不会把三条固定台词一次消耗完。
     *
     * 老版本只存 [KEY_DEBUT_SHOWN]。升级时若它已经是 true，说明老用户早已看过首次开场，
     * 直接视为整段序列完成，避免升级后突然插入两条新手台词；新安装从第 0 条开始。
     */
    suspend fun openingSequenceIndex(today: Long, sequenceSize: Int): Int? {
        require(sequenceSize > 0)
        return context.splashQuoteDataStore.data.map { prefs ->
            val progress = progress(prefs, sequenceSize)
            val lastShownDay = prefs[KEY_OPENING_SEQUENCE_LAST_SHOWN_DAY]
            when {
                progress == 0 -> 0
                lastShownDay == today -> progress - 1
                progress < sequenceSize -> progress
                else -> null
            }
        }.first()
    }

    /**
     * 固定序列中的一条真的渲染出来后推进进度。
     *
     * edit 内重新核对期望下标和日期，避免两个并发回调把进度推进两次。首条展示后同步保留旧版
     * [KEY_DEBUT_SHOWN]，这样降级到旧版也不会重新播放旧的首次台词。
     */
    suspend fun markOpeningQuoteShown(index: Int, today: Long, sequenceSize: Int) {
        require(sequenceSize > 0)
        require(index in 0 until sequenceSize)
        context.splashQuoteDataStore.edit { prefs ->
            val current = progress(prefs, sequenceSize)
            val lastShownDay = prefs[KEY_OPENING_SEQUENCE_LAST_SHOWN_DAY]
            val expected = when {
                current == 0 -> 0
                lastShownDay == today -> current - 1
                current < sequenceSize -> current
                else -> null
            }
            if (expected != index || lastShownDay == today) return@edit

            prefs[KEY_OPENING_SEQUENCE_PROGRESS] = index + 1
            prefs[KEY_OPENING_SEQUENCE_LAST_SHOWN_DAY] = today
            if (index == 0) prefs[KEY_DEBUT_SHOWN] = true
        }
    }

    private fun progress(prefs: Preferences, sequenceSize: Int): Int = openingSequenceProgress(
        storedProgress = prefs[KEY_OPENING_SEQUENCE_PROGRESS],
        legacyDebutShown = prefs[KEY_DEBUT_SHOWN] == true,
        sequenceSize = sequenceSize,
    )

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

    /** 当天真的展示过之后记一次，同一天重复写提前返回，不为每次开屏白写一次盘 */
    suspend fun markShownOn(today: Long) {
        if (!isFirstShowToday(today)) return
        context.splashQuoteDataStore.edit { prefs ->
            prefs[KEY_LAST_SHOWN_DAY] = today
        }
    }


    companion object {
        const val DEFAULT_ENABLED = true
        private val KEY_ENABLED = booleanPreferencesKey("enabled")
        // 兼容旧版首次台词布尔标记；新序列仍写它，保证降级后不会重复播放。
        private val KEY_DEBUT_SHOWN = booleanPreferencesKey("debut_shown")
        private val KEY_OPENING_SEQUENCE_PROGRESS = intPreferencesKey("opening_sequence_progress")
        private val KEY_OPENING_SEQUENCE_LAST_SHOWN_DAY = longPreferencesKey("opening_sequence_last_shown_day")
        private val KEY_LAST_SHOWN_DAY = longPreferencesKey("last_shown_day")
    }
}

/** 旧版只有首次展示布尔值；已有新进度时始终以新进度为准。 */
internal fun openingSequenceProgress(
    storedProgress: Int?,
    legacyDebutShown: Boolean,
    sequenceSize: Int,
): Int = (storedProgress ?: if (legacyDebutShown) sequenceSize else 0)
    .coerceIn(0, sequenceSize)
