// Copyright (c) 2026 Donovon Simpson. See LICENSE-ARIA.md for licensing terms.
package com.aria.launcher.aria.engine.rules

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "aria_rules")
data class AriaRule(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val naturalLanguageSource: String, // original user text, for display
    val humanReadableSummary: String = "", // "When X, ARIA will Y"
    val trigger: RuleTrigger, // stored as JSON via RuleTypeConverters
    val action: RuleAction, // stored as JSON via RuleTypeConverters
    val confidence: Float = 1.0f,
    val isEnabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val lastTriggeredAt: Long? = null,
    val triggerCount: Int = 0,
    val needsUserConfirmation: Boolean = false, // true for destructive actions
)
