// Copyright (c) 2026 Donovon Simpson. See LICENSE-ARIA.md for licensing terms.
package com.aria.launcher.aria.ui.brief.sources

import android.app.Notification
import com.aria.launcher.aria.data.ActiveNotificationCache
import com.aria.launcher.aria.data.CachedNotification
import com.aria.launcher.aria.engine.AppLabelResolver
import com.aria.launcher.aria.engine.AriaContext
import com.aria.launcher.aria.ui.brief.AlertSeverity
import com.aria.launcher.aria.ui.brief.BriefAction
import com.aria.launcher.aria.ui.brief.BriefDataSource
import com.aria.launcher.aria.ui.brief.BriefItem
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Synthesizes active notifications into grouped, action-oriented Brief cards.
 *
 * Groups notifications by app and produces a single card per app with an
 * action-oriented headline (e.g., "3 unread in Discord") instead of forwarding
 * raw notification text. The notification shade already shows full details —
 * the Brief should tell the user what to DO about them.
 *
 * Reminders and alarms are treated individually since they are inherently actionable.
 */
@Singleton
class NotificationBriefSource @Inject constructor(
    private val notificationCache: ActiveNotificationCache,
    private val appLabelResolver: AppLabelResolver,
) : BriefDataSource {

    override val sourceId = "notifications"

    override suspend fun fetchItems(context: AriaContext): List<BriefItem> {
        val notifications = notificationCache.current
            .filter { !it.isMedia }
            .filter { it.title != null }
        if (notifications.isEmpty()) return emptyList()

        val items = mutableListOf<BriefItem>()

        // Reminders and alarms get individual cards — they're inherently actionable
        val (actionable, grouped) = notifications.partition { it.isActionableCategory() }
        actionable.take(2).mapNotNullTo(items) { it.toActionableItem() }

        // Everything else gets grouped by app
        grouped
            .groupBy { it.packageName }
            .entries
            .sortedByDescending { it.value.size }
            .take(2 - items.size.coerceAtMost(2))
            .forEach { (pkg, appNotifs) ->
                items.add(buildGroupedCard(pkg, appNotifs))
            }

        return items.take(2)
    }

    private fun CachedNotification.isActionableCategory(): Boolean = category == Notification.CATEGORY_REMINDER || category == Notification.CATEGORY_ALARM

    private fun CachedNotification.toActionableItem(): BriefItem? {
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

            else -> null
        }
    }

    private fun buildGroupedCard(
        packageName: String,
        notifications: List<CachedNotification>,
    ): BriefItem {
        val appLabel = appLabelResolver.resolve(packageName)
        val count = notifications.size
        val headline = if (count == 1) {
            // Single notification: use a short action hint
            val title = notifications.first().title ?: appLabel
            if (title.length <= 30) title else "$appLabel notification"
        } else {
            "$count unread in $appLabel"
        }

        return BriefItem.AlertAssessed(
            icon = "notifications",
            headline = headline,
            subtext = if (count == 1) notifications.first().text else "Tap to review",
            severity = AlertSeverity.INFO,
            action = BriefAction(
                label = "Open",
                intentUri = "package:$packageName",
            ),
        )
    }
}
