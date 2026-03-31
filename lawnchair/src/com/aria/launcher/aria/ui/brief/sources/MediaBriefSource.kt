// Copyright (c) 2026 Donovon Simpson. See LICENSE-ARIA.md for licensing terms.
package com.aria.launcher.aria.ui.brief.sources

import android.app.Notification
import com.aria.launcher.aria.data.ActiveNotificationCache
import com.aria.launcher.aria.data.CachedNotification
import com.aria.launcher.aria.engine.AriaContext
import com.aria.launcher.aria.ui.brief.BriefAction
import com.aria.launcher.aria.ui.brief.BriefDataSource
import com.aria.launcher.aria.ui.brief.BriefItem
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Surfaces the most recent active media session as a resumable BriefItem.MediaResume card.
 *
 * Shows at most 1 media card — the most recently posted media notification.
 * Album art (thumbnailUri) and session metadata are enriched in Session 10
 * when AriaNotificationListener has full media session access.
 *
 * Requires AriaNotificationListener (Session 10) to populate [ActiveNotificationCache].
 * Returns empty list until the listener is registered.
 */
@Singleton
class MediaBriefSource @Inject constructor(
    private val notificationCache: ActiveNotificationCache,
) : BriefDataSource {

    override val sourceId = "media"

    override suspend fun fetchItems(context: AriaContext): List<BriefItem> {
        val mediaNotification = notificationCache.current
            .filter { it.isMedia || it.category == Notification.CATEGORY_TRANSPORT }
            .maxByOrNull { it.postedAt }
            ?: return emptyList()

        return listOf(mediaNotification.toMediaItem())
    }

    private fun CachedNotification.toMediaItem(): BriefItem.MediaResume = BriefItem.MediaResume(
        title = title ?: "Media",
        subtitle = text ?: "",
        thumbnailUri = null, // album art pulled from MediaSession in Session 10
        resumeAction = BriefAction(
            label = "Resume",
            intentUri = "package:$packageName",
        ),
    )
}
