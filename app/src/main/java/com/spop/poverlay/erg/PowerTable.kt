package com.spop.poverlay.erg

/**
 * Feed-forward map of the Bike+ eddy brake, backed by the generated
 * [PowerTableData].
 *
 * A fixed PID gain cannot hold power well across the whole range of this brake:
 * near the loose end a resistance point is worth only a few watts, and near the
 * tight end it is worth many. Two things fix that, and both need a measured map:
 *
 *  - [resistanceFor] gives the controller somewhere to jump to when the target
 *    changes, instead of walking there one PID iteration at a time.
 *  - [resistancePerWatt] gives the local slope of the map, so the proportional
 *    gain can be scheduled to whatever the brake is worth at this operating
 *    point rather than assumed constant.
 *
 * The table is measured in the direction hardware can be observed --
 * (cadence, resistance) -> watts -- and inverted at fit time into the direction
 * asked for here. See power-sweep.py in the repository root.
 *
 * Every accessor returns null when the table cannot answer, and callers are
 * expected to fall back to unassisted PID. That is also the state the shipped
 * placeholder data is in, so this class is inert until a sweep has been run.
 */
object PowerTable {

    /** Half-width of the interval used to measure the local slope, in watts. */
    private const val SLOPE_SAMPLE_WATTS = 25

    val isCalibrated: Boolean
        get() = PowerTableData.CALIBRATED

    /**
     * Resistance percent expected to produce [targetWatts] at [cadenceRpm], or
     * null where the table has no usable value.
     */
    fun resistanceFor(targetWatts: Int, cadenceRpm: Double): Double? {
        if (!PowerTableData.CALIBRATED) return null

        val cadencePosition = axisPosition(
            cadenceRpm,
            PowerTableData.CADENCE_BIN_BASE,
            PowerTableData.CADENCE_BIN_STEP,
            PowerTableData.CADENCE_BIN_COUNT
        )
        val lowerRow = cadencePosition.toInt()
        val upperRow = minOf(lowerRow + 1, PowerTableData.CADENCE_BIN_COUNT - 1)

        val lower = interpolateWatts(lowerRow, targetWatts)
        val upper = interpolateWatts(upperRow, targetWatts)

        // A cadence band with no samples leaves a row entirely empty. Rather than
        // refuse the lookup, lean on whichever neighbouring row does have data.
        return when {
            lower != null && upper != null ->
                lower + (upper - lower) * (cadencePosition - lowerRow)
            else -> lower ?: upper
        }
    }

    /**
     * Local slope of the map in resistance percent per watt, or null when either
     * side of the sampling interval is missing.
     *
     * This is the quantity a proportional gain should be proportional to.
     */
    fun resistancePerWatt(targetWatts: Int, cadenceRpm: Double): Double? {
        val lowWatts = (targetWatts - SLOPE_SAMPLE_WATTS).coerceAtLeast(1)
        val highWatts = targetWatts + SLOPE_SAMPLE_WATTS
        if (highWatts <= lowWatts) return null

        val low = resistanceFor(lowWatts, cadenceRpm) ?: return null
        val high = resistanceFor(highWatts, cadenceRpm) ?: return null
        val slope = (high - low) / (highWatts - lowWatts)

        // More resistance must mean more watts. A non-positive slope means the
        // fit is bad here, and scheduling a gain from it would drive the loop
        // the wrong way.
        return if (slope > 0.0) slope else null
    }

    /** Interpolate one cadence row along the watt axis. */
    private fun interpolateWatts(row: Int, targetWatts: Int): Double? {
        val position = axisPosition(
            targetWatts.toDouble(),
            PowerTableData.WATT_BIN_BASE,
            PowerTableData.WATT_BIN_STEP,
            PowerTableData.WATT_BIN_COUNT
        )
        val lowerColumn = position.toInt()
        val upperColumn = minOf(lowerColumn + 1, PowerTableData.WATT_BIN_COUNT - 1)

        val lower = cell(row, lowerColumn)
        val upper = cell(row, upperColumn)
        return when {
            lower != null && upper != null ->
                lower + (upper - lower) * (position - lowerColumn)
            else -> lower ?: upper
        }
    }

    private fun cell(row: Int, column: Int): Double? {
        val value = PowerTableData.RESISTANCE[row][column]
        return if (value.isNaN()) null else value
    }

    /** Fractional index into an evenly spaced axis, clamped to its ends. */
    private fun axisPosition(value: Double, base: Int, step: Int, count: Int): Double {
        val raw = (value - base) / step
        return raw.coerceIn(0.0, (count - 1).toDouble())
    }
}
