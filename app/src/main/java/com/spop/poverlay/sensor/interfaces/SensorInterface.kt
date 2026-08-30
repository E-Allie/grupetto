package com.spop.poverlay.sensor.interfaces

import com.spop.poverlay.sensor.v2.TitanControl
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

    /**
     * The Titan controller's own watt-setpoint loop and status record, on the
     * hardware that has one. Null everywhere else, which is the first gate on
     * native ERG -- see [com.spop.poverlay.erg.PzafProbe] for the rest.
     */
    val titanControl: TitanControl?
        get() = null

    fun setResistance(resistance: Int) {} // No-op default; Bike+ overrides
}
