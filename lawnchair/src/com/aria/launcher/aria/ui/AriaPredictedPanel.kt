package com.aria.launcher.aria.ui

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import com.aria.launcher.aria.ui.brief.composables.AriaBrief
import com.aria.launcher.aria.ui.composables.ChatPill
import com.aria.launcher.aria.ui.composables.PredictedAppsRow
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlin.math.abs

/**
 * Full-page predicted apps panel that sits below the smartspace on the
 * first workspace page. Renders the Brief + Predicted Apps.
 *
 * Note: The greeting and date are rendered by AriaSmartspaceContainer above
 * in the current Lawnchair layout. This panel owns the card feed area.
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
                            startPadding = leftPad,
                            endPadding = rightPad,
                        )
                    }
                }
            }
        }
        addView(composeView)
    }

    private var touchDownX = 0f
    private var touchDownY = 0f
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchDownX = ev.x
                touchDownY = ev.y
                // Tentatively claim touch so we can determine direction
                parent?.requestDisallowInterceptTouchEvent(true)
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = abs(ev.x - touchDownX)
                val dy = abs(ev.y - touchDownY)
                if (dx > touchSlop && dx > dy) {
                    // Horizontal gesture — release to parent for page swiping
                    parent?.requestDisallowInterceptTouchEvent(false)
                }
            }
        }
        return false
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        ariaHomeState.refreshContext()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AriaPanelContent(
    state: AriaHomeState,
    chatState: ChatState,
    startPadding: Int,
    endPadding: Int,
) {
    val predictedApps by state.predictedApps.collectAsState()
    val briefItems by state.briefItems.collectAsState()
    val isRefreshing by state.isRefreshing.collectAsState()
    var showChat by remember { mutableStateOf(false) }

    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = { state.manualRefresh() },
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(rememberNestedScrollInteropConnection())
                .padding(
                    start = (startPadding / 2).dp,
                    end = (endPadding / 2).dp,
                )
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.Top,
        ) {
            Spacer(modifier = Modifier.height(4.dp))

            ChatPill(onClick = { showChat = true })

            if (briefItems.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))

                AriaBrief(
                    items = briefItems,
                    onActionClick = { action -> state.executeAction(action) },
                    onItemDismiss = { item -> state.dismissItem(item) },
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (predictedApps.isNotEmpty()) {
                PredictedAppsRow(
                    apps = predictedApps,
                    onAppClick = { packageName -> state.launchApp(packageName) },
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }

    if (showChat) {
        ChatSheet(
            chatState = chatState,
            onDismiss = { showChat = false },
        )
    }
}
