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
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
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
 * Animation fires ONLY when items actually change (keys differ from previous render),
 * not on every unlock/recomposition. This is enforced by tracking previousKeys.
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
    // Track which keys have been seen before — new keys get enter animation, existing ones don't
    val seenKeys = remember { mutableStateListOf<String>() }

    LaunchedEffect(items.map { it.stableKey() }) {
        val currentKeys = items.map { it.stableKey() }.toSet()
        // Remove keys that are no longer present
        seenKeys.removeAll { it !in currentKeys }
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items.forEachIndexed { index, item ->
            val itemKey = item.stableKey()
            val isNew = itemKey !in seenKeys

            // Mark as seen after first composition
            LaunchedEffect(itemKey) {
                if (isNew) seenKeys.add(itemKey)
            }

            key(itemKey) {
                AnimatedVisibility(
                    visible = true,
                    enter = if (isNew) {
                        fadeIn(
                            animationSpec = tween(
                                durationMillis = 280,
                                delayMillis = index * 60,
                            ),
                        ) + expandVertically(
                            animationSpec = spring(
                                stiffness = Spring.StiffnessMediumLow,
                                dampingRatio = Spring.DampingRatioLowBouncy,
                            ),
                        )
                    } else {
                        // No animation for already-seen items (rapid unlock)
                        fadeIn(animationSpec = tween(0))
                    },
                    exit = fadeOut(animationSpec = tween(180)) +
                        shrinkVertically(animationSpec = tween(200)),
                ) {
                    if (item.isDismissible()) {
                        SwipeDismissWrapper(
                            onDismiss = { onItemDismiss(item) },
                        ) {
                            BriefItemCard(
                                item = item,
                                onActionClick = onActionClick,
                                onDismiss = { onItemDismiss(item) },
                            )
                        }
                    } else {
                        BriefItemCard(
                            item = item,
                            onActionClick = onActionClick,
                            onDismiss = null,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Wraps a dismissible Brief card with swipe-to-dismiss gesture.
 * Supports both start-to-end and end-to-start swipe directions.
 */
@Composable
private fun SwipeDismissWrapper(
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    @Suppress("DEPRECATION")
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value != SwipeToDismissBoxValue.Settled) {
                onDismiss()
                true
            } else {
                false
            }
        },
    )

    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {
            // Empty background — card slides off-screen cleanly
        },
        enableDismissFromStartToEnd = true,
        enableDismissFromEndToStart = true,
    ) {
        content()
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
