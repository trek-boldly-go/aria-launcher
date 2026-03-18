// Copyright (c) 2026 Donovon Simpson. All rights reserved. See LICENSE-ARIA.md
package com.aria.launcher.aria.engine.rules

import android.util.Log
import com.aria.launcher.aria.engine.AriaContext
import com.aria.launcher.aria.engine.FiredRule
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pure Kotlin rule evaluator — zero LLM calls at runtime.
 * Called once per context change by [com.aria.launcher.aria.engine.AriaContextMonitor].
 *
 * Returns a [List<FiredRule>] representing all rules whose triggers match the
 * current [AriaContext]. The context monitor attaches these to the context snapshot,
 * and [com.aria.launcher.aria.ui.AriaHomeState] applies the actions.
 */
@Singleton
class AriaRuleEvaluator @Inject constructor(
    private val dao: AriaRuleDao,
) {
    /**
     * Evaluates all enabled rules against [context] and returns the fired rules.
     * Synchronous Room query — must be called on an IO thread.
     */
    fun evaluate(context: AriaContext): List<FiredRule> {
        val enabledRules = dao.getEnabledRules()
        val matched = enabledRules.filter { matches(it.trigger, context) }
        matched.forEach { dao.incrementTriggerCount(it.id) }
        return matched.map { rule ->
            FiredRule(ruleId = rule.id, action = rule.action)
        }.also {
            if (it.isNotEmpty()) {
                Log.d(TAG, "${it.size} rule(s) fired: ${it.map { r -> r.ruleId }}")
            }
        }
    }

    private fun matches(trigger: RuleTrigger, context: AriaContext): Boolean {
        return when (trigger) {
            is RuleTrigger.WifiSsidTrigger -> {
                val ssid = context.wifiSsid ?: return false
                when (trigger.matchType) {
                    MatchType.EXACT -> ssid.equals(trigger.pattern, ignoreCase = true)

                    MatchType.CONTAINS -> ssid.contains(trigger.pattern, ignoreCase = true)

                    MatchType.STARTS_WITH -> ssid.startsWith(trigger.pattern, ignoreCase = true)

                    MatchType.REGEX -> Regex(trigger.pattern, setOf(RegexOption.IGNORE_CASE))
                        .containsMatchIn(ssid)
                }
            }

            is RuleTrigger.VenueCategoryTrigger ->
                context.currentVenueCategory == trigger.category.name

            is RuleTrigger.TimeTrigger -> {
                val cal = Calendar.getInstance().apply { timeInMillis = context.timestampMs }
                val hour = cal.get(Calendar.HOUR_OF_DAY)
                val dow = cal.get(Calendar.DAY_OF_WEEK)
                val inHourRange = hour in trigger.startHour..trigger.endHour
                val inDayRange = trigger.daysOfWeek?.contains(dow) ?: true
                inHourRange && inDayRange
            }

            is RuleTrigger.AppOpenedTrigger ->
                context.recentAppPackages.firstOrNull() == trigger.packageName

            is RuleTrigger.CalendarEventTrigger -> {
                val now = context.timestampMs
                val windowMs = trigger.minutesBefore * 60 * 1000L
                context.upcomingEvents
                    .filter { it.startTimeMs - now < windowMs }
                    .any { event ->
                        trigger.titleKeywords.any { kw ->
                            event.title.contains(kw, ignoreCase = true)
                        }
                    }
            }

            is RuleTrigger.LocationTrigger ->
                // GPS-based evaluation is a future session seam — never fires until implemented
                false

            is RuleTrigger.AndroidAutoTrigger ->
                context.isAndroidAutoConnected &&
                    (
                        trigger.connectedCarName == null ||
                            trigger.connectedCarName == context.connectedCarName
                        )

            is RuleTrigger.CompoundTrigger -> when (trigger.operator) {
                LogicOperator.AND -> trigger.triggers.all { matches(it, context) }
                LogicOperator.OR -> trigger.triggers.any { matches(it, context) }
            }
        }
    }

    companion object {
        private const val TAG = "ARIA.RuleEvaluator"
    }
}
