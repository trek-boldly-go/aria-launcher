package com.aria.launcher.aria.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Listens for power connect/disconnect broadcasts and updates [ContextSignalManager].
 *
 * Registered statically in AndroidManifest so it fires even when the launcher is
 * not in the foreground.
 */
@AndroidEntryPoint
class ChargingReceiver : BroadcastReceiver() {

    @Inject
    lateinit var contextSignalManager: ContextSignalManager

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_POWER_CONNECTED -> contextSignalManager.onChargingChanged(true)
            Intent.ACTION_POWER_DISCONNECTED -> contextSignalManager.onChargingChanged(false)
        }
    }
}
