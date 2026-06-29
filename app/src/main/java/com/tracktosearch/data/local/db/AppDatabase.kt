package com.tracktosearch.data.local.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [MediaItemEntity::class, MediaDetailEntity::class, NotificationRecordEntity::class],
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun mediaItemDao(): MediaItemDao
    abstract fun mediaDetailDao(): MediaDetailDao
    abstract fun notificationRecordDao(): NotificationRecordDao
}
