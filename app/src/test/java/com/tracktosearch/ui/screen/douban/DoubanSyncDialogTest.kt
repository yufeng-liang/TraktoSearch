package com.tracktosearch.ui.screen.douban

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import com.tracktosearch.data.repository.DoubanSyncFailure
import com.tracktosearch.data.repository.DoubanSyncManager
import com.tracktosearch.data.repository.DoubanSyncProgress
import com.tracktosearch.data.repository.DoubanSyncLoginTarget
import com.tracktosearch.data.repository.DoubanSyncPreviewItem
import com.tracktosearch.data.repository.DoubanSyncQueueItem
import com.tracktosearch.data.repository.DoubanSyncStage
import com.tracktosearch.data.repository.DoubanSyncSubStage
import com.tracktosearch.data.repository.FailureReason
import com.tracktosearch.data.remote.douban.DoubanMarkStatus
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * DoubanSyncDialog 真实 Compose UI 渲染测试。
 *
 * 不再验证 FakeSyncProgressHolder 字段赋值，而是真实渲染 [DoubanSyncDialog] Composable，
 * 通过 `onNodeWithText` 验证按钮文案/可见性/启用状态，通过 `performClick` 验证点击回调。
 *
 * 通过 mockk 构造可控的 [DoubanSyncManager]，让 `progress` 返回 [MutableStateFlow]，
 * 再传入真实的 [DoubanSyncViewModel]（绕过 hiltViewModel）。
 *
 * Robolectric 默认加载 values/（英文），故使用英文文案断言：
 * - "Background" / "Cancel" / "Cancelling..." / "Sync Complete" / "Re-login" / "Login Trakt"
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DoubanSyncDialogTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var progressFlow: MutableStateFlow<DoubanSyncProgress>
    private lateinit var mockManager: DoubanSyncManager
    private lateinit var viewModel: DoubanSyncViewModel

    @Before
    fun setup() {
        progressFlow = MutableStateFlow(DoubanSyncProgress())
        mockManager = mockk(relaxed = true)
        every { mockManager.progress } returns progressFlow
        viewModel = DoubanSyncViewModel(mockManager)
    }

    /** 推送新的进度状态到 flow */
    private fun emit(progress: DoubanSyncProgress) {
        progressFlow.value = progress
    }

    /** 渲染 Dialog，回调默认空实现；onBackground 走 Dialog 默认（= onDismiss） */
    private fun setContent(
        onDismiss: () -> Unit = {},
        onRelogin: (() -> Unit)? = null,
        onTraktLogin: (() -> Unit)? = null
    ) {
        composeRule.setContent {
            MaterialTheme {
                DoubanSyncDialog(
                    onDismiss = onDismiss,
                    onRelogin = onRelogin,
                    onTraktLogin = onTraktLogin,
                    viewModel = viewModel
                )
            }
        }
    }

    // ==================== 运行中状态 ====================

    @Test
    fun `运行中显示转后台和取消按钮`() {
        emit(DoubanSyncProgress(
            isRunning = true,
            current = 5,
            total = 100,
            stage = DoubanSyncStage.FETCHING_LIST,
            subStage = DoubanSyncSubStage.FETCHING_WISH_LIST
        ))
        setContent()
        composeRule.onNodeWithText("Background").assertIsDisplayed().assertIsEnabled()
        composeRule.onNodeWithText("Cancel").assertIsDisplayed().assertIsEnabled()
    }

    @Test
    fun `运行中点击取消调用viewModel_cancel`() {
        emit(DoubanSyncProgress(
            isRunning = true,
            current = 5,
            total = 100,
            stage = DoubanSyncStage.FETCHING_LIST,
            subStage = DoubanSyncSubStage.FETCHING_WISH_LIST
        ))
        setContent()
        composeRule.onNodeWithText("Cancel").performClick()
        verify { mockManager.cancel() }
    }

    @Test
    fun `正在取消时转后台和取消按钮被禁用`() {
        emit(DoubanSyncProgress(
            isRunning = true,
            isCancelling = true,
            stage = DoubanSyncStage.CANCELLING
        ))
        setContent()
        composeRule.onNodeWithText("Background").assertIsNotEnabled()
        composeRule.onNodeWithText("Cancelling...").assertIsNotEnabled()
    }

    @Test
    fun `正在取消时取消按钮文案为正在取消`() {
        emit(DoubanSyncProgress(
            isRunning = true,
            isCancelling = true,
            stage = DoubanSyncStage.CANCELLING
        ))
        setContent()
        composeRule.onNodeWithText("Cancelling...").assertIsDisplayed()
    }

    // ==================== 完成状态 ====================

    @Test
    fun `完成状态显示完成按钮`() {
        emit(DoubanSyncProgress(
            isComplete = true,
            stage = DoubanSyncStage.COMPLETED,
            successCount = 50,
            failedCount = 5
        ))
        setContent()
        composeRule.onNodeWithText("Sync Complete").assertIsDisplayed()
    }

    @Test
    fun `完成状态点击完成调用onDismiss`() {
        emit(DoubanSyncProgress(
            isComplete = true,
            stage = DoubanSyncStage.COMPLETED,
            successCount = 50,
            failedCount = 5
        ))
        var dismissed = false
        setContent(onDismiss = { dismissed = true })
        composeRule.onNodeWithText("Sync Complete").performClick()
        assertThat(dismissed).isTrue()
    }

    // ==================== Cookie 过期状态 ====================

    @Test
    fun `cookieExpired状态显示重新登录按钮`() {
        emit(DoubanSyncProgress(
            isComplete = true,
            stage = DoubanSyncStage.LOGIN_REQUIRED,
            loginTarget = DoubanSyncLoginTarget.DOUBAN,
            cookieExpired = true
        ))
        setContent(onRelogin = {})
        composeRule.onNodeWithText("Re-login").assertIsDisplayed()
    }

    @Test
    fun `cookieExpired点击重新登录调用onRelogin`() {
        emit(DoubanSyncProgress(
            isComplete = true,
            stage = DoubanSyncStage.LOGIN_REQUIRED,
            loginTarget = DoubanSyncLoginTarget.DOUBAN,
            cookieExpired = true
        ))
        var reloginCalled = false
        setContent(onRelogin = { reloginCalled = true })
        composeRule.onNodeWithText("Re-login").performClick()
        assertThat(reloginCalled).isTrue()
    }

    // ==================== Trakt 未登录状态 ====================

    @Test
    fun `未登录Trakt状态显示登录Trakt按钮`() {
        emit(DoubanSyncProgress(
            isComplete = true,
            stage = DoubanSyncStage.LOGIN_REQUIRED,
            loginTarget = DoubanSyncLoginTarget.TRAKT
        ))
        setContent(onTraktLogin = {})
        composeRule.onNodeWithText("Login Trakt").assertIsDisplayed()
    }

    // ==================== 文案展示 ====================

    @Test
    fun `运行中显示进度文案`() {
        emit(DoubanSyncProgress(
            isRunning = true,
            current = 5,
            total = 100,
            stage = DoubanSyncStage.FETCHING_LIST,
            subStage = DoubanSyncSubStage.FETCHING_WISH_LIST
        ))
        setContent()
        composeRule.onNodeWithText("Fetching list (5/100)").assertIsDisplayed()
    }

    @Test
    fun `运行中显示最近抓取的豆瓣条目`() {
        emit(
            DoubanSyncProgress(
                isRunning = true,
                stage = DoubanSyncStage.FETCHING_LIST,
                subStage = DoubanSyncSubStage.FETCHING_WISH_LIST,
                recentItems = listOf(
                    DoubanSyncPreviewItem(
                        doubanId = "dune",
                        title = "Dune",
                        status = DoubanMarkStatus.WISH,
                        rating = 4,
                        markedAt = "2024-06-01"
                    )
                )
            )
        )
        setContent()

        composeRule.onNodeWithText("Recently fetched").assertIsDisplayed()
        composeRule.onNodeWithText("Dune").assertIsDisplayed()
        composeRule.onNodeWithText("Wish list").assertIsDisplayed()
        composeRule.onNodeWithText("4/5").assertIsDisplayed()
        composeRule.onNodeWithText("2024-06-01").assertIsDisplayed()
    }

    @Test
    fun `解析阶段显示处理和待处理而不显示最近获取`() {
        emit(
            DoubanSyncProgress(
                isRunning = true,
                current = 1,
                total = 7,
                stage = DoubanSyncStage.PARSING_DATA,
                subStage = DoubanSyncSubStage.FETCHING_DETAIL,
                recentItems = listOf(preview("old", "Old recently fetched")),
                processingItems = listOf(
                    DoubanSyncQueueItem("processing-1", "Processing one"),
                    DoubanSyncQueueItem("processing-2", "Processing two"),
                    DoubanSyncQueueItem("processing-3", "Processing three"),
                    DoubanSyncQueueItem("processing-4", "Processing four")
                ),
                pendingItems = (1..6).map { DoubanSyncQueueItem("pending-$it", "Pending $it") },
                pendingItemCount = 7
            )
        )
        setContent()

        composeRule.onNodeWithText("Processing").assertIsDisplayed()
        composeRule.onNodeWithText("Processing one").assertIsDisplayed()
        composeRule.onNodeWithText("Processing three").assertIsDisplayed()
        composeRule.onAllNodesWithText("Processing four").assertCountEquals(0)
        composeRule.onNodeWithText("Pending").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Pending 5").performScrollTo().assertIsDisplayed()
        composeRule.onAllNodesWithText("Pending 6").assertCountEquals(0)
        composeRule.onNodeWithText("2 more pending").performScrollTo().assertIsDisplayed()
        composeRule.onAllNodesWithText("Recently fetched").assertCountEquals(0)
        composeRule.onAllNodesWithText("Old recently fetched").assertCountEquals(0)
    }

    @Test
    fun `列表阶段显示最近获取而不显示处理队列`() {
        emit(
            DoubanSyncProgress(
                isRunning = true,
                stage = DoubanSyncStage.FETCHING_LIST,
                subStage = DoubanSyncSubStage.FETCHING_WISH_LIST,
                recentItems = listOf(preview("recent", "Recent movie")),
                processingItems = listOf(DoubanSyncQueueItem("processing", "Queue movie")),
                pendingItems = listOf(DoubanSyncQueueItem("pending", "Pending movie")),
                pendingItemCount = 1
            )
        )
        setContent()

        composeRule.onNodeWithText("Recently fetched").assertIsDisplayed()
        composeRule.onNodeWithText("Recent movie").assertIsDisplayed()
        composeRule.onAllNodesWithText("Processing").assertCountEquals(0)
        composeRule.onAllNodesWithText("Pending").assertCountEquals(0)
        composeRule.onAllNodesWithText("Queue movie").assertCountEquals(0)
        composeRule.onAllNodesWithText("Pending movie").assertCountEquals(0)
    }

    @Test
    fun `写入阶段隐藏所有旧列表和队列条目`() {
        emit(
            DoubanSyncProgress(
                isRunning = true,
                stage = DoubanSyncStage.UPDATING_LIST,
                subStage = DoubanSyncSubStage.WRITING_TARGET,
                recentItems = listOf(preview("recent", "Old recent movie")),
                processingItems = listOf(DoubanSyncQueueItem("processing", "Old processing movie")),
                pendingItems = listOf(DoubanSyncQueueItem("pending", "Old pending movie")),
                pendingItemCount = 1
            )
        )
        setContent()

        composeRule.onNodeWithText("Updating list").assertIsDisplayed()
        composeRule.onNodeWithText("Writing Trakt").assertIsDisplayed()
        composeRule.onAllNodesWithText("Recently fetched").assertCountEquals(0)
        composeRule.onAllNodesWithText("Processing").assertCountEquals(0)
        composeRule.onAllNodesWithText("Pending").assertCountEquals(0)
        composeRule.onAllNodesWithText("Old recent movie").assertCountEquals(0)
        composeRule.onAllNodesWithText("Old processing movie").assertCountEquals(0)
        composeRule.onAllNodesWithText("Old pending movie").assertCountEquals(0)
    }

    @Test
    fun `状态变化阶段显示处理和待处理条目`() {
        emit(
            DoubanSyncProgress(
                isRunning = true,
                current = 1,
                total = 3,
                stage = DoubanSyncStage.UPDATING_LIST,
                subStage = DoubanSyncSubStage.STATUS_CHANGES,
                recentItems = listOf(preview("recent", "Old recent movie")),
                processingItems = listOf(DoubanSyncQueueItem("processing", "Changed movie")),
                pendingItems = listOf(DoubanSyncQueueItem("pending", "Next changed movie")),
                pendingItemCount = 1
            )
        )
        setContent()

        composeRule.onNodeWithText("Processing").assertIsDisplayed()
        composeRule.onNodeWithText("Changed movie").assertIsDisplayed()
        composeRule.onNodeWithText("Pending").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Next changed movie").performScrollTo().assertIsDisplayed()
        composeRule.onAllNodesWithText("Recently fetched").assertCountEquals(0)
        composeRule.onAllNodesWithText("Old recent movie").assertCountEquals(0)
    }

    @Test
    fun `上传阶段显示专用阶段文案并隐藏所有旧列表和队列条目`() {
        emit(
            DoubanSyncProgress(
                isRunning = true,
                current = 12,
                total = 461,
                stage = DoubanSyncStage.UPLOADING,
                subStage = DoubanSyncSubStage.UPLOADING_DETAILS,
                etaSeconds = 120,
                recentItems = listOf(preview("recent", "Old recent movie")),
                processingItems = listOf(DoubanSyncQueueItem("processing", "Old processing movie")),
                pendingItems = listOf(DoubanSyncQueueItem("pending", "Old pending movie")),
                pendingItemCount = 1
            )
        )
        setContent()

        composeRule.onNodeWithText("Uploading data").assertIsDisplayed()
        composeRule.onAllNodesWithText("12/461", substring = true).assertCountEquals(0)
        composeRule.onNodeWithText("Uploading details").assertIsDisplayed()
        composeRule.onNodeWithText("about 2 min remaining").assertIsDisplayed()
        composeRule.onAllNodesWithText("Recently fetched").assertCountEquals(0)
        composeRule.onAllNodesWithText("Processing").assertCountEquals(0)
        composeRule.onAllNodesWithText("Pending").assertCountEquals(0)
        composeRule.onAllNodesWithText("Old recent movie").assertCountEquals(0)
        composeRule.onAllNodesWithText("Old processing movie").assertCountEquals(0)
        composeRule.onAllNodesWithText("Old pending movie").assertCountEquals(0)
    }

    @Test
    fun `完成状态显示统计文案`() {
        emit(
            DoubanSyncProgress(
                isComplete = true,
                stage = DoubanSyncStage.COMPLETED,
                successCount = 50,
                failedCount = 5,
                skippedCount = 5,
                cacheHitCount = 10
            )
        )
        setContent()
        // 导入完成后对话框只展示通用处理统计，不再把条目分类为失败项。
        composeRule.onNodeWithText("Success 50 · Skipped 5 · Cached 10").assertIsDisplayed()
    }

    private fun preview(id: String, title: String) = DoubanSyncPreviewItem(
        doubanId = id,
        title = title,
        status = DoubanMarkStatus.WISH,
        rating = 4,
        markedAt = "2024-01-01"
    )

    @Test
    fun `完成状态不显示失败统计列表或导出入口`() {
        val failedItems = listOf(
            DoubanSyncFailure(
                "db-failure",
                "Failed Movie",
                null,
                null,
                null,
                "2024-01-01",
                "url",
                DoubanMarkStatus.WISH,
                FailureReason.DETAIL_FETCH_FAILED,
                1000L,
                1000L,
                1,
                null,
                false,
                null
            )
        )
        emit(
            DoubanSyncProgress(
                isComplete = true,
                stage = DoubanSyncStage.COMPLETED,
                successCount = 50,
                failedCount = 1,
                skippedCount = 5,
                cacheHitCount = 10,
                failedItems = failedItems
            )
        )
        setContent()

        composeRule.onNodeWithText("Success 50 · Skipped 5 · Cached 10").assertIsDisplayed()
        composeRule.onAllNodesWithText("Failed 1", substring = true).assertCountEquals(0)
        composeRule.onAllNodesWithText("Failed:").assertCountEquals(0)
        composeRule.onAllNodesWithText("Failed Movie").assertCountEquals(0)
        composeRule.onAllNodesWithText("Export failures").assertCountEquals(0)
    }

    @Test
    fun `同步异常结果显示错误详情`() {
        emit(
            DoubanSyncProgress(
                isComplete = true,
                stage = DoubanSyncStage.FAILED,
                errorMessage = "Network unavailable"
            )
        )
        setContent()
        composeRule.onNodeWithText("Error: Network error", substring = true).assertIsDisplayed()
    }

    // ==================== 失败项展示 ====================

    @Test
    fun `完成状态_不可恢复组默认展开`() {
        val items = listOf(
            DoubanSyncFailure("db-1", "No IMDb Movie", null, null, null, "2024-01-01",
                "url", DoubanMarkStatus.WISH, FailureReason.NO_IMDB_ID, 1000L, 1000L, 1, null, false, null),
            DoubanSyncFailure("db-2", "Trakt Not Found Movie", null, null, null, "2024-01-01",
                "url", DoubanMarkStatus.WISH, FailureReason.TRAKT_NOT_FOUND, 1000L, 1000L, 1, null, false, null)
        )
        emit(
            DoubanSyncProgress(
                isComplete = true,
                stage = DoubanSyncStage.COMPLETED,
                successCount = 0, failedCount = 2,
                skippedCount = 0, cacheHitCount = 0,
                failedItems = items
            )
        )
        setContent()
        // 完成对话框不再展示失败项分组，所有导入数据由 Watchlist 承载。
        composeRule.onNodeWithText("Non-recoverable (2)").assertDoesNotExist()
        composeRule.onNodeWithText("No IMDb Movie").assertDoesNotExist()
    }

    @Test
    fun `完成状态_可恢复组默认展开`() {
        val items = listOf(
            DoubanSyncFailure("db-3", "Detail Fetch Failed Movie", null, null, null, "2024-01-01",
                "url", DoubanMarkStatus.WISH, FailureReason.DETAIL_FETCH_FAILED, 1000L, 1000L, 1, null, false, null),
            DoubanSyncFailure("db-4", "Write Timeout Movie", null, null, null, "2024-01-01",
                "url", DoubanMarkStatus.WISH, FailureReason.TRAKT_WRITE_TIMEOUT, 1000L, 1000L, 1, null, false, null)
        )
        emit(
            DoubanSyncProgress(
                isComplete = true,
                stage = DoubanSyncStage.COMPLETED,
                successCount = 0, failedCount = 2,
                skippedCount = 0, cacheHitCount = 0,
                failedItems = items
            )
        )
        setContent()
        // 完成对话框不再展示失败项分组，所有导入数据由 Watchlist 承载。
        composeRule.onNodeWithText("Recoverable (2)").assertDoesNotExist()
        composeRule.onNodeWithText("Detail Fetch Failed Movie").assertDoesNotExist()
    }

    @Test
    fun `完成状态_NO_IMDB_ID子组默认折叠_条目不可见`() {
        val items = listOf(
            DoubanSyncFailure("db-noimdb", "No IMDb Movie", null, null, null, "2024-01-01",
                "url", DoubanMarkStatus.WISH, FailureReason.NO_IMDB_ID, 1000L, 1000L, 1, null, false, null)
        )
        emit(
            DoubanSyncProgress(
                isComplete = true,
                stage = DoubanSyncStage.COMPLETED,
                successCount = 0, failedCount = 1,
                skippedCount = 0, cacheHitCount = 0,
                failedItems = items
            )
        )
        setContent()
        // NO_IMDB_ID 子组默认折叠 → 条目不显示
        composeRule.onNodeWithText("No IMDb Movie").assertDoesNotExist()
    }

    @Test
    fun `完成状态_TRAKT_NOT_FOUND子组默认折叠_条目不可见`() {
        val items = listOf(
            DoubanSyncFailure("db-tnf", "Trakt Not Found Movie", null, null, null, "2024-01-01",
                "url", DoubanMarkStatus.WISH, FailureReason.TRAKT_NOT_FOUND, 1000L, 1000L, 1, null, false, null)
        )
        emit(
            DoubanSyncProgress(
                isComplete = true,
                stage = DoubanSyncStage.COMPLETED,
                successCount = 0, failedCount = 1,
                skippedCount = 0, cacheHitCount = 0,
                failedItems = items
            )
        )
        setContent()
        // TRAKT_NOT_FOUND 子组默认折叠 → 条目不显示
        composeRule.onNodeWithText("Trakt Not Found Movie").assertDoesNotExist()
    }
    @Test
    fun `completion summary exposes actionable counts`() {
        emit(
            DoubanSyncProgress(
                isComplete = true,
                stage = DoubanSyncStage.COMPLETED,
                successCount = 20,
                skippedCount = 3,
                cacheHitCount = 4,
                failedCount = 2,
                conflictsFound = 5,
                conflictFixedCount = 4,
                cloudUploadAttempted = true,
                cloudUploadSucceeded = false
            )
        )
        setContent()
        composeRule.onNodeWithText("Sync summary").assertIsDisplayed()
        composeRule.onNodeWithText("Failed, retry available").assertIsDisplayed()
        composeRule.onNodeWithText("Upload needs retry").assertIsDisplayed()
    }
}
