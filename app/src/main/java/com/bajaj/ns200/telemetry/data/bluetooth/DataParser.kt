package com.bajaj.ns200.telemetry.data.bluetooth

import android.bluetooth.BluetoothGattCharacteristic
import android.util.Log
import com.bajaj.ns200.telemetry.config.Ns200BluetoothConfig
import com.bajaj.ns200.telemetry.data.model.TelemetryData
import java.nio.ByteOrder

/**
 * Parses NS200 BLE telemetry data from characteristic values.
 * Configure parseNs200Frame() to match the actual device protocol.
 */
object DataParser {
    private const val TAG = "DataParser"

    fun parse(
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray
    ): TelemetryData {
        return when (characteristic.uuid) {
            Ns200BluetoothConfig.BATTERY_LEVEL_CHAR_UUID -> parseBatteryLevel(value)
            Ns200BluetoothConfig.NS200_TELEMETRY_CHAR_UUID -> parseNs200Frame(value)
            else -> {
                Log.d(TAG, "Unknown char ${characteristic.uuid}: ${bytesToHex(value)}")
                TelemetryData()
            }
        }
    }

    private fun parseBatteryLevel(value: ByteArray): TelemetryData {
        if (value.isEmpty()) return TelemetryData()
        val pct = value[0].toInt() and 0xFF
        Log.d(TAG, "Battery level: $pct%")
        return TelemetryData(batteryVoltage = pct.toFloat() / 100f * 12.6f)
    }

    private fun parseNs200Frame(value: ByteArray): TelemetryData {
        if (value.isEmpty()) return TelemetryData()
        Log.i(TAG, "NS200 frame: ${bytesToHex(value)}")

        val buf = java.nio.ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN)
        return TelemetryData(
            speed = buf.getShort(0).toFloat() / 10f,
            rpm = buf.getShort(2).toInt(),
            fuelLevelPercent = (value[4].toInt() and 0xFF).toFloat() * 100f / 255f,
            gearPosition = value[5].toInt() and 0xFF,
            temperatureCelsius = (value[6].toInt() and 0xFF).toFloat(),
            odometerKm = buf.getInt(8) / 1000f,
            batteryVoltage = buf.getShort(12).toFloat() / 100f
        )
    }

    private fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02X".format(it) }
}
