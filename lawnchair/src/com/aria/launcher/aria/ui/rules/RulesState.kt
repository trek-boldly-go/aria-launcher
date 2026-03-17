// Copyright (c) 2026 Donovon Simpson. All rights reserved. See LICENSE-ARIA.md
package com.aria.launcher.aria.ui.rules

import com.aria.launcher.aria.engine.rules.AriaRule
import com.aria.launcher.aria.engine.rules.AriaRuleDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** State holder for the Rules management screen. */
class RulesState(
    private val ruleDao: AriaRuleDao,
    private val scope: CoroutineScope,
) {
    private val _rules = MutableStateFlow<List<AriaRule>>(emptyList())
    val rules: StateFlow<List<AriaRule>> = _rules.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    init {
        reload()
    }

    fun reload() {
        scope.launch {
            _isLoading.value = true
            _rules.value = withContext(Dispatchers.IO) { ruleDao.getAllRules() }
            _isLoading.value = false
        }
    }

    fun toggle(rule: AriaRule) {
        scope.launch(Dispatchers.IO) {
            ruleDao.update(rule.copy(isEnabled = !rule.isEnabled))
            _rules.value = ruleDao.getAllRules()
        }
    }

    fun delete(rule: AriaRule) {
        scope.launch(Dispatchers.IO) {
            ruleDao.delete(rule)
            _rules.value = ruleDao.getAllRules()
        }
    }
}
