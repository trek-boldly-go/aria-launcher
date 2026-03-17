// Copyright (c) 2026 Donovon Simpson. All rights reserved. See LICENSE-ARIA.md
package com.aria.launcher.aria.engine.rules

import androidx.room.TypeConverter
import kotlinx.serialization.json.Json

/**
 * Room TypeConverters for [RuleTrigger] and [RuleAction] sealed classes.
 * Registered at the database level in [com.aria.launcher.aria.data.AriaDatabase].
 */
class RuleTypeConverters {

    @TypeConverter
    fun ruleTriggerToString(trigger: RuleTrigger): String = Json.encodeToString(trigger)

    @TypeConverter
    fun stringToRuleTrigger(value: String): RuleTrigger = Json.decodeFromString(value)

    @TypeConverter
    fun ruleActionToString(action: RuleAction): String = Json.encodeToString(action)

    @TypeConverter
    fun stringToRuleAction(value: String): RuleAction = Json.decodeFromString(value)
}
