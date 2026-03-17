// Copyright (c) 2026 Donovon Simpson. All rights reserved. See LICENSE-ARIA.md
package com.aria.launcher.aria.engine

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.aria.launcher.aria.engine.rules.RuleAction
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [ActionExecutor] implementation that uses Android Intents.
 * This is the lowest-priority fallback executor — always available.
 */
@Singleton
class IntentExecutor @Inject constructor(
    @ApplicationContext private val context: Context,
) : ActionExecutor {

    override val name = "IntentExecutor"

    override val capabilities = setOf(
        ActionCapability.OPEN_APP,
        ActionCapability.SEND_MESSAGE,
    )

    override suspend fun canExecute(action: RuleAction): Boolean = when (action) {
        is RuleAction.OpenApp -> true

        is RuleAction.SurfaceApp -> true

        is RuleAction.SendMessage -> true

        is RuleAction.ShowCard -> false

        // cards are handled by the UI layer
        is RuleAction.SuppressApp -> false

        // handled by prediction filtering
        is RuleAction.SetSpace -> false

        is RuleAction.RunSkill -> false

        is RuleAction.FetchData -> false // MCP executor handles this
    }

    override suspend fun execute(action: RuleAction): ActionResult = try {
        when (action) {
            is RuleAction.OpenApp -> {
                val intent = if (action.intentUri != null) {
                    Intent(Intent.ACTION_VIEW, Uri.parse(action.intentUri)).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                } else {
                    context.packageManager.getLaunchIntentForPackage(action.packageName)?.apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                }
                if (intent != null) {
                    context.startActivity(intent)
                    ActionResult.Success()
                } else {
                    ActionResult.Failure("App not found: ${action.packageName}", recoverable = false)
                }
            }

            is RuleAction.SurfaceApp -> {
                // SurfaceApp doesn't launch — it boosts prediction score.
                // The actual surfacing is handled by AriaHomeState.
                ActionResult.Success()
            }

            is RuleAction.SendMessage -> {
                ActionResult.RequiresConfirmation(
                    "Send message to ${action.contactName}: ${action.messageTemplate}",
                )
            }

            else -> ActionResult.Failure("IntentExecutor cannot handle ${action::class.simpleName}", recoverable = false)
        }
    } catch (e: Exception) {
        Log.w(TAG, "Intent execution failed", e)
        ActionResult.Failure(e.message ?: "Unknown error", recoverable = true)
    }

    companion object {
        private const val TAG = "ARIA.IntentExecutor"
    }
}
