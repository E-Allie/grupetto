package com.spop.poverlay.sim

import org.junit.Assert.*
import org.junit.Test

class SimulationImprovementsTest {
    private val settings = SimulationSettings(riderMassKg = 158.7573295)
    private val climb = SimulationParameters(0.0, 7.12, .0053, .51)

    private fun advance(engine: SimulationEngine, end: Long, settings: SimulationSettings = this.settings): SimulationDecision {
        engine.sampleCadence(85.0, 0)
        engine.begin(climb, 0)
        var result = engine.evaluate(0, 24, settings)
        for (t in 200L..end step 200) {
            engine.sampleCadence(85.0, t)
            result = engine.evaluate(t, 24, settings)
        }
        return result
    }

    @Test fun `climbing gear reduces the measured ride scenario without altering mass`() {
        val terrain = climb.copy(gradePercent = 4.3)
        val road = RoadLoadModel.calculate(80.0, 1, terrain, settings.copy(gearing = GearProfile.Road))
        val low = RoadLoadModel.calculate(80.0, 1, terrain, settings)
        assertEquals(278.0, road.watts, .1)
        assertEquals(89.0, low.watts, .1)
        for (profile in GearProfile.values()) {
            val ratios = (1..24).map { GearRatios.ratioFor(it, profile) }
            assertTrue(ratios.zipWithNext().all { (a,b) -> a < b })
            assertEquals(4.55, ratios.last(), 1e-9)
        }
    }

    @Test fun `both backends enforce the personal ceiling on every target`() {
        for (policy in listOf(SimulationPolicy(), SimulationPolicy(minWatts = 25, maxWatts = 1000, reenterWatts = 30))) {
            val engine = SimulationEngine(policy)
            engine.sampleCadence(85.0, 0)
            engine.begin(climb, 0)
            engine.evaluate(0, 24, settings)
            var last = 0
            for (t in 200L..14000L step 200) {
                engine.sampleCadence(85.0, t)
                val result = engine.evaluate(t, 24, settings)
                assertTrue(result.capped)
                assertEquals(140, result.powerLimitWatts)
                (result.action as? SimulationAction.Target)?.let {
                    assertTrue(it.watts in policy.minWatts..140)
                    last = it.watts
                }
            }
            assertEquals(140, last)
        }
    }

    @Test fun `a one watt ceiling reduction bypasses deadband and rate limit`() {
        val engine = SimulationEngine()
        assertEquals(140, advance(engine, 8000).requestedWatts)
        engine.sampleCadence(85.0, 8050)
        val result = engine.evaluate(8050, 24, settings.copy(maxSimWatts = 139))
        assertEquals(SimulationAction.Target(139), result.action)
    }

    @Test fun `hardware maximum still applies when personal ceiling is higher`() {
        val engine = SimulationEngine()
        val result = advance(engine, 16000, settings.copy(maxSimWatts = 1000))
        assertEquals(800, result.requestedWatts)
        assertEquals(800, result.powerLimitWatts)
    }

    @Test fun `restart ramp is gentle and does not increase during a sharp cadence drop`() {
        val engine = SimulationEngine()
        advance(engine, 400)
        engine.sampleCadence(85.0, 900)
        assertEquals(SimulationAction.Target(30), engine.evaluate(900, 24, settings).action)
        engine.sampleCadence(60.0, 1400)
        val falling = engine.evaluate(1400, 24, settings)
        assertEquals("cadence falling", falling.reason)
        assertEquals(SimulationAction.None, falling.action)
        assertEquals(30, falling.requestedWatts)
        // Settling at the slower cadence eventually allows a gradual increase.
        engine.sampleCadence(60.0, 1900)
        engine.sampleCadence(60.0, 2400)
        assertEquals(SimulationAction.Target(40), engine.evaluate(2400, 24, settings).action)
    }

    @Test fun `paused rider sees bounded prospective power instead of a stale target`() {
        val engine = SimulationEngine()
        advance(engine, 2000)
        engine.sampleCadence(0.0, 2200)
        val paused = engine.evaluate(2200, 24, settings)
        assertEquals(SimulationAction.Release, paused.action)
        assertTrue(paused.resumeRequired)
        assertEquals(0.0, paused.load!!.watts, 0.0)
        assertEquals(140, paused.resumeTargetWatts)
        assertEquals(80.0, paused.previewCadenceRpm!!, 0.0)
    }

    @Test fun `auto resume requires feedback permission and never clears suspension`() {
        val engine = SimulationEngine()
        val auto = settings.copy(autoResumeAfterPause = true)
        advance(engine, 2000, auto)
        engine.sampleCadence(0.0, 2200)
        engine.evaluate(2200, 24, auto, automaticResumeAllowed = false)
        engine.sampleCadence(85.0, 2400)
        engine.sampleCadence(85.0, 2800)
        assertTrue(engine.evaluate(2800, 24, auto, automaticResumeAllowed = false).resumeRequired)
        assertEquals(SimulationAction.Target(20), engine.evaluate(2800, 24, auto, automaticResumeAllowed = true).action)
        engine.suspend("knob")
        engine.sampleCadence(85.0, 3000)
        assertEquals(SimulationPhase.Suspended, engine.evaluate(3000, 24, auto).phase)
    }
}
