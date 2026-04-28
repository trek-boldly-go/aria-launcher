// Copyright (c) 2026 Donovon Simpson. See LICENSE-ARIA.md for licensing terms.
package com.aria.launcher.aria.data

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

class AriaNotificationListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName in NOISE_PACKAGES) return
        if (shouldFilter(sbn)) return

        synchronized(lock) {
            cachedNotifications[sbn.key] = sbn.toAriaNotification()
            // Prune oldest entries if cache exceeds limit
            if (cachedNotifications.size > MAX_CACHED_NOTIFICATIONS) {
                val oldest = cachedNotifications.entries
                    .sortedBy { it.value.postedTime }
                    .take(cachedNotifications.size - MAX_CACHED_NOTIFICATIONS)
                oldest.forEach { cachedNotifications.remove(it.key) }
            }
            syncToCache()
        }
        Log.d(TAG, "Notification posted: ${sbn.packageName} — ${sbn.notification.extras?.getCharSequence("android.title")}")
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        synchronized(lock) {
            cachedNotifications.remove(sbn.key)
            syncToCache()
        }
    }

    override fun onListenerConnected() {
        Log.d(TAG, "NotificationListener connected")
        synchronized(lock) {
            cachedNotifications.clear()
            val active = try {
                getActiveNotifications()
            } catch (_: Exception) {
                null
            }
            if (active != null) {
                for (sbn in active) {
                    if (sbn.packageName !in NOISE_PACKAGES && !shouldFilter(sbn)) {
                        cachedNotifications[sbn.key] = sbn.toAriaNotification()
                    }
                }
            }
        }
        Log.d(TAG, "Loaded ${cachedNotifications.size} existing notifications")
        syncToCache()
    }

    override fun onListenerDisconnected() {
        Log.d(TAG, "NotificationListener disconnected")
        synchronized(lock) {
            cachedNotifications.clear()
            syncToCache()
        }
    }

    override fun onDestroy() {
        onListenerDisconnected()
        try {
            requestUnbind()
        } catch (_: Exception) { }
        super.onDestroy()
    }

    private fun StatusBarNotification.toAriaNotification(): AriaNotification {
        val extras = notification.extras
        return AriaNotification(
            packageName = packageName,
            key = key,
            title = extras?.getCharSequence("android.title")?.toString(),
            text = extras?.getCharSequence("android.text")?.toString(),
            subText = extras?.getCharSequence("android.subText")?.toString(),
            postedTime = postTime,
            actions = notification.actions?.mapNotNull { it.title?.toString() } ?: emptyList(),
            isOngoing = isOngoing,
            category = notification.category,
        )
    }

    companion object {
        private const val TAG = "ARIA.NotifListener"
        private const val MAX_CACHED_NOTIFICATIONS = 500
        private val lock = Any()
        private val cachedNotifications = HashMap<String, AriaNotification>()

        /**
         * Static reference to the Hilt-managed [ActiveNotificationCache].
         * Set once at app startup via [BriefModule] so the system-service listener
         * can push updates to the DI-managed cache.
         */
        var notificationCacheRef: ActiveNotificationCache? = null

        /** Pushes the current notification set to [notificationCacheRef]. Must be called inside [lock]. */
        private fun syncToCache() {
            notificationCacheRef?.update(
                cachedNotifications.values.map { it.toCachedNotification() },
            )
        }

        private fun AriaNotification.toCachedNotification() = CachedNotification(
            key = key,
            packageName = packageName,
            title = title,
            text = text,
            category = category,
            postedAt = postedTime,
            isMedia = category == "transport",
        )

        fun getNotifications(): List<AriaNotification> {
            synchronized(lock) {
                return cachedNotifications.values.toList()
            }
        }

        fun getNotificationsForPackage(packageName: String): List<AriaNotification> {
            synchronized(lock) {
                return cachedNotifications.values.filter { it.packageName == packageName }
            }
        }

        /**
         * Categories worth keeping even when the notification is ongoing or
         * from a foreground service. Everything else (VPN, sync, battery
         * saver, "running in background") is filtered out.
         */
        private val MEANINGFUL_ONGOING_CATEGORIES = setOf(
            Notification.CATEGORY_TRANSPORT,
            Notification.CATEGORY_NAVIGATION,
            Notification.CATEGORY_CALL,
            Notification.CATEGORY_ALARM,
            Notification.CATEGORY_REMINDER,
            Notification.CATEGORY_MESSAGE,
        )

        private fun shouldFilter(sbn: StatusBarNotification): Boolean {
            val category = sbn.notification.category
            val flags = sbn.notification.flags
            val isForegroundService = flags and Notification.FLAG_FOREGROUND_SERVICE != 0
            if (sbn.isOngoing && category !in MEANINGFUL_ONGOING_CATEGORIES) return true
            if (isForegroundService && category !in MEANINGFUL_ONGOING_CATEGORIES) return true
            return false
        }

        private val NOISE_PACKAGES = setOf(
            "com.google.android.gms",
            "com.android.systemui",
            "com.google.android.ext.services",
            "com.google.android.providers.media.module",
            "com.google.android.inputmethod.latin",
            "com.android.vending",
            "com.google.android.permissioncontroller",
            "com.google.android.packageinstaller",
        )
    }
}
