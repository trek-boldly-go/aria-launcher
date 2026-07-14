// Copyright (c) 2026 Donovon Simpson. See LICENSE-ARIA.md for licensing terms.
package com.aria.launcher.aria.engine

import android.net.Uri
import com.aria.launcher.aria.data.DomainPermissionDao

enum class ToolPermissionTier {
    AUTO,
    CONFIRM,
    NEVER,
}

object EditorialToolPolicy {

    private val TOOL_TIERS = mapOf(
        // AUTO is reserved for data-only tools that don't launch UI. Anything that
        // calls startActivity must be CONFIRM: the Brief runs from a background
        // heartbeat, where auto-launching is intrusive and Android silently drops
        // background activity starts (leaving the Brief falsely reporting "done").
        "activate_skill" to ToolPermissionTier.AUTO,
        "read_notifications" to ToolPermissionTier.AUTO,
        "search_web" to ToolPermissionTier.CONFIRM,
        "set_reminder" to ToolPermissionTier.CONFIRM,
        "set_timer" to ToolPermissionTier.CONFIRM,
        "get_directions" to ToolPermissionTier.CONFIRM,
        "compose_message" to ToolPermissionTier.CONFIRM,
        "send_email" to ToolPermissionTier.CONFIRM,
        "create_event" to ToolPermissionTier.CONFIRM,
        "make_call" to ToolPermissionTier.CONFIRM,
        "share_text" to ToolPermissionTier.CONFIRM,
        "fetch_url" to ToolPermissionTier.CONFIRM,
        "take_photo" to ToolPermissionTier.NEVER,
        "play_music" to ToolPermissionTier.NEVER,
        "open_app" to ToolPermissionTier.NEVER,
    )

    fun tierFor(toolName: String): ToolPermissionTier = TOOL_TIERS[toolName] ?: ToolPermissionTier.NEVER

    fun isOfferedToEditorial(toolName: String): Boolean = tierFor(toolName) != ToolPermissionTier.NEVER

    suspend fun tierForFetchUrl(
        url: String,
        method: String,
        dao: DomainPermissionDao,
    ): ToolPermissionTier {
        val domain = try {
            Uri.parse(url).host?.lowercase() ?: return ToolPermissionTier.CONFIRM
        } catch (_: Exception) {
            return ToolPermissionTier.CONFIRM
        }
        val permission = dao.findByDomain(domain) ?: return ToolPermissionTier.CONFIRM
        val allowed = permission.allowedMethods.split(",").map { it.trim().uppercase() }
        return if (method.uppercase() in allowed) {
            ToolPermissionTier.AUTO
        } else {
            ToolPermissionTier.CONFIRM
        }
    }
}
