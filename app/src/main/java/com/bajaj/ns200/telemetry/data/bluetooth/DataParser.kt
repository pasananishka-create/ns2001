package com.bajaj.ns200.telemetry.data.bluetooth

import android.bluetooth.BluetoothGattCharacteristic
import android.util.Log
import com.bajaj.ns200.telemetry.config.Ns200BluetoothConfig
import com.bajaj.ns200.telemetry.data.model.TelemetryData

/**
 * =============================================================================
 * DATA PARSER FOR NS200 TELEMETRY
 * =============================================================================
 *
 * CUSTOMIZATION GUIDE:
 *
 * Once you identify the NS200's data format from Logcat:
 *
 * 1. Look at the hex dump of the characteristic value
 * 2. Determine the byte layout. Common patterns:
 *    - 16-bit little-endian ints: bytes 0-1 = speed, 2-3 = RPM, etc.
 *    - 8-bit values: single byte per metric
 *    - Structured frame: header + data + checksum
 * 3. Modify parseNs200Frame() to decode the actual format
 *
 * The commented-out code in parseNs200Frame shows common patterns.
 * Uncomment and adjust as needed.
 */
object DataParser {
    private const val TAG = "DataParser"

    fun parse(
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray
    ): TelemetryData {
        return when (characteristic.uuid) {
            Ns200BluetoothConfig.BATTERY_LEVEL_CHAR_UUID -> parseBatteryLevel(value)
//            Ns200BluetoothConfig.NS200_TELEMETRY_CHAR_UUID -> parseNs200Frame(value)
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

    @Suppress("UNUSED_PARAMETER")
    private fun parseNs200Frame(value: ByteArray): TelemetryData {
        if (value.isEmpty()) return TelemetryData()
        Log.i(TAG, "NS200 frame: ${bytesToHex(value)}")

        // ---- FORMAT EXAMPLE: 16-bit little-endian sequential ----
        // val buf = java.nio.ByteBuffer.wrap(value).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        // return TelemetryData(
        //     speed = buf.getShort(0).toFloat() / 10f,
        //     rpm = buf.getShort(2).toInt(),
        //     fuelLevelPercent = (value[4].toInt() and 0xFF).toFloat() * 100f / 255f,
        //     gearPosition = value[5].toInt() and 0xFF,
        //     temperatureCelsius = (value[6].toInt() and 0xFF).toFloat(),
        //     odometerKm = buf.getInt(8) / 1000f,
        //     batteryVoltage = buf.getShort(12).toFloat() / 100f
        // )

        return TelemetryData()
    }

    private fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02X".format(it) }
}
