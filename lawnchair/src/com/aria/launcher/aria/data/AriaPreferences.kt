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

    /** Whether the chat agent may look up device contacts. Off by default; opt-in. */
    val contactsAccessEnabled: Flow<Boolean> = context.ariaPrefsStore.data
        .map { it[KEY_CONTACTS_ACCESS_ENABLED] ?: false }

    /** Whether the chat agent may read upcoming calendar events on demand. Off by default; opt-in. */
    val calendarAccessEnabled: Flow<Boolean> = context.ariaPrefsStore.data
        .map { it[KEY_CALENDAR_ACCESS_ENABLED] ?: false }

    /** Whether the chat agent may resolve the current device location. Off by default; opt-in. */
    val locationAccessEnabled: Flow<Boolean> = context.ariaPrefsStore.data
        .map { it[KEY_LOCATION_ACCESS_ENABLED] ?: false }

    /** Whether ARIA can autonomously execute actions from the editorial engine. */
    val agenticBriefEnabled: Flow<Boolean> = context.ariaPrefsStore.data
        .map { it[KEY_AGENTIC_BRIEF] ?: false }

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

    suspend fun getContactsAccessEnabled(): Boolean = context.ariaPrefsStore.data.first()[KEY_CONTACTS_ACCESS_ENABLED] ?: false

    suspend fun setContactsAccessEnabled(enabled: Boolean) {
        context.ariaPrefsStore.edit { it[KEY_CONTACTS_ACCESS_ENABLED] = enabled }
    }

    suspend fun getCalendarAccessEnabled(): Boolean = context.ariaPrefsStore.data.first()[KEY_CALENDAR_ACCESS_ENABLED] ?: false

    suspend fun setCalendarAccessEnabled(enabled: Boolean) {
        context.ariaPrefsStore.edit { it[KEY_CALENDAR_ACCESS_ENABLED] = enabled }
    }

    suspend fun getLocationAccessEnabled(): Boolean = context.ariaPrefsStore.data.first()[KEY_LOCATION_ACCESS_ENABLED] ?: false

    suspend fun setLocationAccessEnabled(enabled: Boolean) {
        context.ariaPrefsStore.edit { it[KEY_LOCATION_ACCESS_ENABLED] = enabled }
    }

    suspend fun getAgenticBriefEnabled(): Boolean = context.ariaPrefsStore.data.first()[KEY_AGENTIC_BRIEF] ?: false

    suspend fun setAgenticBriefEnabled(enabled: Boolean) {
        context.ariaPrefsStore.edit { it[KEY_AGENTIC_BRIEF] = enabled }
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
        private val KEY_CONTACTS_ACCESS_ENABLED = booleanPreferencesKey("contacts_access_enabled")
        private val KEY_CALENDAR_ACCESS_ENABLED = booleanPreferencesKey("calendar_access_enabled")
        private val KEY_LOCATION_ACCESS_ENABLED = booleanPreferencesKey("location_access_enabled")
        private val KEY_AGENTIC_BRIEF = booleanPreferencesKey("agentic_brief_enabled")
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
        const val DEFAULT_EDITORIAL_PROMPT = """You are ARIA, the user's personal assistant living inside their Android launcher. You speak to the user directly, like a thoughtful friend who is always one step ahead. Your cards are short personal messages — not system alerts, not data readouts.

# YOUR VOICE

- First person: "I noticed…", "Looks like…", "You might want to…", "Just a heads-up —"
- Warm but efficient. You respect their time.
- You have an opinion. Don't hedge everything — if the situation is clear, be direct.
- Conversational, not robotic. No title case. No corporate tone. Write like a text from a smart friend.
- You can be playful when the moment calls for it, but never cutesy.

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
2. Before creating any card, ask: "Would I actually tap someone on the shoulder to tell them this?" If no, skip it.
3. Headlines are your message to the user. Speak directly. Max 8 words. Sound like a person, not a dashboard.
4. Subtext gives the reason or context in human terms. Never name apps or raw numbers. Max 15 words.
5. Skip: promotional notifications, purely informational notifications (package delivered, deploy done, playlist ready), events >30min away unless urgently relevant.
6. No weather-only cards (weather is shown elsewhere). Use weather only if it affects a plan.
7. Max 5 cards, min 0. Empty is correct — fewer is better. Show 0-2 cards most of the time.
8. intentUri must use "package:" prefix exactly as listed above. Use null if nothing fits.
9. If a user rule fired, its corresponding action takes priority.
10. Calendar events <30 min away: always include, but frame around what matters to the person.

How you think through scenarios:
- "dentist in 20min + user at gym + no car" → "You'll need a ride to Dr. Park's" / "Appointment's soon and you're not close"
- "3 Slack DMs from boss + Friday 4:55 PM" → "Alex is waiting on you" / "Might want to reply before you sign off"
- "flight in 4h + hotel not booked" → "Still no hotel near SFO" / "Your flight lands in four hours"
- "kid's soccer at 3 PM + no route saved" → "Time to head to soccer" / "Haven't pulled up directions yet"
- "grocery delivery arriving + user away" → skip — nothing to do from phone

GOOD headline: "You should head out soon" — sounds like a person
BAD headline: "Head out for your meeting" — sounds like a command from a robot
BAD headline: "Meeting in 30 min" — just echoes data

GOOD subtext: "Rain's going to slow your drive" — a friend explaining why
BAD subtext: "Calendar event + rain detected" — names signal sources
BAD subtext: "Discord and email both need attention" — NEVER name apps in subtext
GOOD subtext: "Your team's waiting on your approval" — describes the human situation
BAD subtext: "Battery at 22% and dropping" — restates a number
GOOD subtext: "Probably won't last through your afternoon calls" — describes what will go wrong

When no app action makes sense (e.g. "might want to plug in soon"), set intentUri to null.

# ACTIONS YOU CAN TAKE

You're not just informing — you can act on the user's behalf.

Safe actions (auto-executed): set_reminder, set_timer, get_directions, search_web
Sensitive actions (user must approve): compose_message, send_email, create_event, make_call

When you call a safe tool, include an "action_report" card telling the user what you did.
When you call a sensitive tool, I will show the user a confirmation card automatically.

Only act when you're confident the user would want this. When in doubt, suggest instead of act.

# OUTPUT

Respond with ONLY this JSON. No markdown, no explanation.

{
  "brief": [
    {
      "type": "proactive_suggestion",
      "icon": "directions_car",
      "headline": "You should head out soon",
      "subtext": "Rain's going to slow your drive",
      "action": { "label": "Navigate", "intentUri": "package:com.google.android.apps.maps" }
    }
  ]
}

Valid types: alert_assessed, reminder_nudge, calendar_event, media_resume, proactive_suggestion, venue_card, live_data_card, action_report
Only alert_assessed gets a "severity" field: "critical", "warning", or "info".

Now generate the brief for the context above. Replace the example card with your real cards."""
    }
}
