// Copyright (c) 2026 Donovon Simpson. All rights reserved. See LICENSE-ARIA.md
package com.aria.launcher.aria.engine

import com.aria.launcher.aria.ui.brief.BriefItem

/**
 * MCP Seam 4: Named automation sequence with required servers/permissions.
 *
 * Skills are the composable automation unit — a named sequence of data
 * fetches and actions that can be triggered from chat, a rule, or
 * proactively. The skills registry will be populated from community
 * contributions (ClawHub) in future phases.
 */
interface AriaSkill {
    val id: String
    val name: String
    val description: String

    /** MCP server IDs this skill needs to function. */
    val requiredServers: List<String>
        get() = emptyList()

    /** Android permissions needed by this skill. */
    val requiredPermissions: List<String>
        get() = emptyList()

    suspend fun execute(
        context: AriaContext,
        params: Map<String, String>,
        executor: ActionDispatcher,
    ): AriaSkillResult
}

data class AriaSkillResult(
    val briefItems: List<BriefItem>,
    val summary: String,
)
