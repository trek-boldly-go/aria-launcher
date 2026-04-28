// Copyright (c) 2026 Donovon Simpson. See LICENSE-ARIA.md for licensing terms.
package com.aria.launcher.aria.engine.skills

import com.aria.launcher.aria.data.AppSkill
import com.aria.launcher.aria.data.AriaNotification
import com.aria.launcher.aria.data.AriaNotificationListener
import com.aria.launcher.aria.data.SkillAction
import com.aria.launcher.aria.data.SkillResult
import com.aria.launcher.aria.engine.SkillExecutor
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class NotificationSkillExecutor(
    private val json: Json,
) : SkillExecutor {

    override val supportedSkillIds = setOf(
        "gmail.inbox_summary",
        "messages.unread",
        "media.now_playing",
    )

    override suspend fun execute(skill: AppSkill): SkillResult? {
        return when (skill.id) {
            "gmail.inbox_summary" -> executeGmailSummary(skill)
            "messages.unread" -> executeUnreadMessages(skill)
            "media.now_playing" -> executeNowPlaying(skill)
            else -> null
        }
    }

    private fun executeGmailSummary(skill: AppSkill): SkillResult? {
        val notifications = AriaNotificationListener.getNotificationsForPackage(skill.appPackage)
        if (notifications.isEmpty()) return null

        val count = notifications.size
        val subjects = notifications
            .sortedByDescending { it.postedTime }
            .take(3)
            .mapNotNull { it.title }

        val body = if (subjects.isEmpty()) {
            "$count new email${if (count > 1) "s" else ""}"
        } else {
            subjects.joinToString("\n") { "• $it" }
        }

        val actions = listOf(
            SkillAction("Open Gmail", "OPEN_APP", skill.appPackage),
        )

        return SkillResult(
            skillId = skill.id,
            title = "Gmail · $count new",
            body = body,
            actions = json.encodeToString(actions),
            priority = minOf(0.5f + count * 0.1f, 0.95f),
            timestamp = System.currentTimeMillis(),
            expiresAt = System.currentTimeMillis() + skill.refreshIntervalMin * 60 * 1000L,
        )
    }

    private fun executeUnreadMessages(skill: AppSkill): SkillResult? {
        val notifications = AriaNotificationListener.getNotificationsForPackage(skill.appPackage)
            // Only include actual messages, not system/maintenance notifications
            .filter { it.category == "msg" || it.category == null && it.isActualMessage() }
        if (notifications.isEmpty()) return null

        val count = notifications.size
        val senders = notifications
            .sortedByDescending { it.postedTime }
            .take(3)
            .mapNotNull { it.title }
            .distinct()

        val body = if (senders.isEmpty()) {
            "$count unread message${if (count > 1) "s" else ""}"
        } else {
            "From: ${senders.joinToString(", ")}"
        }

        val actions = listOf(
            SkillAction("Open Messages", "OPEN_APP", skill.appPackage),
        )

        return SkillResult(
            skillId = skill.id,
            title = "Messages · $count unread",
            body = body,
            actions = json.encodeToString(actions),
            priority = minOf(0.6f + count * 0.1f, 0.95f),
            timestamp = System.currentTimeMillis(),
            expiresAt = System.currentTimeMillis() + skill.refreshIntervalMin * 60 * 1000L,
        )
    }

    /**
     * Heuristic: a notification without CATEGORY_MESSAGE might still be a real message
     * if it doesn't match known low-value patterns (device pairing, RCS, backup, etc.).
     */
    private fun AriaNotification.isActualMessage(): Boolean {
        val combined = "${title.orEmpty()} ${text.orEmpty()}".lowercase()
        return LOW_VALUE_PATTERNS.none { it in combined } && !isOngoing
    }

    private fun executeNowPlaying(skill: AppSkill): SkillResult? {
        // Scan all notifications for active media playback (any app)
        val mediaNotification = AriaNotificationListener.getNotifications().find {
            it.category == "transport" || (it.isOngoing && it.title != null)
        } ?: return null

        val title = mediaNotification.title ?: "Unknown Track"
        val artist = mediaNotification.text ?: ""
        val sourcePackage = mediaNotification.packageName

        val actions = listOf(
            SkillAction("Open Player", "OPEN_APP", sourcePackage),
        )

        return SkillResult(
            skillId = skill.id,
            title = "Now Playing",
            body = if (artist.isNotBlank()) "$title — $artist" else title,
            actions = json.encodeToString(actions),
            priority = 0.3f,
            timestamp = System.currentTimeMillis(),
            expiresAt = System.currentTimeMillis() + skill.refreshIntervalMin * 60 * 1000L,
        )
    }

    companion object {
        /** Notification title/text substrings that indicate system noise, not real messages. */
        private val LOW_VALUE_PATTERNS = listOf(
            "device pairing",
            "paired with",
            "linked to",
            "rcs",
            "chat features",
            "connecting",
            "backing up",
            "searching for",
            "sim card",
            "default sms",
            "set up",
        )
    }
}
