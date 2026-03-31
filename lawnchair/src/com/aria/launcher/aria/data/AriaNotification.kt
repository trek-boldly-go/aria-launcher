// Copyright (c) 2026 Donovon Simpson. See LICENSE-ARIA.md for licensing terms.
package com.aria.launcher.aria.data

data class AriaNotification(
    val packageName: String,
    val key: String,
    val title: String?,
    val text: String?,
    val subText: String?,
    val postedTime: Long,
    val actions: List<String>,
    val isOngoing: Boolean,
    val category: String?,
)
