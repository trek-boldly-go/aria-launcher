// Copyright (c) 2026 Donovon Simpson. See LICENSE-ARIA.md for licensing terms.
package com.aria.launcher.aria.ui.rules

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.lawnchair.ui.preferences.components.layout.PreferenceLayoutLazyColumn
import com.aria.launcher.aria.engine.rules.AriaRuleDao
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
private interface RulesEntryPoint {
    fun ariaRuleDao(): AriaRuleDao
}

/**
 * Settings screen listing all saved ARIA rules with toggle/delete controls.
 * Accessible via Settings → ARIA Rules, or by saying "show my rules" in chat.
 */
@Composable
fun RulesScreen(
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state = remember {
        val ep = EntryPointAccessors.fromApplication(context, RulesEntryPoint::class.java)
        RulesState(ep.ariaRuleDao(), scope)
    }

    val rules by state.rules.collectAsState()
    val isLoading by state.isLoading.collectAsState()

    PreferenceLayoutLazyColumn(
        label = "ARIA Rules",
        modifier = modifier,
    ) {
        if (isLoading) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(48.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator()
                }
            }
        } else if (rules.isEmpty()) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(48.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = "No rules yet",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "\u201cAlways show me Spotify when I connect to my car\u201d \u2014 try something like this in the ARIA chat bar.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        } else {
            items(rules, key = { it.id }) { rule ->
                RuleCard(
                    naturalLanguage = rule.naturalLanguageSource,
                    summary = rule.humanReadableSummary,
                    isEnabled = rule.isEnabled,
                    triggerCount = rule.triggerCount,
                    lastTriggered = rule.lastTriggeredAt,
                    onToggle = { state.toggle(rule) },
                    onDelete = { state.delete(rule) },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
        }
    }
}
