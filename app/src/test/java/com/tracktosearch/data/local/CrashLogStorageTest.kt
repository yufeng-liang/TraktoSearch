package com.tracktosearch.data.local

import android.content.Context
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CrashLogStorageTest {

    private val appContext: Context = RuntimeEnvironment.getApplication()

    @Test
    fun setEnabledTrue_同步置prompted为true() = runTest {
        val storage = CrashLogStorage(appContext)

        storage.setEnabled(true)

        assertThat(storage.isEnabledSync()).isTrue()
        assertThat(storage.isPromptedSync()).isTrue()
    }

    @Test
    fun setEnabledFalse_不改动prompted() = runTest {
        val storage = CrashLogStorage(appContext)
        storage.setPrompted(true)

        storage.setEnabled(false)

        assertThat(storage.isEnabledSync()).isFalse()
        assertThat(storage.isPromptedSync()).isTrue()
    }
}
