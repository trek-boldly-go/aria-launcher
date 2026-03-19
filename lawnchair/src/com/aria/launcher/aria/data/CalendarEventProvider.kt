package com.aria.launcher.aria.data

import android.annotation.SuppressLint
import android.content.Context
import android.provider.CalendarContract
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Calendar
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton

data class UpcomingCalendarEvent(
    val title: String,
    val startTimeMs: Long,
    val endTimeMs: Long,
    val calendarDisplayName: String?,
    val isAllDay: Boolean = false,
)

/**
 * Reads upcoming calendar events via [CalendarContract].
 * Requires [android.Manifest.permission.READ_CALENDAR].
 */
@Singleton
class CalendarEventProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /**
     * Returns events starting within the next [windowMinutes] from now, sorted by start time.
     * Returns an empty list if the READ_CALENDAR permission hasn't been granted.
     */
    @SuppressLint("MissingPermission")
    fun getUpcomingEvents(windowMinutes: Int = 60): List<UpcomingCalendarEvent> {
        val now = System.currentTimeMillis()
        val windowEnd = now + windowMinutes * 60 * 1000L

        val projection = arrayOf(
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND,
            CalendarContract.Events.CALENDAR_DISPLAY_NAME,
            CalendarContract.Events.ALL_DAY,
        )

        // For all-day events, Android stores DTSTART as midnight UTC which can
        // fall into the previous day in local time. Widen the query window by
        // 24h so we don't miss all-day events whose UTC start precedes 'now',
        // then filter precisely after correcting timestamps.
        val queryStart = now - 24 * 60 * 60 * 1000L
        val selection = "${CalendarContract.Events.DTSTART} >= ? AND ${CalendarContract.Events.DTSTART} <= ?"
        val selectionArgs = arrayOf(queryStart.toString(), windowEnd.toString())
        val sortOrder = "${CalendarContract.Events.DTSTART} ASC"

        val results = mutableListOf<UpcomingCalendarEvent>()
        val localTz = TimeZone.getDefault()

        try {
            context.contentResolver.query(
                CalendarContract.Events.CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                sortOrder,
            )?.use { cursor ->
                val titleIdx = cursor.getColumnIndexOrThrow(CalendarContract.Events.TITLE)
                val startIdx = cursor.getColumnIndexOrThrow(CalendarContract.Events.DTSTART)
                val endIdx = cursor.getColumnIndexOrThrow(CalendarContract.Events.DTEND)
                val calNameIdx = cursor.getColumnIndexOrThrow(CalendarContract.Events.CALENDAR_DISPLAY_NAME)
                val allDayIdx = cursor.getColumnIndexOrThrow(CalendarContract.Events.ALL_DAY)
                while (cursor.moveToNext()) {
                    val isAllDay = cursor.getInt(allDayIdx) == 1
                    var startMs = cursor.getLong(startIdx)
                    var endMs = cursor.getLong(endIdx)

                    if (isAllDay) {
                        // All-day events are stored as midnight UTC. Convert to
                        // local midnight so the date is correct in the user's TZ.
                        startMs = utcMidnightToLocalMidnight(startMs, localTz)
                        endMs = utcMidnightToLocalMidnight(endMs, localTz)
                    }

                    // After correction, only include events that haven't ended
                    if (endMs <= now && !isAllDay) continue
                    // For all-day events, include if the event spans today or later
                    if (isAllDay && endMs <= now) continue
                    // Non-all-day events must start within the original window
                    if (!isAllDay && startMs < now) continue

                    results += UpcomingCalendarEvent(
                        title = cursor.getString(titleIdx) ?: "(No title)",
                        startTimeMs = startMs,
                        endTimeMs = endMs,
                        calendarDisplayName = cursor.getString(calNameIdx),
                        isAllDay = isAllDay,
                    )
                }
            }
        } catch (_: SecurityException) {
            Log.d(TAG, "READ_CALENDAR permission not granted, returning empty")
        }

        return results
    }

    /**
     * Returns true if there's a calendar event starting within [windowMinutes].
     * Lightweight check for the prediction engine — avoids allocating event objects.
     */
    fun hasUpcomingEvent(windowMinutes: Int = 30): Boolean {
        val now = System.currentTimeMillis()
        val windowEnd = now + windowMinutes * 60 * 1000L

        val projection = arrayOf(CalendarContract.Events._ID)
        val selection = "${CalendarContract.Events.DTSTART} >= ? AND ${CalendarContract.Events.DTSTART} <= ?"
        val selectionArgs = arrayOf(now.toString(), windowEnd.toString())

        return try {
            context.contentResolver.query(
                CalendarContract.Events.CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                null,
            )?.use { it.count > 0 } ?: false
        } catch (_: SecurityException) {
            Log.d(TAG, "READ_CALENDAR permission not granted")
            false
        }
    }

    /**
     * Converts a UTC-midnight timestamp to local-midnight on the same calendar date.
     * E.g. March 20 00:00 UTC → March 20 00:00 EST (not March 19 19:00 EST).
     */
    private fun utcMidnightToLocalMidnight(utcMs: Long, localTz: TimeZone): Long {
        val utcCal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            timeInMillis = utcMs
        }
        val localCal = Calendar.getInstance(localTz).apply {
            set(Calendar.YEAR, utcCal.get(Calendar.YEAR))
            set(Calendar.MONTH, utcCal.get(Calendar.MONTH))
            set(Calendar.DAY_OF_MONTH, utcCal.get(Calendar.DAY_OF_MONTH))
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return localCal.timeInMillis
    }

    companion object {
        private const val TAG = "ARIA.Calendar"
    }
}
