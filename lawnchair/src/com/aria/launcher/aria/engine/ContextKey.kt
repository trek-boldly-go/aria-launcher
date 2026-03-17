package com.aria.launcher.aria.engine

import com.google.android.gms.location.DetectedActivity
import java.util.Calendar

enum class DayType { WEEKDAY, WEEKEND }

enum class TimeBucket {
    EARLY_MORNING, // 5–7
    MORNING, // 8–11
    MIDDAY, // 12–13
    AFTERNOON, // 14–17
    EVENING, // 18–21
    NIGHT, // 22–4
}

enum class LocationHint { HOME, WORK, COMMUTE, UNKNOWN }

enum class VehicleContext {
    NONE,
    COMMUTE_CAR,
    ROAD_TRIP,
    UNKNOWN_VEHICLE,
}

data class ContextKey(
    val dayType: DayType,
    val timeBucket: TimeBucket,
    val location: LocationHint,
    val vehicleContext: VehicleContext = VehicleContext.NONE,
) {
    fun toStringKey(): String = "${dayType}_${timeBucket}_$location"

    companion object {
        fun current(
            wifiSsid: String?,
            detectedActivity: Int?,
            homeWifiSsid: String?,
            workWifiSsid: String?,
            isAndroidAutoConnected: Boolean = false,
            hasUpcomingFarEvent: Boolean = false,
        ): ContextKey = fromCalendar(
            Calendar.getInstance(),
            wifiSsid,
            detectedActivity,
            homeWifiSsid,
            workWifiSsid,
            isAndroidAutoConnected,
            hasUpcomingFarEvent,
        )

        fun fromCalendar(
            cal: Calendar,
            wifiSsid: String?,
            detectedActivity: Int?,
            homeWifiSsid: String?,
            workWifiSsid: String?,
            isAndroidAutoConnected: Boolean = false,
            hasUpcomingFarEvent: Boolean = false,
        ): ContextKey {
            val hour = cal.get(Calendar.HOUR_OF_DAY)
            val dow = cal.get(Calendar.DAY_OF_WEEK)

            val dayType = if (dow == Calendar.SATURDAY || dow == Calendar.SUNDAY) {
                DayType.WEEKEND
            } else {
                DayType.WEEKDAY
            }

            val timeBucket = when (hour) {
                in 5..7 -> TimeBucket.EARLY_MORNING
                in 8..11 -> TimeBucket.MORNING
                in 12..13 -> TimeBucket.MIDDAY
                in 14..17 -> TimeBucket.AFTERNOON
                in 18..21 -> TimeBucket.EVENING
                else -> TimeBucket.NIGHT
            }

            val location = when {
                wifiSsid != null && wifiSsid == homeWifiSsid -> LocationHint.HOME
                wifiSsid != null && wifiSsid == workWifiSsid -> LocationHint.WORK
                detectedActivity == DetectedActivity.IN_VEHICLE -> LocationHint.COMMUTE
                else -> LocationHint.UNKNOWN
            }

            val vehicleContext = resolveVehicleContext(
                isAndroidAutoConnected,
                dayType,
                timeBucket,
                hasUpcomingFarEvent,
            )

            return ContextKey(dayType, timeBucket, location, vehicleContext)
        }

        fun fromEvent(
            hourOfDay: Int,
            dayOfWeek: Int,
            wifiSsid: String?,
            detectedActivity: Int?,
            homeWifiSsid: String?,
            workWifiSsid: String?,
        ): ContextKey {
            val dayType = if (dayOfWeek == Calendar.SATURDAY || dayOfWeek == Calendar.SUNDAY) {
                DayType.WEEKEND
            } else {
                DayType.WEEKDAY
            }

            val timeBucket = when (hourOfDay) {
                in 5..7 -> TimeBucket.EARLY_MORNING
                in 8..11 -> TimeBucket.MORNING
                in 12..13 -> TimeBucket.MIDDAY
                in 14..17 -> TimeBucket.AFTERNOON
                in 18..21 -> TimeBucket.EVENING
                else -> TimeBucket.NIGHT
            }

            val location = when {
                wifiSsid != null && wifiSsid == homeWifiSsid -> LocationHint.HOME
                wifiSsid != null && wifiSsid == workWifiSsid -> LocationHint.WORK
                detectedActivity == DetectedActivity.IN_VEHICLE -> LocationHint.COMMUTE
                else -> LocationHint.UNKNOWN
            }

            return ContextKey(dayType, timeBucket, location)
        }

        /**
         * Heuristic to distinguish commuting vs. road trip when Android Auto is connected:
         * - Weekday + morning/afternoon + no upcoming far events → likely commute
         * - Weekend or upcoming far event or evening → likely road trip
         */
        private fun resolveVehicleContext(
            isAndroidAutoConnected: Boolean,
            dayType: DayType,
            timeBucket: TimeBucket,
            hasUpcomingFarEvent: Boolean,
        ): VehicleContext {
            if (!isAndroidAutoConnected) return VehicleContext.NONE

            val isTypicalCommuteTime = timeBucket in setOf(
                TimeBucket.EARLY_MORNING,
                TimeBucket.MORNING,
                TimeBucket.AFTERNOON,
            )

            return when {
                dayType == DayType.WEEKDAY && isTypicalCommuteTime && !hasUpcomingFarEvent ->
                    VehicleContext.COMMUTE_CAR

                hasUpcomingFarEvent || dayType == DayType.WEEKEND ->
                    VehicleContext.ROAD_TRIP

                else -> VehicleContext.UNKNOWN_VEHICLE
            }
        }
    }
}
