package com.aria.launcher.aria.ui.composables

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun AriaBar(
    greeting: String,
    onChatTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Recompute date when the day changes (key on day-of-year to avoid stale caching)
    val today = LocalDate.now()
    val dateText = remember(today) {
        val formatter = DateTimeFormatter.ofPattern("EEEE, MMMM d", Locale.getDefault())
        today.format(formatter)
    }

    Column(modifier = modifier.fillMaxWidth()) {
        // Hero greeting — the emotional anchor
        Text(
            text = greeting,
            style = MaterialTheme.typography.headlineLarge.copy(
                fontWeight = FontWeight.Bold,
                fontSize = 28.sp,
                letterSpacing = (-0.5).sp,
            ),
            color = MaterialTheme.colorScheme.onSurface,
        )

        // Date line — grounds the user in time
        Text(
            text = dateText,
            style = MaterialTheme.typography.bodyMedium.copy(
                letterSpacing = 0.3.sp,
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        )
    }
}
