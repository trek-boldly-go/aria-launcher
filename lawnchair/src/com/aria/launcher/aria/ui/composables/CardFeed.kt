// Copyright (c) 2026 Donovon Simpson. All rights reserved. See LICENSE-ARIA.md
package com.aria.launcher.aria.ui.composables

import android.graphics.drawable.Drawable
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.InfiniteTransition
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.launcher3.R
import com.aria.launcher.aria.data.SkillAction
import com.aria.launcher.aria.ui.CardFeedItem
import com.aria.launcher.aria.ui.PredictedApp

@Composable
fun CardFeed(
    cards: List<CardFeedItem>,
    predictedApps: List<PredictedApp>,
    onCardClick: (CardFeedItem) -> Unit,
    onActionClick: (SkillAction) -> Unit,
    onAppClick: (String) -> Unit,
    onChatTap: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Chat pill — always first, visually distinct
        if (onChatTap != null) {
            ChatPill(onClick = onChatTap)
        }

        // Cards section
        if (cards.isEmpty()) {
            EmptyStateCard()
        } else {
            for ((index, card) in cards.withIndex()) {
                key(card.skillId) {
                    AnimatedVisibility(
                        visible = true,
                        enter = fadeIn(
                            animationSpec = tween(
                                durationMillis = 300,
                                delayMillis = index * 50,
                            ),
                        ) + slideInVertically(
                            animationSpec = tween(
                                durationMillis = 300,
                                delayMillis = index * 50,
                            ),
                            initialOffsetY = { it / 4 },
                        ),
                    ) {
                        SkillCard(
                            title = card.title,
                            body = card.body,
                            actions = card.actions,
                            appIcon = card.appIcon,
                            onCardClick = { onCardClick(card) },
                            onActionClick = onActionClick,
                        )
                    }
                }
            }
        }

        // Predicted apps section with label
        if (predictedApps.isNotEmpty()) {
            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "Suggested",
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.8.sp,
                    fontSize = 11.sp,
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.padding(start = 4.dp, bottom = 2.dp),
            )

            PredictedAppsRow(
                apps = predictedApps,
                onAppClick = onAppClick,
            )
        }
    }
}

@Composable
private fun ChatPill(onClick: () -> Unit) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2000),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulseAlpha",
    )

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Pulsing dot — ARIA is alive and ready
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .graphicsLayer { alpha = pulseAlpha }
                    .background(MaterialTheme.colorScheme.primary),
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = "Ask ARIA anything\u2026",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Icon(
                painter = painterResource(id = R.drawable.ic_allapps_search),
                contentDescription = "Chat with ARIA",
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            )
        }
    }
}
