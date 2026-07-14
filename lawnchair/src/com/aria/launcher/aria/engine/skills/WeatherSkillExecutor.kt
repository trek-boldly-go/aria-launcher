// Copyright (c) 2026 Donovon Simpson. See LICENSE-ARIA.md for licensing terms.
package com.aria.launcher.aria.engine.skills

import android.util.Log
import com.aria.launcher.aria.data.AppSkill
import com.aria.launcher.aria.data.LocationProvider
import com.aria.launcher.aria.data.SkillAction
import com.aria.launcher.aria.data.SkillResult
import com.aria.launcher.aria.engine.SkillExecutor
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.float
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

class WeatherSkillExecutor(
    private val httpClient: OkHttpClient,
    private val json: Json,
    private val locationProvider: LocationProvider,
) : SkillExecutor {

    override val supportedSkillIds = setOf("weather.forecast", "weather.alerts")

    override suspend fun execute(skill: AppSkill): SkillResult? {
        return when (skill.id) {
            "weather.forecast" -> executeCurrentWeather()
            "weather.alerts" -> executeWeatherAlerts()
            else -> null
        }
    }

    private fun getLastLocation(): Pair<Double, Double>? = try {
        runBlocking { locationProvider.getLatLng() }
    } catch (e: Exception) {
        Log.d(TAG, "Location unavailable", e)
        null
    }

    private fun executeCurrentWeather(): SkillResult? {
        val (lat, lon) = getLastLocation() ?: return null

        val url = "https://api.open-meteo.com/v1/forecast" +
            "?latitude=$lat&longitude=$lon" +
            "&current=temperature_2m,relative_humidity_2m,apparent_temperature," +
            "weather_code,wind_speed_10m,wind_gusts_10m" +
            "&daily=temperature_2m_max,temperature_2m_min,weather_code" +
            "&temperature_unit=fahrenheit&wind_speed_unit=mph" +
            "&timezone=auto&forecast_days=1"

        val request = Request.Builder().url(url).build()
        val response = httpClient.newCall(request).execute()
        if (!response.isSuccessful) {
            Log.w(TAG, "Weather API failed: ${response.code}")
            return null
        }

        val body = response.body?.string() ?: return null
        val data = json.parseToJsonElement(body).jsonObject
        val current = data["current"]?.jsonObject ?: return null

        val temp = current["temperature_2m"]?.jsonPrimitive?.doubleOrNull?.let { Math.round(it) }
            ?: return null
        val feelsLike = current["apparent_temperature"]?.jsonPrimitive?.doubleOrNull?.let { Math.round(it) }
        val weatherCode = current["weather_code"]?.jsonPrimitive?.intOrNull ?: 0
        val windSpeed = current["wind_speed_10m"]?.jsonPrimitive?.doubleOrNull?.let { Math.round(it) }
        val windGusts = current["wind_gusts_10m"]?.jsonPrimitive?.doubleOrNull?.let { Math.round(it) }
        val humidity = current["relative_humidity_2m"]?.jsonPrimitive?.intOrNull

        val daily = data["daily"]?.jsonObject
        val highTemp = daily?.get("temperature_2m_max")?.jsonArray?.firstOrNull()
            ?.jsonPrimitive?.doubleOrNull?.let { Math.round(it) }
        val lowTemp = daily?.get("temperature_2m_min")?.jsonArray?.firstOrNull()
            ?.jsonPrimitive?.doubleOrNull?.let { Math.round(it) }

        val condition = weatherCodeToDescription(weatherCode)
        val icon = weatherCodeToIcon(weatherCode)

        val title = "$icon ${temp}\u00B0F \u00B7 $condition"

        val bodyParts = mutableListOf<String>()
        if (highTemp != null && lowTemp != null) {
            bodyParts.add("H: ${highTemp}\u00B0 L: ${lowTemp}\u00B0")
        }
        if (feelsLike != null && feelsLike != temp) {
            bodyParts.add("Feels like ${feelsLike}\u00B0")
        }
        if (windSpeed != null && windSpeed > 0) {
            val windText = if (windGusts != null && windGusts > windSpeed + 10) {
                "Wind: $windSpeed mph, gusts $windGusts mph"
            } else {
                "Wind: $windSpeed mph"
            }
            bodyParts.add(windText)
        }

        // Flag high winds
        val priority = when {
            windGusts != null && windGusts >= 45 -> 0.9f

            // Advisory-level gusts
            windSpeed != null && windSpeed >= 30 -> 0.85f

            weatherCode >= 95 -> 0.9f

            // Thunderstorm
            else -> 0.5f
        }

        // Add wind warning if applicable
        if (windGusts != null && windGusts >= 40) {
            bodyParts.add("\u26A0\uFE0F High wind gusts — secure loose items")
        }

        val actions = listOf(
            SkillAction("Open Weather", "OPEN_APP", "com.google.android.apps.weather"),
        )

        return SkillResult(
            skillId = "weather.forecast",
            title = title,
            body = bodyParts.joinToString(" \u00B7 "),
            actions = json.encodeToString(actions),
            priority = priority,
            timestamp = System.currentTimeMillis(),
            expiresAt = System.currentTimeMillis() + 30 * 60 * 1000L, // 30 min
        )
    }

    private fun executeWeatherAlerts(): SkillResult? {
        val (lat, lon) = getLastLocation() ?: return null

        // NWS API for active alerts at this location (US only, free, no key)
        val url = "https://api.weather.gov/alerts/active?point=$lat,$lon&status=actual"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "(ARIA Launcher, aria@example.com)")
            .build()

        return try {
            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.d(TAG, "NWS alerts API: ${response.code} (may not be US location)")
                return null
            }

            val body = response.body?.string() ?: return null
            val data = json.parseToJsonElement(body).jsonObject
            val features = data["features"]?.jsonArray ?: return null

            if (features.isEmpty()) return null

            // Get the most severe alert
            val alerts = features.mapNotNull { feature ->
                val props = feature.jsonObject["properties"]?.jsonObject ?: return@mapNotNull null
                val event = props["event"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val headline = props["headline"]?.jsonPrimitive?.contentOrNull
                val severity = props["severity"]?.jsonPrimitive?.contentOrNull ?: "Unknown"
                val description = props["description"]?.jsonPrimitive?.contentOrNull
                Triple(event, headline ?: event, severity)
            }

            if (alerts.isEmpty()) return null

            val (event, headline, severity) = alerts.first()
            val alertIcon = when (severity) {
                "Extreme" -> "\uD83D\uDEA8"
                "Severe" -> "\u26A0\uFE0F"
                "Moderate" -> "\u26A0\uFE0F"
                else -> "\u2139\uFE0F"
            }

            val priority = when (severity) {
                "Extreme" -> 1.0f
                "Severe" -> 0.95f
                "Moderate" -> 0.85f
                else -> 0.7f
            }

            val actions = listOf(
                SkillAction("Open Weather", "OPEN_APP", "com.google.android.apps.weather"),
            )

            SkillResult(
                skillId = "weather.alerts",
                title = "$alertIcon $event",
                body = headline,
                actions = json.encodeToString(actions),
                priority = priority,
                timestamp = System.currentTimeMillis(),
                expiresAt = System.currentTimeMillis() + 60 * 60 * 1000L, // 1 hour
            )
        } catch (e: Exception) {
            Log.w(TAG, "NWS alerts check failed (non-fatal)", e)
            null
        }
    }

    companion object {
        private const val TAG = "ARIA.WeatherSkill"

        private fun weatherCodeToDescription(code: Int): String = when (code) {
            0 -> "Clear"
            1 -> "Mostly clear"
            2 -> "Partly cloudy"
            3 -> "Overcast"
            45, 48 -> "Foggy"
            51, 53, 55 -> "Drizzle"
            56, 57 -> "Freezing drizzle"
            61, 63, 65 -> "Rain"
            66, 67 -> "Freezing rain"
            71, 73, 75 -> "Snow"
            77 -> "Snow grains"
            80, 81, 82 -> "Rain showers"
            85, 86 -> "Snow showers"
            95 -> "Thunderstorm"
            96, 99 -> "Thunderstorm with hail"
            else -> "Unknown"
        }

        private fun weatherCodeToIcon(code: Int): String = when (code) {
            0 -> "\u2600\uFE0F"

            // sunny
            1, 2 -> "\u26C5"

            // partly cloudy
            3 -> "\u2601\uFE0F"

            // cloudy
            45, 48 -> "\uD83C\uDF2B\uFE0F"

            // fog
            51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 80, 81, 82 -> "\uD83C\uDF27\uFE0F"

            // rain
            71, 73, 75, 77, 85, 86 -> "\uD83C\uDF28\uFE0F"

            // snow
            95, 96, 99 -> "\u26C8\uFE0F"

            // thunderstorm
            else -> "\uD83C\uDF24\uFE0F"
        }
    }
}
