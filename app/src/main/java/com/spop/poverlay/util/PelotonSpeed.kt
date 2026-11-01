package com.spop.poverlay.util

import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Peloton cycling speed in mph from reported power in watts.
 *
 * Uses the constants and double-precision expression recovered from PelotonBikeProd's
 * StatsReader (o.ndu.f) and bike metrics collector (o.gdi.invokeSuspend). Resistance
 * and cadence affect this value through measured power, not as separate inputs.
 * The transport converts mph to km/h when encoding FTMS data.
 */
fun calculateSpeedFromPelotonPower(power: Float): Float {
    // Preserve stationary behavior and keep invalid sensor samples out of FTMS speed.
    if (!power.isFinite() || power < 0.1f) return 0f

    val watts = power.toDouble()
    val root = (6641.0 * watts + 23.558 * sqrt(79463.2 * watts * watts + 4094004.4))
        .pow(1.0 / 3.0)
    return (0.1658 * root - 217.95 / root).toFloat()
}
