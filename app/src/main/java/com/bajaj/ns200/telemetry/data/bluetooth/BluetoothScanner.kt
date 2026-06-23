package com.bajaj.ns200.telemetry.data.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.bajaj.ns200.telemetry.config.Ns200BluetoothConfig

@SuppressLint("MissingPermission")
class BluetoothScanner(
    private val bluetoothAdapter: BluetoothAdapter?
) {
    private val bluetoothLeScanner: BluetoothLeScanner? = bluetoothAdapter?.bluetoothLeScanner
    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    var isScanning: Boolean = false
        private set

    interface ScanCallbackListener {
        fun onDeviceDiscovered(device: ScanResult)
        fun onScanStarted()
        fun onScanStopped()
        fun onScanError(errorCode: Int)
    }

    private var callbackListener: ScanCallbackListener? = null
    private var scanFilters: List<ScanFilter> = emptyList()

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            callbackListener?.onDeviceDiscovered(result)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { callbackListener?.onDeviceDiscovered(it) }
        }

        override fun onScanFailed(errorCode: Int) {
            isScanning = false
            callbackListener?.onScanError(errorCode)
        }
    }

    fun setListener(listener: ScanCallbackListener) {
        this.callbackListener = listener
    }

    fun configureFilters() {
        scanFilters = if (Ns200BluetoothConfig.USE_SCAN_FILTER) {
            Ns200BluetoothConfig.SCAN_FILTER_UUIDS.map { serviceUuid ->
                ScanFilter.Builder()
                    .setServiceUuid(android.os.ParcelUuid(serviceUuid))
                    .build()
            }
        } else {
            emptyList()
        }
    }

    fun startScanning() {
        if (isScanning || bluetoothLeScanner == null) return

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                }
            }
            .build()

        bluetoothLeScanner.startScan(scanFilters, settings, scanCallback)
        isScanning = true
        callbackListener?.onScanStarted()

        handler.postDelayed({
            if (isScanning) stopScanning()
        }, Ns200BluetoothConfig.SCAN_PERIOD_MS)
    }

    fun stopScanning() {
        if (!isScanning) return

        bluetoothLeScanner?.stopScan(scanCallback)
        isScanning = false
        handler.removeCallbacksAndMessages(null)
        callbackListener?.onScanStopped()
    }
}
