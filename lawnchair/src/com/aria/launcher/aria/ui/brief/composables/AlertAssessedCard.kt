// Copyright (c) 2026 Donovon Simpson. See LICENSE-ARIA.md for licensing terms.
package com.aria.launcher.aria.ui.brief.composables

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.aria.launcher.aria.ui.brief.AlertSeverity
import com.aria.launcher.aria.ui.brief.BriefAccent
import com.aria.launcher.aria.ui.brief.BriefAction
import com.aria.launcher.aria.ui.brief.BriefItem

@Composable
fun AlertAssessedCard(
    item: BriefItem.AlertAssessed,
    onActionClick: (BriefAction) -> Unit,
    onDismiss: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val accentColor = severityColor(item.severity)
    val severityLabel = when (item.severity) {
        AlertSeverity.CRITICAL -> "Critical"
        AlertSeverity.WARNING -> "Warning"
        AlertSeverity.INFO -> "Info"
    }

    UnifiedBriefCard(
        headline = item.headline,
        modifier = modifier,
        accentColor = accentColor,
        trailingChip = {
            BriefChip(
                text = severityLabel,
                backgroundColor = accentColor.copy(alpha = 0.12f),
                textColor = accentColor,
            )
        },
        subtext = item.subtext,
        primaryAction = item.action,
        dismissLabel = "Dismiss",
        onActionClick = onActionClick,
        onDismiss = onDismiss,
    )
}

/** Accent bar (left strip) color for a [BriefAccent] level. */
@Composable
fun briefAccentColor(accent: BriefAccent): Color = when (accent) {
    BriefAccent.CRITICAL -> MaterialTheme.colorScheme.error
    BriefAccent.WARNING -> MaterialTheme.colorScheme.tertiary
    BriefAccent.ACTION -> Color(0xFFE65100)
    BriefAccent.ACTED -> Color(0xFF1B5E20)
    BriefAccent.TIMELY -> MaterialTheme.colorScheme.primary
    BriefAccent.NEUTRAL -> Color.Unspecified
}

/** Lighter overline-text color for a [BriefAccent] level. */
@Composable
fun briefOverlineColor(accent: BriefAccent): Color = when (accent) {
    BriefAccent.CRITICAL -> MaterialTheme.colorScheme.error
    BriefAccent.WARNING -> MaterialTheme.colorScheme.tertiary
    BriefAccent.ACTION -> Color(0xFFFFA726)
    BriefAccent.ACTED -> Color(0xFF4CAF50)
    BriefAccent.TIMELY -> MaterialTheme.colorScheme.primary
    BriefAccent.NEUTRAL -> Color.Unspecified
}

@Composable
internal fun severityColor(severity: AlertSeverity): Color = when (severity) {
    AlertSeverity.CRITICAL -> MaterialTheme.colorScheme.error
    AlertSeverity.WARNING -> MaterialTheme.colorScheme.tertiary
    AlertSeverity.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
}
