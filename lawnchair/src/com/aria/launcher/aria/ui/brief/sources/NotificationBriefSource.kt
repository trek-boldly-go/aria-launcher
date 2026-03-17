// Copyright (c) 2026 Donovon Simpson. All rights reserved. See LICENSE-ARIA.md
package com.aria.launcher.aria.ui.brief.sources

import android.app.Notification
import com.aria.launcher.aria.data.ActiveNotificationCache
import com.aria.launcher.aria.data.CachedNotification
import com.aria.launcher.aria.engine.AriaContext
import com.aria.launcher.aria.ui.brief.AlertSeverity
import com.aria.launcher.aria.ui.brief.BriefAction
import com.aria.launcher.aria.ui.brief.BriefDataSource
import com.aria.launcher.aria.ui.brief.BriefItem
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Synthesizes active notifications into AlertAssessed or ReminderNudge cards.
 *
 * ARIA does not forward raw notification text — it assesses each alert and shows
 * its own verdict. LLM synthesis is added in Session 9. Until then, classification
 * is heuristic-based on Notification.CATEGORY_*.
 *
 * Requires AriaNotificationListener (Session 10) to populate [ActiveNotificationCache].
 * Returns empty list until the listener is registered.
 */
@Singleton
class NotificationBriefSource @Inject constructor(
    private val notificationCache: ActiveNotificationCache,
) : BriefDataSource {

    override val sourceId = "notifications"

    override suspend fun fetchItems(context: AriaContext): List<BriefItem> {
        val notifications = notificationCache.current
        if (notifications.isEmpty()) return emptyList()

        return notifications
            .filter { !it.isMedia } // media handled by MediaBriefSource
            .filter { it.title != null } // skip contentless notifications
            .take(2) // max 2 alert cards in Brief
            .mapNotNull { it.toAssessedItem() }
    }

    private fun CachedNotification.toAssessedItem(): BriefItem? {
        val headline = title ?: return null
        return when (category) {
            Notification.CATEGORY_REMINDER ->
                BriefItem.ReminderNudge(
                    icon = "alarm",
                    headline = headline,
                    subtext = text,
                    action = BriefAction(
                        label = "Open",
                        intentUri = "package:$packageName",
                    ),
                )

            Notification.CATEGORY_ALARM ->
                BriefItem.AlertAssessed(
                    icon = "alarm",
                    headline = headline,
                    subtext = text,
                    severity = AlertSeverity.WARNING,
                    action = BriefAction(label = "Dismiss", intentUri = null),
                )

            else ->
                BriefItem.AlertAssessed(
                    icon = "notifications",
                    headline = headline,
                    subtext = text,
                    severity = AlertSeverity.INFO,
                    action = BriefAction(
                        label = "Open",
                        intentUri = "package:$packageName",
                    ),
                )
        }
    }
}
