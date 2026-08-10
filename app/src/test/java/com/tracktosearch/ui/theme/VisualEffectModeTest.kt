package com.tracktosearch.ui.theme

import com.google.common.truth.Truth.assertThat
import org.junit.Test

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
}
