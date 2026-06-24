package com.bajaj.ns200.telemetry.ui

import android.Manifest
import android.animation.ArgbEvaluator
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.bluetooth.le.ScanResult
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.AnticipateOvershootInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
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
    private var gearAnimator: ValueAnimator? = null
    private var shimmerAnimator: ValueAnimator? = null
    private var rpmAnimator: ValueAnimator? = null
    private var fuelAnimator: ValueAnimator? = null
    private var entered = false
    private var lastGear: Int? = null
    private var lastRpm: Int? = null
    private var lastFuel: Int? = null
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
        animateEntrance()
    }

    private fun animateEntrance() {
        if (entered) return
        entered = true
        val cards = listOf(
            binding.cardStatus to 0L,
            binding.buttonScan to 80L,
            binding.cardDevices to 160L,
            binding.cardTelemetry to 240L
        )
        cards.forEach { (view, delay) ->
            view.alpha = 0f
            view.translationY = 60f
            view.postDelayed({
                view.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(500)
                    .setInterpolator(OvershootInterpolator(0.8f))
                    .start()
            }, delay)
        }
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

        val statusColor = if (state.isConnected) R.color.connected
        else if (state.isScanning) R.color.accent
        else R.color.disconnected

        binding.textBluetoothStatus.text = if (state.isBluetoothEnabled) "BT On" else "BT Off"
        binding.textBluetoothStatus.setTextColor(
            ContextCompat.getColor(this, if (state.isBluetoothEnabled) R.color.connected else R.color.disconnected)
        )

        val wasConnected = pulseAnimator?.isStarted == true
        if (state.isConnected) {
            binding.viewStatusDot.setBackgroundResource(R.drawable.circle_status_connected)
            if (!wasConnected) startPulseAnimation()
        } else {
            binding.viewStatusDot.setBackgroundResource(R.drawable.circle_status_disconnected)
            stopPulseAnimation()
        }

        ObjectAnimator.ofInt(
            binding.cardStatus, "cardBackgroundColor",
            ContextCompat.getColor(this, R.color.surface_card),
            ContextCompat.getColor(this, statusColor)
        ).apply {
            setEvaluator(ArgbEvaluator())
            duration = 400
            start()
        }

        animateVisibility(binding.buttonScan, !state.isScanning)
        animateVisibility(binding.buttonStopScan, state.isScanning)

        val showConnect = !state.isScanning && !state.isConnected && state.discoveredDevices.isNotEmpty()
        animateVisibility(binding.layoutConnectButtons, showConnect || state.isConnected)
        animateVisibility(binding.buttonConnect, showConnect)
        animateVisibility(binding.buttonDisconnect, state.isConnected)

        deviceAdapter.submitList(state.discoveredDevices)
        animateVisibility(binding.textNoDevices, state.discoveredDevices.isEmpty() && !state.isScanning)

        if (state.isScanning) startShimmer() else stopShimmer()

        val data = state.telemetryData
        val newSpeed = data.speed
        if (newSpeed != null) {
            animateSpeed(newSpeed)
        } else {
            speedAnimator?.cancel()
            binding.textSpeed.text = "--"
        }

        val rpm = data.rpm ?: 0
        binding.textRpm.text = data.rpm?.let { "$it RPM" } ?: "-- RPM"
        animateRpmProgress(rpm.coerceIn(0, 12000))

        val fuel = (data.fuelLevelPercent?.toInt() ?: 0).coerceIn(0, 100)
        animateFuelProgress(fuel)

        binding.textFuel.text = data.fuelLevelPercent?.let { "${it.toInt()}%" } ?: "--%"

        val currentGear = data.gearPosition
        if (currentGear != null && currentGear != lastGear) {
            animateGearChange(currentGear)
            lastGear = currentGear
        }
        binding.textGear.text = if (currentGear == 0) "N" else currentGear?.toString() ?: "--"

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

    private fun animateGearChange(@Suppress("UNUSED_PARAMETER") gear: Int) {
        gearAnimator?.cancel()
        binding.textGear.scaleX = 0.3f
        binding.textGear.scaleY = 0.3f
        binding.textGear.animate()
            .scaleX(1.2f)
            .scaleY(1.2f)
            .setDuration(200)
            .setInterpolator(AnticipateOvershootInterpolator())
            .withEndAction {
                binding.textGear.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(100)
                    .start()
            }
            .start()
    }

    private fun animateRpmProgress(target: Int) {
        if (lastRpm == target) return
        rpmAnimator?.cancel()
        rpmAnimator = ValueAnimator.ofInt(binding.progressRpm.progress, target).apply {
            duration = 400
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                binding.progressRpm.progress = anim.animatedValue as Int
            }
            start()
        }
        lastRpm = target
    }

    private fun animateFuelProgress(target: Int) {
        if (lastFuel == target) return
        fuelAnimator?.cancel()
        fuelAnimator = ValueAnimator.ofInt(binding.progressFuel.progress, target).apply {
            duration = 400
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                binding.progressFuel.progress = anim.animatedValue as Int
            }
            start()
        }
        lastFuel = target
    }

    private fun animateVisibility(view: View, visible: Boolean) {
        if (visible && view.visibility != View.VISIBLE) {
            view.alpha = 0f
            view.translationY = 20f
            view.visibility = View.VISIBLE
            view.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(350)
                .setInterpolator(DecelerateInterpolator())
                .start()
        } else if (!visible && view.visibility == View.VISIBLE) {
            view.animate()
                .alpha(0f)
                .translationY(-10f)
                .setDuration(200)
                .setInterpolator(AccelerateDecelerateInterpolator())
                .withEndAction { view.visibility = View.GONE }
                .start()
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
            duration = 600
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                val v = anim.animatedValue as Float
                binding.textSpeed.text = formatSpeed(v)
                val fraction = v / 160f
                val alpha = (0.3f + fraction * 0.7f).coerceIn(0.3f, 1f)
                binding.textSpeed.alpha = alpha
            }
            start()
        }
    }

    private fun startPulseAnimation() {
        pulseAnimator?.cancel()
        pulseAnimator = ObjectAnimator.ofFloat(binding.viewStatusDot, "scaleX", 1f, 1.5f).apply {
            duration = 700
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { anim ->
                val v = anim.animatedValue as Float
                binding.viewStatusDot.scaleY = v
                binding.viewStatusDot.alpha = 1f - (v - 1f) * 0.8f
            }
            start()
        }
    }

    private fun stopPulseAnimation() {
        pulseAnimator?.cancel()
        pulseAnimator = null
        binding.viewStatusDot.animate()
            .scaleX(1f).scaleY(1f).alpha(1f)
            .setDuration(200)
            .start()
    }

    private fun startShimmer() {
        if (shimmerAnimator?.isRunning == true) return
        val overlay = binding.root
        shimmerAnimator = ValueAnimator.ofFloat(-1f, 2f).apply {
            duration = 1500
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { anim ->
                val fraction = anim.animatedValue as Float
                overlay.elevation = 2f + fraction * 4f
            }
            start()
        }
    }

    private fun stopShimmer() {
        shimmerAnimator?.cancel()
        shimmerAnimator = null
        binding.root.elevation = 0f
    }

    private fun formatSpeed(speed: Float): String {
        return if (speed == speed.toInt().toFloat()) speed.toInt().toString() else "${"%.1f".format(speed)}"
    }

    override fun onDestroy() {
        pulseAnimator?.cancel()
        speedAnimator?.cancel()
        gearAnimator?.cancel()
        shimmerAnimator?.cancel()
        rpmAnimator?.cancel()
        fuelAnimator?.cancel()
        super.onDestroy()
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
