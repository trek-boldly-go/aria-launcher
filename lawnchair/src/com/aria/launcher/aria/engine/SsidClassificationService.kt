// Copyright (c) 2026 Donovon Simpson. All rights reserved. See LICENSE-ARIA.md
package com.aria.launcher.aria.engine

import android.util.Log
import com.aria.launcher.aria.data.SsidClassification
import com.aria.launcher.aria.data.SsidClassificationDao
import com.aria.launcher.aria.data.VenueCategory
import com.aria.launcher.aria.llm.ChatMessage
import com.aria.launcher.aria.llm.LlmProviderManager
import com.aria.launcher.aria.llm.LlmResult
import com.aria.launcher.aria.llm.Role
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Three-layer venue classification stack:
 *
 *   Layer 1: SSID pattern matching (instant, no ML, no LLM)
 *            ↓ if unrecognized
 *   Layer 2: LLM classification (runs ONCE per novel SSID, cached forever)
 *            ↓
 *   Layer 3: Venue affinity map (cold-start app suggestions)
 *
 * Results are cached in Room. Novel SSIDs only trigger the LLM once.
 */
@Singleton
class SsidClassificationService @Inject constructor(
    private val llmProviderManager: LlmProviderManager,
    private val dao: SsidClassificationDao,
) {
    /**
     * Returns the [VenueCategory] for this SSID.
     * Checks cache, then pattern match, then LLM (if configured).
     */
    suspend fun classifyIfNeeded(ssid: String): VenueCategory {
        val hash = ssid.sha256()

        // Check cache first — always a hit on revisit
        dao.getByHash(hash)?.let { return it.venueCategory }

        // Layer 1: pattern match (instant)
        SsidPatternMatcher.classify(ssid)?.let { category ->
            dao.insert(SsidClassification(ssidHash = hash, rawSsid = ssid, venueCategory = category))
            Log.d(TAG, "Pattern matched '$ssid' → $category")
            return category
        }

        // Layer 2: LLM fallback — runs exactly once per novel SSID
        val category = askLlmToClassify(ssid)
        dao.insert(SsidClassification(ssidHash = hash, rawSsid = ssid, venueCategory = category))
        Log.d(TAG, "LLM classified '$ssid' → $category")
        return category
    }

    private suspend fun askLlmToClassify(ssid: String): VenueCategory {
        val provider = llmProviderManager.getProvider() ?: return VenueCategory.UNKNOWN

        val result = provider.complete(
            systemPrompt = """
                Classify this WiFi network name into exactly one venue category.
                Respond with ONLY one of these exact strings, nothing else:
                FAST_FOOD, COFFEE, RETAIL, HEALTHCARE, HOTEL, TRAVEL,
                OFFICE, EDUCATION, ENTERTAINMENT, UNKNOWN
            """.trimIndent(),
            messages = listOf(ChatMessage(Role.USER, "WiFi network name: \"$ssid\"")),
            maxTokens = 16,
        )

        return when (result) {
            is LlmResult.Text -> {
                runCatching { VenueCategory.valueOf(result.content.trim()) }
                    .getOrDefault(VenueCategory.UNKNOWN)
            }

            else -> VenueCategory.UNKNOWN
        }
    }

    companion object {
        private const val TAG = "ARIA.SsidClassifier"
    }
}

internal fun String.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    return digest.digest(toByteArray()).joinToString("") { "%02x".format(it) }
}
