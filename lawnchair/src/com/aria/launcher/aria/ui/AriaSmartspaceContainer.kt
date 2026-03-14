package com.aria.launcher.aria.ui

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import app.lawnchair.LawnchairLauncher
import app.lawnchair.animateToAllApps
import app.lawnchair.launcherNullable
import app.lawnchair.ui.theme.LawnchairTheme
import app.lawnchair.util.ProvideLifecycleState
import com.aria.launcher.aria.ui.composables.AriaBar
import com.aria.launcher.aria.ui.composables.PredictedAppsRow
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

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

        // Apply padding from the device profile (matches smartspace behavior)
        val launcher = LawnchairLauncher.instance
        val dp = launcher?.launcherNullable?.deviceProfile
        val leftPad = dp?.widgetPadding?.left ?: 48
        val rightPad = dp?.widgetPadding?.right ?: 48

        val onChatTap: () -> Unit = {
            val launcher = LawnchairLauncher.instance
            if (launcher != null) {
                CoroutineScope(Dispatchers.Main.immediate).launch {
                    launcher.animateToAllApps()
                }
            }
        }

        val composeView = ComposeView(context).apply {
            setContent {
                LawnchairTheme {
                    ProvideLifecycleState {
                        AriaSmartspaceContent(
                            state = ariaHomeState,
                            onChatTap = onChatTap,
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
    onChatTap: () -> Unit,
    startPadding: Int,
    endPadding: Int,
) {
    val greeting by state.greeting.collectAsState()
    val predictedApps by state.predictedApps.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(
                start = (startPadding / 2).dp,
                end = (endPadding / 2).dp,
                top = 8.dp,
            ),
    ) {
        AriaBar(
            greeting = greeting,
            onChatTap = onChatTap,
        )

        Spacer(modifier = Modifier.height(12.dp))

        PredictedAppsRow(
            apps = predictedApps,
            onAppClick = { packageName -> state.launchApp(packageName) },
        )
    }
}
