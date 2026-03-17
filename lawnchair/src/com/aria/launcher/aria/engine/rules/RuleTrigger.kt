// Copyright (c) 2026 Donovon Simpson. All rights reserved. See LICENSE-ARIA.md
package com.aria.launcher.aria.engine.rules

import com.aria.launcher.aria.data.VenueCategory
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

enum class MatchType { EXACT, CONTAINS, STARTS_WITH, REGEX }

enum class LogicOperator { AND, OR }

@Serializable
sealed class RuleTrigger {

    @Serializable
    @SerialName("wifi_ssid")
    data class WifiSsidTrigger(
        val pattern: String,
        val matchType: MatchType,
    ) : RuleTrigger()

    @Serializable
    @SerialName("venue_category")
    data class VenueCategoryTrigger(
        val category: VenueCategory,
    ) : RuleTrigger()

    @Serializable
    @SerialName("time")
    data class TimeTrigger(
        val startHour: Int,
        val endHour: Int,
        val daysOfWeek: List<Int>?, // null = every day
    ) : RuleTrigger()

    @Serializable
    @SerialName("app_opened")
    data class AppOpenedTrigger(
        val packageName: String,
    ) : RuleTrigger()

    @Serializable
    @SerialName("calendar_event")
    data class CalendarEventTrigger(
        val titleKeywords: List<String>,
        val minutesBefore: Int = 15,
    ) : RuleTrigger()

    @Serializable
    @SerialName("location")
    data class LocationTrigger(
        val lat: Double,
        val lng: Double,
        val radiusMeters: Float,
    ) : RuleTrigger()

    @Serializable
    @SerialName("android_auto")
    data class AndroidAutoTrigger(
        val connectedCarName: String? = null, // null = any car
    ) : RuleTrigger()

    @Serializable
    @SerialName("compound")
    data class CompoundTrigger(
        val triggers: List<RuleTrigger>,
        val operator: LogicOperator,
    ) : RuleTrigger()
}
