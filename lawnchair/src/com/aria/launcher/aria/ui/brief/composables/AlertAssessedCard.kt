// Copyright (c) 2026 Donovon Simpson. All rights reserved. See LICENSE-ARIA.md
package com.aria.launcher.aria.ui.brief.composables

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aria.launcher.aria.ui.brief.AlertSeverity
import com.aria.launcher.aria.ui.brief.BriefAction
import com.aria.launcher.aria.ui.brief.BriefItem

/**
 * Alert card with left severity strip and inline severity chip.
 *
 * Design: ARIA's verdict, not the raw alert. Strip color signals urgency
 * at a glance — red for critical, amber for warning, muted for info.
 * No icon — the strip IS the icon.
 */
@Composable
fun AlertAssessedCard(
    item: BriefItem.AlertAssessed,
    onActionClick: (BriefAction) -> Unit,
    onDismiss: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val accentColor = severityColor(item.severity)

    BriefCard(modifier = modifier, accentColor = accentColor) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.headline,
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.SemiBold,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (item.subtext != null) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = item.subtext,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Severity chip — concise signal, right-aligned
            Box(
                modifier = Modifier
                    .background(
                        color = accentColor.copy(alpha = 0.12f),
                        shape = RoundedCornerShape(6.dp),
                    )
                    .padding(horizontal = 7.dp, vertical = 3.dp),
            ) {
                Text(
                    text = when (item.severity) {
                        AlertSeverity.CRITICAL -> "Critical"
                        AlertSeverity.WARNING -> "Warning"
                        AlertSeverity.INFO -> "Info"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = accentColor,
                )
            }
        }

        if (item.action != null || onDismiss != null) {
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                if (onDismiss != null) {
                    TextButton(
                        onClick = onDismiss,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        Text(
                            text = "Dismiss",
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
                if (item.action != null) {
                    Spacer(modifier = Modifier.width(4.dp))
                    FilledTonalButton(
                        onClick = { onActionClick(item.action) },
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                    ) {
                        Text(
                            text = item.action.label,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun severityColor(severity: AlertSeverity): Color = when (severity) {
    AlertSeverity.CRITICAL -> MaterialTheme.colorScheme.error
    AlertSeverity.WARNING -> MaterialTheme.colorScheme.tertiary
    AlertSeverity.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
}
