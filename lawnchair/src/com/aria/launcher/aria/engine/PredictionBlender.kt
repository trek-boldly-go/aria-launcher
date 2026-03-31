// Copyright (c) 2026 Donovon Simpson. See LICENSE-ARIA.md for licensing terms.
package com.aria.launcher.aria.engine

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Blends cold-start prior scores (e.g. [VenueAffinityMap]) with learned scores
 * (LiteRT model — seam for Session 14).
 *
 * Weight shifts from prior → learned as observations accumulate. After ~20 visits,
 * the learned score dominates entirely.
 *
 * Until LiteRT is trained, [learnedScore] defaults to the Tier 1 frequency score
 * and [priorScore] defaults to 0.0 when no venue affinity is known.
 */
@Singleton
class PredictionBlender @Inject constructor() {

    /**
     * @param priorScore   Cold-start prior (0.0 if no venue affinity applies).
     * @param learnedScore Frequency-based or LiteRT score (0.0 until model is trained).
     * @param observationsAtVenue Number of times the user has been observed in this context.
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

    /**
     * Applies a chain boost multiplier to a base blended score.
     * Used when a chain's trigger app was recently in the foreground.
     *
     * @param baseScore    The already-blended prediction score.
     * @param occurrences  How many times this chain has been observed (1–∞).
     */
    fun applyChainBoost(baseScore: Float, occurrences: Int): Float {
        // Boost grows logarithmically — strong at 5+ occurrences, caps around 20
        val boost = CHAIN_BOOST_BASE * (1f + (occurrences / 20f).coerceIn(0f, 1f))
        return baseScore + boost
    }

    companion object {
        private const val CHAIN_BOOST_BASE = 0.35f
    }
}
