package com.bajaj.ns200.telemetry.ui

import android.Manifest
import android.bluetooth.le.ScanResult
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.bajaj.ns200.telemetry.R
import com.bajaj.ns200.telemetry.databinding.ActivityMainBinding
import com.bajaj.ns200.telemetry.viewmodel.MainUiState
import com.bajaj.ns200.telemetry.viewmodel.MainViewModel
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var viewModel: MainViewModel
    private lateinit var deviceAdapter: DeviceAdapter

    private val requiredPermissions: Array<String> by lazy {
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION
            )
            else -> emptyArray()
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.values.all { it }) {
            viewModel.startScanning()
        } else {
            Toast.makeText(this, "Bluetooth permissions required", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        viewModel = ViewModelProvider(this)[MainViewModel::class.java]
        setupViews()
        setupRecyclerView()
        observeViewModel()
    }

    private fun setupViews() {
        binding.buttonScan.setOnClickListener { checkPermissionsAndScan(true) }
        binding.buttonStopScan.setOnClickListener { viewModel.stopScanning() }
        binding.buttonConnect.setOnClickListener {
            val device = deviceAdapter.getSelectedDevice()
            if (device != null) viewModel.connectToDevice(device.device.address)
            else Toast.makeText(this, "Select a device first", Toast.LENGTH_SHORT).show()
        }
        binding.buttonDisconnect.setOnClickListener { viewModel.disconnectFromDevice() }
    }

    private fun setupRecyclerView() {
        deviceAdapter = DeviceAdapter {
            binding.buttonConnect.text = "Connect to ${it.device.name ?: "Unknown"}"
            binding.buttonConnect.visibility = View.VISIBLE
        }
        binding.recyclerDevices.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = deviceAdapter
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state -> updateUi(state) }
            }
        }
    }

    private fun updateUi(state: MainUiState) {
        binding.textConnectionStatus.text = state.connectionStatus
        binding.textBluetoothStatus.text = if (state.isBluetoothEnabled) "BT On" else "BT Off"
        binding.textBluetoothStatus.setTextColor(
            if (state.isBluetoothEnabled) ContextCompat.getColor(this, R.color.connected)
            else ContextCompat.getColor(this, R.color.disconnected)
        )
        binding.viewStatusDot.setBackgroundResource(
            if (state.isConnected) R.drawable.circle_status_connected
            else R.drawable.circle_status_disconnected
        )

        binding.buttonScan.visibility = if (state.isScanning) View.GONE else View.VISIBLE
        binding.buttonStopScan.visibility = if (state.isScanning) View.VISIBLE else View.GONE

        val showConnect = !state.isScanning && !state.isConnected && state.discoveredDevices.isNotEmpty()
        binding.layoutConnectButtons.visibility = if (showConnect || state.isConnected) View.VISIBLE else View.GONE
        binding.buttonConnect.visibility = if (showConnect) View.VISIBLE else View.GONE
        binding.buttonDisconnect.visibility = if (state.isConnected) View.VISIBLE else View.GONE

        deviceAdapter.submitList(state.discoveredDevices)
        binding.textNoDevices.visibility =
            if (state.discoveredDevices.isEmpty() && !state.isScanning) View.VISIBLE else View.GONE

        val data = state.telemetryData
        binding.textSpeed.text = data.speed?.let { formatSpeed(it) } ?: "--"
        binding.textRpm.text = data.rpm?.let { "$it RPM" } ?: "-- RPM"
        binding.textFuel.text = data.fuelLevelPercent?.let { "${it.toInt()}%" } ?: "--%"
        binding.textGear.text = data.gearPosition?.let { if (it == 0) "N" else it.toString() } ?: "--"
        binding.textTemp.text = data.temperatureCelsius?.let { "${it.toInt()}°C" } ?: "--°C"
        binding.textOdometer.text = data.odometerKm?.let { "${"%.1f".format(it)} km" } ?: "-- km"
        binding.textBatteryVoltage.text = data.batteryVoltage?.let { "${"%.1f".format(it)} V" } ?: "-- V"

        state.errorMessage?.let {
            Toast.makeText(this, it, Toast.LENGTH_LONG).show()
            viewModel.clearError()
        }

        if (state.serviceDiscoveryLog.isNotEmpty()) {
            binding.textDiscoveryLog.text = state.serviceDiscoveryLog.joinToString("\n")
            binding.cardDiscoveryLog.visibility = View.VISIBLE
        }
    }

    private fun formatSpeed(speed: Float): String {
        return if (speed == speed.toInt().toFloat()) speed.toInt().toString() else "${"%.1f".format(speed)}"
    }

    private fun checkPermissionsAndScan(shouldScan: Boolean) {
        val missing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        } else if (shouldScan) {
            viewModel.startScanning()
        }
    }
}
