// Copyright (c) 2026 Donovon Simpson. All rights reserved. See LICENSE-ARIA.md
package com.aria.launcher.aria.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.aria.launcher.aria.llm.AriaLlmClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.Tasks
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Lightweight weather data for the ContextBar.
 * Directly calls Open-Meteo — no API key required, no skill system overhead.
 * Cached for 30 minutes to avoid redundant network calls on rapid context refreshes.
 */
@Singleton
class WeatherProvider @Inject constructor(
    @ApplicationContext private val context: Context,
    @AriaLlmClient private val httpClient: OkHttpClient,
    private val json: Json,
) {
    private var cached: WeatherSnapshot? = null
    private var cacheTimestamp: Long = 0L

    data class WeatherSnapshot(
        val tempF: Int,
        val feelsLikeF: Int,
        val condition: String,
        val icon: String,
        val weatherCode: Int,
        val windSpeedMph: Int,
        val windGustsMph: Int?,
    ) {
        /** Formatted line for the ContextBar: "34°F · Snow · Feels like 25°" */
        fun toWeatherLine(): String {
            val parts = mutableListOf("$tempF°F", condition)
            if (feelsLikeF != tempF) {
                parts.add("Feels like $feelsLikeF°")
            }
            return parts.joinToString(" · ")
        }

        /** Whether conditions are severe enough to warrant a Brief alert card. */
        fun isSevere(): Boolean = weatherCode >= 95 ||
            (windGustsMph != null && windGustsMph >= 45) ||
            windSpeedMph >= 30
    }

    /**
     * Returns a fresh or cached weather snapshot.
     * Returns null if location permission is missing or the API call fails.
     */
    suspend fun getWeather(): WeatherSnapshot? {
        val now = System.currentTimeMillis()
        cached?.let { snapshot ->
            if (now - cacheTimestamp < CACHE_DURATION_MS) return snapshot
        }

        val snapshot = fetchFromApi() ?: return cached // stale cache better than nothing
        cached = snapshot
        cacheTimestamp = now
        return snapshot
    }

    private fun fetchFromApi(): WeatherSnapshot? {
        val (lat, lon) = getLocation() ?: return null

        val url = "https://api.open-meteo.com/v1/forecast" +
            "?latitude=$lat&longitude=$lon" +
            "&current=temperature_2m,apparent_temperature,weather_code," +
            "wind_speed_10m,wind_gusts_10m" +
            "&temperature_unit=fahrenheit&wind_speed_unit=mph" +
            "&precipitation_unit=inch&timezone=auto"

        val request = Request.Builder().url(url).build()
        return try {
            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.w(TAG, "Open-Meteo API failed: ${response.code}")
                return null
            }

            val body = response.body.string()
            val data = json.parseToJsonElement(body).jsonObject
            val current = data["current"]?.jsonObject ?: return null

            val temp = current["temperature_2m"]?.jsonPrimitive?.doubleOrNull
                ?.let { Math.round(it).toInt() } ?: return null
            val feelsLike = current["apparent_temperature"]?.jsonPrimitive?.doubleOrNull
                ?.let { Math.round(it).toInt() } ?: temp
            val weatherCode = current["weather_code"]?.jsonPrimitive?.intOrNull ?: 0
            val windSpeed = current["wind_speed_10m"]?.jsonPrimitive?.doubleOrNull
                ?.let { Math.round(it).toInt() } ?: 0
            val windGusts = current["wind_gusts_10m"]?.jsonPrimitive?.doubleOrNull
                ?.let { Math.round(it).toInt() }

            WeatherSnapshot(
                tempF = temp,
                feelsLikeF = feelsLike,
                condition = weatherCodeToDescription(weatherCode),
                icon = weatherCodeToIcon(weatherCode),
                weatherCode = weatherCode,
                windSpeedMph = windSpeed,
                windGustsMph = windGusts,
            )
        } catch (e: Exception) {
            Log.w(TAG, "Weather fetch failed", e)
            null
        }
    }

    private fun getLocation(): Pair<Double, Double>? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return null
        }

        return try {
            val client = LocationServices.getFusedLocationProviderClient(context)
            val location = Tasks.await(
                client.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, null),
                10,
                TimeUnit.SECONDS,
            )
            if (location != null) {
                location.latitude to location.longitude
            } else {
                val last = Tasks.await(client.lastLocation, 5, TimeUnit.SECONDS)
                if (last != null) last.latitude to last.longitude else null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Location unavailable", e)
            null
        }
    }

    companion object {
        private const val TAG = "ARIA.Weather"
        private const val CACHE_DURATION_MS = 30 * 60 * 1000L // 30 minutes

        fun weatherCodeToDescription(code: Int): String = when (code) {
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

        fun weatherCodeToIcon(code: Int): String = when (code) {
            0 -> "\u2600\uFE0F"
            1, 2 -> "\u26C5"
            3 -> "\u2601\uFE0F"
            45, 48 -> "\uD83C\uDF2B\uFE0F"
            51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 80, 81, 82 -> "\uD83C\uDF27\uFE0F"
            71, 73, 75, 77, 85, 86 -> "\uD83C\uDF28\uFE0F"
            95, 96, 99 -> "\u26C8\uFE0F"
            else -> "\uD83C\uDF24\uFE0F"
        }
    }
}
