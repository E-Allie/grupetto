package com.spop.poverlay.sim

import android.content.SharedPreferences
import androidx.core.content.edit

/** One persistence format shared by the configuration UI and application control. */
object SimulationPreferences {
    private const val RIDER = "simulationRiderMassKg"
    private const val BICYCLE = "simulationBicycleMassKg"
    private const val UNIT = "simulationMassUnit"
    private const val ENABLED = "simulationEnabled"
    private const val GEARING = "simulationGearProfile"
    private const val MAX_WATTS = "simulationMaxWatts"
    private const val AUTO_RESUME = "simulationAutoResume"

    fun read(preferences: SharedPreferences): SimulationSettings {
        fun mass(key: String, range: ClosedFloatingPointRange<Double>, default: Double): Double =
            runCatching { preferences.getString(key, null)?.toDoubleOrNull() }.getOrNull()
                ?.takeIf { it.isFinite() && it in range } ?: default
        return SimulationSettings(
            riderMassKg = mass(RIDER, SimulationSettings.RIDER_KG_RANGE, SimulationSettings.DEFAULT_RIDER_KG),
            bicycleMassKg = mass(BICYCLE, SimulationSettings.BICYCLE_KG_RANGE, SimulationSettings.DEFAULT_BICYCLE_KG),
            massUnit = runCatching { MassUnit.valueOf(preferences.getString(UNIT, "") ?: "") }
                .getOrDefault(MassUnit.Kilograms),
            enabled = runCatching { preferences.getBoolean(ENABLED, false) }.getOrDefault(false),
            gearing = runCatching { GearProfile.valueOf(preferences.getString(GEARING, "") ?: "") }
                .getOrDefault(GearProfile.Climbing),
            maxSimWatts = runCatching { preferences.getString(MAX_WATTS, null)?.toIntOrNull() }.getOrNull()
                ?.takeIf { it in SimulationSettings.SIM_WATTS_RANGE } ?: SimulationSettings.DEFAULT_MAX_SIM_WATTS,
            autoResumeAfterPause = runCatching { preferences.getBoolean(AUTO_RESUME, false) }.getOrDefault(false)
        )
    }

    fun write(preferences: SharedPreferences, settings: SimulationSettings) = preferences.edit {
        putString(RIDER, settings.riderMassKg.toString())
        putString(BICYCLE, settings.bicycleMassKg.toString())
        putString(UNIT, settings.massUnit.name)
        putBoolean(ENABLED, settings.enabled)
        putString(GEARING, settings.gearing.name)
        putString(MAX_WATTS, settings.maxSimWatts.toString())
        putBoolean(AUTO_RESUME, settings.autoResumeAfterPause)
    }
}
