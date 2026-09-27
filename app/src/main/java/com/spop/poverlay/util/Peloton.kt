package com.spop.poverlay.util

import android.os.Build

private const val PelotonBrand = "Peloton"

val IsRunningOnPeloton = Build.BRAND == PelotonBrand

/**
 * Check if the device is a G700 CrossTrainer bike.
 * The G700 uses a different sensor interface than the regular Bike+.
 */
/** G700 model strings include either legacy "G700" or newer "PLTN-ATR" prefixes. */
internal fun isG700CrossTrainerModel(model: String): Boolean {
    return model.contains("G700", ignoreCase = true) || model.startsWith("PLTN-ATR", ignoreCase = true)
}

val IsG700CrossTrainer get() = isG700CrossTrainerModel(Build.MODEL)

/**
 * All Peloton bikes start with model "PLTN-T". Treadmills start with "PLTN-TR", so this might also
 * apply to them, but it will be interesting if anything works on them here.
 * Note: G700 is handled separately.
 */
val IsBikePlus get() = Build.MODEL.contains("PLTN-T")
