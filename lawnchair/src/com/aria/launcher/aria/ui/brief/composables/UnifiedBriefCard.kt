// Copyright (c) 2026 Donovon Simpson. See LICENSE-ARIA.md for licensing terms.
package com.aria.launcher.aria.ui.brief.composables

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aria.launcher.aria.ui.brief.BriefAction

/**
 * Unified Brief card layout with three optional zones:
 *
 * Zone 1 (overline): icon + overline text ................. trailing chip
 * Zone 2 (body):     leading media | headline + subtext ... trailing widget
 * Zone 3 (actions):  ........................ dismiss + primary action
 *
 * All Brief card types render through this single composable, filling only the
 * slots relevant to their type. This guarantees consistent text hierarchy,
 * spacing, and action placement across the entire Brief.
 */
@Composable
fun UnifiedBriefCard(
    headline: String,
    modifier: Modifier = Modifier,
    accentColor: Color = Color.Unspecified,
    overlineIcon: ImageVector? = null,
    overlineText: String? = null,
    overlineColor: Color = BriefCardDefaults.mutedColor,
    overlineLetterSpacing: Float = 0.8f,
    trailingChip: (@Composable () -> Unit)? = null,
    leadingMedia: (@Composable () -> Unit)? = null,
    subtext: String? = null,
    subtextContent: (@Composable () -> Unit)? = null,
    trailingWidget: (@Composable () -> Unit)? = null,
    primaryAction: BriefAction? = null,
    secondaryAction: BriefAction? = null,
    dismissLabel: String? = null,
    onActionClick: (BriefAction) -> Unit = {},
    onDismiss: (() -> Unit)? = null,
) {
    BriefCard(modifier = modifier, accentColor = accentColor) {
        val hasOverline = overlineText != null || trailingChip != null

        // Zone 1: Overline row
        if (hasOverline) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (overlineIcon != null) {
                    Icon(
                        imageVector = overlineIcon,
                        contentDescription = null,
                        modifier = Modifier.size(12.dp),
                        tint = overlineColor,
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                }
                if (overlineText != null) {
                    Text(
                        text = overlineText,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = overlineLetterSpacing.sp,
                        ),
                        color = overlineColor,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                } else {
                    Spacer(modifier = Modifier.weight(1f))
                }
                if (trailingChip != null) {
                    Spacer(modifier = Modifier.width(8.dp))
                    trailingChip()
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
        }

        // Zone 2: Body row (optional leading media + text + optional trailing widget)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leadingMedia != null) {
                leadingMedia()
                Spacer(modifier = Modifier.width(12.dp))
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = headline,
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.SemiBold,
                    ),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtext != null) {
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = subtext,
                        style = MaterialTheme.typography.bodySmall,
                        color = BriefCardDefaults.subtitleColor,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (subtextContent != null) {
                    Spacer(modifier = Modifier.height(3.dp))
                    subtextContent()
                }
            }

            if (trailingWidget != null) {
                Spacer(modifier = Modifier.width(12.dp))
                trailingWidget()
            }
        }

        // Zone 3: Action row
        val hasDismiss = dismissLabel != null && onDismiss != null
        if (primaryAction != null || secondaryAction != null || hasDismiss) {
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                if (hasDismiss) {
                    TextButton(
                        onClick = onDismiss!!,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        Text(
                            text = dismissLabel!!,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                }
                if (secondaryAction != null) {
                    TextButton(
                        onClick = { onActionClick(secondaryAction) },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        Text(
                            text = secondaryAction.label,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                }
                if (primaryAction != null) {
                    FilledTonalButton(
                        onClick = { onActionClick(primaryAction) },
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                    ) {
                        Text(
                            text = primaryAction.label,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }
        }
    }
}
