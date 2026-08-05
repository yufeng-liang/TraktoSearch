package com.tracktosearch.data.local.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [MediaItemEntity::class, MediaDetailEntity::class, NotificationRecordEntity::class, DoubanSyncedItem::class, DoubanSyncFailureEntity::class, DoubanSyncPendingItemEntity::class, DoubanSyncRollbackEntity::class, UserReviewEntity::class, MarkActionRecordEntity::class],
    version = 13,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun mediaItemDao(): MediaItemDao
    abstract fun mediaDetailDao(): MediaDetailDao
    abstract fun notificationRecordDao(): NotificationRecordDao
    abstract fun doubanSyncedItemDao(): DoubanSyncedItemDao
    abstract fun doubanSyncFailureDao(): DoubanSyncFailureDao
    abstract fun doubanSyncPendingItemDao(): DoubanSyncPendingItemDao
    abstract fun doubanSyncRollbackDao(): DoubanSyncRollbackDao
    abstract fun userReviewDao(): UserReviewDao
    abstract fun markActionRecordDao(): MarkActionRecordDao
}
