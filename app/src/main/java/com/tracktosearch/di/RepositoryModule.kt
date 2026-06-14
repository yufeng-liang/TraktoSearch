package com.tracktosearch.di

import com.tracktosearch.data.remote.tmdb.TmdbApiService
import com.tracktosearch.data.remote.trakt.TraktApiService
import com.tracktosearch.data.repository.ResourceRepository
import com.tracktosearch.data.repository.TmdbRepository
import com.tracktosearch.data.repository.TraktRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object RepositoryModule {

    @Provides
    @Singleton
    fun provideTraktRepository(
        traktApiService: TraktApiService
    ): TraktRepository {
        return TraktRepository(traktApiService)
    }

    @Provides
    @Singleton
    fun provideTmdbRepository(
        tmdbApiService: TmdbApiService
    ): TmdbRepository {
        return TmdbRepository(tmdbApiService)
    }
}
