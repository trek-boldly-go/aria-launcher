// Copyright (c) 2026 Donovon Simpson. See LICENSE-ARIA.md for licensing terms.
package com.aria.launcher.aria.ui.onboarding

import android.content.Context
import android.util.Log
import android.widget.Toast
import com.aria.launcher.aria.llm.LlmProviderManager
import com.aria.launcher.aria.llm.ProviderType
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object QrTokenScanner {

    private const val TAG = "ARIA.QrScanner"

    fun scan(
        context: Context,
        llmProviderManager: LlmProviderManager,
        scope: CoroutineScope,
    ) {
        val options = GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .enableAutoZoom()
            .build()

        val scanner = GmsBarcodeScanning.getClient(context, options)

        scanner.startScan()
            .addOnSuccessListener { barcode ->
                val rawValue = barcode.rawValue
                if (rawValue == null) {
                    Toast.makeText(context, "Empty QR code", Toast.LENGTH_SHORT).show()
                    return@addOnSuccessListener
                }

                Log.d(TAG, "QR scanned, parsing token payload")
                scope.launch {
                    try {
                        val parsed = Json.parseToJsonElement(rawValue).jsonObject
                        val accessToken = parsed["access_token"]?.jsonPrimitive?.contentOrNull
                        val refreshToken = parsed["refresh_token"]?.jsonPrimitive?.contentOrNull

                        if (accessToken == null) {
                            Toast.makeText(context, "Invalid QR: no access_token found", Toast.LENGTH_SHORT).show()
                            return@launch
                        }

                        withContext(Dispatchers.IO) {
                            llmProviderManager.configureProvider(
                                type = ProviderType.CLAUDE_OAUTH,
                                apiKey = accessToken,
                                refreshToken = refreshToken,
                            )
                        }
                        Toast.makeText(context, "Claude OAuth configured via QR", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to parse QR payload", e)
                        Toast.makeText(context, "Invalid QR format: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "QR scan failed", e)
                Toast.makeText(context, "Scan failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
            .addOnCanceledListener {
                Log.d(TAG, "QR scan cancelled")
            }
    }
}
