package com.spop.poverlay.util

import com.spop.poverlay.sensor.interfaces.SensorInterface
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PelotonSpeedTest {
    @Test
    fun `matches recovered Peloton reference speeds at riding power`() {
        // Golden mph values evaluated independently from the recovered APK expression.
        val references = listOf(
            1f to 0.557248f,
            10f to 4.654218f,
            25f to 8.431366f,
            50f to 11.996086f,
            100f to 16.251014f,
            200f to 21.391121f,
            400f to 27.682318f,
        )
        for ((watts, mph) in references) {
            assertEquals("Power: $watts W", mph, calculateSpeedFromPelotonPower(watts), 0.00001f)
        }
    }

    @Test
    fun `matches Peloton at sprint power where the old fit diverged`() {
        assertEquals(38.344681f, calculateSpeedFromPelotonPower(1000f), 0.00001f)
        assertEquals(44.143122f, calculateSpeedFromPelotonPower(1500f), 0.00001f)
        assertEquals(48.740178f, calculateSpeedFromPelotonPower(2000f), 0.00001f)
    }

    @Test
    fun `idle and invalid power produce zero speed`() {
        for (watts in listOf(-1f, 0f, 0.05f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertEquals(0f, calculateSpeedFromPelotonPower(watts), 0f)
        }
    }

    @Test
    fun `speed increases smoothly across the former 26 watt breakpoint`() {
        val below = calculateSpeedFromPelotonPower(25.99f)
        val above = calculateSpeedFromPelotonPower(26.01f)
        assertTrue(above > below)
        assertTrue(above - below < 0.01f)

        var previous = 0f
        for (watts in 1..2500) {
            val speed = calculateSpeedFromPelotonPower(watts.toFloat())
            assertTrue("Power: $watts W", speed.isFinite() && speed > previous)
            previous = speed
        }
    }

    @Test
    fun `sensor interface exports the recovered speed curve in mph`() = runBlocking {
        val sensor = object : SensorInterface {
            override val power = flowOf(100f, 400f, 1000f)
            override val cadence = emptyFlow<Float>()
            override val resistance = emptyFlow<Float>()
        }
        val speeds = sensor.speed.toList()
        assertEquals(3, speeds.size)
        assertEquals(16.251014f, speeds[0], 0.00001f)
        assertEquals(27.682318f, speeds[1], 0.00001f)
        assertEquals(38.344681f, speeds[2], 0.00001f)
    }
}
