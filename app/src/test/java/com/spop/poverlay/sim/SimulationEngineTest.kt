package com.spop.poverlay.sim

import org.junit.Assert.*
import org.junit.Test

class SimulationEngineTest {
    private val settings = SimulationSettings(gearing = GearProfile.Road, maxSimWatts = 1000)
    private val flat = SimulationParameters(0.0, 0.0, 0.004, 0.51)

    private fun start(engine: SimulationEngine, terrain: SimulationParameters = flat): SimulationDecision {
        engine.sampleCadence(85.0, 0)
        engine.begin(terrain, 0)
        engine.evaluate(0, 12, settings)
        engine.sampleCadence(85.0, 400)
        return engine.evaluate(400, 12, settings)
    }

    @Test fun `targets ramp toward newest demand without a backlog`() {
        val engine = SimulationEngine()
        assertEquals(SimulationAction.Target(20), start(engine).action)
        engine.sampleCadence(85.0, 600)
        assertEquals("rate limit", engine.evaluate(600, 24, settings).reason)
        engine.sampleCadence(85.0, 900)
        // The new lower gear supersedes the tall-gear target, without walking through it.
        assertEquals(SimulationAction.Target(24), engine.evaluate(900, 1, settings).action)
        engine.sampleCadence(85.0, 1400)
        assertEquals("deadband", engine.evaluate(1400, 1, settings).reason)
    }

    @Test fun `cadence collapse promptly releases even during the write interval`() {
        val engine = SimulationEngine()
        start(engine, flat.copy(gradePercent = 10.0))
        engine.sampleCadence(10.0, 450)
        assertEquals(SimulationAction.Release, engine.evaluate(450, 24, settings).action)
        engine.sampleCadence(0.0, 650)
        assertEquals(SimulationAction.None, engine.evaluate(650, 24, settings).action)
        engine.sampleCadence(85.0, 850)
        engine.sampleCadence(85.0, 1250)
        assertTrue(engine.evaluate(1250, 24, settings).resumeRequired)
        engine.resume(1250)
        assertEquals(SimulationAction.Target(20), engine.evaluate(1250, 24, settings).action)
    }

    @Test fun `sensor loss suspends and fresh data cannot automatically rearm`() {
        val engine = SimulationEngine()
        start(engine)
        assertEquals(SimulationAction.Disable, engine.evaluate(1400, 12, settings).action)
        engine.sampleCadence(85.0, 1500)
        engine.updateTerrain(flat.copy(gradePercent = 5.0))
        assertEquals(SimulationPhase.Suspended, engine.evaluate(1500, 12, settings).phase)
        assertEquals(SimulationAction.None, engine.evaluate(1600, 12, settings).action)
    }

    @Test fun `stop discards targets and later samples do nothing`() {
        val engine = SimulationEngine()
        start(engine)
        engine.stop()
        engine.sampleCadence(100.0, 500)
        assertEquals(SimulationPhase.Inactive, engine.evaluate(500, 24, settings).phase)
        engine.resume(500)
        assertEquals(SimulationAction.None, engine.evaluate(500, 24, settings).action)
    }

    @Test fun `descent hysteresis uses unclamped power and honors zero coefficients`() {
        val engine = SimulationEngine(SimulationPolicy(automaticReentry = true))
        start(engine)
        engine.updateTerrain(flat.copy(gradePercent = -5.0))
        engine.sampleCadence(85.0, 600)
        assertEquals(SimulationAction.Release, engine.evaluate(600, 12, settings).action)
        // Lowest gear at -0.2% produces ~17.9 W: above minimum, below re-entry.
        engine.updateTerrain(flat.copy(gradePercent = -0.2))
        assertEquals(SimulationPhase.LowLoad, engine.evaluate(600, 1, settings).phase)
        engine.updateTerrain(flat)
        assertEquals(SimulationAction.Target(20), engine.evaluate(600, 1, settings).action)
        engine.updateTerrain(SimulationParameters(0.0, 0.0, 0.0, 0.0))
        assertEquals(SimulationAction.Release, engine.evaluate(600, 1, settings).action)
    }

    @Test fun `extreme climbing is capped and never emits outside native bounds`() {
        val engine = SimulationEngine()
        start(engine, flat.copy(gradePercent = 100.0))
        for (now in 600L..16000L step 200) {
            engine.sampleCadence(85.0, now)
            val decision = engine.evaluate(now, 24, settings)
            assertTrue(decision.capped)
            (decision.action as? SimulationAction.Target)?.let { assertTrue(it.watts in 15..800) }
        }
        assertEquals(800, engine.evaluate(16000, 24, settings).requestedWatts)
    }

    @Test fun `invalid and missing cadence cannot start a target`() {
        val engine = SimulationEngine()
        engine.begin(flat, 0)
        assertEquals(SimulationAction.Release, engine.evaluate(0, 12, settings).action)
        assertEquals(SimulationPhase.Suspended, engine.evaluate(1000, 12, settings).phase)
        engine.resume(1000)
        engine.sampleCadence(Double.NaN, 1000)
        assertEquals("Invalid cadence", engine.evaluate(1000, 12, settings).reason)
    }
}
