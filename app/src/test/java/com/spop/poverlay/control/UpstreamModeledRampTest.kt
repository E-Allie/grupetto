package com.spop.poverlay.control

import org.junit.Assert.*
import org.junit.Test

class UpstreamModeledRampTest {
    private var now = 1000L
    private var resistance = 40
    private val writes = mutableListOf<Int>()
    private var writeSucceeds = true
    private var holdEcho = false
    private val control = BikeControl(true, { now }) {
        writes.add(it)
        if (writeSucceeds && !holdEcho) resistance = it
        writeSucceeds
    }.apply { setSoftwareAlgorithm(com.spop.poverlay.erg.SoftwareErgAlgorithm.UpstreamModel) }
    private fun sample(power: Float = 100f, cadence: Float = 80f, elapsed: Long = 200) {
        now += elapsed
        control.acceptSample(BikeSample(power, cadence, resistance, now))
        control.tick()
    }
    private fun command(vararg bytes: Int, client: String = "ble:a") =
        control.procedure(client, bytes.map { it.toByte() }.toByteArray())
    private fun acquire() { pedal(100f, 80f); assertEquals(1, command(0).result) }
    /** Steady pedaling past the spin-up window, so modeled jumps are allowed. */
    private fun pedal(power: Float = 168f, cadence: Float = 90f) = repeat(80) { sample(power, cadence) }

    @Test fun largeErgStepJumpsToModeledLevelWithoutWindup() {
        resistance = 44
        pedal()
        assertTrue(control.localErg(220))
        // The 10 levels/s ramp reaches the modeled level within three samples.
        repeat(3) { sample(168f, 90f) }
        assertEquals(49, resistance)
        // Held there through the 4.5 s settle even though the lagging power report still reads
        // 52 W low: the controller must not keep climbing past the modeled level.
        repeat(22) { sample(168f, 90f) }
        assertEquals(49, resistance)
        assertTrue(writes.all { it in 45..49 })
        assertTrue(writes.zipWithNext().all { (a, b) -> b - a in 1..2 })
    }
    @Test fun rampMovesOneLevelPerTickBetweenSamples() {
        resistance = 44
        pedal()
        assertTrue(control.localErg(220))
        sample(168f, 90f)
        assertEquals(listOf(45), writes)
        // The 100 ms control loop keeps the ramp moving without waiting for new telemetry.
        now += 100; control.tick()
        assertEquals(listOf(45, 46), writes)
        now += 100; control.tick()
        assertEquals(listOf(45, 46, 47), writes)
    }
    @Test fun rampSpeedIsAdjustableAndClamped() {
        assertEquals(com.spop.poverlay.erg.UpstreamSoftwareErgController.RAMP_RATE_DEFAULT, control.state.value.rampRate)
        control.tune(2, .007f, rampRate = 15)
        assertEquals(15, control.state.value.rampRate)
        control.tune(2, .007f, rampRate = 100)
        assertEquals(com.spop.poverlay.erg.UpstreamSoftwareErgController.RAMP_RATE_MAX, control.state.value.rampRate)
        control.tune(2, .007f, rampRate = 0)
        assertEquals(com.spop.poverlay.erg.UpstreamSoftwareErgController.RAMP_RATE_MIN, control.state.value.rampRate)
        // Other tuning keeps the chosen ramp speed.
        control.tune(2, .007f, rampRate = 12); control.tune(3, .01f)
        assertEquals(12, control.state.value.rampRate)
    }
    @Test fun slowerRampMovesHalfALevelPerTick() {
        resistance = 44
        control.tune(2, .007f, rampRate = 5)
        pedal()
        assertTrue(control.localErg(220))
        sample(168f, 90f)
        assertEquals(listOf(45), writes)
        repeat(4) { now += 100; control.tick() }
        // 5 levels/s: two more levels in 400 ms (10 levels/s would have written four).
        assertEquals(listOf(45, 46, 47), writes)
    }
    @Test fun fasterRampReachesTheModeledLevelSooner() {
        resistance = 44
        control.tune(2, .007f, rampRate = 20)
        pedal()
        assertTrue(control.localErg(220))
        sample(168f, 90f)
        now += 100; control.tick()
        assertTrue("20 levels/s moves two levels per tick, got $writes", writes.last() >= 47)
    }
    @Test fun knobMovedDuringRampStopsWithoutFighting() {
        resistance = 44
        pedal()
        assertTrue(control.localErg(345))
        holdEcho = true
        sample(168f, 90f)
        // The echo may trail the ramp by up to 5 levels before the ramp pauses.
        repeat(2) { sample(168f, 90f) }
        val written = writes.size
        resistance = 30
        repeat(10) { sample(168f, 90f) }
        assertTrue(control.state.value.suspended)
        assertTrue(writes.size <= written + 1)
    }
    @Test fun dropUsesCadenceHeldAtTheLowerTarget() {
        resistance = 43
        sample(170f, 90f)
        assertTrue(control.localErg(170))
        repeat(150) { sample(170f, 90f) }           // 30 s settled at 170 W, 90 rpm
        assertTrue(control.localErg(360))
        repeat(150) { sample(360f, 97f) }           // a hard effort spun at 97 rpm
        val expected = control.powerModel.nearestLevelFor(170f, 90f)!!
        val atHighCadence = control.powerModel.nearestLevelFor(170f, 97f)!!
        assertTrue(expected != atHighCadence)
        assertTrue(control.localErg(170))
        repeat(15) { sample(360f, 97f) }            // the ramp runs while cadence is still high
        assertEquals(expected, resistance)
    }
    @Test fun deadbandWidensWithResistanceLevelSize() {
        // Near 345 W one level is about 13 W, so a 5 W shortfall is held.
        resistance = 58
        repeat(3) { sample(340f, 90f) }
        assertTrue(control.localErg(345))
        repeat(50) { sample(340f, 90f) }
        assertTrue(writes.isEmpty())
    }
    @Test fun deadbandStaysNarrowAtModeratePower() {
        // Near 170 W one level is about 10 W, so a 5 W shortfall is still trimmed.
        resistance = 44
        repeat(3) { sample(170f, 90f) }
        assertTrue(control.localErg(175))
        repeat(50) { sample(170f, 90f) }
        assertTrue(writes.isNotEmpty())
    }
    @Test fun moreResistanceWaitsUntilSpinUpIsOver() {
        // Oct 31: ERG 165 W arrived 5 s after the rider started pedaling; a jump sized for the
        // 55 rpm average put out ~285 W at 75 rpm. Now the level holds while cadence is still
        // rising, then jumps once, sized for the cadence they actually settled at.
        resistance = 30
        repeat(10) { sample(0f, 0f) }
        repeat(20) { k -> sample(40f, 45f + k * .5f) }   // 4 s of pedaling, rising 2.5 rpm/s
        assertTrue(control.localErg(165))
        repeat(25) { k -> sample(60f, 55f + k * .5f) }   // to 9 s: still rising (to ~67 rpm)
        assertTrue("held while spinning up, got $writes", writes.isEmpty())
        repeat(25) { sample(70f, 80f) }                  // settles at 80 rpm: steady after 3 s
        // One jump well before the 15 s cap, to about the level 165 W needs at 80 rpm.
        val expected = control.powerModel.nearestLevelFor(165f, 80f)!!
        assertTrue("expected about $expected, got $resistance", resistance in expected - 1..expected + 1)
        assertTrue(writes.zipWithNext().all { (x, y) -> y - x in 1..2 })
    }
    @Test fun steadyCadenceEndsTheSpinUpWaitEarly() {
        // Nov 2: steady 92 rpm 3 s after starting; the fixed 15 s wait held 165 W back another 9 s.
        resistance = 20
        repeat(10) { sample(0f, 0f) }
        assertTrue(control.localErg(165))
        repeat(15) { sample(30f, 92f) }                  // 3 s: below SPIN_UP_MIN_MS, still waiting
        assertTrue("held before the minimum, got $writes", writes.isEmpty())
        repeat(25) { sample(35f, 92f) }                  // to 8 s: steady, so the wait is over
        val expected = control.powerModel.nearestLevelFor(165f, 92f)!!
        assertTrue("expected about $expected by 8 s, got $resistance", resistance in expected - 1..expected + 1)
    }
    @Test fun wobblingCadenceKeepsTheFullSpinUpWait() {
        // Cadence that never holds within SPIN_UP_STEADY_RPM keeps the 15 s bound.
        resistance = 20
        repeat(10) { sample(0f, 0f) }
        assertTrue(control.localErg(165))
        repeat(70) { k -> sample(35f, if (k % 10 < 5) 84f else 92f) }   // 14 s, 8 rpm swings
        assertTrue("held for the full window, got $writes", writes.isEmpty())
        repeat(15) { sample(35f, 88f) }
        assertTrue("jumps after 15 s, got $writes", writes.isNotEmpty())
    }
    @Test fun jumpsNormallyAfterSpinUp() {
        resistance = 30
        repeat(10) { sample(0f, 0f) }
        repeat(80) { sample(60f, 80f) }   // 16 s of pedaling
        assertTrue(control.localErg(165))
        repeat(5) { sample(60f, 80f) }    // 1 s
        assertTrue("expected a modeled jump, got $writes", resistance >= 38)
    }
    @Test fun stoppingRestartsTheSpinUpWindow() {
        resistance = 30
        pedal(60f, 80f)
        repeat(10) { sample(0f, 0f) }     // stopped
        repeat(10) { sample(40f, 60f) }   // pedaling again for 2 s
        assertTrue(control.localErg(165))
        repeat(5) { sample(60f, 75f) }
        assertTrue("held while spinning up, got $writes", writes.isEmpty())
    }
    @Test fun spinUpNeverAddsResistanceForALowerTarget() {
        resistance = 60
        repeat(10) { sample(0f, 0f) }
        repeat(10) { sample(200f, 60f) }  // 2 s of heavy pedaling at a low cadence
        assertTrue(control.localErg(150))
        repeat(10) { sample(190f, 60f) }
        assertTrue("got $writes", writes.all { it <= 60 } && resistance <= 60)
    }
    @Test fun smallErgStepKeepsProportionalTrim() {
        resistance = 44
        sample(168f, 90f)
        assertTrue(control.localErg(175))
        sample(168f, 90f)
        assertTrue(resistance in 44..45)
    }
    @Test fun powerModelLearnsSteadyReadingsInAnyMode() {
        resistance = 44
        repeat(30) { sample(175f, 90f) }
        assertEquals(175f, control.powerModel.wattsAt90(44), 1f)
        assertEquals(44f, control.powerModel.levelFor(175f, 90f)!!, .2f)
        // Neighbouring unlearned levels are scaled by the learned correction.
        assertTrue(control.powerModel.wattsAt90(45) > PowerModel.seedWatts(45))
        assertTrue(writes.isEmpty())
    }
}
