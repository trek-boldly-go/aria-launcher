// Copyright (c) 2026 Donovon Simpson. See LICENSE-ARIA.md for licensing terms.
package com.aria.launcher.aria.ui.brief

import com.aria.launcher.aria.data.NetworkScope
import com.aria.launcher.aria.engine.AriaContext

/**
 * MCP Seam 2: Pluggable data source for Brief generation.
 *
 * Implementations produce candidate [BriefItem]s from their domain
 * (calendar, notifications, media, venue, MCP servers). The
 * [BriefAggregator] collects from all sources and applies editorial
 * ranking.
 *
 * MCP servers will implement this interface in Phase 8.
 */
interface BriefDataSource {

    /** Unique identifier for this source (e.g. "calendar", "notifications", "mcp.homeassistant"). */
    val sourceId: String

    /** Whether this source requires network access. */
    val requiresNetwork: Boolean
        get() = false

    /** Network scope required by this source. */
    val networkScope: NetworkScope
        get() = NetworkScope.ANY

    /**
     * Fetch candidate BriefItems for the given context.
     * Should not throw — return empty list on failure.
     */
    suspend fun fetchItems(context: AriaContext): List<BriefItem>

    /**
     * Whether this source is available in the given context.
     * For example, a home-server MCP source is only available on home WiFi.
     */
    fun isAvailable(context: AriaContext): Boolean = true
}
