package com.aria.launcher.aria.data

import android.annotation.SuppressLint
import android.content.Context
import android.provider.CalendarContract
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

data class UpcomingCalendarEvent(
    val title: String,
    val startTimeMs: Long,
    val endTimeMs: Long,
    val calendarDisplayName: String?,
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
        )

        val selection = "${CalendarContract.Events.DTSTART} >= ? AND ${CalendarContract.Events.DTSTART} <= ?"
        val selectionArgs = arrayOf(now.toString(), windowEnd.toString())
        val sortOrder = "${CalendarContract.Events.DTSTART} ASC"

        val results = mutableListOf<UpcomingCalendarEvent>()

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

                while (cursor.moveToNext()) {
                    results += UpcomingCalendarEvent(
                        title = cursor.getString(titleIdx) ?: "(No title)",
                        startTimeMs = cursor.getLong(startIdx),
                        endTimeMs = cursor.getLong(endIdx),
                        calendarDisplayName = cursor.getString(calNameIdx),
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

    companion object {
        private const val TAG = "ARIA.Calendar"
    }
}
