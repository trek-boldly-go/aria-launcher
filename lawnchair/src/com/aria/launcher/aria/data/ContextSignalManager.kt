package com.aria.launcher.aria.data

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.ActivityRecognition
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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

    private val _isAndroidAutoConnected = MutableStateFlow(false)
    val isAndroidAutoConnected: StateFlow<Boolean> = _isAndroidAutoConnected.asStateFlow()

    private val _connectedCarName = MutableStateFlow<String?>(null)
    val connectedCarName: StateFlow<String?> = _connectedCarName.asStateFlow()

    private val _nearbySSIDs = MutableStateFlow<List<String>>(emptyList())
    val nearbySSIDs: StateFlow<List<String>> = _nearbySSIDs.asStateFlow()

    /** Call once from Application.onCreate() to seed initial state. */
    fun init() {
        _isCharging.value = readChargingState()
        _wifiSsid.value = readWifiSsid()
        _isAndroidAutoConnected.value = AndroidAutoReceiver.isCurrentlyInCarMode(context)
        _connectedCarName.value = AndroidAutoReceiver.lastCarName
        registerActivityRecognition()
        registerWifiListener()
        registerAndroidAutoListener()
        Log.d(TAG, "ContextSignalManager initialized: charging=${_isCharging.value}, wifi=${_wifiSsid.value}")
    }

    // --- Called by receivers ---

    fun onChargingChanged(charging: Boolean) {
        _isCharging.value = charging
    }

    fun onActivityDetected(activityType: Int) {
        _detectedActivity.value = activityType
    }

    fun onAndroidAutoChanged(connected: Boolean, carName: String?) {
        _isAndroidAutoConnected.value = connected
        _connectedCarName.value = carName
    }

    fun updateNearbySSIDs(ssids: List<String>) {
        _nearbySSIDs.value = ssids
    }

    /** Re-read WiFi SSID on demand (e.g. after network change). */
    fun refreshWifiSsid() {
        _wifiSsid.value = readWifiSsid()
    }

    /** Debug: override the WiFi SSID with a fake value for testing venue classification. */
    fun debugOverrideSsid(ssid: String?) {
        _wifiSsid.value = ssid
        Log.d(TAG, "Debug SSID override: $ssid")
    }

    /** Snapshot for attaching to a usage event. */
    fun snapshot() = ContextSnapshot(
        isCharging = _isCharging.value,
        wifiSsid = _wifiSsid.value,
        detectedActivity = _detectedActivity.value,
        isAndroidAutoConnected = _isAndroidAutoConnected.value,
        connectedCarName = _connectedCarName.value,
    )

    // --- Private helpers ---

    private fun readChargingState(): Boolean {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: return false
        return status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
    }

    /**
     * Reads WiFi SSID using the modern NetworkCapabilities API.
     * The deprecated WifiManager.connectionInfo.ssid returns <unknown ssid>
     * on Android 12+ even with ACCESS_FINE_LOCATION granted.
     */
    private fun readWifiSsid(): String? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            if (!loggedWifiPermWarning) {
                Log.w(TAG, "WiFi SSID unavailable: ACCESS_FINE_LOCATION not granted")
                loggedWifiPermWarning = true
            }
            return null
        }
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return null
        val caps = cm.getNetworkCapabilities(network) ?: return null
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return null
        return extractSsid(caps)
    }

    /**
     * Listens for WiFi connectivity changes and reads SSID from
     * [NetworkCapabilities.getTransportInfo] — the modern API that works on Android 12+.
     */
    private fun registerWifiListener() {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()

        cm.registerNetworkCallback(
            request,
            object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(
                    network: Network,
                    caps: NetworkCapabilities,
                ) {
                    _wifiSsid.value = extractSsid(caps)
                }

                override fun onLost(network: Network) {
                    _wifiSsid.value = null
                }
            },
        )
    }

    /** Extract SSID from NetworkCapabilities, stripping quotes and filtering unknowns. */
    private fun extractSsid(caps: NetworkCapabilities): String? {
        val wifiInfo = caps.transportInfo as? WifiInfo ?: return null
        val ssid = wifiInfo.ssid ?: return null
        if (ssid == WifiManager.UNKNOWN_SSID) return null
        return ssid.removePrefix("\"").removeSuffix("\"").ifBlank { null }
    }

    private fun registerAndroidAutoListener() {
        AndroidAutoReceiver.onConnectionChanged = { connected, carName ->
            onAndroidAutoChanged(connected, carName)
        }
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
            .addOnFailureListener { e ->
                Log.w(TAG, "Activity recognition request failed", e)
            }
    }

    private var loggedWifiPermWarning = false

    companion object {
        private const val TAG = "ARIA.ContextSignals"
        private const val REQUEST_CODE_ACTIVITY = 1001
        private const val ACTIVITY_DETECTION_INTERVAL_MS = 30_000L // 30 seconds
    }
}

data class ContextSnapshot(
    val isCharging: Boolean,
    val wifiSsid: String?,
    val detectedActivity: Int?, // DetectedActivity constants: IN_VEHICLE=0, STILL=3, WALKING=7, etc.
    val isAndroidAutoConnected: Boolean = false,
    val connectedCarName: String? = null,
)
