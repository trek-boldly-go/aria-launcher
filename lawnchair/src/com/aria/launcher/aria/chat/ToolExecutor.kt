// Copyright (c) 2026 Donovon Simpson. All rights reserved. See LICENSE-ARIA.md
package com.aria.launcher.aria.chat

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import android.util.Log
import com.aria.launcher.aria.llm.ToolCall
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

data class ToolResult(
    val toolCallId: String,
    val toolName: String,
    val result: String,
    val success: Boolean = true,
)

class ToolExecutor(private val context: Context) {

    fun execute(toolCall: ToolCall): ToolResult {
        return try {
            when (toolCall.name) {
                "open_app" -> executeOpenApp(toolCall)
                "search_web" -> executeSearchWeb(toolCall)
                "set_reminder" -> executeSetReminder(toolCall)
                "get_directions" -> executeGetDirections(toolCall)
                "compose_message" -> executeComposeMessage(toolCall)
                else -> ToolResult(toolCall.id, toolCall.name, "Unknown tool: ${toolCall.name}", false)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Tool execution failed: ${toolCall.name}", e)
            ToolResult(toolCall.id, toolCall.name, "Error: ${e.message}", false)
        }
    }

    private fun executeOpenApp(toolCall: ToolCall): ToolResult {
        val packageName = toolCall.arguments["package_name"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult(toolCall.id, toolCall.name, "Missing package_name", false)

        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: return ToolResult(toolCall.id, toolCall.name, "App not found: $packageName", false)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return launchIntent(intent, toolCall, "Opened $packageName")
    }

    private fun executeSearchWeb(toolCall: ToolCall): ToolResult {
        val query = toolCall.arguments["query"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult(toolCall.id, toolCall.name, "Missing query", false)

        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=${Uri.encode(query)}"))
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return launchIntent(intent, toolCall, "Searching for: $query")
    }

    private fun executeSetReminder(toolCall: ToolCall): ToolResult {
        val message = toolCall.arguments["message"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult(toolCall.id, toolCall.name, "Missing message", false)

        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_MESSAGE, message)
            putExtra(AlarmClock.EXTRA_SKIP_UI, false)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return launchIntent(intent, toolCall, "Set reminder: $message")
    }

    private fun executeGetDirections(toolCall: ToolCall): ToolResult {
        val destination = toolCall.arguments["destination"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult(toolCall.id, toolCall.name, "Missing destination", false)

        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=${Uri.encode(destination)}"))
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        intent.setPackage("com.google.android.apps.maps")

        val launchIntent = if (intent.resolveActivity(context.packageManager) != null) {
            intent
        } else {
            // Fallback to browser
            Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://www.google.com/maps/dir/?api=1&destination=${Uri.encode(destination)}"),
            ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        }
        return launchIntent(launchIntent, toolCall, "Getting directions to: $destination")
    }

    private fun executeComposeMessage(toolCall: ToolCall): ToolResult {
        val contact = toolCall.arguments["contact"]?.jsonPrimitive?.contentOrNull ?: ""
        val message = toolCall.arguments["message"]?.jsonPrimitive?.contentOrNull ?: ""

        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("smsto:$contact")
            putExtra("sms_body", message)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return launchIntent(intent, toolCall, "Composing message to: $contact")
    }

    private fun launchIntent(intent: Intent, toolCall: ToolCall, successMessage: String): ToolResult {
        return try {
            context.startActivity(intent)
            ToolResult(toolCall.id, toolCall.name, successMessage)
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "No activity found for intent: ${intent.action}", e)
            ToolResult(toolCall.id, toolCall.name, "No app available to handle this action", false)
        }
    }

    companion object {
        private const val TAG = "ARIA.ToolExecutor"
    }
}
