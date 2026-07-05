package com.tracktosearch.data.local.db

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "tracktosearch.db"
        )
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
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
}
