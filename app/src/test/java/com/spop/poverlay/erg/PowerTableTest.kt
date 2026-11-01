package com.spop.poverlay.erg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PowerTableTest {
    @Test fun `calibrated endpoints return their actual resistance`() {
        assertTrue(PowerTable.isCalibrated)
        assertEquals(17.62, PowerTable.resistanceFor(25, 80.0)!!, 1e-9)
        assertEquals(68.41, PowerTable.resistanceFor(400, 80.0)!!, 1e-9)
    }

    @Test fun `out of range targets never reuse an endpoint prediction`() {
        for (watts in listOf(Int.MIN_VALUE, -1, 0, 24, 401, 800, 1000, Int.MAX_VALUE)) {
            assertNull("Prediction for $watts W", PowerTable.resistanceFor(watts, 80.0))
            assertNull("Slope for $watts W", PowerTable.resistancePerWatt(watts, 80.0))
        }
    }

    @Test fun `interpolation preserves the calibrated two axis baseline`() {
        assertEquals(51.45, PowerTable.resistanceFor(200, 80.0)!!, 1e-9)
        assertEquals(52.6884, PowerTable.resistanceFor(212, 80.0)!!, 1e-9)
        assertEquals(50.315, PowerTable.resistanceFor(200, 82.5)!!, 1e-9)
        assertEquals(51.5078, PowerTable.resistanceFor(212, 82.5)!!, 1e-9)
    }

    @Test fun `scheduled slope preserves the interior baseline`() {
        assertEquals(0.1056, PowerTable.resistancePerWatt(200, 80.0)!!, 1e-9)
        assertEquals(0.1021, PowerTable.resistancePerWatt(200, 82.5)!!, 1e-9)
        assertEquals((34.38 - 17.62) / 50, PowerTable.resistancePerWatt(50, 80.0)!!, 1e-9)
        assertEquals((68.41 - 64.74) / 50, PowerTable.resistancePerWatt(375, 80.0)!!, 1e-9)
    }

    @Test fun `slope requires both samples to be calibrated`() {
        for (watts in listOf(25, 49, 376, 400)) {
            assertNull(PowerTable.resistancePerWatt(watts, 80.0))
        }
    }

    @Test fun `nonfinite cadence cannot generate a prediction or gain`() {
        for (cadence in listOf(Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY)) {
            assertNull(PowerTable.resistanceFor(200, cadence))
            assertNull(PowerTable.resistancePerWatt(200, cadence))
        }
    }

    @Test fun `cadence endpoints and missing row fallback retain baseline behavior`() {
        assertNull(PowerTable.resistanceFor(200, -10.0))
        assertNull(PowerTable.resistanceFor(200, 5.0))
        assertEquals(45.15, PowerTable.resistanceFor(400, 145.0)!!, 1e-9)
        assertEquals(45.15, PowerTable.resistanceFor(400, 200.0)!!, 1e-9)
        assertEquals(69.54, PowerTable.resistanceFor(25, 17.5)!!, 1e-9)
    }
}
