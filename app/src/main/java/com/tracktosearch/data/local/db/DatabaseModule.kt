package com.tracktosearch.data.local.db

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase {
        return androidx.room.Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "tracktosearch.db"
        ).fallbackToDestructiveMigration().build()
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
