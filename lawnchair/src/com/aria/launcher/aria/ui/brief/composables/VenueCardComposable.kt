// Copyright (c) 2026 Donovon Simpson. See LICENSE-ARIA.md for licensing terms.
package com.aria.launcher.aria.ui.brief.composables

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.aria.launcher.aria.ui.brief.BriefAction
import com.aria.launcher.aria.ui.brief.BriefItem

@Composable
fun VenueCardComposable(
    item: BriefItem.VenueCard,
    onActionClick: (BriefAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    UnifiedBriefCard(
        headline = item.headline,
        modifier = modifier,
        overlineIcon = Icons.Filled.Place,
        overlineText = item.venueName.uppercase(),
        trailingChip = if (item.actions.isNotEmpty()) {
            {
                BriefChip(
                    text = item.venueCategory.replaceFirstChar { it.uppercase() },
                    backgroundColor = MaterialTheme.colorScheme.secondaryContainer,
                    textColor = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        } else {
            null
        },
        primaryAction = item.actions.firstOrNull(),
        secondaryAction = item.actions.getOrNull(1),
        onActionClick = onActionClick,
    )
}
