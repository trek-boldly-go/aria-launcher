// Copyright (c) 2026 Donovon Simpson. All rights reserved. See LICENSE-ARIA.md
package com.aria.launcher.aria.data

import javax.inject.Inject
import javax.inject.Singleton

/** Lightweight snapshot of an active status-bar notification. */
data class CachedNotification(
    val key: String,
    val packageName: String,
    val title: String?,
    val text: String?,
    /** Notification.CATEGORY_* value (e.g. "reminder", "transport"). */
    val category: String?,
    val postedAt: Long,
    /** True when the notification is a media-session transport control. */
    val isMedia: Boolean = false,
)

/**
 * In-memory cache of currently active notifications.
 *
 * Populated by AriaNotificationListener (added in Session 10).
 * BriefDataSources read from [current] to avoid direct notification API calls.
 *
 * Thread-safe: writes use @Volatile; reads happen on coroutine dispatchers.
 */
@Singleton
class ActiveNotificationCache @Inject constructor() {

    @Volatile
    private var notificationList: List<CachedNotification> = emptyList()

    val current: List<CachedNotification>
        get() = notificationList

    /** Called by AriaNotificationListener whenever the active notification set changes. */
    fun update(notifications: List<CachedNotification>) {
        notificationList = notifications
    }
}
