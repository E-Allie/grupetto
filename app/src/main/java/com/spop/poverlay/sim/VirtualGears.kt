package com.spop.poverlay.sim

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber

/**
 * The gear the rider has selected, and the ratio it implies.
 *
 * A Peloton is a fixed-gear direct-drive machine: cadence is flywheel speed,
 * there is one ratio, and no road speed exists anywhere in the system. FTMS sim
 * mode needs a road speed to turn a gradient into a power demand, so one has to
 * be manufactured:
 *
 *     v = cadence / 60 * ratio * wheelCircumference
 *
 * That makes the ratio a mandatory term in gradient control rather than a
 * feature layered on top of it, and it is what makes shifting possible at all:
 * the ratio is simply a number this object owns.
 *
 * Reusing the existing power-derived speed estimate would close a positive
 * feedback loop, because resistance would then raise power, power would raise
 * speed, and speed would raise the power demand again. Measured cadence avoids
 * that direct computational loop, although the rider's cadence responds to load.
 *
 * The simulation controller consumes the selected gear; explicit ERG targets
 * remain independent of gearing.
 */
object VirtualGears {

    /** 700x25c, the same size a game assumes by default. */
    const val WHEEL_CIRCUMFERENCE_M = GearRatios.WHEEL_CIRCUMFERENCE_M

    /** Each profile has 24 evenly spaced ratios on a logarithmic scale. */
    const val GEAR_COUNT = GearRatios.COUNT
    const val DEFAULT_GEAR = GearRatios.DEFAULT

    private val mutableGear = MutableStateFlow(DEFAULT_GEAR)
    private val mutableProfile = MutableStateFlow(GearProfile.Climbing)
    val profile: StateFlow<GearProfile> = mutableProfile.asStateFlow()

    fun setProfile(profile: GearProfile) { mutableProfile.value = profile }

    /** Currently selected gear, 1..[GEAR_COUNT]. */
    val gear: StateFlow<Int> = mutableGear.asStateFlow()

    /** Listener for gear changes, so a recorder can log them. */
    @Volatile
    var onGearChanged: ((gear: Int, ratio: Double) -> Unit)? = null

    /** Ratio of the currently selected gear. */
    val ratio: Double
        get() = ratioFor(mutableGear.value)

    fun ratioFor(gear: Int): Double = GearRatios.ratioFor(gear, profile.value)

    /**
     * Road speed in m/s the selected gear implies at this cadence. This is the
     * quantity sim mode would feed into the power equation.
     */
    fun virtualSpeedMps(cadenceRpm: Double, gear: Int = mutableGear.value): Double =
        GearRatios.speedMps(cadenceRpm, gear, profile.value)

    fun shiftUp() = shiftTo(mutableGear.value + 1)

    fun shiftDown() = shiftTo(mutableGear.value - 1)

    fun shiftTo(gear: Int) {
        val clamped = gear.coerceIn(1, GEAR_COUNT)
        if (clamped == mutableGear.value) return
        mutableGear.value = clamped
        val newRatio = ratioFor(clamped)
        Timber.d("Virtual gear %d/%d, ratio %.2f", clamped, GEAR_COUNT, newRatio)
        onGearChanged?.invoke(clamped, newRatio)
    }
}
