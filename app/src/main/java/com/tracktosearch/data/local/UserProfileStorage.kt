package com.tracktosearch.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.tracktosearch.data.remote.trakt.dto.TraktUserProfileResponse
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.userProfileDataStore: DataStore<Preferences> by preferencesDataStore(name = "user_profile")

@Singleton
class UserProfileStorage @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val key = stringPreferencesKey("profile_json")

    suspend fun getProfile(): TraktUserProfileResponse? {
        val json = context.userProfileDataStore.data.map { it[key] }.first()
        if (json.isNullOrBlank()) return null
        return try {
            kotlinx.serialization.json.Json.decodeFromString<TraktUserProfileResponse>(json)
        } catch (_: Exception) {
            null
        }
    }

    suspend fun saveProfile(profile: TraktUserProfileResponse) {
        val json = kotlinx.serialization.json.Json.encodeToString(TraktUserProfileResponse.serializer(), profile)
        context.userProfileDataStore.edit { it[key] = json }
    }

    suspend fun clear() {
        context.userProfileDataStore.edit { it.remove(key) }
    }
}
