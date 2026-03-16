package com.aria.launcher.aria.ui

import android.content.Context
import android.util.AttributeSet
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.rememberNestedScrollInteropConnection
import androidx.compose.ui.unit.dp
import app.lawnchair.LawnchairLauncher
import app.lawnchair.launcherNullable
import app.lawnchair.ui.theme.LawnchairTheme
import app.lawnchair.util.ProvideLifecycleState
import com.aria.launcher.aria.chat.ChatState
import com.aria.launcher.aria.chat.composables.ChatSheet
import com.aria.launcher.aria.ui.composables.CardFeed
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * Full-page predicted apps panel that sits below the smartspace on the
 * first workspace page. Shows cards + predicted app grid based on context.
 *
 * Note: The greeting and date are rendered by AriaSmartspaceContainer above.
 * This panel only renders the card feed content area.
 */
class AriaPredictedPanel @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface PanelEntryPoint {
        fun ariaHomeState(): AriaHomeState
        fun chatState(): ChatState
    }

    private val ariaHomeState: AriaHomeState
    private val chatState: ChatState

    init {
        val entryPoint = EntryPointAccessors.fromApplication(
            context.applicationContext,
            PanelEntryPoint::class.java,
        )
        ariaHomeState = entryPoint.ariaHomeState()
        chatState = entryPoint.chatState()

        val launcher = LawnchairLauncher.instance
        val dp = launcher?.launcherNullable?.deviceProfile
        val leftPad = dp?.widgetPadding?.left ?: 48
        val rightPad = dp?.widgetPadding?.right ?: 48

        val composeView = ComposeView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            setContent {
                LawnchairTheme {
                    ProvideLifecycleState {
                        AriaPanelContent(
                            state = ariaHomeState,
                            chatState = chatState,
                            appContext = context,
                            startPadding = leftPad,
                            endPadding = rightPad,
                        )
                    }
                }
            }
        }
        addView(composeView)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        ariaHomeState.refreshContext()
    }
}

@Composable
private fun AriaPanelContent(
    state: AriaHomeState,
    chatState: ChatState,
    appContext: Context,
    startPadding: Int,
    endPadding: Int,
) {
    val predictedApps by state.predictedApps.collectAsState()
    val skillResults by state.skillResults.collectAsState()
    var showChat by remember { mutableStateOf(false) }

    val cards = remember(skillResults) {
        CardFeedState.buildCards(skillResults, appContext)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(rememberNestedScrollInteropConnection())
            .padding(
                start = (startPadding / 2).dp,
                end = (endPadding / 2).dp,
            ),
        verticalArrangement = Arrangement.Top,
    ) {
        CardFeed(
            cards = cards,
            predictedApps = predictedApps,
            onCardClick = { card ->
                val firstOpenAction = card.actions.firstOrNull { it.type == "OPEN_APP" }
                if (firstOpenAction != null) {
                    CardFeedState.handleAction(firstOpenAction, appContext)
                }
            },
            onActionClick = { action ->
                CardFeedState.handleAction(action, appContext)
            },
            onAppClick = { packageName -> state.launchApp(packageName) },
            onChatTap = { showChat = true },
            modifier = Modifier.weight(1f),
        )
    }

    if (showChat) {
        ChatSheet(
            chatState = chatState,
            onDismiss = { showChat = false },
        )
    }
}
