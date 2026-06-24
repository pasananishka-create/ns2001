package com.bajaj.ns200.telemetry.ui

import android.Manifest
import android.animation.ArgbEvaluator
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.bluetooth.le.ScanResult
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.ProgressBar
import android.widget.TextView
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
    private var pulseAnimator: ObjectAnimator? = null
    private var speedAnimator: ValueAnimator? = null
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

        val wasConnected = pulseAnimator?.isStarted == true
        if (state.isConnected) {
            binding.viewStatusDot.setBackgroundResource(R.drawable.circle_status_connected)
            if (!wasConnected) startPulseAnimation()
        } else {
            binding.viewStatusDot.setBackgroundResource(R.drawable.circle_status_disconnected)
            stopPulseAnimation()
        }

        animateVisibility(binding.buttonScan, !state.isScanning)
        animateVisibility(binding.buttonStopScan, state.isScanning)

        val showConnect = !state.isScanning && !state.isConnected && state.discoveredDevices.isNotEmpty()
        animateVisibility(binding.layoutConnectButtons, showConnect || state.isConnected)
        animateVisibility(binding.buttonConnect, showConnect)
        animateVisibility(binding.buttonDisconnect, state.isConnected)

        deviceAdapter.submitList(state.discoveredDevices)
        animateVisibility(binding.textNoDevices, state.discoveredDevices.isEmpty() && !state.isScanning)

        val data = state.telemetryData
        val newSpeed = data.speed
        if (newSpeed != null) {
            animateSpeed(newSpeed)
        } else {
            binding.textSpeed.text = "--"
        }

        val rpm = data.rpm
        binding.textRpm.text = rpm?.let { "$it RPM" } ?: "-- RPM"
        binding.progressRpm.progress = (rpm ?: 0).coerceIn(0, 12000)

        val fuel = data.fuelLevelPercent
        binding.textFuel.text = fuel?.let { "${it.toInt()}%" } ?: "--%"
        binding.progressFuel.progress = (fuel?.toInt() ?: 0).coerceIn(0, 100)

        binding.textGear.text = data.gearPosition?.let { if (it == 0) "N" else it.toString() } ?: "--"
        binding.textTemp.text = data.temperatureCelsius?.let { "${it.toInt()}°C" } ?: "--°C"
        binding.textOdometer.text = data.odometerKm?.let { "${"%.1f".format(it)} km" } ?: "-- km"

        val volt = data.batteryVoltage
        binding.textBatteryVoltage.text = volt?.let { "${"%.1f".format(it)} V" } ?: "-- V"
        binding.imageBattery.setColorFilter(
            when {
                volt == null -> ContextCompat.getColor(this, R.color.disconnected)
                volt >= 12.0f -> ContextCompat.getColor(this, R.color.connected)
                volt >= 11.5f -> Color.rgb(255, 183, 0)
                else -> ContextCompat.getColor(this, R.color.speed_color)
            }
        )

        state.errorMessage?.let {
            Toast.makeText(this, it, Toast.LENGTH_LONG).show()
            viewModel.clearError()
        }

        if (state.serviceDiscoveryLog.isNotEmpty()) {
            binding.textDiscoveryLog.text = state.serviceDiscoveryLog.joinToString("\n")
            animateVisibility(binding.cardDiscoveryLog, true)
        }
    }

    private fun animateVisibility(view: View, visible: Boolean) {
        if (visible && view.visibility != View.VISIBLE) {
            view.alpha = 0f
            view.visibility = View.VISIBLE
            view.animate().alpha(1f).setDuration(300).setInterpolator(DecelerateInterpolator()).start()
        } else if (!visible && view.visibility == View.VISIBLE) {
            view.animate().alpha(0f).setDuration(200).setInterpolator(AccelerateDecelerateInterpolator())
                .withEndAction { view.visibility = View.GONE }.start()
        }
    }

    private fun animateSpeed(targetSpeed: Float) {
        val currentText = binding.textSpeed.text.toString()
        val currentSpeed = currentText.toFloatOrNull() ?: 0f
        if (kotlin.math.abs(currentSpeed - targetSpeed) < 0.5f) {
            binding.textSpeed.text = formatSpeed(targetSpeed)
            return
        }
        speedAnimator?.cancel()
        speedAnimator = ValueAnimator.ofFloat(currentSpeed, targetSpeed).apply {
            duration = 500
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                binding.textSpeed.text = formatSpeed(anim.animatedValue as Float)
            }
            start()
        }
    }

    private fun startPulseAnimation() {
        pulseAnimator?.cancel()
        pulseAnimator = ObjectAnimator.ofFloat(binding.viewStatusDot, "alpha", 1f, 0.4f).apply {
            duration = 800
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
    }

    private fun stopPulseAnimation() {
        pulseAnimator?.cancel()
        pulseAnimator = null
        binding.viewStatusDot.alpha = 1f
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
