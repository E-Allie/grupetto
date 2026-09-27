package com.spop.poverlay.ble

import kotlin.math.roundToInt

/** Shared Indoor Bike Data payload for BLE and DirCon. */
internal object FitnessMachineData {
    private const val MAX_RESISTANCE_PERCENT = 100
    private const val CONTROL_UNITS_PER_PERCENT = 10
    private const val MAX_CONTROL_RESISTANCE = MAX_RESISTANCE_PERCENT * CONTROL_UNITS_PER_PERCENT

    // The settable range uses the same tenths-of-a-percent units as Control Point
    // targets. Indoor Bike Data retains whole percentages for existing clients.
    fun supportedResistanceRange(): ByteArray = byteArrayOf(
        0x00, 0x00, // minimum: 0%
        (MAX_CONTROL_RESISTANCE and 0xFF).toByte(), (MAX_CONTROL_RESISTANCE shr 8).toByte(),
        CONTROL_UNITS_PER_PERCENT.toByte(), 0x00 // increment: 1%
    )

    fun encode(cadence: Float, power: Float, speed: Float, resistance: Float): ByteArray {
        // More Data is clear, so instantaneous speed is included.
        val flags = FitnessMachineConstants.IndoorBikeDataFlags.InstantaneousCadencePresent or
            FitnessMachineConstants.IndoorBikeDataFlags.InstantaneousPowerPresent or
            FitnessMachineConstants.IndoorBikeDataFlags.ResistanceLevelPresent
        val speedValue = (speed * 1.60934f * 100).toInt() // mph -> 0.01 km/h
        val cadenceValue = (cadence * 2).toInt()
        val powerValue = power.toInt()
        // Preserve the existing whole-level, 16-bit wire format. Round rather than
        // truncate: 99.99999 must encode as 100. Zero is also used for no sensor data.
        val resistanceValue = if (resistance.isFinite()) {
            resistance.coerceIn(0f, MAX_RESISTANCE_PERCENT.toFloat()).roundToInt()
        } else {
            0
        }

        return byteArrayOf(
            (flags and 0xFF).toByte(),
            (flags shr 8 and 0xFF).toByte(),
            (speedValue and 0xFF).toByte(),
            (speedValue shr 8 and 0xFF).toByte(),
            (cadenceValue and 0xFF).toByte(),
            (cadenceValue shr 8 and 0xFF).toByte(),
            (resistanceValue and 0xFF).toByte(),
            (resistanceValue shr 8 and 0xFF).toByte(),
            (powerValue and 0xFF).toByte(),
            (powerValue shr 8 and 0xFF).toByte()
        )
    }
}
