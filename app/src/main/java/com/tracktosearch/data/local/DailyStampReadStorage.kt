package com.tracktosearch.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dailyStampReadDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "daily_stamp_read"
)

/**
 * 台词日历里「那天的卡片读过没有」。
 *
 * 只有错过签到的那些天用得上：签到过的日子当天就把台词念出来了，不存在没读过；
 * 还没到的日子只有糊图，没有可读的东西。错过的那天平时在格子上给一张糊海报，
 * 卡片真展示过一次之后才换成清晰海报。
 *
 * 为什么不写进 daily_stamp 那张表：它是签到账本，streak、累计天数、初次使用日期全部
 * 从它算出来。把「事后补看的日子」写进去等于允许补签到，而签到的意义就是那天真的打开过
 * App（见 DailyStampRepository 的类注释与 AGENTS.md 的防回归约束）。
 *
 * 为什么走 DataStore 而不是新开一张 Room 表：这份数据只是一个 id 集合，量级是「用户
 * 补看了多少个错过的日子」，几百条封顶；而那个库没有 exportSchema、也没有
 * fallbackToDestructiveMigration，加表要补一次版本迁移和对应的迁移测试，漏一格的后果是
 * 开库即崩。代价和风险不对等。
 */
@Singleton
class DailyStampReadStorage @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val _readDays = MutableStateFlow<Set<Long>>(emptySet())

    /**
     * 已读过卡片的 epochDay 集合。要读它得先走 [loadIntoMirror]。
     *
     * 镜像一上来是空集，所以谁要用它决定「那一格糊不糊」，谁就得先 await
     * [loadIntoMirror]：不等的话已经补看过的日子会先按 12px 糊着请求一张图，镜像落地后
     * 又换成 160px 重请求一次 —— 白解一遍图，还留下一眼看得出的「先糊再清」。
     * 日签日历正是这么做的（见 DailyStampViewModel.publishMonth）。
     */
    val readDays: StateFlow<Set<Long>> = _readDays.asStateFlow()

    /**
     * 从磁盘读回已读集合，并进内存镜像，返回合并后的值。
     *
     * 谁在用镜像谁负责调它，而不是构造时自己发一趟 fire-and-forget 的预加载：日历发布整月
     * 数据之前必须先等这一趟落地，否则就是上面那个「先糊再清」。挂在构造里的那次预加载
     * 只是把同一趟读盘再抢一次，还让调用方失去「已经读过」这个确定的时机。
     *
     * 取并集而不是赋值——这次读盘可能和用户点开卡片撞上：那次已经落了盘，
     * 赋值会把刚记下的那天从内存镜像里抹掉，磁盘上却有。
     */
    suspend fun loadIntoMirror(): Set<Long> {
        val stored = readDaysFrom(context.dailyStampReadDataStore.data.first())
        _readDays.update { it + stored }
        return _readDays.value
    }

    /**
     * 记下那天的卡片看过一次。
     *
     * 内存镜像先判重再写盘：日历一页会反复重绘，同一天不该每次都落一次盘。
     */
    suspend fun markRead(epochDay: Long) {
        if (epochDay in _readDays.value) return
        context.dailyStampReadDataStore.edit { prefs ->
            val stored = prefs[KEY_READ_DAYS].orEmpty()
            val key = epochDay.toString()
            if (key !in stored) prefs[KEY_READ_DAYS] = stored + key
        }
        _readDays.update { it + epochDay }
    }

    private fun readDaysFrom(prefs: Preferences): Set<Long> =
        prefs[KEY_READ_DAYS].orEmpty().mapNotNull(String::toLongOrNull).toSet()

    companion object {
        private val KEY_READ_DAYS = stringSetPreferencesKey("read_days")
    }
}
