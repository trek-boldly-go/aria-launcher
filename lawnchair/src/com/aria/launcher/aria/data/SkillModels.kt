package com.aria.launcher.aria.data

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class TriggerType { PROACTIVE, ON_DEMAND, SCHEDULED }

enum class SkillSourceType { BUILT_IN, SHORTCUT, INTENT, MCP, ACCESSIBILITY }

@Entity(tableName = "app_skills")
data class AppSkill(
    @PrimaryKey val id: String,                     // "gmail.inbox_summary"
    val appPackage: String,                          // "com.google.android.gm"
    val name: String,                                // "Inbox Summary"
    val description: String,                         // "Shows unread email count and top messages"
    val triggerType: String,                          // TriggerType name
    val sourceType: String,                           // SkillSourceType name
    val contextMatch: String,                         // Comma-separated context keys
    val refreshIntervalMin: Int,                      // How often to re-run (0 = on context change only)
    val enabled: Boolean = true,
)

@Entity(
    tableName = "skill_results",
    primaryKeys = ["skillId", "timestamp"],
)
data class SkillResult(
    val skillId: String,
    val title: String,                               // "Gmail · 3 new"
    val body: String,                                // Summary text
    val actions: String,                              // JSON-encoded List<SkillAction>
    val priority: Float,                              // 0.0-1.0, agent-computed
    val timestamp: Long,
    val expiresAt: Long?,                             // When this result becomes stale
)

data class SkillAction(
    val label: String,                               // "Reply"
    val type: String,                                // OPEN_APP, DEEP_LINK, INTENT, AGENT_TASK
    val payload: String,                             // Intent URI, deep link, or agent instruction
)
