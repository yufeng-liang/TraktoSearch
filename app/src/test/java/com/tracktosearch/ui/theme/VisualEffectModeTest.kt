package com.tracktosearch.ui.theme

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.common.truth.Truth.assertThat
import com.tracktosearch.data.local.ThemeStorage
import com.tracktosearch.data.local.themeDataStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

class VisualEffectModeTest {

    @Test
    fun `missing stored mode defaults to glass`() {
        assertThat(VisualEffectMode.fromStorageValue(null)).isEqualTo(VisualEffectMode.GLASS)
    }

    @Test
    fun `unknown stored mode defaults to glass`() {
        assertThat(VisualEffectMode.fromStorageValue("future_mode"))
            .isEqualTo(VisualEffectMode.GLASS)
    }

    @Test
    fun `glass mode uses stable storage value`() {
        assertThat(VisualEffectMode.GLASS.storageValue).isEqualTo("glass")
        assertThat(VisualEffectMode.fromStorageValue("glass"))
            .isEqualTo(VisualEffectMode.GLASS)
    }

    @Test
    fun `missing glass variant defaults to clear`() {
        assertThat(GlassVariant.fromStorageValue(null)).isEqualTo(GlassVariant.CLEAR)
    }

    @Test
    fun `unknown glass variant defaults to clear`() {
        assertThat(GlassVariant.fromStorageValue("future_variant"))
            .isEqualTo(GlassVariant.CLEAR)
    }

    @Test
    fun `old focused glass variant collapses to the single glass profile`() {
        assertThat(GlassVariant.fromStorageValue("focused"))
            .isEqualTo(GlassVariant.CLEAR)
    }

    @Test
    fun `glass variant uses stable storage values`() {
        assertThat(GlassVariant.CLEAR.storageValue).isEqualTo("clear")
        assertThat(GlassVariant.FOCUSED.storageValue).isEqualTo("focused")
        assertThat(GlassVariant.fromStorageValue("focused"))
            .isEqualTo(GlassVariant.CLEAR)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class ThemeStorageCompatibilityTest {

    private val context: Context = RuntimeEnvironment.getApplication()

    @Test
    fun `old glass record without variant loads clear without rewriting`() = kotlinx.coroutines.test.runTest {
        clearThemePrefs()
        context.themeDataStore.edit { prefs ->
            prefs[stringPreferencesKey("visual_effect_mode")] = VisualEffectMode.GLASS.storageValue
        }

        val storage = ThemeStorage(context)
        awaitGlassMode(storage)

        assertThat(storage.visualEffectMode.value).isEqualTo(VisualEffectMode.GLASS)
        assertThat(storage.glassVariant.value).isEqualTo(GlassVariant.CLEAR)

        val prefs = context.themeDataStore.data.first()
        assertThat(prefs[stringPreferencesKey("glass_variant")]).isNull()
    }

    @Test
    fun `setVisualEffectSelection writes mode and variant atomically`() = kotlinx.coroutines.test.runTest {
        clearThemePrefs()
        val storage = ThemeStorage(context)
        storage.setVisualEffectSelection(VisualEffectMode.GLASS, GlassVariant.FOCUSED)

        val prefs = context.themeDataStore.data.first()
        assertThat(prefs[stringPreferencesKey("visual_effect_mode")])
            .isEqualTo(VisualEffectMode.GLASS.storageValue)
        assertThat(prefs[stringPreferencesKey("glass_variant")])
            .isEqualTo(GlassVariant.FOCUSED.storageValue)
    }

    @Test
    fun `setVisualEffectMode normalizes the legacy glass variant`() = kotlinx.coroutines.test.runTest {
        clearThemePrefs()
        val storage = ThemeStorage(context)
        storage.setVisualEffectSelection(VisualEffectMode.GLASS, GlassVariant.FOCUSED)
        storage.setVisualEffectMode(VisualEffectMode.BLUR)

        val prefs = context.themeDataStore.data.first()
        assertThat(prefs[stringPreferencesKey("visual_effect_mode")])
            .isEqualTo(VisualEffectMode.BLUR.storageValue)
        assertThat(storage.glassVariant.value).isEqualTo(GlassVariant.CLEAR)
        assertThat(prefs[stringPreferencesKey("glass_variant")])
            .isEqualTo(GlassVariant.CLEAR.storageValue)
    }

    @Test
    fun `cold start mode setting is not overwritten by initialization snapshot`() = kotlinx.coroutines.test.runTest {
        clearThemePrefs()
        context.themeDataStore.edit { prefs ->
            prefs[stringPreferencesKey("visual_effect_mode")] = VisualEffectMode.GLASS.storageValue
            prefs[stringPreferencesKey("glass_variant")] = GlassVariant.FOCUSED.storageValue
        }
        val initialSnapshot = context.themeDataStore.data.first()
        val delayedDataStore = DelayedInitialSnapshotDataStore(context.themeDataStore, initialSnapshot)

        val storage = ThemeStorage(context, delayedDataStore)
        val setting = async(start = CoroutineStart.UNDISPATCHED) {
            storage.setVisualEffectMode(VisualEffectMode.BLUR)
        }

        assertThat(setting.isActive).isTrue()
        delayedDataStore.releaseInitialSnapshot()
        setting.await()

        assertThat(storage.visualEffectMode.value).isEqualTo(VisualEffectMode.BLUR)
        assertThat(storage.glassVariant.value).isEqualTo(GlassVariant.CLEAR)
        val prefs = context.themeDataStore.data.first()
        assertThat(prefs[stringPreferencesKey("visual_effect_mode")])
            .isEqualTo(VisualEffectMode.BLUR.storageValue)
        assertThat(prefs[stringPreferencesKey("glass_variant")])
            .isEqualTo(GlassVariant.CLEAR.storageValue)
    }

    @Test
    fun `cold start selection is not overwritten by initialization snapshot`() = kotlinx.coroutines.test.runTest {
        clearThemePrefs()
        context.themeDataStore.edit { prefs ->
            prefs[stringPreferencesKey("visual_effect_mode")] = VisualEffectMode.GLASS.storageValue
            prefs[stringPreferencesKey("glass_variant")] = GlassVariant.FOCUSED.storageValue
        }
        val initialSnapshot = context.themeDataStore.data.first()
        val delayedDataStore = DelayedInitialSnapshotDataStore(context.themeDataStore, initialSnapshot)

        val storage = ThemeStorage(context, delayedDataStore)
        val setting = async(start = CoroutineStart.UNDISPATCHED) {
            storage.setVisualEffectSelection(VisualEffectMode.BLUR, GlassVariant.CLEAR)
        }

        assertThat(setting.isActive).isTrue()
        delayedDataStore.releaseInitialSnapshot()
        setting.await()

        assertThat(storage.visualEffectMode.value).isEqualTo(VisualEffectMode.BLUR)
        assertThat(storage.glassVariant.value).isEqualTo(GlassVariant.CLEAR)
        val prefs = context.themeDataStore.data.first()
        assertThat(prefs[stringPreferencesKey("visual_effect_mode")])
            .isEqualTo(VisualEffectMode.BLUR.storageValue)
        assertThat(prefs[stringPreferencesKey("glass_variant")])
            .isEqualTo(GlassVariant.CLEAR.storageValue)
    }

    private suspend fun clearThemePrefs() {
        context.themeDataStore.edit { it.clear() }
    }

    private suspend fun awaitGlassMode(storage: ThemeStorage) {
        withTimeout(5_000) {
            storage.visualEffectMode.first { it == VisualEffectMode.GLASS }
        }
    }

    private class DelayedInitialSnapshotDataStore(
        private val delegate: DataStore<Preferences>,
        private val initialSnapshot: Preferences
    ) : DataStore<Preferences> {
        private val release = CompletableDeferred<Unit>()

        override val data: Flow<Preferences> = flow {
            release.await()
            emit(initialSnapshot)
        }

        override suspend fun updateData(
            transform: suspend (t: Preferences) -> Preferences
        ): Preferences = delegate.updateData(transform)

        fun releaseInitialSnapshot() {
            release.complete(Unit)
        }
    }
}
