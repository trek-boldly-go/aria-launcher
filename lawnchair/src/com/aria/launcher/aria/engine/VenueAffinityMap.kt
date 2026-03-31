// Copyright (c) 2026 Donovon Simpson. See LICENSE-ARIA.md for licensing terms.
package com.aria.launcher.aria.engine

import com.aria.launcher.aria.data.AppAffinity
import com.aria.launcher.aria.data.VenueCategory
import com.aria.launcher.aria.data.VisitContext

/**
 * Default app affinities per venue category.
 * Provides "cold start" prior scores until LiteRT accumulates personal behavior.
 * Only surfaces apps that are actually installed — never suggests uninstalled packages.
 */
object VenueAffinityMap {

    val defaultAffinities: Map<VenueCategory, List<AppAffinity>> = mapOf(
        VenueCategory.FAST_FOOD to listOf(
            AppAffinity("com.mcdonalds.app", 0.90f),
            AppAffinity("com.starbucks.mobilecard", 0.85f),
            AppAffinity("com.chickfila.cfaone", 0.85f),
            AppAffinity("com.tacobell.mobile", 0.80f),
            AppAffinity("com.subway.mobile", 0.80f),
            AppAffinity("com.chipotle.ordering", 0.80f),
            AppAffinity("com.burgerking.app", 0.75f),
            AppAffinity("com.wendys.mobile", 0.75f),
        ),

        VenueCategory.COFFEE to listOf(
            AppAffinity("com.starbucks.mobilecard", 0.95f),
            AppAffinity("com.dunkin.mobile", 0.90f),
            AppAffinity("com.panera.mobile", 0.80f),
        ),

        VenueCategory.HEALTHCARE to listOf(
            AppAffinity("org.mychart.android", 0.90f),
            AppAffinity("com.anthem.android", 0.75f),
            AppAffinity("com.cigna.mobile", 0.75f),
            AppAffinity("com.aetna.mobile", 0.75f),
            AppAffinity("com.unitedhealthcare.member", 0.75f),
            AppAffinity("com.bluecrossma.android", 0.70f),
        ),

        VenueCategory.HOTEL to listOf(
            AppAffinity("com.hilton.android", 0.90f),
            AppAffinity("com.marriott.mrt", 0.90f),
            AppAffinity("com.ihg.apps.android", 0.85f),
            AppAffinity("com.hyatt.android", 0.85f),
        ),

        VenueCategory.TRAVEL to listOf(
            AppAffinity("com.flightaware.flightaware", 0.85f),
            AppAffinity("com.united.mobile.android.united", 0.90f),
            AppAffinity("com.aa.android", 0.90f),
            AppAffinity("com.delta", 0.90f),
            AppAffinity("com.southwest.mobile", 0.85f),
            AppAffinity("com.tsa.precheck", 0.70f),
        ),

        VenueCategory.RETAIL to listOf(
            AppAffinity("com.target.ui", 0.85f),
            AppAffinity("com.walmart.android", 0.85f),
            AppAffinity("com.costco.android", 0.80f),
            AppAffinity("com.kroger.android", 0.75f),
        ),

        VenueCategory.EDUCATION to listOf(
            AppAffinity("com.duolingo", 0.60f),
            AppAffinity("com.coursera.android", 0.55f),
        ),

        VenueCategory.ENTERTAINMENT to listOf(
            AppAffinity("com.spotify.music", 0.80f),
            AppAffinity("com.google.android.youtube", 0.70f),
            AppAffinity("com.netflix.mediaclient", 0.65f),
        ),
    )

    // Context-aware override: same venue, different role
    private val healthcareByVisitContext: Map<VisitContext, List<AppAffinity>> = mapOf(
        VisitContext.LIKELY_WORKPLACE to listOf(
            AppAffinity("com.slack", 0.80f),
            AppAffinity("com.microsoft.teams", 0.80f),
            AppAffinity("com.workday.android", 0.75f),
        ),
        VisitContext.LIKELY_APPOINTMENT to listOf(
            AppAffinity("org.mychart.android", 0.95f),
            AppAffinity("com.anthem.android", 0.80f),
        ),
        VisitContext.LIKELY_VISITOR to listOf(
            AppAffinity("org.mychart.android", 0.75f),
        ),
    )

    /**
     * Returns affinities filtered to installed packages only.
     * Healthcare venue applies visit-context override when context is known.
     */
    fun getAffinities(
        category: VenueCategory,
        context: VisitContext,
        installedPackages: Set<String>,
    ): List<AppAffinity> {
        val base = when {
            category == VenueCategory.HEALTHCARE && context != VisitContext.UNKNOWN ->
                healthcareByVisitContext[context] ?: defaultAffinities[category]

            else -> defaultAffinities[category]
        } ?: emptyList()

        return base.filter { it.packageName in installedPackages }
    }

    /**
     * Blends cold-start prior score with LiteRT learned score.
     * Weight shifts from prior → learned as observations accumulate.
     * After ~20 visits, learned score dominates entirely.
     */
    fun blendScores(
        priorScore: Float,
        learnedScore: Float,
        observationsAtVenue: Int,
    ): Float {
        val learnedWeight = (observationsAtVenue / 20f).coerceIn(0f, 1f)
        val priorWeight = 1f - learnedWeight
        return (priorScore * priorWeight) + (learnedScore * learnedWeight)
    }
}
