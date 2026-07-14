// Copyright (c) 2026 Donovon Simpson. See LICENSE-ARIA.md for licensing terms.
package com.aria.launcher.aria.ui.brief.composables

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aria.launcher.aria.ui.brief.BriefAccent
import com.aria.launcher.aria.ui.brief.BriefAction
import com.aria.launcher.aria.ui.brief.BriefItem

@Composable
fun CalendarEventCard(
    item: BriefItem.CalendarEvent,
    onActionClick: (BriefAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    UnifiedBriefCard(
        headline = item.title,
        modifier = modifier,
        accentColor = briefAccentColor(BriefAccent.TIMELY).copy(alpha = 0.75f),
        overlineText = item.timeDescription,
        overlineColor = briefAccentColor(BriefAccent.TIMELY),
        overlineLetterSpacing = 0.3f,
        subtextContent = if (item.location != null) {
            {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.Place,
                        contentDescription = null,
                        modifier = Modifier.size(11.dp),
                        tint = BriefCardDefaults.mutedColor,
                    )
                    Spacer(modifier = Modifier.width(3.dp))
                    Text(
                        text = item.location,
                        style = MaterialTheme.typography.bodySmall,
                        color = BriefCardDefaults.subtitleColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        } else {
            null
        },
        trailingWidget = {
            FilledTonalButton(
                onClick = { onActionClick(item.primaryAction) },
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
            ) {
                Text(
                    text = item.primaryAction.label,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        },
        onActionClick = onActionClick,
    )
}
