package com.bajaj.ns200.telemetry.config

import java.util.UUID

/**
 * =============================================================================
 * BLUETOOTH CONFIGURATION FOR BAJAJ NS200
 * =============================================================================
 *
 * CUSTOMIZATION GUIDE:
 *
 * Step 1: Connect to the NS200 and check Logcat for "Discovered Service:"
 *         entries. The app logs ALL services/characteristics automatically.
 *
 * Step 2: Look for vendor-specific UUIDs (not the standard 0000xxxx-... pattern)
 *         that likely contain telemetry data. Characteristics with NOTIFY or
 *         INDICATE properties are prime candidates.
 *
 * Step 3: Replace the placeholder NS200_* constants below with the actual UUIDs.
 *
 * Step 4: Adjust DataParser.kt to decode the byte array format matching the
 *         protocol discovered.
 */
object Ns200BluetoothConfig {

    // =========================================================================
    // STANDARD SERVICES (may work immediately with any BLE device)
    // =========================================================================

    /** Battery Service - reports device battery level (0-100%) */
    val BATTERY_SERVICE_UUID: UUID = UUID.fromString("0000180F-0000-1000-8000-00805F9B34FB")
    val BATTERY_LEVEL_CHAR_UUID: UUID = UUID.fromString("00002A19-0000-1000-8000-00805F9B34FB")

    /** Device Information Service - manufacturer, model, serial */
    val DEVICE_INFO_SERVICE_UUID: UUID = UUID.fromString("0000180A-0000-1000-8000-00805F9B34FB")
    val MANUFACTURER_NAME_CHAR_UUID: UUID = UUID.fromString("00002A29-0000-1000-8000-00805F9B34FB")
    val MODEL_NUMBER_CHAR_UUID: UUID = UUID.fromString("00002A24-0000-1000-8000-00805F9B34FB")

    // =========================================================================
    // NS200 PLACEHOLDER UUIDs
    // Replace these with actual UUIDs found during service discovery
    // =========================================================================

    val NS200_TELEMETRY_SERVICE_UUID: UUID = UUID.fromString("0000FFFF-0000-1000-8000-00805F9B34FB")
    val NS200_TELEMETRY_CHAR_UUID: UUID = UUID.fromString("0000FFF1-0000-1000-8000-00805F9B34FB")

    // =========================================================================
    // SCANNING BEHAVIOR
    // =========================================================================

    const val SCAN_PERIOD_MS: Long = 10_000L
    const val NS200_DEVICE_NAME_FILTER: String = ""
    const val USE_SCAN_FILTER: Boolean = false

    val SCAN_FILTER_UUIDS: List<UUID> = listOf(NS200_TELEMETRY_SERVICE_UUID)

    const val RECONNECT_DELAY_MS: Long = 5000L
}
