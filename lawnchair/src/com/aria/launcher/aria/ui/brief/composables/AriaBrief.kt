// Copyright (c) 2026 Donovon Simpson. All rights reserved. See LICENSE-ARIA.md
package com.aria.launcher.aria.ui.brief.composables

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aria.launcher.aria.ui.brief.BriefAction
import com.aria.launcher.aria.ui.brief.BriefItem

/**
 * The Brief — the home screen's primary content area.
 *
 * Up to 5 BriefItem cards in a Column (not LazyColumn — avoids nested scroll crashes).
 * Each card enters with a staggered spring animation (60ms per-item delay).
 * The spring physics (StiffnessMediumLow) gives a natural, non-mechanical feel.
 * Exit is a quick fade+shrink — dismissal should feel decisive.
 *
 * Empty is correct — show nothing if nothing is worth showing.
 */
@Composable
fun AriaBrief(
    items: List<BriefItem>,
    onActionClick: (BriefAction) -> Unit,
    onItemDismiss: (BriefItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items.forEachIndexed { index, item ->
            AnimatedVisibility(
                visible = true,
                enter = fadeIn(
                    animationSpec = tween(
                        durationMillis = 280,
                        delayMillis = index * 60,
                    ),
                ) + expandVertically(
                    animationSpec = spring(
                        stiffness = Spring.StiffnessMediumLow,
                        dampingRatio = Spring.DampingRatioLowBouncy,
                    ),
                ),
                exit = fadeOut(animationSpec = tween(180)) +
                    shrinkVertically(animationSpec = tween(200)),
            ) {
                BriefItemCard(
                    item = item,
                    onActionClick = onActionClick,
                    onDismiss = if (item.isDismissible()) {
                        { onItemDismiss(item) }
                    } else {
                        null
                    },
                )
            }
        }
    }
}

@Composable
private fun BriefItemCard(
    item: BriefItem,
    onActionClick: (BriefAction) -> Unit,
    onDismiss: (() -> Unit)?,
) {
    when (item) {
        is BriefItem.AlertAssessed ->
            AlertAssessedCard(item, onActionClick, onDismiss)

        is BriefItem.ReminderNudge ->
            ReminderNudgeCard(item, onActionClick, onDismiss)

        is BriefItem.CalendarEvent ->
            CalendarEventCard(item, onActionClick)

        is BriefItem.MediaResume ->
            MediaResumeCard(item, onActionClick)

        is BriefItem.LiveDataCard ->
            LiveDataBriefCard(item, onActionClick)

        is BriefItem.ProactiveSuggestion ->
            ProactiveSuggestionCard(item, onActionClick, onDismiss)

        is BriefItem.VenueCard ->
            VenueCardComposable(item, onActionClick)

        is BriefItem.ContextBar -> {
            // ContextBar renders in the greeting area, not in the Brief list
        }
    }
}

/** Stub for MCP live data cards — wired up fully in Session 8+ MCP phase. */
@Composable
private fun LiveDataBriefCard(
    item: BriefItem.LiveDataCard,
    onActionClick: (BriefAction) -> Unit,
) {
    BriefCard {
        Text(
            text = item.headline,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (item.subtext != null) {
            Text(
                text = item.subtext,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (item.action != null) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Spacer(modifier = Modifier.weight(1f))
                TextButton(onClick = { onActionClick(item.action) }) {
                    Text(
                        text = item.action.label,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
    }
}
