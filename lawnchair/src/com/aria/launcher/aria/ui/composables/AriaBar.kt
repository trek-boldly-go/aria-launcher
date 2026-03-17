package com.aria.launcher.aria.ui.composables

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aria.launcher.aria.ui.brief.BriefItem
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun AriaBar(
    greeting: String,
    onChatTap: () -> Unit,
    modifier: Modifier = Modifier,
    contextBar: BriefItem.ContextBar? = null,
) {
    val today = LocalDate.now()
    val dateText = remember(today) {
        val formatter = DateTimeFormatter.ofPattern("EEE, MMM d", Locale.getDefault())
        today.format(formatter)
    }

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

        // Context line — date + optional weather + location pill
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val contextLine = when {
                contextBar != null && contextBar.weatherLine.isNotBlank() ->
                    contextBar.weatherLine

                else ->
                    dateText
            }

            Text(
                text = contextLine,
                style = MaterialTheme.typography.bodyMedium.copy(
                    letterSpacing = 0.1.sp,
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
            )

            // Location pill — concise spatial context, only when known
            if (contextBar?.locationHint != null) {
                Spacer(modifier = Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .background(
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                            shape = RoundedCornerShape(10.dp),
                        )
                        .padding(horizontal = 7.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = contextBar.locationHint,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Medium,
                        ),
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f),
                    )
                }
            }
        }
    }
}
