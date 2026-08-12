package com.tracktosearch.data.local

import android.content.Context
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CrashLogRecordStoreTest {

    private val appContext: Context = RuntimeEnvironment.getApplication()
    private val json = Json { ignoreUnknownKeys = true }
    private lateinit var store: CrashLogRecordStore

    private fun record(id: String, status: CrashLogRecord.Status = CrashLogRecord.Status.PENDING) =
        CrashLogRecord(
            id = id, crashTime = 1000L, status = status,
            logContent = "Time: 2026-08-12 10:00:00\n=== Stack Trace ===\nboom"
        )

    @Test
    fun upsert_新增与更新记录_按崩溃时间倒序() = runTest {
        store = CrashLogRecordStore(appContext, json)

        store.upsert(record("t1_crash_2.log").copy(crashTime = 2000L))
        store.upsert(record("t1_crash_1.log").copy(crashTime = 1000L))
        store.upsert(record("t1_crash_2.log").copy(crashTime = 2000L, status = CrashLogRecord.Status.SUCCESS))

        val records = store.records.first()
        assertThat(records).hasSize(2)
        assertThat(records[0].id).isEqualTo("t1_crash_2.log")
        assertThat(records[0].status).isEqualTo(CrashLogRecord.Status.SUCCESS)
    }

    @Test
    fun upsert_超过上限20条_丢弃最旧记录() = runTest {
        store = CrashLogRecordStore(appContext, json)

        for (i in 1..25) {
            store.upsert(record("t2_crash_$i.log").copy(crashTime = i * 1000L))
        }

        val records = store.records.first()
        assertThat(records).hasSize(20)
        // 最新 20 条保留（t2_crash_6 ~ t2_crash_25），最旧 5 条丢弃
        assertThat(records.minOf { it.crashTime }).isEqualTo(6_000L)
    }

    @Test
    fun loadFromDisk_跨实例恢复记录() = runTest {
        store = CrashLogRecordStore(appContext, json)
        store.upsert(record("t3_crash_1.log"))

        // 新实例模拟 App 重启后重新加载
        val restored = CrashLogRecordStore(appContext, json)
        restored.loadFromDisk()

        val records = restored.records.first()
        assertThat(records).hasSize(1)
        assertThat(records[0].id).isEqualTo("t3_crash_1.log")
        assertThat(records[0].logContent).contains("boom")
    }

    @Test
    fun markSuccess_更新状态与上传时间() = runTest {
        store = CrashLogRecordStore(appContext, json)
        store.upsert(record("t4_crash_1.log"))

        store.markSuccess("t4_crash_1.log", uploadTime = 9999L)

        val r = store.getRecord("t4_crash_1.log")
        assertThat(r?.status).isEqualTo(CrashLogRecord.Status.SUCCESS)
        assertThat(r?.uploadTime).isEqualTo(9999L)
    }

    @Test
    fun markFailed_记录错误信息() = runTest {
        store = CrashLogRecordStore(appContext, json)
        store.upsert(record("t5_crash_1.log"))

        store.markFailed("t5_crash_1.log", error = "network timeout")

        val r = store.getRecord("t5_crash_1.log")
        assertThat(r?.status).isEqualTo(CrashLogRecord.Status.FAILED)
        assertThat(r?.error).isEqualTo("network timeout")
    }
}
