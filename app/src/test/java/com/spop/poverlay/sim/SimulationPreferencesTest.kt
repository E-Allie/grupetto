package com.spop.poverlay.sim

import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.*
import org.junit.Test

class SimulationPreferencesTest {
    private val values = mutableMapOf<String, Any?>()
    private val preferences = mockk<SharedPreferences> {
        every { getString(any(), any()) } answers { (values[firstArg()] ?: secondArg<String?>()) as String? }
        every { getBoolean(any(), any()) } answers { (values[firstArg()] ?: secondArg<Boolean>()) as Boolean }
        every { edit() } returns mockk<SharedPreferences.Editor>(relaxed = true) {
            every { putString(any(), any()) } answers { values[firstArg()] = secondArg<String?>(); self as SharedPreferences.Editor }
            every { putBoolean(any(), any()) } answers { values[firstArg()] = secondArg<Boolean>(); self as SharedPreferences.Editor }
        }
    }

    @Test fun `weights units and opt-in survive a preferences reload`() {
        assertEquals(SimulationSettings(), SimulationPreferences.read(preferences))
        val settings = SimulationSettings(82.125, 9.4, MassUnit.Pounds, true,
            gearing = GearProfile.Road, maxSimWatts = 125, autoResumeAfterPause = true)
        SimulationPreferences.write(preferences, settings)
        assertEquals(settings, SimulationPreferences.read(preferences))
    }

    @Test fun `corrupt preferences fall back to usable defaults`() {
        values["simulationRiderMassKg"] = "NaN"
        values["simulationBicycleMassKg"] = "-5"
        values["simulationMassUnit"] = "stones"
        values["simulationEnabled"] = "yes"
        values["simulationMaxWatts"] = "-1"
        values["simulationGearProfile"] = "invalid"
        values["simulationAutoResume"] = "yes"
        assertEquals(SimulationSettings(), SimulationPreferences.read(preferences))
    }

    @Test fun `old preferences retain rider settings and gain gentler defaults`() {
        values["simulationRiderMassKg"] = "158.7573295"
        values["simulationBicycleMassKg"] = "8.0"
        values["simulationMassUnit"] = "Pounds"
        values["simulationEnabled"] = true
        val migrated = SimulationPreferences.read(preferences)
        assertEquals(158.7573295, migrated.riderMassKg, 0.0)
        assertEquals(MassUnit.Pounds, migrated.massUnit)
        assertTrue(migrated.enabled)
        assertEquals(GearProfile.Climbing, migrated.gearing)
        assertEquals(140, migrated.maxSimWatts)
        assertFalse(migrated.autoResumeAfterPause)
        assertThrows(IllegalArgumentException::class.java) { migrated.copy(maxSimWatts = 0) }
    }

    @Test fun `unit conversion preserves mass and rejects non-finite input`() {
        assertEquals(75.0, MassUnit.Pounds.toKg(MassUnit.Pounds.fromKg(75.0)), 1e-10)
        assertEquals(82.5, MassUnit.Kilograms.parseKg("82,5")!!, 1e-10)
        assertNull(MassUnit.Pounds.parseKg("NaN"))
        assertNull(MassUnit.Pounds.parseKg("Infinity"))
        assertThrows(IllegalArgumentException::class.java) { SimulationSettings(-5.0) }
    }
}
