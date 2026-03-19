// Copyright (c) 2026 Donovon Simpson. All rights reserved. See LICENSE-ARIA.md
package com.aria.launcher.aria.ui.brief.sources

import com.aria.launcher.aria.data.UpcomingCalendarEvent
import com.aria.launcher.aria.engine.AriaContext
import com.aria.launcher.aria.ui.brief.BriefAction
import com.aria.launcher.aria.ui.brief.BriefDataSource
import com.aria.launcher.aria.ui.brief.BriefItem
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Converts upcoming calendar events (within 2h) into BriefItem.CalendarEvent cards.
 *
 * Reads directly from [AriaContext.upcomingEvents] — no extra I/O.
 * At most 2 events appear in the Brief. Ruthless curation.
 */
@Singleton
class CalendarBriefSource @Inject constructor() : BriefDataSource {

    override val sourceId = "calendar"

    override suspend fun fetchItems(context: AriaContext): List<BriefItem> {
        val now = context.timestampMs
        return context.upcomingEvents
            .filter { it.startTimeMs > now }
            .take(2)
            .map { it.toBriefItem(now) }
    }

    private fun UpcomingCalendarEvent.toBriefItem(now: Long): BriefItem.CalendarEvent {
        val minutesUntil = TimeUnit.MILLISECONDS.toMinutes(startTimeMs - now)
        val timeDescription = if (isAllDay) {
            "All day"
        } else {
            when {
                minutesUntil < 2 -> "Starting now"

                minutesUntil < 60 -> "In $minutesUntil min"

                minutesUntil < 120 -> {
                    val hours = minutesUntil / 60
                    val mins = minutesUntil % 60
                    if (mins == 0L) "In ${hours}h" else "In ${hours}h ${mins}m"
                }

                else -> "In 2 hours"
            }
        }
        return BriefItem.CalendarEvent(
            title = title,
            timeDescription = timeDescription,
            location = null, // EVENT_LOCATION added to CalendarEventProvider in Session 9
            primaryAction = BriefAction(
                label = "Open",
                intentUri = "content://com.android.calendar",
            ),
            secondaryAction = null,
        )
    }
}
