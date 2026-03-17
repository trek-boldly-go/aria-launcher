// Copyright (c) 2026 Donovon Simpson. All rights reserved. See LICENSE-ARIA.md
package com.aria.launcher.aria.ui.onboarding

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Suppress("ktlint:compose:parameter-naming")
@Composable
fun WifiSetupPage(
    homeWifi: String,
    workWifi: String,
    currentWifi: String?,
    onHomeWifiChanged: (String) -> Unit,
    onWorkWifiChanged: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    OnboardingPageLayout(
        title = "WiFi Labels",
        subtitle = "Tell ARIA which WiFi networks are home and work so it can provide location-aware predictions without GPS.",
        modifier = modifier,
    ) {
        if (currentWifi != null) {
            Text(text = "Currently connected: $currentWifi")
            Spacer(modifier = Modifier.height(16.dp))
        }

        OutlinedTextField(
            value = homeWifi,
            onValueChange = onHomeWifiChanged,
            label = { Text("Home WiFi name") },
            placeholder = { Text(currentWifi ?: "e.g. MyHomeNetwork") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = workWifi,
            onValueChange = onWorkWifiChanged,
            label = { Text("Work WiFi name") },
            placeholder = { Text("e.g. CorpWiFi") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
    }
}
