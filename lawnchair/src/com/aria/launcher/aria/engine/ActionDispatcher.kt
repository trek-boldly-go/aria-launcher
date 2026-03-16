// Copyright (c) 2026 Donovon Simpson. All rights reserved. See LICENSE-ARIA.md
package com.aria.launcher.aria.engine

import android.util.Log
import com.aria.launcher.aria.engine.rules.RuleAction
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Dispatches [RuleAction]s to the first capable [ActionExecutor].
 * Executors are tried in priority order: MCP > Accessibility > Intent.
 */
@Singleton
class ActionDispatcher @Inject constructor(
    private val executors: List<@JvmSuppressWildcards ActionExecutor>,
) {
    suspend fun dispatch(action: RuleAction): ActionResult {
        val capable = executors.filter { it.canExecute(action) }
        if (capable.isEmpty()) {
            Log.w(TAG, "No executor available for action: ${action::class.simpleName}")
            return ActionResult.Failure("No executor available for this action", recoverable = false)
        }

        Log.d(TAG, "Dispatching ${action::class.simpleName} to ${capable.first().name}")
        return capable.first().execute(action)
    }

    companion object {
        private const val TAG = "ARIA.ActionDispatcher"
    }
}
