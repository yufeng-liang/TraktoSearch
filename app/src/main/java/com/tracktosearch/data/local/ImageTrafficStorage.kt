package com.tracktosearch.data.local

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private val Context.imageTrafficStore: DataStore<Preferences> by preferencesDataStore(name = "image_traffic")

/** 图片下载流量统计（供设置页展示，判断是否需要接入国内 CDN） */
@Immutable
data class ImageTrafficStats(
    val todayBytes: Long = 0L,
    val totalBytes: Long = 0L
)

/**
 * 图片流量统计存储：拦截器高频上报字节数，内存累计 + 防抖落盘。
 * 跨天自动重置「今日」计数，累计总量保留。
 */
@Singleton
class ImageTrafficStorage @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _stats = MutableStateFlow(ImageTrafficStats())
    val stats: StateFlow<ImageTrafficStats> = _stats.asStateFlow()

    // 内存累计（拦截器在 IO 线程高频写入，Atomic 保证线程安全）
    private val totalBytes = AtomicLong(0)
    private val dayBytes = AtomicLong(0)
    private val dayDate = AtomicLong(0) // epochDay，跨天判断用整数比较

    private var persistJob: Job? = null

    init {
        // 预加载历史值；若拦截器在加载完成前已上报增量，则合并避免覆盖
        scope.launch {
            val prefs = context.imageTrafficStore.data.first()
            val memTotal = totalBytes.get()
            val memDay = dayBytes.get()
            val today = todayEpochDay()
            val storedDay = prefs[KEY_DAY_EPOCH_DAY] ?: today
            totalBytes.set((prefs[KEY_TOTAL] ?: 0L) + memTotal)
            dayBytes.set(
                if (storedDay == today) (prefs[KEY_DAY_BYTES] ?: 0L) + memDay else memDay
            )
            dayDate.set(if (storedDay == today) today else today)
            push()
        }
    }

    /** 拦截器上报实际下载字节数（任意线程可调用） */
    fun record(bytes: Long) {
        if (bytes <= 0) return
        val today = todayEpochDay()
        if (dayDate.get() != today) {
            dayDate.set(today)
            dayBytes.set(0)
        }
        totalBytes.addAndGet(bytes)
        dayBytes.addAndGet(bytes)
        push()
        schedulePersist()
    }

    /** 清零统计（设置页手动清零） */
    fun clear() {
        scope.launch {
            totalBytes.set(0)
            dayBytes.set(0)
            dayDate.set(todayEpochDay())
            context.imageTrafficStore.edit { it.clear() }
            push()
        }
    }

    private fun push() {
        _stats.value = ImageTrafficStats(
            todayBytes = dayBytes.get(),
            totalBytes = totalBytes.get()
        )
    }

    /** 防抖落盘：高频上报时合并写，避免每次字节更新都触发 DataStore 事务 */
    private fun schedulePersist() {
        persistJob?.cancel()
        persistJob = scope.launch {
            delay(PERSIST_DEBOUNCE_MS)
            persist()
        }
    }

    private suspend fun persist() {
        val t = totalBytes.get()
        val d = dayBytes.get()
        val date = dayDate.get()
        context.imageTrafficStore.edit { prefs ->
            prefs[KEY_TOTAL] = t
            prefs[KEY_DAY_BYTES] = d
            prefs[KEY_DAY_EPOCH_DAY] = date
        }
    }

    private fun todayEpochDay(): Long = LocalDate.now().toEpochDay()

    private companion object {
        const val PERSIST_DEBOUNCE_MS = 2_000L
        val KEY_TOTAL = longPreferencesKey("total_bytes")
        val KEY_DAY_BYTES = longPreferencesKey("day_bytes")
        val KEY_DAY_EPOCH_DAY = longPreferencesKey("day_epoch_day")
    }
}
