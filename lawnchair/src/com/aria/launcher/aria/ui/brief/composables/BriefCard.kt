// Copyright (c) 2026 Donovon Simpson. All rights reserved. See LICENSE-ARIA.md
package com.aria.launcher.aria.ui.brief.composables

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Centralised color tokens for Brief card content.
 *
 * Uses [MaterialTheme.colorScheme.onSurface] (not onSurfaceVariant) so text
 * stays readable on the translucent card surface, even over dark wallpapers.
 */
object BriefCardDefaults {
    /** Primary text — high contrast on the translucent card. */
    val titleColor: Color
        @Composable get() = MaterialTheme.colorScheme.onSurface

    /** Secondary text — softer but still readable over any wallpaper. */
    val subtitleColor: Color
        @Composable get() = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)

    /** Tertiary elements — icons, timestamps, least prominent text. */
    val mutedColor: Color
        @Composable get() = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
}

/**
 * Shared container for all Brief item cards.
 *
 * Visual spec:
 * - 20dp corners (premium, slightly more rounded than M3 default)
 * - 85% opacity surface — wallpaper breathes through
 * - 0.5dp luminous border (10% alpha) — subtle glass edge
 * - Optional 3dp left accent strip for semantic color coding
 *   (severity on alerts, primary on calendar, etc.)
 */
@Composable
fun BriefCard(
    modifier: Modifier = Modifier,
    accentColor: Color = Color.Unspecified,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
        tonalElevation = 0.dp,
        border = BorderStroke(
            width = 0.5.dp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f),
        ),
    ) {
        // IntrinsicSize.Min lets the accent strip fill the card's exact height
        Row(modifier = Modifier.height(IntrinsicSize.Min)) {
            if (accentColor != Color.Unspecified) {
                Box(
                    modifier = Modifier
                        .width(3.dp)
                        .fillMaxHeight()
                        .background(accentColor),
                )
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = if (accentColor != Color.Unspecified) 13.dp else 16.dp,
                        end = 16.dp,
                        top = 14.dp,
                        bottom = 14.dp,
                    ),
                content = content,
            )
        }
    }
}
