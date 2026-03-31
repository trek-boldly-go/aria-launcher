// Copyright (c) 2026 Donovon Simpson. See LICENSE-ARIA.md for licensing terms.
package com.aria.launcher.aria.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.util.Log
import com.aria.launcher.aria.data.SkillAction
import com.aria.launcher.aria.data.SkillResult
import kotlinx.serialization.json.Json

data class CardFeedItem(
    val skillId: String,
    val title: String,
    val body: String,
    val actions: List<SkillAction>,
    val appIcon: Drawable?,
    val priority: Float,
)

object CardFeedState {

    private val json = Json { ignoreUnknownKeys = true }

    fun buildCards(
        results: List<SkillResult>,
        context: Context,
    ): List<CardFeedItem> {
        val pm = context.packageManager
        return results.mapNotNull { result ->
            val actions = parseActions(result.actions)
            val appPackage = actions.firstOrNull { it.type == "OPEN_APP" }?.payload
            val icon = appPackage?.let { pkg ->
                try {
                    pm.getApplicationIcon(pm.getApplicationInfo(pkg, 0))
                } catch (_: Exception) {
                    null
                }
            }
            CardFeedItem(
                skillId = result.skillId,
                title = result.title,
                body = result.body,
                actions = actions,
                appIcon = icon,
                priority = result.priority,
            )
        }.sortedByDescending { it.priority }
    }

    fun handleAction(action: SkillAction, context: Context) {
        try {
            when (action.type) {
                "OPEN_APP" -> {
                    val intent = context.packageManager.getLaunchIntentForPackage(action.payload)
                    if (intent != null) {
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                    }
                }

                "DEEP_LINK" -> {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(action.payload))
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                }

                "INTENT" -> {
                    val intent = Intent.parseUri(action.payload, 0)
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                }

                else -> Log.w(TAG, "Unknown action type: ${action.type}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle action: ${action.label}", e)
        }
    }

    fun parseActions(actionsJson: String): List<SkillAction> {
        return try {
            json.decodeFromString<List<SkillAction>>(actionsJson)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse skill actions JSON", e)
            emptyList()
        }
    }

    private const val TAG = "ARIA.CardFeed"
}
