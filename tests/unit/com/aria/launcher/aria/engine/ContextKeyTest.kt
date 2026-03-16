package com.aria.launcher.aria.engine

import com.google.android.gms.location.DetectedActivity
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.Calendar

class ContextKeyTest {

    @Test
    fun `weekday detected for Monday through Friday`() {
        val weekdays = listOf(
            Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY,
            Calendar.THURSDAY, Calendar.FRIDAY,
        )
        for (dow in weekdays) {
            val cal = Calendar.getInstance().apply {
                set(Calendar.DAY_OF_WEEK, dow)
                set(Calendar.HOUR_OF_DAY, 10)
            }
            val key = ContextKey.fromCalendar(cal, null, null, null, null)
            assertThat(key.dayType).isEqualTo(DayType.WEEKDAY)
        }
    }

    @Test
    fun `weekend detected for Saturday and Sunday`() {
        for (dow in listOf(Calendar.SATURDAY, Calendar.SUNDAY)) {
            val cal = Calendar.getInstance().apply {
                set(Calendar.DAY_OF_WEEK, dow)
                set(Calendar.HOUR_OF_DAY, 10)
            }
            val key = ContextKey.fromCalendar(cal, null, null, null, null)
            assertThat(key.dayType).isEqualTo(DayType.WEEKEND)
        }
    }

    @Test
    fun `time buckets map to correct hour ranges`() {
        val expected = mapOf(
            0 to TimeBucket.NIGHT,
            4 to TimeBucket.NIGHT,
            5 to TimeBucket.EARLY_MORNING,
            7 to TimeBucket.EARLY_MORNING,
            8 to TimeBucket.MORNING,
            11 to TimeBucket.MORNING,
            12 to TimeBucket.MIDDAY,
            13 to TimeBucket.MIDDAY,
            14 to TimeBucket.AFTERNOON,
            17 to TimeBucket.AFTERNOON,
            18 to TimeBucket.EVENING,
            21 to TimeBucket.EVENING,
            22 to TimeBucket.NIGHT,
            23 to TimeBucket.NIGHT,
        )
        for ((hour, bucket) in expected) {
            val cal = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
            }
            val key = ContextKey.fromCalendar(cal, null, null, null, null)
            assertThat(key.timeBucket).isEqualTo(bucket)
        }
    }

    @Test
    fun `home wifi identified`() {
        val key = ContextKey.fromEvent(10, Calendar.MONDAY, "MyHome", null, "MyHome", "Office")
        assertThat(key.location).isEqualTo(LocationHint.HOME)
    }

    @Test
    fun `work wifi identified`() {
        val key = ContextKey.fromEvent(10, Calendar.MONDAY, "Office", null, "MyHome", "Office")
        assertThat(key.location).isEqualTo(LocationHint.WORK)
    }

    @Test
    fun `in vehicle detected as commute`() {
        val key = ContextKey.fromEvent(10, Calendar.MONDAY, null, DetectedActivity.IN_VEHICLE, "MyHome", "Office")
        assertThat(key.location).isEqualTo(LocationHint.COMMUTE)
    }

    @Test
    fun `unknown wifi with no activity yields UNKNOWN`() {
        val key = ContextKey.fromEvent(10, Calendar.MONDAY, "SomeOtherWifi", null, "MyHome", "Office")
        assertThat(key.location).isEqualTo(LocationHint.UNKNOWN)
    }

    @Test
    fun `null signals yield UNKNOWN location`() {
        val key = ContextKey.fromEvent(10, Calendar.MONDAY, null, null, null, null)
        assertThat(key.location).isEqualTo(LocationHint.UNKNOWN)
    }

    @Test
    fun `toStringKey formats as DAYTYPE_TIMEBUCKET_LOCATION`() {
        val key = ContextKey(DayType.WEEKDAY, TimeBucket.MORNING, LocationHint.HOME)
        assertThat(key.toStringKey()).isEqualTo("WEEKDAY_MORNING_HOME")
    }

    @Test
    fun `fromEvent and fromCalendar produce same result for same inputs`() {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 15)
            set(Calendar.DAY_OF_WEEK, Calendar.SATURDAY)
        }
        val fromCal = ContextKey.fromCalendar(cal, "HomeNet", null, "HomeNet", null)
        val fromEvent = ContextKey.fromEvent(15, Calendar.SATURDAY, "HomeNet", null, "HomeNet", null)
        assertThat(fromEvent).isEqualTo(fromCal)
    }

    @Test
    fun `wifi match takes priority over activity detection`() {
        val key = ContextKey.fromEvent(10, Calendar.MONDAY, "MyHome", DetectedActivity.IN_VEHICLE, "MyHome", null)
        assertThat(key.location).isEqualTo(LocationHint.HOME)
    }
}
