// Copyright (c) 2026 Donovon Simpson. See LICENSE-ARIA.md for licensing terms.
package com.aria.launcher.aria.ui.memory

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.lawnchair.ui.preferences.components.layout.PreferenceLayoutLazyColumn
import com.aria.launcher.aria.data.AriaPreferences
import com.aria.launcher.aria.data.MemoryRepository
import com.aria.launcher.aria.data.UserMemory
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@EntryPoint
@InstallIn(SingletonComponent::class)
private interface MemoryEntryPoint {
    fun memoryRepository(): MemoryRepository
    fun ariaPreferences(): AriaPreferences
}

private val CATEGORY_ORDER = listOf("preference", "routine", "person", "place", "general")

@Composable
fun MemoryManagerScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state = remember {
        val ep = EntryPointAccessors.fromApplication(context, MemoryEntryPoint::class.java)
        MemoryManagerState(ep.memoryRepository(), ep.ariaPreferences(), scope)
    }
    val ep = remember {
        EntryPointAccessors.fromApplication(context, MemoryEntryPoint::class.java)
    }

    val memories by state.memories.collectAsState()
    val isLoading by state.isLoading.collectAsState()
    val memoryEnabled by ep.ariaPreferences().memoryEnabled.collectAsState(initial = true)
    var showClearAll by remember { mutableStateOf(false) }

    PreferenceLayoutLazyColumn(
        label = "ARIA Memory",
        modifier = modifier,
    ) {
        item {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainer,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Memory enabled",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            text = "${memories.size} / 500 memories stored",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = memoryEnabled,
                        onCheckedChange = { state.setMemoryEnabled(it) },
                    )
                }
            }
        }

        if (memories.isNotEmpty()) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = { showClearAll = true }) {
                        Text("Clear all")
                    }
                }
            }
        }

        if (isLoading) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(48.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        "Loading…",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else if (memories.isEmpty()) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(48.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = "No memories yet",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "ARIA will save things you tell it as you chat.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        } else {
            val grouped = memories.groupBy { it.category }
            val orderedCategories = CATEGORY_ORDER.filter { it in grouped } +
                grouped.keys.filter { it !in CATEGORY_ORDER }
            for (category in orderedCategories) {
                val items = grouped[category] ?: continue
                item(key = "header-$category") {
                    Text(
                        text = category.replaceFirstChar { it.uppercase() } +
                            " (${items.size})",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                items(items, key = { it.id }) { memory ->
                    MemoryRow(
                        memory = memory,
                        onDelete = { state.delete(memory.id) },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }

    if (showClearAll) {
        AlertDialog(
            onDismissRequest = { showClearAll = false },
            title = { Text("Clear all memories?") },
            text = { Text("This will permanently delete all ${memories.size} memories. ARIA will start over from your next chat.") },
            confirmButton = {
                TextButton(onClick = {
                    showClearAll = false
                    state.deleteAll()
                }) { Text("Clear all") }
            },
            dismissButton = {
                TextButton(onClick = { showClearAll = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun MemoryRow(
    memory: UserMemory,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = memory.fact,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Rounded.Delete,
                    contentDescription = "Delete memory",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private class MemoryManagerState(
    private val repo: MemoryRepository,
    private val prefs: AriaPreferences,
    private val scope: CoroutineScope,
) {
    private val _memories = MutableStateFlow<List<UserMemory>>(emptyList())
    val memories: StateFlow<List<UserMemory>> = _memories.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    init {
        reload()
    }

    fun reload() {
        scope.launch {
            _isLoading.value = true
            _memories.value = repo.all()
            _isLoading.value = false
        }
    }

    fun delete(id: Long) {
        scope.launch {
            repo.delete(id)
            _memories.value = repo.all()
        }
    }

    fun deleteAll() {
        scope.launch {
            repo.deleteAll()
            _memories.value = repo.all()
        }
    }

    fun setMemoryEnabled(enabled: Boolean) {
        scope.launch {
            withContext(Dispatchers.IO) { prefs.setMemoryEnabled(enabled) }
        }
    }
}
