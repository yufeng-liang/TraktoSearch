package com.tracktosearch.ui.navigation

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SearchLaunchIntentTest {

    @Test
    fun explicitTrueExtraIsRecognizedAsOpenSearch() {
        val intent = Intent().putExtra(SearchNavigator.EXTRA_OPEN_SEARCH, true)

        assertThat(SearchNavigator.isOpenSearchIntent(intent)).isTrue()
    }

    @Test
    fun emptyIntentDoesNotOpenSearch() {
        val intent = Intent()

        assertThat(SearchNavigator.isOpenSearchIntent(intent)).isFalse()
    }

    @Test
    fun explicitFalseExtraDoesNotOpenSearch() {
        val intent = Intent().putExtra(SearchNavigator.EXTRA_OPEN_SEARCH, false)

        assertThat(SearchNavigator.isOpenSearchIntent(intent)).isFalse()
    }

    @Test
    fun nullIntentDoesNotOpenSearch() {
        assertThat(SearchNavigator.isOpenSearchIntent(null)).isFalse()
    }
}
