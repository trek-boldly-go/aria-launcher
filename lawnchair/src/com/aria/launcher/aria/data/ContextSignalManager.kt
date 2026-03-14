package com.aria.launcher.aria.data

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.util.Log
import com.google.android.gms.location.ActivityRecognition
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Holds the current device context signals used to annotate usage events.
 * Updated by [ChargingReceiver] and [ActivityUpdateReceiver]; read by [UsageStatsCollector].
 */
@Singleton
class ContextSignalManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val _isCharging = MutableStateFlow(false)
    val isCharging: StateFlow<Boolean> = _isCharging.asStateFlow()

    private val _wifiSsid = MutableStateFlow<String?>(null)
    val wifiSsid: StateFlow<String?> = _wifiSsid.asStateFlow()

    private val _detectedActivity = MutableStateFlow<Int?>(null)
    val detectedActivity: StateFlow<Int?> = _detectedActivity.asStateFlow()

    /** Call once from Application.onCreate() to seed initial state. */
    fun init() {
        _isCharging.value = readChargingState()
        _wifiSsid.value = readWifiSsid()
        registerActivityRecognition()
        registerWifiListener()
        Log.d(TAG, "ContextSignalManager initialized: charging=${_isCharging.value}, wifi=${_wifiSsid.value}")
    }

    // --- Called by receivers ---

    fun onChargingChanged(charging: Boolean) {
        _isCharging.value = charging
    }

    fun onActivityDetected(activityType: Int) {
        _detectedActivity.value = activityType
    }

    /** Re-read WiFi SSID on demand (e.g. after network change). */
    fun refreshWifiSsid() {
        _wifiSsid.value = readWifiSsid()
    }

    /** Snapshot for attaching to a usage event. */
    fun snapshot() = ContextSnapshot(
        isCharging = _isCharging.value,
        wifiSsid = _wifiSsid.value,
        detectedActivity = _detectedActivity.value,
    )

    // --- Private helpers ---

    private fun readChargingState(): Boolean {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: return false
        return status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
    }

    @SuppressLint("MissingPermission")
    private fun readWifiSsid(): String? {
        val wm = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val ssid = wm.connectionInfo?.ssid ?: return null
        if (ssid == WifiManager.UNKNOWN_SSID) return null
        // Strip surrounding quotes Android adds to SSIDs
        return ssid.removePrefix("\"").removeSuffix("\"").ifBlank { null }
    }

    /**
     * Listens for WiFi connectivity changes and refreshes the SSID whenever
     * the device connects to or disconnects from a WiFi network.
     */
    private fun registerWifiListener() {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()

        cm.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                refreshWifiSsid()
            }

            override fun onLost(network: Network) {
                _wifiSsid.value = null
            }
        })
    }

    @SuppressLint("MissingPermission")
    private fun registerActivityRecognition() {
        val intent = Intent(context, ActivityUpdateReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE_ACTIVITY,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        ActivityRecognition.getClient(context)
            .requestActivityUpdates(ACTIVITY_DETECTION_INTERVAL_MS, pendingIntent)
    }

    companion object {
        private const val TAG = "ARIA.ContextSignals"
        private const val REQUEST_CODE_ACTIVITY = 1001
        private const val ACTIVITY_DETECTION_INTERVAL_MS = 30_000L // 30 seconds
    }
}

data class ContextSnapshot(
    val isCharging: Boolean,
    val wifiSsid: String?,
    val detectedActivity: Int?,  // DetectedActivity constants: IN_VEHICLE=0, STILL=3, WALKING=7, etc.
)
