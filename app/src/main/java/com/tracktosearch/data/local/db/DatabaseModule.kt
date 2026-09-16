package com.tracktosearch.data.local.db

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import net.sqlcipher.database.SQLiteDatabase
import net.sqlcipher.database.SupportFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    private val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // v1 → v2: 添加 media_details 表
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS media_details (
                    traktId INTEGER NOT NULL PRIMARY KEY,
                    tmdbId INTEGER NOT NULL,
                    mediaType TEXT NOT NULL,
                    title TEXT NOT NULL,
                    displayTitle TEXT NOT NULL,
                    overview TEXT NOT NULL,
                    posterUrl TEXT,
                    backdropUrl TEXT,
                    year INTEGER,
                    genres TEXT NOT NULL,
                    rating REAL NOT NULL,
                    runtime INTEGER,
                    releaseDate TEXT NOT NULL,
                    cachedAt INTEGER NOT NULL DEFAULT 0
                )
            """.trimIndent())
        }
    }

    private val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // v2 → v3: media_items 添加索引
            db.execSQL("CREATE INDEX IF NOT EXISTS index_media_items_type ON media_items (type)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_media_items_type_listedAt ON media_items (type, listedAt)")
            // notification_records 添加索引
            db.execSQL("CREATE INDEX IF NOT EXISTS index_notification_records_traktId ON notification_records (traktId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_notification_records_traktId_type ON notification_records (traktId, type)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_notification_records_notifiedAt ON notification_records (notifiedAt)")
        }
    }

    private val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // v3 → v4: 新增豆瓣同步记录表
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS `douban_synced_items` (
                    `doubanId` TEXT NOT NULL,
                    `imdbId` TEXT,
                    `traktId` INTEGER,
                    `title` TEXT NOT NULL,
                    `status` TEXT NOT NULL,
                    `rating` INTEGER,
                    `syncedAt` INTEGER NOT NULL,
                    `mediaType` TEXT NOT NULL,
                    PRIMARY KEY(`doubanId`)
                )
            """.trimIndent())
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_douban_synced_items_imdbId` ON `douban_synced_items` (`imdbId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_douban_synced_items_status` ON `douban_synced_items` (`status`)")
        }
    }

    private val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // v4 → v5: 新增豆瓣同步失败项持久化表(用于失败重试)
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS `douban_sync_failures` (
                    `doubanId` TEXT NOT NULL,
                    `title` TEXT NOT NULL,
                    `posterUrl` TEXT,
                    `rating` INTEGER,
                    `comment` TEXT,
                    `markedAt` TEXT NOT NULL,
                    `doubanUrl` TEXT NOT NULL,
                    `status` TEXT NOT NULL,
                    `failureReason` TEXT NOT NULL,
                    `failedAt` INTEGER NOT NULL,
                    `attemptCount` INTEGER NOT NULL DEFAULT 0,
                    PRIMARY KEY(`doubanId`)
                )
            """.trimIndent())
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_douban_sync_failures_status` ON `douban_sync_failures` (`status`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_douban_sync_failures_failureReason` ON `douban_sync_failures` (`failureReason`)")
        }
    }

    private val MIGRATION_5_6 = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // v5 → v6: 新增豆瓣同步列表爬取进度持久化表(用于取消后续传)
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS `douban_sync_pending_items` (
                    `doubanId` TEXT NOT NULL,
                    `title` TEXT NOT NULL,
                    `posterUrl` TEXT,
                    `rating` INTEGER,
                    `comment` TEXT,
                    `markedAt` TEXT NOT NULL,
                    `doubanUrl` TEXT NOT NULL,
                    `status` TEXT NOT NULL,
                    `crawledAt` INTEGER NOT NULL,
                    PRIMARY KEY(`doubanId`)
                )
            """.trimIndent())
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_douban_sync_pending_items_status` ON `douban_sync_pending_items` (`status`)")
        }
    }

    private val MIGRATION_6_7 = object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // v6 → v7: douban_sync_failures 表新增 mediaType 和 subtitle 两个可空列
            // 用于失败项查看页的手动类型标注和资源搜索子标题
            db.execSQL("ALTER TABLE douban_sync_failures ADD COLUMN mediaType TEXT")
            db.execSQL("ALTER TABLE douban_sync_failures ADD COLUMN subtitle TEXT")
        }
    }

    private val MIGRATION_7_8 = object : Migration(7, 8) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // v7 → v8: douban_sync_failures 表新增 mediaTypeCleared 列
            // 用于区分"从未标注"(false)与"用户主动清除标注"(true),防止全局池/自动推断重新填充
            db.execSQL("ALTER TABLE douban_sync_failures ADD COLUMN mediaTypeCleared INTEGER NOT NULL DEFAULT 0")
        }
    }

    private val MIGRATION_8_9 = object : Migration(8, 9) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // v8 → v9: 新增 douban_sync_rollback 表 + douban_sync_failures 新增 updatedAt 列
            // rollback 表: 「完整重写」同步失败时保存被删除的标记,支持下次启动恢复
            // updatedAt 列: mediaType/subtitle/status 等字段最近修改时间,用于云同步时间戳比较
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS douban_sync_rollback (
                    doubanId TEXT NOT NULL PRIMARY KEY,
                    traktId INTEGER NOT NULL,
                    title TEXT NOT NULL,
                    status TEXT NOT NULL,
                    mediaType TEXT NOT NULL,
                    rating INTEGER,
                    rollbackAt INTEGER NOT NULL
                )""".trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS index_douban_sync_rollback_status ON douban_sync_rollback(status)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_douban_sync_rollback_mediaType ON douban_sync_rollback(mediaType)")
            db.execSQL("ALTER TABLE douban_sync_failures ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
        }
    }

    private val MIGRATION_9_10 = object : Migration(9, 10) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // v9 → v10: 新增 user_review 表（本地评分+短评缓存，供详情页优先读取与短评词云复用）
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS user_review (
                    traktId INTEGER NOT NULL PRIMARY KEY,
                    tmdbId INTEGER,
                    imdbId TEXT,
                    mediaType TEXT NOT NULL,
                    title TEXT,
                    year INTEGER,
                    rating REAL,
                    comment TEXT,
                    liked INTEGER,
                    createdAt INTEGER,
                    updatedAt INTEGER,
                    syncedAt INTEGER NOT NULL DEFAULT 0
                )""".trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS index_user_review_mediaType ON user_review(mediaType)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_user_review_tmdbId ON user_review(tmdbId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_user_review_imdbId ON user_review(imdbId)")
        }
    }

    private val MIGRATION_10_11 = object : Migration(10, 11) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // v10 → v11: 新增 mark_action_record 表（App 内标记操作流水）
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS mark_action_record (
                    id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                    traktId INTEGER NOT NULL,
                    tmdbId INTEGER NOT NULL,
                    imdbId TEXT NOT NULL,
                    mediaType TEXT NOT NULL,
                    title TEXT NOT NULL,
                    displayTitle TEXT NOT NULL,
                    posterUrl TEXT,
                    year INTEGER,
                    actionType TEXT NOT NULL,
                    actedAt INTEGER NOT NULL,
                    episodeInfo TEXT
                )""".trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS index_mark_action_record_actionType ON mark_action_record(actionType)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_mark_action_record_actedAt ON mark_action_record(actedAt)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_mark_action_record_mediaType ON mark_action_record(mediaType)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_mark_action_record_traktId ON mark_action_record(traktId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_mark_action_record_actionType_actedAt ON mark_action_record(actionType, actedAt)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_mark_action_record_mediaType_actedAt ON mark_action_record(mediaType, actedAt)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_mark_action_record_actionType_mediaType_actedAt ON mark_action_record(actionType, mediaType, actedAt)")
        }
    }

    private val MIGRATION_11_12 = object : Migration(11, 12) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // v11 → v12: douban_synced_items 扩展为豆瓣独立模式 watchlist 主数据源
            // 新增字段: tmdbId/displayTitle/year/genres/posterUrl/listedAt(可空) + pendingSync(NOT NULL 默认 0)
            db.execSQL("ALTER TABLE douban_synced_items ADD COLUMN tmdbId INTEGER")
            db.execSQL("ALTER TABLE douban_synced_items ADD COLUMN displayTitle TEXT")
            db.execSQL("ALTER TABLE douban_synced_items ADD COLUMN year INTEGER")
            db.execSQL("ALTER TABLE douban_synced_items ADD COLUMN genres TEXT")
            db.execSQL("ALTER TABLE douban_synced_items ADD COLUMN posterUrl TEXT")
            db.execSQL("ALTER TABLE douban_synced_items ADD COLUMN listedAt TEXT")
            db.execSQL("ALTER TABLE douban_synced_items ADD COLUMN pendingSync INTEGER NOT NULL DEFAULT 0")
            // pendingSync 索引: 下次同步重试时按 pendingSync=1 拉取
            db.execSQL("CREATE INDEX IF NOT EXISTS index_douban_synced_items_pendingSync ON douban_synced_items(pendingSync)")
        }
    }

    internal val MIGRATION_12_13 = object : Migration(12, 13) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // v12 -> v13：让同步表保存完整的豆瓣快照，旧数据使用可空列兼容。
            db.execSQL("ALTER TABLE douban_synced_items ADD COLUMN doubanUrl TEXT")
            db.execSQL("ALTER TABLE douban_synced_items ADD COLUMN comment TEXT")
            db.execSQL("ALTER TABLE douban_synced_items ADD COLUMN markedAt TEXT")
            db.execSQL("ALTER TABLE douban_synced_items ADD COLUMN subtitle TEXT")

            // 失败表继续保留供重试；同时把历史失败快照纳入统一 watchlist 数据源。
            db.execSQL(
                """
                INSERT INTO douban_synced_items (
                    doubanId, imdbId, traktId, title, status, rating, syncedAt, mediaType,
                    tmdbId, displayTitle, year, genres, posterUrl, listedAt, pendingSync,
                    doubanUrl, comment, markedAt, subtitle
                )
                SELECT
                    f.doubanId, NULL, NULL, f.title, f.status, f.rating, f.failedAt,
                    COALESCE(f.mediaType, 'other'), NULL, f.title, NULL, NULL,
                    f.posterUrl, f.markedAt, 0, f.doubanUrl, f.comment, f.markedAt, f.subtitle
                FROM douban_sync_failures AS f
                WHERE NOT EXISTS (
                    SELECT 1 FROM douban_synced_items AS s WHERE s.doubanId = f.doubanId
                )
                """.trimIndent()
            )

            // 同一 doubanId 已存在于同步表时，用失败表补齐快照字段，但保留已有的外部 ID 和 TMDB 富化结果。
            db.execSQL(
                """
                UPDATE douban_synced_items AS s
                SET
                    title = (SELECT f.title FROM douban_sync_failures AS f WHERE f.doubanId = s.doubanId),
                    status = (SELECT f.status FROM douban_sync_failures AS f WHERE f.doubanId = s.doubanId),
                    rating = (SELECT f.rating FROM douban_sync_failures AS f WHERE f.doubanId = s.doubanId),
                    posterUrl = COALESCE(
                        s.posterUrl,
                        (SELECT f.posterUrl FROM douban_sync_failures AS f WHERE f.doubanId = s.doubanId)
                    ),
                    listedAt = COALESCE(
                        s.listedAt,
                        (SELECT f.markedAt FROM douban_sync_failures AS f WHERE f.doubanId = s.doubanId)
                    ),
                    doubanUrl = COALESCE(
                        s.doubanUrl,
                        (SELECT f.doubanUrl FROM douban_sync_failures AS f WHERE f.doubanId = s.doubanId)
                    ),
                    comment = COALESCE(
                        s.comment,
                        (SELECT f.comment FROM douban_sync_failures AS f WHERE f.doubanId = s.doubanId)
                    ),
                    markedAt = COALESCE(
                        s.markedAt,
                        (SELECT f.markedAt FROM douban_sync_failures AS f WHERE f.doubanId = s.doubanId)
                    ),
                    subtitle = COALESCE(
                        s.subtitle,
                        (SELECT f.subtitle FROM douban_sync_failures AS f WHERE f.doubanId = s.doubanId)
                    ),
                    displayTitle = COALESCE(
                        s.displayTitle,
                        (SELECT f.title FROM douban_sync_failures AS f WHERE f.doubanId = s.doubanId)
                    ),
                    mediaType = CASE
                        WHEN s.mediaType IS NULL OR s.mediaType = 'other'
                        THEN COALESCE(
                            (SELECT f.mediaType FROM douban_sync_failures AS f WHERE f.doubanId = s.doubanId),
                            'other'
                        )
                        ELSE s.mediaType
                    END,
                    syncedAt = MAX(
                        s.syncedAt,
                        (SELECT f.failedAt FROM douban_sync_failures AS f WHERE f.doubanId = s.doubanId)
                    )
                WHERE EXISTS (
                    SELECT 1 FROM douban_sync_failures AS f WHERE f.doubanId = s.doubanId
                )
                """.trimIndent()
            )
        }
    }

    internal val MIGRATION_13_14 = object : Migration(13, 14) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // v13 -> v14：保存 Trakt 短评 ID，编辑时复用原评论，避免重复发布。
            db.execSQL("ALTER TABLE user_review ADD COLUMN traktCommentId INTEGER")
            db.execSQL("ALTER TABLE user_review ADD COLUMN commentCheckedAt INTEGER")
        }
    }

    internal val MIGRATION_14_15 = object : Migration(14, 15) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // v14 -> v15：新增可恢复一致性检查状态；旧同步、pending、rollback 数据不改写。
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS douban_consistency_check_runs (
                    id INTEGER NOT NULL PRIMARY KEY,
                    accountKey TEXT NOT NULL,
                    dataVersion INTEGER NOT NULL,
                    status TEXT NOT NULL,
                    phase TEXT NOT NULL,
                    current INTEGER NOT NULL,
                    total INTEGER NOT NULL,
                    startedAt INTEGER NOT NULL,
                    updatedAt INTEGER NOT NULL,
                    completedAt INTEGER,
                    invalidReason TEXT
                )""".trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS index_douban_consistency_check_runs_status ON douban_consistency_check_runs(status)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_douban_consistency_check_runs_accountKey_dataVersion ON douban_consistency_check_runs(accountKey, dataVersion)")
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS douban_consistency_check_tasks (
                    runId TEXT NOT NULL,
                    doubanId TEXT NOT NULL,
                    action TEXT NOT NULL,
                    status TEXT NOT NULL,
                    attemptCount INTEGER NOT NULL,
                    errorMessage TEXT,
                    updatedAt INTEGER NOT NULL,
                    PRIMARY KEY(runId, doubanId, action)
                )""".trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS index_douban_consistency_check_tasks_runId_status ON douban_consistency_check_tasks(runId, status)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_douban_consistency_check_tasks_doubanId ON douban_consistency_check_tasks(doubanId)")
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS douban_consistency_conflicts (
                    id TEXT NOT NULL PRIMARY KEY,
                    runId TEXT NOT NULL,
                    doubanId TEXT NOT NULL,
                    title TEXT NOT NULL,
                    doubanStatus TEXT NOT NULL,
                    traktStatus TEXT NOT NULL,
                    conflictType TEXT NOT NULL,
                    resolutionStatus TEXT NOT NULL,
                    errorMessage TEXT,
                    updatedAt INTEGER NOT NULL
                )""".trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS index_douban_consistency_conflicts_runId_resolutionStatus ON douban_consistency_conflicts(runId, resolutionStatus)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_douban_consistency_conflicts_doubanId ON douban_consistency_conflicts(doubanId)")
        }
    }

    internal val MIGRATION_15_16 = object : Migration(15, 16) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // v15 -> v16：新增日签表，只增不改；已有缓存、同步、评论、流水数据一律不动。
            // 升级上来的老用户没有历史日签，日历从升级后第一次打开那天开始长——
            // 历史无法回算（台词库会扩容、海报就绪情况因人而异），补造假数据比空着更糟。
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS daily_stamp (
                    epochDay INTEGER NOT NULL PRIMARY KEY,
                    quoteId TEXT NOT NULL,
                    stampedAt INTEGER NOT NULL
                )""".trimIndent()
            )
        }
    }

    internal val MIGRATION_16_17 = object : Migration(16, 17) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // v16 -> v17：Trakt 的电影/剧集 ID 分属不同命名空间，且想看/已看共用此表。
            // 旧表只以 traktId 为主键，会让同号的其他分类覆盖电影快照；改为 traktId + type。
            db.execSQL(
                """CREATE TABLE media_items_new (
                    traktId INTEGER NOT NULL,
                    tmdbId INTEGER NOT NULL,
                    type TEXT NOT NULL,
                    title TEXT NOT NULL,
                    displayTitle TEXT NOT NULL,
                    year INTEGER,
                    genres TEXT NOT NULL,
                    posterUrl TEXT,
                    imdbId TEXT NOT NULL,
                    traktRating REAL NOT NULL,
                    listedAt TEXT NOT NULL,
                    cachedAt INTEGER NOT NULL,
                    PRIMARY KEY(traktId, type)
                )""".trimIndent()
            )
            db.execSQL(
                """INSERT INTO media_items_new (
                    traktId, tmdbId, type, title, displayTitle, year, genres, posterUrl,
                    imdbId, traktRating, listedAt, cachedAt
                )
                SELECT
                    traktId, tmdbId, type, title, displayTitle, year, genres, posterUrl,
                    imdbId, traktRating, listedAt, cachedAt
                FROM media_items""".trimIndent()
            )
            db.execSQL("DROP TABLE media_items")
            db.execSQL("ALTER TABLE media_items_new RENAME TO media_items")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_media_items_type ON media_items (type)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_media_items_type_listedAt ON media_items (type, listedAt)")
        }
    }

    internal val MIGRATION_17_18 = object : Migration(17, 18) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // v17 -> v18：新增按 friendId 隔离的画像本地镜像和 outbox，不改写既有业务表。
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS ai_profile_settings (
                    friendId TEXT NOT NULL PRIMARY KEY,
                    profileConsent INTEGER NOT NULL DEFAULT 0,
                    behaviorConsent INTEGER NOT NULL DEFAULT 0,
                    personalizationEnabled INTEGER NOT NULL DEFAULT 0,
                    syncEnabled INTEGER NOT NULL DEFAULT 1,
                    shouldAutoImport INTEGER NOT NULL DEFAULT 0,
                    updatedAt INTEGER NOT NULL,
                    clearedAt INTEGER
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS index_ai_profile_settings_updatedAt ON ai_profile_settings(updatedAt)")

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS ai_profile_media (
                    friendId TEXT NOT NULL,
                    mediaKey TEXT NOT NULL,
                    mediaType TEXT NOT NULL,
                    tmdbId INTEGER,
                    traktId INTEGER,
                    imdbId TEXT,
                    doubanId TEXT,
                    title TEXT NOT NULL,
                    year INTEGER,
                    genresJson TEXT NOT NULL,
                    publicRating REAL,
                    userRating REAL,
                    userComment TEXT,
                    watchedAt INTEGER,
                    isWatched INTEGER NOT NULL DEFAULT 0,
                    isWatchlist INTEGER NOT NULL DEFAULT 0,
                    updatedAt INTEGER NOT NULL,
                    PRIMARY KEY(friendId, mediaKey)
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS index_ai_profile_media_friendId ON ai_profile_media(friendId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_ai_profile_media_friendId_mediaType ON ai_profile_media(friendId, mediaType)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_ai_profile_media_friendId_updatedAt ON ai_profile_media(friendId, updatedAt)")

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS ai_profile_media_source (
                    friendId TEXT NOT NULL,
                    mediaKey TEXT NOT NULL,
                    source TEXT NOT NULL,
                    sourceId TEXT NOT NULL,
                    isTombstone INTEGER NOT NULL DEFAULT 0,
                    updatedAt INTEGER NOT NULL,
                    PRIMARY KEY(friendId, mediaKey, source)
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS index_ai_profile_media_source_friendId ON ai_profile_media_source(friendId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_ai_profile_media_source_friendId_mediaKey ON ai_profile_media_source(friendId, mediaKey)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_ai_profile_media_source_friendId_sourceId ON ai_profile_media_source(friendId, sourceId)")

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS ai_profile_behavior_daily (
                    friendId TEXT NOT NULL,
                    mediaKey TEXT NOT NULL,
                    day TEXT NOT NULL,
                    dwellIgnoredCount INTEGER NOT NULL DEFAULT 0,
                    dwell10To30Count INTEGER NOT NULL DEFAULT 0,
                    dwell30To120Count INTEGER NOT NULL DEFAULT 0,
                    dwell120PlusCount INTEGER NOT NULL DEFAULT 0,
                    searchClickCount INTEGER NOT NULL DEFAULT 0,
                    episodeStartCount INTEGER NOT NULL DEFAULT 0,
                    episodeCompleteCount INTEGER NOT NULL DEFAULT 0,
                    progress25Count INTEGER NOT NULL DEFAULT 0,
                    progress50Count INTEGER NOT NULL DEFAULT 0,
                    progress75Count INTEGER NOT NULL DEFAULT 0,
                    progress100Count INTEGER NOT NULL DEFAULT 0,
                    updatedAt INTEGER NOT NULL,
                    PRIMARY KEY(friendId, mediaKey, day)
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS index_ai_profile_behavior_daily_friendId ON ai_profile_behavior_daily(friendId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_ai_profile_behavior_daily_friendId_day ON ai_profile_behavior_daily(friendId, day)")

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS ai_profile_outbox (
                    friendId TEXT NOT NULL,
                    batchId TEXT NOT NULL,
                    schemaVersion INTEGER NOT NULL DEFAULT 1,
                    payloadJson TEXT NOT NULL,
                    payloadDigest TEXT NOT NULL,
                    status TEXT NOT NULL DEFAULT 'PENDING',
                    attemptCount INTEGER NOT NULL DEFAULT 0,
                    nextAttemptAt INTEGER NOT NULL DEFAULT 0,
                    lastError TEXT,
                    createdAt INTEGER NOT NULL,
                    updatedAt INTEGER NOT NULL,
                    PRIMARY KEY(friendId, batchId)
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS index_ai_profile_outbox_friendId ON ai_profile_outbox(friendId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_ai_profile_outbox_friendId_status ON ai_profile_outbox(friendId, status)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_ai_profile_outbox_friendId_createdAt ON ai_profile_outbox(friendId, createdAt)")

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS ai_profile_snapshot (
                    friendId TEXT NOT NULL PRIMARY KEY,
                    profileVersion INTEGER NOT NULL DEFAULT 0,
                    status TEXT NOT NULL DEFAULT 'EMPTY',
                    summaryJson TEXT NOT NULL DEFAULT '{}',
                    generatedAt INTEGER,
                    updatedAt INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS index_ai_profile_snapshot_updatedAt ON ai_profile_snapshot(updatedAt)")
        }
    }

    internal val MIGRATION_18_19 = object : Migration(18, 19) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // v18 -> v19：新增公开影视元数据本地镜像，供看单批量摘要和详情首帧复用。
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS media_metadata (
                    mediaKey TEXT NOT NULL PRIMARY KEY,
                    mediaType TEXT NOT NULL,
                    tmdbId INTEGER NOT NULL,
                    locale TEXT NOT NULL,
                    summaryJson TEXT,
                    detailJson TEXT,
                    schemaVersion INTEGER NOT NULL,
                    summaryRefreshedAt INTEGER NOT NULL,
                    detailRefreshedAt INTEGER NOT NULL,
                    updatedAt INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS index_media_metadata_tmdbId ON media_metadata(tmdbId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_media_metadata_updatedAt ON media_metadata(updatedAt)")
        }
    }

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase {
        // 首次从明文库迁移到 SQLCipher：删除旧明文库（数据可从云端/Trakt 重新同步）
        if (DatabaseKeyProvider.legacyPlaintextDbExists(context) && !DatabaseKeyProvider.hasCipherKey(context)) {
            DatabaseKeyProvider.deleteLegacyPlaintextDb(context)
        }

        // 加载 SQLCipher native 库（需在创建 SupportFactory 前调用）
        SQLiteDatabase.loadLibs(context)

        val passphrase = DatabaseKeyProvider.getPassphrase(context)
        // SupportFactory 用 SQLCipher 密钥打开加密数据库
        val factory: SupportSQLiteOpenHelper.Factory = SupportFactory(passphrase)

        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "tracktosearch.db"
        )
            .openHelperFactory(factory)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17, MIGRATION_17_18, MIGRATION_18_19)
            .build()
    }

    @Provides
    @Singleton
    fun provideMediaItemDao(db: AppDatabase): MediaItemDao = db.mediaItemDao()

    @Provides
    @Singleton
    fun provideMediaDetailDao(db: AppDatabase): MediaDetailDao = db.mediaDetailDao()

    @Provides
    @Singleton
    fun provideNotificationRecordDao(db: AppDatabase): NotificationRecordDao = db.notificationRecordDao()

    @Provides
    @Singleton
    fun provideDoubanSyncedItemDao(db: AppDatabase): DoubanSyncedItemDao = db.doubanSyncedItemDao()

    @Provides
    @Singleton
    fun provideDoubanSyncFailureDao(db: AppDatabase): DoubanSyncFailureDao = db.doubanSyncFailureDao()

    @Provides
    @Singleton
    fun provideDoubanSyncPendingItemDao(db: AppDatabase): DoubanSyncPendingItemDao = db.doubanSyncPendingItemDao()

    @Provides
    @Singleton
    fun provideDoubanSyncRollbackDao(db: AppDatabase): DoubanSyncRollbackDao = db.doubanSyncRollbackDao()

    @Provides
    @Singleton
    fun provideDoubanConsistencyCheckDao(db: AppDatabase): DoubanConsistencyCheckDao = db.doubanConsistencyCheckDao()

    @Provides
    @Singleton
    fun provideUserReviewDao(db: AppDatabase): UserReviewDao = db.userReviewDao()

    @Provides
    @Singleton
    fun provideMarkActionRecordDao(db: AppDatabase): MarkActionRecordDao = db.markActionRecordDao()

    @Provides
    @Singleton
    fun provideDailyStampDao(db: AppDatabase): DailyStampDao = db.dailyStampDao()

    @Provides
    @Singleton
    fun provideAiProfileDao(db: AppDatabase): AiProfileDao = db.aiProfileDao()

    @Provides
    @Singleton
    fun provideMediaMetadataDao(db: AppDatabase): MediaMetadataDao = db.mediaMetadataDao()
}
