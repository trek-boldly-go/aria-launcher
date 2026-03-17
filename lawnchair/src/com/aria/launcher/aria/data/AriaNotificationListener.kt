// Copyright (c) 2026 Donovon Simpson. All rights reserved. See LICENSE-ARIA.md
package com.aria.launcher.aria.data

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

class AriaNotificationListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName in NOISE_PACKAGES) return
        if (sbn.isOngoing && sbn.notification.category == null) return

        synchronized(lock) {
            cachedNotifications[sbn.key] = sbn.toAriaNotification()
            // Prune oldest entries if cache exceeds limit
            if (cachedNotifications.size > MAX_CACHED_NOTIFICATIONS) {
                val oldest = cachedNotifications.entries
                    .sortedBy { it.value.postedTime }
                    .take(cachedNotifications.size - MAX_CACHED_NOTIFICATIONS)
                oldest.forEach { cachedNotifications.remove(it.key) }
            }
        }
        Log.d(TAG, "Notification posted: ${sbn.packageName} — ${sbn.notification.extras?.getString("android.title")}")
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        synchronized(lock) {
            cachedNotifications.remove(sbn.key)
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
                    if (sbn.packageName !in NOISE_PACKAGES) {
                        cachedNotifications[sbn.key] = sbn.toAriaNotification()
                    }
                }
            }
        }
        Log.d(TAG, "Loaded ${cachedNotifications.size} existing notifications")
    }

    override fun onListenerDisconnected() {
        Log.d(TAG, "NotificationListener disconnected")
        synchronized(lock) {
            cachedNotifications.clear()
        }
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
