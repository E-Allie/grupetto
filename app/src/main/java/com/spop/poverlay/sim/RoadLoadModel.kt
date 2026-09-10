package com.spop.poverlay.sim

import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.sin

data class RoadLoad(
    val speedMps: Double,
    val gradeForceNewtons: Double,
    val rollingForceNewtons: Double,
    val aeroForceNewtons: Double,
    val watts: Double
)

/**
 * Quasi-static road load with Cw = air density * CdA (the trainer convention).
 * The 1/2 belongs in the force equation. See FTMS_SIM_MODE_PLAN.md §4 for the
 * interoperability assumption; neither air density nor difficulty is added again.
 * Negative watts are retained for the actuator's low-load policy.
 */
object RoadLoadModel {
    const val GRAVITY = 9.80665

    fun calculate(
        cadenceRpm: Double,
        gear: Int,
        terrain: SimulationParameters,
        settings: SimulationSettings
    ): RoadLoad {
        require(cadenceRpm.isFinite() && cadenceRpm in 0.0..300.0)
        require(gear in 1..GearRatios.COUNT)
        require(terrain.isValid)
        val speed = GearRatios.speedMps(cadenceRpm, gear, settings.gearing)
        val theta = atan(terrain.gradePercent / 100.0)
        val weight = settings.totalMassKg * GRAVITY
        val relativeWind = speed + terrain.windSpeedMps
        val grade = weight * sin(theta)
        val rolling = weight * terrain.crr * cos(theta)
        val aero = 0.5 * terrain.cw * relativeWind * abs(relativeWind)
        return RoadLoad(speed, grade, rolling, aero, speed * (grade + rolling + aero))
    }
}
