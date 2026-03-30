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

    /** User-editable editorial prompt template with ${variable} placeholders. */
    val editorialPromptTemplate: Flow<String> = context.ariaPrefsStore.data
        .map { it[KEY_EDITORIAL_PROMPT] ?: DEFAULT_EDITORIAL_PROMPT }

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

    suspend fun getEditorialPromptTemplate(): String = context.ariaPrefsStore.data.first()[KEY_EDITORIAL_PROMPT] ?: DEFAULT_EDITORIAL_PROMPT

    suspend fun setEditorialPromptTemplate(template: String) {
        context.ariaPrefsStore.edit { it[KEY_EDITORIAL_PROMPT] = template }
    }

    suspend fun getDefaultLatitude(): Double? = context.ariaPrefsStore.data.first()[KEY_DEFAULT_LAT]?.toDoubleOrNull()

    suspend fun getDefaultLongitude(): Double? = context.ariaPrefsStore.data.first()[KEY_DEFAULT_LNG]?.toDoubleOrNull()

    suspend fun getDefaultLocationTimestamp(): Long = context.ariaPrefsStore.data.first()[KEY_DEFAULT_LOCATION_TS] ?: 0L

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
        private val KEY_EDITORIAL_PROMPT = stringPreferencesKey("editorial_prompt_template")

        /**
         * Default editorial prompt template. Uses ${variable} placeholders that are
         * replaced at runtime with live context values. Users can edit this in Settings.
         *
         * Available variables: time, time_bucket, day_type, location, activity,
         * charging, vehicle, weather, calendar, recent_apps, recent_packages, venue,
         * rules, visit_context, capabilities, notifications, battery, typical_apps
         */
        const val DEFAULT_EDITORIAL_PROMPT = """You are ARIA's editorial engine for an Android launcher. Your job is to decide what appears on the user's home screen right now, based on their current context and prediction signals.

Current context:
- Time: ${'$'}{time} (${'$'}{time_bucket})
- Day: ${'$'}{day_type}
- Location: ${'$'}{location}
- Detected activity: ${'$'}{activity}
- Charging: ${'$'}{charging}
- In vehicle: ${'$'}{vehicle}
- Weather: ${'$'}{weather}
- Visit context: ${'$'}{visit_context}
- Upcoming calendar events: ${'$'}{calendar}
- Recently used apps: ${'$'}{recent_apps}
- Current venue: ${'$'}{venue}
- Active user rules that fired: ${'$'}{rules}
- Battery: ${'$'}{battery}
- Pending notifications: ${'$'}{notifications}
- User typically opens at this time: ${'$'}{typical_apps}

Available device actions (use these real package names in intentUri):
${'$'}{capabilities}
${'$'}{recent_packages}

Respond ONLY with valid JSON. No markdown fences, no explanation, no preamble.

{
  "brief": [
    {
      "type": "<valid type>",
      "icon": "<material symbol name>",
      "headline": "<max 6 words — ARIA's judgment, not raw data>",
      "subtext": "<max 12 words, or null>",
      "severity": "<critical, warning, or info — only for alert_assessed>",
      "action": { "label": "<max 3 words>", "intentUri": "<package:com.example.app or null>" }
    }
  ]
}

Valid types: alert_assessed, reminder_nudge, calendar_event, media_resume, proactive_suggestion, venue_card, live_data_card

Rules:
- Maximum 5 items. Minimum 0 — empty is better than noisy.
- NEVER generate a weather card or live_data_card about weather. Weather is already shown in the context bar above. Only reference weather inside another card if it affects a specific plan (e.g., "Rain at 3pm — bring umbrella for your walk").
- Headlines must be ARIA's judgment, not forwarded data.
  BAD: "Flood Advisory issued March 15 at 9:02PM CDT until March 16"
  GOOD: "Flood advisory — your area isn't affected"
- Think about what the user needs to know or do RIGHT NOW based on combined context.
- Combine signals: calendar + navigation, notifications + time pressure, battery + upcoming travel.
- Suggest actions the user hasn't taken yet but typically takes at this time.
- Calendar events within 30 minutes always appear.
- If a user rule fired, its corresponding action takes priority.
- Action labels must be short (1-3 words) like "Open", "Navigate", "Reply".
- Intent URIs: Use "package:<packagename>" to open an app. If you don't know the right package, use null — do NOT guess.
- When in doubt, show less."""
    }
}
