package com.spop.poverlay.erg

import org.junit.Assert.*
import org.junit.Test

class UpstreamSoftwareErgControllerTest {
    private var now = 1000L
    private var resistance = 44
    private val writes = mutableListOf<Int>()
    private var failure: String? = null
    private val controller = UpstreamSoftwareErgController({ writes += it; resistance = it; true }, { now }, Any()) { failure = it }
    private fun sample(power: Float = 168f, external: Boolean = false, cadence: Float = 90f) {
        now += 200
        controller.sample(power, cadence, resistance, now, 1500, 168f, now, external)
        controller.tick()
    }
    @Test fun modeledRampAndSettleMatchUpstream() {
        sample(); assertTrue(controller.enable(220))
        repeat(5) { sample() }
        assertEquals(49, resistance)
        repeat(20) { sample() }
        assertEquals(49, resistance)
        assertTrue(writes.zipWithNext().all { (a, b) -> b - a in 1..2 })
        assertNull(failure)
    }
    @Test fun rampContinuesBetweenFreshBikeSamplesAndStopsForKnob() {
        sample(); controller.enable(345); sample()
        val before = writes.size
        now += 100; controller.tick()
        assertTrue(writes.size > before)
        resistance = 20
        repeat(10) { sample() }
        assertFalse(controller.isActive)
        assertNotNull(failure)
    }
    @Test fun externalPowerDisablesPredictedJump() {
        sample(external = true); controller.enable(220)
        sample(external = true)
        assertTrue(resistance in 44..45)
        assertNull(failure)
    }
    @Test fun staleOrRepeatedDataCannotContinueFeedback() {
        sample(); controller.enable(175); sample(100f, external = true)
        val before = writes.toList()
        repeat(10) { now += 100; controller.tick() }
        assertEquals(before, writes)
        now += 1000; controller.tick()
        assertFalse(controller.isActive)
    }
    @Test fun lowCadencePreventsRampWrites() {
        sample(cadence = 0f); controller.enable(300)
        repeat(10) { sample(cadence = 0f) }
        assertTrue(writes.isEmpty())
    }
    @Test fun ignoredFeedbackWritesCannotKeepExtendingTheDeadline() {
        var at=0L
        var stopped=false
        val sent=mutableListOf<Int>()
        val c=UpstreamSoftwareErgController({sent+=it;true},{at},Any()) {stopped=true}
        c.sample(80f,90f,44,at,1500,168f,at,true)
        c.enable(220)
        repeat(40) {
            at+=200;c.sample(80f,90f,44,at,1500,168f,at,true);c.tick()
        }
        assertTrue(stopped)
        assertFalse(c.isActive)
        assertTrue(sent.size < 8)
    }
    @Test fun nativePowerModelLearnsPassivelyWithoutUsingPedalWattsOrMovingResistance() {
        repeat(30) {
            now += 200
            controller.sample(250f,90f,44,now,3000,175f,now,true)
        }
        assertTrue(controller.powerModel.isLearned(44))
        assertEquals(175f,controller.powerModel.wattsAt90(44),1f)
        assertFalse(controller.isActive)
        assertTrue(writes.isEmpty())
    }
}
