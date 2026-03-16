// Copyright (c) 2026 Donovon Simpson. All rights reserved. See LICENSE-ARIA.md
package com.aria.launcher.aria.data

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class AndroidAutoReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_AA_CONNECTION -> {
                val connected = intent.getBooleanExtra("connected", false)
                val deviceName = intent.getParcelableExtra<BluetoothDevice>(
                    BluetoothDevice.EXTRA_DEVICE,
                )?.name
                Log.d(TAG, "Android Auto connection: connected=$connected, device=$deviceName")
                lastConnected = connected
                lastCarName = if (connected) deviceName else null
                onConnectionChanged?.invoke(connected, lastCarName)
            }
            ACTION_AA_DISCONNECT -> {
                Log.d(TAG, "Android Auto disconnected")
                lastConnected = false
                lastCarName = null
                onConnectionChanged?.invoke(false, null)
            }
        }
    }

    companion object {
        private const val TAG = "ARIA.AndroidAuto"
        private const val ACTION_AA_CONNECTION = "android.car.CONNECTION_STATE"
        private const val ACTION_AA_DISCONNECT = "android.car.DISCONNECTION_STATE"

        @Volatile
        var lastConnected: Boolean = false
            private set

        @Volatile
        var lastCarName: String? = null
            private set

        @Volatile
        var onConnectionChanged: ((connected: Boolean, carName: String?) -> Unit)? = null
    }
}
