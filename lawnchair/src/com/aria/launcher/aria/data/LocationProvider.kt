// Copyright (c) 2026 Donovon Simpson. See LICENSE-ARIA.md for licensing terms.
package com.aria.launcher.aria.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.Tasks
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

data class LocationSnapshot(
    val lat: Double,
    val lng: Double,
    val accuracyMeters: Float,
    val timestamp: Long,
    /** Best-effort geocoded label (city/locality). Null if geocoding failed or timed out. */
    val label: String?,
)

/**
 * Single source of truth for device location.
 *
 * Used by both [WeatherProvider] (context bar/skill) and the chat agent's
 * `get_current_location` tool. Caches successful reads for 60 seconds so an
 * agent that loops calls does not hammer the GPS.
 */
@Singleton
class LocationProvider @Inject constructor(
    @ApplicationContext private val context: Context,
    private val ariaPreferences: AriaPreferences,
) {
    private var cached: LocationSnapshot? = null
    private var cacheTimestamp: Long = 0L

    /**
     * Returns the current device location, or null if location permission is missing or the
     * fused-location request fails. Falls back to the most recently saved location in
     * [AriaPreferences] when GPS is unavailable.
     */
    suspend fun getCurrentLocation(includeLabel: Boolean = true): LocationSnapshot? {
        val now = System.currentTimeMillis()
        cached?.let { snap ->
            if (now - cacheTimestamp < CACHE_DURATION_MS) return snap
        }

        val (lat, lng, accuracy) = fetchGps() ?: fetchSavedFallback() ?: return cached
        val label = if (includeLabel) reverseGeocode(lat, lng) else null
        val snap = LocationSnapshot(lat, lng, accuracy, now, label)
        cached = snap
        cacheTimestamp = now

        // Persist the freshly-read location so the saved-location fallback stays warm.
        try {
            ariaPreferences.setDefaultLocation(lat, lng)
        } catch (_: Exception) { }

        return snap
    }

    /** Returns just (lat, lng) for callers that don't need accuracy/label/cache semantics. */
    suspend fun getLatLng(): Pair<Double, Double>? =
        getCurrentLocation(includeLabel = false)?.let { it.lat to it.lng }

    private fun fetchGps(): Triple<Double, Double, Float>? {
        if (!hasLocationPermission()) return null
        return try {
            val client = LocationServices.getFusedLocationProviderClient(context)
            val location = Tasks.await(
                client.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, null),
                10,
                TimeUnit.SECONDS,
            ) ?: Tasks.await(client.lastLocation, 5, TimeUnit.SECONDS)
            if (location != null) {
                Triple(location.latitude, location.longitude, location.accuracy)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Location unavailable", e)
            null
        }
    }

    private suspend fun fetchSavedFallback(): Triple<Double, Double, Float>? {
        return try {
            val ts = ariaPreferences.getDefaultLocationTimestamp()
            val ageMs = System.currentTimeMillis() - ts
            if (ts > 0 && ageMs > MAX_SAVED_LOCATION_AGE_MS) return null
            val lat = ariaPreferences.getDefaultLatitude() ?: return null
            val lng = ariaPreferences.getDefaultLongitude() ?: return null
            // Saved snapshots have unknown accuracy; report as 0 so callers can detect.
            Triple(lat, lng, 0f)
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun reverseGeocode(lat: Double, lng: Double): String? {
        if (!Geocoder.isPresent()) return null
        return try {
            withTimeout(GEOCODE_TIMEOUT_MS) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val deferred = CompletableDeferred<String?>()
                    val geocoder = Geocoder(context)
                    geocoder.getFromLocation(lat, lng, 1) { results ->
                        deferred.complete(results.firstOrNull()?.let { addr ->
                            addr.locality
                                ?: addr.subAdminArea
                                ?: addr.adminArea
                        })
                    }
                    deferred.await()
                } else {
                    @Suppress("DEPRECATION")
                    val results = Geocoder(context).getFromLocation(lat, lng, 1)
                    results?.firstOrNull()?.let { addr ->
                        addr.locality
                            ?: addr.subAdminArea
                            ?: addr.adminArea
                    }
                }
            }
        } catch (_: TimeoutCancellationException) {
            null
        } catch (e: Exception) {
            Log.d(TAG, "Reverse geocoding failed", e)
            null
        }
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    companion object {
        private const val TAG = "ARIA.Location"
        private const val CACHE_DURATION_MS = 60_000L
        private const val GEOCODE_TIMEOUT_MS = 3_000L
        private const val MAX_SAVED_LOCATION_AGE_MS = 24 * 60 * 60 * 1000L
    }
}
