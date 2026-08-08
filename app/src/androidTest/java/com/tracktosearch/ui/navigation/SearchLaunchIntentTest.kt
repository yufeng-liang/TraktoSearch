package com.tracktosearch.ui.navigation

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SearchLaunchIntentTest {

    @Test
    fun `显式 true extra 识别为打开搜索`() {
        val intent = Intent().putExtra(SearchNavigator.EXTRA_OPEN_SEARCH, true)

        assertThat(SearchNavigator.isOpenSearchIntent(intent)).isTrue()
    }

    @Test
    fun `空 Intent 不打开搜索`() {
        val intent = Intent()

        assertThat(SearchNavigator.isOpenSearchIntent(intent)).isFalse()
    }

    @Test
    fun `显式 false extra 不打开搜索`() {
        val intent = Intent().putExtra(SearchNavigator.EXTRA_OPEN_SEARCH, false)

        assertThat(SearchNavigator.isOpenSearchIntent(intent)).isFalse()
    }

    @Test
    fun `null Intent 不打开搜索`() {
        assertThat(SearchNavigator.isOpenSearchIntent(null)).isFalse()
    }
}
