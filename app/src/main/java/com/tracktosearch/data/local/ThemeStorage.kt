package com.tracktosearch.data.local

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.glance.appwidget.updateAll
import com.tracktosearch.ui.theme.GlassVariant
import com.tracktosearch.ui.theme.MeshPreset
import com.tracktosearch.ui.theme.MonetAccent
import com.tracktosearch.ui.theme.VisualEffectMode
import com.tracktosearch.widget.QuickSearchWidget
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

internal val Context.themeDataStore: DataStore<Preferences> by preferencesDataStore(name = "theme")

@Singleton
class ThemeStorage private constructor(
    private val context: Context,
    private val dataStore: DataStore<Preferences>,
    @Suppress("UNUSED_PARAMETER") constructorMarker: Unit
) {
    @Inject
    constructor(@ApplicationContext context: Context) : this(context, context.themeDataStore, Unit)

    /** 为冷启动时序测试注入可控的 DataStore，生产构造仍使用应用级单例。 */
    internal constructor(
        context: Context,
        dataStore: DataStore<Preferences>
    ) : this(context, dataStore, Unit)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val initializationComplete = CompletableDeferred<Unit>()

    private val _themeMode = MutableStateFlow(MODE_LIGHT)
    val themeMode: StateFlow<String> = _themeMode.asStateFlow()

    /** null = 用户主动选择动态壁纸取色；未配置时使用复古票根主题。*/
    private val _accentColor = MutableStateFlow<MonetAccent?>(MonetAccent.VINTAGE_TICKET)
    val accentColor: StateFlow<MonetAccent?> = _accentColor.asStateFlow()

    private val _visualEffectMode = MutableStateFlow(VisualEffectMode.BLUR)
    val visualEffectMode: StateFlow<VisualEffectMode> = _visualEffectMode.asStateFlow()

    private val _glassVariant = MutableStateFlow(GlassVariant.CLEAR)
    val glassVariant: StateFlow<GlassVariant> = _glassVariant.asStateFlow()

    /** 自定义色调收藏列表（上限 [MAX_CUSTOM_ACCENTS] 个，保持添加顺序） */
    private val _customAccentColors = MutableStateFlow<List<Long>>(emptyList())
    val customAccentColors: StateFlow<List<Long>> = _customAccentColors.asStateFlow()

    /** 当前选中的自定义色调 ARGB（null = 未选中自定义色调） */
    private val _selectedCustomAccentArgb = MutableStateFlow<Long?>(null)
    val selectedCustomAccentArgb: StateFlow<Long?> = _selectedCustomAccentArgb.asStateFlow()

    /** 旧单值 API 只读视图（迁移期兼容，恒等于 [selectedCustomAccentArgb]） */
    @Deprecated("改用 selectedCustomAccentArgb，旧视图仅迁移期保留")
    val customAccentArgb: StateFlow<Long?> = _selectedCustomAccentArgb

    // 主页面背景彩色弥散光晕：预设(枚举名)与开关。默认 BLOOM（星云需解锁霉粉彩蛋）
    private val _meshPreset = MutableStateFlow("BLOOM")
    val meshPreset: StateFlow<String> = _meshPreset.asStateFlow()

    // 背景动效默认关闭，用户需要时再手动开启
    private val _meshEnabled = MutableStateFlow(false)
    val meshEnabled: StateFlow<Boolean> = _meshEnabled.asStateFlow()

    init {
        // 预加载 DataStore 首值到 StateFlow，避免 stateIn 默认值抖动。
        scope.launch {
            val prefs = dataStore.data.first()
            _themeMode.value = prefs[KEY_THEME_MODE] ?: MODE_LIGHT
            _accentColor.value = decodeAccentName(prefs[KEY_ACCENT_COLOR])
            _visualEffectMode.value = VisualEffectMode.fromStorageValue(prefs[KEY_VISUAL_EFFECT_MODE])
            _glassVariant.value = GlassVariant.fromStorageValue(prefs[KEY_GLASS_VARIANT])
            // 自定义色调：先读新列表结构，再处理旧单值 key 的迁移（一次性并入 + 删旧 key）
            val colors = decodeAccentColors(prefs[KEY_CUSTOM_ACCENT_COLORS])
            val legacy = prefs[KEY_CUSTOM_ACCENT_ARGB]
            val (migratedColors, migratedSelected) = mergeLegacyAccent(colors, legacy)
            _customAccentColors.value = migratedColors
            _selectedCustomAccentArgb.value = prefs[KEY_SELECTED_CUSTOM_ACCENT]?.toLongOrNull()
                ?.takeIf { it in migratedColors }
                ?: migratedSelected
            if (legacy != null) {
                // 旧 key 一旦读到（无论是否成功并入）立即淘汰：写回新结构并删除旧 key，
                // 避免下次冷启动又走一次迁移分支。写入失败不阻塞主题加载，下轮再试。
                dataStore.edit { p ->
                    p[KEY_CUSTOM_ACCENT_COLORS] = encodeAccentColors(migratedColors)
                    _selectedCustomAccentArgb.value?.let { p[KEY_SELECTED_CUSTOM_ACCENT] = it.toString() }
                    p.remove(KEY_CUSTOM_ACCENT_ARGB)
                }
            }
            _meshPreset.value = prefs[KEY_MESH_PRESET] ?: "BLOOM"
            _meshEnabled.value = prefs[KEY_MESH_ENABLED] ?: false
            initializationComplete.complete(Unit)
        }.invokeOnCompletion { throwable ->
            if (throwable != null && !initializationComplete.isCompleted) {
                initializationComplete.completeExceptionally(throwable)
            }
        }
    }

    /** 冷启动同步读取已持久化的主题模式（等待 DataStore 首值加载完成）。 */
    suspend fun readThemeModeSnapshot(): String {
        initializationComplete.await()
        return _themeMode.value
    }

    suspend fun setThemeMode(mode: String) {
        initializationComplete.await()
        dataStore.edit { prefs ->
            prefs[KEY_THEME_MODE] = mode
        }
        _themeMode.value = mode
        // App 内主题模式联动系统 uiMode（API 31+ 内部走 UiModeManager.setApplicationNightMode），
        // 让下次冷启动时系统 Splash 直接按 app 设置选择 values/values-night 资源。
        applyThemeModeToSystem(mode)
    }

    suspend fun setAccentColor(accent: MonetAccent?) {
        initializationComplete.await()
        val previousAccent = _accentColor.value
        dataStore.edit { prefs ->
            prefs[KEY_ACCENT_COLOR] = accent?.name ?: DYNAMIC_ACCENT
        }
        _accentColor.value = accent
        if (previousAccent != accent) {
            // Widget 刷新是附加效果，不能让主题设置因桌面组件状态异常而失败。
            try {
                QuickSearchWidget().updateAll(context.applicationContext)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // 未安装 Widget 或启动器暂不可用时，主题设置仍然有效。
            }
        }
    }

    suspend fun setVisualEffectMode(mode: VisualEffectMode) {
        initializationComplete.await()
        dataStore.edit { prefs ->
            prefs[KEY_VISUAL_EFFECT_MODE] = mode.storageValue
            prefs[KEY_GLASS_VARIANT] = GlassVariant.CLEAR.storageValue
        }
        _visualEffectMode.value = mode
        _glassVariant.value = GlassVariant.CLEAR
    }

    suspend fun setVisualEffectSelection(
        mode: VisualEffectMode,
        glassVariant: GlassVariant
    ) {
        initializationComplete.await()
        dataStore.edit { prefs ->
            prefs[KEY_VISUAL_EFFECT_MODE] = mode.storageValue
            prefs[KEY_GLASS_VARIANT] = GlassVariant.CLEAR.storageValue
        }
        _visualEffectMode.value = mode
        _glassVariant.value = GlassVariant.CLEAR
    }

    /**
     * 旧单值 API（迁移期保留）：null = 清除自定义色调；非 null = 收藏并选中该色。
     * 等价于 [addCustomAccent]（同色仅选中）+ [setSelectedCustomAccent]。
     */
    suspend fun setCustomAccent(argb: Long?) {
        if (argb == null) {
            setSelectedCustomAccent(null)
        } else {
            addCustomAccent(argb)
        }
    }

    /**
     * 添加自定义色调到收藏：同色只选中不新增，返回 false；满 [MAX_CUSTOM_ACCENTS] 返回 false；
     * 新增成功返回 true 并自动选中新色。
     */
    suspend fun addCustomAccent(argb: Long): Boolean {
        initializationComplete.await()
        val current = _customAccentColors.value
        if (argb in current) {
            persistSelected(argb)
            return false
        }
        if (current.size >= MAX_CUSTOM_ACCENTS) return false
        persistColorsAndSelected(current + argb, argb)
        return true
    }

    /** 移除收藏色；若移除的是选中色则同时清除选中。 */
    suspend fun removeCustomAccent(argb: Long) {
        initializationComplete.await()
        val current = _customAccentColors.value
        if (argb !in current) return
        val next = current - argb
        persistColorsAndSelected(next, if (_selectedCustomAccentArgb.value == argb) null else _selectedCustomAccentArgb.value)
    }

    /** 原位更新收藏色（编辑模式复用）；选中该色时选中值同步为新色。 */
    suspend fun updateCustomAccent(old: Long, new: Long) {
        initializationComplete.await()
        if (old == new) return
        val current = _customAccentColors.value
        if (old !in current) return
        // 编辑成别的已收藏色时不产生重复：收起该位置的旧色即可（同色只保留一份）。
        val next = current.map { if (it == old) new else it }.distinct()
        val selected = if (_selectedCustomAccentArgb.value == old) new else _selectedCustomAccentArgb.value
        persistColorsAndSelected(next, selected)
    }

    /** 清空全部自定义色调收藏并清除选中。 */
    suspend fun clearCustomAccents() {
        initializationComplete.await()
        if (_customAccentColors.value.isEmpty() && _selectedCustomAccentArgb.value == null) return
        persistColorsAndSelected(emptyList(), null)
    }

    /** 仅设置选中色（需已在收藏列表内，否则视为清除选中）。 */
    suspend fun setSelectedCustomAccent(argb: Long?) {
        initializationComplete.await()
        if (argb != null && argb !in _customAccentColors.value) {
            persistSelected(null)
            return
        }
        persistSelected(argb)
    }

    /** 持久化收藏列表与选中值，并同步内存状态。 */
    private suspend fun persistColorsAndSelected(colors: List<Long>, selected: Long?) {
        dataStore.edit { prefs ->
            prefs[KEY_CUSTOM_ACCENT_COLORS] = encodeAccentColors(colors)
            if (selected != null) {
                prefs[KEY_SELECTED_CUSTOM_ACCENT] = selected.toString()
            } else {
                prefs.remove(KEY_SELECTED_CUSTOM_ACCENT)
            }
        }
        _customAccentColors.value = colors
        persistSelected(selected)
    }

    /** 持久化并发布选中值（不落盘会失去重启后的选中记忆）。 */
    private suspend fun persistSelected(selected: Long?) {
        dataStore.edit { prefs ->
            if (selected != null) {
                prefs[KEY_SELECTED_CUSTOM_ACCENT] = selected.toString()
            } else {
                prefs.remove(KEY_SELECTED_CUSTOM_ACCENT)
            }
        }
        _selectedCustomAccentArgb.value = selected
    }

    suspend fun readAccentColorSnapshot(): MonetAccent? {
        initializationComplete.await()
        val prefs = dataStore.data.first()
        return decodeAccentName(prefs[KEY_ACCENT_COLOR])
    }

    suspend fun readMeshPresetSnapshot(): String {
        initializationComplete.await()
        val prefs = dataStore.data.first()
        return prefs[KEY_MESH_PRESET] ?: MeshPreset.BLOOM.name
    }

    /**
     * 存的是枚举名字符串，所以删枚举项会读到认不出的名字。
     *
     * [RETIRED_ACCENTS] 把删掉的色调映射到色相最近的幸存者（差 2°-9°，
     * 用户基本看不出换了），而不是一律掉回默认的复古票根 ——
     * 从「麦田金黄」跳到棕色是能一眼看出来的，从它跳到「干草堆金」不会。
     * 迁移是静默的，不写回存储：下次用户主动改色调时自然会覆盖掉旧值。
     */
    private fun decodeAccentName(name: String?): MonetAccent? {
        return when (name) {
            DYNAMIC_ACCENT -> null
            null -> MonetAccent.VINTAGE_TICKET
            else -> RETIRED_ACCENTS[name]
                ?: runCatching { MonetAccent.valueOf(name) }
                    .getOrElse { MonetAccent.VINTAGE_TICKET }
        }
    }

    suspend fun setMeshPreset(preset: String) {
        initializationComplete.await()
        dataStore.edit { prefs -> prefs[KEY_MESH_PRESET] = preset }
        _meshPreset.value = preset
    }

    suspend fun setMeshEnabled(enabled: Boolean) {
        initializationComplete.await()
        dataStore.edit { prefs -> prefs[KEY_MESH_ENABLED] = enabled }
        _meshEnabled.value = enabled
    }

    companion object {
        /**
         * 把 app 内主题模式应用到系统 uiMode，让系统 Splash（starting window）在冷启动时
         * 按 app 自己的深浅设置选资源，而不是只看系统夜间模式。
         *
         * dark/light 会覆盖 app 进程的 uiMode（API 31+ 由 AppCompat 走
         * UiModeManager.setApplicationNightMode），system 则恢复跟随系统。
         */
        fun applyThemeModeToSystem(mode: String) {
            val appCompatMode = when (mode) {
                MODE_DARK -> AppCompatDelegate.MODE_NIGHT_YES
                MODE_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
            AppCompatDelegate.setDefaultNightMode(appCompatMode)
        }

        const val MODE_SYSTEM = "system"
        const val MODE_DARK = "dark"
        const val MODE_LIGHT = "light"
        private const val DYNAMIC_ACCENT = "dynamic"
        private val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
        private val KEY_ACCENT_COLOR = stringPreferencesKey("accent_color")
        private val KEY_VISUAL_EFFECT_MODE = stringPreferencesKey("visual_effect_mode")
        private val KEY_GLASS_VARIANT = stringPreferencesKey("glass_variant")
        private val KEY_CUSTOM_ACCENT_COLORS = stringPreferencesKey("custom_accent_colors")
        private val KEY_SELECTED_CUSTOM_ACCENT = stringPreferencesKey("selected_custom_accent")
        private val KEY_CUSTOM_ACCENT_ARGB = longPreferencesKey("custom_accent_argb")
        private val KEY_MESH_PRESET = stringPreferencesKey("mesh_preset")
        private val KEY_MESH_ENABLED = booleanPreferencesKey("mesh_enabled")

        /** 自定义色调收藏数量上限（UI 满额后禁用添加入口） */
        const val MAX_CUSTOM_ACCENTS = 8

        /**
         * 解析收藏列表的落盘格式（逗号分隔的 ARGB 十进制串）。
         * 脏数据宽松处理：非数字段直接丢弃、去重、按上限截断，绝不抛异常 ——
         * 这条路径挂在 DataStore 首值加载里，抛出去等于一条脏数据崩掉整个启动。
         */
        internal fun decodeAccentColors(raw: String?): List<Long> =
            raw?.split(',')
                ?.mapNotNull { it.toLongOrNull() }
                ?.distinct()
                ?.take(MAX_CUSTOM_ACCENTS)
                ?: emptyList()

        internal fun encodeAccentColors(colors: List<Long>): String =
            colors.joinToString(",")

        /**
         * 旧单值 key 并入新列表的迁移决策（纯函数，便于单测）。
         *
         * @return (迁移后的列表, 需要作为选中值的旧色) —— 旧色为 null 表示本次
         * 无迁移选中（旧色未设置、已在列表中、或列表已满放不下旧色）。
         */
        internal fun mergeLegacyAccent(colors: List<Long>, legacy: Long?): Pair<List<Long>, Long?> {
            if (legacy == null || legacy in colors) return colors to null
            if (colors.size >= MAX_CUSTOM_ACCENTS) return colors to null
            return (colors + legacy) to legacy
        }

        /**
         * 已下线的色调 -> 色相最近的幸存者。见 [decodeAccentName]。
         *
         * 这三个当初是重复色：括号里是删除前后两者的 Lab 色相差，
         * 同彩度同明度加上这个色差，肉眼分不出来，所以迁移过去不算换主题。
         */
        private val RETIRED_ACCENTS = mapOf(
            "WHEAT_FIELD" to MonetAccent.HAYSTACK,             // 82° -> 91°
            "ROUEN_CATHEDRAL" to MonetAccent.WATER_LILY,       // 302° -> 304°
            "WATER_LILY_GREEN" to MonetAccent.JAPANESE_BRIDGE, // 154° -> 152°
        )
    }
}
