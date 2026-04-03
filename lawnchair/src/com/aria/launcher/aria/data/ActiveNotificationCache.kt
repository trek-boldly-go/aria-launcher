// Copyright (c) 2026 Donovon Simpson. See LICENSE-ARIA.md for licensing terms.
package com.aria.launcher.aria.data

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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
 * Populated by AriaNotificationListener via a static reference bridge.
 * BriefDataSources read from [current] to avoid direct notification API calls.
 * [changeSignal] emits on every update so observers can reactively refresh.
 *
 * Thread-safe: writes use @Volatile; reads happen on coroutine dispatchers.
 */
@Singleton
class ActiveNotificationCache @Inject constructor() {

    @Volatile
    private var notificationList: List<CachedNotification> = emptyList()

    private val _changeSignal = MutableStateFlow(0L)

    /** Emits an incrementing value each time the notification set changes. */
    val changeSignal: StateFlow<Long> = _changeSignal.asStateFlow()

    val current: List<CachedNotification>
        get() = notificationList

    /** Called by AriaNotificationListener whenever the active notification set changes. */
    fun update(notifications: List<CachedNotification>) {
        notificationList = notifications
        _changeSignal.value++
    }
}
