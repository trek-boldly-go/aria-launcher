// Copyright (c) 2026 Donovon Simpson. All rights reserved. See LICENSE-ARIA.md
package com.aria.launcher.aria.engine.skills

import com.aria.launcher.aria.data.AppSkill
import com.aria.launcher.aria.data.NearbyWifiScanner
import com.aria.launcher.aria.data.SkillAction
import com.aria.launcher.aria.data.SkillResult
import com.aria.launcher.aria.engine.SkillExecutor
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class VenueSkillExecutor(
    private val wifiScanner: NearbyWifiScanner,
    private val json: Json,
) : SkillExecutor {

    override val supportedSkillIds = setOf("venue.nearby")

    override suspend fun execute(skill: AppSkill): SkillResult? {
        wifiScanner.refresh()
        val venues = wifiScanner.nearbyVenues.value
        if (venues.isEmpty()) return null

        val venue = venues.firstOrNull() ?: return null
        if (venue.suggestedPackages.isEmpty()) return null

        val actions = venue.suggestedPackages.take(2).map { pkg ->
            SkillAction("Open App", "OPEN_APP", pkg)
        }

        return SkillResult(
            skillId = skill.id,
            title = "Near ${venue.venueName}",
            body = "You're near a ${venue.venueName} location",
            actions = json.encodeToString(actions),
            priority = 0.5f,
            timestamp = System.currentTimeMillis(),
            expiresAt = System.currentTimeMillis() + 30 * 60 * 1000L,
        )
    }
}
