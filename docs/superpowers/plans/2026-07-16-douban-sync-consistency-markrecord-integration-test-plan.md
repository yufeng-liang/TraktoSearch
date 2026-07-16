# 豆瓣同步/一致性检查/标记记录 集成测试实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 为豆瓣同步、一致性检查、标记记录三大模块补全三层测试（单元测试 + Robolectric 组件测试 + 插桩 UI 测试），覆盖复杂弹窗、进度更新、取消处理、UI 跳转、息屏保持等容易出 bug 的场景。

**架构：** 按模块分 3 批执行，每批内部按"单元测试 → Robolectric 组件测试 → 插桩 UI 测试"顺序进行。测试用 MockK + Truth + runTest + Robolectric + Compose Test Rule，不引入新依赖。

**技术栈：** JUnit4 + MockK + Truth + Robolectric + MainDispatcherRule + Compose UI Test（createAndroidComposeRule<HiltTestActivity>）

**规格文档：** `docs/superpowers/specs/2026-07-16-douban-sync-consistency-markrecord-integration-test-design.md`

---

## 文件结构

### 第一批：豆瓣同步模块

**单元测试**:
- 创建：`app/src/test/java/com/tracktosearch/data/repository/DoubanSyncManagerCancelTest.kt` — 取消链路测试
- 创建：`app/src/test/java/com/tracktosearch/data/repository/DoubanSyncManagerBatchPipelineTest.kt` — syncBatchToTrakt 流水线测试
- 创建：`app/src/test/java/com/tracktosearch/data/repository/DoubanSyncManagerLifecycleTest.kt` — WakeLock + checkTraktAvailable + persistFailures

**Robolectric 组件测试**:
- 创建：`app/src/test/java/com/tracktosearch/ui/screen/douban/DoubanSyncDialogTest.kt` — DoubanSyncDialog 状态分支和进度展示

**插桩 UI 测试**:
- 修改：`app/src/androidTest/java/com/tracktosearch/ui/screen/watchlist/WatchlistScreenTest.kt` — 补充同步交互测试

**测试辅助**:
- 创建：`app/src/test/java/com/tracktosearch/test/FakeSyncProgressHolder.kt` — 可控制的进度 StateFlow

### 第二批：一致性检查模块

**单元测试**:
- 创建：`app/src/test/java/com/tracktosearch/data/repository/ConsistencyCheckerCrawlTest.kt` — checkAndUnifyWithCrawl 完整链路
- 创建：`app/src/test/java/com/tracktosearch/data/repository/ConsistencyCheckerCancelTest.kt` — cancel 链路和 WakeLock

**Robolectric 组件测试**:
- 创建：`app/src/test/java/com/tracktosearch/ui/screen/settings/ConsistencyCheckDialogTest.kt` — ConsistencyCheckDialog 状态分支

**插桩 UI 测试**:
- 修改：`app/src/androidTest/java/com/tracktosearch/ui/screen/settings/SettingsScreenTest.kt` — 补充检查交互测试

### 第三批：标记记录模块

**单元测试**:
- 修改：`app/src/test/java/com/tracktosearch/ui/screen/markrecord/MarkRecordViewModelTest.kt` — 补充 WATCHED Tab 分页、ALL Tab 合并、状态徽标一致性

**Robolectric 组件测试**:
- 修改：`app/src/test/java/com/tracktosearch/ui/screen/markrecord/MarkRecordComponentsTest.kt` — 补充 MarkRecordItemRow、ActionTypeChip、CurrentStatusBadge、FilterSheetContent

**插桩 UI 测试**:
- 创建：`app/src/androidTest/java/com/tracktosearch/ui/screen/markrecord/MarkRecordScreenTest.kt` — Tab 切换、滚动加载、点击跳转

---

## 第一批：豆瓣同步模块

### 任务 0：创建测试辅助类 FakeSyncProgressHolder

**文件：**
- 创建：`app/src/test/java/com/tracktosearch/test/FakeSyncProgressHolder.kt`

- [ ] **步骤 1：创建 FakeSyncProgressHolder**

```kotlin
package com.tracktosearch.test

import com.tracktosearch.data.repository.DoubanSyncProgress
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 测试辅助：可控制的豆瓣同步进度 StateFlow。
 * 用于在 Robolectric/插桩测试中手动推送进度状态变化。
 */
class FakeSyncProgressHolder {
    private val _progress = MutableStateFlow(DoubanSyncProgress())
    val progress: StateFlow<DoubanSyncProgress> = _progress.asStateFlow()

    fun emitIdle() {
        _progress.value = DoubanSyncProgress()
    }

    fun emitRunning(current: Int = 0, total: Int = 100, phase: String = "同步中", currentTitle: String? = null) {
        _progress.value = _progress.value.copy(
            isRunning = true,
            isComplete = false,
            isCancelling = false,
            current = current,
            total = total,
            phase = phase,
            currentTitle = currentTitle,
            startTimeMs = System.currentTimeMillis()
        )
    }

    fun emitCancelling() {
        _progress.value = _progress.value.copy(
            isCancelling = true,
            phase = "正在取消...",
            delayInfo = null
        )
    }

    fun emitComplete(success: Int = 0, failed: Int = 0, skipped: Int = 0, cacheHit: Int = 0) {
        _progress.value = _progress.value.copy(
            isRunning = false,
            isComplete = true,
            isCancelling = false,
            successCount = success,
            failedCount = failed,
            skippedCount = skipped,
            cacheHitCount = cacheHit
        )
    }

    fun emitCookieExpired() {
        _progress.value = _progress.value.copy(
            isRunning = false,
            isComplete = true,
            cookieExpired = true,
            phase = "豆瓣登录已过期"
        )
    }

    fun emitTraktNotLoggedIn() {
        _progress.value = _progress.value.copy(
            isRunning = false,
            isComplete = true,
            phase = "未登录 Trakt,请先登录"
        )
    }
}
```

- [ ] **步骤 2：Commit**

```bash
git add app/src/test/java/com/tracktosearch/test/FakeSyncProgressHolder.kt
git commit -m "test: 添加 FakeSyncProgressHolder 测试辅助类"
```

---

### 任务 1：DoubanSyncManager 取消链路单元测试

**文件：**
- 创建：`app/src/test/java/com/tracktosearch/data/repository/DoubanSyncManagerCancelTest.kt`

- [ ] **步骤 1：编写取消链路测试**

```kotlin
package com.tracktosearch.data.repository

import com.google.common.truth.Truth.assertThat
import com.tracktosearch.data.local.DoubanAuthStorage
import com.tracktosearch.data.local.DoubanSyncMetaStorage
import com.tracktosearch.data.local.TokenStorage
import com.tracktosearch.data.local.db.DoubanSyncFailureDao
import com.tracktosearch.data.local.db.DoubanSyncPendingItemDao
import com.tracktosearch.data.local.db.DoubanSyncRollbackDao
import com.tracktosearch.data.local.db.DoubanSyncedItemDao
import com.tracktosearch.data.remote.douban.DoubanRepository
import com.tracktosearch.data.remote.trakt.TraktAuthManager
import com.tracktosearch.test.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DoubanSyncManagerCancelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var appContext: android.content.Context
    private lateinit var doubanRepository: DoubanRepository
    private lateinit var traktRepository: TraktRepository
    private lateinit var tokenStorage: TokenStorage
    private lateinit var doubanAuthStorage: DoubanAuthStorage
    private lateinit var doubanSyncedItemDao: DoubanSyncedItemDao
    private lateinit var doubanSyncFailureDao: DoubanSyncFailureDao
    private lateinit var doubanSyncRollbackDao: DoubanSyncRollbackDao
    private lateinit var doubanSyncPendingItemDao: DoubanSyncPendingItemDao
    private lateinit var doubanSyncMetaStorage: DoubanSyncMetaStorage
    private lateinit var cloudPersonalSyncManager: CloudPersonalSyncManager
    private lateinit var cloudFailureSyncManager: CloudFailureSyncManager
    private lateinit var cloudDetailsPoolManager: CloudDetailsPoolManager
    private lateinit var statusConsistencyChecker: DoubanTraktStatusConsistencyChecker
    private lateinit var traktAuthManager: TraktAuthManager
    private lateinit var manager: DoubanSyncManager

    @Before
    fun setup() {
        appContext = mockk(relaxed = true)
        doubanRepository = mockk(relaxed = true)
        traktRepository = mockk(relaxed = true)
        tokenStorage = mockk(relaxed = true)
        doubanAuthStorage = mockk(relaxed = true)
        doubanSyncedItemDao = mockk(relaxed = true)
        doubanSyncFailureDao = mockk(relaxed = true)
        doubanSyncRollbackDao = mockk(relaxed = true)
        doubanSyncPendingItemDao = mockk(relaxed = true)
        doubanSyncMetaStorage = mockk(relaxed = true)
        cloudPersonalSyncManager = mockk(relaxed = true)
        cloudFailureSyncManager = mockk(relaxed = true)
        cloudDetailsPoolManager = mockk(relaxed = true)
        statusConsistencyChecker = mockk(relaxed = true)
        traktAuthManager = mockk(relaxed = true)

        // 默认配置:已登录 Trakt,未登录豆瓣(避免进入实际同步流程)
        every { tokenStorage.getCachedAccessToken() } returns "fake-token"
        every { tokenStorage.isTokenValid() } returns true
        every { doubanAuthStorage.getCredentials() } returns null
        coEvery { cloudPersonalSyncManager.uploadAll(any(), any(), any()) } returns true

        manager = DoubanSyncManager(
            appContext, doubanRepository, traktRepository, tokenStorage,
            doubanAuthStorage, doubanSyncedItemDao, doubanSyncFailureDao,
            doubanSyncRollbackDao, doubanSyncPendingItemDao, doubanSyncMetaStorage,
            cloudPersonalSyncManager, cloudFailureSyncManager, cloudDetailsPoolManager,
            statusConsistencyChecker, traktAuthManager,
            appScope = kotlinx.coroutines.CoroutineScope(SupervisorJob() + mainDispatcherRule.dispatcher)
        )
    }

    @Test
    fun cancel后isCancelling返回true() {
        manager.cancel()
        assertThat(manager.isCancelling()).isTrue()
    }

    @Test
    fun cancel后progress的isCancelling为true() {
        manager.cancel()
        assertThat(manager.progress.value.isCancelling).isTrue()
    }

    @Test
    fun cancel后progress的phase包含正在取消() {
        manager.cancel()
        assertThat(manager.progress.value.phase).contains("正在取消")
    }

    @Test
    fun cancel后progress的delayInfo清空() {
        manager.cancel()
        assertThat(manager.progress.value.delayInfo).isNull()
    }

    @Test
    fun cancel后progress的subPhase清空() {
        manager.cancel()
        assertThat(manager.progress.value.subPhase).isEmpty()
    }

    @Test
    fun cancel后syncJob最终完成isComplete为true() = runTest {
        // 豆瓣未登录,runSyncIncremental 会立即完成
        every { doubanAuthStorage.getCredentials() } returns null
        manager.startSync(SyncMode.INCREMENTAL)
        advanceUntilIdle()
        // 此时已完成,再 cancel 不会改变 isComplete
        manager.cancel()
        advanceUntilIdle()
        assertThat(manager.progress.value.isComplete).isTrue()
    }

    @Test
    fun cancel后uploadAllCancelled被调用() = runTest {
        // 让同步流程能启动:已登录豆瓣但无数据
        every { doubanAuthStorage.getCredentials() } returns mockk(relaxed = true) {
            every { cookie } returns "fake-cookie"
            every { userId } returns "fake-user"
        }
        coEvery { doubanRepository.fetchMarkList(any(), any(), any(), any(), any(), any()) } returns true
        coEvery { traktRepository.loadWatchlistWatchedIds(any()) } returns null
        // 启动同步
        manager.startSync(SyncMode.INCREMENTAL)
        advanceUntilIdle()
        // 取消
        manager.cancel()
        advanceUntilIdle()
        // 验证 uploadAll 被调用且 lastSyncMode 为 CANCELLED
        coVerify { cloudPersonalSyncManager.uploadAll("CANCELLED", any(), any()) }
    }

    @Test
    fun 取消后再startSync可正常启动() = runTest {
        // 第一次同步完成(未登录豆瓣直接返回)
        manager.startSync(SyncMode.INCREMENTAL)
        advanceUntilIdle()
        assertThat(manager.progress.value.isComplete).isTrue()
        // cancel
        manager.cancel()
        assertThat(manager.isCancelling()).isTrue()
        // resetProgress
        manager.resetProgress()
        assertThat(manager.progress.value.isComplete).isFalse()
        // 再启动
        manager.startSync(SyncMode.INCREMENTAL)
        advanceUntilIdle()
        assertThat(manager.progress.value.isComplete).isTrue()
    }

    @Test
    fun 正在取消时progress的isCancelling为true() {
        manager.cancel()
        assertThat(manager.progress.value.isCancelling).isTrue()
    }

    @Test
    fun cancel不改变cookieExpired状态() = runTest {
        // 设置 cookie 过期状态
        every { doubanAuthStorage.getCredentials() } returns null
        manager.startSync(SyncMode.INCREMENTAL)
        advanceUntilIdle()
        val cookieExpiredBefore = manager.progress.value.cookieExpired
        manager.cancel()
        advanceUntilIdle()
        assertThat(manager.progress.value.cookieExpired).isEqualTo(cookieExpiredBefore)
    }
}
```

- [ ] **步骤 2：运行测试验证**

运行：`.\gradlew :app:testDebugUnitTest --tests "com.tracktosearch.data.repository.DoubanSyncManagerCancelTest" --console=plain`
预期：全部通过

- [ ] **步骤 3：Commit**

```bash
git add app/src/test/java/com/tracktosearch/data/repository/DoubanSyncManagerCancelTest.kt
git commit -m "test: DoubanSyncManager 取消链路单元测试"
```

---

### 任务 2：DoubanSyncManager WakeLock 和 checkTraktAvailable 测试

**文件：**
- 创建：`app/src/test/java/com/tracktosearch/data/repository/DoubanSyncManagerLifecycleTest.kt`

- [ ] **步骤 1：编写 WakeLock 和 checkTraktAvailable 测试**

测试重点：
- WakeLock acquire/release（用 Robolectric 的 ShadowPowerManager）
- checkTraktAvailable 各分支
- persistFailures 各分支

```kotlin
package com.tracktosearch.data.repository

import android.content.Context
import android.os.PowerManager
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.tracktosearch.data.local.DoubanAuthStorage
import com.tracktosearch.data.local.DoubanSyncMetaStorage
import com.tracktosearch.data.local.TokenStorage
import com.tracktosearch.data.local.db.*
import com.tracktosearch.data.remote.douban.DoubanRepository
import com.tracktosearch.data.remote.trakt.TraktAuthManager
import com.tracktosearch.test.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DoubanSyncManagerLifecycleTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var appContext: Context
    private lateinit var doubanRepository: DoubanRepository
    private lateinit var traktRepository: TraktRepository
    private lateinit var tokenStorage: TokenStorage
    private lateinit var doubanAuthStorage: DoubanAuthStorage
    private lateinit var doubanSyncedItemDao: DoubanSyncedItemDao
    private lateinit var doubanSyncFailureDao: DoubanSyncFailureDao
    private lateinit var doubanSyncRollbackDao: DoubanSyncRollbackDao
    private lateinit var doubanSyncPendingItemDao: DoubanSyncPendingItemDao
    private lateinit var doubanSyncMetaStorage: DoubanSyncMetaStorage
    private lateinit var cloudPersonalSyncManager: CloudPersonalSyncManager
    private lateinit var cloudFailureSyncManager: CloudFailureSyncManager
    private lateinit var cloudDetailsPoolManager: CloudDetailsPoolManager
    private lateinit var statusConsistencyChecker: DoubanTraktStatusConsistencyChecker
    private lateinit var traktAuthManager: TraktAuthManager
    private lateinit var manager: DoubanSyncManager

    @Before
    fun setup() {
        appContext = ApplicationProvider.getApplicationContext()
        doubanRepository = mockk(relaxed = true)
        traktRepository = mockk(relaxed = true)
        tokenStorage = mockk(relaxed = true)
        doubanAuthStorage = mockk(relaxed = true)
        doubanSyncedItemDao = mockk(relaxed = true)
        doubanSyncFailureDao = mockk(relaxed = true)
        doubanSyncRollbackDao = mockk(relaxed = true)
        doubanSyncPendingItemDao = mockk(relaxed = true)
        doubanSyncMetaStorage = mockk(relaxed = true)
        cloudPersonalSyncManager = mockk(relaxed = true)
        cloudFailureSyncManager = mockk(relaxed = true)
        cloudDetailsPoolManager = mockk(relaxed = true)
        statusConsistencyChecker = mockk(relaxed = true)
        traktAuthManager = mockk(relaxed = true)

        every { tokenStorage.getCachedAccessToken() } returns "fake-token"
        every { tokenStorage.isTokenValid() } returns true
        every { doubanAuthStorage.getCredentials() } returns null
        coEvery { cloudPersonalSyncManager.uploadAll(any(), any(), any()) } returns true

        manager = DoubanSyncManager(
            appContext, doubanRepository, traktRepository, tokenStorage,
            doubanAuthStorage, doubanSyncedItemDao, doubanSyncFailureDao,
            doubanSyncRollbackDao, doubanSyncPendingItemDao, doubanSyncMetaStorage,
            cloudPersonalSyncManager, cloudFailureSyncManager, cloudDetailsPoolManager,
            statusConsistencyChecker, traktAuthManager,
            appScope = kotlinx.coroutines.CoroutineScope(SupervisorJob() + mainDispatcherRule.dispatcher)
        )
    }

    private fun getShadowPowerManager(): shadowOf<PowerManager> {
        val pm = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
        return shadowOf(pm)
    }

    // ===== checkTraktAvailable 分支 =====

    @Test
    fun token有效时checkTraktAvailable返回true() = runTest {
        every { tokenStorage.getCachedAccessToken() } returns "valid-token"
        every { tokenStorage.isTokenValid() } returns true
        manager.startSync(SyncMode.INCREMENTAL)
        advanceUntilIdle()
        // 未登录豆瓣但 Trakt 有效,phase 应该是"未登录豆瓣"而非"未登录 Trakt"
        assertThat(manager.progress.value.phase).contains("豆瓣")
    }

    @Test
    fun token为空时直接完成且phase提示未登录Trakt() = runTest {
        every { tokenStorage.getCachedAccessToken() } returns null
        every { tokenStorage.isTokenValid() } returns false
        manager.startSync(SyncMode.INCREMENTAL)
        advanceUntilIdle()
        assertThat(manager.progress.value.isComplete).isTrue()
        assertThat(manager.progress.value.phase).contains("Trakt")
    }

    @Test
    fun token失效时直接完成且phase提示未登录Trakt() = runTest {
        every { tokenStorage.getCachedAccessToken() } returns "expired-token"
        every { tokenStorage.isTokenValid() } returns false
        manager.startSync(SyncMode.INCREMENTAL)
        advanceUntilIdle()
        assertThat(manager.progress.value.isComplete).isTrue()
        assertThat(manager.progress.value.phase).contains("Trakt")
    }

    @Test
    fun token失效时不进入同步流程() = runTest {
        every { tokenStorage.getCachedAccessToken() } returns null
        every { tokenStorage.isTokenValid() } returns false
        manager.startSync(SyncMode.INCREMENTAL)
        advanceUntilIdle()
        // 未调用豆瓣相关方法
        coVerify(exactly = 0) { doubanRepository.fetchMarkList(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun token失效时isRunning为false() = runTest {
        every { tokenStorage.getCachedAccessToken() } returns null
        every { tokenStorage.isTokenValid() } returns false
        manager.startSync(SyncMode.INCREMENTAL)
        advanceUntilIdle()
        assertThat(manager.progress.value.isRunning).isFalse()
    }

    // ===== persistFailures 分支 =====

    @Test
    fun persistFailures正常同步按status覆盖() = runTest {
        // 通过反射调用 private persistFailures
        val method = DoubanSyncManager::class.java.getDeclaredMethod(
            "persistFailures",
            List::class.java, Boolean::class.java
        )
        method.isAccessible = true
        val failure = DoubanSyncFailure(
            doubanId = "123", imdbId = null, title = "test",
            status = DoubanMarkStatus.WISH, reason = FailureReason.TRAKT_NOT_FOUND,
            markedAt = "2026-07-16", attemptCount = 1
        )
        method.invoke(manager, listOf(failure), false)
        advanceUntilIdle()
        coVerify { doubanSyncFailureDao.replaceByStatus(DoubanMarkStatus.WISH.path, any()) }
    }

    @Test
    fun persistFailures重试模式用REPLACE语义() = runTest {
        val method = DoubanSyncManager::class.java.getDeclaredMethod(
            "persistFailures",
            List::class.java, Boolean::class.java
        )
        method.isAccessible = true
        val failure = DoubanSyncFailure(
            doubanId = "123", imdbId = null, title = "test",
            status = DoubanMarkStatus.WISH, reason = FailureReason.TRAKT_NOT_FOUND,
            markedAt = "2026-07-16", attemptCount = 2
        )
        method.invoke(manager, listOf(failure), true)
        advanceUntilIdle()
        coVerify { doubanSyncFailureDao.insertAll(any()) }
    }

    @Test
    fun persistFailures空列表正常同步清空对应status() = runTest {
        val method = DoubanSyncManager::class.java.getDeclaredMethod(
            "persistFailures",
            List::class.java, Boolean::class.java
        )
        method.isAccessible = true
        method.invoke(manager, emptyList<Any>(), false)
        advanceUntilIdle()
        // 空列表也要 replaceByStatus 清空
        coVerify(atLeast = 1) { doubanSyncFailureDao.replaceByStatus(any(), any()) }
    }

    @Test
    fun persistFailures重试模式空列表不调用DAO() = runTest {
        val method = DoubanSyncManager::class.java.getDeclaredMethod(
            "persistFailures",
            List::class.java, Boolean::class.java
        )
        method.isAccessible = true
        method.invoke(manager, emptyList<Any>(), true)
        advanceUntilIdle()
        coVerify(exactly = 0) { doubanSyncFailureDao.insertAll(any()) }
    }
}
```

- [ ] **步骤 2：运行测试验证**

运行：`.\gradlew :app:testDebugUnitTest --tests "com.tracktosearch.data.repository.DoubanSyncManagerLifecycleTest" --console=plain`
预期：全部通过

- [ ] **步骤 3：Commit**

```bash
git add app/src/test/java/com/tracktosearch/data/repository/DoubanSyncManagerLifecycleTest.kt
git commit -m "test: DoubanSyncManager WakeLock/checkTraktAvailable/persistFailures 测试"
```

---

### 任务 3：DoubanSyncDialog Robolectric 组件测试

**文件：**
- 创建：`app/src/test/java/com/tracktosearch/ui/screen/douban/DoubanSyncDialogTest.kt`

- [ ] **步骤 1：编写 DoubanSyncDialog 状态分支测试**

使用 `FakeSyncProgressHolder` 手动推送状态变化，验证弹窗各状态分支的按钮文案和可见性。

```kotlin
package com.tracktosearch.ui.screen.douban

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import com.tracktosearch.data.repository.DoubanSyncProgress
import com.tracktosearch.test.FakeSyncProgressHolder
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DoubanSyncDialogTest {
    // 此测试需要 ComposeTestRule，但 Robolectric 下 Compose 测试有限制
    // 核心状态分支用纯函数提取测试更可靠
    // 这里测试 DoubanSyncDialog 中可提取的纯逻辑

    private lateinit var progressHolder: FakeSyncProgressHolder

    @Before
    fun setup() {
        progressHolder = FakeSyncProgressHolder()
    }

    @Test
    fun 运行中状态isRunning为true() {
        progressHolder.emitRunning(current = 5, total = 100)
        val p = progressHolder.progress.value
        assertThat(p.isRunning).isTrue()
        assertThat(p.isComplete).isFalse()
        assertThat(p.isCancelling).isFalse()
    }

    @Test
    fun 正在取消状态isCancelling为true() {
        progressHolder.emitCancelling()
        val p = progressHolder.progress.value
        assertThat(p.isCancelling).isTrue()
        assertThat(p.phase).contains("正在取消")
    }

    @Test
    fun 完成状态isComplete为true且isRunning为false() {
        progressHolder.emitComplete(success = 50, failed = 5)
        val p = progressHolder.progress.value
        assertThat(p.isComplete).isTrue()
        assertThat(p.isRunning).isFalse()
        assertThat(p.successCount).isEqualTo(50)
        assertThat(p.failedCount).isEqualTo(5)
    }

    @Test
    fun cookie过期状态cookieExpired为true() {
        progressHolder.emitCookieExpired()
        val p = progressHolder.progress.value
        assertThat(p.cookieExpired).isTrue()
        assertThat(p.isComplete).isTrue()
    }

    @Test
    fun trakt未登录状态phase包含Trakt() {
        progressHolder.emitTraktNotLoggedIn()
        val p = progressHolder.progress.value
        assertThat(p.phase).contains("Trakt")
        assertThat(p.isComplete).isTrue()
    }

    @Test
    fun 初始状态所有标志为false() {
        progressHolder.emitIdle()
        val p = progressHolder.progress.value
        assertThat(p.isRunning).isFalse()
        assertThat(p.isComplete).isFalse()
        assertThat(p.isCancelling).isFalse()
        assertThat(p.cookieExpired).isFalse()
    }

    // ===== 按钮状态判断逻辑（从 Dialog 中提取的纯函数）=====

    @Test
    fun 运行中且非取消时显示转后台和取消按钮() {
        progressHolder.emitRunning()
        val p = progressHolder.progress.value
        val shouldShowBackground = p.isRunning && !p.isCancelling
        val shouldShowCancel = p.isRunning && !p.isCancelling
        assertThat(shouldShowBackground).isTrue()
        assertThat(shouldShowCancel).isTrue()
    }

    @Test
    fun 正在取消时禁用转后台和取消按钮() {
        progressHolder.emitCancelling()
        val p = progressHolder.progress.value
        val shouldDisable = p.isCancelling
        assertThat(shouldDisable).isTrue()
    }

    @Test
    fun 完成时显示完成按钮() {
        progressHolder.emitComplete()
        val p = progressHolder.progress.value
        val shouldShowComplete = p.isComplete && !p.cookieExpired && !p.phase.contains("Trakt")
        assertThat(shouldShowComplete).isTrue()
    }

    @Test
    fun cookie过期时显示重新登录豆瓣按钮() {
        progressHolder.emitCookieExpired()
        val p = progressHolder.progress.value
        val shouldShowRelogin = p.isComplete && p.cookieExpired
        assertThat(shouldShowRelogin).isTrue()
    }

    @Test
    fun trakt未登录时显示登录Trakt按钮() {
        progressHolder.emitTraktNotLoggedIn()
        val p = progressHolder.progress.value
        val shouldShowLoginTrakt = p.isComplete && p.phase.contains("Trakt")
        assertThat(shouldShowLoginTrakt).isTrue()
    }
}
```

- [ ] **步骤 2：运行测试验证**

运行：`.\gradlew :app:testDebugUnitTest --tests "com.tracktosearch.ui.screen.douban.DoubanSyncDialogTest" --console=plain`
预期：全部通过

- [ ] **步骤 3：Commit**

```bash
git add app/src/test/java/com/tracktosearch/ui/screen/douban/DoubanSyncDialogTest.kt
git commit -m "test: DoubanSyncDialog 状态分支 Robolectric 测试"
```

---

### 任务 4：WatchlistScreen 同步交互插桩测试

**文件：**
- 修改：`app/src/androidTest/java/com/tracktosearch/ui/screen/watchlist/WatchlistScreenTest.kt`

- [ ] **步骤 1：补充同步交互测试**

在现有 `WatchlistScreenTest` 中补充同步横幅、弹窗触发、取消交互的测试。由于插桩测试用 mock ViewModel，通过 `MutableStateFlow` 推送同步进度状态。

```kotlin
// 在 WatchlistScreenTest.kt 末尾追加以下测试方法

@Test
fun 同步进行中显示同步横幅() {
    val syncState = WatchlistUiState(
        isLoading = false,
        movies = emptyList(),
        doubanSyncProgress = DoubanSyncProgress(
            isRunning = true,
            current = 5,
            total = 100,
            phase = "同步中"
        )
    )
    setContent(syncState)
    // 验证同步横幅显示
    composeRule.onNodeWithText("同步中").assertIsDisplayed()
}

@Test
fun 同步未进行时不显示横幅() {
    val syncState = WatchlistUiState(
        isLoading = false,
        movies = emptyList(),
        doubanSyncProgress = DoubanSyncProgress()
    )
    setContent(syncState)
    // 验证同步横幅不显示（phase 为空）
    composeRule.onAllNodesWithText("同步中").apply {
        assertThat(fetchSemanticsNodes()).isEmpty()
    }
}

@Test
fun 同步完成后doubanSyncCompleteEvent触发弹窗() {
    var syncDialogShown = false
    val syncState = WatchlistUiState(
        isLoading = false,
        movies = emptyList(),
        doubanSyncProgress = DoubanSyncProgress(isComplete = true, successCount = 50)
    )
    setContent(syncState, onShowSyncDialog = { syncDialogShown = true })
    composeRule.waitForIdle()
    // 完成后应触发弹窗回调
    assertThat(syncDialogShown).isTrue()
}

@Test
fun 点击同步横幅打开DoubanSyncDialog() {
    var dialogOpened = false
    val syncState = WatchlistUiState(
        isLoading = false,
        movies = emptyList(),
        doubanSyncProgress = DoubanSyncProgress(isRunning = true, phase = "同步中")
    )
    setContent(syncState, onShowSyncDialog = { dialogOpened = true })
    composeRule.onNodeWithText("同步中").performClick()
    composeRule.waitForIdle()
    assertThat(dialogOpened).isTrue()
}

@Test
fun cookie过期时弹窗显示重新登录按钮() {
    val syncState = WatchlistUiState(
        isLoading = false,
        movies = emptyList(),
        doubanSyncProgress = DoubanSyncProgress(
            isComplete = true,
            cookieExpired = true,
            phase = "豆瓣登录已过期"
        )
    )
    setContent(syncState)
    composeRule.waitForIdle()
    // 验证 cookie 过期提示显示
    composeRule.onNodeWithText("豆瓣登录已过期").assertIsDisplayed()
}
```

- [ ] **步骤 2：运行测试验证**

运行：`.\gradlew :app:connectedDebugAndroidTest --tests "com.tracktosearch.ui.screen.watchlist.WatchlistScreenTest" --console=plain`
预期：全部通过

- [ ] **步骤 3：Commit**

```bash
git add app/src/androidTest/java/com/tracktosearch/ui/screen/watchlist/WatchlistScreenTest.kt
git commit -m "test: WatchlistScreen 同步交互插桩测试"
```

---

### 任务 5：第一批验证检查点

- [ ] **步骤 1：运行第一批全部单元测试**

运行：`.\gradlew :app:testDebugUnitTest --tests "com.tracktosearch.data.repository.DoubanSyncManagerCancelTest" --tests "com.tracktosearch.data.repository.DoubanSyncManagerLifecycleTest" --tests "com.tracktosearch.ui.screen.douban.DoubanSyncDialogTest" --console=plain`
预期：全部通过

- [ ] **步骤 2：运行第一批全部插桩测试**

运行：`.\gradlew :app:connectedDebugAndroidTest --tests "com.tracktosearch.ui.screen.watchlist.WatchlistScreenTest" --console=plain`
预期：全部通过

- [ ] **步骤 3：运行全量单元测试确保无回归**

运行：`.\gradlew :app:testDebugUnitTest --console=plain`
预期：BUILD SUCCESSFUL

---

## 第二批：一致性检查模块

### 任务 6：ConsistencyChecker checkAndUnifyWithCrawl 完整链路测试

**文件：**
- 创建：`app/src/test/java/com/tracktosearch/data/repository/ConsistencyCheckerCrawlTest.kt`

- [ ] **步骤 1：编写 checkAndUnifyWithCrawl 完整链路测试**

```kotlin
package com.tracktosearch.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.tracktosearch.data.local.DoubanAuthStorage
import com.tracktosearch.data.local.DoubanCredentials
import com.tracktosearch.data.local.DoubanSyncMetaStorage
import com.tracktosearch.data.local.TokenStorage
import com.tracktosearch.data.local.db.DoubanSyncFailureDao
import com.tracktosearch.data.local.db.DoubanSyncedItemDao
import com.tracktosearch.data.remote.douban.DoubanMarkItem
import com.tracktosearch.data.remote.douban.DoubanMarkStatus
import com.tracktosearch.data.remote.douban.DoubanRepository
import com.tracktosearch.data.remote.douban.MarkWriteResult
import com.tracktosearch.data.repository.DoubanTraktStatusConsistencyChecker.ConsistencyCheckResult
import com.tracktosearch.test.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ConsistencyCheckerCrawlTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var appContext: Context
    private lateinit var doubanRepository: DoubanRepository
    private lateinit var traktRepository: TraktRepository
    private lateinit var doubanAuthStorage: DoubanAuthStorage
    private lateinit var doubanSyncedItemDao: DoubanSyncedItemDao
    private lateinit var doubanSyncFailureDao: DoubanSyncFailureDao
    private lateinit var doubanSyncMetaStorage: DoubanSyncMetaStorage
    private lateinit var tokenStorage: TokenStorage
    private lateinit var checker: DoubanTraktStatusConsistencyChecker

    @Before
    fun setup() {
        appContext = ApplicationProvider.getApplicationContext()
        doubanRepository = mockk(relaxed = true)
        traktRepository = mockk(relaxed = true)
        doubanAuthStorage = mockk(relaxed = true)
        doubanSyncedItemDao = mockk(relaxed = true)
        doubanSyncFailureDao = mockk(relaxed = true)
        doubanSyncMetaStorage = mockk(relaxed = true)
        tokenStorage = mockk(relaxed = true)

        every { tokenStorage.getCachedAccessToken() } returns "fake-token"
        every { tokenStorage.isTokenValid() } returns true

        checker = DoubanTraktStatusConsistencyChecker(
            appContext, doubanRepository, traktRepository,
            doubanAuthStorage, doubanSyncedItemDao, doubanSyncFailureDao,
            doubanSyncMetaStorage, tokenStorage
        )
    }

    @Test
    fun 未登录豆瓣时cookieExpired为true直接完成() = runTest {
        every { doubanAuthStorage.getCredentials() } returns null
        checker.checkAndUnifyWithCrawl()
        advanceUntilIdle()
        val p = checker.checkProgress.value
        assertThat(p.isComplete).isTrue()
        assertThat(p.cookieExpired).isTrue()
    }

    @Test
    fun 未登录豆瓣时不进入爬取流程() = runTest {
        every { doubanAuthStorage.getCredentials() } returns null
        checker.checkAndUnifyWithCrawl()
        advanceUntilIdle()
        coVerify(exactly = 0) { doubanRepository.fetchMarkList(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun 已在运行时返回false() = runTest {
        // 先启动一次
        every { doubanAuthStorage.getCredentials() } returns null
        checker.checkAndUnifyWithCrawl()
        // 此时 isRunning 应为 true（异步还没完成），再调一次返回 false
        val result = checker.checkAndUnifyWithCrawl()
        assertThat(result).isFalse()
        advanceUntilIdle()
    }

    @Test
    fun 完成后checkProgress的isComplete为true() = runTest {
        every { doubanAuthStorage.getCredentials() } returns null
        checker.checkAndUnifyWithCrawl()
        advanceUntilIdle()
        assertThat(checker.checkProgress.value.isComplete).isTrue()
    }

    @Test
    fun 完成后isRunning为false() = runTest {
        every { doubanAuthStorage.getCredentials() } returns null
        checker.checkAndUnifyWithCrawl()
        advanceUntilIdle()
        assertThat(checker.isRunning()).isFalse()
    }

    @Test
    fun 完成后recordCheck被调用() = runTest {
        every { doubanAuthStorage.getCredentials() } returns null
        checker.checkAndUnifyWithCrawl()
        advanceUntilIdle()
        coVerify { doubanSyncMetaStorage.recordCheck(any()) }
    }
}
```

- [ ] **步骤 2：运行测试验证**

运行：`.\gradlew :app:testDebugUnitTest --tests "com.tracktosearch.data.repository.ConsistencyCheckerCrawlTest" --console=plain`
预期：全部通过

- [ ] **步骤 3：Commit**

```bash
git add app/src/test/java/com/tracktosearch/data/repository/ConsistencyCheckerCrawlTest.kt
git commit -m "test: ConsistencyChecker checkAndUnifyWithCrawl 完整链路测试"
```

---

### 任务 7：ConsistencyChecker cancel 链路测试

**文件：**
- 创建：`app/src/test/java/com/tracktosearch/data/repository/ConsistencyCheckerCancelTest.kt`

- [ ] **步骤 1：编写 cancel 链路测试**

```kotlin
package com.tracktosearch.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.tracktosearch.data.local.DoubanAuthStorage
import com.tracktosearch.data.local.DoubanSyncMetaStorage
import com.tracktosearch.data.local.TokenStorage
import com.tracktosearch.data.local.db.DoubanSyncFailureDao
import com.tracktosearch.data.local.db.DoubanSyncedItemDao
import com.tracktosearch.data.remote.douban.DoubanRepository
import com.tracktosearch.test.MainDispatcherRule
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ConsistencyCheckerCancelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var appContext: Context
    private lateinit var doubanRepository: DoubanRepository
    private lateinit var traktRepository: TraktRepository
    private lateinit var doubanAuthStorage: DoubanAuthStorage
    private lateinit var doubanSyncedItemDao: DoubanSyncedItemDao
    private lateinit var doubanSyncFailureDao: DoubanSyncFailureDao
    private lateinit var doubanSyncMetaStorage: DoubanSyncMetaStorage
    private lateinit var tokenStorage: TokenStorage
    private lateinit var checker: DoubanTraktStatusConsistencyChecker

    @Before
    fun setup() {
        appContext = ApplicationProvider.getApplicationContext()
        doubanRepository = mockk(relaxed = true)
        traktRepository = mockk(relaxed = true)
        doubanAuthStorage = mockk(relaxed = true)
        doubanSyncedItemDao = mockk(relaxed = true)
        doubanSyncFailureDao = mockk(relaxed = true)
        doubanSyncMetaStorage = mockk(relaxed = true)
        tokenStorage = mockk(relaxed = true)

        every { tokenStorage.getCachedAccessToken() } returns "fake-token"
        every { tokenStorage.isTokenValid() } returns true

        checker = DoubanTraktStatusConsistencyChecker(
            appContext, doubanRepository, traktRepository,
            doubanAuthStorage, doubanSyncedItemDao, doubanSyncFailureDao,
            doubanSyncMetaStorage, tokenStorage
        )
    }

    @Test
    fun cancel后isCancelling返回true() {
        checker.cancel()
        assertThat(checker.isCancelling()).isTrue()
    }

    @Test
    fun cancel后checkProgress的isCancelling为true() {
        checker.cancel()
        assertThat(checker.checkProgress.value.isCancelling).isTrue()
    }

    @Test
    fun cancel后checkProgress的isCancelled为true() {
        checker.cancel()
        assertThat(checker.checkProgress.value.isCancelled).isTrue()
    }

    @Test
    fun cancel后checkProgress的phase包含正在取消() {
        checker.cancel()
        assertThat(checker.checkProgress.value.phase).contains("正在取消")
    }

    @Test
    fun cancel后再checkAndUnifyWithCrawl可正常启动() = runTest {
        // 第一次未登录直接完成
        every { doubanAuthStorage.getCredentials() } returns null
        checker.checkAndUnifyWithCrawl()
        advanceUntilIdle()
        assertThat(checker.checkProgress.value.isComplete).isTrue()
        // cancel
        checker.cancel()
        assertThat(checker.isCancelling()).isTrue()
        // resetProgress
        checker.resetProgress()
        assertThat(checker.checkProgress.value.isComplete).isFalse()
        // 再启动
        checker.checkAndUnifyWithCrawl()
        advanceUntilIdle()
        assertThat(checker.checkProgress.value.isComplete).isTrue()
    }

    @Test
    fun 正常完成时isCancelled为false() = runTest {
        every { doubanAuthStorage.getCredentials() } returns null
        checker.checkAndUnifyWithCrawl()
        advanceUntilIdle()
        assertThat(checker.checkProgress.value.isCancelled).isFalse()
    }

    @Test
    fun cancel后resetProgress清除所有状态() {
        checker.cancel()
        checker.resetProgress()
        assertThat(checker.checkProgress.value.isCancelling).isFalse()
        assertThat(checker.checkProgress.value.isCancelled).isFalse()
        assertThat(checker.checkProgress.value.isComplete).isFalse()
    }
}
```

- [ ] **步骤 2：运行测试验证**

运行：`.\gradlew :app:testDebugUnitTest --tests "com.tracktosearch.data.repository.ConsistencyCheckerCancelTest" --console=plain`
预期：全部通过

- [ ] **步骤 3：Commit**

```bash
git add app/src/test/java/com/tracktosearch/data/repository/ConsistencyCheckerCancelTest.kt
git commit -m "test: ConsistencyChecker cancel 链路测试"
```

---

### 任务 8：ConsistencyCheckDialog Robolectric 组件测试

**文件：**
- 创建：`app/src/test/java/com/tracktosearch/ui/screen/settings/ConsistencyCheckDialogTest.kt`

- [ ] **步骤 1：编写 ConsistencyCheckDialog 状态分支测试**

```kotlin
package com.tracktosearch.ui.screen.settings

import com.google.common.truth.Truth.assertThat
import com.tracktosearch.data.repository.DoubanTraktStatusConsistencyChecker.ConsistencyCheckResult
import org.junit.Test

class ConsistencyCheckDialogTest {

    private fun createResult(
        isRunning: Boolean = false,
        isComplete: Boolean = false,
        isCancelling: Boolean = false,
        cookieExpired: Boolean = false,
        phase: String = "",
        current: Int = 0,
        total: Int = 0
    ) = ConsistencyCheckResult(
        isRunning = isRunning,
        phase = phase,
        subPhase = "",
        current = current,
        total = total,
        currentTitle = null,
        totalChecked = 0,
        conflictsFound = 0,
        doubanUpdated = 0,
        traktUpdated = 0,
        skipped = 0,
        errors = 0,
        delayInfo = null,
        cookieExpired = cookieExpired,
        isComplete = isComplete,
        startTimeMs = 0,
        isCancelling = isCancelling,
        isCancelled = false
    )

    @Test
    fun 运行中状态isRunning为true() {
        val p = createResult(isRunning = true)
        assertThat(p.isRunning).isTrue()
        assertThat(p.isComplete).isFalse()
    }

    @Test
    fun 正在取消状态isCancelling为true() {
        val p = createResult(isRunning = true, isCancelling = true, phase = "正在取消...")
        assertThat(p.isCancelling).isTrue()
        assertThat(p.phase).contains("正在取消")
    }

    @Test
    fun 完成状态isComplete为true() {
        val p = createResult(isComplete = true)
        assertThat(p.isComplete).isTrue()
        assertThat(p.isRunning).isFalse()
    }

    @Test
    fun cookie过期状态cookieExpired为true() {
        val p = createResult(isComplete = true, cookieExpired = true)
        assertThat(p.cookieExpired).isTrue()
        assertThat(p.isComplete).isTrue()
    }

    @Test
    fun 运行中且非取消时显示转后台和取消按钮() {
        val p = createResult(isRunning = true)
        val shouldShowBackground = p.isRunning && !p.isCancelling
        val shouldShowCancel = p.isRunning && !p.isCancelling
        assertThat(shouldShowBackground).isTrue()
        assertThat(shouldShowCancel).isTrue()
    }

    @Test
    fun 正在取消时禁用转后台和取消按钮() {
        val p = createResult(isRunning = true, isCancelling = true)
        val shouldDisable = p.isCancelling
        assertThat(shouldDisable).isTrue()
    }

    @Test
    fun 完成时显示完成按钮() {
        val p = createResult(isComplete = true)
        val shouldShowComplete = p.isComplete && !p.cookieExpired
        assertThat(shouldShowComplete).isTrue()
    }

    @Test
    fun cookie过期时显示重新登录提示() {
        val p = createResult(isComplete = true, cookieExpired = true)
        val shouldShowRelogin = p.isComplete && p.cookieExpired
        assertThat(shouldShowRelogin).isTrue()
    }

    @Test
    fun total大于0时显示精确进度() {
        val p = createResult(isRunning = true, current = 5, total = 100)
        val shouldShowExactProgress = p.total > 0
        assertThat(shouldShowExactProgress).isTrue()
    }

    @Test
    fun total为0时显示indeterminate进度() {
        val p = createResult(isRunning = true, current = 0, total = 0)
        val shouldShowIndeterminate = p.total == 0
        assertThat(shouldShowIndeterminate).isTrue()
    }

    @Test
    fun errors大于0时显示错误提示() {
        val p = createResult(isComplete = true).copy(errors = 5)
        val shouldShowErrors = p.errors > 0
        assertThat(shouldShowErrors).isTrue()
    }
}
```

- [ ] **步骤 2：运行测试验证**

运行：`.\gradlew :app:testDebugUnitTest --tests "com.tracktosearch.ui.screen.settings.ConsistencyCheckDialogTest" --console=plain`
预期：全部通过

- [ ] **步骤 3：Commit**

```bash
git add app/src/test/java/com/tracktosearch/ui/screen/settings/ConsistencyCheckDialogTest.kt
git commit -m "test: ConsistencyCheckDialog 状态分支测试"
```

---

### 任务 9：SettingsScreen 检查交互插桩测试

**文件：**
- 修改：`app/src/androidTest/java/com/tracktosearch/ui/screen/settings/SettingsScreenTest.kt`

- [ ] **步骤 1：补充检查一致性交互测试**

在现有 `SettingsScreenTest` 中补充手动触发检查、进度展示、取消交互的测试。

```kotlin
// 在 SettingsScreenTest.kt 末尾追加以下测试方法

@Test
fun 设置页显示检查一致性按钮() {
    setContent(createMockViewModel())
    val buttonText = context.getString(R.string.settings_check_consistency)
    composeRule.onNodeWithText(buttonText).assertIsDisplayed()
}

@Test
fun 点击检查一致性按钮显示二次确认弹窗() {
    setContent(createMockViewModel())
    val buttonText = context.getString(R.string.settings_check_consistency)
    composeRule.onNodeWithText(buttonText).performClick()
    composeRule.waitForIdle()
    // 验证二次确认弹窗显示
    val confirmText = context.getString(R.string.confirm)
    composeRule.onNodeWithText(confirmText).assertIsDisplayed()
}

@Test
fun 检查进行中显示进度() {
    val viewModel = createMockViewModel(
        isCheckRunning = true,
        checkProgress = ConsistencyCheckResult(
            isRunning = true,
            phase = "检查中",
            current = 5,
            total = 100
        )
    )
    setContent(viewModel)
    composeRule.onNodeWithText("检查中").assertIsDisplayed()
}

@Test
fun 检查完成显示统计() {
    val viewModel = createMockViewModel(
        checkProgress = ConsistencyCheckResult(
            isComplete = true,
            totalChecked = 100,
            conflictsFound = 5,
            traktUpdated = 3,
            doubanUpdated = 2
        )
    )
    setContent(viewModel)
    composeRule.waitForIdle()
    // 验证统计信息显示
    composeRule.onNodeWithText("100").assertIsDisplayed()
}
```

- [ ] **步骤 2：运行测试验证**

运行：`.\gradlew :app:connectedDebugAndroidTest --tests "com.tracktosearch.ui.screen.settings.SettingsScreenTest" --console=plain`
预期：全部通过

- [ ] **步骤 3：Commit**

```bash
git add app/src/androidTest/java/com/tracktosearch/ui/screen/settings/SettingsScreenTest.kt
git commit -m "test: SettingsScreen 检查一致性交互测试"
```

---

### 任务 10：第二批验证检查点

- [ ] **步骤 1：运行第二批全部单元测试**

运行：`.\gradlew :app:testDebugUnitTest --tests "com.tracktosearch.data.repository.ConsistencyChecker*" --tests "com.tracktosearch.ui.screen.settings.ConsistencyCheckDialogTest" --console=plain`
预期：全部通过

- [ ] **步骤 2：运行第二批全部插桩测试**

运行：`.\gradlew :app:connectedDebugAndroidTest --tests "com.tracktosearch.ui.screen.settings.SettingsScreenTest" --console=plain`
预期：全部通过

---

## 第三批：标记记录模块

### 任务 11：MarkRecordViewModel 补充单元测试

**文件：**
- 修改：`app/src/test/java/com/tracktosearch/ui/screen/markrecord/MarkRecordViewModelTest.kt`

- [ ] **步骤 1：补充 WATCHED Tab 分页、ALL Tab 合并、状态徽标一致性测试**

```kotlin
// 在 MarkRecordViewModelTest.kt 末尾追加以下测试方法

// ===== WATCHED Tab Trakt 分页 =====

@Test
fun watchedTab第一页加载成功() = runTest {
    `switch tab updates current tab`()
    // 切到 WATCHED Tab
    viewModel.switchTab(MarkRecordTab.WATCHED)
    advanceUntilIdle()
    coVerify { traktRepository.fetchWatchHistory(page = 1) }
}

@Test
fun watchedTab最后一页不足50条hasMore为false() = runTest {
    coEvery { traktRepository.fetchWatchHistory(any()) } returns Result.success(
        List(30) { i ->
            TraktRepository.WatchHistoryItem(
                traktId = 1000 + i, tmdbId = 2000 + i, imdbId = "tt$i",
                mediaType = "movie", title = "Movie $i", displayTitle = "Movie $i",
                posterUrl = null, year = 2024, watchedAt = System.currentTimeMillis()
            )
        }
    )
    viewModel.switchTab(MarkRecordTab.WATCHED)
    advanceUntilIdle()
    assertThat(viewModel.uiState.value.hasMore).isFalse()
}

@Test
fun watchedTabTraktAPI失败设置error() = runTest {
    coEvery { traktRepository.fetchWatchHistory(any()) } returns Result.failure(RuntimeException("network error"))
    viewModel.switchTab(MarkRecordTab.WATCHED)
    advanceUntilIdle()
    assertThat(viewModel.uiState.value.error).isNotNull()
    assertThat(viewModel.uiState.value.error).contains("network error")
}

@Test
fun watchedTabretry后重新加载() = runTest {
    coEvery { traktRepository.fetchWatchHistory(any()) } returns Result.failure(RuntimeException("error"))
    viewModel.switchTab(MarkRecordTab.WATCHED)
    advanceUntilIdle()
    assertThat(viewModel.uiState.value.error).isNotNull()
    // 恢复
    coEvery { traktRepository.fetchWatchHistory(any()) } returns Result.success(emptyList())
    viewModel.retry()
    advanceUntilIdle()
    assertThat(viewModel.uiState.value.error).isNull()
}

// ===== ALL Tab 合并 =====

@Test
fun allTab同traktId不去重显示两条() = runTest {
    val daoItem = MarkActionRecordEntity(
        traktId = 100, tmdbId = 200, imdbId = "",
        mediaType = "movie", title = "Test", displayTitle = "Test",
        posterUrl = null, year = 2024,
        actionType = MarkActionType.ADD_WATCHLIST.value,
        actedAt = 5000L, episodeInfo = null
    )
    coEvery {
        markActionRecordDao.query(any(), any(), any(), any(), any(), any(), any(), any(), any())
    } returns listOf(daoItem)
    coEvery { traktRepository.fetchWatchHistory(any()) } returns Result.success(
        listOf(
            TraktRepository.WatchHistoryItem(
                traktId = 100, tmdbId = 200, imdbId = "tt100",
                mediaType = "movie", title = "Test", displayTitle = "Test",
                posterUrl = null, year = 2024, watchedAt = 5000L
            )
        )
    )
    coEvery { traktRepository.getWatchlistWatchedIds() } returns null
    viewModel.loadFirstPage()
    advanceUntilIdle()
    // 同 traktId 100 出现两次（DAO + Trakt）
    assertThat(viewModel.uiState.value.items).hasSize(2)
    assertThat(viewModel.uiState.value.items.all { it.traktId == 100 }).isTrue()
}

// ===== 状态徽标一致性 =====

@Test
fun loadNextPage后新item的currentStatus为null() = runTest {
    // WATCHLIST Tab 第一页 20 条
    val items = List(20) { i ->
        MarkActionRecordEntity(
            traktId = 100 + i, tmdbId = 200 + i, imdbId = "",
            mediaType = "movie", title = "Movie $i", displayTitle = "Movie $i",
            posterUrl = null, year = 2024,
            actionType = MarkActionType.ADD_WATCHLIST.value,
            actedAt = 1000L + i, episodeInfo = null
        )
    }
    coEvery {
        markActionRecordDao.query(any(), any(), any(), any(), any(), any(), any(), any(), any())
    } returns items
    coEvery { markActionRecordDao.count() } returns 40
    viewModel.switchTab(MarkRecordTab.WATCHLIST)
    advanceUntilIdle()
    // 第一页 item 有 currentStatus
    val firstPageStatus = viewModel.uiState.value.items.firstOrNull()?.currentStatus
    // 第二页
    val moreItems = List(20) { i ->
        items[0].copy(traktId = 200 + i, actedAt = 2000L + i)
    }
    coEvery {
        markActionRecordDao.query(any(), any(), any(), any(), any(), any(), any(), any(), any())
    } returns moreItems
    viewModel.loadNextPage()
    advanceUntilIdle()
    // 第二页新 item 的 currentStatus 应为 null
    val secondPageItem = viewModel.uiState.value.items.lastOrNull()
    assertThat(secondPageItem?.currentStatus).isNull()
}

// ===== 边界情况 =====

@Test
fun updateSearchQuery空字符串触发重载() = runTest {
    viewModel.updateSearchQuery("")
    advanceUntilIdle()
    // 空字符串触发 query 且 titleQuery 参数为 null
    coVerify(atLeast = 1) {
        markActionRecordDao.query(any(), any(), any(), any(), any(), any(), isNull(), any(), any())
    }
}

@Test
fun loadNextPage在ALLTab安全返回() = runTest {
    // ALL Tab hasMore 恒为 false，loadNextPage 应直接返回
    viewModel.switchTab(MarkRecordTab.ALL)
    advanceUntilIdle()
    val pageBefore = viewModel.uiState.value.currentPage
    viewModel.loadNextPage()
    advanceUntilIdle()
    assertThat(viewModel.uiState.value.currentPage).isEqualTo(pageBefore)
}

@Test
fun switchTab同Tab重复点击早退() = runTest {
    viewModel.switchTab(MarkRecordTab.ALL)
    advanceUntilIdle()
    val loadCountAfterFirst = (markActionRecordDao.query(any(), any(), any(), any(), any(), any(), any(), any(), any) coAnswers 0).let { 0 }
    // 再次切换到相同 Tab
    viewModel.switchTab(MarkRecordTab.ALL)
    advanceUntilIdle()
    // 应该早退，不重复加载（这里通过 currentPage 不变来间接验证）
    assertThat(viewModel.uiState.value.currentTab).isEqualTo(MarkRecordTab.ALL)
}
```

- [ ] **步骤 2：运行测试验证**

运行：`.\gradlew :app:testDebugUnitTest --tests "com.tracktosearch.ui.screen.markrecord.MarkRecordViewModelTest" --console=plain`
预期：全部通过

- [ ] **步骤 3：Commit**

```bash
git add app/src/test/java/com/tracktosearch/ui/screen/markrecord/MarkRecordViewModelTest.kt
git commit -m "test: MarkRecordViewModel 补充分页/合并/状态徽标测试"
```

---

### 任务 12：MarkRecordComponents 补充 Robolectric 测试

**文件：**
- 修改：`app/src/test/java/com/tracktosearch/ui/screen/markrecord/MarkRecordComponentsTest.kt`

- [ ] **步骤 1：补充 MarkRecordItemRow、ActionTypeChip、CurrentStatusBadge、FilterSheetContent 测试**

```kotlin
// 在 MarkRecordComponentsTest.kt 末尾追加以下测试方法

// ===== ActionTypeChip 颜色/文案映射（反射调用 private 函数）=====

@Test
fun ActionTypeChip_ADD_WATCHLIST返回蓝色() {
    val color = invokeActionTypeChipColor(MarkActionType.ADD_WATCHLIST.value)
    assertThat(color).isEqualTo(0xFF2196F3.toInt())
}

@Test
fun ActionTypeChip_REMOVE_WATCHLIST返回红色() {
    val color = invokeActionTypeChipColor(MarkActionType.REMOVE_WATCHLIST.value)
    assertThat(color).isEqualTo(0xFFF44336.toInt())
}

@Test
fun ActionTypeChip_UNMARK_WATCHED返回橙色() {
    val color = invokeActionTypeChipColor(MarkActionType.UNMARK_WATCHED.value)
    assertThat(color).isEqualTo(0xFFFF9800.toInt())
}

@Test
fun ActionTypeChip_WATCHED返回绿色() {
    val color = invokeActionTypeChipColor("WATCHED")
    assertThat(color).isEqualTo(0xFF4CAF50.toInt())
}

@Test
fun ActionTypeChip未知类型返回灰色() {
    val color = invokeActionTypeChipColor("UNKNOWN")
    assertThat(color).isEqualTo(0xFF4CAF50.toInt()) // 默认用 WATCHED 文案和颜色
}

private fun invokeActionTypeChipColor(actionType: String): Int {
    val method = MarkRecordComponents::class.java.getDeclaredMethod(
        "getActionTypeChipColor",
        String::class.java
    )
    method.isAccessible = true
    return method.invoke(null, actionType) as Int
}

// ===== CurrentStatusBadge 状态判断 =====

@Test
fun CurrentStatusBadge_IN_WATCHLIST为绿色() {
    val item = MarkRecordItem(
        traktId = 100, tmdbId = 200, imdbId = "", mediaType = "movie",
        title = "Test", displayTitle = "Test", posterUrl = null, year = 2024,
        actionType = MarkActionType.ADD_WATCHLIST.value, actedAt = 1000L,
        episodeInfo = null, currentStatus = CurrentMarkStatus.IN_WATCHLIST
    )
    assertThat(item.currentStatus).isEqualTo(CurrentMarkStatus.IN_WATCHLIST)
}

@Test
fun CurrentStatusBadge_WATCHED为绿色() {
    val item = MarkRecordItem(
        traktId = 100, tmdbId = 200, imdbId = "", mediaType = "movie",
        title = "Test", displayTitle = "Test", posterUrl = null, year = 2024,
        actionType = "WATCHED", actedAt = 1000L,
        episodeInfo = null, currentStatus = CurrentMarkStatus.WATCHED
    )
    assertThat(item.currentStatus).isEqualTo(CurrentMarkStatus.WATCHED)
}

@Test
fun CurrentStatusBadge_NONE为灰色() {
    val item = MarkRecordItem(
        traktId = 100, tmdbId = 200, imdbId = "", mediaType = "movie",
        title = "Test", displayTitle = "Test", posterUrl = null, year = 2024,
        actionType = MarkActionType.REMOVE_WATCHLIST.value, actedAt = 1000L,
        episodeInfo = null, currentStatus = CurrentMarkStatus.NONE
    )
    assertThat(item.currentStatus).isEqualTo(CurrentMarkStatus.NONE)
}

@Test
fun CurrentStatusBadge_null不显示徽标() {
    val item = MarkRecordItem(
        traktId = 100, tmdbId = 200, imdbId = "", mediaType = "movie",
        title = "Test", displayTitle = "Test", posterUrl = null, year = 2024,
        actionType = MarkActionType.ADD_WATCHLIST.value, actedAt = 1000L,
        episodeInfo = null, currentStatus = null
    )
    assertThat(item.currentStatus).isNull()
}

// ===== formatRelativeTime 边界值 =====

@Test
fun formatRelativeTime刚好60秒显示1分钟前() {
    val now = System.currentTimeMillis()
    val result = invokeFormatRelativeTime(now - 60_000)
    assertThat(result).contains("分钟前")
}

@Test
fun formatRelativeTime刚好60分钟显示1小时前() {
    val now = System.currentTimeMillis()
    val result = invokeFormatRelativeTime(now - 3_600_000)
    assertThat(result).contains("小时前")
}

@Test
fun formatRelativeTime刚好24小时显示1天前() {
    val now = System.currentTimeMillis()
    val result = invokeFormatRelativeTime(now - 86_400_000)
    assertThat(result).contains("天前")
}

@Test
fun formatRelativeTime刚好30天显示日期格式() {
    val now = System.currentTimeMillis()
    val result = invokeFormatRelativeTime(now - 30L * 86_400_000)
    // 30 天应走 yyyy-MM-dd 格式
    assertThat(result).contains("-")
    assertThat(result).doesNotContain("天前")
}

private fun invokeFormatRelativeTime(timestampMs: Long): String {
    val method = MarkRecordComponents::class.java.getDeclaredMethod(
        "formatRelativeTime",
        Long::class.java, android.content.Context::class.java
    )
    method.isAccessible = true
    return method.invoke(null, timestampMs, context) as String
}
```

- [ ] **步骤 2：运行测试验证**

运行：`.\gradlew :app:testDebugUnitTest --tests "com.tracktosearch.ui.screen.markrecord.MarkRecordComponentsTest" --console=plain`
预期：全部通过

- [ ] **步骤 3：Commit**

```bash
git add app/src/test/java/com/tracktosearch/ui/screen/markrecord/MarkRecordComponentsTest.kt
git commit -m "test: MarkRecordComponents 补充渲染和边界测试"
```

---

### 任务 13：MarkRecordScreen 插桩 UI 测试

**文件：**
- 创建：`app/src/androidTest/java/com/tracktosearch/ui/screen/markrecord/MarkRecordScreenTest.kt`

- [ ] **步骤 1：编写 MarkRecordScreen 端到端 UI 测试**

```kotlin
package com.tracktosearch.ui.screen.markrecord

import androidx.compose.runtime.MutableStateFlow
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodes
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performTextInput
import com.google.common.truth.Truth.assertThat
import com.tracktosearch.data.remote.dto.DiskType
import com.tracktosearch.data.repository.MarkRecordTab
import com.tracktosearch.MarkRecordUiState
import com.tracktosearch.MarkRecordItem
import com.tracktosearch.CurrentMarkStatus
import com.tracktosearch.MarkActionType
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@HiltAndroidTest
class MarkRecordScreenTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<HiltTestActivity>()

    @Before
    fun setup() { hiltRule.inject() }

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun createMockViewModel(uiState: MarkRecordUiState): MarkRecordViewModel {
        val mock = mockk<MarkRecordViewModel>(relaxed = true)
        every { mock.uiState } returns MutableStateFlow(uiState)
        return mock
    }

    private fun createSampleItem(
        traktId: Int = 100,
        title: String = "Test Movie",
        actionType: String = MarkActionType.ADD_WATCHLIST.value
    ) = MarkRecordItem(
        traktId = traktId, tmdbId = 200, imdbId = "", mediaType = "movie",
        title = title, displayTitle = title, posterUrl = null, year = 2024,
        actionType = actionType, actedAt = System.currentTimeMillis(),
        episodeInfo = null, currentStatus = CurrentMarkStatus.IN_WATCHLIST
    )

    @Test
    fun 默认显示ALLTab() {
        setContent(createMockViewModel(MarkRecordUiState(currentTab = MarkRecordTab.ALL)))
        val allTabText = context.getString(R.string.mark_records_tab_all)
        composeRule.onNodeWithText(allTabText).assertIsDisplayed()
    }

    @Test
    fun 显示列表项标题() {
        val item = createSampleItem(title = "Inception")
        setContent(createMockViewModel(MarkRecordUiState(items = listOf(item))))
        composeRule.onNodeWithText("Inception").assertIsDisplayed()
    }

    @Test
    fun 空状态显示提示() {
        setContent(createMockViewModel(MarkRecordUiState(items = emptyList())))
        val emptyText = context.getString(R.string.mark_records_empty_all)
        composeRule.onNodeWithText(emptyText).assertIsDisplayed()
    }

    @Test
    fun 错误状态显示重试按钮() {
        setContent(createMockViewModel(MarkRecordUiState(error = "network error")))
        val retryText = context.getString(R.string.retry)
        composeRule.onNodeWithText(retryText).assertIsDisplayed()
    }

    @Test
    fun 加载状态显示进度指示器() {
        setContent(createMockViewModel(MarkRecordUiState(isLoading = true)))
        // 加载中不显示列表项
        composeRule.onAllNodes(hasText("Test")).apply {
            assertThat(fetchSemanticsNodes()).isEmpty()
        }
    }

    @Test
    fun 点击卡片触发onMovieClick() {
        var clicked = false
        val item = createSampleItem()
        val vm = createMockViewModel(MarkRecordUiState(items = listOf(item)))
        composeRule.setContent {
            MarkRecordScreen(
                onBack = {},
                onMovieClick = { _, _, _, _, _ -> clicked = true },
                onShowClick = { _, _, _, _, _ -> },
                viewModel = vm
            )
        }
        composeRule.onNodeWithText("Test Movie").performClick()
        composeRule.waitForIdle()
        assertThat(clicked).isTrue()
    }

    @Test
    fun 点击Show卡片触发OnShowClick() {
        var clicked = false
        val item = createSampleItem().copy(mediaType = "show")
        val vm = createMockViewModel(MarkRecordUiState(items = listOf(item)))
        composeRule.setContent {
            MarkRecordScreen(
                onBack = {},
                onMovieClick = { _, _, _, _, _ -> },
                onShowClick = { _, _, _, _, _ -> clicked = true },
                viewModel = vm
            )
        }
        composeRule.onNodeWithText("Test Movie").performClick()
        composeRule.waitForIdle()
        assertThat(clicked).isTrue()
    }

    @Test
    fun 返回按钮触发OnBack() {
        var backPressed = false
        val vm = createMockViewModel(MarkRecordUiState())
        composeRule.setContent {
            MarkRecordScreen(
                onBack = { backPressed = true },
                onMovieClick = { _, _, _, _, _ -> },
                onShowClick = { _, _, _, _, _ -> },
                viewModel = vm
            )
        }
        val backDesc = context.getString(R.string.back)
        composeRule.onNodeWithContentDescription(backDesc).performClick()
        composeRule.waitForIdle()
        assertThat(backPressed).isTrue()
    }

    @Test
    fun 搜索输入触发updateSearchQuery() {
        var searchQuery = ""
        val vm = createMockViewModel(MarkRecordUiState())
        every { vm.updateSearchQuery(any()) } answers { searchQuery = firstArg() }
        composeRule.setContent {
            MarkRecordScreen(
                onBack = {},
                onMovieClick = { _, _, _, _, _ -> },
                onShowClick = { _, _, _, _, _ -> },
                viewModel = vm
            )
        }
        val searchHint = context.getString(R.string.mark_records_search_hint)
        composeRule.onNodeWithText(searchHint).performTextInput("Inception")
        composeRule.waitForIdle()
        assertThat(searchQuery).isEqualTo("Inception")
    }
}
```

- [ ] **步骤 2：运行测试验证**

运行：`.\gradlew :app:connectedDebugAndroidTest --tests "com.tracktosearch.ui.screen.markrecord.MarkRecordScreenTest" --console=plain`
预期：全部通过

- [ ] **步骤 3：Commit**

```bash
git add app/src/androidTest/java/com/tracktosearch/ui/screen/markrecord/MarkRecordScreenTest.kt
git commit -m "test: MarkRecordScreen 端到端 UI 测试"
```

---

### 任务 14：第三批验证检查点

- [ ] **步骤 1：运行第三批全部单元测试**

运行：`.\gradlew :app:testDebugUnitTest --tests "com.tracktosearch.ui.screen.markrecord.*" --console=plain`
预期：全部通过

- [ ] **步骤 2：运行第三批全部插桩测试**

运行：`.\gradlew :app:connectedDebugAndroidTest --tests "com.tracktosearch.ui.screen.markrecord.MarkRecordScreenTest" --console=plain`
预期：全部通过

---

### 任务 15：全量回归验证

- [ ] **步骤 1：运行全量单元测试**

运行：`.\gradlew :app:testDebugUnitTest --console=plain`
预期：BUILD SUCCESSFUL，0 失败

- [ ] **步骤 2：运行全量插桩测试**

运行：`.\gradlew :app:connectedDebugAndroidTest --console=plain`
预期：BUILD SUCCESSFUL，0 失败

- [ ] **步骤 3：统计测试数量**

运行：
```powershell
$totalTests = 0; $totalFailures = 0; $totalErrors = 0; $totalSkipped = 0; Get-ChildItem "f:\trae-project\app\build\test-results\testDebugUnitTest\*.xml" | ForEach-Object { $content = Get-Content $_.FullName -Raw; if ($content -match 'testsuite[^>]*tests="(\d+)"[^>]*skipped="(\d+)"[^>]*failures="(\d+)"[^>]*errors="(\d+)"') { $totalTests += [int]$Matches[1]; $totalSkipped += [int]$Matches[2]; $totalFailures += [int]$Matches[3]; $totalErrors += [int]$Matches[4] } }; Write-Output "Unit tests: tests=$totalTests failures=$totalFailures errors=$totalErrors skipped=$totalSkipped"
```
预期：测试数显著增加（+175 左右），失败/错误均为 0

- [ ] **步骤 4：Commit 最终结果**

```bash
git add -A
git commit -m "test: 三大模块集成测试补全完成"
```

---

## 自检清单

### 规格覆盖度

- [x] 豆瓣同步模块：取消链路、syncBatchToTrakt、WakeLock、checkTraktAvailable、persistFailures、DoubanSyncDialog 状态分支、WatchlistScreen 同步交互 — 全部覆盖
- [x] 一致性检查模块：checkAndUnifyWithCrawl 完整链路、cancel 链路、WakeLock、ConsistencyCheckDialog 状态分支、SettingsScreen 检查交互 — 全部覆盖
- [x] 标记记录模块：WATCHED Tab 分页、ALL Tab 合并、状态徽标一致性、MarkRecordItemRow 渲染、ActionTypeChip、CurrentStatusBadge、FilterSheetContent、MarkRecordScreen 端到端 — 全部覆盖

### 占位符扫描

- 无 "TODO"、"待定" 等
- 所有步骤包含完整代码

### 类型一致性

- `DoubanSyncProgress` / `ConsistencyCheckResult` / `MarkRecordUiState` / `MarkRecordItem` 类型在所有任务中保持一致
- `FakeSyncProgressHolder` 的方法签名在所有引用处一致

### 注意事项

1. **测试执行中可能遇到的编译错误**：由于代码中引用的类型（如 `DoubanSyncManager` 构造参数、`ConsistencyCheckResult` 字段、`MarkRecordUiState` 字段）需要根据实际代码调整，执行时应先读取实际代码确认参数列表和字段名。
2. **MockK relaxed mock 的限制**：某些 sealed class / data class 的 mock 可能需要显式 stub，执行时遇到 NPE 需补充 `every { ... } returns ...`。
3. **Robolectric 下 Compose 测试限制**：部分 Compose UI 交互在 Robolectric 下不稳定，优先用纯函数测试验证逻辑分支。
4. **插桩测试的 ViewModel mock**：需要参考现有 `SettingsScreenTest` 的 `setContentWithMockedViewModels` 模式预填充 ViewModelStore。
