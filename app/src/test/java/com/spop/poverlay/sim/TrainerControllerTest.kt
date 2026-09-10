package com.spop.poverlay.sim

import com.spop.poverlay.erg.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TrainerControllerTest {
    @Test fun `opted in resume waits for fresh minimum feedback then restarts on steady cadence`() = runTest {
        val rig = Rig(this)
        rig.settings = rig.settings.copy(autoResumeAfterPause = true)
        runCurrent()
        rig.controller.command(client, byteArrayOf(0))
        rig.controller.command(client, sim)
        pedal(rig, 1200)
        rig.sensor.cadence.tryEmit(0f)
        runCurrent()
        advanceTimeBy(100); runCurrent()
        assertFalse(rig.power.isActive)
        pedal(rig, 1200)
        assertTrue(rig.power.isActive)
        assertEquals(TrainerMode.Simulation, rig.controller.state.value.mode)
    }

    @Test fun `external resistance while released prevents automatic reentry`() = runTest {
        val rig = Rig(this)
        rig.settings = rig.settings.copy(autoResumeAfterPause = true)
        runCurrent()
        rig.controller.command(client, byteArrayOf(0))
        rig.controller.command(client, sim)
        pedal(rig, 1200)
        rig.sensor.cadence.tryEmit(0f)
        runCurrent()
        advanceTimeBy(100); runCurrent()
        rig.sensor.resistance.tryEmit(25f)
        runCurrent()
        pedal(rig, 1200)
        assertFalse(rig.power.isActive)
        assertEquals("Resistance changed while SIM released", rig.controller.state.value.suspension)
        assertEquals(5, rig.controller.command(client, sim))
    }
    private val client = "dircon:rider"
    private val sim = byteArrayOf(17, 0, 0, -62, 1, 40, 51) // +4.5%
    private fun power(watts: Int) = byteArrayOf(5, watts.toByte(), (watts shr 8).toByte())

    private class Rig(scope: TestScope, native: Boolean = false) {
        val device = if (native) FakeNativeControl() else null
        val sensor = FakeControlSensor(device)
        val host = FakePowerController()
        var gear = 12
        var settings = SimulationSettings(enabled = true)
        val power = ErgCoordinator(sensor, { ErgMode.Auto }, host, scope.backgroundScope.coroutineContext,
            { if (native) PzafCapability.Supported("5.11") else PzafCapability.Unsupported("firmware 1.94") })
        val controller = TrainerController(sensor, power, { settings }, { gear },
            { scope.testScheduler.currentTime }, scope.backgroundScope.coroutineContext)
    }

    private suspend fun TestScope.pedal(rig: Rig, durationMs: Long) {
        repeat((durationMs / 200).toInt()) {
            advanceTimeBy(200)
            rig.sensor.cadence.tryEmit(85f)
            runCurrent()
        }
    }

    @Test fun `simulation works on both native and old firmware host fallback`() = runTest {
        for (native in listOf(false, true)) {
            val rig = Rig(this, native)
            runCurrent()
            assertEquals(1, rig.controller.command(client, byteArrayOf(0)))
            assertEquals(1, rig.controller.command(client, sim))
            pedal(rig, 1600)
            assertEquals(TrainerMode.Simulation, rig.controller.state.value.mode)
            assertEquals(if (native) ErgPath.Native else ErgPath.Host, rig.power.state.value.path)
            assertTrue(rig.power.isActive)
            val writes = rig.device?.targets ?: rig.host.writes
            assertTrue(writes.size >= 2)
            assertTrue(writes.last() > writes.first())
            rig.controller.closeSession()
        }
    }

    @Test fun `SIM ERG SIM mode changes cancel competing sources and gears are inert in ERG`() = runTest {
        val rig = Rig(this)
        runCurrent()
        rig.controller.command(client, byteArrayOf(0))
        rig.controller.command(client, sim)
        pedal(rig, 1200)
        rig.controller.command(client, power(250))
        runCurrent()
        val count = rig.host.writes.size
        rig.gear = 24
        pedal(rig, 1200)
        assertEquals(250, rig.power.targetWatts)
        assertEquals(count, rig.host.writes.size)
        assertEquals(TrainerMode.Erg, rig.controller.state.value.mode)
        rig.controller.command(client, sim)
        pedal(rig, 1200)
        assertEquals(TrainerMode.Simulation, rig.controller.state.value.mode)
        assertTrue(rig.power.targetWatts < 250) // Restarts through the bounded SIM ramp.
    }

    @Test fun `only owner can control and observer disconnect does not stop riding`() = runTest {
        val rig = Rig(this)
        runCurrent()
        assertEquals(5, rig.controller.command(client, power(150)))
        rig.controller.command(client, byteArrayOf(0))
        rig.controller.command(client, power(150))
        runCurrent()
        assertEquals(5, rig.controller.command("gatt:observer", byteArrayOf(0)))
        assertEquals(5, rig.controller.command("gatt:observer", power(200)))
        rig.controller.command(client, power(150))
        runCurrent()
        rig.controller.disconnected("gatt:observer")
        assertTrue(rig.power.isActive)
        rig.controller.disconnected(client)
        pedal(rig, 1200)
        assertFalse(rig.power.isActive)
        assertEquals(TrainerMode.Idle, rig.controller.state.value.mode)
    }

    @Test fun `idle discovery reservation cannot block MyWhoosh ERG or later Scatto control`() = runTest {
        for (native in listOf(false, true)) {
            val rig = Rig(this, native)
            runCurrent()
            val discovery = "dircon:idle-scatto"
            val mywhoosh = "dircon:mywhoosh"
            assertEquals(1, rig.controller.command(discovery, byteArrayOf(0)))
            assertFalse(rig.controller.state.value.controlEngaged)
            assertEquals(1, rig.controller.command(mywhoosh, byteArrayOf(0)))
            assertEquals(5, rig.controller.command(discovery, power(250)))
            assertEquals(1, rig.controller.command(mywhoosh, power(150)))
            runCurrent()
            assertTrue(rig.power.isActive)
            assertEquals(150, rig.power.targetWatts)
            assertEquals(5, rig.controller.command(discovery, byteArrayOf(0)))
            // Late closure of the replaced reservation must not stop this target.
            rig.controller.disconnected(discovery)
            assertTrue(rig.power.isActive)
            rig.controller.command(mywhoosh, byteArrayOf(8, 1))
            assertEquals(1, rig.controller.command(discovery, byteArrayOf(0)))
            assertEquals(1, rig.controller.command(discovery, power(250)))
            runCurrent()
            assertEquals(250, rig.power.targetWatts) // SIM's personal cap is irrelevant to ERG.
            rig.controller.closeSession()
        }
    }

    @Test fun `startup zero SIM parameters do not monopolize control or move the brake`() = runTest {
        val rig = Rig(this, native = true)
        runCurrent()
        rig.controller.command(client, byteArrayOf(0))
        rig.controller.command(client, byteArrayOf(1))
        assertEquals(1, rig.controller.command(client, byteArrayOf(17,0,0,0,0,0,0)))
        pedal(rig, 1200)
        assertFalse(rig.controller.state.value.controlEngaged)
        assertTrue(rig.sensor.resistanceWrites.isEmpty())
        assertTrue(rig.device!!.targets.isEmpty())
        assertEquals(1, rig.controller.command("dircon:scatto", byteArrayOf(0)))
        assertEquals(1, rig.controller.command("dircon:scatto", power(150)))
        runCurrent()
        assertEquals(listOf(150), rig.device.targets)
    }

    @Test fun `paused ERG and zero load during established SIM retain ownership`() = runTest {
        val rig = Rig(this)
        runCurrent()
        rig.controller.command(client, byteArrayOf(0))
        rig.controller.command(client, power(150))
        runCurrent()
        rig.controller.command(client, byteArrayOf(8,2))
        assertFalse(rig.power.isActive)
        assertEquals(5, rig.controller.command("observer", byteArrayOf(0)))
        rig.controller.command(client, byteArrayOf(7))
        runCurrent()
        assertEquals(150, rig.power.targetWatts)
        rig.controller.command(client, sim)
        pedal(rig, 1200)
        rig.controller.command(client, byteArrayOf(17,0,0,0,0,0,0))
        assertTrue(rig.controller.state.value.controlEngaged)
        assertEquals(5, rig.controller.command("observer", byteArrayOf(0)))
    }

    @Test fun `accepted power reserves control before async backend activation`() = runTest {
        val rig = Rig(this, native = true)
        rig.controller.command(client, byteArrayOf(0))
        rig.controller.command(client, power(150))
        assertFalse(rig.power.isActive)
        assertTrue(rig.controller.state.value.controlEngaged)
        assertEquals(5, rig.controller.command("observer", byteArrayOf(0)))
        runCurrent()
        assertTrue(rig.power.isActive)
    }

    @Test fun `pause resumes retained terrain but reset clears it`() = runTest {
        val rig = Rig(this)
        runCurrent()
        rig.controller.command(client, byteArrayOf(0))
        rig.controller.command(client, sim)
        pedal(rig, 1200)
        assertEquals(1, rig.controller.command(client, byteArrayOf(8, 2)))
        pedal(rig, 1200)
        assertFalse(rig.power.isActive)
        assertTrue(rig.controller.state.value.paused)
        rig.controller.command(client, byteArrayOf(7))
        pedal(rig, 1200)
        assertTrue(rig.power.isActive)
        rig.controller.command(client, byteArrayOf(1))
        rig.controller.command(client, byteArrayOf(7))
        pedal(rig, 1200)
        assertFalse(rig.power.isActive)
        assertEquals(TrainerMode.Idle, rig.controller.state.value.mode)
    }

    @Test fun `local stop cannot be undone by repeated targets reset or reconnection`() = runTest {
        val rig = Rig(this)
        rig.settings = rig.settings.copy(autoResumeAfterPause = true)
        runCurrent()
        rig.controller.command(client, byteArrayOf(0))
        rig.controller.command(client, power(150))
        runCurrent()
        rig.controller.toggle()
        assertEquals(5, rig.controller.command(client, power(250)))
        assertEquals(5, rig.controller.command(client, sim))
        rig.controller.command(client, byteArrayOf(1))
        rig.controller.disconnected(client)
        rig.controller.command("gatt:new", byteArrayOf(0))
        assertEquals(5, rig.controller.command("gatt:new", power(250)))
        rig.controller.toggle()
        assertEquals(1, rig.controller.command("gatt:new", power(250)))
        runCurrent()
        assertTrue(rig.power.isActive)
    }

    @Test fun `invalid commands do not disturb an active target`() = runTest {
        val rig = Rig(this)
        runCurrent()
        rig.controller.command(client, byteArrayOf(0))
        rig.controller.command(client, power(150))
        runCurrent()
        for (bytes in listOf(sim + byteArrayOf(0), sim.copyOf(6), byteArrayOf(8), byteArrayOf(8, 9), power(-1), power(801))) {
            assertEquals(3, rig.controller.command(client, bytes))
            assertTrue(rig.power.isActive)
            assertEquals(TrainerMode.Erg, rig.controller.state.value.mode)
        }
        rig.settings = rig.settings.copy(enabled = false)
        assertEquals(2, rig.controller.command(client, sim))
        assertEquals(150, rig.power.targetWatts)
    }

    @Test fun `turning off terrain control stops SIM and later cadence does not rearm`() = runTest {
        val rig = Rig(this)
        runCurrent()
        rig.controller.command(client, byteArrayOf(0))
        rig.controller.command(client, sim)
        pedal(rig, 1200)
        rig.settings = rig.settings.copy(enabled = false)
        pedal(rig, 1200)
        assertFalse(rig.power.isActive)
        assertEquals(TrainerMode.Idle, rig.controller.state.value.mode)
        rig.settings = rig.settings.copy(enabled = true)
        pedal(rig, 1200)
        assertFalse(rig.power.isActive)
    }
}
