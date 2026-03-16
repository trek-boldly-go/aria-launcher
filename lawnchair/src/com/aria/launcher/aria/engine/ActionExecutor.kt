// Copyright (c) 2026 Donovon Simpson. All rights reserved. See LICENSE-ARIA.md
package com.aria.launcher.aria.engine

import com.aria.launcher.aria.engine.rules.RuleAction

/**
 * MCP Seam 1: Shared interface for executing actions.
 *
 * Both [IntentExecutor] (Android Intents) and future McpExecutor
 * implement this interface. [ActionDispatcher] tries executors in
 * priority order (MCP > Accessibility > Intent).
 */
interface ActionExecutor {
    val name: String
    val capabilities: Set<ActionCapability>
    suspend fun execute(action: RuleAction): ActionResult
    suspend fun canExecute(action: RuleAction): Boolean
}

enum class ActionCapability {
    OPEN_APP,
    SEND_MESSAGE,
    READ_SCREEN,
    CLICK_ELEMENT,
    FETCH_DATA,
    TRIGGER_AUTOMATION,
    CONTROL_MEDIA,
    MODIFY_CALENDAR,
    READ_CALENDAR,
}

sealed class ActionResult {
    data class Success(val data: Any? = null) : ActionResult()
    data class RequiresConfirmation(val description: String) : ActionResult()
    data class Failure(val reason: String, val recoverable: Boolean) : ActionResult()
}
