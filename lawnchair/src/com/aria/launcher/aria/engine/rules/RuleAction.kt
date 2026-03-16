// Copyright (c) 2026 Donovon Simpson. All rights reserved. See LICENSE-ARIA.md
package com.aria.launcher.aria.engine.rules

/**
 * Actions ARIA takes when a rule fires.
 * Includes MCP Seam 3: [FetchData] variant (no-op until MCP executor exists).
 */
sealed class RuleAction {

    /** Boost an app's visibility in the predicted apps row. */
    data class SurfaceApp(
        val packageName: String,
        val priority: SurfacePriority,
    ) : RuleAction()

    /** Suppress an app from appearing in predictions. */
    data class SuppressApp(
        val packageName: String,
    ) : RuleAction()

    /** Show a card in the Brief. */
    data class ShowCard(
        val cardType: String,
        val headline: String,
        val subtext: String?,
        val intentUri: String?,
    ) : RuleAction()

    /** Open an app or deep link. */
    data class OpenApp(
        val packageName: String,
        val intentUri: String? = null,
    ) : RuleAction()

    /** Send a message (requires confirmation). */
    data class SendMessage(
        val contactName: String,
        val messageTemplate: String,
    ) : RuleAction()

    /** Switch to a named space/mode. */
    data class SetSpace(
        val spaceName: String,
    ) : RuleAction()

    /** Run a registered skill with parameters. */
    data class RunSkill(
        val skillId: String,
        val params: Map<String, String>,
    ) : RuleAction()

    /**
     * MCP Seam 3: Fetch data from an MCP server.
     * No-op until MCP executor is implemented in Phase 8.
     */
    data class FetchData(
        val serverId: String,
        val toolName: String,
        val params: Map<String, String>,
        val onSuccess: RuleAction,
        val onFailure: RuleAction?,
    ) : RuleAction()
}

enum class SurfacePriority { ALWAYS_SHOW, BOOST, PIN_TO_DOCK }
