// Copyright (c) 2026 Donovon Simpson. All rights reserved. See LICENSE-ARIA.md
package com.aria.launcher.aria.ui.onboarding

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.aria.launcher.aria.llm.GeminiProvider
import com.aria.launcher.aria.llm.LlmProviderManager
import com.aria.launcher.aria.llm.LlmResult
import com.aria.launcher.aria.llm.ProviderType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)
@Suppress("ktlint:compose:parameter-naming")
@Composable
fun LlmSetupPage(
    llmProviderManager: LlmProviderManager,
    onQrScanRequested: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var apiKey by remember { mutableStateOf("") }
    var ollamaUrl by remember { mutableStateOf("") }
    var testResult by remember { mutableStateOf<String?>(null) }
    var isTesting by remember { mutableStateOf(false) }

    // Gemini model selection
    val geminiModels = remember { GeminiProvider.AVAILABLE_MODELS }
    var selectedModelId by remember { mutableStateOf(geminiModels[0].first) }
    var modelDropdownExpanded by remember { mutableStateOf(false) }

    OnboardingPageLayout(
        title = "AI Setup",
        subtitle = "Connect an AI provider for chat and smart card ranking. This is optional \u2014 ARIA works without it.",
        modifier = modifier,
    ) {
        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
            // ── Gemini (recommended free option) ──
            Text(
                text = "Gemini (Free, Recommended)",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.height(8.dp))

            // Model selector
            ExposedDropdownMenuBox(
                expanded = modelDropdownExpanded,
                onExpandedChange = { modelDropdownExpanded = it },
            ) {
                val selectedLabel = geminiModels.firstOrNull { it.first == selectedModelId }?.second
                    ?: selectedModelId
                OutlinedTextField(
                    value = selectedLabel,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Model") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = modelDropdownExpanded) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                )
                ExposedDropdownMenu(
                    expanded = modelDropdownExpanded,
                    onDismissRequest = { modelDropdownExpanded = false },
                ) {
                    for ((id, label) in geminiModels) {
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = {
                                selectedModelId = id
                                modelDropdownExpanded = false
                            },
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                label = { Text("API key") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = {
                    if (apiKey.isBlank()) {
                        Toast.makeText(context, "Enter an API key", Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            llmProviderManager.configureProvider(
                                type = ProviderType.GEMINI,
                                apiKey = apiKey,
                                modelId = selectedModelId,
                            )
                        }
                        val label = geminiModels.firstOrNull { it.first == selectedModelId }?.second
                            ?: selectedModelId
                        Toast.makeText(context, "Gemini configured ($label)", Toast.LENGTH_SHORT).show()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                shapes = ButtonDefaults.shapes(),
            ) {
                Text("Set API Key")
            }

            Spacer(modifier = Modifier.height(20.dp))

            // ── Claude ──
            Text(
                text = "Claude (Anthropic)",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Get an API key from console.anthropic.com, or run npx aria-token-qr " +
                    "on your computer to transfer credentials via QR code.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(8.dp))
            var claudeToken by remember { mutableStateOf("") }
            OutlinedTextField(
                value = claudeToken,
                onValueChange = { claudeToken = it },
                label = { Text("Claude API key or OAuth token") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = {
                    if (claudeToken.isBlank()) {
                        Toast.makeText(context, "Enter a Claude API key", Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    scope.launch {
                        val type = when {
                            claudeToken.startsWith("sk-ant-oat01-") -> ProviderType.CLAUDE_OAUTH
                            else -> ProviderType.CLAUDE_API_KEY
                        }
                        withContext(Dispatchers.IO) {
                            llmProviderManager.configureProvider(type = type, apiKey = claudeToken)
                        }
                        Toast.makeText(context, "Claude configured", Toast.LENGTH_SHORT).show()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                shapes = ButtonDefaults.shapes(),
            ) {
                Text("Set Claude Key")
            }
            Spacer(modifier = Modifier.height(4.dp))
            OutlinedButton(
                onClick = onQrScanRequested,
                modifier = Modifier.fillMaxWidth(),
                shapes = ButtonDefaults.shapes(),
            ) {
                Text("Scan QR Code Instead")
            }

            Spacer(modifier = Modifier.height(20.dp))

            // ── Ollama ──
            Text(
                text = "Ollama (Self-hosted)",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = ollamaUrl,
                onValueChange = { ollamaUrl = it },
                label = { Text("Server URL") },
                placeholder = { Text("http://192.168.1.100:11434") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = {
                    if (ollamaUrl.isBlank()) {
                        Toast.makeText(context, "Enter a server URL", Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            llmProviderManager.configureProvider(
                                type = ProviderType.OLLAMA,
                                serverUrl = ollamaUrl,
                            )
                        }
                        Toast.makeText(context, "Ollama configured", Toast.LENGTH_SHORT).show()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                shapes = ButtonDefaults.shapes(),
            ) {
                Text("Set Ollama Server")
            }

            Spacer(modifier = Modifier.height(20.dp))

            // ── Test connection ──
            Button(
                onClick = {
                    if (isTesting) return@Button
                    isTesting = true
                    testResult = null
                    scope.launch {
                        val result = withContext(Dispatchers.IO) { llmProviderManager.testConnection() }
                        testResult = when (result) {
                            is LlmResult.Text -> "Connected: ${result.content.take(60)}"
                            is LlmResult.ToolUse -> "Connected (tool use)"
                            is LlmResult.Error -> "Error: ${result.message.take(120)}"
                        }
                        isTesting = false
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                shapes = ButtonDefaults.shapes(),
                enabled = !isTesting,
            ) {
                Text(if (isTesting) "Testing\u2026" else "Test Connection")
            }

            if (testResult != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = testResult!!,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Bottom padding for scroll
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
