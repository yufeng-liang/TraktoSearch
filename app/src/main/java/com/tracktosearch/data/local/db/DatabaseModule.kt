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

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "tracktosearch.db"
        )
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
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
}
