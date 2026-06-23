package com.bajaj.ns200.telemetry.data.model

data class TelemetryData(
    val speed: Float? = null,
    val rpm: Int? = null,
    val fuelLevelPercent: Float? = null,
    val gearPosition: Int? = null,
    val temperatureCelsius: Float? = null,
    val odometerKm: Float? = null,
    val batteryVoltage: Float? = null,
    val timestamp: Long = System.currentTimeMillis()
)
