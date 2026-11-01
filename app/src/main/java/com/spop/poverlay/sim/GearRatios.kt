package com.spop.poverlay.sim

import kotlin.math.pow

enum class GearProfile(val label: String, val lowestRatio: Double) {
    Climbing("Climbing", 0.40),
    Road("Road", 1.21)
}

/** Pure gearing math shared by the overlay and road-load model. */
object GearRatios {
    const val WHEEL_CIRCUMFERENCE_M = 2.105
    const val COUNT = 24
    const val DEFAULT = 12

    fun ratioFor(gear: Int, profile: GearProfile = GearProfile.Climbing): Double =
        profile.lowestRatio * (4.55 / profile.lowestRatio).pow((gear.coerceIn(1, COUNT) - 1).toDouble() / (COUNT - 1))

    fun speedMps(cadenceRpm: Double, gear: Int, profile: GearProfile = GearProfile.Climbing): Double {
        require(cadenceRpm.isFinite())
        return cadenceRpm.coerceAtLeast(0.0) / 60.0 * ratioFor(gear, profile) * WHEEL_CIRCUMFERENCE_M
    }
}
