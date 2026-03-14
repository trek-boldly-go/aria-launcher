package com.aria.launcher.aria.ui.composables

import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aria.launcher.aria.ui.PredictedApp
import com.google.accompanist.drawablepainter.rememberDrawablePainter

@Composable
fun PredictedAppsRow(
    apps: List<PredictedApp>,
    onAppClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (apps.isEmpty()) return

    LazyRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        contentPadding = PaddingValues(horizontal = 0.dp),
    ) {
        items(apps, key = { it.packageName }) { app ->
            PredictedAppIcon(
                label = app.label,
                icon = app.icon,
                onClick = { onAppClick(app.packageName) },
            )
        }
    }
}

@Composable
fun PredictedAppsGrid(
    apps: List<PredictedApp>,
    columns: Int,
    onAppClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (apps.isEmpty()) return

    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        userScrollEnabled = false,
    ) {
        items(apps, key = { it.packageName }) { app ->
            PredictedAppIcon(
                label = app.label,
                icon = app.icon,
                onClick = { onAppClick(app.packageName) },
            )
        }
    }
}

@Composable
private fun PredictedAppIcon(
    label: String,
    icon: Drawable,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .width(64.dp)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(
            painter = rememberDrawablePainter(drawable = icon),
            contentDescription = label,
            modifier = Modifier.size(52.dp),
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}
