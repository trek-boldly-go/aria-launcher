package com.aria.launcher.aria.ui.composables

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.aria.launcher.aria.ui.brief.BriefItem

@Composable
fun AriaBar(
    greeting: String,
    onChatTap: () -> Unit,
    modifier: Modifier = Modifier,
    contextBar: BriefItem.ContextBar? = null,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        // Hero greeting — emotional anchor, prominent weight
        Text(
            text = greeting,
            style = MaterialTheme.typography.headlineLarge.copy(
                fontWeight = FontWeight.Bold,
                fontSize = 30.sp,
                letterSpacing = (-0.8).sp,
            ),
            color = MaterialTheme.colorScheme.onSurface,
        )

        // Context line — day · location · weather (via ContextBarComposable)
        ContextBarComposable(contextBar = contextBar)
    }
}
