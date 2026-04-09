// Copyright (c) 2026 Donovon Simpson. See LICENSE-ARIA.md for licensing terms.
package com.aria.launcher.aria.ui.brief.composables

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.aria.launcher.aria.ui.brief.BriefAction
import com.aria.launcher.aria.ui.brief.BriefItem

@Composable
fun ReminderNudgeCard(
    item: BriefItem.ReminderNudge,
    onActionClick: (BriefAction) -> Unit,
    onDismiss: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    UnifiedBriefCard(
        headline = item.headline,
        modifier = modifier,
        accentColor = MaterialTheme.colorScheme.primary,
        overlineIcon = Icons.Filled.Alarm,
        overlineText = "REMINDER",
        overlineColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
        subtext = item.subtext,
        primaryAction = item.action,
        dismissLabel = "Dismiss",
        onActionClick = onActionClick,
        onDismiss = onDismiss,
    )
}
