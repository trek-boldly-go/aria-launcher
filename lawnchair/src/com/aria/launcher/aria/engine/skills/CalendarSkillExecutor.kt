// Copyright (c) 2026 Donovon Simpson. All rights reserved. See LICENSE-ARIA.md
package com.aria.launcher.aria.engine.skills

import com.aria.launcher.aria.data.AppSkill
import com.aria.launcher.aria.data.CalendarEventProvider
import com.aria.launcher.aria.data.SkillAction
import com.aria.launcher.aria.data.SkillResult
import com.aria.launcher.aria.engine.SkillExecutor
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class CalendarSkillExecutor(
    private val calendarEventProvider: CalendarEventProvider,
    private val json: Json,
) : SkillExecutor {

    override val supportedSkillIds = setOf(
        "calendar.next_event",
        "calendar.tomorrow",
    )

    override suspend fun execute(skill: AppSkill): SkillResult? {
        return when (skill.id) {
            "calendar.next_event" -> executeNextEvent(skill)
            "calendar.tomorrow" -> executeTomorrow(skill)
            else -> null
        }
    }

    private fun executeNextEvent(skill: AppSkill): SkillResult? {
        val events = calendarEventProvider.getUpcomingEvents(windowMinutes = 120)
        if (events.isEmpty()) return null

        val next = events.firstOrNull() ?: return null
        val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())
        val startTime = timeFormat.format(Date(next.startTimeMs))

        val minutesUntil = ((next.startTimeMs - System.currentTimeMillis()) / 60_000).toInt()
        val timeDescription = when {
            minutesUntil <= 0 -> "Now"
            minutesUntil < 60 -> "in $minutesUntil min"
            else -> "at $startTime"
        }

        val actions = listOf(
            SkillAction("Open Calendar", "OPEN_APP", skill.appPackage),
        )

        return SkillResult(
            skillId = skill.id,
            title = "${next.title} $timeDescription",
            body = next.calendarDisplayName ?: "",
            actions = json.encodeToString(actions),
            priority = if (minutesUntil <= 15) 0.95f else 0.7f,
            timestamp = System.currentTimeMillis(),
            expiresAt = next.startTimeMs + 5 * 60 * 1000L,
        )
    }

    private fun executeTomorrow(skill: AppSkill): SkillResult? {
        val tomorrowStart = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val tomorrowEnd = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, 1)
            set(Calendar.HOUR_OF_DAY, 23)
            set(Calendar.MINUTE, 59)
        }

        val windowMinutes = ((tomorrowEnd.timeInMillis - System.currentTimeMillis()) / 60_000).toInt()
        val events = calendarEventProvider.getUpcomingEvents(windowMinutes)
            .filter { it.startTimeMs >= tomorrowStart.timeInMillis }
        if (events.isEmpty()) return null

        val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())
        val body = events.take(5).joinToString("\n") { event ->
            "• ${timeFormat.format(Date(event.startTimeMs))} ${event.title}"
        }

        val actions = listOf(
            SkillAction("Open Calendar", "OPEN_APP", skill.appPackage),
        )

        return SkillResult(
            skillId = skill.id,
            title = "Tomorrow · ${events.size} event${if (events.size > 1) "s" else ""}",
            body = body,
            actions = json.encodeToString(actions),
            priority = 0.4f,
            timestamp = System.currentTimeMillis(),
            expiresAt = tomorrowStart.timeInMillis,
        )
    }
}
