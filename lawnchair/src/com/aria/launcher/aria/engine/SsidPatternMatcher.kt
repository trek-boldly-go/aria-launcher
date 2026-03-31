// Copyright (c) 2026 Donovon Simpson. See LICENSE-ARIA.md for licensing terms.
package com.aria.launcher.aria.engine

import com.aria.launcher.aria.data.VenueCategory

private val CI = setOf(RegexOption.IGNORE_CASE)

/**
 * Layer 1 of the three-layer venue intelligence stack.
 * Instant regex classification — no ML, no LLM.
 * Returns null if no pattern matches, which triggers Layer 2 (LLM classification).
 */
object SsidPatternMatcher {

    private val patterns: List<Pair<Regex, VenueCategory>> = listOf(
        // Fast food
        Regex("mcdonald|mcdonalds|mcds", CI) to VenueCategory.FAST_FOOD,
        Regex("burger.?king", CI) to VenueCategory.FAST_FOOD,
        Regex("taco.?bell", CI) to VenueCategory.FAST_FOOD,
        Regex("chick.?fil.?a|cfaone", CI) to VenueCategory.FAST_FOOD,
        Regex("wendy|popeyes|kfc|subway|chipotle|panda.?express", CI) to VenueCategory.FAST_FOOD,
        Regex("five.?guys|whataburger|sonic.?drive", CI) to VenueCategory.FAST_FOOD,

        // Coffee
        Regex("starbucks", CI) to VenueCategory.COFFEE,
        Regex("dunkin", CI) to VenueCategory.COFFEE,
        Regex("tim.?hortons|caribou.?coffee|peet.?s", CI) to VenueCategory.COFFEE,
        Regex("panera", CI) to VenueCategory.COFFEE,

        // Retail
        Regex("target|walmart|costco|sam.?s.?club", CI) to VenueCategory.RETAIL,
        Regex("kroger|meijer|aldi|whole.?foods|trader.?joe", CI) to VenueCategory.RETAIL,
        Regex("walgreens|cvs.?pharmacy|rite.?aid", CI) to VenueCategory.RETAIL,
        Regex("best.?buy|home.?depot|lowe.?s|ikea", CI) to VenueCategory.RETAIL,
        Regex("nordstrom|macys|kohls|tj.?maxx", CI) to VenueCategory.RETAIL,

        // Healthcare
        Regex("hospital|medical|health|clinic", CI) to VenueCategory.HEALTHCARE,
        Regex("mayo.?clinic|cedar.?sinai|northwestern.?med", CI) to VenueCategory.HEALTHCARE,
        Regex("walgreens.?health|cvs.?health|urgent.?care", CI) to VenueCategory.HEALTHCARE,
        Regex("dental|dentist|orthodon", CI) to VenueCategory.HEALTHCARE,

        // Hotel
        Regex("hilton|hampton.?inn|doubletree", CI) to VenueCategory.HOTEL,
        Regex("marriott|sheraton|westin|w.?hotel|ritz.?carlton", CI) to VenueCategory.HOTEL,
        Regex("hyatt|ihg|holiday.?inn|crowne.?plaza|intercontinental", CI) to VenueCategory.HOTEL,
        Regex("airbnb|vrbo|guest.?wifi|hotel.?guest", CI) to VenueCategory.HOTEL,

        // Travel
        Regex("airport|terminal.?wifi|gate.?wifi", CI) to VenueCategory.TRAVEL,
        Regex("united.?(wifi|airlines?)|delta.?(wifi|airlines?)", CI) to VenueCategory.TRAVEL,
        Regex("southwest.?wifi|american.?air", CI) to VenueCategory.TRAVEL,
        Regex("amtrak|greyhound|megabus", CI) to VenueCategory.TRAVEL,

        // Education
        Regex("university|college|\\.edu\\b|student.?wifi", CI) to VenueCategory.EDUCATION,
        Regex("eduroam", CI) to VenueCategory.EDUCATION,
        Regex("library|public.?library", CI) to VenueCategory.EDUCATION,

        // Entertainment
        Regex("amc.?theatre|regal|cinemark", CI) to VenueCategory.ENTERTAINMENT,
        Regex("stadium|arena|concert|venue.?wifi", CI) to VenueCategory.ENTERTAINMENT,
        Regex("bowlero|main.?event|dave.?buster", CI) to VenueCategory.ENTERTAINMENT,
        Regex("gym|planet.?fitness|la.?fitness|anytime.?fitness|ymca", CI) to VenueCategory.ENTERTAINMENT,
    )

    /** Returns null if no pattern matches — triggers Layer 2 LLM classification. */
    fun classify(ssid: String): VenueCategory? = patterns.firstOrNull { (regex, _) -> regex.containsMatchIn(ssid) }?.second
}
