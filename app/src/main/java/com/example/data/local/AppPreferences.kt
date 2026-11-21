package com.example.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "panoguide_prefs")

class AppPreferences(private val context: Context) {

    private object Keys {
        val SIMULATED_MODE = booleanPreferencesKey("simulated_mode")
        val SOUND_ENABLED = booleanPreferencesKey("sound_enabled")
    }

    val simulatedModeFlow: Flow<Boolean> = context.dataStore.data
        .map { prefs -> prefs[Keys.SIMULATED_MODE] ?: false }

    val soundEnabledFlow: Flow<Boolean> = context.dataStore.data
        .map { prefs -> prefs[Keys.SOUND_ENABLED] ?: true }

    suspend fun setSimulatedMode(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.SIMULATED_MODE] = enabled
        }
    }

    suspend fun setSoundEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.SOUND_ENABLED] = enabled
        }
    }
}
