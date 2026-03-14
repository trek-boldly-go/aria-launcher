package com.aria.launcher.aria.ui

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.lawnchair.LawnchairLauncher
import app.lawnchair.animateToAllApps
import app.lawnchair.launcherNullable
import app.lawnchair.ui.theme.LawnchairTheme
import app.lawnchair.util.ProvideLifecycleState
import com.aria.launcher.aria.ui.composables.PredictedAppsGrid
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Full-page predicted apps panel that sits below the smartspace on the
 * first workspace page. Shows a grid of predicted app icons based on
 * the current context.
 */
class AriaPredictedPanel @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface PanelEntryPoint {
        fun ariaHomeState(): AriaHomeState
    }

    private val ariaHomeState: AriaHomeState

    init {
        val entryPoint = EntryPointAccessors.fromApplication(
            context.applicationContext,
            PanelEntryPoint::class.java,
        )
        ariaHomeState = entryPoint.ariaHomeState()

        val launcher = LawnchairLauncher.instance
        val dp = launcher?.launcherNullable?.deviceProfile
        val leftPad = dp?.widgetPadding?.left ?: 48
        val rightPad = dp?.widgetPadding?.right ?: 48
        val numColumns = dp?.inv?.numColumns ?: 5

        val onChatTap: () -> Unit = {
            val l = LawnchairLauncher.instance
            if (l != null) {
                CoroutineScope(Dispatchers.Main.immediate).launch {
                    l.animateToAllApps()
                }
            }
        }

        val composeView = ComposeView(context).apply {
            setContent {
                LawnchairTheme {
                    ProvideLifecycleState {
                        AriaPanelContent(
                            state = ariaHomeState,
                            numColumns = numColumns,
                            startPadding = leftPad,
                            endPadding = rightPad,
                            onChatTap = onChatTap,
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
    numColumns: Int,
    startPadding: Int,
    endPadding: Int,
    onChatTap: () -> Unit,
) {
    val greeting by state.greeting.collectAsState()
    val predictedApps by state.predictedApps.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(
                start = (startPadding / 2).dp,
                end = (endPadding / 2).dp,
                top = 4.dp,
            ),
        verticalArrangement = Arrangement.Top,
    ) {
        Text(
            text = greeting,
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
            ),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(bottom = 12.dp),
        )

        PredictedAppsGrid(
            apps = predictedApps,
            columns = numColumns,
            onAppClick = { packageName -> state.launchApp(packageName) },
            modifier = Modifier.weight(1f),
        )
    }
}
