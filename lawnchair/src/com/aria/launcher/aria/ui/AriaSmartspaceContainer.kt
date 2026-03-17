package com.aria.launcher.aria.ui

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import app.lawnchair.LawnchairLauncher
import app.lawnchair.launcherNullable
import app.lawnchair.ui.theme.LawnchairTheme
import app.lawnchair.util.ProvideLifecycleState
import com.aria.launcher.aria.ui.composables.AriaBar
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * Smartspace container — occupies the top rows of the home screen.
 * Renders only the hero greeting and date. All content (cards, chat pill,
 * predicted apps) is rendered by AriaPredictedPanel below.
 */
class AriaSmartspaceContainer @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface AriaEntryPoint {
        fun ariaHomeState(): AriaHomeState
    }

    private val ariaHomeState: AriaHomeState

    init {
        val entryPoint = EntryPointAccessors.fromApplication(
            context.applicationContext,
            AriaEntryPoint::class.java,
        )
        ariaHomeState = entryPoint.ariaHomeState()

        val launcher = LawnchairLauncher.instance
        val dp = launcher?.launcherNullable?.deviceProfile
        val leftPad = dp?.widgetPadding?.left ?: 48
        val rightPad = dp?.widgetPadding?.right ?: 48

        val composeView = ComposeView(context).apply {
            setContent {
                LawnchairTheme {
                    ProvideLifecycleState {
                        AriaSmartspaceContent(
                            state = ariaHomeState,
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
private fun AriaSmartspaceContent(
    state: AriaHomeState,
    startPadding: Int,
    endPadding: Int,
) {
    val greeting by state.greeting.collectAsState()
    val contextBar by state.contextBar.collectAsState()

    AriaBar(
        greeting = greeting,
        onChatTap = { /* Chat is handled by AriaPredictedPanel */ },
        contextBar = contextBar,
        modifier = Modifier
            .fillMaxSize()
            .padding(
                start = (startPadding / 2).dp,
                end = (endPadding / 2).dp,
                top = 8.dp,
            ),
    )
}
