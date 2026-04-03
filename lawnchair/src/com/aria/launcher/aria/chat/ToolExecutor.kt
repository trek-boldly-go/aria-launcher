// Copyright (c) 2026 Donovon Simpson. See LICENSE-ARIA.md for licensing terms.
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
import android.text.format.DateUtils
import android.util.Log
import com.aria.launcher.aria.data.AriaNotificationListener
import com.aria.launcher.aria.engine.AppLabelResolver
import com.aria.launcher.aria.engine.skills.AgentSkillManager
import com.aria.launcher.aria.llm.ToolCall
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

data class ToolResult(
    val toolCallId: String,
    val toolName: String,
    val result: String,
    val success: Boolean = true,
)

class ToolExecutor(
    private val context: Context,
    private val httpClient: OkHttpClient? = null,
    private val agentSkillManager: AgentSkillManager? = null,
    private val appLabelResolver: AppLabelResolver? = null,
    private val isNotificationContentEnabled: () -> Boolean = { false },
) {

    fun execute(toolCall: ToolCall): ToolResult {
        return try {
            when (toolCall.name) {
                "fetch_url" -> executeFetchUrl(toolCall)
                "activate_skill" -> executeActivateSkill(toolCall)
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
                "read_notifications" -> executeReadNotifications(toolCall)
                else -> ToolResult(toolCall.id, toolCall.name, "Unknown tool: ${toolCall.name}", false)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Tool execution failed: ${toolCall.name}", e)
            ToolResult(toolCall.id, toolCall.name, "Error: ${e.message}", false)
        }
    }

    private fun executeFetchUrl(toolCall: ToolCall): ToolResult {
        val url = toolCall.arguments["url"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult(toolCall.id, toolCall.name, "Missing url", false)
        val method = toolCall.arguments["method"]?.jsonPrimitive?.contentOrNull?.uppercase() ?: "GET"
        val bodyContent = toolCall.arguments["body"]?.jsonPrimitive?.contentOrNull

        val client = httpClient
            ?: return ToolResult(toolCall.id, toolCall.name, "HTTP client not available", false)

        // Security: reject private network URLs
        val rejectionReason = checkUrlSafety(url)
        if (rejectionReason != null) {
            return ToolResult(toolCall.id, toolCall.name, rejectionReason, false)
        }

        val requestBuilder = Request.Builder().url(url)

        // Apply optional headers
        val headersObj = toolCall.arguments["headers"]?.jsonObject
        headersObj?.forEach { (key, value) ->
            val headerVal = value.jsonPrimitive.contentOrNull ?: return@forEach
            requestBuilder.header(key, headerVal)
        }

        // Set method and body
        when (method) {
            "POST" -> {
                val body = (bodyContent ?: "")
                    .toRequestBody("application/json".toMediaType())
                requestBuilder.post(body)
            }

            "GET" -> requestBuilder.get()

            else -> return ToolResult(
                toolCall.id,
                toolCall.name,
                "Unsupported method: $method (use GET or POST)",
                false,
            )
        }

        // Add a User-Agent so APIs don't reject the request
        if (requestBuilder.build().header("User-Agent") == null) {
            requestBuilder.header("User-Agent", "ARIA-Launcher/1.0")
        }

        return try {
            val response = client.newCall(requestBuilder.build()).execute()
            val responseBody = response.body?.string() ?: ""
            val truncated = if (responseBody.length > MAX_FETCH_RESPONSE_CHARS) {
                responseBody.take(MAX_FETCH_RESPONSE_CHARS) + "\n... [truncated, ${responseBody.length} chars total]"
            } else {
                responseBody
            }
            val resultText = "HTTP ${response.code}\n$truncated"
            ToolResult(toolCall.id, toolCall.name, resultText, response.isSuccessful)
        } catch (e: Exception) {
            Log.w(TAG, "fetch_url failed: $url", e)
            val errorMsg = e.message ?: "${e.javaClass.simpleName} (no details)"
            ToolResult(toolCall.id, toolCall.name, "Fetch failed: $errorMsg", false)
        }
    }

    private fun checkUrlSafety(url: String): String? {
        val lower = url.lowercase()
        if (lower.startsWith("file://")) return "file:// URLs are not allowed"
        if (lower.startsWith("javascript:")) return "javascript: URLs are not allowed"

        // Extract host from URL
        val host = try {
            Uri.parse(url).host?.lowercase()
        } catch (_: Exception) {
            return "Invalid URL"
        } ?: return "Invalid URL: no host"

        // Reject private network addresses
        if (host == "localhost" || host == "127.0.0.1" || host == "::1") {
            return "localhost URLs are not allowed"
        }
        if (host.startsWith("10.") || host.startsWith("192.168.") || host.startsWith("172.")) {
            return "Private network URLs are not allowed"
        }
        if (host.endsWith(".local")) {
            return "Local network URLs are not allowed"
        }
        return null
    }

    private fun executeActivateSkill(toolCall: ToolCall): ToolResult {
        val name = toolCall.arguments["name"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult(toolCall.id, toolCall.name, "Missing skill name", false)

        val manager = agentSkillManager
            ?: return ToolResult(toolCall.id, toolCall.name, "Skill system not available", false)

        val content = runBlocking { manager.getSkillContent(name) }
            ?: return ToolResult(toolCall.id, toolCall.name, "Skill '$name' not found", false)

        val config = manager.getConfig(name)
        val configSection = if (config.isNotEmpty()) {
            "\n\nUser configuration:\n" + config.entries.joinToString("\n") { "- ${it.key}: ${it.value}" }
        } else {
            ""
        }

        return ToolResult(toolCall.id, toolCall.name, content + configSection)
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

    private fun executeReadNotifications(toolCall: ToolCall): ToolResult {
        if (!isNotificationContentEnabled()) {
            return ToolResult(
                toolCall.id,
                toolCall.name,
                "Notification content access is not enabled by the user.",
                false,
            )
        }
        val packageFilter = toolCall.arguments["package_filter"]?.jsonPrimitive?.contentOrNull
        val limit = toolCall.arguments["limit"]?.jsonPrimitive?.intOrNull ?: MAX_NOTIFICATION_RESULTS

        val notifications = AriaNotificationListener.getNotifications()
            .let { list ->
                if (packageFilter != null) list.filter { it.packageName == packageFilter } else list
            }
            .sortedByDescending { it.postedTime }
            .take(limit.coerceAtMost(MAX_NOTIFICATION_RESULTS))

        if (notifications.isEmpty()) {
            return ToolResult(toolCall.id, toolCall.name, "No notifications found.")
        }

        val now = System.currentTimeMillis()
        val lines = notifications.map { notif ->
            val label = appLabelResolver?.resolve(notif.packageName) ?: notif.packageName
            val title = notif.title ?: "(no title)"
            val body = notif.text?.take(MAX_NOTIFICATION_BODY_CHARS) ?: "(no body)"
            val ago = DateUtils.getRelativeTimeSpanString(
                notif.postedTime,
                now,
                DateUtils.MINUTE_IN_MILLIS,
            )
            "$label — Title: $title | Body: $body | $ago"
        }

        return ToolResult(toolCall.id, toolCall.name, lines.joinToString("\n"))
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
        private const val MAX_FETCH_RESPONSE_CHARS = 4000
        private const val MAX_NOTIFICATION_RESULTS = 20
        private const val MAX_NOTIFICATION_BODY_CHARS = 500
    }
}
