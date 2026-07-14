package com.tracktosearch.data.repository

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.google.common.truth.Truth.assertThat
import com.tracktosearch.data.local.db.DoubanSyncFailureDao
import com.tracktosearch.data.local.db.DoubanSyncFailureEntity
import com.tracktosearch.data.remote.douban.DoubanMarkStatus
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * DoubanFailureExporter 单元测试。
 *
 * 覆盖点：
 * - exportToFile：空列表返回 null、有数据返回 Uri 且文件含正确 JSON
 * - exportFromList：空列表返回 null、有数据返回 Uri
 * - exportFromLocal：空列表返回 null、有数据返回 Uri
 * - JSON 往返一致性：exportToFile→importFromFile、exportFromList→importFromFile 字段全部一致
 * - importFromFile：有效 JSON、损坏 JSON、空文件
 * - importToRoom：有效数据调用 dao.insertAll 返回 Success、损坏 JSON 返回 InvalidFormat、空列表返回 Empty
 *
 * 由于 FailureDto 和 ExportPayload 是私有类,无法直接构造,测试通过完整的 export→import 往返
 * 验证 JSON 序列化逻辑。用 Robolectric 的真实 Context 测试文件 I/O 和 FileProvider。
 *
 * 注意:DoubanSyncFailureEntity 有 updatedAt 和 mediaTypeCleared 字段,但 FailureDto 未包含,
 * 导出时这两个字段会丢失(设计选择:updatedAt 是本地时间戳,mediaTypeCleared 是本地状态)。
 * 往返测试只验证 FailureDto 包含的字段。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DoubanFailureExporterTest {

    private val doubanSyncFailureDao = mockk<DoubanSyncFailureDao>(relaxed = true)
    // 与生产环境 NetworkModule.provideJson() 配置一致(encodeDefaults = true 确保 version/source 字段被序列化)
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        encodeDefaults = true
    }
    private val exporter = DoubanFailureExporter(doubanSyncFailureDao, json)
    private lateinit var context: Context

    @Before
    fun setUp() {
        // 清除前序测试的 stub 和调用记录,确保 coVerify(exactly = 0) 不受干扰
        clearMocks(doubanSyncFailureDao)
        // 获取 Robolectric 真实 Context(项目未配置 androidx.test:core,改用 RuntimeEnvironment)
        context = RuntimeEnvironment.getApplication()
        // 清理上次测试可能遗留的 share 目录,避免文件名碰撞
        File(context.cacheDir, "share").deleteRecursively()
        // FileProvider 在 Robolectric 下未正确初始化,getUriForFile 会抛 IllegalArgumentException。
        // mock 为返回指向实际文件的 file:// Uri,使后续 importFromFile 能通过 contentResolver 直接打开。
        mockkStatic(FileProvider::class)
        every { FileProvider.getUriForFile(any(), any(), any<File>()) } answers {
            Uri.fromFile(arg(2))
        }
    }

    @After
    fun tearDown() {
        // 清理 FileProvider 的静态 mock,避免影响其他测试类
        unmockkStatic(FileProvider::class)
    }

    // ============================================================
    // 辅助函数
    // ============================================================

    /** 构造本地失败项 entity,所有字段可定制 */
    private fun buildFailureEntity(
        doubanId: String = "db-001",
        title: String = "测试电影",
        posterUrl: String? = "https://img.example.com/p.jpg",
        rating: Int? = 5,
        comment: String? = "好片",
        markedAt: String = "2024-01-15",
        doubanUrl: String = "https://book.douban.com/subject/db-001/",
        status: String = "wish",
        failureReason: String = "TRAKT_WRITE_FAILED",
        failedAt: Long = 1000L,
        updatedAt: Long = 1000L,
        attemptCount: Int = 1,
        mediaType: String? = "movie",
        mediaTypeCleared: Boolean = false,
        subtitle: String? = null
    ): DoubanSyncFailureEntity = DoubanSyncFailureEntity(
        doubanId = doubanId,
        title = title,
        posterUrl = posterUrl,
        rating = rating,
        comment = comment,
        markedAt = markedAt,
        doubanUrl = doubanUrl,
        status = status,
        failureReason = failureReason,
        failedAt = failedAt,
        updatedAt = updatedAt,
        attemptCount = attemptCount,
        mediaType = mediaType,
        mediaTypeCleared = mediaTypeCleared,
        subtitle = subtitle
    )

    /** 构造 DoubanSyncFailure,所有字段可定制 */
    private fun buildFailure(
        doubanId: String = "db-001",
        title: String = "测试电影",
        posterUrl: String? = "https://img.example.com/p.jpg",
        rating: Int? = 5,
        comment: String? = "好片",
        markedAt: String = "2024-01-15",
        doubanUrl: String = "https://book.douban.com/subject/db-001/",
        status: DoubanMarkStatus = DoubanMarkStatus.WISH,
        failureReason: FailureReason = FailureReason.TRAKT_WRITE_FAILED,
        failedAt: Long = 1000L,
        updatedAt: Long = 1000L,
        attemptCount: Int = 1,
        mediaType: String? = "movie",
        mediaTypeCleared: Boolean = false,
        subtitle: String? = null
    ): DoubanSyncFailure = DoubanSyncFailure(
        doubanId = doubanId,
        title = title,
        posterUrl = posterUrl,
        rating = rating,
        comment = comment,
        markedAt = markedAt,
        doubanUrl = doubanUrl,
        status = status,
        failureReason = failureReason,
        failedAt = failedAt,
        updatedAt = updatedAt,
        attemptCount = attemptCount,
        mediaType = mediaType,
        mediaTypeCleared = mediaTypeCleared,
        subtitle = subtitle
    )

    /** 写入 JSON 字符串到 cacheDir 下的文件,返回 file:// Uri 用于 importFromFile */
    private fun writeJsonFile(jsonStr: String, fileName: String = "test-import.json"): Uri {
        val file = File(context.cacheDir, fileName)
        file.parentFile?.mkdirs()
        file.writeText(jsonStr, Charsets.UTF_8)
        return Uri.fromFile(file)
    }

    // ============================================================
    // exportToFile 测试
    // ============================================================

    @Test
    fun exportToFile_空列表_returnsNull() = runTest {
        coEvery { doubanSyncFailureDao.getAll() } returns emptyList()

        val result = exporter.exportToFile(context)

        assertThat(result).isNull()
    }

    @Test
    fun exportToFile_有数据_返回Uri且文件含正确JSON() = runTest {
        val entity = buildFailureEntity(
            doubanId = "db-001",
            title = "测试电影",
            mediaType = "movie",
            subtitle = "测试子标题"
        )
        coEvery { doubanSyncFailureDao.getAll() } returns listOf(entity)

        val result = exporter.exportToFile(context)

        // 验证返回非空 Uri(FileProvider 在 Robolectric 下应可正常工作)
        assertThat(result).isNotNull()

        // 验证 cacheDir/share/ 下的文件含正确 JSON
        val shareDir = File(context.cacheDir, "share")
        val jsonFiles = shareDir.listFiles { _, name -> name.endsWith(".json") }
        assertThat(jsonFiles).isNotNull()
        assertThat(jsonFiles!!.size).isGreaterThan(0)

        val jsonStr = jsonFiles[0].readText(Charsets.UTF_8)
        // 验证 ExportPayload 关键字段
        assertThat(jsonStr).contains("version")
        assertThat(jsonStr).contains("exportedAt")
        assertThat(jsonStr).contains("source")
        assertThat(jsonStr).contains("TraktToSearch")
        assertThat(jsonStr).contains("totalFailures")
        assertThat(jsonStr).contains("failures")
        // 验证 FailureDto 字段
        assertThat(jsonStr).contains("db-001")
        assertThat(jsonStr).contains("测试电影")
        assertThat(jsonStr).contains("TRAKT_WRITE_FAILED")
        assertThat(jsonStr).contains("wish")
        assertThat(jsonStr).contains("测试子标题")
    }

    // ============================================================
    // exportFromList 测试
    // ============================================================

    @Test
    fun exportFromList_空列表_returnsNull() = runTest {
        val result = exporter.exportFromList(context, emptyList())

        assertThat(result).isNull()
    }

    @Test
    fun exportFromList_有数据_返回Uri() = runTest {
        val failure = buildFailure(
            doubanId = "list-001",
            title = "列表导出测试"
        )

        val result = exporter.exportFromList(context, listOf(failure))

        assertThat(result).isNotNull()
    }

    // ============================================================
    // exportFromLocal 测试
    // ============================================================

    @Test
    fun exportFromLocal_空列表_returnsNull() = runTest {
        coEvery { doubanSyncFailureDao.getAll() } returns emptyList()

        val result = exporter.exportFromLocal(context)

        assertThat(result).isNull()
    }

    @Test
    fun exportFromLocal_有数据_返回Uri() = runTest {
        val entity = buildFailureEntity(doubanId = "local-001")
        coEvery { doubanSyncFailureDao.getAll() } returns listOf(entity)

        val result = exporter.exportFromLocal(context)

        assertThat(result).isNotNull()
    }

    // ============================================================
    // JSON 往返一致性测试(核心)
    // ============================================================

    @Test
    fun exportThenImport_往返字段一致() = runTest {
        val entity1 = buildFailureEntity(
            doubanId = "rt-001",
            title = "往返测试电影",
            posterUrl = "https://img.example.com/rt.jpg",
            rating = 5,
            comment = "往返测试评论",
            markedAt = "2024-03-20",
            doubanUrl = "https://book.douban.com/subject/rt-001/",
            status = "collect",
            failureReason = "TRAKT_WRITE_FAILED",
            failedAt = 1000L,
            attemptCount = 2,
            mediaType = "show",
            subtitle = "Test Show"
        )
        val entity2 = buildFailureEntity(
            doubanId = "rt-002",
            title = "第二个条目",
            posterUrl = null,
            rating = null,
            comment = null,
            status = "wish",
            failureReason = "NO_IMDB_ID",
            failedAt = 2000L,
            attemptCount = 0,
            mediaType = null,
            subtitle = null
        )
        coEvery { doubanSyncFailureDao.getAll() } returns listOf(entity1, entity2)

        // 导出
        val uri = exporter.exportToFile(context)
        assertThat(uri).isNotNull()

        // 导入
        val imported = exporter.importFromFile(context, uri!!)
        assertThat(imported).isNotNull()
        assertThat(imported).hasSize(2)

        // 验证第一条字段(全字段)
        val first = imported!![0]
        assertThat(first.doubanId).isEqualTo("rt-001")
        assertThat(first.title).isEqualTo("往返测试电影")
        assertThat(first.posterUrl).isEqualTo("https://img.example.com/rt.jpg")
        assertThat(first.rating).isEqualTo(5)
        assertThat(first.comment).isEqualTo("往返测试评论")
        assertThat(first.markedAt).isEqualTo("2024-03-20")
        assertThat(first.doubanUrl).isEqualTo("https://book.douban.com/subject/rt-001/")
        assertThat(first.status).isEqualTo(DoubanMarkStatus.COLLECT)
        assertThat(first.failureReason).isEqualTo(FailureReason.TRAKT_WRITE_FAILED)
        assertThat(first.failedAt).isEqualTo(1000L)
        assertThat(first.attemptCount).isEqualTo(2)
        assertThat(first.mediaType).isEqualTo("show")
        assertThat(first.subtitle).isEqualTo("Test Show")

        // 验证第二条字段(nullable 字段为 null 的场景)
        val second = imported[1]
        assertThat(second.doubanId).isEqualTo("rt-002")
        assertThat(second.title).isEqualTo("第二个条目")
        assertThat(second.posterUrl).isNull()
        assertThat(second.rating).isNull()
        assertThat(second.comment).isNull()
        assertThat(second.status).isEqualTo(DoubanMarkStatus.WISH)
        assertThat(second.failureReason).isEqualTo(FailureReason.NO_IMDB_ID)
        assertThat(second.failedAt).isEqualTo(2000L)
        assertThat(second.attemptCount).isEqualTo(0)
        assertThat(second.mediaType).isNull()
        assertThat(second.subtitle).isNull()
    }

    @Test
    fun exportFromListThenImport_往返字段一致() = runTest {
        val failure1 = buildFailure(
            doubanId = "list-rt-001",
            title = "列表往返测试",
            posterUrl = "https://img.example.com/list.jpg",
            rating = 4,
            comment = "列表导出评论",
            markedAt = "2024-05-01",
            doubanUrl = "https://movie.douban.com/subject/list-rt-001/",
            status = DoubanMarkStatus.COLLECT,
            failureReason = FailureReason.DETAIL_FETCH_FAILED,
            failedAt = 3000L,
            attemptCount = 3,
            mediaType = "movie",
            subtitle = "List Test"
        )
        val failure2 = buildFailure(
            doubanId = "list-rt-002",
            title = "全 null 条目",
            posterUrl = null,
            rating = null,
            comment = null,
            status = DoubanMarkStatus.WISH,
            failureReason = FailureReason.TRAKT_NOT_FOUND,
            failedAt = 4000L,
            attemptCount = 1,
            mediaType = null,
            subtitle = null
        )

        // 用 DoubanSyncFailure 列表导出
        val uri = exporter.exportFromList(context, listOf(failure1, failure2))
        assertThat(uri).isNotNull()

        // 导入回来
        val imported = exporter.importFromFile(context, uri!!)
        assertThat(imported).isNotNull()
        assertThat(imported).hasSize(2)

        // 验证第一条字段
        val first = imported!![0]
        assertThat(first.doubanId).isEqualTo("list-rt-001")
        assertThat(first.title).isEqualTo("列表往返测试")
        assertThat(first.posterUrl).isEqualTo("https://img.example.com/list.jpg")
        assertThat(first.rating).isEqualTo(4)
        assertThat(first.comment).isEqualTo("列表导出评论")
        assertThat(first.markedAt).isEqualTo("2024-05-01")
        assertThat(first.doubanUrl).isEqualTo("https://movie.douban.com/subject/list-rt-001/")
        assertThat(first.status).isEqualTo(DoubanMarkStatus.COLLECT)
        assertThat(first.failureReason).isEqualTo(FailureReason.DETAIL_FETCH_FAILED)
        assertThat(first.failedAt).isEqualTo(3000L)
        assertThat(first.attemptCount).isEqualTo(3)
        assertThat(first.mediaType).isEqualTo("movie")
        assertThat(first.subtitle).isEqualTo("List Test")

        // 验证第二条字段(nullable 字段为 null)
        val second = imported[1]
        assertThat(second.doubanId).isEqualTo("list-rt-002")
        assertThat(second.title).isEqualTo("全 null 条目")
        assertThat(second.posterUrl).isNull()
        assertThat(second.rating).isNull()
        assertThat(second.comment).isNull()
        assertThat(second.status).isEqualTo(DoubanMarkStatus.WISH)
        assertThat(second.failureReason).isEqualTo(FailureReason.TRAKT_NOT_FOUND)
        assertThat(second.failedAt).isEqualTo(4000L)
        assertThat(second.attemptCount).isEqualTo(1)
        assertThat(second.mediaType).isNull()
        assertThat(second.subtitle).isNull()
    }

    // ============================================================
    // importFromFile 测试
    // ============================================================

    @Test
    fun importFromFile_有效JSON_returnsDoubanSyncFailure列表() = runTest {
        // 通过 exportFromList 生成有效 JSON,确保格式与被测类一致
        val failure = buildFailure(doubanId = "imp-001", title = "导入测试")
        val exportUri = exporter.exportFromList(context, listOf(failure))
        assertThat(exportUri).isNotNull()

        // 从生成的 Uri 导入
        val result = exporter.importFromFile(context, exportUri!!)

        assertThat(result).isNotNull()
        assertThat(result).hasSize(1)
        assertThat(result!![0].doubanId).isEqualTo("imp-001")
        assertThat(result[0].title).isEqualTo("导入测试")
    }

    @Test
    fun importFromFile_损坏JSON_returnsNull() = runTest {
        val uri = writeJsonFile("这不是有效的 JSON { broken", "broken.json")

        val result = exporter.importFromFile(context, uri)

        // 损坏 JSON 反序列化抛异常,被 catch 返回 null,不崩溃
        assertThat(result).isNull()
    }

    @Test
    fun importFromFile_空文件_returnsNull() = runTest {
        val uri = writeJsonFile("", "empty.json")

        val result = exporter.importFromFile(context, uri)

        // 空字符串反序列化抛异常,返回 null
        assertThat(result).isNull()
    }

    // ============================================================
    // importToRoom 测试
    // ============================================================

    @Test
    fun importToRoom_有效数据_调用DaoInsertAll并返回Success() = runTest {
        val failure1 = buildFailure(doubanId = "room-001", title = "Room 测试 1")
        val failure2 = buildFailure(doubanId = "room-002", title = "Room 测试 2")
        val uri = exporter.exportFromList(context, listOf(failure1, failure2))
        assertThat(uri).isNotNull()

        val insertSlot = slot<List<DoubanSyncFailureEntity>>()
        coEvery { doubanSyncFailureDao.insertAll(capture(insertSlot)) } returns Unit

        val result = exporter.importToRoom(context, uri!!)

        assertThat(result).isInstanceOf(ImportResult.Success::class.java)
        assertThat((result as ImportResult.Success).count).isEqualTo(2)
        coVerify(exactly = 1) { doubanSyncFailureDao.insertAll(any()) }
        // 验证传入 dao 的 entity 字段
        assertThat(insertSlot.captured).hasSize(2)
        assertThat(insertSlot.captured[0].doubanId).isEqualTo("room-001")
        assertThat(insertSlot.captured[1].doubanId).isEqualTo("room-002")
    }

    @Test
    fun importToRoom_损坏JSON_returnsInvalidFormat() = runTest {
        val uri = writeJsonFile("broken json {", "broken-room.json")

        val result = exporter.importToRoom(context, uri)

        assertThat(result).isEqualTo(ImportResult.InvalidFormat)
        coVerify(exactly = 0) { doubanSyncFailureDao.insertAll(any()) }
    }

    @Test
    fun importToRoom_空失败项列表_returnsEmpty() = runTest {
        // 构造有效 JSON 但 failures 为空列表
        val emptyPayloadJson = """
            {
                "version": 1,
                "exportedAt": "2024-01-01T00:00:00Z",
                "source": "TraktToSearch",
                "totalFailures": 0,
                "failures": []
            }
        """.trimIndent()
        val uri = writeJsonFile(emptyPayloadJson, "empty-failures.json")

        val result = exporter.importToRoom(context, uri)

        assertThat(result).isEqualTo(ImportResult.Empty)
        coVerify(exactly = 0) { doubanSyncFailureDao.insertAll(any()) }
    }
}
