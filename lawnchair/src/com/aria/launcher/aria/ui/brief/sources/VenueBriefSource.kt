// Copyright (c) 2026 Donovon Simpson. See LICENSE-ARIA.md for licensing terms.
package com.aria.launcher.aria.ui.brief.sources

import com.aria.launcher.aria.engine.AriaContext
import com.aria.launcher.aria.ui.brief.BriefAction
import com.aria.launcher.aria.ui.brief.BriefDataSource
import com.aria.launcher.aria.ui.brief.BriefItem
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Shows a contextual venue card when ARIA has classified the current location.
 *
 * [AriaContext.currentVenueCategory] is populated by SsidClassificationService (Session 9).
 * Returns empty until then. Venue categories are short strings: "gym", "cafe",
 * "grocery", "airport", "transit", etc.
 */
@Singleton
class VenueBriefSource @Inject constructor() : BriefDataSource {

    override val sourceId = "venue"
    override val requiresNetwork = false

    override fun isAvailable(context: AriaContext): Boolean = context.currentVenueCategory != null

    override suspend fun fetchItems(context: AriaContext): List<BriefItem> {
        val category = context.currentVenueCategory ?: return emptyList()
        val headline = headlineFor(category) ?: return emptyList()

        return listOf(
            BriefItem.VenueCard(
                venueName = category.replaceFirstChar { it.uppercase() },
                venueCategory = category,
                headline = headline,
                actions = actionsFor(category),
            ),
        )
    }

    private fun headlineFor(category: String): String? = when (category) {
        "gym" -> "Gym mode on"
        "cafe" -> "Working from a café"
        "grocery" -> "Grocery run"
        "airport" -> "Airport detected"
        "transit" -> "On transit"
        "library" -> "Library nearby"
        "hospital" -> "Medical facility"
        "restaurant" -> "At a restaurant"
        "hotel" -> "Hotel stay"
        "park" -> "At the park"
        else -> null
    }

    private fun actionsFor(category: String): List<BriefAction> = when (category) {
        "gym" -> listOf(BriefAction(label = "Log workout", intentUri = null))
        "grocery" -> listOf(BriefAction(label = "Shopping list", intentUri = null))
        "airport" -> listOf(BriefAction(label = "Travel info", intentUri = null))
        "transit" -> listOf(BriefAction(label = "Transit map", intentUri = null))
        "restaurant" -> listOf(BriefAction(label = "Menu", intentUri = null))
        else -> emptyList()
    }
}
