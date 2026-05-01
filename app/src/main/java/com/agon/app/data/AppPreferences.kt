package com.agon.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "agon_prefs")

object AppPreferences {
    private val RELAY_URL_KEY = stringPreferencesKey("relay_url")
    private val WAKE_LOCK_KEY = booleanPreferencesKey("wake_lock")

    fun relayUrl(context: Context): Flow<String> =
        context.dataStore.data.map { it[RELAY_URL_KEY] ?: "" }

    suspend fun setRelayUrl(context: Context, url: String) {
        context.dataStore.edit { it[RELAY_URL_KEY] = url }
    }

    fun wakeLock(context: Context): Flow<Boolean> =
        context.dataStore.data.map { it[WAKE_LOCK_KEY] ?: true }

    suspend fun setWakeLock(context: Context, enabled: Boolean) {
        context.dataStore.edit { it[WAKE_LOCK_KEY] = enabled }
    }
}
