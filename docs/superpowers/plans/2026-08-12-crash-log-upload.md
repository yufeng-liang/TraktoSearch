# 崩溃日志上报增强 实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 崩溃后启动提示上报改为「成功 toast / 失败不关对话框 + 重试」，修复设置页开关开启后崩溃不自动上报的问题，崩溃日志上传记录显示在反馈页并可查看详情。

**架构：** 上传改为状态机（`CrashLogUploader` 暴露 `StateFlow<UploadState>` + in-flight 互斥），新增 `CrashLogRecordStore`（DataStore JSON 列表，每份日志一条记录，状态可更新），MainActivity 用 Compose 顶层状态驱动对话框替换原生 AlertDialog，反馈页新增「崩溃日志」区块 + `CrashLogDetailScreen` 详情页。

**技术栈：** Kotlin + Compose + Hilt + DataStore + kotlinx.serialization；测试用 Robolectric + MockK + Truth + runTest + MockWebServer（沿用现有模式）。

**规格文档：** `docs/superpowers/specs/2026-08-12-crash-log-upload-design.md`

**工作树隔离：** 本计划所有改动在 worktree `feat/crash-log-upload` 中进行（任务 1），主仓库 master 不动。

---

### 任务 1：创建隔离 worktree

**文件：** 无（git 操作）

- [ ] **步骤 1：创建 worktree 分支**

```bash
cd F:/trae-project
git worktree add F:/trae-project-worktrees/crash-upload -b feat/crash-log-upload
```

预期：输出 `Preparing worktree`，新分支 `feat/crash-log-upload` 从 master 检出。

- [ ] **步骤 2：复制 local.properties**

```bash
cp F:/trae-project/local.properties F:/trae-project-worktrees/crash-upload/local.properties
```

预期：无输出。验证：`ls F:/trae-project-worktrees/crash-upload/local.properties` 存在。

- [ ] **步骤 3：确认后续命令的工作目录**

后续所有任务在 `cd F:/trae-project-worktrees/crash-upload` 下执行（本文其余命令默认该目录）。

---

### 任务 2：CrashLogRecord 模型 + CrashLogRecordStore

**文件：**
- 创建：`app/src/main/java/com/tracktosearch/data/local/CrashLogRecord.kt`
- 创建：`app/src/main/java/com/tracktosearch/data/local/CrashLogRecordStore.kt`
- 测试：`app/src/test/java/com/tracktosearch/data/local/CrashLogRecordStoreTest.kt`

- [ ] **步骤 1：编写失败的测试**

```kotlin
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

        store.upsert(record("crash_2.log").copy(crashTime = 2000L))
        store.upsert(record("crash_1.log").copy(crashTime = 1000L))
        store.upsert(record("crash_2.log").copy(crashTime = 2000L, status = CrashLogRecord.Status.SUCCESS))

        val records = store.records.first()
        assertThat(records).hasSize(2)
        assertThat(records[0].id).isEqualTo("crash_2.log")
        assertThat(records[0].status).isEqualTo(CrashLogRecord.Status.SUCCESS)
    }

    @Test
    fun upsert_超过上限20条_丢弃最旧记录() = runTest {
        store = CrashLogRecordStore(appContext, json)

        for (i in 1..25) {
            store.upsert(record("crash_$i.log").copy(crashTime = i * 1000L))
        }

        val records = store.records.first()
        assertThat(records).hasSize(20)
        // 最新 20 条保留（crash_6 ~ crash_25），最旧 5 条丢弃
        assertThat(records.minOf { it.crashTime }).isEqualTo(6_000L)
    }

    @Test
    fun loadFromDisk_跨实例恢复记录() = runTest {
        store = CrashLogRecordStore(appContext, json)
        store.upsert(record("crash_1.log"))

        // 新实例模拟 App 重启后重新加载
        val restored = CrashLogRecordStore(appContext, json)
        restored.loadFromDisk()

        val records = restored.records.first()
        assertThat(records).hasSize(1)
        assertThat(records[0].id).isEqualTo("crash_1.log")
        assertThat(records[0].logContent).contains("boom")
    }

    @Test
    fun markSuccess_更新状态与上传时间() = runTest {
        store = CrashLogRecordStore(appContext, json)
        store.upsert(record("crash_1.log"))

        store.markSuccess("crash_1.log", uploadTime = 9999L)

        val r = store.getRecord("crash_1.log")
        assertThat(r?.status).isEqualTo(CrashLogRecord.Status.SUCCESS)
        assertThat(r?.uploadTime).isEqualTo(9999L)
    }

    @Test
    fun markFailed_记录错误信息() = runTest {
        store = CrashLogRecordStore(appContext, json)
        store.upsert(record("crash_1.log"))

        store.markFailed("crash_1.log", error = "network timeout")

        val r = store.getRecord("crash_1.log")
        assertThat(r?.status).isEqualTo(CrashLogRecord.Status.FAILED)
        assertThat(r?.error).isEqualTo("network timeout")
    }
}
```

- [ ] **步骤 2：运行测试验证失败**

运行：`cd F:/trae-project-worktrees/crash-upload && ./gradlew :app:testDebugUnitTest --tests "com.tracktosearch.data.local.CrashLogRecordStoreTest" --offline`
预期：编译失败（类不存在）。

- [ ] **步骤 3：创建 CrashLogRecord 模型**

```kotlin
package com.tracktosearch.data.local

import kotlinx.serialization.Serializable

/** 崩溃日志上传记录（每份日志文件一条，状态可更新，详情页直接展示快照与文件生命周期解耦） */
@Serializable
data class CrashLogRecord(
    val id: String,               // 日志文件名（crash_2026-08-12_10-32-00.log）
    val crashTime: Long,          // 崩溃时间戳（由文件名解析）
    val status: Status = Status.PENDING,
    val uploadTime: Long = 0L,    // 上传完成时间戳（0 = 未完成）
    val error: String = "",       // 失败原因（英文，非 UI 展示文案）
    val logContent: String,       // 日志全文快照
    val appVersion: String = "",
    val device: String = "",
) {
    @Serializable
    enum class Status { PENDING, UPLOADING, SUCCESS, FAILED }
}
```

- [ ] **步骤 4：创建 CrashLogRecordStore**

```kotlin
package com.tracktosearch.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

private val Context.crashLogRecordStore: DataStore<Preferences> by preferencesDataStore(name = "crash_log_records")

/**
 * 崩溃日志上传记录存储（DataStore key→JSON 列表）。
 *
 * - 每份日志文件一条记录，状态可更新（PENDING/UPLOADING/SUCCESS/FAILED）
 * - 记录始终含日志全文快照，详情页展示与日志文件生命周期解耦
 * - 上限 [MAX_RECORDS] 条，超出丢弃最旧
 * - 内存 StateFlow 为唯一读写源；写操作串行（synchronized），调用方（Uploader 单线程）保证顺序
 */
@Singleton
class CrashLogRecordStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val json: Json,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()

    private val _records = MutableStateFlow<List<CrashLogRecord>>(emptyList())
    val records: StateFlow<List<CrashLogRecord>> = _records.asStateFlow()

    init {
        scope.launch { loadFromDisk() }
    }

    /** 幂等：等待 DataStore 磁盘数据加载完成（Uploader 上传前调用，避免启动竞态） */
    suspend fun loadFromDisk() {
        val prefs = context.crashLogRecordStore.data.first()
        val saved = prefs[KEY_RECORDS] ?: "[]"
        val restored = runCatching {
            json.decodeFromString(ListSerializer(CrashLogRecord.serializer()), saved)
        }.getOrDefault(emptyList())
        _records.value = restored
    }

    suspend fun getRecord(id: String): CrashLogRecord? =
        _records.value.find { it.id == id }

    suspend fun upsert(record: CrashLogRecord) {
        synchronized(lock) {
            val updated = (_records.value.filterNot { it.id == record.id } + record)
                .sortedByDescending { it.crashTime }
                .take(MAX_RECORDS)
            _records.value = updated
            persist(updated)
        }
    }

    suspend fun markUploading(id: String) {
        val current = _records.value.find { it.id == id } ?: return
        upsert(current.copy(status = CrashLogRecord.Status.UPLOADING))
    }

    suspend fun markSuccess(id: String, uploadTime: Long) {
        val current = _records.value.find { it.id == id } ?: return
        upsert(current.copy(status = CrashLogRecord.Status.SUCCESS, uploadTime = uploadTime, error = ""))
    }

    suspend fun markFailed(id: String, error: String) {
        val current = _records.value.find { it.id == id } ?: return
        upsert(current.copy(status = CrashLogRecord.Status.FAILED, error = error))
    }

    private suspend fun persist(records: List<CrashLogRecord>) {
        context.crashLogRecordStore.edit { prefs ->
            prefs[KEY_RECORDS] = json.encodeToString(ListSerializer(CrashLogRecord.serializer()), records)
        }
    }

    companion object {
        private const val MAX_RECORDS = 20
        private val KEY_RECORDS = stringPreferencesKey("crash_log_records_v1")
    }
}
```

- [ ] **步骤 5：运行测试验证通过**

运行：`./gradlew :app:testDebugUnitTest --tests "com.tracktosearch.data.local.CrashLogRecordStoreTest" --offline`
预期：5 个测试全部 PASS。（DataStore 单例按 name 固定，Robolectric 跨测试共享文件；本套测试全部基于相对 crashTime 断言，天然免疫残留数据。若因跨套件残留导致断言不稳，在 `setUp` 中先 `store.loadFromDisk()` 后对已知 id 建测试专属前缀文件名）

- [ ] **步骤 6：Commit**

```bash
git add app/src/main/java/com/tracktosearch/data/local/CrashLogRecord.kt app/src/main/java/com/tracktosearch/data/local/CrashLogRecordStore.kt app/src/test/java/com/tracktosearch/data/local/CrashLogRecordStoreTest.kt
git commit -m "feat(崩溃上报): 新增上传记录存储 CrashLogRecordStore"
```

---

### 任务 3：CrashLogStorage.setEnabled 同步 prompted

**文件：**
- 修改：`app/src/main/java/com/tracktosearch/data/local/CrashLogStorage.kt:54-59`
- 测试：`app/src/test/java/com/tracktosearch/data/local/CrashLogStorageTest.kt`（新建）

- [ ] **步骤 1：编写失败的测试**

```kotlin
package com.tracktosearch.data.local

import android.content.Context
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
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
```

- [ ] **步骤 2：运行测试验证失败**

运行：`./gradlew :app:testDebugUnitTest --tests "com.tracktosearch.data.local.CrashLogStorageTest" --offline`
预期：`setEnabledTrue_同步置prompted为true` FAIL（prompted 仍 false）。

- [ ] **步骤 3：修改 setEnabled**

`CrashLogStorage.kt` 中 `setEnabled` 改为：

```kotlin
suspend fun setEnabled(enabled: Boolean) {
    context.crashLogDataStore.edit { prefs ->
        prefs[KEY_ENABLED] = enabled
        // 开启即视为已授权引导，避免下次崩溃时再次弹首次授权弹窗（设置页开关=自动上报语义）
        if (enabled) prefs[KEY_PROMPTED] = true
    }
    _enabled.value = enabled
    if (enabled) _prompted.value = true
}
```

- [ ] **步骤 4：运行测试验证通过**

运行：`./gradlew :app:testDebugUnitTest --tests "com.tracktosearch.data.local.CrashLogStorageTest" --offline`
预期：2 个测试全部 PASS。

- [ ] **步骤 5：Commit**

```bash
git add app/src/main/java/com/tracktosearch/data/local/CrashLogStorage.kt app/src/test/java/com/tracktosearch/data/local/CrashLogStorageTest.kt
git commit -m "fix(崩溃上报): 设置页开启开关同步标记已引导避免重复弹窗"
```

---

### 任务 4：CrashLogUploader 状态机改造

**文件：**
- 创建：`app/src/main/java/com/tracktosearch/data/util/UploadState.kt`
- 修改：`app/src/main/java/com/tracktosearch/data/util/CrashLogUploader.kt`（全文重写，保留 uploadLog 解析逻辑）
- 测试：`app/src/test/java/com/tracktosearch/data/util/CrashLogUploaderTest.kt`（重写）

- [ ] **步骤 1：创建 UploadState**

```kotlin
package com.tracktosearch.data.util

/** 崩溃日志上传状态（状态机终态为 Success / Failed，下次上传开始时回到 Uploading） */
sealed interface UploadState {
    data object Idle : UploadState
    data object Uploading : UploadState
    data object Success : UploadState
    data class Failed(val error: String) : UploadState
}
```

- [ ] **步骤 2：重写 CrashLogUploader**

```kotlin
package com.tracktosearch.data.util

import android.content.Context
import android.os.Build
import com.tracktosearch.data.local.CrashLogRecord
import com.tracktosearch.data.local.CrashLogRecordStore
import com.tracktosearch.data.local.CrashLogStorage
import com.tracktosearch.data.remote.crash.CrashLogApiService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 崩溃日志上报器（Hilt 单例，状态机）。
 *
 * 走 auth-worker 代理（${GATEWAY_BASE_URL}/api/crash-logs），客户端不持有上报密钥。
 * 隐私规范：仅在用户授权后上报（[CrashLogStorage.enabled]），未授权时直接跳过。
 *
 * 状态机：
 * - [uploadState] 暴露 Idle / Uploading / Success / Failed(error)
 * - in-flight 互斥：App 启动 / 授权同意 / 设置页开关并发触发只执行一次真正上传
 * - 上传前扫描目录为无记录文件补 PENDING 记录（含全文快照）；成功删文件，失败保留文件
 */
@Singleton
class CrashLogUploader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val crashLogApi: CrashLogApiService,
    private val crashLogStorage: CrashLogStorage,
    private val recordStore: CrashLogRecordStore,
) {

    companion object {
        private const val CRASH_DIR = "crash_logs"
    }

    private val mutex = Mutex()

    private val _uploadState = MutableStateFlow<UploadState>(UploadState.Idle)
    val uploadState: StateFlow<UploadState> = _uploadState.asStateFlow()

    /** 是否存在待上传的日志文件（设置页开关打开时判断是否需要触发上传） */
    fun hasPendingLogs(): Boolean {
        val dir = File(context.filesDir, CRASH_DIR)
        return dir.exists() && (dir.listFiles()?.any { it.name.endsWith(".log") } ?: false)
    }

    /**
     * 上传待上传日志。返回是否全部成功。
     * 未授权或无文件视为成功；上传中重复调用返回 false（不打断在途任务）。
     */
    suspend fun uploadPendingLogs(): Boolean {
        // 隐私授权：用户未同意上报时直接跳过（不建记录不打扰）
        if (!crashLogStorage.enabled.first()) return true
        return mutex.withLock {
            if (_uploadState.value is UploadState.Uploading) {
                false
            } else {
                _uploadState.value = UploadState.Uploading
                val (allOk, lastError) = doUpload()
                _uploadState.value = if (allOk) {
                    UploadState.Success
                } else {
                    UploadState.Failed(lastError)
                }
                allOk
            }
        }
    }

    /** 逐个上传日志文件；返回是否全部成功与最后一个失败原因 */
    private suspend fun doUpload(): Pair<Boolean, String> {
        val dir = File(context.filesDir, CRASH_DIR)
        if (!dir.exists()) return true to ""
        val files = dir.listFiles()
            ?.filter { it.name.endsWith(".log") }
            ?.sortedBy { it.name }
        if (files.isNullOrEmpty()) return true to ""

        // 为尚无记录的文件补 PENDING 记录（含全文快照），已有记录（含 UPLOADING 残留）不重复建
        recordStore.loadFromDisk()
        val knownIds = recordStore.records.value.map { it.id }.toSet()
        for (file in files) {
            if (file.name in knownIds) continue
            val content = runCatching { file.readText() }.getOrNull() ?: continue
            recordStore.upsert(buildPendingRecord(file.name, content))
        }

        var allOk = true
        var lastError = ""
        withContext(Dispatchers.IO) {
            for (file in files) {
                val record = recordStore.getRecord(file.name) ?: continue
                try {
                    recordStore.markUploading(file.name)
                    val ok = uploadLog(record.logContent)
                    if (ok) {
                        recordStore.markSuccess(file.name, System.currentTimeMillis())
                        file.delete()
                    } else {
                        recordStore.markFailed(file.name, "upload failed")
                        allOk = false
                        lastError = "upload failed"
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val msg = e.message ?: "unknown error"
                    recordStore.markFailed(file.name, msg)
                    allOk = false
                    lastError = msg
                }
            }
        }
        return allOk to lastError
    }

    private fun buildPendingRecord(fileName: String, content: String): CrashLogRecord {
        return CrashLogRecord(
            id = fileName,
            crashTime = parseCrashTime(fileName),
            status = CrashLogRecord.Status.PENDING,
            logContent = content,
            appVersion = content.lines()
                .find { it.startsWith("App Version:") }?.removePrefix("App Version:")?.trim() ?: "",
            device = content.lines()
                .find { it.startsWith("Device:") }?.removePrefix("Device:")?.trim() ?: "",
        )
    }

    /** 从文件名 crash_yyyy-MM-dd_HH-mm-ss.log 解析崩溃时间戳 */
    private fun parseCrashTime(fileName: String): Long {
        return try {
            val ts = fileName.removePrefix("crash_").removeSuffix(".log")
            SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).parse(ts)?.time
                ?: System.currentTimeMillis()
        } catch (_: Exception) {
            System.currentTimeMillis()
        }
    }

    private suspend fun uploadLog(logContent: String): Boolean {
        val appVersion = runCatching {
            val pi = context.packageManager.getPackageInfo(context.packageName, 0)
            "${pi.versionName} (${if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode})"
        }.getOrNull() ?: "unknown"

        // 解析本地日志的字段
        val lines = logContent.lines()
        val timeLine = lines.find { it.startsWith("Time:") }?.removePrefix("Time:")?.trim() ?: ""
        val pageLine = lines.find { it.startsWith("Current Page:") }?.removePrefix("Current Page:")?.trim() ?: ""
        val actionsLine = logContent.let { full ->
            val idx = full.indexOf("=== Recent User Actions ===")
            if (idx >= 0) {
                val endIdx = full.indexOf("\n\n", idx)
                if (endIdx >= 0) full.substring(idx, endIdx) else full.substring(idx)
            } else ""
        }

        // 提取栈信息
        val stackStart = logContent.indexOf("=== Stack Trace ===")
        val stackTrace = if (stackStart >= 0) {
            val afterStack = logContent.substring(stackStart)
            afterStack.substringBefore("=== Crash Context ===")
                .substringBefore("=== Caused by:")
                .trim()
        } else {
            logContent
        }

        val jsonBody = buildJsonString(
            "timestamp" to timeLine,
            "appVersion" to appVersion,
            "androidVersion" to "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            "device" to "${Build.MANUFACTURER} ${Build.MODEL}",
            "currentPage" to pageLine,
            "recentActions" to actionsLine,
            "stackTrace" to stackTrace
        )

        val requestBody = jsonBody.toRequestBody("application/json".toMediaType())
        return try {
            val response = crashLogApi.upload(requestBody)
            try { response.close() } catch (_: Exception) {}
            true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    private fun buildJsonString(vararg pairs: Pair<String, String>): String {
        return pairs.joinToString(
            separator = ",",
            prefix = "{",
            postfix = "}"
        ) { (key, value) ->
            "\"${escapeJson(key)}\":\"${escapeJson(value)}\""
        }
    }

    private fun escapeJson(s: String): String {
        return s.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }
}
```

注意：删除原 `uploadResult: Deferred<Boolean>` 与 `completeUploadResult`（TraktSearchApp 只调用 `uploadPendingLogs()`，签名不变，无需修改）。

- [ ] **步骤 3：重写 CrashLogUploaderTest**

```kotlin
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
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
        every { storage.enabled } returns MutableStateFlow(enabled)
        val recordStore = mockk<CrashLogRecordStore>(relaxed = true)
        every { recordStore.records } returns MutableStateFlow(emptyList())
        coEvery { recordStore.awaitLoaded() } returns Unit
        coEvery { recordStore.getRecord(any()) } returns null
        coEvery { recordStore.upsert(any()) } returns Unit
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
        every { storage.enabled } returns MutableStateFlow(true)
        val recordStore = mockk<CrashLogRecordStore>(relaxed = true)
        every { recordStore.records } returns MutableStateFlow(emptyList())
        var captured: CrashLogRecord? = null
        coEvery { recordStore.upsert(any()) } answers {
            captured = firstArg<CrashLogRecord>()
        }
        coEvery { recordStore.getRecord(any()) } answers { captured }
        val api = mockk<CrashLogApiService>()
        coEvery { api.upload(any()) } throws RuntimeException("network")
        val uploader = CrashLogUploader(context, api, storage, recordStore)
        writeLogFile("crash_1.log")

        uploader.uploadPendingLogs()

        coEvery { recordStore.markFailed(any(), any()) } returns Unit
        // doUpload 内对 record 调用 markFailed；此处通过记录快照断言失败状态
        assertThat(captured?.status).isEqualTo(CrashLogRecord.Status.FAILED)
        assertThat(captured?.error).isEqualTo("network")
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
        coEvery { api.upload(any()) } answers {
            callCount++
            kotlinx.coroutines.delay(100)
            "{}".toResponseBody("application/json".toMediaTypeOrNull())
        }

        // 并发触发两次，互斥锁保证只有一次真正上传
        val results = listOf(
            kotlinx.coroutines.async { uploader.uploadPendingLogs() },
            kotlinx.coroutines.async { uploader.uploadPendingLogs() }
        )
        val first = results[0].await()
        val second = results[1].await()

        assertThat(listOf(first, second)).contains(true)
        assertThat(callCount).isEqualTo(1)
        assertThat(uploader.uploadState.first()).isEqualTo(UploadState.Success)
    }

    @Test
    fun mixedFiles_部分成功部分失败_失败文件保留() = runTest {
        val (uploader, api) = setup(enabled = true)
        writeLogFile("crash_a.log")
        writeLogFile("crash_b.log")
        coEvery { api.upload(any()) } answers {
            if (firstArg<okhttp3.RequestBody>().contentLength() < 0L) error("unreachable")
            // 按文件名区分：第一个请求抛异常，后续成功
            if (failNext) throw RuntimeException("network")
            "{}".toResponseBody("application/json".toMediaTypeOrNull())
        }
        var failNext = true

        val ok = uploader.uploadPendingLogs()

        assertThat(ok).isFalse()
        assertThat(uploader.uploadState.first()).isInstanceOf(UploadState.Failed::class.java)
        // 失败文件保留（sortedBy 顺序首个文件失败）
        val remaining = crashDir.listFiles()?.filter { it.name.endsWith(".log") } ?: emptyList()
        assertThat(remaining).hasSize(1)
    }
}
```

注意：`mixedFiles` 测试中 `var failNext` 需要在 `answers` 闭包外声明才能变更，请按此顺序写（先声明 `var failNext = true` 再 `coEvery`）。若 MockK 与 `async` 在 `runTest` 内存在调度问题，`concurrentCalls` 可改用两个 `launch` + `joinAll`。

- [ ] **步骤 4：运行测试验证通过**

运行：`./gradlew :app:testDebugUnitTest --tests "com.tracktosearch.data.util.CrashLogUploaderTest" --offline`
预期：7 个测试全部 PASS。若有 MockK 严格 mock 误报，检查 `relaxed = true` 是否生效于 `CrashLogRecordStore`。

- [ ] **步骤 5：Commit**

```bash
git add app/src/main/java/com/tracktosearch/data/util/UploadState.kt app/src/main/java/com/tracktosearch/data/util/CrashLogUploader.kt app/src/test/java/com/tracktosearch/data/util/CrashLogUploaderTest.kt
git commit -m "feat(崩溃上报): 上传改造为状态机并接入记录存储"
```

---

### 任务 5：崩溃上报启动决策纯函数

**文件：**
- 创建：`app/src/main/java/com/tracktosearch/data/util/CrashPromptDecision.kt`
- 测试：`app/src/test/java/com/tracktosearch/data/util/CrashPromptDecisionTest.kt`

- [ ] **步骤 1：编写失败的测试**

```kotlin
package com.tracktosearch.data.util

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CrashPromptDecisionTest {

    @Test
    fun 无崩溃_无动作() {
        assertThat(CrashPromptDecision.decide(crashCount = 0, enabled = false, prompted = false))
            .isEqualTo(CrashPromptDecision.Action.None)
    }

    @Test
    fun 未授权未弹窗_弹授权() {
        assertThat(CrashPromptDecision.decide(crashCount = 1, enabled = false, prompted = false))
            .isEqualTo(CrashPromptDecision.Action.Authorize)
    }

    @Test
    fun 拒绝过_清日志() {
        assertThat(CrashPromptDecision.decide(crashCount = 1, enabled = false, prompted = true))
            .isEqualTo(CrashPromptDecision.Action.ClearLogs)
    }

    @Test
    fun 已授权_自动上传() {
        assertThat(CrashPromptDecision.decide(crashCount = 1, enabled = true, prompted = false))
            .isEqualTo(CrashPromptDecision.Action.AutoUpload)
    }

    @Test
    fun 已授权且弹过窗_自动上传() {
        assertThat(CrashPromptDecision.decide(crashCount = 2, enabled = true, prompted = true))
            .isEqualTo(CrashPromptDecision.Action.AutoUpload)
    }
}
```

- [ ] **步骤 2：运行测试验证失败**

运行：`./gradlew :app:testDebugUnitTest --tests "com.tracktosearch.data.util.CrashPromptDecisionTest" --offline`
预期：编译失败（类不存在）。

- [ ] **步骤 3：创建 CrashPromptDecision**

```kotlin
package com.tracktosearch.data.util

/**
 * 崩溃上报启动决策（纯函数，便于单元测试）。
 * MainActivity 的崩溃上报对话框据此决定展示内容。
 */
object CrashPromptDecision {

    sealed interface Action {
        /** 弹授权询问对话框（用户同意后开启开关并上传） */
        data object Authorize : Action
        /** 已拒绝过上报：清理本地日志，不打扰用户 */
        data object ClearLogs : Action
        /** 已授权：启动自动上传已由 TraktSearchApp 触发，失败时弹重试 */
        data object AutoUpload : Action
        /** 无崩溃：无动作 */
        data object None : Action
    }

    fun decide(crashCount: Int, enabled: Boolean, prompted: Boolean): Action = when {
        crashCount < 1 -> Action.None
        !enabled && !prompted -> Action.Authorize
        !enabled -> Action.ClearLogs
        else -> Action.AutoUpload
    }
}
```

- [ ] **步骤 4：运行测试验证通过**

运行：`./gradlew :app:testDebugUnitTest --tests "com.tracktosearch.data.util.CrashPromptDecisionTest" --offline`
预期：5 个测试全部 PASS。

- [ ] **步骤 5：Commit**

```bash
git add app/src/main/java/com/tracktosearch/data/util/CrashPromptDecision.kt app/src/test/java/com/tracktosearch/data/util/CrashPromptDecisionTest.kt
git commit -m "feat(崩溃上报): 新增启动决策纯函数区分授权/清日志/自动上传"
```

---

### 任务 6：strings 资源（4 语言）

**文件：** 修改 `app/src/main/res/values/strings.xml`、`app/src/main/res/values-zh/strings.xml`、`app/src/main/res/values-ja/strings.xml`、`app/src/main/res/values-ko/strings.xml`

- [ ] **步骤 1：英语（values/strings.xml）**

在 crash 相关字符串区域（`crash_auth_dialog_*` 附近）新增：

```xml
    <string name="crash_upload_success">Crash log uploaded successfully</string>
    <string name="crash_upload_failed">Crash log upload failed, you can retry</string>
    <string name="crash_uploading">Uploading crash logs…</string>
    <string name="crash_upload_retry">Retry</string>
    <string name="crash_upload_keep">Keep and try later</string>
    <string name="crash_record_section_title">Crash Logs</string>
    <string name="crash_record_status_pending">Pending upload</string>
    <string name="crash_record_status_uploading">Uploading</string>
    <string name="crash_record_status_success">Uploaded</string>
    <string name="crash_record_status_failed">Upload failed</string>
    <string name="crash_detail_title">Crash Log Detail</string>
    <string name="crash_detail_status">Status</string>
    <string name="crash_detail_upload_time">Upload time</string>
    <string name="crash_detail_fail_reason">Fail reason</string>
    <string name="crash_detail_crash_time">Crash time</string>
    <string name="crash_detail_app_version">App version</string>
    <string name="crash_detail_device">Device</string>
    <string name="crash_detail_log_content">Log content</string>
    <string name="crash_detail_upload_now">Upload now</string>
    <string name="crash_detail_not_found">Record not found</string>
    <string name="crash_upload_fail_hint">Upload failed. Retry or keep the log for the next launch.</string>
```

删除（邮件兜底相关，不再使用）：
`crash_dialog_title`、`crash_dialog_message`、`crash_dialog_send`、`crash_email_subject`、`crash_email_body`、`crash_email_no_log`、`crash_toast_copied`。
保留 `crash_dialog_cancel`（重试对话框「取消」复用）。

设置页开关文案改为：

```xml
    <string name="settings_crash_log_title">Auto-upload crash logs</string>
    <string name="settings_crash_log_subtitle">Crash logs will be uploaded automatically on next launch</string>
```

- [ ] **步骤 2：中文（values-zh/strings.xml）对应新增/删除/改名**

```xml
    <string name="crash_upload_success">崩溃日志上报成功</string>
    <string name="crash_upload_failed">崩溃日志上报失败，可重试</string>
    <string name="crash_uploading">正在上传崩溃日志…</string>
    <string name="crash_upload_retry">重试</string>
    <string name="crash_upload_keep">保留，下次再试</string>
    <string name="crash_record_section_title">崩溃日志</string>
    <string name="crash_record_status_pending">待上传</string>
    <string name="crash_record_status_uploading">上传中</string>
    <string name="crash_record_status_success">已上报</string>
    <string name="crash_record_status_failed">上传失败</string>
    <string name="crash_detail_title">崩溃日志详情</string>
    <string name="crash_detail_status">状态</string>
    <string name="crash_detail_upload_time">上传时间</string>
    <string name="crash_detail_fail_reason">失败原因</string>
    <string name="crash_detail_crash_time">崩溃时间</string>
    <string name="crash_detail_app_version">应用版本</string>
    <string name="crash_detail_device">设备型号</string>
    <string name="crash_detail_log_content">日志内容</string>
    <string name="crash_detail_upload_now">立即上传</string>
    <string name="crash_detail_not_found">记录不存在</string>
    <string name="crash_upload_fail_hint">上报失败，可重试或保留日志待下次启动再试</string>
    <string name="settings_crash_log_title">自动上报崩溃日志</string>
    <string name="settings_crash_log_subtitle">开启后崩溃日志将在下次启动时自动上传</string>
```

同样删除邮件兜底相关中文键，保留 `crash_dialog_cancel` 中文值（「取消」或「暂时不要」——保留原值）。

- [ ] **步骤 3：日语（values-ja/strings.xml）与韩语（values-ko/strings.xml）对应翻译**

照抄英文键名，翻译为日/韩（参考现有 crash_auth_dialog_* 的既有翻译风格；若该语言文件缺失某些键则按文件现有结构补齐）。删除邮件兜底相关键。

- [ ] **步骤 4：同步无改动键检查**

运行：`cd F:/trae-project-worktrees/crash-upload && grep -rn "crash_dialog_title\|crash_email_subject" app/src/main/res/ | grep -v "crash_dialog_cancel"`
预期：无输出（所有引用已清理）。若其他代码文件引用这些键（如 MainActivity 旧逻辑），任务 7 会一并删除；检查 `app/src/main/java` 内引用后确认。

- [ ] **步骤 5：Commit**

```bash
git add app/src/main/res/
git commit -m "feat(崩溃上报): 新增上报反馈与记录展示文案并删除邮件兜底文案"
```

---

### 任务 7：MainActivity 重构（Compose 状态驱动对话框）

**文件：**
- 修改：`app/src/main/java/com/tracktosearch/MainActivity.kt`
- 创建：`app/src/main/java/com/tracktosearch/ui/component/CrashReportDialogHost.kt`

- [ ] **步骤 1：创建 CrashReportDialogHost**

```kotlin
package com.tracktosearch.ui.component

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tracktosearch.CrashHandler
import com.tracktosearch.R
import com.tracktosearch.data.local.CrashLogStorage
import com.tracktosearch.data.util.CrashPromptDecision
import com.tracktosearch.data.util.CrashLogUploader
import com.tracktosearch.data.util.UploadState
import com.tracktosearch.ui.util.showToast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 崩溃上报对话框主机（Compose 顶层状态驱动）。
 *
 * 状态组合：
 * - 启动读崩溃计数 + 授权状态 → 弹授权询问 / 清日志 / 等待自动上传
 * - 观察 [CrashLogUploader.uploadState]：Uploading 显示转圈；Success 弹成功 toast；
 *   Failed 弹失败 toast + 重试对话框（不关闭，可取消保留日志）
 * - toast 触发规则（避免误报）：状态转移（Uploading → 终态）或首次挂载即终态且本次会话有崩溃
 */
@Composable
fun CrashReportDialogHost(
    crashLogStorage: CrashLogStorage,
    crashLogUploader: CrashLogUploader,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var crashCount by remember { mutableStateOf(0) }
    var authLoaded by remember { mutableStateOf(false) }
    var enabled by remember { mutableStateOf(false) }
    var dialogVisible by remember { mutableStateOf(false) }
    var dialogKind by remember { mutableStateOf<DialogKind>(DialogKind.Authorize) }
    var lastUploadState by remember { mutableStateOf<UploadState?>(null) }
    val uploadState by crashLogUploader.uploadState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    // 启动决策：读崩溃计数与授权状态（一次性）
    LaunchedEffect(Unit) {
        crashCount = withContext(Dispatchers.IO) { CrashHandler.getAndResetCrashCount(context) }
        val prompted = crashLogStorage.prompted.first()
        enabled = crashLogStorage.enabled.first()
        when (CrashPromptDecision.decide(crashCount, enabled, prompted)) {
            CrashPromptDecision.Action.None -> dialogVisible = false
            CrashPromptDecision.Action.Authorize -> {
                dialogVisible = true
                dialogKind = DialogKind.Authorize
            }
            CrashPromptDecision.Action.ClearLogs ->
                withContext(Dispatchers.IO) { CrashHandler.clearCrashLogs(context) }
            CrashPromptDecision.Action.AutoUpload -> {
                // 上传已由 TraktSearchApp 启动触发，由下方状态监听驱动
            }
        }
        authLoaded = true
    }

    // 上传状态监听：处理 toast 与失败重试对话框
    LaunchedEffect(uploadState, authLoaded) {
        if (!authLoaded) return@LaunchedEffect
        val prev = lastUploadState
        lastUploadState = uploadState
        val fromUploading = prev is UploadState.Uploading
        when (uploadState) {
            UploadState.Uploading -> {
                dialogVisible = true
                dialogKind = DialogKind.Uploading
            }
            UploadState.Success -> {
                // 手动触发（从 Uploading 转移）或启动自动上传完成（prev==null 且有崩溃）
                if (fromUploading || (prev == null && crashCount > 0)) {
                    context.showToast(context.getString(R.string.crash_upload_success))
                }
                dialogVisible = false
            }
            is UploadState.Failed -> {
                if (fromUploading || (prev == null && crashCount > 0)) {
                    context.showToast(context.getString(R.string.crash_upload_failed))
                }
                dialogVisible = true
                dialogKind = DialogKind.Retry(uploadState.error)
            }
            UploadState.Idle -> Unit
        }
    }

    if (!dialogVisible) return

    val dismissDialog = { dialogVisible = false }
    when (val kind = dialogKind) {
        is DialogKind.Authorize -> AlertDialog(
            onDismissRequest = { /* 不可取消 */ },
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            title = { Text(stringResource(R.string.crash_auth_dialog_title)) },
            text = { Text(stringResource(R.string.crash_auth_dialog_message)) },
            confirmButton = {
                TextButton(onClick = {
                    dialogVisible = false
                    scope.launch {
                        crashLogStorage.setEnabled(true)
                        enabled = true
                        crashLogUploader.uploadPendingLogs()
                    }
                }) { Text(stringResource(R.string.crash_auth_dialog_agree)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    dialogVisible = false
                    scope.launch {
                        crashLogStorage.setPrompted(true)
                        withContext(Dispatchers.IO) { CrashHandler.clearCrashLogs(context) }
                    }
                }) { Text(stringResource(R.string.crash_auth_dialog_decline)) }
            },
        )
        DialogKind.Uploading -> AlertDialog(
            onDismissRequest = { /* 上传中不可取消 */ },
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            title = { Text(stringResource(R.string.crash_auth_dialog_title)) },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp))
                    Spacer(Modifier.width(16.dp))
                    Text(stringResource(R.string.crash_uploading))
                }
            },
            confirmButton = {},
        )
        is DialogKind.Retry -> AlertDialog(
            onDismissRequest = { /* 需用户明确取消 */ },
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            icon = { Icon(Icons.Rounded.WarningAmber, contentDescription = null) },
            title = { Text(stringResource(R.string.crash_upload_failed)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.crash_upload_fail_hint))
                    if (kind.error.isNotBlank()) {
                        Text(
                            kind.error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { crashLogUploader.uploadPendingLogs() }
                }) { Text(stringResource(R.string.crash_upload_retry)) }
            },
            dismissButton = {
                TextButton(onClick = dismissDialog) { Text(stringResource(R.string.crash_dialog_cancel)) }
            },
        )
    }
}

private sealed interface DialogKind {
    data object Authorize : DialogKind
    data object Uploading : DialogKind
    data class Retry(val error: String) : DialogKind
}
```

注意：`Arrangement.spacedBy` 需要 `import androidx.compose.foundation.layout.*`；`Icons.Rounded.WarningAmber` 需要 material-icons（项目已有 `Icons.Rounded.Add` 等用法，确认 `Icons.Rounded.WarningAmber` 在依赖范围内——若缺失改用 `Icons.Rounded.Error`）。`Spacer`/`Row`/`Column`/`width`/`size` 来自 foundation.layout。

- [ ] **步骤 2：改造 MainActivity**

删除：`checkCrashAndPrompt()`、`sendCrashEmail()` 两个私有方法，以及不再使用的 import（`android.app.AlertDialog`、`android.content.DialogInterface`、`android.net.Uri`、`android.widget.Toast`、`com.tracktosearch.ui.util.showToast`——确认 `showToast` 无其他引用后删除）。

`setContent` 内 `TraktoSearchTheme` 包裹的 `CompositionLocalProvider` 块中，`if (isReady) { ... }` 之后追加：

```kotlin
                // 崩溃上报对话框（授权/上传中/失败重试，状态驱动）
                CrashReportDialogHost(
                    crashLogStorage = crashLogStorage,
                    crashLogUploader = crashLogUploader,
                )
```

import 追加：`com.tracktosearch.ui.component.CrashReportDialogHost`。

`onCreate` 中删除：

```kotlin
        // 在后台线程检查崩溃日志并上传
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            checkCrashAndPrompt()
        }
```

- [ ] **步骤 3：删除旧测试引用检查**

运行：`grep -rn "checkCrashAndPrompt\|sendCrashEmail\|crashLogUploader.uploadResult" app/src/main/java app/src/test/java`
预期：无匹配（除本文件已删除）。

- [ ] **步骤 4：编译验证**

运行：`./gradlew :app:compileDebugKotlin --offline`
预期：BUILD SUCCESSFUL。

- [ ] **步骤 5：Commit**

```bash
git add app/src/main/java/com/tracktosearch/MainActivity.kt app/src/main/java/com/tracktosearch/ui/component/CrashReportDialogHost.kt
git commit -m "refactor(崩溃上报): 启动提示改为 Compose 状态驱动对话框并移除邮件兜底"
```

---

### 任务 8：设置页开关（改名 + 触发上传）

**文件：**
- 修改：`app/src/main/java/com/tracktosearch/ui/screen/settings/SettingsViewModel.kt`（注入 uploader，`setCrashLogEnabled` 改造）
- 修改：`app/src/main/java/com/tracktosearch/ui/screen/settings/SettingsScreen.kt`（无需改动，标题/副标题走 string 资源自动生效）

- [ ] **步骤 1：SettingsViewModel 注入 uploader 并改造 setCrashLogEnabled**

构造器参数追加（第 92 行 `crashLogStorage` 之后）：

```kotlin
    private val crashLogUploader: com.tracktosearch.data.util.CrashLogUploader,
```

`setCrashLogEnabled` 改为：

```kotlin
    fun setCrashLogEnabled(enabled: Boolean) {
        viewModelScope.launch {
            crashLogStorage.setEnabled(enabled)
            // 开启且有待上传日志：立即触发上传，完成后经主界面对话框/此处消息反馈
            if (enabled && crashLogUploader.hasPendingLogs()) {
                val ok = crashLogUploader.uploadPendingLogs()
                if (ok) {
                    _exportImportState.value = _exportImportState.value.copy(
                        message = context.getString(R.string.crash_upload_success)
                    )
                }
            }
        }
    }
```

注意：`context` 已在 SettingsViewModel 构造器注入（现有代码大量使用 `context.getString`）。上传失败时主界面 `CrashReportDialogHost` 会统一弹重试对话框，设置页不重复提示。

- [ ] **步骤 2：编译验证**

运行：`./gradlew :app:compileDebugKotlin --offline`
预期：BUILD SUCCESSFUL（Hilt 自动满足新依赖）。

- [ ] **步骤 3：Commit**

```bash
git add app/src/main/java/com/tracktosearch/ui/screen/settings/SettingsViewModel.kt
git commit -m "feat(崩溃上报): 设置页开关开启后立即上传待传日志"
```

---

### 任务 9：反馈页「崩溃日志」区块

**文件：**
- 修改：`app/src/main/java/com/tracktosearch/ui/screen/feedback/FeedbackViewModel.kt`（注入 RecordStore，暴露记录）
- 修改：`app/src/main/java/com/tracktosearch/ui/screen/feedback/FeedbackScreen.kt`（区块 UI + 新参数）
- 修改：`app/src/main/java/com/tracktosearch/ui/navigation/AppNavigation.kt`（传参）

- [ ] **步骤 1：FeedbackViewModel 注入 RecordStore**

构造器追加：

```kotlin
    private val crashLogRecordStore: com.tracktosearch.data.local.CrashLogRecordStore,
```

暴露记录（`_unreadCount` 定义附近）：

```kotlin
    /** 崩溃日志上传记录（本地，倒序由 Store 保证） */
    val crashLogRecords: StateFlow<List<com.tracktosearch.data.local.CrashLogRecord>> =
        crashLogRecordStore.records
```

- [ ] **步骤 2：FeedbackScreen 增加区块**

函数签名追加参数：

```kotlin
    onCrashLogClick: (String) -> Unit,
```

「写新反馈」item 之后、「我的反馈」标题 item 之前插入：

```kotlin
            // 崩溃日志上传记录区块（本地数据，无记录隐藏）
            val crashLogRecords by viewModel.crashLogRecords.collectAsStateWithLifecycle()
            if (crashLogRecords.isNotEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.crash_record_section_title),
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = 4.dp, top = 8.dp)
                    )
                }
                items(crashLogRecords, key = { it.id }) { record ->
                    CrashLogRecordCard(record, onClick = { onCrashLogClick(record.id) })
                }
            }
```

文件末尾（`formatRelativeTime` 之前或之后）新增卡片组件：

```kotlin
@Composable
private fun CrashLogRecordCard(
    record: com.tracktosearch.data.local.CrashLogRecord,
    onClick: () -> Unit
) {
    val (statusRes, statusColor) = when (record.status) {
        com.tracktosearch.data.local.CrashLogRecord.Status.PENDING ->
            R.string.crash_record_status_pending to MaterialTheme.colorScheme.onSurfaceVariant
        com.tracktosearch.data.local.CrashLogRecord.Status.UPLOADING ->
            R.string.crash_record_status_uploading to MaterialTheme.colorScheme.primary
        com.tracktosearch.data.local.CrashLogRecord.Status.SUCCESS ->
            R.string.crash_record_status_success to Color(0xFF10B981)
        com.tracktosearch.data.local.CrashLogRecord.Status.FAILED ->
            R.string.crash_record_status_failed to MaterialTheme.colorScheme.error
    }
    val statusIcon = when (record.status) {
        com.tracktosearch.data.local.CrashLogRecord.Status.SUCCESS -> Icons.Rounded.CheckCircle
        com.tracktosearch.data.local.CrashLogRecord.Status.FAILED -> Icons.Rounded.Error
        com.tracktosearch.data.local.CrashLogRecord.Status.UPLOADING -> Icons.Rounded.HourglassTop
        com.tracktosearch.data.local.CrashLogRecord.Status.PENDING -> Icons.Rounded.Schedule
    }
    val justNow = stringResource(R.string.feedback_time_just_now)
    val minutesAgo = stringResource(R.string.feedback_time_minutes_ago)
    val hoursAgo = stringResource(R.string.feedback_time_hours_ago)
    val daysAgo = stringResource(R.string.feedback_time_days_ago)
    val monthsAgo = stringResource(R.string.feedback_time_months_ago)

    Card(
        modifier = Modifier.fillMaxWidth().clickable { onClick() },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(statusIcon, contentDescription = null, tint = statusColor, modifier = Modifier.size(20.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(statusRes),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = formatRelativeTime(record.crashTime / 1000, justNow, minutesAgo, hoursAgo, daysAgo, monthsAgo),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (record.status == com.tracktosearch.data.local.CrashLogRecord.Status.FAILED && record.error.isNotBlank()) {
                Text(
                    text = record.error,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(0.6f)
                )
            }
        }
    }
}
```

注意：`Icons.Rounded.CheckCircle`、`Icons.Rounded.Error`、`Icons.Rounded.HourglassTop`、`Icons.Rounded.Schedule` 需确认 material-icons 依赖可用（项目已用 `Icons.Rounded.BugReport`、`Icons.Rounded.Lightbulb` 等）。`TextOverflow` 需 `import androidx.compose.ui.text.style.TextOverflow`。`formatRelativeTime` 是文件内已有私有函数，直接复用。

- [ ] **步骤 3：AppNavigation 传参**

`AppNavigation.kt` 中 `FeedbackScreen(` 调用处（约 1073 行）追加：

```kotlin
                            onCrashLogClick = { id ->
                                navController.navigate(Routes.crashLogDetailRoute(id))
                            },
```

- [ ] **步骤 4：编译验证 + 相关测试**

运行：`./gradlew :app:compileDebugKotlin :app:testDebugUnitTest --tests "com.tracktosearch.ui.screen.feedback.*" --offline`
预期：BUILD SUCCESSFUL，既有 FeedbackViewModel 测试不回归。

- [ ] **步骤 5：Commit**

```bash
git add app/src/main/java/com/tracktosearch/ui/screen/feedback/FeedbackViewModel.kt app/src/main/java/com/tracktosearch/ui/screen/feedback/FeedbackScreen.kt app/src/main/java/com/tracktosearch/ui/navigation/AppNavigation.kt
git commit -m "feat(崩溃上报): 反馈页新增崩溃日志上传记录区块"
```

---

### 任务 10：崩溃日志详情页 + 路由

**文件：**
- 创建：`app/src/main/java/com/tracktosearch/ui/screen/crashlog/CrashLogDetailViewModel.kt`
- 创建：`app/src/main/java/com/tracktosearch/ui/screen/crashlog/CrashLogDetailScreen.kt`
- 修改：`app/src/main/java/com/tracktosearch/ui/navigation/AppNavigation.kt`

- [ ] **步骤 1：创建 CrashLogDetailViewModel**

```kotlin
package com.tracktosearch.ui.screen.crashlog

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tracktosearch.data.local.CrashLogRecord
import com.tracktosearch.data.local.CrashLogRecordStore
import com.tracktosearch.data.util.CrashLogUploader
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 崩溃日志记录详情 ViewModel：按 id 从记录列表映射，上传后自动刷新 */
@HiltViewModel
class CrashLogDetailViewModel @Inject constructor(
    private val recordStore: CrashLogRecordStore,
    private val crashLogUploader: CrashLogUploader,
) : ViewModel() {

    fun record(id: String): StateFlow<CrashLogRecord?> =
        recordStore.records
            .map { list -> list.find { it.id == id } }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** 立即上传待传日志（成功后记录自动刷新为 SUCCESS） */
    fun uploadNow() {
        viewModelScope.launch { crashLogUploader.uploadPendingLogs() }
    }
}
```

- [ ] **步骤 2：创建 CrashLogDetailScreen**

```kotlin
package com.tracktosearch.ui.screen.crashlog

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tracktosearch.R
import com.tracktosearch.data.local.CrashLogRecord
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CrashLogDetailScreen(
    recordId: String,
    onBack: () -> Unit,
    viewModel: CrashLogDetailViewModel = hiltViewModel(),
) {
    val record by viewModel.record(recordId).collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.crash_detail_title), fontWeight = FontWeight.ExtraBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { padding ->
        val current = record
        when {
            current == null -> {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = androidx.compose.ui.Alignment.Center) {
                    Text(stringResource(R.string.crash_detail_not_found), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item { InfoCard(current) }
                item {
                    Text(
                        text = stringResource(R.string.crash_detail_log_content),
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                item {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Text(
                            text = current.logContent,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                            modifier = Modifier.fillMaxWidth().padding(16.dp)
                        )
                    }
                }
                // 待上传/失败：提供立即上传入口
                if (current.status == CrashLogRecord.Status.PENDING ||
                    current.status == CrashLogRecord.Status.FAILED
                ) {
                    item {
                        Button(
                            onClick = { viewModel.uploadNow() },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(stringResource(R.string.crash_detail_upload_now)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoCard(record: CrashLogRecord) {
    val statusLabel = when (record.status) {
        CrashLogRecord.Status.PENDING -> R.string.crash_record_status_pending
        CrashLogRecord.Status.UPLOADING -> R.string.crash_record_status_uploading
        CrashLogRecord.Status.SUCCESS -> R.string.crash_record_status_success
        CrashLogRecord.Status.FAILED -> R.string.crash_record_status_failed
    }
    val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    fun fmt(ts: Long): String = if (ts <= 0) "—" else timeFormat.format(Date(ts))

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            InfoRow(stringResource(R.string.crash_detail_status), stringResource(statusLabel))
            InfoRow(stringResource(R.string.crash_detail_upload_time), fmt(record.uploadTime))
            if (record.error.isNotBlank()) {
                InfoRow(stringResource(R.string.crash_detail_fail_reason), record.error)
            }
            InfoRow(stringResource(R.string.crash_detail_crash_time), fmt(record.crashTime))
            if (record.appVersion.isNotBlank()) {
                InfoRow(stringResource(R.string.crash_detail_app_version), record.appVersion)
            }
            if (record.device.isNotBlank()) {
                InfoRow(stringResource(R.string.crash_detail_device), record.device)
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(
            text = label,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(88.dp)
        )
        Text(
            text = value,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Medium
        )
    }
}
```

注意：`stringResource` 需 `import androidx.compose.ui.res.stringResource`；`Button` 来自 material3（已 import）。

- [ ] **步骤 3：AppNavigation 注册路由**

`Routes` 对象（约 133 行 `NEW_FEEDBACK` 附近）追加：

```kotlin
    const val CRASH_LOG_DETAIL = "crashLogDetail/{recordId}"

    fun crashLogDetailRoute(recordId: String): String = "crashLogDetail/$recordId"
```

import 追加：`com.tracktosearch.ui.screen.crashlog.CrashLogDetailScreen`。

`NEW_FEEDBACK` composable 注册（约 1089 行）之后追加：

```kotlin
                composable(
                    route = Routes.CRASH_LOG_DETAIL,
                    arguments = listOf(
                        navArgument("recordId") { type = NavType.StringType }
                    )
                ) { backStackEntry ->
                    val recordId = backStackEntry.arguments?.getString("recordId") ?: return@composable
                    CrashLogDetailScreen(
                        recordId = recordId,
                        onBack = { navController.popBackStack() }
                    )
                }
```

- [ ] **步骤 4：编译验证**

运行：`./gradlew :app:compileDebugKotlin --offline`
预期：BUILD SUCCESSFUL。

- [ ] **步骤 5：Commit**

```bash
git add app/src/main/java/com/tracktosearch/ui/screen/crashlog/ app/src/main/java/com/tracktosearch/ui/navigation/AppNavigation.kt
git commit -m "feat(崩溃上报): 新增崩溃日志记录详情页与路由"
```

---

### 任务 11：帮助页更新

**文件：** 修改 `app/src/main/java/com/tracktosearch/ui/screen/help/HelpScreen.kt` 与 4 个 strings.xml 的 help_tips 区块

- [ ] **步骤 1：阅读 HelpScreen 的 tips 区块结构**

运行：`grep -n "help_tips" app/src/main/java/com/tracktosearch/ui/screen/help/HelpScreen.kt | head`
预期：找到 tips 分组对应的字符串数组或逐个 Text 的渲染位置。

- [ ] **步骤 2：在 More Tips（help_tips）分组追加一条说明**

英语（values/strings.xml，`help_tips_b10` 后）：

```xml
    <string name="help_tips_b11">Crash logs are auto-uploaded after a crash if enabled in Settings; upload history can be viewed on the Feedback page</string>
```

中文（values-zh/strings.xml）：

```xml
    <string name="help_tips_b11">开启「自动上报崩溃日志」后，崩溃日志将在下次启动时自动上传；上传记录可在反馈与建议页查看</string>
```

日语/韩语同步翻译。HelpScreen 的渲染逻辑若为逐个 item 引用 `help_tips_b*`，则新增一个 item 引用 `help_tips_b11`；若为字符串数组则向数组追加一项。

- [ ] **步骤 3：编译验证**

运行：`./gradlew :app:compileDebugKotlin --offline`
预期：BUILD SUCCESSFUL。

- [ ] **步骤 4：Commit**

```bash
git add app/src/main/java/com/tracktosearch/ui/screen/help/HelpScreen.kt app/src/main/res/
git commit -m "docs(崩溃上报): 帮助页补充崩溃日志自动上报说明"
```

---

### 任务 12：全量验证与合入主分支

**文件：** 无（验证 + git 操作）

- [ ] **步骤 1：全量单测**

运行：`./gradlew :app:testDebugUnitTest --offline`（超时设 600000ms；超时先查 `app/build/test-results` 再判断）
预期：全部 PASS，无回归。

- [ ] **步骤 2：构建 APK 验证**

运行：`./gradlew :app:assembleDebug --offline`
预期：BUILD SUCCESSFUL，产出 `app/build/outputs/apk/debug/app-debug.apk`。

- [ ] **步骤 3：手动验证清单（模拟器，可选但推荐）**

按规格文档「手动验证清单」6 项逐一验证（首次授权、拒绝、设置页开关自动上报、断网失败重试、反馈页记录详情、开关联动），使用 `adb -s <serial> shell am start` + `logcat -b crash` + screencap。若无法跑模拟器，至少确认编译与单测通过并在报告说明。

- [ ] **步骤 4：提交剩余改动并检查**

运行：`git status`、`git diff --cached --check`、`git diff --stat HEAD`
预期：工作区干净（或仅剩未提交的零星改动一并提交）。

- [ ] **步骤 5：合入主分支（征求用户确认后）**

```bash
cd F:/trae-project
git merge feat/crash-log-upload --no-ff -m "merge: 合入崩溃日志上报增强"
```

预期：无冲突合并（master 自 worktree 创建后无其他改动；若有冲突按冲突文件逐一解决）。

- [ ] **步骤 6：清理 worktree（合并后）**

```bash
git worktree remove F:/trae-project-worktrees/crash-upload
git branch -d feat/crash-log-upload
```

预期：worktree 与分支清理完成。

---

## 自检记录

- **规格覆盖度**：规格「上传状态机」→ 任务 4；「记录存储」→ 任务 2；「授权判断修复」→ 任务 3、5、7；「toast 策略」→ 任务 7（含自动上传成功提示）；「设置页开关改名+触发上传」→ 任务 6（字符串）+ 任务 8；「反馈页区块」→ 任务 9；「详情页+路由」→ 任务 10；「错误处理（并发/残留/上限）」→ 任务 4（互斥/scan 幂等）+ 任务 2（上限 20）；「帮助页」→ 任务 11；「邮件兜底删除」→ 任务 6、7。全部覆盖 ✅
- **占位符扫描**：无 TODO/待定；所有步骤含完整代码或精确命令 ✅
- **类型一致性**：`UploadState`（Idle/Uploading/Success/Failed）在任务 4 定义、任务 7 使用一致；`CrashLogRecord.Status` 全计划统一；`CrashLogRecordStore` 方法签名（upsert/getRecord/markUploading/markSuccess/markFailed/loadFromDisk/records）在任务 2 定义、任务 4/9/10 使用一致；`CrashPromptDecision.decide` 在任务 5 定义、任务 7 使用一致；路由 `crashLogDetailRoute(recordId)` 在任务 9 调用、任务 10 定义 ✅
- **已知风险**：任务 4 `mixedFiles`/`concurrentCalls` 测试中 MockK 与 runTest 调度细节可能存在平台差异，已附替代写法；任务 7 `Icons.Rounded.WarningAmber`/任务 9 新图标需确认 material-icons 图标集覆盖，已附回退方案。
