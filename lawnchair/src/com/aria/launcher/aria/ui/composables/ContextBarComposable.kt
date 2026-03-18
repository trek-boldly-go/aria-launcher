// Copyright (c) 2026 Donovon Simpson. All rights reserved. See LICENSE-ARIA.md
package com.aria.launcher.aria.ui.composables

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aria.launcher.aria.ui.brief.BriefItem
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Always-visible context line below the greeting.
 *
 * Renders the spec format: "Sunday · Lisle · 34°F Snow"
 * Falls back to just the day name + date when weather or location are unavailable.
 *
 * This is NOT in the scrollable Brief — it lives in the greeting area
 * and is always visible when the home screen is showing.
 */
@Composable
fun ContextBarComposable(
    contextBar: BriefItem.ContextBar?,
    modifier: Modifier = Modifier,
) {
    val today = LocalDate.now()
    val dayName = remember(today) {
        today.format(DateTimeFormatter.ofPattern("EEEE", Locale.getDefault()))
    }
    val dateFallback = remember(today) {
        today.format(DateTimeFormatter.ofPattern("EEE, MMM d", Locale.getDefault()))
    }

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val contextLine = buildContextLine(
            dayName = dayName,
            dateFallback = dateFallback,
            contextBar = contextBar,
        )

        Text(
            text = contextLine,
            style = MaterialTheme.typography.bodyMedium.copy(
                letterSpacing = 0.1.sp,
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
        )

        // Location pill — only when weather is present (otherwise location is in the line)
        if (contextBar?.locationHint != null && contextBar.weatherLine.isBlank()) {
            Spacer(modifier = Modifier.width(8.dp))
            LocationPill(contextBar.locationHint)
        }
    }
}

/**
 * Builds the context line string.
 *
 * With weather + location: "Sunday · Lisle · 34°F Snow"
 * With weather only:       "Sunday · 34°F Snow"
 * Without weather:         "Sun, Mar 15" + optional location pill
 */
private fun buildContextLine(
    dayName: String,
    dateFallback: String,
    contextBar: BriefItem.ContextBar?,
): String {
    if (contextBar == null || contextBar.weatherLine.isBlank()) {
        return dateFallback
    }

    val parts = mutableListOf(dayName)
    if (contextBar.locationHint != null) {
        parts.add(contextBar.locationHint)
    }
    parts.add(contextBar.weatherLine)
    return parts.joinToString(" \u00B7 ")
}

@Composable
private fun LocationPill(locationHint: String) {
    Box(
        modifier = Modifier
            .background(
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                shape = RoundedCornerShape(10.dp),
            )
            .padding(horizontal = 7.dp, vertical = 2.dp),
    ) {
        Text(
            text = locationHint,
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.Medium,
            ),
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f),
        )
    }
}
