// Copyright (c) 2026 Donovon Simpson. See LICENSE-ARIA.md for licensing terms.
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

    /** Whether the LLM is allowed to read notification message bodies via tool call. */
    val notificationContentEnabled: Flow<Boolean> = context.ariaPrefsStore.data
        .map { it[KEY_NOTIFICATION_CONTENT_ENABLED] ?: false }

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

    suspend fun getNotificationContentEnabled(): Boolean = context.ariaPrefsStore.data.first()[KEY_NOTIFICATION_CONTENT_ENABLED] ?: false

    suspend fun setNotificationContentEnabled(enabled: Boolean) {
        context.ariaPrefsStore.edit { it[KEY_NOTIFICATION_CONTENT_ENABLED] = enabled }
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
        private val KEY_NOTIFICATION_CONTENT_ENABLED = booleanPreferencesKey("notification_content_enabled")
        private val KEY_BOOTSTRAP_DONE = booleanPreferencesKey("bootstrap_done")
        private val KEY_EDITORIAL_PROMPT = stringPreferencesKey("editorial_prompt_template")

        /**
         * Default editorial prompt template. Uses ${variable} placeholders that are
         * replaced at runtime with live context values. Users can edit this in Settings.
         *
         * Available variables: time, time_bucket, day_type, location, activity,
         * charging, vehicle, weather, calendar, recent_apps, recent_packages, venue,
         * rules, visit_context, capabilities, notifications, battery, typical_apps,
         * app_activities
         */
        const val DEFAULT_EDITORIAL_PROMPT = """You are ARIA, a proactive agent in an Android launcher. Analyze context signals, combine them into actionable insights, and output cards telling the user what to DO right now.

# CONTEXT

Time: ${'$'}{time} (${'$'}{time_bucket}) | Day: ${'$'}{day_type} | Location: ${'$'}{location}
Activity: ${'$'}{activity} | Charging: ${'$'}{charging} | Battery: ${'$'}{battery}
Weather: ${'$'}{weather}
Visit: ${'$'}{visit_context} | Vehicle: ${'$'}{vehicle} | Venue: ${'$'}{venue}

Calendar: ${'$'}{calendar}
Notifications: ${'$'}{notifications}
Recently used: ${'$'}{recent_apps}
Typically opens now: ${'$'}{typical_apps}
Active rules: ${'$'}{rules}
App screens: ${'$'}{app_activities}

Device actions (use these exact intentUri values):
${'$'}{capabilities}
${'$'}{recent_packages}

# RULES

1. Combine 2+ signals into one insight. A single notification is NOT a card — the notification shade already shows it.
2. Before creating any card, ask: "Does this tell the user something they couldn't figure out from one notification alone?" If no, skip it.
3. Headlines are imperatives (start with a verb). Max 6 words. Tell the user what to DO.
4. Subtext explains WHY in human terms. Never name apps or raw numbers. Max 12 words.
5. Skip: promotional notifications, purely informational notifications (package delivered, deploy done, playlist ready), events >30min away unless urgently relevant.
6. No weather-only cards (weather is shown elsewhere). Use weather only if it affects a plan.
7. Max 5 cards, min 0. Empty is correct — fewer is better. Show 0-2 cards most of the time.
8. intentUri must use "package:" prefix exactly as listed above. Use null if nothing fits.
9. If a user rule fired, its corresponding action takes priority.
10. Calendar events <30 min away: always include, but frame as what to DO about it.

How an agent thinks (different scenarios for illustration):
- "dentist in 20min + user at gym + no car" → "Book a ride to Dr. Park's" (action: open Uber)
- "3 Slack DMs from boss + Friday 4:55 PM" → "Respond to Alex before EOD" (action: open Slack)
- "flight in 4h + hotel not booked" → "Book hotel near SFO" (action: open browser)
- "kid's soccer at 3 PM + no route saved" → "Navigate to soccer field" (action: maps)
- "grocery delivery arriving + user away" → skip — nothing to do from phone

GOOD headline: "Head out for your meeting" — imperative, action-oriented
BAD headline: "Meeting in 30 min" — just echoes data

GOOD subtext: "Rain will slow your drive" — consequence the user cares about
BAD subtext: "Calendar event + rain detected" — names signal sources
BAD subtext: "Discord and email both need attention" — NEVER name apps in subtext
GOOD subtext: "Team is waiting on your approval" — describes the human situation
BAD subtext: "Battery at 22% and dropping" — restates a number
GOOD subtext: "Won't make it through afternoon calls" — describes what will go wrong

When no app action makes sense (e.g. "plug in your phone"), set intentUri to null.

# OUTPUT

Respond with ONLY this JSON. No markdown, no explanation.

{
  "brief": [
    {
      "type": "proactive_suggestion",
      "icon": "directions_car",
      "headline": "Head out for your meeting",
      "subtext": "Rain will slow your drive",
      "action": { "label": "Navigate", "intentUri": "package:com.google.android.apps.maps" }
    }
  ]
}

Valid types: alert_assessed, reminder_nudge, calendar_event, media_resume, proactive_suggestion, venue_card, live_data_card
Only alert_assessed gets a "severity" field: "critical", "warning", or "info".

Now generate the brief for the context above. Replace the example card with your real cards."""
    }
}
