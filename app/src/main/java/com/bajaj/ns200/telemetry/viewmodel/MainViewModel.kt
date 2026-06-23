package com.bajaj.ns200.telemetry.viewmodel

import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanResult
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bajaj.ns200.telemetry.config.Ns200BluetoothConfig
import com.bajaj.ns200.telemetry.data.bluetooth.BluetoothConnectionManager
import com.bajaj.ns200.telemetry.data.bluetooth.BluetoothScanner
import com.bajaj.ns200.telemetry.data.bluetooth.DataParser
import com.bajaj.ns200.telemetry.data.model.TelemetryData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class MainUiState(
    val isBluetoothEnabled: Boolean = false,
    val isScanning: Boolean = false,
    val discoveredDevices: List<ScanResult> = emptyList(),
    val isConnected: Boolean = false,
    val connectedDeviceAddress: String? = null,
    val connectionStatus: String = "Disconnected",
    val telemetryData: TelemetryData = TelemetryData(),
    val errorMessage: String? = null,
    val serviceDiscoveryLog: List<String> = emptyList()
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    companion object {
        private const val TAG = "MainViewModel"
    }

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        val mgr = getApplication<Application>()
            .getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        mgr?.adapter
    }

    private val scanner: BluetoothScanner by lazy {
        BluetoothScanner(bluetoothAdapter).apply {
            setListener(object : BluetoothScanner.ScanCallbackListener {
                override fun onDeviceDiscovered(device: ScanResult) {
                    val addr = device.device.address
                    val name = device.device.name ?: "Unknown"
                    if (Ns200BluetoothConfig.NS200_DEVICE_NAME_FILTER.isNotBlank() &&
                        !name.contains(Ns200BluetoothConfig.NS200_DEVICE_NAME_FILTER, ignoreCase = true)
                    ) return

                    discoveredDeviceMap[addr] = device
                    _uiState.value = _uiState.value.copy(
                        discoveredDevices = discoveredDeviceMap.values.toList()
                    )
                }

                override fun onScanStarted() {
                    _uiState.value = _uiState.value.copy(isScanning = true, errorMessage = null)
                }

                override fun onScanStopped() {
                    _uiState.value = _uiState.value.copy(isScanning = false)
                }

                override fun onScanError(code: Int) {
                    _uiState.value = _uiState.value.copy(
                        isScanning = false,
                        errorMessage = "Scan error (code $code)"
                    )
                }
            })
        }
    }

    private val connectionManager: BluetoothConnectionManager by lazy {
        BluetoothConnectionManager(getApplication(), bluetoothAdapter).apply {
            setCallback(object : BluetoothConnectionManager.ConnectionCallback {
                override fun onConnected() {
                    _uiState.value = _uiState.value.copy(
                        isConnected = true,
                        connectionStatus = "Connected to ${connectedDeviceAddress ?: "device"}",
                        errorMessage = null
                    )
                    subscribeToCharacteristics()
                }

                override fun onDisconnected() {
                    _uiState.value = _uiState.value.copy(
                        isConnected = false,
                        connectedDeviceAddress = null,
                        connectionStatus = "Disconnected",
                        telemetryData = TelemetryData()
                    )
                }

                override fun onServiceDiscovered(services: List<BluetoothGattService>) {
                    val logLines = mutableListOf<String>()
                    logLines.add("=== ${services.size} Services ===")
                    services.forEach { svc ->
                        logLines.add("${svc.uuid}")
                        svc.characteristics.forEach { char ->
                            val props = buildString {
                                if (hasProp(char, BluetoothGattCharacteristic.PROPERTY_READ)) append("R")
                                if (hasProp(char, BluetoothGattCharacteristic.PROPERTY_WRITE)) append("W")
                                if (hasProp(char, BluetoothGattCharacteristic.PROPERTY_NOTIFY)) append(" N")
                                if (hasProp(char, BluetoothGattCharacteristic.PROPERTY_INDICATE)) append(" I")
                            }
                            logLines.add("  ${char.uuid} [$props]")
                        }
                    }
                    Log.i(TAG, logLines.joinToString("\n"))
                    _uiState.value = _uiState.value.copy(serviceDiscoveryLog = logLines)
                }

                override fun onCharacteristicRead(char: BluetoothGattCharacteristic, value: ByteArray) {
                    handleData(char, value)
                }

                override fun onCharacteristicChanged(char: BluetoothGattCharacteristic, value: ByteArray) {
                    handleData(char, value)
                }

                override fun onConnectionError(code: Int) {
                    _uiState.value = _uiState.value.copy(errorMessage = "Connection error ($code)")
                }
            })
        }
    }

    private val discoveredDeviceMap = mutableMapOf<String, ScanResult>()

    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                val enabled = state == BluetoothAdapter.STATE_ON
                _uiState.value = _uiState.value.copy(isBluetoothEnabled = enabled)
                if (!enabled) {
                    scanner.stopScanning()
                    connectionManager.disconnect()
                    _uiState.value = _uiState.value.copy(
                        isScanning = false, isConnected = false,
                        discoveredDevices = emptyList(), connectionStatus = "Bluetooth disabled"
                    )
                }
            }
        }
    }

    init {
        getApplication<Application>().registerReceiver(
            bluetoothStateReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        )
        _uiState.value = _uiState.value.copy(
            isBluetoothEnabled = bluetoothAdapter?.isEnabled == true
        )
    }

    fun startScanning() {
        if (bluetoothAdapter?.isEnabled != true) {
            _uiState.value = _uiState.value.copy(errorMessage = "Enable Bluetooth first")
            return
        }
        discoveredDeviceMap.clear()
        _uiState.value = _uiState.value.copy(discoveredDevices = emptyList(), errorMessage = null)
        scanner.configureFilters()
        scanner.startScanning()
    }

    fun stopScanning() = scanner.stopScanning()

    fun connectToDevice(address: String) {
        if (bluetoothAdapter?.isEnabled != true) {
            _uiState.value = _uiState.value.copy(errorMessage = "Bluetooth is disabled")
            return
        }
        _uiState.value = _uiState.value.copy(
            connectionStatus = "Connecting to $address...", errorMessage = null
        )
        connectionManager.connect(address)
    }

    fun disconnectFromDevice() {
        connectionManager.disconnect()
        _uiState.value = _uiState.value.copy(
            isConnected = false, connectedDeviceAddress = null,
            connectionStatus = "Disconnected", telemetryData = TelemetryData()
        )
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }

    private fun subscribeToCharacteristics() {
        viewModelScope.launch(Dispatchers.IO) {
            connectionManager.enableNotifications(
                Ns200BluetoothConfig.BATTERY_SERVICE_UUID,
                Ns200BluetoothConfig.BATTERY_LEVEL_CHAR_UUID
            )
            connectionManager.readCharacteristic(
                Ns200BluetoothConfig.BATTERY_SERVICE_UUID,
                Ns200BluetoothConfig.BATTERY_LEVEL_CHAR_UUID
            )
            connectionManager.readCharacteristic(
                Ns200BluetoothConfig.DEVICE_INFO_SERVICE_UUID,
                Ns200BluetoothConfig.MANUFACTURER_NAME_CHAR_UUID
            )
            connectionManager.readCharacteristic(
                Ns200BluetoothConfig.DEVICE_INFO_SERVICE_UUID,
                Ns200BluetoothConfig.MODEL_NUMBER_CHAR_UUID
            )
        }
    }

    private fun handleData(char: BluetoothGattCharacteristic, value: ByteArray) {
        val parsed = DataParser.parse(char, value)
        val cur = _uiState.value.telemetryData
        _uiState.value = _uiState.value.copy(
            telemetryData = TelemetryData(
                speed = parsed.speed ?: cur.speed,
                rpm = parsed.rpm ?: cur.rpm,
                fuelLevelPercent = parsed.fuelLevelPercent ?: cur.fuelLevelPercent,
                gearPosition = parsed.gearPosition ?: cur.gearPosition,
                temperatureCelsius = parsed.temperatureCelsius ?: cur.temperatureCelsius,
                odometerKm = parsed.odometerKm ?: cur.odometerKm,
                batteryVoltage = parsed.batteryVoltage ?: cur.batteryVoltage,
                timestamp = System.currentTimeMillis()
            )
        )
    }

    private fun hasProp(char: BluetoothGattCharacteristic, prop: Int): Boolean =
        char.properties and prop != 0

    override fun onCleared() {
        scanner.stopScanning()
        connectionManager.disconnect()
        getApplication<Application>().unregisterReceiver(bluetoothStateReceiver)
        super.onCleared()
    }
}
