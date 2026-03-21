// Copyright (c) 2026 Donovon Simpson. All rights reserved. See LICENSE-ARIA.md
package com.aria.launcher.aria.ui.onboarding

import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.aria.launcher.aria.llm.GeminiProvider
import com.aria.launcher.aria.llm.LiteRtLmProvider
import com.aria.launcher.aria.llm.LiteRtModelManager
import com.aria.launcher.aria.llm.LlmProviderManager
import com.aria.launcher.aria.llm.LlmResult
import com.aria.launcher.aria.llm.ModelDownloadState
import com.aria.launcher.aria.llm.OnDeviceModel
import com.aria.launcher.aria.llm.ProviderType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class LlmSetupScreen {
    CHOOSER,
    GEMINI_SETUP,
    CLAUDE_SETUP,
    OLLAMA_SETUP,
    OPENAI_SETUP,
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)
@Suppress("ktlint:compose:parameter-naming")
@Composable
fun LlmSetupPage(
    llmProviderManager: LlmProviderManager,
    onQrScanRequested: () -> Unit,
    modifier: Modifier = Modifier,
    liteRtModelManager: LiteRtModelManager? = null,
    liteRtLmProvider: LiteRtLmProvider? = null,
) {
    var currentScreen by remember { mutableStateOf(LlmSetupScreen.CHOOSER) }

    OnboardingPageLayout(
        title = "AI Setup",
        subtitle = "This is optional \u2014 ARIA works without it.",
        modifier = modifier,
    ) {
        AnimatedContent(
            targetState = currentScreen,
            transitionSpec = {
                if (targetState == LlmSetupScreen.CHOOSER) {
                    (slideInHorizontally { -it / 3 } + fadeIn()) togetherWith
                        (slideOutHorizontally { it / 3 } + fadeOut())
                } else {
                    (slideInHorizontally { it / 3 } + fadeIn()) togetherWith
                        (slideOutHorizontally { -it / 3 } + fadeOut())
                }
            },
            label = "llmSetupTransition",
        ) { screen ->
            when (screen) {
                LlmSetupScreen.CHOOSER -> ProviderChooser(
                    onSelectGemini = { currentScreen = LlmSetupScreen.GEMINI_SETUP },
                    onSelectClaude = { currentScreen = LlmSetupScreen.CLAUDE_SETUP },
                    onSelectOllama = { currentScreen = LlmSetupScreen.OLLAMA_SETUP },
                    onSelectOpenAi = { currentScreen = LlmSetupScreen.OPENAI_SETUP },
                    liteRtModelManager = liteRtModelManager,
                    liteRtLmProvider = liteRtLmProvider,
                    llmProviderManager = llmProviderManager,
                )

                LlmSetupScreen.GEMINI_SETUP -> GeminiSetupSubPage(
                    llmProviderManager = llmProviderManager,
                    onBack = { currentScreen = LlmSetupScreen.CHOOSER },
                )

                LlmSetupScreen.CLAUDE_SETUP -> ClaudeSetupSubPage(
                    llmProviderManager = llmProviderManager,
                    onQrScanRequested = onQrScanRequested,
                    onBack = { currentScreen = LlmSetupScreen.CHOOSER },
                )

                LlmSetupScreen.OLLAMA_SETUP -> OllamaSetupSubPage(
                    llmProviderManager = llmProviderManager,
                    onBack = { currentScreen = LlmSetupScreen.CHOOSER },
                )

                LlmSetupScreen.OPENAI_SETUP -> OpenAiSetupSubPage(
                    llmProviderManager = llmProviderManager,
                    onBack = { currentScreen = LlmSetupScreen.CHOOSER },
                )
            }
        }
    }
}

// ── Provider Chooser ──

@Composable
private fun ProviderChooser(
    onSelectGemini: () -> Unit,
    onSelectClaude: () -> Unit,
    onSelectOllama: () -> Unit,
    onSelectOpenAi: () -> Unit,
    liteRtModelManager: LiteRtModelManager?,
    liteRtLmProvider: LiteRtLmProvider?,
    llmProviderManager: LlmProviderManager?,
) {
    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
        // Explainer
        Text(
            text = "How ARIA\u2019s AI works",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "ARIA uses AI to understand your patterns, curate your home screen, and chat with you.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "On-device model",
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = "An on-device AI model handles app predictions, card ranking, and quick questions offline. Choose a model below based on your device. Requires a free HuggingFace account to download (Gemma license). Set up the token in ARIA settings after onboarding.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "Cloud AI (optional, choose below)",
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = "For complex tasks like long conversations, deep analysis, and multi-step actions, ARIA can use a cloud AI service. If you don\u2019t set one up, ARIA still works \u2014 it just uses the on-device model for everything once it downloads.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(20.dp))

        // Gemini card (recommended)
        ProviderCard(
            title = "Gemini (Free)",
            subtitle = "Google\u2019s AI. Free API key required.\nBest for most users.",
            isRecommended = true,
            onClick = onSelectGemini,
            buttonText = "Get Started",
        )

        Spacer(modifier = Modifier.height(10.dp))

        // Claude card
        ProviderCard(
            title = "Claude (Anthropic)",
            subtitle = "Requires a paid API key or Pro subscription.",
            isRecommended = false,
            onClick = onSelectClaude,
            buttonText = "Set Up",
        )

        Spacer(modifier = Modifier.height(10.dp))

        // Advanced options (collapsed)
        var advancedExpanded by remember { mutableStateOf(false) }
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 48.dp)
                .clickable { advancedExpanded = !advancedExpanded },
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = MaterialTheme.shapes.medium,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Rounded.ExpandMore,
                    contentDescription = if (advancedExpanded) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Advanced options",
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (advancedExpanded) {
            Spacer(modifier = Modifier.height(8.dp))
            ProviderCard(
                title = "Ollama (Self-hosted server)",
                subtitle = "Run models on your own hardware.",
                isRecommended = false,
                onClick = onSelectOllama,
                buttonText = "Set Up",
            )
            Spacer(modifier = Modifier.height(8.dp))
            ProviderCard(
                title = "OpenAI-compatible endpoint",
                subtitle = "Any provider with an OpenAI-compatible API.",
                isRecommended = false,
                onClick = onSelectOpenAi,
                buttonText = "Set Up",
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Skip affordance
        TextButton(
            onClick = { /* Continue button in wizard handles this */ },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = "Skip \u2014 the on-device model will handle most tasks once it downloads. You can add a cloud provider later in Settings.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // On-device model status footer
        OnDeviceModelFooter(
            liteRtModelManager = liteRtModelManager,
            liteRtLmProvider = liteRtLmProvider,
            llmProviderManager = llmProviderManager,
        )

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun ProviderCard(
    title: String,
    subtitle: String,
    isRecommended: Boolean,
    onClick: () -> Unit,
    buttonText: String,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = if (isRecommended) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isRecommended) {
                    Icon(
                        imageVector = Icons.Rounded.Star,
                        contentDescription = "Recommended",
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = if (isRecommended) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = if (isRecommended) {
                    MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Spacer(modifier = Modifier.height(10.dp))
            TextButton(
                onClick = onClick,
                modifier = Modifier.defaultMinSize(minHeight = 48.dp),
            ) {
                Text(
                    text = "$buttonText \u2192",
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                )
            }
        }
    }
}

@Composable
private fun OnDeviceModelFooter(
    liteRtModelManager: LiteRtModelManager?,
    liteRtLmProvider: LiteRtLmProvider?,
    llmProviderManager: LlmProviderManager?,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isReady = remember { liteRtLmProvider?.isReady() == true }
    val selectedModel by llmProviderManager?.selectedOnDeviceModel
        ?.collectAsState(initial = liteRtModelManager?.selectedModel ?: OnDeviceModel.GEMMA_1B)
        ?: remember { mutableStateOf(OnDeviceModel.GEMMA_1B) }
    val initialState = remember {
        if (liteRtModelManager?.isModelDownloaded() == true) {
            ModelDownloadState.Completed
        } else {
            ModelDownloadState.NotStarted
        }
    }
    val downloadState by liteRtModelManager?.downloadState()
        ?.collectAsState(initial = initialState)
        ?: remember { mutableStateOf(initialState) }
    val deviceRamMb = remember {
        LiteRtModelManager.getDeviceTotalRamMb(context)
    }

    val statusText = when (val state = downloadState) {
        is ModelDownloadState.NotStarted -> "${selectedModel.sizeDescription} download \u00b7 Requires HuggingFace token\nSet up in ARIA settings after onboarding"
        is ModelDownloadState.Queued -> "Queued \u2014 waiting for Wi-Fi + charging"
        is ModelDownloadState.Downloading -> "Downloading\u2026 ${state.progress}%"
        is ModelDownloadState.Completed -> if (isReady) "Ready (${selectedModel.displayName}) \u00b7 Works offline" else "Downloaded \u2014 will warm up on first use"
        is ModelDownloadState.Failed -> "Download failed: ${state.message}"
    }
    val currentState = downloadState
    val currentDownloading = currentState as? ModelDownloadState.Downloading
    val currentFailed = currentState as? ModelDownloadState.Failed

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.PhoneAndroid,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "On-device AI",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (currentFailed != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Model chooser
            if (llmProviderManager != null) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Model",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(4.dp))
                for (model in OnDeviceModel.entries) {
                    val isEligible = deviceRamMb >= model.minRamMb
                    val isSelected = model == selectedModel
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp)
                            .then(
                                if (isEligible && !isSelected) {
                                    Modifier.clickable {
                                        scope.launch {
                                            withContext(Dispatchers.IO) {
                                                llmProviderManager.setSelectedOnDeviceModel(model)
                                            }
                                        }
                                    }
                                } else {
                                    Modifier
                                },
                            ),
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHigh
                        },
                        shape = MaterialTheme.shapes.small,
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = model.displayName + if (isSelected) " \u2713" else "",
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                color = when {
                                    !isEligible -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                                    isSelected -> MaterialTheme.colorScheme.onPrimaryContainer
                                    else -> MaterialTheme.colorScheme.onSurface
                                },
                            )
                            Text(
                                text = if (!isEligible) {
                                    "Your device doesn\u2019t have enough RAM"
                                } else {
                                    "${model.sizeDescription} \u00b7 ${model.qualityDescription}"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = when {
                                    !isEligible -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                                    isSelected -> MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    }
                }
            }

            if (currentDownloading != null) {
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { currentDownloading.progress / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (currentFailed != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Set up your HuggingFace token in ARIA settings to retry",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

// ── Sub-page: Back button helper ──

@Composable
private fun SubPageBackButton(onBack: () -> Unit) {
    IconButton(
        onClick = onBack,
        modifier = Modifier.size(48.dp),
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
            contentDescription = "Back to provider list",
        )
    }
}

// ── Inline test result display ──

@Composable
private fun TestResultDisplay(
    testResult: String?,
    isTesting: Boolean,
) {
    if (isTesting) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            Text(
                text = "Testing connection\u2026",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    } else if (testResult != null) {
        val isSuccess = testResult.startsWith("Connected")
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (isSuccess) {
                Icon(
                    imageVector = Icons.Rounded.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
            }
            Text(
                text = testResult,
                style = MaterialTheme.typography.bodySmall,
                color = if (isSuccess) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
        }
    }
}

/** Run test and return a human-readable result string. */
private suspend fun runProviderTest(llmProviderManager: LlmProviderManager): String {
    val result = withContext(Dispatchers.IO) { llmProviderManager.testConnection() }
    return when (result) {
        is LlmResult.Text -> "Connected \u2014 responses take about 2 seconds"
        is LlmResult.ToolUse -> "Connected (tool use supported)"
        is LlmResult.Error -> LlmProviderManager.humanizeError(result.message)
    }
}

// ── Gemini Sub-Page ──

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun GeminiSetupSubPage(
    llmProviderManager: LlmProviderManager,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current

    var apiKey by remember { mutableStateOf("") }
    val geminiModels = remember { GeminiProvider.AVAILABLE_MODELS }
    var selectedModelId by remember { mutableStateOf(geminiModels[0].first) }
    var modelDropdownExpanded by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var isTesting by remember { mutableStateOf(false) }

    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
        SubPageBackButton(onBack = onBack)
        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Gemini Setup",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(modifier = Modifier.height(12.dp))

        // Get API key link
        val linkText = buildAnnotatedString {
            append("Sign in with your Google account at ")
            pushStringAnnotation(tag = "URL", annotation = "https://aistudio.google.com/app/apikey")
            withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary, textDecoration = TextDecoration.Underline)) {
                append("Google AI Studio")
            }
            pop()
            append(". Your key will be created automatically.")
        }
        ClickableText(
            text = linkText,
            style = MaterialTheme.typography.bodyMedium.copy(
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
            onClick = { offset ->
                linkText.getStringAnnotations("URL", offset, offset).firstOrNull()?.let {
                    uriHandler.openUri(it.item)
                }
            },
        )

        Spacer(modifier = Modifier.height(16.dp))

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
                        modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Free tier \u00b7 250 requests/day",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it },
            label = { Text("API key") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        )
        Spacer(modifier = Modifier.height(12.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(
                onClick = {
                    if (apiKey.isBlank()) {
                        Toast.makeText(context, "Enter an API key", Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    isTesting = true
                    testResult = null
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            llmProviderManager.configureProvider(
                                type = ProviderType.GEMINI,
                                apiKey = apiKey,
                                modelId = selectedModelId,
                            )
                        }
                        testResult = runProviderTest(llmProviderManager)
                        isTesting = false
                    }
                },
                shapes = ButtonDefaults.shapes(),
                enabled = !isTesting,
            ) {
                Text("Save & Test")
            }
            if (isTesting) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        TestResultDisplay(testResult = testResult, isTesting = false)

        Spacer(modifier = Modifier.height(16.dp))
    }
}

// ── Claude Sub-Page ──

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Suppress("ktlint:compose:parameter-naming")
@Composable
private fun ClaudeSetupSubPage(
    llmProviderManager: LlmProviderManager,
    onQrScanRequested: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 0 = API Key tab, 1 = Pro/OAuth tab
    var selectedTab by remember { mutableIntStateOf(0) }
    var apiKey by remember { mutableStateOf("") }
    var testResult by remember { mutableStateOf<String?>(null) }
    var isTesting by remember { mutableStateOf(false) }

    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
        SubPageBackButton(onBack = onBack)
        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Claude Setup",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(modifier = Modifier.height(12.dp))

        // Tab selector
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
            ) {
                Text("API Key")
            }
            SegmentedButton(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
            ) {
                Text("Pro / OAuth")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (selectedTab == 0) {
            // API Key path
            Text(
                text = "Go to console.anthropic.com \u2192 API Keys \u2192 Create. Requires adding billing (pay-per-use, ~\$3/MTok for Haiku).",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                label = { Text("Claude API key") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
            Spacer(modifier = Modifier.height(12.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(
                    onClick = {
                        if (apiKey.isBlank()) {
                            Toast.makeText(context, "Enter an API key", Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        isTesting = true
                        testResult = null
                        scope.launch {
                            val type = when {
                                apiKey.startsWith("sk-ant-oat01-") -> ProviderType.CLAUDE_OAUTH
                                else -> ProviderType.CLAUDE_API_KEY
                            }
                            withContext(Dispatchers.IO) {
                                llmProviderManager.configureProvider(type = type, apiKey = apiKey)
                            }
                            testResult = runProviderTest(llmProviderManager)
                            isTesting = false
                        }
                    },
                    shapes = ButtonDefaults.shapes(),
                    enabled = !isTesting,
                ) {
                    Text("Save & Test")
                }
                if (isTesting) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            }
        } else {
            // OAuth / Pro path
            Text(
                text = "If you have a Claude Pro subscription (\$20/mo), you can share your session with ARIA via QR code.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Run npx aria-token-qr on your computer, then scan the code.",
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = onQrScanRequested,
                modifier = Modifier.fillMaxWidth(),
                shapes = ButtonDefaults.shapes(),
            ) {
                Text("Scan QR Code")
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        TestResultDisplay(testResult = testResult, isTesting = isTesting)

        Spacer(modifier = Modifier.height(16.dp))
    }
}

// ── Ollama Sub-Page ──

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun OllamaSetupSubPage(
    llmProviderManager: LlmProviderManager,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var serverUrl by remember { mutableStateOf("") }
    var testResult by remember { mutableStateOf<String?>(null) }
    var isTesting by remember { mutableStateOf(false) }

    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
        SubPageBackButton(onBack = onBack)
        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Ollama Setup",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Enter the URL of your Ollama server. This is a self-hosted server on your network, not on this phone.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = serverUrl,
            onValueChange = { serverUrl = it },
            label = { Text("Server URL") },
            placeholder = { Text("http://192.168.1.100:11434") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Spacer(modifier = Modifier.height(12.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(
                onClick = {
                    if (serverUrl.isBlank()) {
                        Toast.makeText(context, "Enter a server URL", Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    isTesting = true
                    testResult = null
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            llmProviderManager.configureProvider(
                                type = ProviderType.OLLAMA,
                                serverUrl = serverUrl,
                            )
                        }
                        testResult = runProviderTest(llmProviderManager)
                        isTesting = false
                    }
                },
                shapes = ButtonDefaults.shapes(),
                enabled = !isTesting,
            ) {
                Text("Save & Test")
            }
            if (isTesting) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        TestResultDisplay(testResult = testResult, isTesting = false)

        Spacer(modifier = Modifier.height(16.dp))
    }
}

// ── OpenAI-Compatible Sub-Page ──

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun OpenAiSetupSubPage(
    llmProviderManager: LlmProviderManager,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var serverUrl by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var modelId by remember { mutableStateOf("") }
    var testResult by remember { mutableStateOf<String?>(null) }
    var isTesting by remember { mutableStateOf(false) }

    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
        SubPageBackButton(onBack = onBack)
        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "OpenAI-Compatible Setup",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Any provider with an OpenAI-compatible API (OpenAI, OpenRouter, Together, etc.).",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = serverUrl,
            onValueChange = { serverUrl = it },
            label = { Text("Base URL") },
            placeholder = { Text("https://api.openai.com/v1") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
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

        OutlinedTextField(
            value = modelId,
            onValueChange = { modelId = it },
            label = { Text("Model ID") },
            placeholder = { Text("gpt-4o") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Spacer(modifier = Modifier.height(12.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(
                onClick = {
                    if (serverUrl.isBlank() || apiKey.isBlank()) {
                        Toast.makeText(context, "Enter URL and API key", Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    isTesting = true
                    testResult = null
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            llmProviderManager.configureProvider(
                                type = ProviderType.OPENAI_COMPATIBLE,
                                apiKey = apiKey,
                                serverUrl = serverUrl,
                                modelId = modelId.ifBlank { null },
                            )
                        }
                        testResult = runProviderTest(llmProviderManager)
                        isTesting = false
                    }
                },
                shapes = ButtonDefaults.shapes(),
                enabled = !isTesting,
            ) {
                Text("Save & Test")
            }
            if (isTesting) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        TestResultDisplay(testResult = testResult, isTesting = false)

        Spacer(modifier = Modifier.height(16.dp))
    }
}
