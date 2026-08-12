package com.tracktosearch.ui.theme

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.common.truth.Truth.assertThat
import com.tracktosearch.data.local.ThemeStorage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

private val Context.themeTestDataStore by preferencesDataStore(name = "theme")
private val dummyPropertyForThemeTest: Int = 0

class VisualEffectModeTest {

    @Test
    fun `missing stored mode defaults to blur`() {
        assertThat(VisualEffectMode.fromStorageValue(null)).isEqualTo(VisualEffectMode.BLUR)
    }

    @Test
    fun `unknown stored mode defaults to blur`() {
        assertThat(VisualEffectMode.fromStorageValue("future_mode"))
            .isEqualTo(VisualEffectMode.BLUR)
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
    fun `glass variant uses stable storage values`() {
        assertThat(GlassVariant.CLEAR.storageValue).isEqualTo("clear")
        assertThat(GlassVariant.FOCUSED.storageValue).isEqualTo("focused")
        assertThat(GlassVariant.fromStorageValue("focused"))
            .isEqualTo(GlassVariant.FOCUSED)
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
        context.themeTestDataStore.edit { prefs ->
            prefs[stringPreferencesKey("visual_effect_mode")] = VisualEffectMode.GLASS.storageValue
        }

        val storage = ThemeStorage(context)
        awaitGlassMode(storage)

        assertThat(storage.visualEffectMode.value).isEqualTo(VisualEffectMode.GLASS)
        assertThat(storage.glassVariant.value).isEqualTo(GlassVariant.CLEAR)

        val prefs = context.themeTestDataStore.data.first()
        assertThat(prefs[stringPreferencesKey("glass_variant")]).isNull()
    }

    @Test
    fun `setVisualEffectSelection writes mode and variant atomically`() = kotlinx.coroutines.test.runTest {
        clearThemePrefs()
        val storage = ThemeStorage(context)
        storage.setVisualEffectSelection(VisualEffectMode.GLASS, GlassVariant.FOCUSED)

        val prefs = context.themeTestDataStore.data.first()
        assertThat(prefs[stringPreferencesKey("visual_effect_mode")])
            .isEqualTo(VisualEffectMode.GLASS.storageValue)
        assertThat(prefs[stringPreferencesKey("glass_variant")])
            .isEqualTo(GlassVariant.FOCUSED.storageValue)
    }

    @Test
    fun `setVisualEffectMode preserves previously selected glass variant`() = kotlinx.coroutines.test.runTest {
        clearThemePrefs()
        val storage = ThemeStorage(context)
        storage.setVisualEffectSelection(VisualEffectMode.GLASS, GlassVariant.FOCUSED)
        storage.setVisualEffectMode(VisualEffectMode.BLUR)

        val prefs = context.themeTestDataStore.data.first()
        assertThat(prefs[stringPreferencesKey("visual_effect_mode")])
            .isEqualTo(VisualEffectMode.BLUR.storageValue)
        assertThat(prefs[stringPreferencesKey("glass_variant")])
            .isEqualTo(GlassVariant.FOCUSED.storageValue)
    }

    private suspend fun clearThemePrefs() {
        context.themeTestDataStore.edit { it.clear() }
    }

    private suspend fun awaitGlassMode(storage: ThemeStorage) {
        withTimeout(5_000) {
            while (storage.visualEffectMode.value != VisualEffectMode.GLASS) {
                delay(10)
            }
        }
    }
}
