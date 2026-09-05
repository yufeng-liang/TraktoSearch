package com.tracktosearch.data.local.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [MediaItemEntity::class, MediaDetailEntity::class, NotificationRecordEntity::class, DoubanSyncedItem::class, DoubanSyncFailureEntity::class, DoubanSyncPendingItemEntity::class, DoubanSyncRollbackEntity::class, DoubanConsistencyCheckRunEntity::class, DoubanConsistencyCheckTaskEntity::class, DoubanConsistencyConflictEntity::class, UserReviewEntity::class, MarkActionRecordEntity::class, DailyStampEntity::class],
    version = 17,
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
    abstract fun doubanConsistencyCheckDao(): DoubanConsistencyCheckDao
    abstract fun userReviewDao(): UserReviewDao
    abstract fun markActionRecordDao(): MarkActionRecordDao
    abstract fun dailyStampDao(): DailyStampDao
}
