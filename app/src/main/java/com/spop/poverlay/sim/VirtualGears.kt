package com.spop.poverlay.sim

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import kotlin.math.pow

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
 * Deriving speed this way is also the only safe option. The obvious alternative
 * -- reusing the existing power-derived speed estimate -- closes a positive
 * feedback loop, because resistance would then raise power, power would raise
 * speed, and speed would raise the power demand again. Cadence is measured and
 * independent of the brake, so it breaks that loop.
 *
 * Nothing consumes this yet. The shifter moves the gear and the ride recorder
 * logs it; no resistance follows from it until sim mode is implemented.
 */
object VirtualGears {

    /** 700x25c, the same size a game assumes by default. */
    const val WHEEL_CIRCUMFERENCE_M = 2.105

    /**
     * Ratios span a compact road setup, 34x28 up to 50x11. At 85 rpm that is
     * about 13 km/h in the lowest gear and 49 km/h in the highest, which covers
     * the range a rider meets on a course.
     */
    private const val LOWEST_RATIO = 1.21
    private const val HIGHEST_RATIO = 4.55

    /** Even spacing in ratio terms works out at roughly 6% a shift. */
    const val GEAR_COUNT = 24
    const val DEFAULT_GEAR = 12

    private val mutableGear = MutableStateFlow(DEFAULT_GEAR)

    /** Currently selected gear, 1..[GEAR_COUNT]. */
    val gear: StateFlow<Int> = mutableGear.asStateFlow()

    /** Listener for gear changes, so a recorder can log them. */
    @Volatile
    var onGearChanged: ((gear: Int, ratio: Double) -> Unit)? = null

    /** Ratio of the currently selected gear. */
    val ratio: Double
        get() = ratioFor(mutableGear.value)

    fun ratioFor(gear: Int): Double {
        val step = (HIGHEST_RATIO / LOWEST_RATIO).pow(1.0 / (GEAR_COUNT - 1))
        return LOWEST_RATIO * step.pow((gear - 1).coerceIn(0, GEAR_COUNT - 1).toDouble())
    }

    /**
     * Road speed in m/s the selected gear implies at this cadence. This is the
     * quantity sim mode would feed into the power equation.
     */
    fun virtualSpeedMps(cadenceRpm: Double, gear: Int = mutableGear.value): Double =
        cadenceRpm / 60.0 * ratioFor(gear) * WHEEL_CIRCUMFERENCE_M

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
