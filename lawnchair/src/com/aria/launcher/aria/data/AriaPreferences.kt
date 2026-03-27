// Copyright (c) 2026 Donovon Simpson. All rights reserved. See LICENSE-ARIA.md
package com.aria.launcher.aria.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.ariaPrefsStore by preferencesDataStore(name = "aria_preferences")

@Singleton
class AriaPreferences @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    val homeWifiSsid: Flow<String?> = context.ariaPrefsStore.data
        .map { it[KEY_HOME_WIFI] }

    val workWifiSsid: Flow<String?> = context.ariaPrefsStore.data
        .map { it[KEY_WORK_WIFI] }

    val isRightHanded: Flow<Boolean> = context.ariaPrefsStore.data
        .map { it[KEY_RIGHT_HANDED] ?: true }

    val onboardingVersion: Flow<Int> = context.ariaPrefsStore.data
        .map { it[KEY_ONBOARDING_VERSION] ?: 0 }

    val cardFeedEnabled: Flow<Boolean> = context.ariaPrefsStore.data
        .map { it[KEY_CARD_FEED_ENABLED] ?: true }

    val llmRankingEnabled: Flow<Boolean> = context.ariaPrefsStore.data
        .map { it[KEY_LLM_RANKING_ENABLED] ?: false }

    /** Chat conversation timeout in minutes. 0 = never auto-clear. Default 15 min. */
    val chatTimeoutMinutes: Flow<Int> = context.ariaPrefsStore.data
        .map { it[KEY_CHAT_TIMEOUT_MINUTES] ?: DEFAULT_CHAT_TIMEOUT_MINUTES }

    /** Timestamp of the last chat interaction (message sent or chat opened). */
    val chatLastInteraction: Flow<Long> = context.ariaPrefsStore.data
        .map { it[KEY_CHAT_LAST_INTERACTION] ?: 0L }

    /** Whether user memory extraction is enabled. */
    val memoryEnabled: Flow<Boolean> = context.ariaPrefsStore.data
        .map { it[KEY_MEMORY_ENABLED] ?: true }

    suspend fun getHomeWifiSsid(): String? = context.ariaPrefsStore.data.first()[KEY_HOME_WIFI]
    suspend fun getWorkWifiSsid(): String? = context.ariaPrefsStore.data.first()[KEY_WORK_WIFI]
    suspend fun getIsRightHanded(): Boolean = context.ariaPrefsStore.data.first()[KEY_RIGHT_HANDED] ?: true

    suspend fun setHomeWifiSsid(ssid: String?) {
        context.ariaPrefsStore.edit { prefs ->
            if (ssid != null) prefs[KEY_HOME_WIFI] = ssid else prefs.remove(KEY_HOME_WIFI)
        }
    }

    suspend fun setWorkWifiSsid(ssid: String?) {
        context.ariaPrefsStore.edit { prefs ->
            if (ssid != null) prefs[KEY_WORK_WIFI] = ssid else prefs.remove(KEY_WORK_WIFI)
        }
    }

    suspend fun setRightHanded(rightHanded: Boolean) {
        context.ariaPrefsStore.edit { it[KEY_RIGHT_HANDED] = rightHanded }
    }

    suspend fun setOnboardingVersion(version: Int) {
        context.ariaPrefsStore.edit { it[KEY_ONBOARDING_VERSION] = version }
    }

    suspend fun setCardFeedEnabled(enabled: Boolean) {
        context.ariaPrefsStore.edit { it[KEY_CARD_FEED_ENABLED] = enabled }
    }

    suspend fun setLlmRankingEnabled(enabled: Boolean) {
        context.ariaPrefsStore.edit { it[KEY_LLM_RANKING_ENABLED] = enabled }
    }

    suspend fun setChatTimeoutMinutes(minutes: Int) {
        context.ariaPrefsStore.edit { it[KEY_CHAT_TIMEOUT_MINUTES] = minutes }
    }

    suspend fun setChatLastInteraction(timestamp: Long = System.currentTimeMillis()) {
        context.ariaPrefsStore.edit { it[KEY_CHAT_LAST_INTERACTION] = timestamp }
    }

    suspend fun setMemoryEnabled(enabled: Boolean) {
        context.ariaPrefsStore.edit { it[KEY_MEMORY_ENABLED] = enabled }
    }

    suspend fun getDefaultLatitude(): Double? = context.ariaPrefsStore.data.first()[KEY_DEFAULT_LAT]?.toDoubleOrNull()

    suspend fun getDefaultLongitude(): Double? = context.ariaPrefsStore.data.first()[KEY_DEFAULT_LNG]?.toDoubleOrNull()

    suspend fun getDefaultLocationTimestamp(): Long =
        context.ariaPrefsStore.data.first()[KEY_DEFAULT_LOCATION_TS] ?: 0L

    suspend fun setDefaultLocation(lat: Double, lng: Double) {
        context.ariaPrefsStore.edit {
            it[KEY_DEFAULT_LAT] = lat.toString()
            it[KEY_DEFAULT_LNG] = lng.toString()
            it[KEY_DEFAULT_LOCATION_TS] = System.currentTimeMillis()
        }
    }

    suspend fun isBootstrapDone(): Boolean = context.ariaPrefsStore.data.first()[KEY_BOOTSTRAP_DONE] ?: false

    suspend fun setBootstrapDone() {
        context.ariaPrefsStore.edit { it[KEY_BOOTSTRAP_DONE] = true }
    }

    companion object {
        const val CURRENT_ONBOARDING_VERSION = 2
        const val DEFAULT_CHAT_TIMEOUT_MINUTES = 15

        private val KEY_HOME_WIFI = stringPreferencesKey("home_wifi_ssid")
        private val KEY_WORK_WIFI = stringPreferencesKey("work_wifi_ssid")
        private val KEY_RIGHT_HANDED = booleanPreferencesKey("right_handed")
        private val KEY_ONBOARDING_VERSION = intPreferencesKey("onboarding_version")
        private val KEY_CARD_FEED_ENABLED = booleanPreferencesKey("card_feed_enabled")
        private val KEY_LLM_RANKING_ENABLED = booleanPreferencesKey("llm_ranking_enabled")
        private val KEY_CHAT_TIMEOUT_MINUTES = intPreferencesKey("chat_timeout_minutes")
        private val KEY_CHAT_LAST_INTERACTION = longPreferencesKey("chat_last_interaction")
        private val KEY_MEMORY_ENABLED = booleanPreferencesKey("memory_enabled")
        private val KEY_DEFAULT_LAT = stringPreferencesKey("default_latitude")
        private val KEY_DEFAULT_LNG = stringPreferencesKey("default_longitude")
        private val KEY_DEFAULT_LOCATION_TS = longPreferencesKey("default_location_ts")
        private val KEY_BOOTSTRAP_DONE = booleanPreferencesKey("bootstrap_done")
    }
}
