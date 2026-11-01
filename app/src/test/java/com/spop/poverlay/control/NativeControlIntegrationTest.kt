package com.spop.poverlay.control

import com.spop.poverlay.erg.ErgMode
import com.spop.poverlay.erg.ErgPath
import com.spop.poverlay.sensor.v2.*
import com.spop.poverlay.sim.SimulationSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

class NativeControlIntegrationTest {
    private var now = 1000L
    private var resistance = 40
    private val native = FakeNative()
    private val writes = mutableListOf<Int>()
    private val control = BikeControl(true, { now }, { writes += it; resistance = it; true }, native)
    private fun sample(status: Int = 0, rpm: Int = 30, cadence: Float = 80f) {
        now += 200
        native.packet(now, status, rpm)
        control.acceptSample(BikeSample(100f, cadence, resistance, now))
        control.tick()
    }
    private fun startErg() {
        sample()
        assertTrue(control.localErg(200))
        control.tick() // Request the configuration probe echo.
        sample(rpm = 40)
        assertEquals(ErgPath.Native, control.state.value.ergPath)
        sample(PzafStatus.ENABLED_ACTIVE)
    }
    @After fun cleanup() { native.accept = true; control.close() }

    @Test fun autoSelectsNativeAndForwardsExactWattTargetsWithoutHostResistanceWrites() {
        startErg()
        assertTrue(writes.isEmpty())
        assertTrue(control.state.value.nativeAvailable)
        assertTrue(control.localErg(300))
        assertEquals("enable:300", native.commands.last())
        assertTrue(writes.isEmpty())
    }
    @Test fun explicitSoftwareSettingBypassesNativeProbeAndWattCommands() {
        sample(); control.setErgMode(ErgMode.Host)
        assertTrue(control.localErg(200)); control.tick()
        assertEquals(ErgPath.Host, control.state.value.ergPath)
        assertTrue(writes.isNotEmpty())
        assertTrue(native.commands.none { it.startsWith("enable:") || it.startsWith("rpm:") })
    }
    @Test fun nativeKnobDisableLatchesAcrossFreshTelemetryAndRemoteReset() {
        sample(); control.procedure("ble:a", byteArrayOf(0)); startErg()
        sample(PzafStatus.DISABLED_BY_KNOB)
        assertTrue(control.state.value.suspended)
        assertEquals("ble:a", control.state.value.permissionLostClient)
        val enables = native.commands.count { it.startsWith("enable:") }
        repeat(5) { sample() }
        assertEquals(1, control.procedure("ble:a", byteArrayOf(1)).result)
        assertEquals(5, control.procedure("ble:a", byteArrayOf(5, 200.toByte(), 0)).result)
        assertEquals(enables, native.commands.count { it.startsWith("enable:") })
        assertTrue(control.resumeLocally())
        assertTrue(control.localErg(200))
        assertEquals(enables + 1, native.commands.count { it.startsWith("enable:") })
    }
    @Test fun failedNativeReleaseBlocksManualTargetsAndResumeUntilConfirmedAccepted() {
        startErg(); native.accept = false
        assertFalse(control.localResistance(20))
        assertTrue(control.state.value.suspended)
        assertFalse(control.resumeLocally())
        assertTrue(writes.isEmpty())
        native.accept = true; sample()
        assertTrue(control.resumeLocally())
        assertTrue(control.localResistance(20))
        assertEquals(listOf(20), writes)
    }
    @Test fun nativeSimulationUsesSamePhysicsPersonalCapAndGentleRestart() {
        control.setSimulationSettings(SimulationSettings(enabled = true))
        sample(); assertTrue(control.localSimulation(20f))
        control.tick(); sample(rpm = 40)
        repeat(45) { sample(PzafStatus.ENABLED_ACTIVE) }
        assertFalse(control.state.value.suspended)
        assertEquals(ErgPath.Native, control.state.value.ergPath)
        assertEquals(140, control.state.value.simulationDecision!!.requestedWatts)
        val targets = native.commands.filter { it.startsWith("enable:") }.map { it.substringAfter(':').toInt() }
        assertEquals(20, targets.first())
        assertTrue(targets.all { it in 15..140 })
        sample(PzafStatus.ENABLED_ACTIVE, cadence = 0f)
        assertEquals(0, resistance)
        assertTrue(control.state.value.simulationDecision!!.resumeRequired)
        val count = native.commands.count { it.startsWith("enable:") }
        repeat(5) { sample() }
        assertEquals(count, native.commands.count { it.startsWith("enable:") })
    }

    private class FakeNative : TitanControl {
        override val status: Flow<TitanStatusPacket> = emptyFlow()
        override var latestStatusReading: TitanStatusReading? = null
        val commands = mutableListOf<String>()
        var accept = true
        private fun command(value: String): Boolean { commands += value; return accept }
        override fun setPowerZoneAutoFollow(watts: Int) = command("enable:$watts")
        override fun disablePowerZoneAutoFollow() = command("disable")
        override fun setPzafRampUpRate(rate: Int) = command("up:$rate")
        override fun setPzafRampDownRate(rate: Int) = command("down:$rate")
        override fun setPzafMaxResistance(percent: Int) = command("max:$percent")
        override fun setPzafMinUpdateRpm(rpm: Int) = command("rpm:$rpm")
        fun packet(time: Long, status: Int, rpm: Int) {
            val bytes = ByteBuffer.allocate(256)
            bytes.putInt(0, 0xDEADBEEF.toInt()); bytes.putInt(252, 0xCCDDEEFF.toInt())
            bytes.putShort(12, 5); bytes.putShort(14, 8)
            bytes.put(247, status.toByte()); bytes.put(251, rpm.toByte())
            latestStatusReading = TitanStatusReading(TitanStatusPacket.parse(bytes.array())!!, time)
        }
    }
}
