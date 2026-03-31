// Copyright (c) 2026 Donovon Simpson. All rights reserved. See LICENSE-ARIA.md
package app.lawnchair.ui.preferences.destinations

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.lawnchair.ui.preferences.LocalNavController
import com.aria.launcher.aria.llm.LlmProviderManager
import com.aria.launcher.aria.llm.ProviderType
import com.aria.launcher.aria.llm.displayName
import com.aria.launcher.aria.ui.onboarding.ClaudeSetupSubPage
import com.aria.launcher.aria.ui.onboarding.GeminiSetupSubPage
import com.aria.launcher.aria.ui.onboarding.OllamaSetupSubPage
import com.aria.launcher.aria.ui.onboarding.OpenAiSetupSubPage
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
private interface AriaLlmSetupEntryPoint {
    fun llmProviderManager(): LlmProviderManager
}

private enum class SetupScreen {
    CHOOSER,
    GEMINI,
    CLAUDE,
    OLLAMA,
    OPENAI,
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AriaLlmProviderSetupPreferences(
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val navController = LocalNavController.current
    val entryPoint = remember {
        EntryPointAccessors.fromApplication(context, AriaLlmSetupEntryPoint::class.java)
    }
    val llmProviderManager = entryPoint.llmProviderManager()
    val activeType by llmProviderManager.activeProviderType.collectAsState(initial = null)

    var currentScreen by remember { mutableStateOf(SetupScreen.CHOOSER) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("AI Provider") },
                navigationIcon = {
                    IconButton(onClick = {
                        if (currentScreen == SetupScreen.CHOOSER) {
                            navController.popBackStack()
                        } else {
                            currentScreen = SetupScreen.CHOOSER
                        }
                    }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
            )
        },
    ) { padding ->
        AnimatedContent(
            targetState = currentScreen,
            modifier = Modifier.padding(padding),
            transitionSpec = {
                if (targetState == SetupScreen.CHOOSER) {
                    (slideInHorizontally { -it / 3 } + fadeIn()) togetherWith
                        (slideOutHorizontally { it / 3 } + fadeOut())
                } else {
                    (slideInHorizontally { it / 3 } + fadeIn()) togetherWith
                        (slideOutHorizontally { -it / 3 } + fadeOut())
                }
            },
            label = "llmSettingsTransition",
        ) { screen ->
            when (screen) {
                SetupScreen.CHOOSER -> SettingsProviderChooser(
                    activeType = activeType,
                    onSelectGemini = { currentScreen = SetupScreen.GEMINI },
                    onSelectClaude = { currentScreen = SetupScreen.CLAUDE },
                    onSelectOllama = { currentScreen = SetupScreen.OLLAMA },
                    onSelectOpenAi = { currentScreen = SetupScreen.OPENAI },
                )

                SetupScreen.GEMINI -> GeminiSetupSubPage(
                    llmProviderManager = llmProviderManager,
                    onBack = { currentScreen = SetupScreen.CHOOSER },
                    onSuccess = { navController.popBackStack() },
                )

                SetupScreen.CLAUDE -> ClaudeSetupSubPage(
                    llmProviderManager = llmProviderManager,
                    onQrScanRequested = {},
                    onBack = { currentScreen = SetupScreen.CHOOSER },
                    onSuccess = { navController.popBackStack() },
                )

                SetupScreen.OLLAMA -> OllamaSetupSubPage(
                    llmProviderManager = llmProviderManager,
                    onBack = { currentScreen = SetupScreen.CHOOSER },
                    onSuccess = { navController.popBackStack() },
                )

                SetupScreen.OPENAI -> OpenAiSetupSubPage(
                    llmProviderManager = llmProviderManager,
                    onBack = { currentScreen = SetupScreen.CHOOSER },
                    onSuccess = { navController.popBackStack() },
                )
            }
        }
    }
}

@Composable
private fun SettingsProviderChooser(
    activeType: ProviderType?,
    onSelectGemini: () -> Unit,
    onSelectClaude: () -> Unit,
    onSelectOllama: () -> Unit,
    onSelectOpenAi: () -> Unit,
) {
    Column(
        modifier = Modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Choose a cloud AI provider for complex tasks like conversations and multi-step actions.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(20.dp))

        SettingsProviderCard(
            title = "Gemini (Free)",
            subtitle = "Google\u2019s AI. Free API key required.",
            isActive = activeType == ProviderType.GEMINI,
            isRecommended = true,
            onClick = onSelectGemini,
        )

        Spacer(modifier = Modifier.height(10.dp))

        SettingsProviderCard(
            title = "Claude (Anthropic)",
            subtitle = "Requires a paid API key or Pro subscription.",
            isActive = activeType == ProviderType.CLAUDE_API_KEY ||
                activeType == ProviderType.CLAUDE_OAUTH,
            onClick = onSelectClaude,
        )

        Spacer(modifier = Modifier.height(10.dp))

        SettingsProviderCard(
            title = "Ollama (Self-hosted)",
            subtitle = "Run models on your own hardware.",
            isActive = activeType == ProviderType.OLLAMA,
            onClick = onSelectOllama,
        )

        Spacer(modifier = Modifier.height(10.dp))

        SettingsProviderCard(
            title = "OpenAI-compatible endpoint",
            subtitle = "OpenAI, OpenRouter, Together, etc.",
            isActive = activeType == ProviderType.OPENAI_COMPATIBLE ||
                activeType == ProviderType.OPEN_ROUTER,
            onClick = onSelectOpenAi,
        )

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun SettingsProviderCard(
    title: String,
    subtitle: String,
    isActive: Boolean,
    isRecommended: Boolean = false,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        color = when {
            isActive -> MaterialTheme.colorScheme.primaryContainer
            isRecommended -> MaterialTheme.colorScheme.surfaceContainerHigh
            else -> MaterialTheme.colorScheme.surfaceContainerHigh
        },
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isActive) {
                    Icon(
                        imageVector = Icons.Rounded.CheckCircle,
                        contentDescription = "Active",
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                } else if (isRecommended) {
                    Icon(
                        imageVector = Icons.Rounded.Star,
                        contentDescription = "Recommended",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = if (isActive) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = if (isActive) "Active \u00b7 $subtitle" else subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = if (isActive) {
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
                    text = if (isActive) "Reconfigure \u2192" else "Set Up \u2192",
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontWeight = FontWeight.SemiBold,
                    ),
                )
            }
        }
    }
}
