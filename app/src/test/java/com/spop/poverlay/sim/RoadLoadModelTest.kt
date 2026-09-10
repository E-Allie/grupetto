package com.spop.poverlay.sim

import org.junit.Assert.*
import org.junit.Test

class RoadLoadModelTest {
    private val settings = SimulationSettings(gearing = GearProfile.Road, maxSimWatts = 1000)
    private fun load(grade: Double = 0.0, gear: Int = 12, cadence: Double = 85.0, wind: Double = 0.0) =
        RoadLoadModel.calculate(cadence, gear, SimulationParameters(wind, grade, 0.004, 0.51), settings)

    @Test fun `road load matches independently calculated plan examples`() {
        val examples = mapOf(1 to listOf(23.7, 170.4, 14.9), 12 to listOf(102.3, 378.6, 85.7),
            24 to listOf(681.2, 1232.6, 648.0))
        examples.forEach { (gear, watts) ->
            listOf(0.0, 5.0, -0.3).zip(watts).forEach { (grade, expected) ->
                assertEquals(expected, load(grade, gear).watts, 0.05)
            }
        }
    }

    @Test fun `strong tailwind assists rather than increasing drag`() {
        val still = load()
        assertEquals(0.0, load(wind = -still.speedMps).aeroForceNewtons, 1e-9)
        assertTrue(load(wind = -20.0).aeroForceNewtons < 0)
        assertTrue(load(wind = -20.0).watts < 0)
        assertTrue(load(wind = 5.0).watts > still.watts)
    }

    @Test fun `zero cadence and zero terrain are valid without invented drag`() {
        assertEquals(0.0, load(grade = 10.0, cadence = 0.0).watts, 0.0)
        val zero = SimulationParameters.parse(byteArrayOf(17, 0, 0, 0, 0, 0, 0))!!
        assertEquals(0.0, RoadLoadModel.calculate(90.0, 24, zero, settings).watts, 0.0)
        assertTrue(load(grade = -5.0).watts < 0)
    }

    @Test fun `total mass affects grade and rolling forces but not aero`() {
        val terrain = SimulationParameters(0.0, 5.0, 0.004, 0.51)
        val first = RoadLoadModel.calculate(85.0, 12, terrain, SimulationSettings(75.0, 8.0))
        val second = RoadLoadModel.calculate(85.0, 12, terrain, SimulationSettings(158.0, 8.0))
        assertEquals(first.gradeForceNewtons * 2, second.gradeForceNewtons, 1e-9)
        assertEquals(first.rollingForceNewtons * 2, second.rollingForceNewtons, 1e-9)
        assertEquals(first.aeroForceNewtons, second.aeroForceNewtons, 0.0)
    }

    @Test fun `wire extremes stay finite and invalid sensor values are rejected`() {
        for (sign in listOf(0x7F, 0x80)) {
            val terrain = SimulationParameters.parse(byteArrayOf(17, 0, sign.toByte(), 0, sign.toByte(), -1, -1))!!
            assertTrue(RoadLoadModel.calculate(300.0, 24, terrain, SimulationSettings(300.0, 50.0)).watts.isFinite())
        }
        listOf(Double.NaN, Double.POSITIVE_INFINITY, -1.0, 301.0).forEach { rpm ->
            assertThrows(IllegalArgumentException::class.java) { load(cadence = rpm) }
        }
    }
}
