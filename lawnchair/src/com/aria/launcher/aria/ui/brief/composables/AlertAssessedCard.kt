// Copyright (c) 2026 Donovon Simpson. See LICENSE-ARIA.md for licensing terms.
package com.aria.launcher.aria.ui.brief.composables

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.aria.launcher.aria.ui.brief.AlertSeverity
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

@Composable
internal fun severityColor(severity: AlertSeverity): Color = when (severity) {
    AlertSeverity.CRITICAL -> MaterialTheme.colorScheme.error
    AlertSeverity.WARNING -> MaterialTheme.colorScheme.tertiary
    AlertSeverity.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
}
