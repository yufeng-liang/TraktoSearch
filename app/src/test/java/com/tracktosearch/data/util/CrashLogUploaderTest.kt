package com.tracktosearch.data.util

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.tracktosearch.data.local.CrashLogStorage
import com.tracktosearch.data.remote.crash.CrashLogApiService
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class CrashLogUploaderTest {

    private lateinit var crashDir: File

    private fun setup(
        enabled: Boolean = true,
        apiResult: (() -> ResponseBody)? = null
    ): Pair<CrashLogUploader, CrashLogApiService> {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        crashDir = File(context.filesDir, "crash_logs")
        crashDir.mkdirs()
        val storage = mockk<CrashLogStorage>(relaxed = true)
        every { storage.enabled } returns MutableStateFlow(enabled)
        val api = mockk<CrashLogApiService>()
        coEvery { api.upload(any()) } answers {
            apiResult?.invoke() ?: ("{}".toResponseBody("application/json".toMediaTypeOrNull()))
        }
        val uploader = CrashLogUploader(context, api, storage)
        return uploader to api
    }

    private fun writeLogFile(name: String, content: String = "Time: 2026-08-09T10:00:00\n=== Stack Trace ===\nboom") {
        File(crashDir, name).writeText(content)
    }

    @Test
    fun uploadFails_keepsLocalLog() = runTest {
        val (uploader, _) = setup(enabled = true, apiResult = { throw RuntimeException("network") })
        writeLogFile("crash_1.log")

        val ok = uploader.uploadPendingLogs()

        assertThat(ok).isFalse()
        assertThat(File(crashDir, "crash_1.log").exists()).isTrue()
    }

    @Test
    fun uploadSucceeds_deletesLocalLog() = runTest {
        val (uploader, _) = setup(enabled = true, apiResult = { "{}".toResponseBody("application/json".toMediaTypeOrNull()) })
        writeLogFile("crash_1.log")

        val ok = uploader.uploadPendingLogs()

        assertThat(ok).isTrue()
        assertThat(File(crashDir, "crash_1.log").exists()).isFalse()
    }

    @Test
    fun notEnabled_returnsTrue_completesIdempotently() = runTest {
        val (uploader, _) = setup(enabled = false)
        writeLogFile("crash_1.log")

        // 未授权时返回 true 且不删除日志；重复调用不崩溃（complete 幂等）
        val ok1 = uploader.uploadPendingLogs()
        val ok2 = uploader.uploadPendingLogs()

        assertThat(ok1).isTrue()
        assertThat(ok2).isTrue()
        assertThat(File(crashDir, "crash_1.log").exists()).isTrue()
    }
}
