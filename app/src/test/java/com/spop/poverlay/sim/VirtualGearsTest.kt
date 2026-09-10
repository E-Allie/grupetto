package com.spop.poverlay.sim

import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VirtualGearsTest {

    @Before fun useClimbingProfile() { VirtualGears.setProfile(GearProfile.Climbing) }

    @After
    fun reset() {
        VirtualGears.onGearChanged = null
        VirtualGears.shiftTo(VirtualGears.DEFAULT_GEAR)
    }

    @Test
    fun `ratios rise monotonically across the block`() {
        val ratios = (1..VirtualGears.GEAR_COUNT).map { VirtualGears.ratioFor(it) }
        ratios.zipWithNext().forEach { (low, high) ->
            assertTrue("ratios must increase: $low then $high", high > low)
        }
        assertEquals(0.40, ratios.first(), 1e-6)
        assertEquals(4.55, ratios.last(), 1e-6)
    }

    @Test
    fun `shifting clamps at both ends`() {
        VirtualGears.shiftTo(1)
        VirtualGears.shiftDown()
        assertEquals(1, VirtualGears.gear.value)

        VirtualGears.shiftTo(VirtualGears.GEAR_COUNT)
        VirtualGears.shiftUp()
        assertEquals(VirtualGears.GEAR_COUNT, VirtualGears.gear.value)
    }

    @Test
    fun `a change at the end of the block does not notify`() {
        VirtualGears.shiftTo(VirtualGears.GEAR_COUNT)
        var notifications = 0
        VirtualGears.onGearChanged = { _, _ -> notifications++ }

        VirtualGears.shiftUp()
        assertEquals(0, notifications)

        VirtualGears.shiftDown()
        assertEquals(1, notifications)
    }

    @Test
    fun `virtual speed spans a realistic road range at riding cadence`() {
        // The point of the ratios: at a normal cadence the lowest gear should be
        // a climbing speed and the highest a sprinting one. If this drifts, sim
        // mode will ask for power the brake cannot reach.
        val lowKmh = VirtualGears.virtualSpeedMps(85.0, gear = 1) * 3.6
        val highKmh = VirtualGears.virtualSpeedMps(85.0, gear = VirtualGears.GEAR_COUNT) * 3.6

        assertTrue("lowest gear was $lowKmh km/h", lowKmh in 3.0..6.0)
        assertTrue("highest gear was $highKmh km/h", highKmh in 44.0..54.0)
    }

    @Test
    fun `virtual speed is proportional to cadence`() {
        val slow = VirtualGears.virtualSpeedMps(45.0, gear = 8)
        val fast = VirtualGears.virtualSpeedMps(90.0, gear = 8)
        assertEquals(2.0, fast / slow, 1e-9)
    }
}
