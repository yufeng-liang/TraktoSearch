package com.tracktosearch.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.tracktosearch.ui.haptic.HapticMode
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

private val Context.hapticDataStore: DataStore<Preferences> by preferencesDataStore(name = "haptic")

/**
 * 触感四态开关持久化存储：跟随系统（默认）、轻、关闭、强。
 *
 * 只存一个 [HapticMode] 枚举，没有强度数值 —— 轻/强是两档固定档位，绝对强度仍以
 * 系统触感设置为准（跟随系统档不传强度，就是 ROM 调好的默认）。
 *
 * 用 StateFlow 镜像磁盘值，而不是让调用方收裸 DataStore Flow。
 * 触感读值落在每一次点击的主线程路径上，必须同步可得（`modeState.value`），
 * 等一次 Flow 收集就迟了；Compose 侧 collectAsStateWithLifecycle 也因此不需要 initialValue。
 *
 * 本类只管持久化。系统总开关（`View.isHapticFeedbackEnabled`、
 * `Settings.System.HAPTIC_FEEDBACK_ENABLED`）与档位到强度的映射都在引擎层判定，
 * 这里不掺和，也不提供任何绕过系统设置的口子。
 *
 * 设计依据见 docs/superpowers/plans/2026-09-01-haptics-overhaul.md 的「三态开关」
 * 与 2026-09-09 增补（档位重设计）两节。
 */
@Singleton
class HapticStorage @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val _modeState = MutableStateFlow(HapticMode.DEFAULT)

    /** 状态流：预加载后始终持有磁盘真实值，引擎与设置页都读它 */
    val modeState: StateFlow<HapticMode> = _modeState.asStateFlow()

    /** DataStore 原始 Flow（仅 preload 用） */
    private val dataStoreFlow: Flow<HapticMode> = context.hapticDataStore.data.map { prefs ->
        decodeMode(prefs[KEY_MODE])
    }.distinctUntilChanged()

    /**
     * 预加载：启动时读一次磁盘首值填进 [modeState]。
     *
     * 要在引擎开始派发触感之前调用。[modeState] 的初值是「跟随系统」，
     * 预加载完成前用户选的「关闭」还没生效 —— 这段窗口里点一下照样会震。
     */
    suspend fun preloadAndGetValue(): HapticMode {
        val value = dataStoreFlow.first()
        _modeState.value = value
        return value
    }

    /** 写档位：落盘存枚举名，随后同步更新 [modeState]，设置页不必等磁盘回读 */
    suspend fun setMode(mode: HapticMode) {
        context.hapticDataStore.edit { prefs ->
            prefs[KEY_MODE] = mode.name
        }
        _modeState.value = mode
    }

    companion object {
        private val KEY_MODE = stringPreferencesKey("mode")

        /**
         * 解析磁盘里的枚举名，缺值与脏数据一律回落 [HapticMode.DEFAULT]。
         *
         * 存 `name` 不存 `ordinal`：往枚举中间插一档时 ordinal 会把老用户的「关闭」
         * 读成别的档。代价是枚举成员**不能改名** —— 改名等于把老用户存的值变成脏数据，
         * 静默回落成跟随系统。
         *
         * `valueOf` 遇未知名字抛 `IllegalArgumentException`，用 runCatching 吃掉，
         * 不让一条脏数据把 App 弄崩。此处不是挂起调用，不涉及 CancellationException 被误吞。
         *
         * 留 internal 是为了让脏数据回落这条不变式能在纯 JVM 单测里直接断言：
         * `preferencesDataStore` 委托是进程单例，走磁盘测这条会跨用例互相污染。
         */
        internal fun decodeMode(raw: String?): HapticMode =
            if (raw == null) {
                HapticMode.DEFAULT
            } else {
                runCatching { HapticMode.valueOf(raw) }.getOrDefault(HapticMode.DEFAULT)
            }
    }
}
