package com.tracktosearch.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
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

internal val Context.swiftieDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "swiftie")

/**
 * 霉粉彩蛋的持久化状态。
 *
 * [unlocked] 与 [quizSolved] 刻意分成两个键：存量用户迁移只写前者（保住已在用的星云背景），
 * 后者留 false 让他们仍有一次解题机会；用 ✕ 关掉题面时也只推进 [cloudClickCount]，不写后者。
 */
@Singleton
class SwiftieEggStorage private constructor(
    private val dataStore: DataStore<Preferences>,
    @Suppress("UNUSED_PARAMETER") constructorMarker: Unit
) {
    @Inject
    constructor(@ApplicationContext context: Context) : this(context.swiftieDataStore, Unit)

    internal constructor(context: Context, dataStore: DataStore<Preferences>) :
        this(dataStore, Unit)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val ready = CompletableDeferred<Unit>()

    private val _unlocked = MutableStateFlow(false)
    val unlocked: StateFlow<Boolean> = _unlocked.asStateFlow()

    private val _quizSolved = MutableStateFlow(false)
    val quizSolved: StateFlow<Boolean> = _quizSolved.asStateFlow()

    private val _cloudClickCount = MutableStateFlow(0)
    val cloudClickCount: StateFlow<Int> = _cloudClickCount.asStateFlow()

    private val _cloudNudgeShown = MutableStateFlow(0)

    /** 已经抖给用户的暗示轮数。抖满 `SwiftieEggController.NUDGE_BUDGET` 轮后永久停。 */
    val cloudNudgeShown: StateFlow<Int> = _cloudNudgeShown.asStateFlow()

    init {
        scope.launch {
            val prefs = dataStore.data.first()
            _unlocked.value = prefs[KEY_UNLOCKED] ?: false
            _quizSolved.value = prefs[KEY_QUIZ_SOLVED] ?: false
            _cloudClickCount.value = prefs[KEY_CLICK_COUNT] ?: 0
            _cloudNudgeShown.value = prefs[KEY_CLOUD_NUDGE_SHOWN] ?: 0
            ready.complete(Unit)
        }.invokeOnCompletion { error ->
            if (error != null && !ready.isCompleted) ready.completeExceptionally(error)
        }
    }

    suspend fun awaitReady() = ready.await()

    suspend fun markUnlocked() {
        ready.await()
        dataStore.edit { it[KEY_UNLOCKED] = true }
        _unlocked.value = true
    }

    suspend fun markQuizSolved() {
        ready.await()
        dataStore.edit { it[KEY_QUIZ_SOLVED] = true }
        _quizSolved.value = true
    }

    /** @return 递增后的点击总数 */
    suspend fun incrementCloudClick(): Int {
        ready.await()
        var next = 0
        dataStore.edit { prefs ->
            next = (prefs[KEY_CLICK_COUNT] ?: 0) + 1
            prefs[KEY_CLICK_COUNT] = next
        }
        _cloudClickCount.value = next
        return next
    }

    /**
     * 记一轮抖完。
     *
     * 调用点必须在一整轮两下都跑完之后：中途用户点了云说明暗示已经得手，那不该
     * 占用配额（spec §4）。
     *
     * @return 递增后的累计轮数
     */
    suspend fun incrementCloudNudgeShown(): Int {
        ready.await()
        var next = 0
        dataStore.edit { prefs ->
            next = (prefs[KEY_CLOUD_NUDGE_SHOWN] ?: 0) + 1
            prefs[KEY_CLOUD_NUDGE_SHOWN] = next
        }
        _cloudNudgeShown.value = next
        return next
    }

    /**
     * 存量用户迁移：升级前已经在用星云背景的人，星云选项不能凭空消失。
     * 只写 [KEY_UNLOCKED]，不写 [KEY_QUIZ_SOLVED]。
     */
    suspend fun migrateLegacyNebulaUser(storedMeshPreset: String?) {
        ready.await()
        val prefs = dataStore.data.first()
        if (prefs[KEY_MIGRATED] == true) return
        dataStore.edit { it[KEY_MIGRATED] = true }
        if (storedMeshPreset == "NEBULA") markUnlocked()
    }

    companion object {
        internal val KEY_UNLOCKED = booleanPreferencesKey("swiftie_unlocked")
        internal val KEY_QUIZ_SOLVED = booleanPreferencesKey("swiftie_quiz_solved")
        internal val KEY_CLICK_COUNT = intPreferencesKey("cloud_click_count")
        internal val KEY_CLOUD_NUDGE_SHOWN = intPreferencesKey("cloud_nudge_shown")
        internal val KEY_MIGRATED = booleanPreferencesKey("swiftie_migrated")
    }
}
