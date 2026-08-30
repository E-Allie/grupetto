package com.spop.poverlay.erg

/**
 * Feed-forward map of the Bike+ eddy brake: (cadence, target watts) -> resistance.
 *
 * Derived from the Titan controller's own feed-forward surface rather than
 * from riding the bike. The firmware carries `pzaf_resistance_lookup_table` at
 * 0x0801D0A4 in Titan SC 5.08.0 -- 609 float32 holding crank torque over 29
 * cadence rows (5..145 rpm) by 21 resistance columns (0..100%) -- and its own
 * PZAF loop inverts it to pick a resistance. This is the same inversion done
 * ahead of time: P = T * 2*pi*rpm/60, then linear interpolation between the
 * resistance columns bracketing each target.
 *
 * Cross-checked against 48 samples swept from this bike on 2026-08-19: 73
 * overlapping cells agreed to a mean of 1.32 resistance points, worst case
 * 2.8. Two independent derivations of one brake, so the sweep is kept as the
 * validation set rather than the source. The firmware surface wins as the
 * source because it has 29 real cadence rows where the sweep had four, and
 * needs no extrapolation to fill the rest.
 *
 * [NO_DATA] is a physical limit, not missing data: below the first cell of a
 * row the brake cannot go loose enough to make so little power at that
 * cadence, and past the last it cannot go tight enough to make so much.
 * Callers fall back to unassisted PID there.
 *
 * Firmware SHA-256 85968f9cf9551e048fec05cfb98d16b0178134862e44589fd94a6de334243480.
 * Do not hand-edit; regenerate with generate-power-table.awk.
 */
object PowerTableData {

    /** Flipped to true once real data has replaced the placeholder values. */
    const val CALIBRATED = true

    val NO_DATA = Double.NaN

    const val CADENCE_BIN_BASE = 5
    const val CADENCE_BIN_STEP = 5
    const val CADENCE_BIN_COUNT = 29

    const val WATT_BIN_BASE = 25
    const val WATT_BIN_STEP = 25
    const val WATT_BIN_COUNT = 16

    /** [cadenceBin][wattBin] -> resistance percent. */
    val RESISTANCE: Array<DoubleArray> =
        arrayOf(
            // 5 rpm
            doubleArrayOf(NO_DATA, NO_DATA, NO_DATA, NO_DATA, NO_DATA, NO_DATA, NO_DATA, NO_DATA, NO_DATA, NO_DATA, NO_DATA, NO_DATA, NO_DATA, NO_DATA, NO_DATA, NO_DATA),
            // 10 rpm
            doubleArrayOf(NO_DATA, NO_DATA, NO_DATA, NO_DATA, NO_DATA, NO_DATA, NO_DATA, NO_DATA, NO_DATA, NO_DATA, NO_DATA, NO_DATA, NO_DATA, NO_DATA, NO_DATA, NO_DATA),
            // 15 rpm
            doubleArrayOf(NO_DATA, NO_DATA, NO_DATA, NO_DATA, NO_DATA, NO_DATA, NO_DATA, NO_DATA, NO_DATA, NO_DATA, NO_DATA, NO_DATA, NO_DATA, NO_DATA, NO_DATA, NO_DATA),
            // 20 rpm
            doubleArrayOf(69.54, NO_DATA, NO_DATA, NO_DATA, NO_DATA, NO_DATA, NO_DATA, NO_DATA, NO_DATA, NO_DATA, NO_DATA, NO_DATA, NO_DATA, NO_DATA, NO_DATA, NO_DATA),
            // 25 rpm
            doubleArrayOf(56.82, 76.32, 95.99, NO_DATA, NO_DATA, NO_DATA, NO_DATA, NO_DATA, NO_DATA, NO_DATA, NO_DATA, NO_DATA, NO_DATA, NO_DATA, NO_DATA, NO_DATA),
            // 30 rpm
            doubleArrayOf(48.57, 64.78, 77.03, 89.71, NO_DATA, NO_DATA, NO_DATA, NO_DATA, NO_DATA, NO_DATA, NO_DATA, NO_DATA, NO_DATA, NO_DATA, NO_DATA, NO_DATA),
            // 35 rpm
            doubleArrayOf(42.51, 56.69, 67.05, 75.73, 84.50, 94.39, NO_DATA, NO_DATA, NO_DATA, NO_DATA, NO_DATA, NO_DATA, NO_DATA, NO_DATA, NO_DATA, NO_DATA),
            // 40 rpm
            doubleArrayOf(37.81, 50.77, 59.85, 67.43, 73.99, 80.84, 87.59, 95.12, NO_DATA, NO_DATA, NO_DATA, NO_DATA, NO_DATA, NO_DATA, NO_DATA, NO_DATA),
            // 45 rpm
            doubleArrayOf(34.23, 46.15, 54.42, 61.07, 67.01, 72.26, 77.50, 82.71, 88.24, 94.13, NO_DATA, NO_DATA, NO_DATA, NO_DATA, NO_DATA, NO_DATA),
            // 50 rpm
            doubleArrayOf(30.93, 42.21, 49.97, 56.11, 61.38, 66.24, 70.61, 74.65, 79.11, 83.20, 87.69, 92.48, 98.08, NO_DATA, NO_DATA, NO_DATA),
            // 55 rpm
            doubleArrayOf(28.16, 38.99, 46.31, 51.96, 56.87, 61.23, 65.34, 69.02, 72.45, 75.89, 79.62, 82.96, 86.56, 90.60, 94.49, 99.78),
            // 60 rpm
            doubleArrayOf(25.93, 36.35, 43.15, 48.52, 53.11, 57.15, 60.83, 64.34, 67.53, 70.58, 73.40, 76.37, 79.52, 82.36, 85.19, 88.62),
            // 65 rpm
            doubleArrayOf(23.68, 34.06, 40.42, 45.69, 49.92, 53.73, 57.13, 60.32, 63.33, 66.19, 68.85, 71.39, 73.81, 76.38, 79.08, 81.58),
            // 70 rpm
            doubleArrayOf(21.46, 31.82, 38.05, 43.00, 47.13, 50.72, 54.02, 56.96, 59.74, 62.37, 64.98, 67.31, 69.63, 71.78, 73.89, 76.12),
            // 75 rpm
            doubleArrayOf(19.59, 30.03, 36.13, 40.68, 44.85, 48.10, 51.19, 54.09, 56.68, 59.13, 61.48, 63.77, 65.96, 68.00, 70.04, 71.91),
            // 80 rpm
            doubleArrayOf(17.62, 28.24, 34.38, 38.66, 42.48, 45.91, 48.75, 51.45, 54.03, 56.35, 58.52, 60.65, 62.69, 64.74, 66.59, 68.41),
            // 85 rpm
            doubleArrayOf(16.06, 26.77, 32.57, 36.93, 40.52, 43.83, 46.64, 49.18, 51.57, 53.88, 56.00, 57.95, 59.89, 61.73, 63.56, 65.35),
            // 90 rpm
            doubleArrayOf(14.59, 25.56, 31.05, 35.49, 38.77, 41.86, 44.84, 47.16, 49.45, 51.59, 53.68, 55.65, 57.40, 59.15, 60.86, 62.51),
            // 95 rpm
            doubleArrayOf(12.32, 24.25, 29.73, 33.99, 37.25, 40.19, 42.89, 45.46, 47.53, 49.61, 51.55, 53.45, 55.30, 56.89, 58.48, 60.07),
            // 100 rpm
            doubleArrayOf(10.47, 22.85, 28.41, 32.53, 35.96, 38.65, 41.24, 43.71, 45.90, 47.80, 49.69, 51.46, 53.20, 54.95, 56.41, 57.87),
            // 105 rpm
            doubleArrayOf(7.99, 21.67, 27.29, 31.29, 34.80, 37.32, 39.79, 42.08, 44.35, 46.24, 47.98, 49.71, 51.34, 52.95, 54.56, 55.97),
            // 110 rpm
            doubleArrayOf(5.58, 20.66, 26.33, 30.21, 33.45, 36.18, 38.45, 40.66, 42.76, 44.85, 46.49, 48.10, 49.70, 51.21, 52.70, 54.19),
            // 115 rpm
            doubleArrayOf(NO_DATA, 19.74, 25.52, 29.12, 32.27, 35.19, 37.28, 39.38, 41.37, 43.31, 45.20, 46.69, 48.17, 49.66, 51.07, 52.46),
            // 120 rpm
            doubleArrayOf(NO_DATA, 18.75, 24.69, 28.15, 31.24, 34.02, 36.26, 38.21, 40.14, 41.96, 43.77, 45.45, 46.83, 48.22, 49.60, 50.93),
            // 125 rpm
            doubleArrayOf(NO_DATA, 17.91, 23.66, 27.30, 30.33, 32.93, 35.37, 37.18, 38.99, 40.76, 42.46, 44.15, 45.65, 46.95, 48.24, 49.53),
            // 130 rpm
            doubleArrayOf(NO_DATA, 17.19, 22.77, 26.56, 29.45, 31.97, 34.40, 36.28, 37.97, 39.67, 41.28, 42.88, 44.48, 45.82, 47.03, 48.25),
            // 135 rpm
            doubleArrayOf(NO_DATA, 16.58, 21.98, 25.92, 28.61, 31.11, 33.40, 35.48, 37.07, 38.66, 40.24, 41.74, 43.25, 44.75, 45.96, 47.10),
            // 140 rpm
            doubleArrayOf(NO_DATA, 16.05, 21.29, 25.35, 27.88, 30.34, 32.50, 34.66, 36.26, 37.76, 39.26, 40.72, 42.14, 43.57, 44.99, 46.07),
            // 145 rpm
            doubleArrayOf(NO_DATA, 15.60, 20.68, 24.74, 27.22, 29.60, 31.70, 33.74, 35.54, 36.95, 38.37, 39.78, 41.14, 42.49, 43.85, 45.15),
        )
}
