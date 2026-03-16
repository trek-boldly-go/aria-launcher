// Copyright (c) 2026 Donovon Simpson. All rights reserved. See LICENSE-ARIA.md
package com.aria.launcher.aria.data

import android.content.Context

object VenuePatterns {

    data class VenuePattern(
        val ssidPattern: String,
        val venueType: String,
        val venueName: String,
        val packages: List<String>,
    )

    private val builtInPatterns = listOf(
        // Fast food
        VenuePattern("McDonald's*", "fast_food", "McDonald's", listOf("com.mcdonalds.app")),
        VenuePattern("Starbucks*", "coffee", "Starbucks", listOf("com.starbucks.android")),
        VenuePattern("Chick-fil-A*", "fast_food", "Chick-fil-A", listOf("com.chickfila.cfaone")),
        VenuePattern("Dunkin*", "coffee", "Dunkin'", listOf("com.dunkinbrands.otgo")),
        VenuePattern("Chipotle*", "fast_food", "Chipotle", listOf("com.chipotle.ordering")),

        // Stores
        VenuePattern("*Walmart*", "store", "Walmart", listOf("com.walmart.android")),
        VenuePattern("*Target*WiFi*", "store", "Target", listOf("com.target.ui")),
        VenuePattern("*Costco*", "store", "Costco", listOf("com.costco.app.android")),
        VenuePattern("*Kroger*", "store", "Kroger", listOf("com.kroger.mobile")),

        // Gyms
        VenuePattern("*Planet Fitness*", "gym", "Planet Fitness", listOf("com.planetfitness.home")),
        VenuePattern("*LA Fitness*", "gym", "LA Fitness", listOf("com.fitnessintl.myiclubonline")),

        // Hotels
        VenuePattern("*Marriott*", "hotel", "Marriott", listOf("com.marriott.rez")),
        VenuePattern("*Hilton*", "hotel", "Hilton", listOf("com.hilton.android.hhonors")),
        VenuePattern("*Hyatt*", "hotel", "Hyatt", listOf("com.hyatt.android.hyatthotels")),

        // Airports
        VenuePattern("*Airport*", "airport", "Airport", listOf()),
        VenuePattern("*_Free_WiFi", "airport", "Airport", listOf()),

        // Medical
        VenuePattern("*Hospital*", "medical", "Hospital", listOf()),
        VenuePattern("*Medical*", "medical", "Medical Center", listOf()),
        VenuePattern("*Clinic*", "medical", "Clinic", listOf()),
    )

    fun matchVenues(ssids: List<String>, context: Context): List<NearbyVenue> {
        val pm = context.packageManager
        val venues = mutableListOf<NearbyVenue>()
        val seenTypes = mutableSetOf<String>()

        for (ssid in ssids) {
            for (pattern in builtInPatterns) {
                if (matchesPattern(ssid, pattern.ssidPattern) && pattern.venueType !in seenTypes) {
                    val installedPackages = pattern.packages.filter { pkg ->
                        try {
                            pm.getApplicationInfo(pkg, 0)
                            true
                        } catch (_: Exception) {
                            false
                        }
                    }
                    venues += NearbyVenue(
                        venueType = pattern.venueType,
                        venueName = pattern.venueName,
                        matchedSSID = ssid,
                        suggestedPackages = installedPackages,
                    )
                    seenTypes += pattern.venueType
                }
            }
        }

        return venues
    }

    private fun matchesPattern(ssid: String, pattern: String): Boolean {
        val regex = pattern
            .replace("*", ".*")
            .toRegex(RegexOption.IGNORE_CASE)
        return regex.matches(ssid)
    }
}
