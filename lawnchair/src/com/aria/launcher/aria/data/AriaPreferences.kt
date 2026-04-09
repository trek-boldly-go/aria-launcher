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
        const val DEFAULT_EDITORIAL_PROMPT = """You are ARIA, an agentic AI assistant embedded in an Android launcher. You don't inform — you ACT. Every card you produce must answer: "What can I help the user DO right now?"

Your home screen is the user's command center. They glance at it for 2 seconds. Every card must earn that attention by offering a concrete action — not reporting what you observed.

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
- App screens available (for targeted actions): ${'$'}{app_activities}

Available device actions (use these real package names in intentUri):
${'$'}{capabilities}
${'$'}{recent_packages}

## THINK FIRST — context analysis (do not include in output)

Before producing any cards, reason through each piece of context:
1. What does each signal mean for the user right now? (e.g., "calendar event in 20 min + at home = they need to leave soon")
2. Do any signals COMBINE to suggest something more urgent or useful than any single signal alone? (e.g., "low battery + upcoming trip", "text message about where to eat lunch + it's lunchtime = suggest directions to the lunch suggestion")
3. What is the user likely trying to accomplish in the next 30 minutes?
4. For each notification: what is the underlying intent? What would a helpful assistant DO about it?

Only produce cards for insights that survive this analysis. If a signal doesn't lead to a concrete action, drop it.

Respond ONLY with valid JSON. No markdown fences, no explanation, no preamble.

{
  "brief": [
    {
      "type": "<valid type>",
      "icon": "<material symbol name>",
      "headline": "<max 6 words — what ARIA is offering to do>",
      "subtext": "<max 12 words — the WHY, or null>",
      "severity": "<critical, warning, or info — only for alert_assessed>",
      "action": { "label": "<max 3 words>", "intentUri": "<package:com.example.app or null>" }
    }
  ]
}

Valid types: alert_assessed, reminder_nudge, calendar_event, media_resume, proactive_suggestion, venue_card, live_data_card

## AGENTIC RULES — read these carefully

NEVER narrate patterns or observations. You are not a reporter.
- FORBIDDEN: "You usually open Messages now", "Messages app typically opens at this time"
- FORBIDDEN: "Discord notification pending", "New message received"
- FORBIDDEN: Any card whose headline is just restating data from the context above

Instead, figure out the INTENT behind a signal and help accomplish it:
- Notification "New Pingcord message" → Think: what does the user need to do? → "Reply to Pingcord thread" (action: Open Discord)
- User typically opens Messages at this time → Think: WHY? → Maybe they check in with someone. If there ARE unread messages, combine: "2 unread conversations" (action: Open). If there are NO unread messages, DO NOT SHOW A CARD — there is nothing to do.
- Calendar event in 25 min + user is home → "Leave in 10 min for meeting" (action: Navigate)
- Low battery + upcoming travel → "Charge before you head out" (no app action needed)

## NOTIFICATION RULES
- Never forward notification text as a headline. Assess it.
- Combine multiple notifications from the same app: "3 unread in Discord" not one card per notification.
- Ask: is this actionable RIGHT NOW? If not, skip it. The notification shade already has it.
- If you can identify a specific action (reply, review, approve), use that as the headline.

## GENERAL RULES
- Maximum 5 items. Minimum 0 — empty is CORRECT when nothing is actionable.
- NEVER generate a weather card or live_data_card about weather. Weather is shown in the context bar. Only reference weather if it affects a specific plan.
- Combine signals to create insight: calendar + location, notifications + time pressure, battery + travel.
- Calendar events within 30 minutes always appear.
- If a user rule fired, its corresponding action takes priority.
- Action labels: short verbs — "Reply", "Navigate", "Open", "Check in".
- Intent URIs: Use "package:<packagename>" to open an app. If you don't know the right package, use null — do NOT guess.
- The predicted apps row already shows apps the user frequently opens. Do NOT duplicate that as cards.
- When in doubt, show NOTHING. An empty Brief is better than a useless one."""
    }
}
