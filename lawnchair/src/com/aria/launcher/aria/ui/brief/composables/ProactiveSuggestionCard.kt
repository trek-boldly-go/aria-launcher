// Copyright (c) 2026 Donovon Simpson. See LICENSE-ARIA.md for licensing terms.
package com.aria.launcher.aria.ui.brief.composables

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.aria.launcher.aria.ui.brief.BriefAction
import com.aria.launcher.aria.ui.brief.BriefItem

@Composable
fun ProactiveSuggestionCard(
    item: BriefItem.ProactiveSuggestion,
    onActionClick: (BriefAction) -> Unit,
    onDismiss: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    UnifiedBriefCard(
        headline = item.headline,
        modifier = modifier,
        overlineText = "ARIA",
        overlineColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
        overlineLetterSpacing = 1.2f,
        subtext = item.rationale.takeIf { it.isNotBlank() },
        primaryAction = item.action,
        dismissLabel = "Not now",
        onActionClick = onActionClick,
        onDismiss = onDismiss,
    )
}
