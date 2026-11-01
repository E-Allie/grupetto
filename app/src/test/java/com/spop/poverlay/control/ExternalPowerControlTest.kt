package com.spop.poverlay.control

import com.spop.poverlay.erg.ErgMode
import com.spop.poverlay.erg.ErgPath
import com.spop.poverlay.sensor.power.ExternalPowerReading
import com.spop.poverlay.sensor.v2.TitanControl
import com.spop.poverlay.sensor.v2.TitanStatusPacket
import com.spop.poverlay.sensor.v2.TitanStatusReading
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

class ExternalPowerControlTest {
    private val r = ControlRig()
    private val control get() = r.control
    private fun meter(watts: Int = 250, address: String = "a") =
        control.useExternalPower(ExternalPowerReading(watts, r.now, address))
    private fun advance(watts: Int = 250, power: Float = 100f, cadence: Float = 80f) {
        r.now += 200; meter(watts); r.sample(power, cadence, elapsed = 0)
    }
    @After fun cleanup() { control.close() }

    @Test fun externalPowerDrivesErgDespiteOppositeNativePower() {
        r.sample(); meter(); assertTrue(control.localErg(200)); control.tick()
        val prediction = r.resistance
        repeat(30) {
            advance()
            assertFalse("step=$it state=${control.state.value}", control.state.value.suspended)
        }
        assertTrue(r.resistance < prediction) // Native 100 W would increase resistance.
        assertEquals(ControlMode.Erg, control.state.value.mode)
        assertEquals(ErgPath.Host, control.state.value.ergPath)
        assertTrue(control.state.value.externalPowerActive)
    }
    @Test fun oneHertzExternalMeterAndFasterBikeFeedbackStayActive() {
        r.sample(); meter(); control.localErg(200); control.tick()
        repeat(30) { step ->
            r.now += 200
            if (step % 5 == 0) meter()
            r.sample(elapsed = 0)
            assertFalse("step=$step state=${control.state.value}", control.state.value.suspended)
            assertEquals(ErgPath.Host, control.state.value.ergPath)
        }
    }
    @Test fun freshBikePollsDoNotTurnOneMeterPacketIntoNewPowerCorrections() {
        r.sample(); meter(); control.localErg(200); control.tick()
        val before = r.writes.toList()
        repeat(14) { r.sample() }
        assertEquals(before, r.writes)
        assertFalse(control.state.value.suspended)
        r.sample(); r.sample()
        assertTrue(control.state.value.suspended)
        assertFalse(control.state.value.externalPowerActive)
    }
    @Test fun externalZeroIsNotMissingAndBikeCadenceStillGuardsMotor() {
        r.sample(cadence = 0f); meter(0); control.localErg(200)
        repeat(10) { advance(0, 400f, 0f) }
        assertTrue(r.writes.isEmpty())
        repeat(30) { advance(0, 400f) }
        assertTrue(r.resistance > 40)
        assertTrue(control.state.value.externalPowerActive)
    }
    @Test fun connectingSwitchingAndDisconnectingEachRequireExplicitErgResume() {
        r.sample(); control.localErg(200); meter()
        assertTrue(control.state.value.suspended)
        assertFalse(control.localErg(200))
        assertTrue(control.resumeLocally())
        meter(address = "b")
        assertTrue(control.state.value.suspended)
        assertTrue(control.resumeLocally())
        control.useExternalPower(null)
        assertTrue(control.state.value.suspended)
        assertFalse(control.localErg(200))
        assertTrue(r.writes.isEmpty())
    }
    @Test fun sameMeterFreshReadingsDoNotStopControl() {
        r.sample(); meter(); control.localErg(200)
        repeat(10) { advance() }
        assertFalse(control.state.value.suspended)
        assertEquals(ControlMode.Erg, control.state.value.mode)
        assertTrue(r.writes.isNotEmpty())
    }
    @Test fun staleMeterSuspendsWithFreshBikeDataAndDoesNotAutomaticallyResume() {
        r.sample(); meter(); control.localErg(200)
        r.now += 3001; r.sample(elapsed = 0)
        assertTrue(control.state.value.suspended)
        assertTrue(control.state.value.connected)
        assertFalse(control.state.value.externalPowerActive)
        meter(); r.sample()
        assertTrue(control.state.value.suspended)
        assertTrue(r.writes.isEmpty())
        assertTrue(control.resumeLocally())
        advance(); assertEquals(ErgPath.Host, control.state.value.ergPath)
    }
    @Test fun sourceLossAlsoLatchesPausedErgAndNotifiesPermissionLoss() {
        r.acquire(); meter(); r.command(5, 200, 0); r.command(8, 2)
        control.useExternalPower(null)
        assertEquals(1, control.state.value.permissionLostCount)
        assertEquals(5, r.command(7).result)
        assertTrue(control.state.value.suspended)
        assertEquals(ControlMode.Erg, control.state.value.mode)
        assertEquals(1, r.command(1).result)
        assertEquals(5, r.command(5, 200, 0).result)
    }
    @Test fun externalSourceChangesRequireExplicitSimulationResumeToo() {
        r.enableSim(); r.sample(); control.localSimulation(10f)
        meter(); assertTrue(control.state.value.suspended)
        assertEquals(ControlMode.Simulation, control.state.value.mode)
        assertFalse(control.localSimulation(10f))
        assertTrue(control.resumeLocally())
        meter(address = "b"); assertTrue(control.state.value.suspended)
        assertTrue(control.resumeLocally())
        control.useExternalPower(null); assertTrue(control.state.value.suspended)
        val before = r.writes.toList()
        repeat(10) { r.sample() }
        assertEquals(before, r.writes)
    }
    @Test fun externalSourceChangesDoNotInterruptExplicitResistanceMode() {
        r.sample(); control.localResistance(70); meter()
        assertFalse(control.state.value.suspended)
        assertEquals(ControlMode.Resistance, control.state.value.mode)
        control.useExternalPower(null)
        assertFalse(control.state.value.suspended)
        assertEquals(listOf(70), r.writes)
    }
    @Test fun staleAndFutureExternalReadingsNeverReplaceBikePower() {
        r.sample()
        control.useExternalPower(ExternalPowerReading(500, r.now - 3001, "old"))
        assertFalse(control.state.value.externalPowerActive)
        control.useExternalPower(ExternalPowerReading(500, r.now + 1, "future"))
        assertFalse(control.state.value.externalPowerActive)
        assertFalse(control.state.value.suspended)
    }
    @Test fun externalMeterForcesSoftwareDespiteExplicitNativePreference() {
        val native = FakeNative()
        val fakeWrites = mutableListOf<Int>()
        val c = BikeControl(true, { r.now }, { fakeWrites += it; true }, native)
        try {
            native.packet(r.now)
            c.acceptSample(BikeSample(100f, 80f, 40, r.now))
            c.setErgMode(ErgMode.Native)
            c.useExternalPower(ExternalPowerReading(200, r.now, "meter"))
            assertTrue(c.localErg(150))
            c.tick()
            assertEquals(ErgPath.Host, c.state.value.ergPath)
            assertTrue(fakeWrites.isNotEmpty())
            assertTrue(native.commands.none { it.startsWith("enable:") || it.startsWith("rpm:") })
        } finally { c.close() }
    }

    private class FakeNative : TitanControl {
        override val status: Flow<TitanStatusPacket> = emptyFlow()
        override var latestStatusReading: TitanStatusReading? = null
        val commands = mutableListOf<String>()
        private fun command(value: String): Boolean { commands += value; return true }
        override fun setPowerZoneAutoFollow(watts: Int) = command("enable:$watts")
        override fun disablePowerZoneAutoFollow() = command("disable")
        override fun setPzafRampUpRate(rate: Int) = command("up:$rate")
        override fun setPzafRampDownRate(rate: Int) = command("down:$rate")
        override fun setPzafMaxResistance(percent: Int) = command("max:$percent")
        override fun setPzafMinUpdateRpm(rpm: Int) = command("rpm:$rpm")
        fun packet(time: Long) {
            val bytes = ByteBuffer.allocate(256)
            bytes.putInt(0, 0xDEADBEEF.toInt()); bytes.putInt(252, 0xCCDDEEFF.toInt())
            bytes.putShort(12, 5); bytes.putShort(14, 8); bytes.put(251, 30)
            latestStatusReading = TitanStatusReading(TitanStatusPacket.parse(bytes.array())!!, time)
        }
    }
}
