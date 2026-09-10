package com.spop.poverlay.erg

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.coroutines.flow.emptyFlow
import com.spop.poverlay.sensor.interfaces.SensorInterface
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HostErgControllerTest {
    @Test fun `failed initial feedback suspends without issuing a brake command`() = runTest {
        val base = FakeControlSensor()
        val sensor = object : SensorInterface by base {
            override val requestedResistance = emptyFlow<Float>()
        }
        var reason: String? = null
        val host = HostErgController(sensor, onStandDown = { reason = it },
            coroutineContext = backgroundScope.coroutineContext)
        host.enable(150)
        runCurrent()
        assertFalse(host.isActive)
        assertEquals("Resistance feedback failed", reason)
        assertTrue(base.resistanceWrites.isEmpty())
    }

    @Test fun `external brake target stops host PID instead of fighting the rider`() = runTest {
        val sensor = FakeControlSensor()
        var reason: String? = null
        val host = HostErgController(sensor, onStandDown = { reason = it },
            coroutineContext = backgroundScope.coroutineContext, nowMs = { testScheduler.currentTime })
        host.enable(150)
        runCurrent()
        assertTrue(sensor.resistanceWrites.isNotEmpty())
        assertTrue(host.isActive)
        sensor.resistance.tryEmit(2f)
        runCurrent()
        val writesAtOverride = sensor.resistanceWrites.size
        advanceTimeBy(3000)
        runCurrent()
        assertFalse(host.isActive)
        assertNotNull(reason)
        assertEquals(writesAtOverride, sensor.resistanceWrites.size)
    }

    @Test fun `delayed echoes are accepted but a new external target is not`() {
        val tracker = ResistanceCommandTracker()
        tracker.reset(20, 0)
        tracker.commanded(45, 100)
        tracker.commanded(48, 200)
        assertFalse(tracker.isUnexpected(20, 300))
        assertFalse(tracker.isUnexpected(45, 400))
        assertFalse(tracker.isUnexpected(48, 2500))
        assertTrue(tracker.isUnexpected(2, 400))
        assertTrue(tracker.isUnexpected(20, 2500))
    }

    @Test fun `stop cancels future host resistance writes`() = runTest {
        val sensor = FakeControlSensor()
        val host = HostErgController(sensor, coroutineContext = backgroundScope.coroutineContext,
            nowMs = { testScheduler.currentTime })
        host.enable(150)
        runCurrent()
        host.disable()
        val before = sensor.resistanceWrites.size
        sensor.power.value = 0f
        advanceTimeBy(5000)
        runCurrent()
        assertEquals(before, sensor.resistanceWrites.size)
    }
}
