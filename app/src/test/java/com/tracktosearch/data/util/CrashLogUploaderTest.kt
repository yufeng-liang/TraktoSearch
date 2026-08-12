package com.tracktosearch.data.util

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.tracktosearch.data.local.CrashLogRecord
import com.tracktosearch.data.local.CrashLogRecordStore
import com.tracktosearch.data.local.CrashLogStorage
import com.tracktosearch.data.remote.crash.CrashLogApiService
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
        crashDir.listFiles()?.forEach { it.delete() }
        val storage = mockk<CrashLogStorage>(relaxed = true)
        every { storage.loaded } returns MutableStateFlow(true)
        every { storage.enabled } returns MutableStateFlow(enabled)
        val recordStore = mockk<CrashLogRecordStore>(relaxed = true)
        every { recordStore.records } returns MutableStateFlow(emptyList())
        var captured: CrashLogRecord? = null
        coEvery { recordStore.loadFromDisk() } returns Unit
        coEvery { recordStore.getRecord(any()) } answers { captured }
        coEvery { recordStore.upsert(any()) } answers { captured = firstArg<CrashLogRecord>() }
        coEvery { recordStore.markUploading(any()) } returns Unit
        coEvery { recordStore.markSuccess(any(), any()) } returns Unit
        coEvery { recordStore.markFailed(any(), any()) } returns Unit
        val api = mockk<CrashLogApiService>()
        coEvery { api.upload(any()) } answers {
            apiResult?.invoke() ?: ("{}".toResponseBody("application/json".toMediaTypeOrNull()))
        }
        val uploader = CrashLogUploader(context, api, storage, recordStore)
        return uploader to api
    }

    private fun writeLogFile(name: String, content: String = "Time: 2026-08-09T10:00:00\n=== Stack Trace ===\nboom") {
        File(crashDir, name).writeText(content)
    }

    @Test
    fun uploadSucceeds_状态流Success_文件删除() = runTest {
        val (uploader, _) = setup(enabled = true)
        writeLogFile("crash_1.log")

        val ok = uploader.uploadPendingLogs()

        assertThat(ok).isTrue()
        assertThat(uploader.uploadState.first()).isEqualTo(UploadState.Success)
        assertThat(File(crashDir, "crash_1.log").exists()).isFalse()
    }

    @Test
    fun uploadFails_状态流Failed_文件保留() = runTest {
        val (uploader, _) = setup(enabled = true, apiResult = { throw RuntimeException("network") })
        writeLogFile("crash_1.log")

        val ok = uploader.uploadPendingLogs()

        assertThat(ok).isFalse()
        assertThat(uploader.uploadState.first()).isEqualTo(UploadState.Failed("upload failed"))
        assertThat(File(crashDir, "crash_1.log").exists()).isTrue()
    }

    @Test
    fun uploadFails_记录标记FAILED() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        crashDir = File(context.filesDir, "crash_logs")
        crashDir.mkdirs()
        crashDir.listFiles()?.forEach { it.delete() }
        val storage = mockk<CrashLogStorage>(relaxed = true)
        every { storage.loaded } returns MutableStateFlow(true)
        every { storage.enabled } returns MutableStateFlow(true)
        val recordStore = mockk<CrashLogRecordStore>(relaxed = true)
        every { recordStore.records } returns MutableStateFlow(emptyList())
        var captured: CrashLogRecord? = null
        coEvery { recordStore.loadFromDisk() } returns Unit
        coEvery { recordStore.upsert(any()) } answers { captured = firstArg<CrashLogRecord>() }
        coEvery { recordStore.getRecord(any()) } answers { captured }
        coEvery { recordStore.markUploading(any()) } returns Unit
        coEvery { recordStore.markFailed(any(), any()) } answers {
            captured = captured?.copy(status = CrashLogRecord.Status.FAILED, error = secondArg<String>())
        }
        val api = mockk<CrashLogApiService>()
        coEvery { api.upload(any()) } throws RuntimeException("network")
        val uploader = CrashLogUploader(context, api, storage, recordStore)
        writeLogFile("crash_1.log")

        uploader.uploadPendingLogs()

        // uploadLog 内部吞掉异常返回 false → doUpload 记 "upload failed"
        assertThat(captured?.status).isEqualTo(CrashLogRecord.Status.FAILED)
        assertThat(captured?.error).isEqualTo("upload failed")
    }

    @Test
    fun notEnabled_跳过上传_不建记录不删文件() = runTest {
        val (uploader, _) = setup(enabled = false)
        writeLogFile("crash_1.log")

        val ok = uploader.uploadPendingLogs()

        assertThat(ok).isTrue()
        assertThat(File(crashDir, "crash_1.log").exists()).isTrue()
        assertThat(uploader.uploadState.first()).isEqualTo(UploadState.Idle)
    }

    @Test
    fun noFiles_视为成功() = runTest {
        val (uploader, _) = setup(enabled = true)

        val ok = uploader.uploadPendingLogs()

        assertThat(ok).isTrue()
        assertThat(uploader.uploadState.first()).isEqualTo(UploadState.Success)
    }

    @Test
    fun concurrentCalls_只执行一次上传() = runTest {
        val (uploader, api) = setup(enabled = true)
        writeLogFile("crash_1.log")
        var callCount = 0
        coEvery { api.upload(any()) } coAnswers {
            callCount++
            delay(100)
            "{}".toResponseBody("application/json".toMediaTypeOrNull())
        }

        // 并发触发两次：第二个调用在锁外快速路径 join 在途上传（runTest 虚拟调度下覆盖锁外 join 路径），不重复上传
        val first = async { uploader.uploadPendingLogs() }
        val second = async { uploader.uploadPendingLogs() }
        val r1 = first.await()
        val r2 = second.await()

        assertThat(r1).isTrue()
        assertThat(r2).isTrue()
        assertThat(callCount).isEqualTo(1)
        assertThat(uploader.uploadState.first()).isEqualTo(UploadState.Success)
    }

    @Test
    fun secondCallWhileUploading_不重复上传() = runTest {
        val (uploader, api) = setup(enabled = true)
        writeLogFile("crash_1.log")
        var callCount = 0
        coEvery { api.upload(any()) } coAnswers {
            callCount++
            delay(100)
            "{}".toResponseBody("application/json".toMediaTypeOrNull())
        }

        // 第一个调用已进入上传（delay 100 在途），在途期间发起第二个调用：join 复用结果
        val first = async { uploader.uploadPendingLogs() }
        delay(20)
        val second = async { uploader.uploadPendingLogs() }
        val r1 = first.await()
        val r2 = second.await()

        assertThat(r1).isTrue()
        assertThat(r2).isTrue()
        assertThat(callCount).isEqualTo(1)
        assertThat(uploader.uploadState.first()).isEqualTo(UploadState.Success)
    }

    @Test
    fun storeLoadThrows_不抛异常_状态Failed返回false() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        crashDir = File(context.filesDir, "crash_logs")
        crashDir.mkdirs()
        crashDir.listFiles()?.forEach { it.delete() }
        val storage = mockk<CrashLogStorage>(relaxed = true)
        every { storage.loaded } returns MutableStateFlow(true)
        every { storage.enabled } returns MutableStateFlow(true)
        val recordStore = mockk<CrashLogRecordStore>(relaxed = true)
        every { recordStore.records } returns MutableStateFlow(emptyList())
        coEvery { recordStore.loadFromDisk() } throws RuntimeException("disk read failed")
        val api = mockk<CrashLogApiService>()
        val uploader = CrashLogUploader(context, api, storage, recordStore)
        writeLogFile("crash_1.log")

        val ok = uploader.uploadPendingLogs()

        // C1 修复：DataStore 异常被吞掉转为 Failed 状态（不抛到启动协程，deferred 正常完成）
        assertThat(ok).isFalse()
        assertThat(uploader.uploadState.first()).isEqualTo(UploadState.Failed("disk read failed"))
    }

    @Test
    fun mixedFiles_部分成功部分失败_失败文件保留() = runTest {
        val (uploader, api) = setup(enabled = true)
        writeLogFile("crash_a.log")
        writeLogFile("crash_b.log")
        var callCount = 0
        coEvery { api.upload(any()) } answers {
            callCount++
            if (callCount == 1) throw RuntimeException("network")
            "{}".toResponseBody("application/json".toMediaTypeOrNull())
        }

        val ok = uploader.uploadPendingLogs()

        assertThat(ok).isFalse()
        assertThat(uploader.uploadState.first()).isInstanceOf(UploadState.Failed::class.java)
        // sortedBy 顺序第一个文件失败保留，第二个成功删除
        val remaining = crashDir.listFiles()?.filter { it.name.endsWith(".log") } ?: emptyList()
        assertThat(remaining).hasSize(1)
    }

    @Test
    fun notLoaded_等待授权状态加载后上传() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        crashDir = File(context.filesDir, "crash_logs")
        crashDir.mkdirs()
        crashDir.listFiles()?.forEach { it.delete() }
        val storage = mockk<CrashLogStorage>(relaxed = true)
        val loadedFlow = MutableStateFlow(false)
        every { storage.loaded } returns loadedFlow
        every { storage.enabled } returns MutableStateFlow(true)
        val recordStore = mockk<CrashLogRecordStore>(relaxed = true)
        every { recordStore.records } returns MutableStateFlow(emptyList())
        var captured: CrashLogRecord? = null
        coEvery { recordStore.loadFromDisk() } returns Unit
        coEvery { recordStore.getRecord(any()) } answers { captured }
        coEvery { recordStore.upsert(any()) } answers { captured = firstArg<CrashLogRecord>() }
        coEvery { recordStore.markUploading(any()) } returns Unit
        coEvery { recordStore.markSuccess(any(), any()) } returns Unit
        coEvery { recordStore.markFailed(any(), any()) } returns Unit
        val api = mockk<CrashLogApiService>()
        var uploadCalled = 0
        coEvery { api.upload(any()) } answers {
            uploadCalled++
            "{}".toResponseBody("application/json".toMediaTypeOrNull())
        }
        val uploader = CrashLogUploader(context, api, storage, recordStore)
        writeLogFile("crash_1.log")

        // 授权状态未加载时 uploadPendingLogs 挂起等待 loaded，加载完成后继续上传（runTest 虚拟时间驱动）
        launch {
            delay(50)
            loadedFlow.value = true
        }
        val ok = uploader.uploadPendingLogs()

        assertThat(ok).isTrue()
        assertThat(uploadCalled).isEqualTo(1)
        assertThat(File(crashDir, "crash_1.log").exists()).isFalse()
    }
}
