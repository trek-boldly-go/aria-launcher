// Copyright (c) 2026 Donovon Simpson. All rights reserved. See LICENSE-ARIA.md
package com.aria.launcher.aria.data

import android.app.UiModeManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.util.Log

class AndroidAutoReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_ENTER_CAR -> {
                Log.d(TAG, "Entered car mode (Android Auto)")
                lastConnected = true
                lastCarName = null
                onConnectionChanged?.invoke(true, null)
            }

            ACTION_EXIT_CAR -> {
                Log.d(TAG, "Exited car mode (Android Auto)")
                lastConnected = false
                lastCarName = null
                onConnectionChanged?.invoke(false, null)
            }
        }
    }

    companion object {
        private const val TAG = "ARIA.AndroidAuto"
        private const val ACTION_ENTER_CAR = "android.app.action.ENTER_CAR_MODE"
        private const val ACTION_EXIT_CAR = "android.app.action.EXIT_CAR_MODE"

        @Volatile
        var lastConnected: Boolean = false
            private set

        @Volatile
        var lastCarName: String? = null
            private set

        @Volatile
        var onConnectionChanged: ((connected: Boolean, carName: String?) -> Unit)? = null

        /** Check current car mode state via UiModeManager (for startup initialization). */
        fun isCurrentlyInCarMode(context: Context): Boolean {
            val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager
            return uiModeManager.currentModeType == Configuration.UI_MODE_TYPE_CAR
        }
    }
}
