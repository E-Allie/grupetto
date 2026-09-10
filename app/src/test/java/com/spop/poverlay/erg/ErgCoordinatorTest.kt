package com.spop.poverlay.erg

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class ErgCoordinatorTest {
    @Test fun `failed native probe does not repeatedly stall host simulation targets`() = runTest {
        var probes = 0
        val host = FakePowerController()
        val controller = ErgCoordinator(FakeControlSensor(), { ErgMode.Auto }, host,
            backgroundScope.coroutineContext, { probes++; error("Binder unavailable") })
        runCurrent()
        assertEquals(ErgPath.Host, controller.preferredPath())
        assertTrue(controller.simulationTarget(50))
        runCurrent()
        controller.simulationTarget(100)
        runCurrent()
        assertEquals(listOf(50, 100), host.writes)
        assertEquals(1, probes)
    }

    @Test fun `failed actuator write suspends and refuses later targets`() = runTest {
        val host = FakePowerController().apply { beforeEnable = { error("write failed") } }
        val controller = ErgCoordinator(FakeControlSensor(), { ErgMode.Host }, host,
            backgroundScope.coroutineContext, { PzafCapability.Unsupported("host") })
        controller.enable(150)
        runCurrent()
        assertTrue(controller.state.value.suspended)
        assertFalse(controller.simulationTarget(250))
        assertFalse(controller.isActive)
    }

    @Test fun `stop invalidates a target waiting for capability resolution`() = runTest {
        val probe = CompletableDeferred<PzafCapability>()
        val host = FakePowerController()
        val controller = ErgCoordinator(FakeControlSensor(), { ErgMode.Auto }, host,
            backgroundScope.coroutineContext, { probe.await() })
        controller.enable(250)
        runCurrent()
        controller.disable()
        probe.complete(PzafCapability.Unsupported("old firmware"))
        runCurrent()
        assertTrue(host.writes.isEmpty())
        assertFalse(controller.isActive)
    }

    @Test fun `newest pending target replaces older values`() = runTest {
        val host = FakePowerController()
        val controller = ErgCoordinator(FakeControlSensor(), { ErgMode.Host }, host,
            backgroundScope.coroutineContext, { PzafCapability.Unsupported("old firmware") })
        controller.enable(100)
        controller.enable(200)
        runCurrent()
        assertEquals(listOf(200), host.writes)
    }

    @Test fun `native knob stand-down latches until deliberate resume`() = runTest {
        val native = FakeNativeControl()
        val controller = ErgCoordinator(FakeControlSensor(native), { ErgMode.Auto }, FakePowerController(),
            backgroundScope.coroutineContext, { PzafCapability.Supported("5.11") })
        controller.enable(150)
        runCurrent()
        native.standDown()
        runCurrent()
        assertTrue(controller.state.value.suspended)
        controller.enable(250)
        controller.setTarget(200)
        assertFalse(controller.simulationTarget(300))
        runCurrent()
        assertEquals(listOf(150), native.targets)
        controller.resume()
        runCurrent()
        assertEquals(listOf(150, 150), native.targets)
    }

    @Test fun `native arm timeout fires even with no new status packets`() = runTest {
        val native = FakeNativeControl().apply { echoEnable = false }
        val controller = ErgCoordinator(FakeControlSensor(native), { ErgMode.Native }, FakePowerController(),
            backgroundScope.coroutineContext, { PzafCapability.Supported("5.11") })
        controller.enable(150)
        runCurrent()
        advanceTimeBy(3000)
        runCurrent()
        assertTrue(controller.state.value.suspended)
        assertFalse(controller.isActive)
    }

    @Test fun `stop completes after an in-flight write and leaves control disabled`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        val host = FakePowerController().apply {
            beforeEnable = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        }
        val scope = SupervisorJob() + Dispatchers.Default
        val controller = ErgCoordinator(FakeControlSensor(), { ErgMode.Host }, host, scope,
            { PzafCapability.Unsupported("host") })
        try {
            controller.enable(150)
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val stopping = Thread { controller.disable(); stopped.countDown() }.apply { start() }
            assertFalse(stopped.await(100, TimeUnit.MILLISECONDS))
            release.countDown()
            assertTrue(stopped.await(5, TimeUnit.SECONDS))
            stopping.join(1000)
            assertFalse(host.isActive)
            assertFalse(controller.isActive)
        } finally { release.countDown(); scope.cancel() }
    }
}
