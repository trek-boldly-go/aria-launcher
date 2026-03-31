// Copyright (c) 2026 Donovon Simpson. All rights reserved. See LICENSE-ARIA.md
package com.aria.launcher.aria.chat

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.MediaStore
import android.util.Log
import com.aria.launcher.aria.llm.ToolCall
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
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
                "make_call" -> executeMakeCall(toolCall)
                "send_email" -> executeSendEmail(toolCall)
                "set_timer" -> executeSetTimer(toolCall)
                "create_event" -> executeCreateEvent(toolCall)
                "take_photo" -> executeTakePhoto(toolCall)
                "share_text" -> executeShareText(toolCall)
                "play_music" -> executePlayMusic(toolCall)
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
        val component = toolCall.arguments["component"]?.jsonPrimitive?.contentOrNull
        val intentUri = toolCall.arguments["intent_uri"]?.jsonPrimitive?.contentOrNull

        val intent = when {
            // Priority 1: explicit component — verify it's exported before launching
            component != null -> {
                val cn = ComponentName.unflattenFromString(component)
                    ?: return ToolResult(toolCall.id, toolCall.name, "Invalid component: $component", false)
                try {
                    val activityInfo = context.packageManager.getActivityInfo(cn, 0)
                    if (!activityInfo.exported) {
                        return ToolResult(toolCall.id, toolCall.name, "Activity is not exported: $component", false)
                    }
                } catch (_: PackageManager.NameNotFoundException) {
                    return ToolResult(toolCall.id, toolCall.name, "Activity not found: $component", false)
                }
                Intent().apply {
                    setComponent(cn)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }

            // Priority 2: deep link URI
            intentUri != null -> {
                Intent(Intent.ACTION_VIEW, Uri.parse(intentUri)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }

            // Priority 3: default launch activity
            else -> {
                context.packageManager.getLaunchIntentForPackage(packageName)
                    ?: return ToolResult(toolCall.id, toolCall.name, "App not found: $packageName", false)
            }
        }

        val desc = when {
            component != null -> "Opened $packageName (${component.substringAfterLast('.')})"
            intentUri != null -> "Opened $packageName via $intentUri"
            else -> "Opened $packageName"
        }
        return launchIntent(intent, toolCall, desc)
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

    private fun executeMakeCall(toolCall: ToolCall): ToolResult {
        val number = toolCall.arguments["number"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult(toolCall.id, toolCall.name, "Missing number", false)

        val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(number)}")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return launchIntent(intent, toolCall, "Dialing: $number")
    }

    private fun executeSendEmail(toolCall: ToolCall): ToolResult {
        val to = toolCall.arguments["to"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult(toolCall.id, toolCall.name, "Missing to address", false)
        val subject = toolCall.arguments["subject"]?.jsonPrimitive?.contentOrNull ?: ""
        val body = toolCall.arguments["body"]?.jsonPrimitive?.contentOrNull ?: ""

        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:${Uri.encode(to)}")
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return launchIntent(intent, toolCall, "Composing email to: $to")
    }

    private fun executeSetTimer(toolCall: ToolCall): ToolResult {
        val seconds = toolCall.arguments["seconds"]?.jsonPrimitive?.intOrNull
            ?: return ToolResult(toolCall.id, toolCall.name, "Missing seconds", false)
        val label = toolCall.arguments["label"]?.jsonPrimitive?.contentOrNull

        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            if (label != null) putExtra(AlarmClock.EXTRA_MESSAGE, label)
            putExtra(AlarmClock.EXTRA_SKIP_UI, false)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val desc = if (label != null) "Set timer: $label (${seconds}s)" else "Set timer: ${seconds}s"
        return launchIntent(intent, toolCall, desc)
    }

    private fun executeCreateEvent(toolCall: ToolCall): ToolResult {
        val title = toolCall.arguments["title"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult(toolCall.id, toolCall.name, "Missing title", false)

        val intent = Intent(Intent.ACTION_INSERT).apply {
            data = CalendarContract.Events.CONTENT_URI
            putExtra(CalendarContract.Events.TITLE, title)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return launchIntent(intent, toolCall, "Creating event: $title")
    }

    private fun executeTakePhoto(toolCall: ToolCall): ToolResult {
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return launchIntent(intent, toolCall, "Opening camera")
    }

    private fun executeShareText(toolCall: ToolCall): ToolResult {
        val text = toolCall.arguments["text"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult(toolCall.id, toolCall.name, "Missing text", false)

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return launchIntent(
            Intent.createChooser(intent, "Share via").apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) },
            toolCall,
            "Sharing text",
        )
    }

    private fun executePlayMusic(toolCall: ToolCall): ToolResult {
        val query = toolCall.arguments["query"]?.jsonPrimitive?.contentOrNull

        val intent = if (query != null) {
            Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
                putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
                putExtra(android.app.SearchManager.QUERY, query)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } else {
            // Open default music app
            Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse("content://media/external/audio/media")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
        val desc = if (query != null) "Playing: $query" else "Opening music"
        return launchIntent(intent, toolCall, desc)
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
