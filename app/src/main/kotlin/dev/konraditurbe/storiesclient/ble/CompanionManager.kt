package dev.konraditurbe.storiesclient.ble

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothLeDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresPermission
import java.util.regex.Pattern

/**
 * Discovers and associates the Ray-Ban Stories glasses ("RB Stories ####").
 *
 * Primary path: [CompanionDeviceManager] with a BLE name filter; the system chooser result must be forwarded
 * from the host Activity's `onActivityResult` to [onAssociationActivityResult]. Fallback: a direct BLE scan
 * that returns the first advertisement whose name matches [NAME_PATTERN].
 */
class CompanionManager(context: Context) {

    /** Result callback for both the CDM and direct-scan paths. */
    interface DeviceCallback {
        /**
         * A matching glasses device was found. [device] may be null on the CDM path on older APIs where only a
         * MAC string is returned; then [macAddress] is non-null.
         */
        fun onDeviceFound(device: BluetoothDevice?, macAddress: String?)
        fun onError(message: String)
    }

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    private var pendingCallback: DeviceCallback? = null
    private var activeScanner: BluetoothLeScanner? = null
    private var activeScanCallback: ScanCallback? = null
    private var scanTimeout: Runnable? = null

    // ---------------------------------------------------------------------------------- CDM association

    /**
     * Begin CompanionDeviceManager association. The chosen device arrives via the activity's
     * `onActivityResult`, which the caller forwards to [onAssociationActivityResult]. Falls back to
     * [scanFallback] if CDM is unavailable or fails.
     */
    @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT])
    fun associate(activity: Activity, cb: DeviceCallback) {
        pendingCallback = cb
        val cdm = appContext.getSystemService(Context.COMPANION_DEVICE_SERVICE) as? CompanionDeviceManager
        if (cdm == null) {
            Log.w(TAG, "CompanionDeviceManager unavailable; falling back to direct scan")
            scanFallback(cb); return
        }
        val request = AssociationRequest.Builder()
            .addDeviceFilter(BluetoothLeDeviceFilter.Builder().setNamePattern(Pattern.compile(NAME_PATTERN)).build())
            .setSingleDevice(false)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            cdm.associate(request, { main.post(it) }, object : CompanionDeviceManager.Callback() {
                override fun onAssociationPending(intentSender: IntentSender) = launchChooser(activity, intentSender, cb)
                override fun onAssociationCreated(associationInfo: AssociationInfo) {
                    val dev = associationInfo.associatedDevice?.bleDevice?.device
                    val mac = associationInfo.deviceMacAddress?.toString()
                    deliver(resolveDevice(dev, mac), mac, cb)
                }
                override fun onFailure(error: CharSequence?) = failOrFallback("CDM associate failed: $error", cb)
            })
        } else {
            @Suppress("DEPRECATION")
            cdm.associate(request, object : CompanionDeviceManager.Callback() {
                @Deprecated("Deprecated in Java")
                override fun onDeviceFound(chooserLauncher: IntentSender) = launchChooser(activity, chooserLauncher, cb)
                override fun onFailure(error: CharSequence?) = failOrFallback("CDM associate failed: $error", cb)
            }, null)
        }
    }

    private fun launchChooser(activity: Activity, intentSender: IntentSender, cb: DeviceCallback) {
        try {
            activity.startIntentSenderForResult(intentSender, REQUEST_CODE_CDM, null, 0, 0, 0)
        } catch (e: IntentSender.SendIntentException) {
            failOrFallback("startIntentSender failed: ${e.message}", cb)
        }
    }

    /** Forward the host activity's `onActivityResult` for [REQUEST_CODE_CDM]. */
    @Suppress("DEPRECATION")
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun onAssociationActivityResult(resultCode: Int, data: Intent?) {
        val cb = pendingCallback ?: return
        if (resultCode != Activity.RESULT_OK || data == null) {
            cb.onError("CDM chooser cancelled (resultCode=$resultCode)"); return
        }
        var device: BluetoothDevice? = null
        var mac: String? = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            data.getParcelableExtra(CompanionDeviceManager.EXTRA_ASSOCIATION, AssociationInfo::class.java)?.let { info ->
                device = info.associatedDevice?.bleDevice?.device
                mac = info.deviceMacAddress?.toString()
            }
        }
        if (device == null) {
            when (val extra = data.getParcelableExtra<android.os.Parcelable>(CompanionDeviceManager.EXTRA_DEVICE)) {
                is BluetoothDevice -> device = extra
                is ScanResult -> device = extra.device
            }
        }
        device = resolveDevice(device, mac)
        if (device == null && mac == null) {
            cb.onError("CDM result had no device"); return
        }
        cb.onDeviceFound(device, if (device != null) null else mac)
    }

    // ------------------------------------------------------------------------------ direct-scan fallback

    /** Direct BLE scan: returns the first advertisement matching [NAME_PATTERN], then stops. */
    @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT])
    fun scanFallback(cb: DeviceCallback) {
        pendingCallback = cb
        val adapter = (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            cb.onError("Bluetooth adapter unavailable or disabled"); return
        }
        val scanner = adapter.bluetoothLeScanner ?: run { cb.onError("BLE scanner unavailable"); return }
        val pattern = Pattern.compile(NAME_PATTERN)

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult?) { handle(result) }
            override fun onBatchScanResults(results: List<ScanResult>) { results.firstOrNull { handle(it) } }
            override fun onScanFailed(errorCode: Int) {
                stopScan(); cb.onError("BLE scan failed, errorCode=$errorCode")
            }
            private fun handle(result: ScanResult?): Boolean {
                val name = result?.let(::nameOf) ?: return false
                if (!pattern.matcher(name).matches()) return false
                stopScan()
                cb.onDeviceFound(result.device, null)
                return true
            }
        }
        activeScanner = scanner
        activeScanCallback = callback
        scanTimeout = Runnable {
            stopScan(); cb.onError("BLE scan timed out after ${SCAN_TIMEOUT_MS}ms")
        }.also { main.postDelayed(it, SCAN_TIMEOUT_MS) }

        try {
            scanner.startScan(callback)
        } catch (e: SecurityException) {
            stopScan(); cb.onError("startScan SecurityException: ${e.message}")
        }
    }

    private fun nameOf(result: ScanResult): String? =
        result.scanRecord?.deviceName ?: try { result.device?.name } catch (e: SecurityException) { null }

    /** Stop and tear down any active fallback scan. Safe to call repeatedly. */
    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    fun stopScan() {
        scanTimeout?.let(main::removeCallbacks); scanTimeout = null
        val s = activeScanner; val c = activeScanCallback
        if (s != null && c != null) runCatching { s.stopScan(c) }
        activeScanner = null; activeScanCallback = null
    }

    // --------------------------------------------------------------------------------------- helpers

    private fun resolveDevice(device: BluetoothDevice?, mac: String?): BluetoothDevice? {
        if (device != null) return device
        if (mac == null) return null
        val adapter = (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter ?: return null
        return try { adapter.getRemoteDevice(mac) } catch (e: IllegalArgumentException) { null }
    }

    private fun deliver(device: BluetoothDevice?, mac: String?, cb: DeviceCallback) {
        if (device == null && mac == null) { cb.onError("association produced no device"); return }
        cb.onDeviceFound(device, if (device != null) null else mac)
    }

    @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT])
    private fun failOrFallback(message: String, cb: DeviceCallback) {
        Log.w(TAG, "$message — falling back to direct scan")
        scanFallback(cb)
    }

    companion object {
        private const val TAG = "CompanionManager"

        /** Advertised name pattern for the glasses ("RB Stories 0050"). */
        const val NAME_PATTERN = "RB Stories.*"

        /** Request code for `startIntentSenderForResult` of the CDM chooser. */
        const val REQUEST_CODE_CDM = 0x5713

        private const val SCAN_TIMEOUT_MS = 30_000L
    }
}
