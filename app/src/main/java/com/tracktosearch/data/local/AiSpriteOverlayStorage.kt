package com.tracktosearch.data.local

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 精灵自动探头的每日展示计数。
 *
 * 之前这段 SharedPreferences 读写在主搜索页和 Trakt 搜索页各抄了一份，
 * 且 [com.tracktosearch.ui.screen.ai.AiSpriteOverlayPolicy] 实例是页面级 `remember`，
 * 导航离开再回来就重建，会话上限（3 次）等于「每次进页面 3 次」，
 * 跨页的冷却也一并失效。计数收到这里，策略实例收到共享 ViewModel 里。
 *
 * 只是展示节流，不含用户数据，用普通 SharedPreferences 同步读写即可，
 * 不上加密存储也不需要协程。
 */
@Singleton
class AiSpriteOverlayStorage @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val preferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun readDailyCount(dayKey: String): Int =
        if (preferences.getString(KEY_DAY, null) == dayKey) {
            preferences.getInt(KEY_COUNT, 0).coerceAtLeast(0)
        } else {
            0
        }

    fun writeDailyCount(dayKey: String, count: Int) {
        preferences.edit()
            .putString(KEY_DAY, dayKey)
            .putInt(KEY_COUNT, count)
            .apply()
    }

    private companion object {
        // 沿用旧文件名与键名，已经攒下的当日计数不会因为这次搬家被清零
        const val PREFS_NAME = "ai_sprite_overlay_quota_v1"
        const val KEY_DAY = "day_key"
        const val KEY_COUNT = "daily_count"
    }
}
