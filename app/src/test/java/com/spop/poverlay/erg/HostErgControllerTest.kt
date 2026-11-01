package com.spop.poverlay.erg

import org.junit.Assert.*
import org.junit.Test

class HostErgControllerTest {
    private class Rig {
        var now = 0L
        var resistance = 20
        var failWrites = false
        var failure: String? = null
        val writes = mutableListOf<Int>()
        val host = HostErgController({ value ->
            if (failWrites) false else { writes += value; resistance = value; true }
        }, { now }, onStandDown = { failure = it })
        fun sample(power: Float = 100f, cadence: Float = 80f) = host.sample(power, cadence, resistance, now)
        fun step(delta: Long = 100, power: Float = 100f, cadence: Float = 80f) {
            now += delta; sample(power, cadence); host.tick()
        }
    }

    @Test fun `table feed forward is immediate and retains two second settling`() {
        val r = Rig()
        r.sample()
        assertTrue(r.host.enable(150))
        r.host.tick()
        assertEquals(listOf(45), r.writes) // Calibrated 45.91, not a 3 levels per second ramp.
        repeat(19) { r.step() }
        assertEquals(listOf(45), r.writes)
        repeat(4) { r.step() }
        assertTrue(r.writes.last() > 45)
    }

    @Test fun `stale or repeated input never keeps adjusting the brake`() {
        val r = Rig()
        r.sample(0f)
        assertTrue(r.host.enable(500))
        r.host.tick()
        val writes = r.writes.toList()
        repeat(15) { r.now += 100; r.host.tick() }
        assertEquals(writes, r.writes)
        assertTrue(r.host.isActive)
        r.now++
        r.host.tick()
        assertFalse(r.host.isActive)
        assertEquals("Power or cadence data lost", r.failure)
        assertEquals(writes, r.writes)
    }

    @Test fun `high target uses PID rather than the 400 watt table endpoint`() {
        val r = Rig()
        r.sample(0f)
        r.host.enable(800)
        r.host.tick()
        assertEquals(listOf(23), r.writes) // Existing 3-point per 100 ms PID stability bound.
    }

    @Test fun `unexpected rider resistance stops software control until explicit enable`() {
        val r = Rig()
        r.sample(); r.host.enable(150); r.host.tick()
        r.now = 100
        r.host.sample(100f, 80f, 2, r.now)
        assertFalse(r.host.isActive)
        assertEquals("Resistance changed outside app control", r.failure)
        val count = r.writes.size
        assertFalse(r.host.setTarget(200))
        r.step()
        assertEquals(count, r.writes.size)
    }

    @Test fun `write failure is reported and does not leave loop active`() {
        val r = Rig()
        r.sample(); r.host.enable(150)
        r.failWrites = true
        r.host.tick()
        assertFalse(r.host.isActive)
        assertEquals("Resistance command failed", r.failure)
    }

    @Test fun `gain adjustment scales fallback correction while default stays at old gain`() {
        fun first(gain: Float): Int {
            val r = Rig()
            r.sample(400f)
            r.host.setGain(gain)
            r.host.enable(500)
            r.host.tick()
            r.step(power = 400f)
            return r.resistance
        }
        assertEquals(21, first(.007f)) // 2 * 0.007 * 100 W = 1.4 points.
        assertEquals(22, first(.014f))
    }

    @Test fun `200 ms readings preserve original 100 ms EMA and PD strength`() {
        fun run(period: Long): Int {
            val r = Rig()
            r.resistance = 40
            r.sample(400f)
            r.host.enable(500)
            r.host.tick()
            repeat((2000L / period).toInt()) { r.step(period, power = 440f) }
            return r.resistance
        }
        assertEquals(run(100), run(200))
        assertEquals(47, run(100)) // 40 + 0.7 initial + 11.889 integrated P - 4.985 D.
    }

    @Test fun `low cadence leaves resistance unchanged and invalid input disarms`() {
        val r = Rig()
        r.sample(cadence = 20f); r.host.enable(150); r.host.tick()
        assertTrue(r.writes.isEmpty())
        r.now = 100
        r.host.sample(Float.NaN, 80f, 20, r.now)
        assertFalse(r.host.isActive)
        assertTrue(r.writes.isEmpty())
    }

    @Test fun `disable is final until fresh explicit enable`() {
        val r = Rig()
        r.sample(); r.host.enable(150); r.host.tick(); r.host.disable()
        val writes = r.writes.toList()
        repeat(40) { r.step() }
        assertFalse(r.host.setTarget(200))
        assertEquals(writes, r.writes)
    }
    @Test fun `gain adjustment preserves the calibrated gain schedule shape`() {
        fun settled(gain: Float): Int {
            val r = Rig()
            r.sample(150f)
            r.host.setGain(gain)
            r.host.enable(200)
            r.host.tick()
            repeat(22) { r.step(power = 150f) }
            return r.resistance
        }
        // At 200 W/80 rpm the table schedules Kp=.00528, giving .264
        // resistance points per 100 ms for this 50 W error after settling.
        assertEquals(52, settled(.007f))
        assertEquals(53, settled(.014f))
    }

    @Test fun `an immediate echo from the writer is recognized as our command`() {
        var now = 0L
        var reason: String? = null
        lateinit var host: HostErgController
        host = HostErgController({ value ->
            host.sample(100f, 80f, value, ++now)
            true
        }, { now }, onStandDown = { reason = it })
        host.sample(100f, 80f, 20, now)
        host.enable(150)
        host.tick()
        assertTrue(host.isActive)
        assertNull(reason)
    }

    @Test fun `external power timestamp is not refreshed by newer bike feedback`() {
        var now = 0L
        var resistance = 20
        val writes = mutableListOf<Int>()
        val host = HostErgController({ resistance = it; writes += it; true }, { now })
        host.sample(100f, 80f, resistance, 0L, staleAfterMs = 3000)
        host.enable(500); host.tick()
        val first = writes.toList()
        repeat(15) {
            now += 200
            // New cadence and resistance feedback accompanies the same meter packet.
            host.sample(100f, 80f, resistance, 0L, staleAfterMs = 3000)
            host.tick()
        }
        assertEquals(first, writes)
        assertTrue(host.isActive)
        now++
        host.tick()
        assertFalse(host.isActive)
        assertEquals(first, writes)
    }

    @Test fun `an inactive loop accepts the new sources earlier fresh timestamp`() {
        var now = 1000L
        val writes = mutableListOf<Int>()
        val host = HostErgController({ writes += it; true }, { now })
        host.sample(500f, 80f, 20, 1000L)
        host.enable(500); host.tick(); host.disable()
        // The external meter packet predates the last bike poll but is still fresh.
        host.sample(0f, 80f, 20, 900L, staleAfterMs = 3000)
        assertTrue(host.enable(500)); host.tick()
        assertEquals(listOf(23), writes)
    }

    @Test fun `first trim after settling tolerates the still in flight previous resistance`() {
        val r = Rig()
        r.sample(); r.host.enable(150); r.host.tick()
        repeat(20) { r.step() }
        assertEquals(46, r.resistance)
        // The first adjustment followed a 2 s hold at 45. A delayed Binder poll
        // or newer meter packet may still carry that preceding bike sample.
        r.now += 100
        r.host.sample(100f, 80f, 45, r.now)
        r.host.tick()
        assertTrue(r.host.isActive)
        assertNull(r.failure)
    }

}
