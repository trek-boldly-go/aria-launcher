// Copyright (c) 2026 Donovon Simpson. All rights reserved. See LICENSE-ARIA.md
package com.aria.launcher.aria.ui

/**
 * Reorders a scored list of items into a grid where the highest-scored items
 * land in the thumb-zone positions (bottom-right for right-handed, bottom-left
 * for left-handed users).
 *
 * Based on Fitts's law and mobile ergonomics research showing most taps land
 * in the bottom-right quadrant for right-handed users.
 */
object ThumbZoneLayout {

    /**
     * Reorder [items] for a grid with [columns] columns so that the highest-scored
     * items appear in the thumb-friendly zone.
     *
     * @param isRightHanded true for right-handed (bottom-right priority), false for left-handed
     */
    fun <T> reorder(
        items: List<T>,
        columns: Int,
        isRightHanded: Boolean = true,
        score: (T) -> Float,
    ): List<T> {
        if (items.size <= 1 || columns <= 0) return items

        val rows = (items.size + columns - 1) / columns
        val totalSlots = rows * columns
        val sorted = items.sortedByDescending { score(it) }

        // Build priority order: bottom rows first, then right (or left) columns
        val slotPriority = (0 until totalSlots).sortedWith(
            compareByDescending<Int> { it / columns } // bottom rows first
                .thenBy { slot ->
                    val col = slot % columns
                    if (isRightHanded) columns - 1 - col else col
                },
        )

        val result = MutableList<T?>(totalSlots) { null }
        for ((i, item) in sorted.withIndex()) {
            if (i >= slotPriority.size) break
            result[slotPriority[i]] = item
        }

        return result.filterNotNull()
    }
}
