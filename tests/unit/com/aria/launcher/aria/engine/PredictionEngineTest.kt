package com.aria.launcher.aria.engine

import android.app.usage.UsageEvents
import com.aria.launcher.aria.data.AppPrediction
import com.aria.launcher.aria.data.AppUsageEvent
import com.aria.launcher.aria.data.CalendarEventProvider
import com.aria.launcher.aria.data.UsageDataRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.util.Calendar

class PredictionEngineTest {

    private val repository = mock<UsageDataRepository>()
    private val calendarProvider = mock<CalendarEventProvider>()
    private val engine = PredictionEngine(repository, calendarProvider, PredictionBlender())

    @Before
    fun setup() {
        whenever(calendarProvider.hasUpcomingEvent(any())).thenReturn(false)
    }

    @Test
    fun `empty events produces no predictions`() = runTest {
        whenever(repository.getEventsForTraining(30)).thenReturn(emptyList())

        engine.generatePredictions(null, null)

        verify(repository, never()).savePredictions(any())
    }

    @Test
    fun `only foreground events are counted`() = runTest {
        val events = listOf(
            makeEvent("com.app.a", eventType = UsageEvents.Event.MOVE_TO_FOREGROUND),
            makeEvent("com.app.a", eventType = UsageEvents.Event.MOVE_TO_BACKGROUND),
            makeEvent("com.app.a", eventType = UsageEvents.Event.MOVE_TO_FOREGROUND),
        )
        whenever(repository.getEventsForTraining(30)).thenReturn(events)

        engine.generatePredictions(null, null)

        val captor = argumentCaptor<List<AppPrediction>>()
        verify(repository).savePredictions(captor.capture())
        val prediction = captor.firstValue.find { it.packageName == "com.app.a" }
        assertThat(prediction).isNotNull()
        assertThat(prediction!!.score).isGreaterThan(0f)
    }

    @Test
    fun `scoring normalizes to max count per context bucket`() = runTest {
        val events = buildList {
            repeat(10) { add(makeEvent("com.app.a")) }
            repeat(5) { add(makeEvent("com.app.b")) }
        }
        whenever(repository.getEventsForTraining(30)).thenReturn(events)

        engine.generatePredictions(null, null)

        val captor = argumentCaptor<List<AppPrediction>>()
        verify(repository).savePredictions(captor.capture())
        val predictions = captor.firstValue

        val scoreA = predictions.find { it.packageName == "com.app.a" }!!.score
        val scoreB = predictions.find { it.packageName == "com.app.b" }!!.score
        // scoreA should always be higher than scoreB (10 vs 5 events)
        assertThat(scoreA).isGreaterThan(scoreB)
        assertThat(scoreB).isGreaterThan(0f)
    }

    @Test
    fun `apps below MIN_SCORE_THRESHOLD are filtered out`() = runTest {
        val events = buildList {
            repeat(100) { add(makeEvent("com.app.a")) }
            repeat(1) { add(makeEvent("com.app.b")) }
        }
        whenever(repository.getEventsForTraining(30)).thenReturn(events)

        engine.generatePredictions(null, null)

        val captor = argumentCaptor<List<AppPrediction>>()
        verify(repository).savePredictions(captor.capture())
        val predictions = captor.firstValue

        assertThat(predictions.map { it.packageName }).contains("com.app.a")
        assertThat(predictions.map { it.packageName }).doesNotContain("com.app.b")
    }

    @Test
    fun `different contexts produce separate predictions`() = runTest {
        val events = listOf(
            makeEvent("com.app.a", hourOfDay = 9, dayOfWeek = Calendar.MONDAY),
            makeEvent("com.app.b", hourOfDay = 20, dayOfWeek = Calendar.SATURDAY),
        )
        whenever(repository.getEventsForTraining(30)).thenReturn(events)

        engine.generatePredictions(null, null)

        val captor = argumentCaptor<List<AppPrediction>>()
        verify(repository).savePredictions(captor.capture())
        val predictions = captor.firstValue

        val contextKeys = predictions.map { it.contextKey }.toSet()
        assertThat(contextKeys).hasSize(2)
        assertThat(contextKeys).contains("WEEKDAY_MORNING_UNKNOWN")
        assertThat(contextKeys).contains("WEEKEND_EVENING_UNKNOWN")
    }

    @Test
    fun `meeting boost injects meeting apps when calendar event upcoming`() = runTest {
        val events = listOf(makeEvent("com.app.a"))
        whenever(repository.getEventsForTraining(30)).thenReturn(events)
        whenever(calendarProvider.hasUpcomingEvent(30)).thenReturn(true)

        engine.generatePredictions(null, null)

        val captor = argumentCaptor<List<AppPrediction>>()
        verify(repository).savePredictions(captor.capture())
        val packages = captor.firstValue.map { it.packageName }

        val meetingPackages = setOf(
            "us.zoom.videomeetings", "com.microsoft.teams",
            "com.google.android.apps.meetings", "com.slack",
            "com.google.android.calendar",
        )
        assertThat(packages.any { it in meetingPackages }).isTrue()
    }

    @Test
    fun `meeting boost raises existing app score to at least 0_9`() = runTest {
        val events = buildList {
            repeat(10) { add(makeEvent("com.app.a")) }
            repeat(1) { add(makeEvent("com.slack")) }
        }
        whenever(repository.getEventsForTraining(30)).thenReturn(events)
        whenever(calendarProvider.hasUpcomingEvent(30)).thenReturn(true)

        engine.generatePredictions(null, null)

        val captor = argumentCaptor<List<AppPrediction>>()
        verify(repository).savePredictions(captor.capture())
        val slackPrediction = captor.firstValue.find { it.packageName == "com.slack" }
        assertThat(slackPrediction).isNotNull()
        assertThat(slackPrediction!!.score).isAtLeast(0.9f)
    }

    @Test
    fun `old events are pruned after prediction`() = runTest {
        val events = listOf(makeEvent("com.app.a"))
        whenever(repository.getEventsForTraining(30)).thenReturn(events)

        engine.generatePredictions(null, null, windowDays = 30)

        verify(repository).pruneOldEvents(30)
    }

    private fun makeEvent(
        packageName: String,
        eventType: Int = UsageEvents.Event.MOVE_TO_FOREGROUND,
        hourOfDay: Int = 10,
        dayOfWeek: Int = Calendar.MONDAY,
        wifiSsid: String? = null,
        detectedActivity: Int? = null,
    ) = AppUsageEvent(
        packageName = packageName,
        timestamp = System.currentTimeMillis(),
        eventType = eventType,
        hourOfDay = hourOfDay,
        dayOfWeek = dayOfWeek,
        isCharging = false,
        wifiSsid = wifiSsid,
        detectedActivity = detectedActivity,
    )
}
