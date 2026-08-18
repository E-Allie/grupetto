package com.spop.poverlay.sensor.interfaces

import com.spop.poverlay.util.calculateSpeedFromPelotonV1Power
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

interface SensorInterface {
    val power: Flow<Float>
    val cadence: Flow<Float>
    val resistance: Flow<Float>
    val speed
        get() = power.map(::calculateSpeedFromPelotonV1Power)

    /**
     * Whether [setResistance] actually drives the brake on this hardware.
     *
     * The default [setResistance] is a no-op, so an interface that does not
     * override it cannot honour FTMS resistance or power targets. FTMS must not
     * advertise those capabilities on such a bike, or controller apps will send
     * targets that are silently ignored.
     */
    val supportsResistanceControl: Boolean
        get() = false

    fun setResistance(resistance: Int) {} // No-op default; Bike+ overrides
}
