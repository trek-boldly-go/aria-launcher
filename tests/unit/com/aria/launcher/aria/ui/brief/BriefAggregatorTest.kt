package com.aria.launcher.aria.ui.brief

import com.aria.launcher.aria.engine.AriaContext
import com.aria.launcher.aria.engine.BriefEditorialEngine
import com.aria.launcher.aria.engine.ContextKey
import com.aria.launcher.aria.engine.DayType
import com.aria.launcher.aria.engine.LocationHint
import com.aria.launcher.aria.engine.TimeBucket
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class BriefAggregatorTest {

    // Returns null so all tests exercise the heuristic fallback path
    private val editorialEngine = mock<BriefEditorialEngine>()

    @Before
    fun setup() {
        runBlocking {
            whenever(editorialEngine.generateBrief(any())).thenReturn(null)
        }
    }

    private fun fakeContext() = AriaContext(
        timestampMs = System.currentTimeMillis(),
        contextKey = ContextKey(DayType.WEEKDAY, TimeBucket.MORNING, LocationHint.HOME),
        isCharging = false,
        wifiSsid = "HomeWifi",
        detectedActivity = null,
        isAndroidAutoConnected = false,
        connectedCarName = null,
        nearbySSIDs = emptyList(),
        upcomingEvents = emptyList(),
        recentAppPackages = emptyList(),
    )

    private class FakeSource(
        override val sourceId: String,
        private val items: List<BriefItem>,
        private val available: Boolean = true,
    ) : BriefDataSource {
        override suspend fun fetchItems(context: AriaContext) = items
        override fun isAvailable(context: AriaContext) = available
    }

    @Test
    fun `empty sources produce empty brief`() = runBlocking {
        val aggregator = BriefAggregator(emptyList(), editorialEngine)
        val result = aggregator.buildBrief(fakeContext())
        assertTrue(result.isEmpty())
    }

    @Test
    fun `brief capped at 5 items`() = runBlocking {
        val items = (1..10).map {
            BriefItem.ReminderNudge(
                icon = "reminder",
                headline = "Reminder $it",
                subtext = null,
                action = null,
            )
        }
        val aggregator = BriefAggregator(listOf(FakeSource("test", items)), editorialEngine)
        val result = aggregator.buildBrief(fakeContext())
        assertEquals(5, result.size)
    }

    @Test
    fun `calendar events ranked above reminders`() = runBlocking {
        val reminder = BriefItem.ReminderNudge("bell", "Buy groceries", null, null)
        val calendar = BriefItem.CalendarEvent(
            title = "Team standup",
            timeDescription = "in 15 minutes",
            location = null,
            primaryAction = BriefAction("Join", null),
        )
        val aggregator = BriefAggregator(listOf(
            FakeSource("cal", listOf(calendar)),
            FakeSource("notif", listOf(reminder)),
        ), editorialEngine)
        val result = aggregator.buildBrief(fakeContext())
        assertEquals(2, result.size)
        assertTrue(result[0] is BriefItem.CalendarEvent)
        assertTrue(result[1] is BriefItem.ReminderNudge)
    }

    @Test
    fun `unavailable sources are skipped`() = runBlocking {
        val item = BriefItem.ReminderNudge("bell", "Test", null, null)
        val aggregator = BriefAggregator(listOf(
            FakeSource("available", listOf(item), available = true),
            FakeSource("unavailable", listOf(item, item), available = false),
        ), editorialEngine)
        val result = aggregator.buildBrief(fakeContext())
        assertEquals(1, result.size)
    }

    @Test
    fun `critical alerts ranked above warnings`() = runBlocking {
        val warning = BriefItem.AlertAssessed("warn", "Light rain", null, AlertSeverity.WARNING, null)
        val critical = BriefItem.AlertAssessed("alert", "Tornado warning", null, AlertSeverity.CRITICAL, null)
        val aggregator = BriefAggregator(listOf(
            FakeSource("weather", listOf(warning, critical)),
        ), editorialEngine)
        val result = aggregator.buildBrief(fakeContext())
        assertEquals(2, result.size)
        assertEquals(AlertSeverity.CRITICAL, (result[0] as BriefItem.AlertAssessed).severity)
    }
}
