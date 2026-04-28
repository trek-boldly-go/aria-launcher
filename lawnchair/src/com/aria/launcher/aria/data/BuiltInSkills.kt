// Copyright (c) 2026 Donovon Simpson. See LICENSE-ARIA.md for licensing terms.
package com.aria.launcher.aria.data

/**
 * Default built-in skill definitions. These are auto-seeded into the database
 * on first launch and can be re-seeded from debug preferences.
 */
object BuiltInSkills {

    fun all(): List<AppSkill> = listOf(
        AppSkill(
            id = "gmail.inbox_summary",
            appPackage = "com.google.android.gm",
            name = "Inbox Summary",
            description = "Shows unread email count and top messages",
            triggerType = "PROACTIVE",
            sourceType = "BUILT_IN",
            contextMatch = "MORNING,MIDDAY",
            refreshIntervalMin = 15,
        ),
        AppSkill(
            id = "calendar.next_event",
            appPackage = "com.google.android.calendar",
            name = "Next Event",
            description = "Shows the next upcoming calendar event",
            triggerType = "PROACTIVE",
            sourceType = "BUILT_IN",
            contextMatch = "MORNING,MIDDAY,AFTERNOON",
            refreshIntervalMin = 10,
        ),
        AppSkill(
            id = "media.now_playing",
            appPackage = "",
            name = "Now Playing",
            description = "Shows currently playing track from any media app",
            triggerType = "PROACTIVE",
            sourceType = "BUILT_IN",
            contextMatch = "",
            refreshIntervalMin = 5,
        ),
        AppSkill(
            id = "messages.unread",
            appPackage = "com.google.android.apps.messaging",
            name = "Unread Messages",
            description = "Shows unread message count and recent contacts",
            triggerType = "PROACTIVE",
            sourceType = "BUILT_IN",
            contextMatch = "",
            refreshIntervalMin = 10,
        ),
        AppSkill(
            id = "weather.forecast",
            appPackage = "com.google.android.apps.weather",
            name = "Weather",
            description = "Shows current temperature, conditions, and forecast",
            triggerType = "PROACTIVE",
            sourceType = "BUILT_IN",
            contextMatch = "",
            refreshIntervalMin = 30,
        ),
        AppSkill(
            id = "weather.alerts",
            appPackage = "com.google.android.apps.weather",
            name = "Weather Alerts",
            description = "Shows active NWS weather advisories and warnings",
            triggerType = "PROACTIVE",
            sourceType = "BUILT_IN",
            contextMatch = "",
            refreshIntervalMin = 30,
        ),
        AppSkill(
            id = "calendar.tomorrow",
            appPackage = "com.google.android.calendar",
            name = "Tomorrow's Schedule",
            description = "Shows tomorrow's calendar events",
            triggerType = "PROACTIVE",
            sourceType = "BUILT_IN",
            contextMatch = "EVENING,NIGHT",
            refreshIntervalMin = 60,
        ),
        AppSkill(
            id = "venue.nearby",
            appPackage = "com.google.android.apps.maps",
            name = "Nearby Venues",
            description = "Detects nearby venues from WiFi networks",
            triggerType = "PROACTIVE",
            sourceType = "BUILT_IN",
            contextMatch = "",
            refreshIntervalMin = 15,
        ),
    )
}
