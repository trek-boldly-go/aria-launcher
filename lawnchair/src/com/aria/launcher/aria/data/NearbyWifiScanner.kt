// Copyright (c) 2026 Donovon Simpson. All rights reserved. See LICENSE-ARIA.md
package com.aria.launcher.aria.data

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NearbyWifiScanner @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val _nearbySSIDs = MutableStateFlow<List<String>>(emptyList())
    val nearbySSIDs: StateFlow<List<String>> = _nearbySSIDs.asStateFlow()

    private val _nearbyVenues = MutableStateFlow<List<NearbyVenue>>(emptyList())
    val nearbyVenues: StateFlow<List<NearbyVenue>> = _nearbyVenues.asStateFlow()

    @SuppressLint("MissingPermission")
    fun refresh() {
        try {
            val wm = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val results = wm.scanResults ?: emptyList()
            val ssids = results
                .mapNotNull { it.SSID?.takeIf { s -> s.isNotBlank() } }
                .distinct()
            _nearbySSIDs.value = ssids
            _nearbyVenues.value = VenuePatterns.matchVenues(ssids, context)
            Log.d(TAG, "Scanned ${ssids.size} SSIDs, matched ${_nearbyVenues.value.size} venues")
        } catch (e: SecurityException) {
            Log.w(TAG, "WiFi scan permission denied", e)
            _nearbySSIDs.value = emptyList()
            _nearbyVenues.value = emptyList()
        }
    }

    companion object {
        private const val TAG = "ARIA.WifiScanner"
    }
}

data class NearbyVenue(
    val venueType: String,
    val venueName: String,
    val matchedSSID: String,
    val suggestedPackages: List<String>,
)
